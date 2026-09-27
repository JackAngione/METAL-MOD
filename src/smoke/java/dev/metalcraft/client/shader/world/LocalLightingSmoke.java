package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Executes the production Metal visibility/composition helpers against independent fixtures. */
public final class LocalLightingSmoke {
    public static void main(String[] args) throws Exception {
        try (var device = MetalNative.openDefaultDevice().orElseThrow()) { run(device); }
    }

    public static void run(MetalDevice device) throws Exception {
        checkClusters();
        String source = "#include <metal_stdlib>\nusing namespace metal;\n#define MC_OPTION_LOCAL_LIGHTS 1\n#define MC_PASS_RESOLVE 1\n";
        for (String name : List.of("shadows", "lighting", "local_lights")) {
            try (var input = LocalLightingSmoke.class.getResourceAsStream("/assets/metalcraft/shaderpacks/standard/shared/" + name + ".metal")) {
                source += new String(input.readAllBytes(), StandardCharsets.UTF_8) + "\n";
            }
        }
        source += """
            struct V { float4 position [[position]]; };
            vertex V vs(uint id [[vertex_id]]) {
                float2 p = id == 0 ? float2(-1,-1) : (id == 1 ? float2(3,-1) : float2(-1,3));
                return {float4(p,0,1)};
            }
            constant McFog fixtureFog = {float4(0), 1e10,1e10,1e10,1e10,1e10,1e10};
            fragment float4 fs(V in [[stage_in]], device const uint *scene [[buffer(0)]],
                constant MCLocalFrame &frame [[buffer(1)]], device const MCLocalLight *lights [[buffer(2)]],
                device const uint *clusters [[buffer(3)]]) {
                uint test = uint(in.position.x);
                float3 receiver = float3(50.5, 50.25, 50.5);
                MCLocalLight light = {float4(58.5, 50.25, 50.5, 15), uint4(0)};
                if (test == 1) { receiver.y = 50.75; light.position.y = 50.75; }
                if (test == 2) { receiver.z = 51.5; light.position.z = 51.5; }
                if (test == 3) { receiver = float3(58.5,50.25,50.5); light.position.xyz = float3(50.5,50.25,50.5); }
                if (test == 4) { receiver = float3(54.5,50.75,50.5); light.position.xyz = float3(54.5,50.5,50.5); }
                if (test == 5) { receiver.z = 52.5; light.position.z = 52.5; }
                if (test == 6) { receiver.z = 52.5; light.position.z = 52.5; light.flags.x = 1; }
                if (test < 7) return float4(mc_local_visibility(receiver, light, frame.counts.x, scene),0,0,1);
                if (test == 7) {
                    float2 lightResult = mc_local_lighting(receiver - frame.camera.xyz, float3(0,1,0), frame, scene, lights, clusters, lights);
                    return float4(lightResult,0,1);
                }
                float2 local = test == 8 ? float2(0,0) : float2(1,1);
                return mc_compose_local_lighting(float4(0.5,0.5,0.5,1), float4(1,1,1,1.0/255.0),
                    float2(1,0), float3(0), local, fixtureFog);
            }
            """;
        int[] cells = new int[LocalLightVolume.CELLS + 4];
        cells[LocalLightVolume.CELLS] = 1;
        cells[LocalLightVolume.CELLS + 1] = LocalLightVolume.FULL_CUBE;
        cells[LocalLightVolume.CELLS + 2] = 1;
        cells[LocalLightVolume.CELLS + 3] = LocalLightVolume.box(0, 0, 0, 1, 0.5, 1);
        cells[LocalLightVolume.index(54, 50, 50)] = LocalLightVolume.CELLS + 2;
        cells[LocalLightVolume.index(58, 50, 52)] = LocalLightVolume.CELLS;
        List<LocalLightVolume.Light> sources = List.of(new LocalLightVolume.Light(58.5F, 50.25F, 50.5F, 15, false));
        try (var queue = device.createCommandQueue();
             var scene = upload(device, cells);
             var grid = upload(device, LocalLightVolume.clusters(sources));
             var frame = device.createBuffer(32, MetalBuffer.StorageMode.SHARED);
             var lights = device.createBuffer(32, MetalBuffer.StorageMode.SHARED);
             var target = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 10, 1, 1));
             var pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source, "vs", source, "fs",
                 List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT)), null,
                 MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT))) {
            try (var mapping = frame.map()) {
                mapping.bytes().putFloat(50).putFloat(50).putFloat(50).putFloat(32).putInt(LocalLightVolume.SIZE).putInt(1).putInt(0).putInt(1);
            }
            try (var mapping = lights.map()) { sources.getFirst().write(mapping.bytes()); }
            try (var commands = queue.createCommandBuffer()) {
                try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(target, 0, 0, 0, 0)))) {
                    pass.setPipeline(pipeline);
                    pass.setUniformBuffer(0, scene, 0, MetalRenderPass.STAGE_FRAGMENT);
                    pass.setUniformBuffer(1, frame, 0, MetalRenderPass.STAGE_FRAGMENT);
                    pass.setUniformBuffer(2, lights, 0, MetalRenderPass.STAGE_FRAGMENT);
                    pass.setUniformBuffer(3, grid, 0, MetalRenderPass.STAGE_FRAGMENT);
                    pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
                }
                commands.commitAndWait();
            }
            ByteBuffer pixels = target.readback(queue, 0).order(ByteOrder.nativeOrder());
            float[] expected = {0, 1, 1, 0, 1, 1, 0, 0, 0.1F, 1.6F};
            for (int i = 0; i < expected.length; i++) {
                float actual = Float.float16ToFloat(pixels.getShort(i * 8));
                if (!Float.isFinite(actual) || Math.abs(actual - expected[i]) > 0.003)
                    throw new AssertionError("Local light fixture " + i + ": expected " + expected[i] + ", got " + actual);
            }
        }
        System.out.println("Local lighting: uncapped spatial lists, block/slab shadows, axis/reverse rays, source self-occlusion, moving-source occlusion, ambient retention and fire irradiance passed on Metal");
    }

    private static MetalBuffer upload(MetalDevice device, int[] data) {
        var buffer = device.createBuffer(data.length * 4L, MetalBuffer.StorageMode.SHARED);
        try (var mapping = buffer.map()) { mapping.bytes().asIntBuffer().put(data); }
        return buffer;
    }

    private static void checkClusters() {
        ArrayList<LocalLightVolume.Light> lights = new ArrayList<>();
        for (int i = 0; i < 300; i++) lights.add(new LocalLightVolume.Light(52 + (i % 4) * 0.1F, 52, 52, 15, false));
        int[] clusters = LocalLightVolume.clusters(lights);
        int cell = 6 + LocalLightVolume.CLUSTERS * (6 + LocalLightVolume.CLUSTERS * 6);
        if (clusters[cell * 2 + 1] != 300) throw new AssertionError("Spatial lighting silently dropped sources");
        float previous = Float.POSITIVE_INFINITY;
        for (int i = 0; i < 300; i++) {
            float bound = Float.intBitsToFloat(clusters[clusters[cell * 2] + i * 2 + 1]);
            if (bound > previous || bound < 0 || bound > 1) throw new AssertionError("Nonconservative source ordering");
            previous = bound;
        }
        if (LocalLightVolume.box(0,0,0,1,1,1) != LocalLightVolume.FULL_CUBE) throw new AssertionError("Shape packing ABI");
        // Independently verify that section-phase changes and the outer source halo never
        // remove a light that can reach an eligible receiver, or underestimate its bound.
        var random = new java.util.Random(374);
        lights.clear();
        for (int i = 0; i < 100; i++) lights.add(new LocalLightVolume.Light(random.nextFloat() * 112,
            random.nextFloat() * 112, random.nextFloat() * 112, 1 + random.nextInt(15), false));
        clusters = LocalLightVolume.clusters(lights);
        for (int phase = 0; phase < 8; phase++) for (int sample = 0; sample < 100; sample++) {
            float cx = (phase & 1) == 0 ? 48 : 63.99F;
            float cy = (phase & 2) == 0 ? 48 : 63.99F;
            float cz = (phase & 4) == 0 ? 48 : 63.99F;
            var offset = new org.joml.Vector3f(random.nextFloat() - 0.5F, random.nextFloat() - 0.5F, random.nextFloat() - 0.5F)
                .normalize(31.95F * random.nextFloat());
            float x = cx + offset.x, y = cy + offset.y, z = cz + offset.z;
            cell = (int)x / 8 + LocalLightVolume.CLUSTERS * ((int)y / 8 + LocalLightVolume.CLUSTERS * ((int)z / 8));
            for (int lightIndex = 0; lightIndex < lights.size(); lightIndex++) {
                var light = lights.get(lightIndex);
                float dx = x - light.x(), dy = y - light.y(), dz = z - light.z();
                double energy = (light.level() - Math.sqrt(dx * dx + dy * dy + dz * dz)) / 15;
                if (energy <= 0) continue;
                boolean found = false;
                for (int i = 0; i < clusters[cell * 2 + 1]; i++) {
                    int entry = clusters[cell * 2] + i * 2;
                    if (clusters[entry] != lightIndex) continue;
                    if (Float.intBitsToFloat(clusters[entry + 1]) + 1e-6 < energy * energy)
                        throw new AssertionError("Spatial bound underestimated an influencing source");
                    found = true;
                    break;
                }
                if (!found) throw new AssertionError("Source halo lost a light at camera phase " + phase);
            }
        }
    }
}
