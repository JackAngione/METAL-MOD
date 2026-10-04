package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Track a real caster's shadow while only the sun moves, near and far from world origin. */
final class ShadowSunMotionSmoke {
    static void run(MetalDevice device, String contract) {
        String terrain;
        try (var in = ShadowSunMotionSmoke.class.getResourceAsStream("/assets/metalcraft/shaderpacks/standard/shadow.metal")) {
            terrain = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        String source = "#include <metal_stdlib>\nusing namespace metal;\n" + contract + """
            struct V { float4 p [[position]]; };
            vertex V vs(uint id [[vertex_id]]) {
                return {float4(id==0?float2(-1,-1):(id==1?float2(3,-1):float2(-1,3)),0,1)};
            }
            fragment float4 fs(V v [[stage_in]], constant MCShadowFrame& f [[buffer(0)]],
                    depth2d_array<float> map [[texture(5)]], sampler s [[sampler(5)]]) {
                float3 receiver = float3(-2.0 + v.p.x * (10.0/3840.0), 0, -10);
                float visibility = mc_shadow_visibility(receiver,10,0,f,map,s,float3(0,1,0));
                return float4(visibility,0,0,1);
            }
            """;
        var settings = new ShadowCascades.Settings(4,1024,.05F,256,.6F,256);
        try (var queue=device.createCommandQueue();
             var renderer=new TerrainShadowRenderer(device,contract,terrain);
             var vertices=device.createBuffer(4*28,MetalBuffer.StorageMode.SHARED);
             var indices=device.createBuffer(6*4,MetalBuffer.StorageMode.SHARED);
             var atlas=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,1,1,1));
             var atlasView=atlas.createView();
             var sampler=device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,MetalSampler.Filter.NEAREST,MetalSampler.AddressMode.CLAMP_TO_EDGE));
             var output=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT,3840,1,1));
             var pipeline=device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source,"vs","fs",MetalTexture.Format.RGBA16_FLOAT,null))) {
            try(var mapping=vertices.map()) {
                var bytes=mapping.bytes();
                for(int i=0;i<bytes.capacity();i++) bytes.put(i,(byte)0);
                for(int i=0;i<4;i++) {
                    bytes.putFloat(i*28,i==0||i==3?-2:2).putFloat(i*28+4,4).putFloat(i*28+8,i<2?-12:-8);
                }
            }
            try(var mapping=indices.map()) { for(int i:new int[]{0,1,2,0,2,3}) mapping.bytes().putInt(i); }
            atlas.upload(queue,0,ByteBuffer.allocateDirect(4).putInt(-1).flip());
            var draws=List.of(new TerrainShadowRenderer.Draw(ChunkSectionLayer.SOLID,vertices,0,indices,0,MetalRenderPass.IndexType.UINT32,6,0,0,0));
            double worst=0;
            for(var camera:List.of(new Vector3d(),new Vector3d(4702.5,216,546.5),new Vector3d(29_000_000,128,-29_000_000))) {
                double previous=0, previousExpected=0, variation=0, maxJump=0;
                try(var module=new WorldShadowModule(device,settings)) {
                    for(int step=0;step<96;step++) {
                        float angle=.7F+step*.00002F;
                        var sun=new Vector3f(-(float)Math.sin(angle),(float)Math.cos(angle),0);
                        try(var frame=module.prepareFrame(camera,new Quaternionf(),(float)Math.toRadians(70),16F/9F,sun,new Matrix4f());
                            var commands=queue.createCommandBuffer()) {
                            try(var pass=commands.beginRenderPass(module.depthPass())) { renderer.encode(pass,frame,draws,atlasView,sampler); }
                            try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(output,1,0,0,1)))) {
                                pass.setPipeline(pipeline);frame.bindUniforms(pass,0,MetalRenderPass.STAGE_FRAGMENT);frame.bindDepth(pass,5);
                                pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3);
                            }
                            commands.commitAndWait();
                        }
                        var pixels=output.readback(queue,0).order(ByteOrder.nativeOrder());
                        double sum=0,weighted=0;
                        for(int x=0;x<3840;x++) {
                            float shade=1-Float.float16ToFloat(pixels.getShort(x*8));
                            sum+=shade;weighted+=shade*(-2+(x+.5)*10/3840);
                        }
                        if(sum<500) throw new AssertionError("Sun-motion caster is not visible");
                        double centroid=weighted/sum, expected=4*Math.tan(angle);
                        if(step>0) { double jump=Math.abs(centroid-previous-(expected-previousExpected));variation+=jump;maxJump=Math.max(maxJump,jump); }
                        previous=centroid;previousExpected=expected;
                    }
                }
                double mean=variation/95;
                worst=Math.max(worst,mean);
                System.out.println("Shadow sun motion: camera="+camera+", mean unphysical movement="+mean+", max="+maxJump);
            }
            if(worst>.005) throw new AssertionError("Moving sunlight makes stationary shadows dance: "+worst);
        }
    }
}
