package dev.metalcraft.client.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import java.util.List;

/** GLSL block payloads can end before the padded MSL struct size. Keep the public slice logical. */
public final class MetalUniformTailSmoke {
    public static void run() {
        var gpu=new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(),(id,type)->null);
        String source="""
                #include <metal_stdlib>
                using namespace metal;
                struct Small { float3 color; };
                struct Large { float4 a; float4 b; float4 c; float2 tail; };
                vertex float4 vs(uint id [[vertex_id]]) {
                    float2 p=float2((id<<1)&2,id&2); return float4(p*2-1,0,1);
                }
                fragment float4 fs(constant Small& s [[buffer(0)]], constant Large& l [[buffer(1)]]) {
                    return float4(s.color.x,l.tail.x,l.tail.y,1);
                }
                """;
        try(var small=gpu.createBuffer(()->"12-byte GLSL block",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,12);
            var large=gpu.createBuffer(()->"56-byte GLSL block",GpuBuffer.USAGE_UNIFORM|GpuBuffer.USAGE_MAP_WRITE,56);
            var queue=gpu.metal().createCommandQueue();
            var target=gpu.metal().createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,4,4,1));
            var pipeline=gpu.metal().createRenderPipeline(new MetalRenderPipeline.Descriptor(source,"vs",source,"fs",
                    List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)),null,
                    MetalRenderPipeline.VertexDescriptor.EMPTY,MetalRenderPipeline.DepthState.DISABLED,MetalRenderPipeline.RasterState.DEFAULT))) {
            if(small.size()!=12 || large.size()!=56 || small.metal().size()<16 || large.metal().size()<64)
                throw new AssertionError("Uniform tails need Metal struct padding without extending the logical slice");
            boolean rejected=false;
            try(var ignored=small.map(0,16,false,true)) { }
            catch(IllegalArgumentException expected) { rejected=true; }
            if(!rejected) throw new AssertionError("Physical padding must not extend the public mapping range");
            try(var a=small.map(0,12,false,true);var b=large.map(0,56,false,true)) {
                a.data().putFloat(0,.25f); b.data().putFloat(48,.5f).putFloat(52,.75f);
            }
            try(var commands=queue.createCommandBuffer()) {
                try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(target,0,0,0,0)))) {
                    pass.setPipeline(pipeline);
                    pass.setUniformBuffer(0,small.metal(),0,MetalRenderPass.STAGE_FRAGMENT);
                    pass.setUniformBuffer(1,large.metal(),0,MetalRenderPass.STAGE_FRAGMENT);
                    pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3,1,0);
                }
                commands.commitAndWait();
            }
            var pixels=target.readback(queue,0);
            for(int p=0;p<16;p++) for(int c=0;c<4;c++) {
                int expected=new int[]{64,128,191,255}[c];
                if(Math.abs((pixels.get(p*4+c)&255)-expected)>1) throw new AssertionError("Padded uniform member offset changed");
            }
        } finally { gpu.close(); }
        System.out.println("Metal uniform tails passed: logical 12/56-byte blocks, padded allocation, exact member readback");
    }
}
