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
    private static final MetalTexture.Format HDR = MetalTexture.Format.RGBA16_FLOAT;
    private static final MetalTexture.Format BYTE = MetalTexture.Format.RGBA8_UNORM;
    private static final MetalTexture.Format DEPTH = MetalTexture.Format.DEPTH32_FLOAT;
    private static final List<MetalRenderPipeline.ColorTarget> GBUFFER = List.of(
            MetalRenderPipeline.ColorTarget.opaque(HDR), MetalRenderPipeline.ColorTarget.opaque(BYTE),
            MetalRenderPipeline.ColorTarget.opaque(BYTE), MetalRenderPipeline.ColorTarget.opaque(BYTE));

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
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
        source += helper + Files.readString(Path.of("src/smoke/resources/lod-shading-prototype.metal"));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("scope", "Synthetic Standard resolve cost experiment; not world/frame performance or a shipped capability");
        report.put("bands", "near <64 blocks full resolution, middle 64..128 half linear, far >=128 quarter linear");
        report.put("depth", "Common full-resolution opaque depth; reduced targets contain no depth; subsequent depth-tested consumer unchanged");
        report.put("timestamp", "Metal GPUStartTime/GPUEndTime for one command buffer containing all passes; synchronous harness waits are outside measured intervals");
        report.put("samplesPerVariant", 180);
        var results = new ArrayList<Object>();
        MetalStallProbe.setEnabled(true);
        try (var device = MetalNative.openDefaultDevice().orElseThrow()) {
            report.put("device", device.name());
            for (int[] size : new int[][]{{1279,719}, {1920,1080}, {3840,2160}}) {
                try (var scene = new Scene(device, source, size[0], size[1])) {
                    for (int i = 0; i < 40; i++) scene.draw((i & 1) != 0, 0);
                    double[][] timings = {new double[180], new double[180]};
                    // Alternate order every pair to limit systematic clock/thermal ordering bias.
                    for (int sample = 0; sample < 180; sample++) for (int order = 0; order < 2; order++) {
                        int variant = order ^ (sample & 1);
                        timings[variant][sample] = scene.draw(variant == 1, sample % 16);
                    }
                    var row = new LinkedHashMap<String, Object>();
                    row.put("width", size[0]); row.put("height", size[1]);
                    row.put("mergedMs", quantiles(timings[0])); row.put("bandsMs", quantiles(timings[1]));
                    row.put("medianChangePercent", 100 * (median(timings[1]) / median(timings[0]) - 1));
                    row.put("allocatedBytes", device.currentAllocatedBytes());
                    if (size[0] == 1279) row.put("quality", scene.quality());
                    results.add(row);
                    System.out.println(new GsonBuilder().create().toJson(row));
                }
            }
        } finally { MetalStallProbe.setEnabled(false); }
        report.put("results", results);
        Files.writeString(OUTPUT.resolve("metrics.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
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
        final int width, height;
        final MetalTexture scene, albedo, normal, light, depth, output, half, quarter, shadow;
        final MetalTextureView sceneView, albedoView, normalView, lightView, halfView, quarterView, shadowView;
        final MetalSampler sampler;
        final MetalBuffer options, frame, camera, fog, parameters;
        final MetalRenderPipeline geometry, resolve, halfPipeline, quarterPipeline, reconstruct, overlay, shadowFixture;

        Scene(MetalDevice device, String source, int width, int height) {
            this.device = device; this.width = width; this.height = height;
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
                b.putFloat(384,24).putFloat(388,48).putFloat(392,72).putFloat(396,96);
                b.putFloat(408,1).putInt(416,4).putFloat(420,1f/64).putFloat(424,96).putFloat(428,96);
            }
            try (var m = camera.map()) {
                new Matrix4f().perspective((float)Math.toRadians(70),(float)width/height,.05f,512,true).invert().get(0,m.bytes());
                new Matrix4f().get(64,m.bytes());
                m.bytes().putFloat(128,width).putFloat(132,height);
            }
            try (var m = fog.map()) { for(int i=16;i<48;i+=4) m.bytes().putFloat(i,10000); }
            try (var m = parameters.map()) { m.bytes().putFloat(0,width).putFloat(4,height); }
            var depthWrite = new MetalRenderPipeline.DepthState(true,true,MetalRenderPipeline.CompareFunction.GREATER,0,0);
            geometry = pipeline(source,"fixture_geometry",GBUFFER,DEPTH,depthWrite);
            resolve = pipeline(source,"resolve_fragment",GBUFFER,DEPTH,MetalRenderPipeline.DepthState.DISABLED);
            halfPipeline = pipeline("#define LOD_SCALE 2\n"+source,"fixture_band",List.of(MetalRenderPipeline.ColorTarget.opaque(HDR)),null,MetalRenderPipeline.DepthState.DISABLED);
            quarterPipeline = pipeline("#define LOD_SCALE 4\n"+source,"fixture_band",List.of(MetalRenderPipeline.ColorTarget.opaque(HDR)),null,MetalRenderPipeline.DepthState.DISABLED);
            reconstruct = pipeline(source,"fixture_reconstruct",List.of(MetalRenderPipeline.ColorTarget.opaque(HDR)),DEPTH,MetalRenderPipeline.DepthState.DISABLED);
            overlay = pipeline(source,"fixture_overlay",List.of(MetalRenderPipeline.ColorTarget.opaque(HDR)),DEPTH,
                    new MetalRenderPipeline.DepthState(true,false,MetalRenderPipeline.CompareFunction.GREATER,0,0));
            shadowFixture = pipeline(source,"fixture_shadow",List.of(MetalRenderPipeline.ColorTarget.unused()),DEPTH,
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
        double draw(boolean bands,int motion) {
            try(var m=parameters.map()) { m.bytes().putFloat(8,motion); }
            MetalStallProbe.recordCompletedGpuWork(); MetalStallProbe.takeFrame(work,0);
            try(var commands=queue.createCommandBuffer()) {
                try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(List.of(clear(scene,true),clear(albedo,bands),
                        clear(normal,bands),clear(light,bands)),depth(true),0))) {
                    fullscreen(pass,geometry);
                    if(!bands) fullscreen(pass,resolve);
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
        Map<String,Object> quality() throws Exception {
            long differing = 0, nearDiffering = 0, depthDiffering = 0, pixels = 0;
            double maxError = 0, sumError = 0;
            for(int motion=0;motion<16;motion++) {
                draw(false,motion); var reference=scene.readback(queue,0).order(ByteOrder.nativeOrder());
                var originalDepth=depth.readback(queue,0).order(ByteOrder.nativeOrder());
                draw(true,motion); var actual=output.readback(queue,0).order(ByteOrder.nativeOrder());
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
                if(motion==7) { image(reference,"merged.png"); image(actual,"bands.png"); }
            }
            if(depthDiffering!=0||nearDiffering!=0) throw new AssertionError("Full-resolution depth/near ownership changed");
            return Map.of("motionSteps",16,"pixels",pixels,"pixelsOver002",differing,"nearPixelsOver002",nearDiffering,
                    "depthBitDifferences",depthDiffering,"maximumChannelError",maxError,"meanMaximumChannelError",sumError/pixels);
        }
        void image(ByteBuffer data,String name) throws Exception {
            var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
            for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                int rgb=0;
                for(int c=0;c<3;c++) rgb=(rgb<<8)|Math.clamp(Math.round(Float.float16ToFloat(data.getShort((y*width+x)*8+c*2))*255),0,255);
                image.setRGB(x,y,rgb);
            }
            ImageIO.write(image,"png",OUTPUT.resolve(name).toFile());
        }
        public void close() throws Exception { for(int i=owned.size()-1;i>=0;i--) owned.get(i).close(); }
    }
}
