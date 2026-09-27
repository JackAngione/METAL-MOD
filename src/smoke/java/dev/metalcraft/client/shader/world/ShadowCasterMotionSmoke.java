package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Culling and batched mixed-layer draws must match drawing every caster in every cascade. */
final class ShadowCasterMotionSmoke {
    static void run(MetalDevice device, String contract, String source) {
        int size = 128;
        String compare = """
            #include <metal_stdlib>
            using namespace metal;
            struct V { float4 p [[position]]; };
            vertex V vs(uint id [[vertex_id]]) {
                return {float4(id==0?float2(-1,-1):(id==1?float2(3,-1):float2(-1,3)),0,1)};
            }
            fragment float4 fs(V v [[stage_in]],depth2d_array<float> a [[texture(5)]],
                    depth2d_array<float> b [[texture(6)]],sampler s [[sampler(5)]]) {
                bool same=true, covered=false;
                for(uint layer=0;layer<4;layer++) {
                    float da=a.sample(s,v.p.xy/128.0,layer),db=b.sample(s,v.p.xy/128.0,layer);
                    same = same && abs(da-db)<0.000001;
                    covered = covered || da<1.0;
                }
                return float4(same?0:1,covered?1:0,0,1);
            }
            """;
        var settings = new ShadowCascades.Settings(4, size, .05F, 96, .6F, 96);
        try (var queue = device.createCommandQueue();
             var renderer = new TerrainShadowRenderer(device, contract, source);
             var a = new WorldShadowModule(device, settings); var b = new WorldShadowModule(device, settings);
             var vertices = device.createBuffer(4 * 28, MetalBuffer.StorageMode.SHARED);
             var indices = device.createBuffer(6 * 4, MetalBuffer.StorageMode.SHARED);
             var atlas = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 1, 1, 1));
             var atlasView = atlas.createView();
             var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,
                 MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
             var output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, size, size, 1));
             var pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(compare, "vs", "fs", MetalTexture.Format.RGBA8_UNORM, null))) {
            try (var mapping = vertices.map()) {
                var bytes = mapping.bytes();
                for (int i = 0; i < bytes.capacity(); i++) bytes.put(i, (byte)0);
                for (int i = 0; i < 4; i++) {
                    bytes.putFloat(i * 28, i == 0 || i == 3 ? 0 : 16);
                    bytes.putFloat(i * 28 + 4, 8);
                    bytes.putFloat(i * 28 + 8, i < 2 ? 0 : 16);
                }
            }
            try (var mapping = indices.map()) { for (int i : new int[]{0,1,2,0,2,3}) mapping.bytes().putInt(i); }
            atlas.upload(queue, 0, ByteBuffer.allocateDirect(4).putInt(-1).flip());
            int changedMasks = 0;
            for (int step = 0; step < 24; step++) {
                var camera = new Vector3d(step * .45, 5, step * -.2);
                var rotation = new Quaternionf().rotateYXZ(step * .015F, -.1F, 0);
                var sun = new Vector3f(.3F, .7F, 0).normalize();
                try (var af = a.prepareFrame(camera, rotation, 1.2F, 1.6F, sun, new Matrix4f());
                     var bf = b.prepareFrame(camera, rotation, 1.2F, 1.6F, sun, new Matrix4f())) {
                    var all = new ArrayList<TerrainShadowRenderer.Draw>();
                    var culled = new ArrayList<TerrainShadowRenderer.Draw>();
                    for (int z = -10; z <= 4; z++) for (int x = -6; x <= 6; x++) {
                        float rx = (float)(x * 16 - camera.x), ry = (float)-camera.y, rz = (float)(z * 16 - camera.z);
                        var layer = (x + z) % 2 == 0 ? ChunkSectionLayer.SOLID : ChunkSectionLayer.CUTOUT;
                        int mask = bf.cascadeMask(rx - 1, ry - 1, rz - 1, rx + 17, ry + 17, rz + 17);
                        if (mask != 0 && mask != 15) changedMasks++;
                        all.add(new TerrainShadowRenderer.Draw(layer, vertices, 0, indices, 0, MetalRenderPass.IndexType.UINT32, 6, rx, ry, rz));
                        culled.add(new TerrainShadowRenderer.Draw(layer, vertices, 0, indices, 0, MetalRenderPass.IndexType.UINT32, 6, rx, ry, rz, mask));
                    }
                    try (var commands = queue.createCommandBuffer()) {
                        try (var pass = commands.beginRenderPass(a.depthPass())) { renderer.encode(pass, af, all, atlasView, sampler); }
                        try (var pass = commands.beginRenderPass(b.depthPass())) { renderer.encode(pass, bf, culled, atlasView, sampler); }
                        try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(output, 1, 0, 0, 1)))) {
                            pass.setPipeline(pipeline); af.bindDepth(pass, 5); bf.bindDepth(pass, 6);
                            pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
                        }
                        commands.commitAndWait();
                    }
                    var pixels = output.readback(queue, 0);
                    int coverage = 0;
                    for (int p = 0; p < size * size; p++) {
                        if (pixels.get(p * 4) != 0) throw new AssertionError("Moving cascade culling changed depth at step=" + step + ", pixel=" + p);
                        if (pixels.get(p * 4 + 1) != 0) coverage++;
                    }
                    if (coverage < 100) throw new AssertionError("Motion culling fixture is empty");
                }
            }
            if (changedMasks < 100) throw new AssertionError("Motion fixture did not exercise sparse cascades");
        }
        System.out.println("Shadow motion culling: all cascade texels match unculled mixed-layer draws over 24 moving frames");
    }
}
