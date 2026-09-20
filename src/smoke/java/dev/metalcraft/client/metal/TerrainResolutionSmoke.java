package dev.metalcraft.client.metal;

import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;

/** GPU evidence: actual target extents and pixel reuse, exact scene depth, discontinuity fallback. */
final class TerrainResolutionSmoke {
    private static final String VERTEX = """
        #version 450
        layout(location=0) out vec2 uv;
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            uv = p;
            gl_Position = vec4(p * 2.0 - 1.0, 0.2 + p.x * 0.2, 1.0);
        }
        """;
    private static final String FRAGMENT = """
        #version 450
        layout(location=0) in vec2 uv;
        layout(location=0) out vec4 fragColor;
        void main() {
            fragColor = vec4(uv, 0.25, 1.0);
        }
        """;

    static void run(MetalDevice device) {
        var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), LinearWorldShadersSmoke::expanded);
        try {
            var p = gpu.terrainResolutionPipeline(RenderPipelines.SOLID_TERRAIN);
            check(p != null && p.isValid(), "actual vanilla terrain reconstructs");
            var ordinary = (MetalCompiledRenderPipeline)gpu.precompilePipeline(RenderPipelines.SOLID_TERRAIN,null);
            check(p.sharesTerrainBindings(ordinary), "resolution variants share existing binding stages");
            check(p == gpu.terrainResolutionPipeline(RenderPipelines.SOLID_TERRAIN), "pipeline reuse");
            check(gpu.terrainResolutionPipeline(RenderPipelines.TRANSLUCENT_TERRAIN) == null, "translucent fallback");
        } finally { gpu.close(); }
        try (var queue = device.createCommandQueue(); var targets = new MetalTerrainResolution(device);
             var nativePipeline = pipeline(device, FRAGMENT);
             var reconstruction = pipeline(device, MetalTerrainResolution.reconstructFragment(FRAGMENT))) {
            for (int[] size : new int[][]{{32,16}, {33,17}, {1,1}}) for (int tier : new int[]{1,2}) {
                int width = size[0], height = size[1];
                var band = targets.band(tier, width, height, MetalTexture.Format.RGBA8_UNORM);
                check(band == targets.band(tier, width, height, MetalTexture.Format.RGBA8_UNORM), "target reuse");
                int scale = 1 << tier;
                check(band.width == (width + scale - 1) / scale && band.height == (height + scale - 1) / scale, "actual reduced extents");
                try (var color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, width,height,1));
                     var depth = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.DEPTH32_FLOAT, width,height,1))) {
                    for (int mode : new int[]{0,1,2}) {
                        boolean coarsePresent = mode == 0;
                        var coarseDescriptor = mode == 2 ? new MetalRenderPass.Descriptor(
                            MetalRenderPass.ColorAttachment.clear(band.color,0,0,0,0),
                            new MetalRenderPass.DepthAttachment(band.depth,MetalRenderPass.LoadAction.CLEAR,MetalRenderPass.StoreAction.STORE,0.8))
                            : band.descriptor;
                        try (var commands = queue.createCommandBuffer()) {
                            try (var pass = commands.beginRenderPass(coarseDescriptor)) {
                                if (coarsePresent) { pass.setPipeline(nativePipeline); pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3); }
                            }
                            try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
                                    MetalRenderPass.ColorAttachment.clear(color,0,0,0,0),
                                    new MetalRenderPass.DepthAttachment(depth,MetalRenderPass.LoadAction.CLEAR,MetalRenderPass.StoreAction.STORE,0)))) {
                                pass.setPipeline(reconstruction);
                                pass.setTexture(14,band.colorView,MetalRenderPass.STAGE_FRAGMENT);
                                pass.setTexture(15,band.depthView,MetalRenderPass.STAGE_FRAGMENT);
                                pass.setUniformBuffer(15,band.parameters,0,MetalRenderPass.STAGE_FRAGMENT);
                                pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3);
                            }
                            // Queue ownership must survive resize before GPU execution.
                            if (mode == 2) targets.band(tier,width+7,height+3,MetalTexture.Format.RGBA8_UNORM);
                            commands.commitAndWait();
                        }
                        var pixels = color.readback(queue,0);
                        var depths = depth.readback(queue,0).order(ByteOrder.nativeOrder());
                        for (int y=0;y<height;y++) for(int x=0;x<width;x++) {
                            float expectedX = coarsePresent ? ((int)((x+.5)*band.width/width)+.5f)/band.width : (x+.5f)/width;
                            float expectedY = coarsePresent ? ((int)((y+.5)*band.height/height)+.5f)/band.height : (y+.5f)/height;
                            int i = (y*width+x)*4;
                            check(Math.abs((pixels.get(i)&255)/255f-expectedX)<.006, "coarse color/fallback X " + x + "," + y);
                            check(Math.abs((pixels.get(i+1)&255)/255f-expectedY)<.006, "coarse color/fallback Y");
                            check(Math.abs(depths.getFloat(i)-(.2f+(x+.5f)/width*.2f))<1e-6, "full-resolution sloped depth");
                        }
                    }
                }
            }
            var retired = targets.band(1,32,16,MetalTexture.Format.RGBA8_UNORM).colorView;
            targets.band(1,35,19,MetalTexture.Format.RGBA8_UNORM);
            check(retired.isClosed(), "resize retires old storage");
        }
        System.out.println("Terrain resolution: vanilla compilation, half/quarter real targets, odd/tiny extents, coarse color reuse, exact sloped depth, missing/wrong-depth fallback and in-flight retirement passed");
    }
    private static MetalRenderPipeline pipeline(MetalDevice device, String fragment) {
        var shaders = MetalShaderTranslator.translatePipeline(VERTEX,"resolution.vsh",fragment,"resolution.fsh");
        return device.createRenderPipeline(new MetalRenderPipeline.Descriptor(shaders.vertex().metalSource(),shaders.vertex().entryPoint(),
            shaders.fragment().metalSource(),shaders.fragment().entryPoint(), List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)),
            MetalTexture.Format.DEPTH32_FLOAT, MetalRenderPipeline.VertexDescriptor.EMPTY,
            new MetalRenderPipeline.DepthState(true,true,MetalRenderPipeline.CompareFunction.GREATER,0,0),MetalRenderPipeline.RasterState.DEFAULT));
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
