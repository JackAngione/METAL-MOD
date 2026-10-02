package dev.metalcraft.client.metal;

import java.nio.file.Files;
import java.util.List;

final class MetalPipelineCacheSmoke {
    private static final String SOURCE = """
        #include <metal_stdlib>
        using namespace metal;
        vertex float4 vs(uint id [[vertex_id]]) {
            float2 p = float2((id << 1) & 2, id & 2); return float4(p * 2 - 1, 0, 1);
        }
        fragment float4 fs() { return float4(1, 0, 0, 1); }
        """;

    static void run() {
        String previous = System.getProperty("metalcraft.pipelineCacheDir");
        try {
            var directory = Files.createTempDirectory("metalcraft-pipeline-cache-smoke-");
            System.setProperty("metalcraft.pipelineCacheDir", directory.toString());
            String glsl = "#version 450\nlayout(location=0) out vec4 c; void main(){ c=vec4(1,0,0,1); }";
            var first = MetalShaderTranslator.translate(glsl, MetalShaderTranslator.Stage.FRAGMENT, "cache.frag");
            long hits = MetalShaderTranslator.translationCacheHits();
            var repeated = MetalShaderTranslator.translate(glsl, MetalShaderTranslator.Stage.FRAGMENT, "cache.frag");
            var changed = MetalShaderTranslator.translate(glsl.replace("1,0,0,1", "0,0,1,1"), MetalShaderTranslator.Stage.FRAGMENT, "cache.frag");
            if (MetalShaderTranslator.translationCacheHits() != hits + 1 || !first.equals(repeated)
                || first.metalSource().equals(changed.metalSource())) throw new AssertionError("Translation cache reused stale content");
            byte[] copy = first.spirv(); copy[0] = 0;
            if (first.spirv()[0] != 3) throw new AssertionError("Cached SPIR-V is mutable through its public accessor");

            try (var device = MetalNative.openDefaultDevice().orElseThrow()) {
                draw(device, SOURCE, false, false);
                draw(device, SOURCE, true, false);
                long[] stats = MetalNative.nPipelineCacheStats(device.requireOpenHandle());
                if (stats[0] < 3 || stats[1] != 1 || stats[3] == 0)
                    throw new AssertionError("Depth variants recompiled identical libraries or failed to harvest binaries");
                draw(device, SOURCE.replace("float4(1, 0, 0, 1)", "float4(0, 0, 1, 1)"), false, true);
            }
            try (var device = MetalNative.openDefaultDevice().orElseThrow()) {
                draw(device, SOURCE, false, false);
                long[] stats = MetalNative.nPipelineCacheStats(device.requireOpenHandle());
                if (stats[4] != 1 || stats[2] == 0) throw new AssertionError("Saved pipeline archive did not produce a real cache hit");
            }
            try (var files = Files.list(directory)) {
                var archive = files.filter(path -> path.toString().endsWith(".metalarc")).findFirst().orElseThrow();
                Files.write(archive, new byte[]{1, 2, 3, 4});
            }
            try (var device = MetalNative.openDefaultDevice().orElseThrow()) {
                draw(device, SOURCE, false, false);
                if (MetalNative.nPipelineCacheStats(device.requireOpenHandle())[4] != 0)
                    throw new AssertionError("Corrupt archive was treated as valid");
            }
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        finally {
            if (previous == null) System.clearProperty("metalcraft.pipelineCacheDir");
            else System.setProperty("metalcraft.pipelineCacheDir", previous);
        }
        System.out.println("Metal compilation cache passed: content changes, depth variants, immutable translations, disk hits, corrupt archive fallback");
    }

    private static void draw(MetalDevice device, String source, boolean withDepth, boolean blue) {
        try (var pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source, "vs", source, "fs",
                 List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)),
                 withDepth ? MetalTexture.Format.DEPTH32_FLOAT : null, MetalRenderPipeline.VertexDescriptor.EMPTY,
                 MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT));
             var color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 4, 4, 1));
             var depth = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.DEPTH32_FLOAT, 4, 4, 1));
             var queue = device.createCommandQueue()) {
            try (var commands = queue.createCommandBuffer()) {
                try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
                    MetalRenderPass.ColorAttachment.clear(color, 0, 1, 0, 1), withDepth ? new MetalRenderPass.DepthAttachment(
                        depth, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 1) : null))) {
                    pass.setPipeline(pipeline);
                    pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
                }
                commands.commitAndWait();
            }
            var pixels = color.readback(queue, 0);
            for (int i = 0; i < 16; i++) {
                if ((pixels.get(i * 4 + (blue ? 2 : 0)) & 255) != 255 || pixels.get(i * 4 + (blue ? 0 : 2)) != 0)
                    throw new AssertionError("Native cache rendered stale shader content");
            }
        }
    }
}
