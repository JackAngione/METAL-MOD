package dev.metalcraft.client.shader;

import com.google.gson.GsonBuilder;
import dev.metalcraft.client.metal.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;

/** P6 experiment, not a runtime feature: actual Standard lighting with synthetic opaque inputs.
 * GPU intervals include coverage/depth, lost merging, stores, bands, reconstruction and a depth consumer.
 * This deliberately cannot establish generated-world image quality or an overall frame-rate benefit. */
public final class LodShadingBenchmark {
    private static final Path OUTPUT = Path.of("build", "reports", "lod-shading");
    private static final int SAMPLES = 180;
    private static final int REPEATS = 3;
    private enum Variant { MERGED, BANDS, FUSED, QUAD, DIRECT, DISTANCE }
    private static final MetalTexture.Format HDR = MetalTexture.Format.RGBA16_FLOAT;
    private static final MetalTexture.Format BYTE = MetalTexture.Format.RGBA8_UNORM;
    private static final MetalTexture.Format DEPTH = MetalTexture.Format.DEPTH32_FLOAT;
    private static final List<MetalRenderPipeline.ColorTarget> GBUFFER = List.of(
            MetalRenderPipeline.ColorTarget.opaque(HDR), MetalRenderPipeline.ColorTarget.opaque(BYTE),
            MetalRenderPipeline.ColorTarget.opaque(BYTE), MetalRenderPipeline.ColorTarget.opaque(BYTE));

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        String source = productionSource();
        Files.writeString(OUTPUT.resolve("fixture.metal"), "#define MC_REDUCE_DISTANT_LIGHTING 0\n" + source);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "Synthetic Standard resolve cost experiment; not world/frame performance or a shipped capability");
        report.put("bands", "near <64 blocks full resolution, middle 64..128 half linear, far >=128 quarter linear");
        report.put("depth", "Common full-resolution opaque depth; reduced targets contain no depth; subsequent depth-tested consumer unchanged");
        report.put("timestamp", "Metal GPUStartTime/GPUEndTime for one command buffer containing all passes; synchronous harness waits are outside measured intervals");
        report.put("samplesPerVariantPerRepeat", SAMPLES);
        report.put("repeats", REPEATS);
        report.put("warmupSubmissionsPerRepeat", 80);
        report.put("quantileOrder", List.of("median", "p95", "p99"));
        report.put("distanceScope", "Production full-resolution identity-lighting fallback; no reduced targets, sharing, history, approximation or new resources. Specialization removed at shadow_distance >=128.");
        report.put("quadScope", "Half-linear shading only; diagnostic simpler alternative, never quarter-linear");
        report.put("acceptance", "Experimental cost/quality report only; failed gates do not enable runtime capabilities");
        report.put("java", System.getProperty("java.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        var results = new ArrayList<Object>();
        MetalStallProbe.setEnabled(true);
        try (var device = MetalNative.openDefaultDevice().orElseThrow()) {
            report.put("device", device.name());
            for (int shadowDistance : new int[]{96, 256}) for (int[] size : new int[][]{{1279,719}, {1920,1080}, {3840,2160}}) {
                try (var scene = new Scene(device, source.replace("#define MC_OPTION_SHADOW_DISTANCE 96\n", "#define MC_OPTION_SHADOW_DISTANCE " + shadowDistance + "\n"), size[0], size[1], shadowDistance)) {
                    for (int repeat = 0; repeat < REPEATS; repeat++) {
                        for (int i = 0; i < 80; i++) scene.draw(Variant.values()[i % Variant.values().length], 0);
                        double[][] timings = new double[Variant.values().length][SAMPLES];
                        // Rotate all variant positions, including the reference, within each sample.
                        for (int sample = 0; sample < SAMPLES; sample++) for (int order = 0; order < Variant.values().length; order++) {
                            int variant = (order + sample + repeat) % Variant.values().length;
                            timings[variant][sample] = scene.draw(Variant.values()[variant], sample % 16);
                        }
                        var row = new LinkedHashMap<String, Object>();
                        row.put("shadowDistance", shadowDistance); row.put("width", size[0]); row.put("height", size[1]);
                        row.put("repeat", repeat + 1);
                        for (Variant variant : Variant.values()) {
                            String name = variant.name().toLowerCase(java.util.Locale.ROOT);
                            row.put(name + "Ms", quantiles(timings[variant.ordinal()]));
                            row.put(name + "SamplesMs", timings[variant.ordinal()]);
                            if (variant != Variant.MERGED) {
                                row.put(name + "MedianChangePercent", 100 * (median(timings[variant.ordinal()]) / median(timings[0]) - 1));
                                row.put(name + "NetGpuImprovement", median(timings[variant.ordinal()]) < median(timings[0]));
                                if (repeat == 0 && size[0] == 1279) row.put(name + "Quality", scene.quality(variant));
                            }
                        }
                        row.put("allocatedBytes", device.currentAllocatedBytes());
                        results.add(row);
                        System.out.printf("P6 shadows=%d %dx%d repeat=%d GPU medians: merged=%.4f bands=%.4f fused=%.4f quad=%.4f direct=%.4f distance=%.4f ms%n",
                                shadowDistance, size[0], size[1], repeat + 1, median(timings[0]), median(timings[1]),
                                median(timings[2]), median(timings[3]), median(timings[4]), median(timings[5]));
                    }
                }
            }
        } finally { MetalStallProbe.setEnabled(false); }
        report.put("results", results);
        Files.writeString(OUTPUT.resolve("metrics.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
    }

    private static String productionSource() throws Exception {
        var pack = ShaderPackLoader.loadBundled(LodShadingBenchmark.class.getClassLoader(),
                ShaderPackRuntime.BUILTIN_ID, "assets/metalcraft/shaderpacks/standard");
        var declaration = pack.manifest().passes().stream().filter(p -> p.id().equals("resolve")).findFirst().orElseThrow();
        String source = ShaderPassCompiler.source(pack, declaration, Map.of())
                .replace("#define MC_SCENE_LINEAR_HDR 0\n", "#define MC_SCENE_LINEAR_HDR 1\n");
        source = "#define MC_TARGET_GBUFFER_ALBEDO 1\n#define MC_TARGET_GBUFFER_NORMAL 2\n#define MC_TARGET_GBUFFER_LIGHT 3\n" + source;
        // Keep the production function and extract its body for sampled-input calls.
        int body = source.indexOf("    ResolveTargets out = previous;");
        int end = source.indexOf("\n}\n", body);
        if (body < 0 || end < 0 || source.indexOf("    ResolveTargets out = previous;", body + 1) >= 0)
            throw new AssertionError("Standard resolve changed; review this prototype's adapter");
        String helper = """
                ResolveTargets shade(ResolveVaryings in, ResolveTargets previous, constant PackOptions &options,
                    constant MCShadowFrame &shadowFrame, constant MCResolveCamera &camera, constant McFog &fog,
                    depth2d_array<float> shadowMap, sampler shadowSampler) {
                """ + source.substring(body, end) + "\n}\n";
        source += helper + Files.readString(Path.of("src/smoke/resources/lod-shading-prototype.metal"))
                + Files.readString(Path.of("src/smoke/resources/lod-shading-fused.metal"))
                + Files.readString(Path.of("src/smoke/resources/lod-shading-direct.metal"));
        return source;
    }

    /** Correctness gate for the production specialization, independent of timing noise. */
    public static void verifyDistanceLighting() throws Exception {
        Files.createDirectories(OUTPUT);
        String source = productionSource();
        MetalStallProbe.setEnabled(true);
        int cases = 0;
        double maximum = 0;
        try (var device = MetalNative.openDefaultDevice().orElseThrow()) {
            for (boolean hdr : new boolean[]{false, true}) for (int shadowDistance : new int[]{32, 96, 128, 256}) {
                String selected = source.replace("#define MC_OPTION_SHADOW_DISTANCE 96\n", "#define MC_OPTION_SHADOW_DISTANCE " + shadowDistance + "\n")
                        .replace("#define MC_SCENE_LINEAR_HDR 1\n", "#define MC_SCENE_LINEAR_HDR " + (hdr ? 1 : 0) + "\n");
                selected = "#define FIXTURE_COLOR_GAIN " + (hdr ? 4 : 1) + "\n" + selected;
                try (var scene = new Scene(device, selected, 127, 73, shadowDistance)) {
                    for (int debug = 0; debug <= 8; debug++) for (int fog = 0; fog < 3; fog++) for (int boundary = -1; boundary <= 1; boundary++) {
                        try (var mapped = scene.options.map()) { mapped.bytes().putInt(8, debug); }
                        try (var mapped = scene.fog.map()) {
                            var bytes = mapped.bytes();
                            bytes.putFloat(0, .12f).putFloat(4, .31f).putFloat(8, .47f).putFloat(12, 1);
                            bytes.putFloat(16, fog == 0 ? 10000 : 0).putFloat(20, fog == 0 ? 10000 : fog == 1 ? 300 : 1);
                        }
                        try (var mapped = scene.parameters.map()) { mapped.bytes().putFloat(12, shadowDistance - 200 + boundary * .02f); }
                        // Also cover the defined unoccluded night/dimension frame.
                        try (var mapped = scene.frame.map()) { mapped.bytes().putInt(416, fog == 2 ? 0 : 4); }
                        scene.draw(Variant.MERGED, 7);
                        var reference = scene.scene.readback(scene.queue, 0).order(ByteOrder.nativeOrder());
                        var depth = scene.depth.readback(scene.queue, 0).order(ByteOrder.nativeOrder());
                        scene.draw(Variant.DISTANCE, 7);
                        var actual = scene.scene.readback(scene.queue, 0).order(ByteOrder.nativeOrder());
                        var actualDepth = scene.depth.readback(scene.queue, 0).order(ByteOrder.nativeOrder());
                        for (int p = 0; p < scene.width * scene.height; p++) {
                            if (depth.getInt(p * 4) != actualDepth.getInt(p * 4)) throw new AssertionError("Distance lighting changed depth");
                            for (int c = 0; c < 4; c++) {
                                float error = Math.abs(Float.float16ToFloat(reference.getShort(p * 8 + c * 2))
                                        - Float.float16ToFloat(actual.getShort(p * 8 + c * 2)));
                                if (!Float.isFinite(error) || error > .002f) throw new AssertionError("Distance lighting changed color: " + error);
                                maximum = Math.max(maximum, error);
                            }
                        }
                        cases++;
                    }
                }
            }
        } finally { MetalStallProbe.setEnabled(false); }
        System.out.println("Distance lighting contracts passed: " + cases + " legacy/HDR, debug, fog, unoccluded and threshold cases; max channel error=" + maximum);
    }

    private static List<Double> quantiles(double[] input) {
        double[] sorted = input.clone(); Arrays.sort(sorted);
        return List.of(sorted[sorted.length / 2], sorted[(int)(sorted.length * .95)], sorted[(int)(sorted.length * .99)]);
    }
    private static double median(double[] values) { return quantiles(values).getFirst(); }

    private static final class Scene implements AutoCloseable {
        final MetalDevice device;
        final MetalCommandQueue queue;
        final long[] work = new long[MetalStallProbe.slots()];
        final List<AutoCloseable> owned = new ArrayList<>();
        final int width, height, shadowDistance;
        final MetalTexture scene, albedo, normal, light, depth, output, half, quarter, shadow;
        final MetalTextureView sceneView, albedoView, normalView, lightView, halfView, quarterView, shadowView;
        final MetalSampler sampler;
        final MetalBuffer options, frame, camera, fog, parameters;
        final MetalRenderPipeline geometry, resolve, fused, quad, direct, distance, halfPipeline, quarterPipeline, reconstruct, overlay, shadowFixture;

        Scene(MetalDevice device, String source, int width, int height, int shadowDistance) {
            this.device = device; this.width = width; this.height = height; this.shadowDistance = shadowDistance;
            queue = own(device.createCommandQueue());
            scene = texture(HDR,width,height); albedo = texture(BYTE,width,height);
            normal = texture(BYTE,width,height); light = texture(BYTE,width,height);
            depth = texture(DEPTH,width,height); output = texture(HDR,width,height);
            half = texture(HDR,(width+1)/2,(height+1)/2); quarter = texture(HDR,(width+3)/4,(height+3)/4);
            shadow = own(device.createTexture(new MetalTexture.Descriptor(DEPTH,64,64,4,1,
                    MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_RENDER_TARGET, false)));
            sceneView = own(scene.createView()); albedoView = own(albedo.createView());
            normalView = own(normal.createView()); lightView = own(light.createView());
            halfView = own(half.createView()); quarterView = own(quarter.createView()); shadowView = own(shadow.createView());
            sampler = own(device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,
                    MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE)));
            options = buffer(16); frame = buffer(432); camera = buffer(160); fog = buffer(48); parameters = buffer(16);
            try (var m = options.map()) { m.bytes().putFloat(0,1).putFloat(12,1); }
            try (var m = frame.map()) {
                var b = m.bytes();
                for (int i=0;i<4;i++) new Matrix4f().scaling(.004f,.004f,.001f).m32(.5f).get(i*64,b);
                new Matrix4f().get(256,b); new Matrix4f().get(320,b);
                b.putFloat(384,shadowDistance/4f).putFloat(388,shadowDistance/2f).putFloat(392,shadowDistance*.75f).putFloat(396,shadowDistance);
                b.putFloat(408,1).putInt(416,4).putFloat(420,1f/64).putFloat(424,shadowDistance).putFloat(428,96);
            }
            try (var m = camera.map()) {
                new Matrix4f().perspective((float)Math.toRadians(70),(float)width/height,.05f,512,true).invert().get(0,m.bytes());
                new Matrix4f().get(64,m.bytes());
                m.bytes().putFloat(128,width).putFloat(132,height);
            }
            try (var m = fog.map()) { for(int i=16;i<48;i+=4) m.bytes().putFloat(i,10000); }
            try (var m = parameters.map()) { m.bytes().putFloat(0,width).putFloat(4,height); }
            for (var entry : Map.of("options", options, "frame-" + shadowDistance, frame, "camera-" + width, camera, "fog", fog).entrySet()) {
                try (var mapped = entry.getValue().map()) {
                    byte[] bytes = new byte[mapped.bytes().remaining()]; mapped.bytes().get(bytes);
                    Files.write(OUTPUT.resolve(entry.getKey() + ".bin"), bytes);
                } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            }
            var depthWrite = new MetalRenderPipeline.DepthState(true,true,MetalRenderPipeline.CompareFunction.GREATER,0,0);
            String referenceSource = "#define MC_REDUCE_DISTANT_LIGHTING 0\n" + source;
            distance = pipeline(source,"resolve_fragment",GBUFFER,DEPTH,MetalRenderPipeline.DepthState.DISABLED);
            geometry = pipeline(referenceSource,"fixture_geometry",GBUFFER,DEPTH,depthWrite);
            resolve = pipeline(referenceSource,"resolve_fragment",GBUFFER,DEPTH,MetalRenderPipeline.DepthState.DISABLED);
            quad = pipeline(referenceSource,"fixture_quad",GBUFFER,DEPTH,MetalRenderPipeline.DepthState.DISABLED);
            direct = pipeline(referenceSource,"fixture_direct",GBUFFER,DEPTH,MetalRenderPipeline.DepthState.DISABLED);
            fused = pipeline(referenceSource,"fixture_fused",GBUFFER,DEPTH,MetalRenderPipeline.DepthState.DISABLED);
            halfPipeline = pipeline("#define LOD_SCALE 2\n"+referenceSource,"fixture_band",List.of(MetalRenderPipeline.ColorTarget.opaque(HDR)),null,MetalRenderPipeline.DepthState.DISABLED);
            quarterPipeline = pipeline("#define LOD_SCALE 4\n"+referenceSource,"fixture_band",List.of(MetalRenderPipeline.ColorTarget.opaque(HDR)),null,MetalRenderPipeline.DepthState.DISABLED);
            reconstruct = pipeline(referenceSource,"fixture_reconstruct",List.of(MetalRenderPipeline.ColorTarget.opaque(HDR)),DEPTH,MetalRenderPipeline.DepthState.DISABLED);
            overlay = pipeline(referenceSource,"fixture_overlay",List.of(MetalRenderPipeline.ColorTarget.opaque(HDR)),DEPTH,
                    new MetalRenderPipeline.DepthState(true,false,MetalRenderPipeline.CompareFunction.GREATER,0,0));
            shadowFixture = pipeline(referenceSource,"fixture_shadow",List.of(MetalRenderPipeline.ColorTarget.unused()),DEPTH,
                    new MetalRenderPipeline.DepthState(true,true,MetalRenderPipeline.CompareFunction.ALWAYS,0,0));
            // Alternating occluder depths exercise moving shadow edges, not only constant visibility.
            try (var commands = queue.createCommandBuffer()) {
                for(int layer=0;layer<4;layer++) try(var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
                        List.of(), new MetalRenderPass.DepthAttachment(shadow,0,layer,MetalRenderPass.LoadAction.CLEAR,
                        MetalRenderPass.StoreAction.STORE,1),0))) {
                    pass.setPipeline(shadowFixture); pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3,1,0);
                }
                commands.commitAndWait();
            }
        }

        <T extends AutoCloseable> T own(T value) { owned.add(value); return value; }
        MetalTexture texture(MetalTexture.Format format,int w,int h) {
            return own(device.createTexture(new MetalTexture.Descriptor(format,w,h,1)));
        }
        MetalBuffer buffer(int size) {
            var b = own(device.createBuffer(size,MetalBuffer.StorageMode.SHARED));
            try(var m=b.map()) { for(int i=0;i<size;i++) m.bytes().put(i,(byte)0); } return b;
        }
        MetalRenderPipeline pipeline(String source,String fragment,List<MetalRenderPipeline.ColorTarget> colors,
                MetalTexture.Format depth,MetalRenderPipeline.DepthState state) {
            return own(device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source,"resolve_vertex",source,fragment,
                    colors,depth,MetalRenderPipeline.VertexDescriptor.EMPTY,state,MetalRenderPipeline.RasterState.DEFAULT)));
        }
        void bind(MetalRenderPass pass) {
            for (int i=0;i<5;i++) pass.setUniformBuffer(i,List.of(options,frame,camera,fog,parameters).get(i),0,MetalRenderPass.STAGE_FRAGMENT);
            pass.setTexture(0,shadowView,MetalRenderPass.STAGE_FRAGMENT); pass.setSampler(0,sampler,MetalRenderPass.STAGE_FRAGMENT);
        }
        void inputs(MetalRenderPass pass) {
            for(int i=0;i<6;i++) pass.setTexture(i+1,List.of(sceneView,albedoView,normalView,lightView,halfView,quarterView).get(i),MetalRenderPass.STAGE_FRAGMENT);
        }
        void fullscreen(MetalRenderPass pass,MetalRenderPipeline pipeline) {
            pass.setPipeline(pipeline); bind(pass); pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3,1,0);
        }
        MetalRenderPass.ColorAttachment clear(MetalTexture t,boolean store) {
            return new MetalRenderPass.ColorAttachment(t,MetalRenderPass.LoadAction.CLEAR,
                    store?MetalRenderPass.StoreAction.STORE:MetalRenderPass.StoreAction.DONT_CARE,0,0,0,0);
        }
        MetalRenderPass.DepthAttachment depth(boolean clear) {
            return new MetalRenderPass.DepthAttachment(depth,clear?MetalRenderPass.LoadAction.CLEAR:MetalRenderPass.LoadAction.LOAD,
                    MetalRenderPass.StoreAction.STORE,0);
        }
        double draw(Variant variant,int motion) {
            boolean bands = variant == Variant.BANDS;
            try(var m=parameters.map()) { m.bytes().putFloat(8,motion); }
            MetalStallProbe.recordCompletedGpuWork(); MetalStallProbe.takeFrame(work,0);
            try(var commands=queue.createCommandBuffer()) {
                try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(List.of(clear(scene,true),clear(albedo,bands),
                        clear(normal,bands),clear(light,bands)),depth(true),0))) {
                    fullscreen(pass,geometry);
                    if(!bands) fullscreen(pass, variant == Variant.DISTANCE ? distance : variant == Variant.DIRECT ? direct : variant == Variant.QUAD ? quad : variant == Variant.FUSED ? fused : resolve);
                }
                if(bands) {
                    for(int level=0;level<2;level++) try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(
                            clear(level==0?half:quarter,true)))) {
                        // A band's output must never be bound as a sampled input to its own encoder.
                        pass.setTexture(1,sceneView,MetalRenderPass.STAGE_FRAGMENT);
                        pass.setTexture(2,albedoView,MetalRenderPass.STAGE_FRAGMENT);
                        pass.setTexture(3,normalView,MetalRenderPass.STAGE_FRAGMENT);
                        pass.setTexture(4,lightView,MetalRenderPass.STAGE_FRAGMENT);
                        fullscreen(pass,level==0?halfPipeline:quarterPipeline);
                    }
                    try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(List.of(clear(output,true)),depth(false),0))) {
                        inputs(pass); fullscreen(pass,reconstruct);
                    }
                }
                // Depth-tested intersection uses the original full-resolution depth in both variants.
                var result = bands?output:scene;
                try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(List.of(new MetalRenderPass.ColorAttachment(
                        result,MetalRenderPass.LoadAction.LOAD,MetalRenderPass.StoreAction.STORE,0,0,0,0)),depth(false),0))) {
                    fullscreen(pass,overlay);
                }
                commands.commitAndWait();
                MetalStallProbe.recordCompletedGpuWork(); MetalStallProbe.takeFrame(work,0);
                int base = MetalStallProbe.Source.GPU_FRAME.ordinal()*MetalStallProbe.FIELDS;
                if(work[base+MetalStallProbe.FIELD_COUNT]!=1 || work[base+MetalStallProbe.FIELD_NANOS]<=0)
                    throw new AssertionError("Expected one completed nonzero GPU command-buffer interval");
                return work[base+MetalStallProbe.FIELD_NANOS]/1e6;
            }
        }
        Map<String,Object> quality(Variant variant) throws Exception {
            long differing = 0, nearDiffering = 0, depthDiffering = 0, pixels = 0;
            double maxError = 0, sumError = 0;
            for(int motion=0;motion<16;motion++) {
                draw(Variant.MERGED,motion); var reference=scene.readback(queue,0).order(ByteOrder.nativeOrder());
                var originalDepth=depth.readback(queue,0).order(ByteOrder.nativeOrder());
                draw(variant,motion); var actual=(variant == Variant.BANDS ? output : scene).readback(queue,0).order(ByteOrder.nativeOrder());
                var bandDepth=depth.readback(queue,0).order(ByteOrder.nativeOrder());
                for(int p=0;p<width*height;p++) {
                    double error=0;
                    for(int c=0;c<4;c++) error=Math.max(error,Math.abs(Float.float16ToFloat(reference.getShort(p*8+c*2))
                            -Float.float16ToFloat(actual.getShort(p*8+c*2))));
                    if(!Double.isFinite(error)) throw new AssertionError("Nonfinite reconstruction");
                    if(error>.02) { differing++; if(originalDepth.getFloat(p*4)>.1/64) nearDiffering++; }
                    if(originalDepth.getInt(p*4)!=bandDepth.getInt(p*4)) depthDiffering++;
                    maxError=Math.max(maxError,error); sumError+=error; pixels++;
                }
                if(motion==7) { image(reference,"merged.png"); image(actual,variant.name().toLowerCase(java.util.Locale.ROOT) + ".png"); }
            }
            if(depthDiffering!=0||nearDiffering!=0) throw new AssertionError("Full-resolution depth/near ownership changed");
            return Map.of("motionSteps",16,"pixels",pixels,"pixelsOver002",differing,"nearPixelsOver002",nearDiffering,
                    "depthBitDifferences",depthDiffering,"maximumChannelError",maxError,"meanMaximumChannelError",sumError/pixels,
                    "imageBudgetPassed", differing == 0);
        }
        void image(ByteBuffer data,String name) throws Exception {
            var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                int rgb=0;
                for(int c=0;c<3;c++) rgb=(rgb<<8)|Math.clamp(Math.round(Float.float16ToFloat(data.getShort((y*width+x)*8+c*2))*255),0,255);
                image.setRGB(x,y,rgb);
            }
            Path imageDirectory = OUTPUT.resolve("shadows-" + shadowDistance);
            Files.createDirectories(imageDirectory);
            ImageIO.write(image,"png",imageDirectory.resolve(name).toFile());
        }
        public void close() throws Exception { for(int i=owned.size()-1;i>=0;i--) owned.get(i).close(); }
    }
}
