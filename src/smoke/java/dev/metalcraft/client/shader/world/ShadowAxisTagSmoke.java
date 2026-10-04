package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.*;
import java.nio.ByteOrder;
import org.joml.Matrix4f;

/** Real perspective interpolation at 4K must retain the exact plane of block faces. */
final class ShadowAxisTagSmoke {
    static void run(MetalDevice device, String contract) {
        String source = "#include <metal_stdlib>\nusing namespace metal;\n" + contract + """
            struct V { float4 p [[position]]; float3 local; };
            vertex V vs(uint id [[vertex_id]], constant float4x4& m [[buffer(0)]],
                    device const packed_float3* points [[buffer(1)]]) {
                float3 p=points[id];float4 clip=m*float4(p,1);clip.y=-clip.y;
                return {clip,p};
            }
            fragment float4 fs(V v [[stage_in]]) {
                uint axis=mc_shadow_axis_tag(v.local);
                return float4(axis==2u?1:0,1,0,1);
            }
            """;
        try(var queue=device.createCommandQueue();
            var matrix=device.createBuffer(64,MetalBuffer.StorageMode.SHARED);
            var vertices=device.createBuffer(6*12,MetalBuffer.StorageMode.SHARED);
            var image=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT,3840,2160,1));
            var pipeline=device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source,"vs","fs",MetalTexture.Format.RGBA16_FLOAT,null))) {
            try(var mapping=vertices.map()) {
                for(int i:new int[]{0,1,2,0,2,3}) mapping.bytes()
                    .putFloat(i==0||i==3?0:16).putFloat(9).putFloat(i<2?0:16);
            }
            for(float yaw:new float[]{0,.13F,.29F}) {
                try(var mapping=matrix.map()) {
                    new Matrix4f().perspective((float)Math.toRadians(70),16F/9F,.05F,256,true)
                        .rotateX(.55F).rotateY(yaw).translate(-8,-20,-20).get(0,mapping.bytes());
                }
                try(var commands=queue.createCommandBuffer()) {
                    try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(image,0,0,0,0)))) {
                        pass.setPipeline(pipeline);pass.setUniformBuffer(0,matrix,0,MetalRenderPass.STAGE_VERTEX);
                        pass.setUniformBuffer(1,vertices,0,MetalRenderPass.STAGE_VERTEX);
                        pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,6);
                    }
                    commands.commitAndWait();
                }
                var pixels=image.readback(queue,0).order(ByteOrder.nativeOrder());
                int covered=0,bad=0;
                for(int i=0;i<3840*2160;i++) if(Float.float16ToFloat(pixels.getShort(i*8+2))>.5F) {
                    covered++;if(Float.float16ToFloat(pixels.getShort(i*8))<.5F) bad++;
                }
                System.out.println("4K interpolated block plane: yaw="+yaw+", lost tags="+bad+"/"+covered);
                if(covered<100000 || bad>covered/1000) throw new AssertionError("Raster interpolation loses exact shadow planes");
            }
        }
    }
}
