package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.*;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Flat terrain stays lit through camera turns, packed-depth error, and grazing sun angles. */
final class ShadowReceiverPlaneSmoke {
    static void run(MetalDevice device, String contract) {
        String lighting;
        try (var input = ShadowReceiverPlaneSmoke.class.getResourceAsStream("/assets/metalcraft/shaderpacks/standard/shared/lighting.metal")) {
            lighting = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        String normals = lighting.substring(lighting.indexOf("static inline float2 mc_encode_normal"), lighting.indexOf("static inline bool mc_sun_active"));
        String source = "#include <metal_stdlib>\nusing namespace metal;\n" + contract + normals + """
            struct V { float4 p [[position]]; uint layer [[render_target_array_index]]; };
            vertex V vs(uint id [[vertex_id]], uint instance [[instance_id]]) {
                return {float4(id==0?float2(-1,-1):(id==1?float2(3,-1):float2(-1,3)),0,1),instance};
            }
            struct Depth { float value [[depth(any)]]; };
            fragment Depth depth_fs(V v [[stage_in]], constant MCShadowFrame& f [[buffer(0)]]) {
                float4x4 m=f.cameraRelativeToShadow[v.layer];
                float3 rx=float3(m[0].x,m[1].x,m[2].x), ry=float3(m[0].y,m[1].y,m[2].y), rz=float3(m[0].z,m[1].z,m[2].z);
                float3 n=float3(0,1,0);
                float3 plane=float3(dot(rx,n)/dot(rx,rx),dot(ry,n)/dot(ry,ry),dot(rz,n)/dot(rz,rz));
                float3 center=(m*float4(0,0,0,1)).xyz;
                float2 ndc=v.p.xy*f.inverseResolution*2-1;
                return {saturate(center.z-dot(plane.xy/plane.z,ndc-center.xy))};
            }
            struct S { float4 p [[position]]; };
            vertex S sample_vs(uint id [[vertex_id]]) {
                return {float4(id==0?float2(-1,-1):(id==1?float2(3,-1):float2(-1,3)),0,1)};
            }
            fragment float4 fs(S v [[stage_in]],constant MCShadowFrame& f [[buffer(0)]],constant float4x4* turns [[buffer(1)]],
                depth2d_array<float> map [[texture(5)]],sampler s [[sampler(5)]]) {
                uint cascade=uint(v.p.y);
                float d=(cascade==0?0:f.cascadeFar[cascade-1])+f.cascadeFar[cascade]; d*=0.5;
                float3 p=float3((v.p.x-64.0)*0.1,0,-d), n=float3(0,1,0);
                float3x3 rotation=float3x3(turns[uint(v.p.x)][0].xyz,turns[uint(v.p.x)][1].xyz,turns[uint(v.p.x)][2].xyz);
                float2 encoded=round(mc_encode_normal(rotation*n)*255.0)/255.0;
                float3 quantized=normalize(transpose(rotation)*mc_decode_normal(encoded));
                // Opposite packed-depth errors across a quad must not tilt an exact block face.
                float3 noisyP=p+float3(0,(uint(v.p.x)&1u)?0.00004:-0.00004,0);
                uint axis=mc_shadow_axis_tag(p);
                float3 receiverNormal=mc_shadow_receiver_normal(noisyP,quantized,float(axis)/255.0);
                float a=mc_shadow_visibility(noisyP,d,mc_shadow_receiver_bias(receiverNormal,d,f),f,map,s,receiverNormal);
                float b=mc_shadow_visibility(p,d,mc_shadow_receiver_bias(n,d,f),f,map,s,n);
                float legacy=mc_shadow_visibility(p,d,mc_shadow_receiver_bias(quantized,d,f),f,map,s,quantized);
                bool fallback=all(mc_shadow_receiver_normal(p,float3(1,0,0))==float3(1,0,0))
                    && all(mc_shadow_receiver_normal(float3(0),n)==n)
                    && axis==2u && mc_shadow_axis_tag(p.yxz)==1u && mc_shadow_axis_tag(p.xzy)==3u
                    && mc_shadow_axis_tag(float3(p.x,p.x,p.z))==0u
                    && mc_shadow_axis_tag(float3(0))==0u
                    && all(mc_shadow_receiver_normal(noisyP,-n,2.0/255.0)==-n)
                    && all(mc_shadow_receiver_normal(p,n,0.85)==mc_shadow_receiver_normal(p,n));
                return float4(a,b,legacy,fallback?1:0);
            }
            """;
        try (var queue=device.createCommandQueue();
             var turns=device.createBuffer(128*64,MetalBuffer.StorageMode.SHARED);
             var color=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT,128,4,1));
             var depth=device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source,"vs",source,"depth_fs",
                 List.of(MetalRenderPipeline.ColorTarget.unused()),MetalTexture.Format.DEPTH32_FLOAT,MetalRenderPipeline.VertexDescriptor.EMPTY,
                 new MetalRenderPipeline.DepthState(true,true,MetalRenderPipeline.CompareFunction.ALWAYS,0,0),MetalRenderPipeline.RasterState.DEFAULT,MetalRenderPipeline.InputPrimitiveTopology.TRIANGLE));
             var sample=device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source,"sample_vs","fs",MetalTexture.Format.RGBA16_FLOAT,null))) {
            try(var mapping=turns.map()) { for(int i=0;i<128;i++) new Matrix4f().rotateYXZ(i*.049F,-.35F+i*.003F,0).get(i*64,mapping.bytes()); }
            for(float height:new float[]{.001F,.01F,.04F,.079F,.08F,.081F,.09F,.12F,.25F,.7F}) {
                var settings=new ShadowCascades.Settings(4,1792,.05F,256,.6F,256);
                var sun=new Vector3f((float)Math.sqrt(1-height*height),height,0);
                try(var module=new WorldShadowModule(device,settings);
                    var frame=module.prepareFrame(new Vector3d(),new Quaternionf(),1.2F,1.777F,sun,new Matrix4f())) {
                    try(var commands=queue.createCommandBuffer()) {
                        try(var pass=commands.beginRenderPass(module.depthPass())) { pass.setPipeline(depth);frame.bindUniforms(pass,0,MetalRenderPass.STAGE_FRAGMENT);pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3,4,0); }
                        try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(color,0,0,0,1)))) {
                            pass.setPipeline(sample);frame.bindUniforms(pass,0,MetalRenderPass.STAGE_FRAGMENT);frame.bindDepth(pass,5);pass.setUniformBuffer(1,turns,0,MetalRenderPass.STAGE_FRAGMENT);pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3);
                        }
                        commands.commitAndWait();
                    }
                    var pixels=color.readback(queue,0).order(ByteOrder.nativeOrder());
                    float min=1, exact=1, legacy=1;
                    for(int i=0;i<512;i++) {
                        min=Math.min(min,Float.float16ToFloat(pixels.getShort(i*8)));
                        exact=Math.min(exact,Float.float16ToFloat(pixels.getShort(i*8+2)));
                        legacy=Math.min(legacy,Float.float16ToFloat(pixels.getShort(i*8+4)));
                        if(Float.float16ToFloat(pixels.getShort(i*8+6))!=1) throw new AssertionError("Receiver normal fallback failed");
                    }
                    System.out.println("Receiver normal motion: sun.y="+height+", min visibility="+min+", exact="+exact+", previous="+legacy);
                    if(min<0.999F || exact<0.999F) throw new AssertionError("Camera turn made an unoccluded receiver self-shadow: sun.y="+height+", visibility="+min);
                }
            }
        }
    }
}
