package dev.metalcraft.client.metal;

import dev.metalcraft.client.shader.world.ShadowCascades;
import dev.metalcraft.client.shader.world.TerrainShadowRenderer;
import dev.metalcraft.client.shader.world.WorldShadowModule;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Actual cutout foliage casters sampled at fixed world receivers while the camera/grid moves. */
final class WindShadowStabilitySmoke {
    private static final int SIZE = 128;

    public static void main(String[] args) {
        try (var device = MetalNative.openDefaultDevice().orElseThrow()) { run(device); }
    }

    static void run(MetalDevice device) {
        check(device,false);
        check(device,true);
    }

    private static void check(MetalDevice device, boolean celestial) {
        String contract = resource("shared/shadows.metal");
        String source = "#define MC_TEST_CELESTIAL " + (celestial?1:0) + "\n#include <metal_stdlib>\nusing namespace metal;\n" + contract + """
            struct V { float4 position [[position]]; };
            vertex V vs(uint id [[vertex_id]]) {
                return {float4(id == 0 ? float2(-1,-1) : id == 1 ? float2(3,-1) : float2(-1,3),0,1)};
            }
            fragment float4 fs(V in [[stage_in]], constant MCShadowFrame& frame [[buffer(0)]],
                    constant float4& cameraDelta [[buffer(1)]], depth2d_array<float> map [[texture(0)]],
                    sampler nearestSampler [[sampler(0)]]) {
                // These receivers stay fixed in world space, independently of the camera.
                float2 world = in.position.xy * (3.0 / 128.0) - float2(1.5,0.5);
                float3 receiver = (MC_TEST_CELESTIAL ? float3(-3.0,world.y-4.0/3.0,world.x)
                    : float3(world,-3.0)) - cameraDelta.xyz;
                float visibility = mc_shadow_visibility_in_cascade(receiver,0u,0.0,frame,map,
                    nearestSampler,MC_TEST_CELESTIAL ? float3(1,0,0) : float3(0,0,1));
                return float4(visibility,0,0,1);
            }
            """;
        var settings = new ShadowCascades.Settings(1,256,.1F,4,.6F,4);
        var rotation = new Quaternionf();
        var sun = celestial ? new Vector3f(.6F,.8F,0) : new Vector3f(0,0,1);
        float texel = ShadowCascades.fit(settings,new Vector3d(),rotation,1,1,sun).getFirst().texelSize();
        try (var queue = device.createCommandQueue();
             var renderer = new TerrainShadowRenderer(device,contract,resource("shadow.metal"),resource("shared/wind.metal"));
             var vertices = device.createBuffer(4 * 28,MetalBuffer.StorageMode.SHARED);
             var indices = device.createBuffer(12,MetalBuffer.StorageMode.SHARED);
             var metadata = device.createBuffer(16,MetalBuffer.StorageMode.SHARED);
             var delta = device.createBuffer(16,MetalBuffer.StorageMode.SHARED);
             var atlas = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,4,4,1));
             var atlasView = atlas.createView();
             var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,
                 MetalSampler.Filter.NEAREST,MetalSampler.AddressMode.CLAMP_TO_EDGE));
             var output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT,SIZE,SIZE,1));
             var sample = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source,"vs","fs",MetalTexture.Format.RGBA16_FLOAT,null))) {
            try (var mapping = vertices.map()) {
                for (int i=0;i<4;i++) {
                    int p=i*28;
                    boolean top=i>=2, left=i==0||i==3;
                    mapping.bytes().putFloat(p,celestial?-2:(left?-1:1)).putFloat(p+4,top?2:0).putFloat(p+8,celestial?(left?-1:1):-2)
                        .putInt(p+12,-1).putFloat(p+16,left?0:1).putFloat(p+20,top?1:0)
                        .putShort(p+24,(short)240).putShort(p+26,(short)240);
                }
            }
            try (var mapping = indices.map()) { for (int i:new int[]{0,1,2,0,2,3}) mapping.bytes().putShort((short)i); }
            ByteBuffer pixels=ByteBuffer.allocateDirect(64);
            for (int y=0;y<4;y++) for(int x=0;x<4;x++) {
                // Asymmetric interior holes make sampling/coverage errors visible in both axes.
                pixels.put((byte)255).put((byte)255).put((byte)255)
                    .put((byte)((x==1&&y==1 || x==2&&y==2)?0:255));
            }
            atlas.upload(queue,0,pixels.flip());
            float maximumError=0;
            for (float kind:new float[]{-1,2}) {
                try (var mapping=metadata.map()) {
                    for(int i=0;i<4;i++) mapping.bytes().putFloat(i*4,kind>0&&i<2?0:kind);
                }
                float[][] references=new float[2][];
                // Integer multiples preserve the periodic wind origin, including near world limits.
                for(int origin:new int[]{0,1024,-1024,29_999_104,-29_999_104}) {
                    try(var shadows=new WorldShadowModule(device,settings)) {
                        for(float[] pose:new float[][]{{0,0,0},{.1F,0,0},{.4F,0,0},{.6F,0,0},{1.1F,0,0},{-.6F,0,0},{0,5,3},{0,-5,-3}}) {
                            float fraction=pose[0];
                            var frameRotation=new Quaternionf().rotateYXZ((float)Math.toRadians(pose[1]),(float)Math.toRadians(pose[2]),0);
                            float dx=fraction*texel, dy=fraction*texel*.7F, dz=celestial?fraction*texel:0;
                            try(var frame=shadows.prepareFrame(new Vector3d((double)origin+dx,(double)origin+dy,(double)origin+dz),frameRotation,1,1,sun,new Matrix4f())) {
                                try(var mapping=delta.map()) { mapping.bytes().putFloat(0,dx).putFloat(4,dy).putFloat(8,dz).putFloat(12,0); }
                                var draw=new TerrainShadowRenderer.Draw(ChunkSectionLayer.CUTOUT,vertices,0,indices,0,
                                    MetalRenderPass.IndexType.UINT16,6,-dx,-dy,-dz,1,metadata,origin,origin,origin);
                                for(float seconds:new float[]{17.25F,1041.25F,0,1024}) {
                                    try(var commands=queue.createCommandBuffer()) {
                                        try(var pass=commands.beginRenderPass(shadows.depthPass())) { renderer.encode(pass,frame,List.of(draw),atlasView,sampler,seconds); }
                                        try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(output,1,0,0,1)))) {
                                            pass.setPipeline(sample); frame.bindUniforms(pass,0,MetalRenderPass.STAGE_FRAGMENT); frame.bindDepth(pass,0);
                                            pass.setUniformBuffer(1,delta,0,MetalRenderPass.STAGE_FRAGMENT); pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3);
                                        }
                                        commands.commitAndWait();
                                    }
                                    var result=output.readback(queue,0).order(ByteOrder.nativeOrder());
                                    int clock=seconds==0||seconds==1024?1:0;
                                    if(references[clock]==null) references[clock]=new float[SIZE*SIZE];
                                    float[] reference=references[clock];
                                    int shadowed=0, lit=0;
                                    for(int i=0;i<reference.length;i++) {
                                        float actual=Float.float16ToFloat(result.getShort(i*8));
                                        if(origin==0&&fraction==0&&pose[1]==0&&pose[2]==0&&(seconds==17.25F||seconds==0)) reference[i]=actual;
                                        float error=Math.abs(actual-reference[i]); maximumError=Math.max(maximumError,error);
                                        if(!Float.isFinite(actual)||error>.003F) throw new AssertionError("Foliage cast shadow moved with camera/wrap: kind="+kind
                                            +", celestial="+celestial+", origin="+origin+", texelFraction="+fraction+", yaw="+pose[1]+", pitch="+pose[2]+", seconds="+seconds+", pixel="+i+", error="+error);
                                        if(actual<.1F) shadowed++; if(actual>.9F) lit++;
                                    }
                                    if(shadowed<500||lit<500) throw new AssertionError("Foliage stability fixture lacks lit/shadowed receivers");
                                    if(reference[53*SIZE+53]<.9F || reference[32*SIZE+96]>.1F)
                                        throw new AssertionError("Foliage fixture must retain an interior alpha hole and opaque cast shadow; celestial="+celestial+", kind="+kind);
                                }
                            }
                        }
                    }
                }
                int animated=0;
                for(int i=0;i<references[0].length;i++) if(Math.abs(references[0][i]-references[1][i])>.02F) animated++;
                if(animated<16) throw new AssertionError("Foliage caster fixture must animate its actual shadow: celestial="+celestial+", kind="+kind+", changed="+animated);
            }
            System.out.println("Foliage cast shadows: celestial="+celestial+", alpha holes, animated silhouettes, camera translation/turns, animation period/seam and periodic origins near world borders passed; max visibility error="+maximumError);
        }
    }

    private static String resource(String name) {
        try(var input=WindShadowStabilitySmoke.class.getResourceAsStream("/assets/metalcraft/shaderpacks/standard/"+name)) {
            if(input==null) throw new AssertionError(name);
            return new String(input.readAllBytes(),StandardCharsets.UTF_8);
        } catch(java.io.IOException error) { throw new AssertionError(error); }
    }
}
