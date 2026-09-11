package dev.metalcraft.client.lod;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalSampler;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import java.nio.ByteBuffer;
import java.util.List;

/** Isolated Metal prototype. Not yet admitted to the world's opaque/composition contract. */
public final class LodMetalGeometry implements AutoCloseable {
    private static final int STRIDE = 40;
    private static final int VERTEX_SLOT = 16;
    private final MetalRenderPipeline pipeline;

    /** Baked face appearance; the world adapter must supply verified atlas rectangles and lighting. */
    public record Appearance(float minU, float minV, float maxU, float maxV, int rgba) {
        public Appearance {
            if (!Float.isFinite(minU) || !Float.isFinite(minV) || !Float.isFinite(maxU) || !Float.isFinite(maxV)
                    || minU < 0 || minV < 0 || maxU > 1 || maxV > 1 || maxU <= minU || maxV <= minV) {
                throw new IllegalArgumentException("Invalid atlas rectangle");
            }
        }
    }
    @FunctionalInterface public interface Appearances { Appearance face(TerrainSnapshot.Material material, int axis, int sign); }

    public static final class Mesh implements AutoCloseable {
        private final MetalBuffer vertices;
        private final int count;
        private Mesh(MetalBuffer vertices, int count) { this.vertices = vertices; this.count = count; }
        public long bytes() { return (long)count * STRIDE; }
        @Override public void close() { vertices.close(); }
    }

    private static final String SOURCE = """
        #include <metal_stdlib>
        using namespace metal;
        struct In {
            float3 position [[attribute(0)]];
            float2 tileUV [[attribute(1)]];
            float4 atlasRect [[attribute(2)]];
            float4 color [[attribute(3)]];
        };
        struct Out {
            float4 position [[position]];
            float2 tileUV;
            float4 atlasRect [[flat]];
            float4 color [[flat]];
        };
        vertex Out lod_vertex(In v [[stage_in]], constant float4x4& clipFromSection [[buffer(0)]]) {
            return {clipFromSection * float4(v.position, 1), v.tileUV, v.atlasRect, v.color};
        }
        fragment float4 lod_fragment(Out v [[stage_in]], texture2d<float> atlas [[texture(0)]], sampler s [[sampler(0)]]) {
            float2 extent = float2(atlas.get_width(), atlas.get_height());
            float2 tileSize = (v.atlasRect.zw - v.atlasRect.xy) * extent;
            float2 dx = dfdx(v.tileUV) * tileSize;
            float2 dy = dfdy(v.tileUV) * tileSize;
            float footprint = max(max(length(dx), length(dy)), 1.0f);
            float maxLod = min(float(atlas.get_num_mip_levels() - 1), floor(log2(max(min(tileSize.x, tileSize.y), 1.0f))));
            float lod = clamp(floor(log2(footprint)), 0.0f, maxLod);
            // Clamp the footprint inside this tile at the chosen mip. A neighboring atlas
            // sprite must never leak across a large merged face or a block-repeat boundary.
            float2 inset = min(0.5f * exp2(lod) / extent, (v.atlasRect.zw - v.atlasRect.xy) * 0.5f);
            float2 uv = mix(v.atlasRect.xy, v.atlasRect.zw, fract(v.tileUV));
            uv = clamp(uv, v.atlasRect.xy + inset, v.atlasRect.zw - inset);
            return atlas.sample(s, uv, level(lod)) * v.color;
        }
        """;

