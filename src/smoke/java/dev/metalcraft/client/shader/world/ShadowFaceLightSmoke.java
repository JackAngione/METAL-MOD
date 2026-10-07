package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.*;
import java.nio.ByteOrder;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/**
 * Block faces edge-on to or turned from the sun keep one visibility wherever their edge falls in
 * a cascade's texel grid; sun-facing faces stay lit and roofed faces keep their cast shadow.
 */
final class ShadowFaceLightSmoke {
    private static final int COLUMNS = 16;

    static void run(MetalDevice device, String contract) {
        String source = "#include <metal_stdlib>\nusing namespace metal;\n" + contract + """
            constant uint COLUMNS = 16u;
            // Row 0/1: open blocks in cascades 0/1. Row 2/3: the same with a roof in front of the south face.
            static float3 block_min(uint row, uint column) {
                float z = row == 0u ? -12.0 : row == 1u ? -40.0 : row == 2u ? -18.0 : -46.0;
                float c = float(column);
                return float3(-24.0 + c * 3.0 + c * 0.0371, -1.0 + c * 0.0219 + (row >= 2u ? 8.0 : 0.0), z + c * 0.0293);
            }
            static float hit_box(float3 o, float3 d, float3 lo, float3 hi) {
                float enter = 0.0, exit = 1.0;
                for (int a = 0; a < 3; ++a) {
                    if (abs(d[a]) < 1e-12) { if (o[a] < lo[a] || o[a] > hi[a]) return 1.0; continue; }
                    float t0 = (lo[a] - o[a]) / d[a], t1 = (hi[a] - o[a]) / d[a];
                    enter = max(enter, min(t0, t1)); exit = min(exit, max(t0, t1));
                }
                return enter <= exit ? enter : 1.0;
            }
            struct V { float4 p [[position]]; uint layer [[render_target_array_index]]; };
            vertex V vs(uint id [[vertex_id]], uint instance [[instance_id]]) {
                return {float4(id==0?float2(-1,-1):(id==1?float2(3,-1):float2(-1,3)),0,1),instance};
            }
            struct Depth { float value [[depth(any)]]; };
            fragment Depth depth_fs(V v [[stage_in]], constant MCShadowFrame& f [[buffer(0)]],
                    constant float4x4* inverse [[buffer(1)]]) {
                float2 ndc = v.p.xy * f.inverseResolution * 2.0 - 1.0;
                float4 a = inverse[v.layer] * float4(ndc, 0, 1), b = inverse[v.layer] * float4(ndc, 1, 1);
                float3 o = a.xyz / a.w, d = b.xyz / b.w - o;
                float depth = 1.0;
                for (uint row = 0u; row < 4u; ++row) for (uint column = 0u; column < COLUMNS; ++column) {
                    float3 lo = block_min(row, column);
                    depth = min(depth, hit_box(o, d, lo, lo + 1.0));
                    if (row >= 2u) depth = min(depth, hit_box(o, d, lo + float3(-2, 2, 1), lo + float3(1, 2.5, 3)));
                }
                return {depth};
            }
            struct S { float4 p [[position]]; };
            vertex S sample_vs(uint id [[vertex_id]]) {
                return {float4(id==0?float2(-1,-1):(id==1?float2(3,-1):float2(-1,3)),0,1)};
            }
            // x: column + 16 * row. y: south, east, west face centers.
            fragment float4 fs(S v [[stage_in]], constant MCShadowFrame& f [[buffer(0)]],
                    depth2d_array<float> map [[texture(5)]], sampler s [[sampler(5)]]) {
                uint index = uint(v.p.x), face = uint(v.p.y);
                float3 lo = block_min(index / COLUMNS, index % COLUMNS);
                float3 n = face == 0u ? float3(0, 0, 1) : face == 1u ? float3(1, 0, 0) : float3(-1, 0, 0);
                float3 p = lo + (face == 0u ? float3(0.5, 0.5, 1.0) : face == 1u ? float3(1.0, 0.5, 0.5) : float3(0.0, 0.5, 0.5));
                float viewDepth = -p.z;
                float3 sun = f.directionToSun.xyz;
                float bias = mc_shadow_receiver_bias(n, viewDepth, f);
                float result = mc_shadow_face_light(n, sun)
                    * mc_shadow_visibility(p, viewDepth, bias, f, map, s, n, mc_shadow_face_grazing(n, sun));
                float previous = mc_shadow_visibility(p, viewDepth, bias, f, map, s, n);
                // East/west faces cross the light at noon: both weights must change gradually.
                bool continuous = true;
                for (int step = -20; step < 20; ++step) {
                    float2 a = float2(sin(step * 0.005f), sin((step + 1) * 0.005f));
                    float3 l0 = float3(-a.x, sqrt(1.0 - a.x * a.x), 0), l1 = float3(-a.y, sqrt(1.0 - a.y * a.y), 0);
                    continuous = continuous
                        && abs(mc_shadow_face_light(float3(1, 0, 0), l0) - mc_shadow_face_light(float3(1, 0, 0), l1)) < 0.05
                        && abs(mc_shadow_face_grazing(float3(1, 0, 0), l0) - mc_shadow_face_grazing(float3(1, 0, 0), l1)) < 0.1;
                }
                continuous = continuous && mc_shadow_face_grazing(float3(0, 1, 0), float3(1, 0, 0)) == 0.0
                    && mc_shadow_face_light(float3(0, 1, 0), float3(0)) == 1.0;
                return float4(result, previous, continuous ? 1.0 : 0.0, 1.0);
            }
            """;
        var settings = new ShadowCascades.Settings(4, 1024, .05F, 256, .6F, 256);
        // Afternoon light in Minecraft's XY orbit: north/south faces are edge-on, east faces turn away.
        var sun = new Vector3f(-0.506F, 0.862F, 0).normalize();
        try (var queue = device.createCommandQueue();
             var inverses = device.createBuffer(4 * 64, MetalBuffer.StorageMode.SHARED);
             var color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, COLUMNS * 4, 3, 1));
             var depth = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source, "vs", source, "depth_fs",
                 List.of(MetalRenderPipeline.ColorTarget.unused()), MetalTexture.Format.DEPTH32_FLOAT, MetalRenderPipeline.VertexDescriptor.EMPTY,
                 new MetalRenderPipeline.DepthState(true, true, MetalRenderPipeline.CompareFunction.ALWAYS, 0, 0),
                 MetalRenderPipeline.RasterState.DEFAULT, MetalRenderPipeline.InputPrimitiveTopology.TRIANGLE));
             var sample = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source, "sample_vs", "fs", MetalTexture.Format.RGBA16_FLOAT, null));
             var module = new WorldShadowModule(device, settings);
             var frame = module.prepareFrame(new Vector3d(), new Quaternionf(), 1.2F, 1.777F, sun, new Matrix4f())) {
            var cascades = ShadowCascades.fit(settings, new Vector3d(), new Quaternionf(), 1.2F, 1.777F, sun);
            try (var mapping = inverses.map()) {
                for (int i = 0; i < cascades.size(); i++) new Matrix4f(cascades.get(i).cameraRelativeToShadow()).invert().get(i * 64, mapping.bytes());
            }
            try (var commands = queue.createCommandBuffer()) {
                try (var pass = commands.beginRenderPass(module.depthPass())) {
                    pass.setPipeline(depth);
                    frame.bindUniforms(pass, 0, MetalRenderPass.STAGE_FRAGMENT);
                    pass.setUniformBuffer(1, inverses, 0, MetalRenderPass.STAGE_FRAGMENT);
                    pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 4, 0);
                }
                try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(color, 0, 0, 0, 1)))) {
                    pass.setPipeline(sample);
                    frame.bindUniforms(pass, 0, MetalRenderPass.STAGE_FRAGMENT);
                    frame.bindDepth(pass, 5);
                    pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
                }
                commands.commitAndWait();
            }
            var pixels = color.readback(queue, 0).order(ByteOrder.nativeOrder());
            float openMin = 1, openMax = 0, previousMin = 1, previousMax = 0, east = 0, west = 1, roofed = 0;
            for (int face = 0; face < 3; face++) for (int index = 0; index < COLUMNS * 4; index++) {
                int offset = (face * COLUMNS * 4 + index) * 8;
                float value = Float.float16ToFloat(pixels.getShort(offset));
                float previous = Float.float16ToFloat(pixels.getShort(offset + 2));
                if (Float.float16ToFloat(pixels.getShort(offset + 4)) != 1) throw new AssertionError("Face light weights are discontinuous");
                boolean open = index < COLUMNS * 2;
                if (face == 0 && open) {
                    openMin = Math.min(openMin, value); openMax = Math.max(openMax, value);
                    previousMin = Math.min(previousMin, previous); previousMax = Math.max(previousMax, previous);
                } else if (face == 0) roofed = Math.max(roofed, value);
                else if (face == 1) east = Math.max(east, value);
                else if (open) west = Math.min(west, value);
            }
            System.out.println("Shadow face light: edge-on open=" + openMin + ".." + openMax + " (previous " + previousMin + ".." + previousMax
                + "), roofed<=" + roofed + ", turned-away<=" + east + ", sun-facing>=" + west);
            if (openMin < 0.49F || openMax > 0.51F) throw new AssertionError("Edge-on face visibility depends on texel alignment: " + openMin + ".." + openMax);
            if (roofed > 0.02F) throw new AssertionError("Edge-on face lost its cast shadow: " + roofed);
            if (east > 0.001F) throw new AssertionError("Face turned from the sun received sunlight: " + east);
            if (west < 0.99F) throw new AssertionError("Sun-facing face self-shadowed: " + west);
        }
    }
}
