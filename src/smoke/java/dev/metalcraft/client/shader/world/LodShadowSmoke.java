package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.lod.LodBakedMesh;
import dev.metalcraft.client.lod.LodWorldMesh;
import dev.metalcraft.client.metal.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Every shadow texel must agree between original and resident LOD BLOCK vertices. */
final class LodShadowSmoke {
    static void run(MetalDevice device, String contract, String source) {
        var sprite = new LodBakedMesh.Sprite("shadow-fixture", 0, 0, 1, 1);
        var quads = new ArrayList<LodBakedMesh.Quad>();
        for (int z=0;z<16;z++) for (int x=0;x<16;x++) quads.add(new LodBakedMesh.Quad(sprite, List.of(
                new LodBakedMesh.Vertex(x,8,z,0,0,-1,0),new LodBakedMesh.Vertex(x,8,z+1,0,1,-1,0),
                new LodBakedMesh.Vertex(x+1,8,z+1,1,1,-1,0),new LodBakedMesh.Vertex(x+1,8,z,1,0,-1,0))));
        var original = new LodBakedMesh.Simplified(true,List.of(),quads,quads.size());
        var coarse = new LodBakedMesh(quads).simplify(4);
        try (var queue=device.createCommandQueue();
             var renderer=new TerrainShadowRenderer(device,contract,source);
             var fine=device.createBuffer(256L*4*28,MetalBuffer.StorageMode.SHARED);
             var metadata=device.createBuffer(256L*4*48,MetalBuffer.StorageMode.SHARED);
             var indices=device.createBuffer(256L*6*4,MetalBuffer.StorageMode.SHARED);
             var lod=LodWorldMesh.upload(device,coarse);
             var atlas=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,1,1,1));
             var atlasView=atlas.createView();
             var sampler=device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,
                     MetalSampler.Filter.NEAREST,MetalSampler.AddressMode.CLAMP_TO_EDGE));
             var output=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,128,128,1))) {
            try(var v=fine.map();var m=metadata.map()) { LodWorldMesh.write(original,v.bytes(),m.bytes()); }
            try(var mapping=indices.map()) {
                for(int q=0;q<256;q++) for(int i:new int[]{0,1,2,2,3,0}) mapping.bytes().putInt(q*4+i);
            }
            atlas.upload(queue,0,ByteBuffer.allocateDirect(4).putInt(-1).flip());
            for(int count=1;count<=4;count++) {
                var settings=new ShadowCascades.Settings(count,128,.1f,64,.6f,96);
                String compare="""
                        #include <metal_stdlib>
                        using namespace metal;
                        struct V { float4 p [[position]]; };
                        vertex V vs(uint id [[vertex_id]]) {
                            return {float4(id==0?float2(-1,-1):(id==1?float2(3,-1):float2(-1,3)),0,1)};
                        }
                        fragment float4 fs(V v [[stage_in]],depth2d_array<float> a [[texture(5)]],
                                depth2d_array<float> b [[texture(6)]],sampler s [[sampler(5)]]) {
                            bool same=true,covered=false;
                            for(uint layer=0;layer<%d;layer++) {
                                float da=a.sample(s,v.p.xy/128.0,layer),db=b.sample(s,v.p.xy/128.0,layer);
                                same= same && abs(da-db)<0.000001;
                                covered=covered || da<1.0;
                            }
                            return float4(same?0:1,covered?1:0,0,1);
                        }
                        """.formatted(count);
                try(var a=new WorldShadowModule(device,settings);var b=new WorldShadowModule(device,settings);
                    var af=a.prepareFrame(new Vector3d(8,0,0),new Quaternionf(),1,1,new Vector3f(0,1,0),new Matrix4f());
                    var bf=b.prepareFrame(new Vector3d(8,0,0),new Quaternionf(),1,1,new Vector3f(0,1,0),new Matrix4f());
                    var pipeline=device.createRenderPipeline(new MetalRenderPipeline.Descriptor(compare,"vs","fs",MetalTexture.Format.RGBA8_UNORM,null))) {
                    try(var commands=queue.createCommandBuffer()) {
                        try(var pass=commands.beginRenderPass(a.depthPass())) {
                            renderer.encode(pass,af,List.of(new TerrainShadowRenderer.Draw(ChunkSectionLayer.SOLID,
                                    fine,0,indices,0,MetalRenderPass.IndexType.UINT32,256*6,-8,0,-32)),atlasView,sampler);
                        }
                        try(var pass=commands.beginRenderPass(b.depthPass())) {
                            renderer.encode(pass,bf,List.of(new TerrainShadowRenderer.Draw(ChunkSectionLayer.SOLID,
                                    lod.vertices(),0,indices,0,MetalRenderPass.IndexType.UINT32,lod.indexCount(),-8,0,-32)),atlasView,sampler);
                        }
                        try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(output,1,0,0,1)))) {
                            pass.setPipeline(pipeline);
                            af.bindDepth(pass,5);bf.bindDepth(pass,6);
                            pass.setSampler(5,sampler,MetalRenderPass.STAGE_FRAGMENT);
                            pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3);
                        }
                        commands.commitAndWait();
                    }
                    var pixels=output.readback(queue,0);
                    int covered=0;
                    for(int p=0;p<128*128;p++) {
                        if(pixels.get(p*4)!=0) throw new AssertionError("LOD shadow coverage/depth changed, cascades="+count);
                        if(pixels.get(p*4+1)!=0) covered++;
                    }
                    if(covered<20) throw new AssertionError("LOD shadow fixture had insufficient caster coverage: "+covered);
                }
            }
        }
        System.out.println("LOD shadow Metal passed: every texel of 1–4 cascades matches original BLOCK coverage/depth");
    }
}
