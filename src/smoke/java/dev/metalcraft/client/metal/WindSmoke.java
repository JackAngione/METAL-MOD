package dev.metalcraft.client.metal;

import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.wind.WindVertexMetadata;
import dev.metalcraft.client.shader.wind.WindAnimation;
import dev.metalcraft.client.shader.world.ShadowCascades;
import dev.metalcraft.client.shader.world.TerrainShadowRenderer;
import dev.metalcraft.client.shader.world.WorldShadowModule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Real Metal displacement, original BLOCK vertices, nonzero base vertex and matching shadow coverage. */
final class WindSmoke {
    private static final int SIZE = 128, BASE = 2, STRIDE = 28;
    private static final int STAGES = MetalRenderPass.STAGE_VERTEX | MetalRenderPass.STAGE_FRAGMENT;

    static void run() {
        clock();
        metadata();
        var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
        try {
            helpers(gpu.metal(), 1);
            helpers(gpu.metal(), 0);
            WindShadowStabilitySmoke.run(gpu.metal());
            var runtime = gpu.shaderPackRuntime();
            runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
            runtime.setOption("wind_enabled", true);
            runtime.setOption("wind_strength", 1.0);
            runtime.resize(SIZE, SIZE);
            var geometry = runtime.worldGeometry();
            geometry.beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);
            var original = ChunkSectionLayer.CUTOUT.pipeline();
            var wind = geometry.windPipeline(original).orElseThrow();
            var compiled = (MetalCompiledRenderPipeline)gpu.precompileLinearWorldPipeline(wind, null);
            geometry.beginFrame();
            geometry.beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);
            check(geometry.windPipeline(original).orElseThrow() == wind, "wind pipeline cache survives HDR transitions");
            var pipeline = compiled.metal(false, MetalTexture.Format.RGBA16_FLOAT);
            var solidWind = geometry.windPipeline(ChunkSectionLayer.SOLID.pipeline()).orElseThrow();
            var solid = ((MetalCompiledRenderPipeline)gpu.precompileLinearWorldPipeline(solidWind,null))
                .metal(false,MetalTexture.Format.RGBA16_FLOAT);
            WindOverlayDepthSmoke.run(gpu.metal(), solid.descriptor(), pipeline.descriptor());
            boolean[] still = render(gpu.metal(), pipeline, 0, 0);
            check(Arrays.equals(still, render(gpu.metal(), pipeline, 0, 2)), "unrelated blocks remain still");
            boolean[] leaves = render(gpu.metal(), pipeline, -1, 0);
            check(!Arrays.equals(leaves, render(gpu.metal(), pipeline, -1, 2)), "leaves animate");
            boolean[] grass = render(gpu.metal(), pipeline, 2, 0);
            check(!Arrays.equals(grass, render(gpu.metal(), pipeline, 2, 2)), "grass bends");
            runtime.setOption("wind_enabled", false);
            runtime.worldGeometry().beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);
            check(runtime.worldGeometry().windPipeline(original).isEmpty(), "off selects ordinary geometry");
            runtime.setOption("wind_enabled", true);
            runtime.setOption("wind_strength", 0.0);
            runtime.worldGeometry().beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);
            check(runtime.worldGeometry().windPipeline(original).isEmpty(), "zero strength selects ordinary geometry");
        } finally { gpu.close(); }
        System.out.println("Foliage wind: 80% animation speed, seamless clock wrap, anchored roots, tall-grass joint, sparse metadata, bounded periodic motion, world-border precision, nonzero base vertex, cutout/shadow coverage, deformed receiver planes, animation and option/cache lifecycle passed");
    }

    private static void clock() {
        check(WindAnimation.seconds(20, 0) == 0.8F, "one game second advances wind by 0.8 seconds");
        check(WindAnimation.seconds(0, 0.5F) == 0.02F, "partial ticks retain smooth slower motion");
        check(WindAnimation.seconds(20_480, 0) == 819.2F, "water clock wrap does not reset wind");
        check(WindAnimation.seconds(25_599, 1) == 0 && WindAnimation.seconds(25_600, 0) == 0, "wind wraps at its own full period");
        for (long ticks : new long[]{-1, 0, 20_480, 25_599, Long.MAX_VALUE}) {
            check(WindAnimation.seconds(ticks, 0.5F) == WindAnimation.seconds(Math.floorMod(ticks, 25_600L), 0.5F),
                "long-running clock retains sub-tick precision");
        }
    }

    private static void metadata() {
        var builder = new WindVertexMetadata.Builder();
        builder.put(4, WindVertexMetadata.LEAVES, 1);
        builder.put(6, WindVertexMetadata.GRASS, 0);
        builder.put(7, WindVertexMetadata.GRASS, 1);
        builder.put(8, WindVertexMetadata.UPPER_GRASS, 0);
        builder.put(9, WindVertexMetadata.UPPER_GRASS, 1);
        var data = builder.build(12);
        check(data.value(4) == -1 && data.value(6) == 0 && data.value(7) == data.value(8)
            && data.value(9) == 2 && data.value(0) == 0 && data.value(5) == 0 && data.value(11) == 0, "sparse plant metadata and two-block root height");
        check(data.bytes().remaining() == 48 && data.bytes().getFloat(36) == 2, "packed metadata ABI");
        try { builder.build(9); throw new AssertionError("Truncated metadata accepted"); }
        catch (IllegalStateException expected) { }
    }

    private static void helpers(MetalDevice device, int strength) {
        String source = "#define MC_OPTION_WIND_STRENGTH " + strength + "\n" + resource("shared/wind.metal") + """
            kernel void wind_probe(device float4* out [[buffer(0)]], uint id [[thread_position_in_grid]]) {
                float3 p = float3(12.25, 70, 34.75);
                out[0] = float4(mc_wind_offset(p, 0, 17.25), 0);
                out[1] = float4(mc_wind_offset(p, -1, 17.25), 0);
                out[2] = float4(mc_wind_offset(p, -1, 1041.25), 0);
                out[3] = float4(mc_wind_offset(p + float3(1024, 1024, -1024), -1, 17.25), 0);
                out[4] = float4(mc_wind_offset(p, 1, 17.25), 0);
                out[5] = float4(mc_wind_offset(p, 2, 17.25), 0);
                out[6] = float4(mc_wind_offset(mc_wind_world_position(int3(29999984,64,-30000000), float3(15.75,6,0.25)), -1, 17.25), 0);
                out[7] = float4(mc_wind_offset(mc_wind_world_position(int3(29999968,64,-30000016), float3(31.75,6,16.25)), -1, 17.25), 0);
                float largest = 0;
                for (uint i = 0; i < 512; i++) {
                    float3 d = mc_wind_offset(p + float3(i,0,-int(i)), 2, float(i) * 0.1);
                    largest = max(largest, length(d));
                }
                out[8] = float4(largest, 0, 0, 0);
            }
            """;
        try (var queue = device.createCommandQueue();
             var pipeline = device.createComputePipeline(new MetalComputePipeline.Descriptor(source, "wind_probe"));
             var output = device.createBuffer(9 * 16, MetalBuffer.StorageMode.SHARED)) {
            try (var commands = queue.createCommandBuffer()) {
                try (var pass = commands.beginComputePass()) {
                    pass.setPipeline(pipeline); pass.setBuffer(0, output, 0); pass.dispatch(1,1,1,1,1,1);
                }
                commands.commitAndWait();
            }
            try (var mapping = output.map()) {
                var b = mapping.bytes();
                for (int c = 0; c < 3; c++) {
                    check(b.getFloat(c * 4) == 0, "root is exactly anchored");
                    check(Math.abs(b.getFloat(16 + c*4) - b.getFloat(32 + c*4)) < 0.00003, "time wrap continuous");
                    check(Math.abs(b.getFloat(16 + c*4) - b.getFloat(48 + c*4)) < 0.00003, "spatial wrap continuous");
                    check(Math.abs(b.getFloat(96 + c*4) - b.getFloat(112 + c*4)) < 0.000001, "section/world-border continuity");
                }
                check(b.getFloat(128) < 0.5, "grass displacement is bounded");
                if (strength == 0) for (int i = 0; i < 36; i++) check(b.getFloat(i * 4) == 0, "zero strength identity");
                else check(b.getFloat(128) > 0.1, "visible breeze");
            }
        }
    }

    private static boolean[] render(MetalDevice device, MetalRenderPipeline pipeline, float kind, float seconds) {
        seconds = WindAnimation.seconds((long)(seconds * 20), 0);
        var settings = new ShadowCascades.Settings(1, SIZE, 0.1F, 4, 0.6F, 4);
        var camera = new Vector3d(); var rotation = new Quaternionf(); var sun = new Vector3f(0,0,1);
        var matrix = ShadowCascades.fit(settings, camera, rotation, 1, 1, sun).getFirst().cameraRelativeToShadow();
        String sampleSource = """
            #include <metal_stdlib>
            using namespace metal;
            struct V { float4 p [[position]]; };
            vertex V vs(uint id [[vertex_id]]) { return {float4(id == 0 ? float2(-1,-1) : id == 1 ? float2(3,-1) : float2(-1,3),0,1)}; }
            fragment float4 fs(V in [[stage_in]], depth2d_array<float> map [[texture(0)]]) {
                return float4(map.read(uint2(in.p.xy), 0) < 1.0 ? 1.0 : 0.0);
            }
            """;
        try (var queue = device.createCommandQueue();
             var shadows = new WorldShadowModule(device, settings);
             var frame = shadows.prepareFrame(camera, rotation, 1, 1, sun, new Matrix4f());
             var renderer = new TerrainShadowRenderer(device, resource("shared/shadows.metal"), resource("shadow.metal"), resource("shared/wind.metal"));
             var scene = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT,SIZE,SIZE,1));
             var albedo = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,SIZE,SIZE,1));
             var normal = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,SIZE,SIZE,1));
             var light = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,SIZE,SIZE,1));
             var mask = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,SIZE,SIZE,1));
             var atlas = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,2,2,1));
             var lightmap = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,1,1,1));
             var atlasView = atlas.createView(); var lightView = lightmap.createView();
             var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,MetalSampler.Filter.NEAREST,MetalSampler.AddressMode.CLAMP_TO_EDGE));
             var vertices = device.createBuffer((BASE+4)*STRIDE, MetalBuffer.StorageMode.SHARED);
             var indices = device.createBuffer(12, MetalBuffer.StorageMode.SHARED);
             var metadata = device.createBuffer(16, MetalBuffer.StorageMode.SHARED);
             var projection = device.createBuffer(64, MetalBuffer.StorageMode.SHARED);
             var section = device.createBuffer(96, MetalBuffer.StorageMode.SHARED);
             var globals = device.createBuffer(64, MetalBuffer.StorageMode.SHARED);
             var fog = device.createBuffer(48, MetalBuffer.StorageMode.SHARED);
             var draw = device.createBuffer(16, MetalBuffer.StorageMode.SHARED);
             var sample = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(sampleSource,"vs","fs",MetalTexture.Format.RGBA8_UNORM,null))) {
            for (var buffer : List.of(vertices,indices,metadata,projection,section,globals,fog,draw)) {
                try (var m = buffer.map()) { for (int i=0;i<m.bytes().capacity();i++) m.bytes().put(i,(byte)0); }
            }
            try (var m = vertices.map(); var data = metadata.map()) {
                for (int i=0;i<4;i++) {
                    boolean top = i>=2;
                    int p=(BASE+i)*STRIDE;
                    m.bytes().putFloat(p, i==0||i==3 ? -1 : 1).putFloat(p+4,top ? 2 : 0).putFloat(p+8,-2)
                        .putInt(p+12,-1).putFloat(p+16,i==0||i==3 ? 0 : 1).putFloat(p+20,top ? 1 : 0)
                        .putShort(p+24,(short)240).putShort(p+26,(short)240);
                    data.bytes().putFloat(i*4, kind > 0 && !top ? 0 : kind);
                }
            }
            try (var m = indices.map()) { for (int i : new int[]{0,1,2,0,2,3}) m.bytes().putShort((short)i); }
            try (var m = projection.map()) { matrix.get(0,m.bytes()); }
            try (var m = section.map()) { new Matrix4f().get(0,m.bytes()); m.bytes().putFloat(64,1).putInt(72,2).putInt(76,2); }
            try (var m = fog.map()) { for (int i=16;i<48;i+=4) m.bytes().putFloat(i,1000); }
            try (var m = draw.map()) { m.bytes().putInt(BASE).putInt(4).putFloat(seconds).putFloat(0); }
            // A real alpha hole, not just a transparent whole draw.
            atlas.upload(queue,0,ByteBuffer.allocateDirect(16).put(new byte[]{-1,-1,-1,-1, -1,-1,-1,0, -1,-1,-1,-1, -1,-1,-1,-1}).flip());
            lightmap.upload(queue,0,ByteBuffer.allocateDirect(4).putInt(-1).flip());
            try (var commands = queue.createCommandBuffer()) {
                try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(List.of(
                    MetalRenderPass.ColorAttachment.clear(scene,0,0,0,0), MetalRenderPass.ColorAttachment.clear(albedo,0,0,0,0),
                    MetalRenderPass.ColorAttachment.clear(normal,0,0,0,0), MetalRenderPass.ColorAttachment.clear(light,0,0,0,0)),null,0))) {
                    pass.setPipeline(pipeline); pass.setVertexBuffer(16,vertices,0);
                    var slots = pipeline.descriptor().vertexSource();
                    pass.setUniformBuffer(slot(slots,"PROJECTION"),projection,0,STAGES);
                    pass.setUniformBuffer(slot(slots,"TRANSFORMS"),section,0,STAGES);
                    pass.setUniformBuffer(slot(slots,"GLOBALS"),globals,0,STAGES);
                    pass.setUniformBuffer(slot(slots,"FOG"),fog,0,STAGES);
                    pass.setUniformBuffer(14,metadata,0,STAGES); pass.setUniformBuffer(15,draw,0,STAGES);
                    pass.setTexture(slot(slots,"SAMPLER0"),atlasView,STAGES); pass.setSampler(slot(slots,"SAMPLER0"),sampler,STAGES);
                    pass.setTexture(slot(slots,"SAMPLER2"),lightView,STAGES); pass.setSampler(slot(slots,"SAMPLER2"),sampler,STAGES);
                    pass.drawIndexed(MetalRenderPass.Primitive.TRIANGLE,indices,0,MetalRenderPass.IndexType.UINT16,6,1,BASE,0);
                }
                try (var pass = commands.beginRenderPass(shadows.depthPass())) {
                    renderer.encode(pass,frame,List.of(new TerrainShadowRenderer.Draw(ChunkSectionLayer.CUTOUT, vertices,BASE*STRIDE,
                        indices,0,MetalRenderPass.IndexType.UINT16,6,0,0,0,1,metadata,0,0,0)),atlasView,sampler,seconds);
                }
                try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(mask,0,0,0,0)))) {
                    pass.setPipeline(sample); frame.bindDepth(pass,0); pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3);
                }
                commands.commitAndWait();
            }
            var pixels=scene.readback(queue,0).order(ByteOrder.nativeOrder()); var shadow=mask.readback(queue,0);
            boolean[] coverage = new boolean[SIZE*SIZE]; int count=0, differences=0;
            for (int i=0;i<coverage.length;i++) {
                coverage[i]=Float.float16ToFloat(pixels.getShort(i*8+6))>0;
                if (coverage[i]) count++;
                if (coverage[i] != ((shadow.get(i*4)&255)>0)) differences++;
            }
            check(count>100, "fixture has real visible terrain");
            check(differences<=4, "wind/shadow coverage matches: differences="+differences+", kind="+kind+", time="+seconds);
            checkReceiverPlanes(device, normal.readback(queue,0), coverage, matrix, kind, seconds);
            return coverage;
        }
    }

    /** Compare the actual RGBA8 G-buffer with triangle planes measured from GPU-deformed vertices. */
    private static void checkReceiverPlanes(MetalDevice device, ByteBuffer normals, boolean[] coverage,
            Matrix4fc projection, float kind, float seconds) {
        String source = resource("shared/wind.metal") + """
            kernel void points(device float4* out [[buffer(0)]], uint id [[thread_position_in_grid]]) {
                float3 p = float3(id == 0 || id == 3 ? -1 : 1, id >= 2 ? 2 : 0, -2);
                float height = KIND > 0 && id < 2 ? 0 : KIND;
                out[id] = float4(p + mc_wind_offset(p, height, SECONDS), 1);
            }
            """;
        source = "#define KIND " + kind + "\n#define SECONDS " + seconds + "\n" + source;
        var points = new Vector3f[4];
        var screen = new Vector3f[4];
        try (var queue = device.createCommandQueue();
             var kernel = device.createComputePipeline(new MetalComputePipeline.Descriptor(source,"points"));
             var output = device.createBuffer(64, MetalBuffer.StorageMode.SHARED)) {
            try (var commands = queue.createCommandBuffer()) {
                try (var pass = commands.beginComputePass()) {
                    pass.setPipeline(kernel); pass.setBuffer(0,output,0); pass.dispatch(4,1,1,1,1,1);
                }
                commands.commitAndWait();
            }
            try (var mapping = output.map()) {
                for (int i=0;i<4;i++) {
                    points[i] = new Vector3f(i*16,mapping.bytes());
                    var clip = projection.transform(new Vector4f(points[i],1));
                    screen[i] = new Vector3f((clip.x/clip.w*.5F+.5F)*SIZE,
                        (clip.y/clip.w*.5F+.5F)*SIZE,0);
                }
            }
        }
        int checked = 0;
        for (int[] indices : new int[][]{{0,1,2},{0,2,3}}) {
            Vector3f a=points[indices[0]], b=points[indices[1]], c=points[indices[2]];
            var expected = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a)).normalize();
            if (expected.dot(new Vector3f(a).add(b).add(c)) > 0) expected.negate();
            Vector3f sa=screen[indices[0]], sb=screen[indices[1]], sc=screen[indices[2]];
            float determinant=(sb.y-sc.y)*(sa.x-sc.x)+(sc.x-sb.x)*(sa.y-sc.y);
            for (int y=0;y<SIZE;y++) for(int x=0;x<SIZE;x++) {
                int i=y*SIZE+x;
                if(!coverage[i]) continue;
                float u=((sb.y-sc.y)*(x+.5F-sc.x)+(sc.x-sb.x)*(y+.5F-sc.y))/determinant;
                float v=((sc.y-sa.y)*(x+.5F-sc.x)+(sa.x-sc.x)*(y+.5F-sc.y))/determinant;
                if(u<.1F || v<.1F || 1-u-v<.1F) continue;
                int r=normals.get(i*4)&255, g=normals.get(i*4+1)&255, tag=normals.get(i*4+2)&255;
                Vector3f actual;
                if(kind==0) {
                    check(tag==3,"still block retains exact Z shadow plane");
                    actual = new Vector3f(0,0,1);
                } else {
                    check((tag&192)==64,"wind-deformed triangle retains its plane, not quantized-depth derivatives");
                    float nx=((r|((tag&7)<<8))/2047F)*2-1;
                    float ny=((g|(((tag>>3)&7)<<8))/2047F)*2-1;
                    float nz=1-Math.abs(nx)-Math.abs(ny);
                    if(nz<0) {
                        float oldX=nx;
                        nx=(1-Math.abs(ny))*(oldX>=0?1:-1);
                        ny=(1-Math.abs(oldX))*(ny>=0?1:-1);
                    }
                    actual=new Vector3f(nx,ny,nz).normalize();
                }
                check(actual.distance(expected)<.003F,"stored wind plane matches real displaced triangle: "+actual+" vs "+expected);
                checked++;
            }
        }
        check(checked>50,"receiver plane regression sampled visible triangle interiors");
    }

    private static int slot(String source,String name) {
        var m=java.util.regex.Pattern.compile("#define MC_SLOT_"+name+" (\\d+)").matcher(source);
        if (!m.find()) throw new AssertionError("Missing slot "+name);
        return Integer.parseInt(m.group(1));
    }
    private static String resource(String name) {
        try (var input=WindSmoke.class.getResourceAsStream("/assets/metalcraft/shaderpacks/standard/"+name)) {
            if(input==null) throw new AssertionError(name);
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        } catch(java.io.IOException e) { throw new AssertionError(e); }
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