    public LodMetalGeometry(MetalDevice device, MetalTexture.Format colorFormat) {
        var attributes = List.of(
                new MetalRenderPipeline.VertexAttribute(0,VERTEX_SLOT,0,MetalRenderPipeline.VertexAttributeFormat.FLOAT3),
                new MetalRenderPipeline.VertexAttribute(1,VERTEX_SLOT,12,MetalRenderPipeline.VertexAttributeFormat.FLOAT2),
                new MetalRenderPipeline.VertexAttribute(2,VERTEX_SLOT,20,MetalRenderPipeline.VertexAttributeFormat.FLOAT4),
                new MetalRenderPipeline.VertexAttribute(3,VERTEX_SLOT,36,MetalRenderPipeline.VertexAttributeFormat.UCHAR4_NORMALIZED));
        var descriptor = new MetalRenderPipeline.VertexDescriptor(attributes, List.of(new MetalRenderPipeline.VertexBufferLayout(VERTEX_SLOT,STRIDE,0)));
        pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(SOURCE,"lod_vertex",SOURCE,"lod_fragment",
                List.of(MetalRenderPipeline.ColorTarget.opaque(colorFormat)), MetalTexture.Format.DEPTH32_FLOAT, descriptor,
                new MetalRenderPipeline.DepthState(true,true,MetalRenderPipeline.CompareFunction.GREATER_EQUAL,0,0),
                MetalRenderPipeline.RasterState.DEFAULT, MetalRenderPipeline.InputPrimitiveTopology.TRIANGLE));
    }

    /** Admission must happen before allocation. Ordinary geometry remains owner when it cannot fit. */
    public static Mesh upload(MetalDevice device, TerrainHierarchy.Mesh source, Appearances appearances, long allowanceBytes) {
        if (!source.supported() || source.faces().isEmpty()) throw new IllegalArgumentException("No supported geometry");
        long bytes = Math.multiplyExact((long)source.faces().size(), 6L * STRIDE);
        if (bytes > allowanceBytes) throw new IllegalArgumentException("Mesh exceeds upload allowance");
        MetalBuffer buffer = device.createBuffer(bytes, MetalBuffer.StorageMode.SHARED);
        try {
            try (var mapping = buffer.map()) {
                ByteBuffer output = mapping.bytes();
                for (var face : source.faces()) {
                    Appearance appearance = appearances.face(face.material(),face.axis(),face.sign());
                    int[] order = face.sign() > 0 ? new int[]{0,1,2,0,2,3} : new int[]{0,3,2,0,2,1};
                    for (int corner : order) {
                        float u = face.u() + (corner == 1 || corner == 2 ? face.width() : 0);
                        float v = face.v() + (corner >= 2 ? face.height() : 0);
                        float x = face.axis() == 0 ? face.plane() : face.axis() == 1 ? v : u;
                        float y = face.axis() == 0 ? u : face.axis() == 1 ? face.plane() : v;
                        float z = face.axis() == 0 ? v : face.axis() == 1 ? u : face.plane();
                        output.putFloat(x).putFloat(y).putFloat(z).putFloat(u).putFloat(v);
                        output.putFloat(appearance.minU()).putFloat(appearance.minV()).putFloat(appearance.maxU()).putFloat(appearance.maxV());
                        output.put((byte)(appearance.rgba() >>> 24)).put((byte)(appearance.rgba() >>> 16))
                                .put((byte)(appearance.rgba() >>> 8)).put((byte)appearance.rgba());
                    }
                }
            }
            return new Mesh(buffer, Math.multiplyExact(source.faces().size(), 6));
        } catch (RuntimeException | Error error) {
            buffer.close();
            throw error;
        }
    }

    public void encode(MetalRenderPass pass, Mesh mesh, MetalBuffer clipFromSection,
            MetalTextureView atlas, MetalSampler sampler) {
        pass.setPipeline(pipeline);
        pass.setVertexBuffer(VERTEX_SLOT,mesh.vertices,0);
        pass.setUniformBuffer(0,clipFromSection,0,MetalRenderPass.STAGE_VERTEX);
        pass.setTexture(0,atlas,MetalRenderPass.STAGE_FRAGMENT);
        pass.setSampler(0,sampler,MetalRenderPass.STAGE_FRAGMENT);
        pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,mesh.count);
    }

    @Override public void close() { pipeline.close(); }
}
