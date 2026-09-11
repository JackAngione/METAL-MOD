package dev.metalcraft.client.metal;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.metalcraft.client.lod.LodBakedMesh;
import dev.metalcraft.client.lod.LodWorldMesh;
import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

/** Actual world vertex/fragment variants, compared against unsimplified BLOCK draws on Metal. */
public final class MetalLodWorldSmoke {
    public static void run() {
        var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), LinearWorldShadersSmoke::expanded);
        try {
            RenderPipeline info = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
                    .withLocation(Identifier.parse("metalcraft:smoke/lod_world")).withCull(false).build();
            var sprite = new LodBakedMesh.Sprite("fixture", 0, 0, .5f, 1);
            var quads = new ArrayList<LodBakedMesh.Quad>();
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                quads.add(new LodBakedMesh.Quad(sprite, List.of(
                        new LodBakedMesh.Vertex(x,y,8,0,0,-1,0x00f000f0),
                        new LodBakedMesh.Vertex(x+1,y,8,.5f,0,-1,0x00f000f0),
                        new LodBakedMesh.Vertex(x+1,y+1,8,.5f,1,-1,0x00f000f0),
                        new LodBakedMesh.Vertex(x,y+1,8,0,1,-1,0x00f000f0))));
            }
            var original = new LodBakedMesh.Simplified(true,List.of(),quads,quads.size());
            var coarse = new LodBakedMesh(quads).simplify(4);
            var variants = new ArrayList<MetalCompiledRenderPipeline>();
            var noPack = gpu.lodPipeline(info, false);
            check(noPack != null, "actual no-pack LOD variant compiles");
            variants.add(noPack);
            for (boolean linear : new boolean[]{false, true}) {
                ShaderPackRuntime runtime = gpu.shaderPackRuntime();
                runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
                runtime.resize(128, 128);
                var geometry = runtime.worldGeometry();
                geometry.beginFrame(linear ? FrameBindings.ColorEncoding.LINEAR_SRGB : FrameBindings.ColorEncoding.LEGACY_ENCODED);
                var stand = geometry.preflightLodTerrain(info);
                check(stand != null, "Standard terrain preflight");
                var standard = gpu.lodPipeline(stand, linear);
                check(standard != null, "actual Standard LOD variant compiles");
                compare(gpu, standard, original, coarse, true, linear);
            }
            compare(gpu, noPack, original, coarse, false, false);
            check(gpu.lodPipeline(RenderPipelines.TRANSLUCENT_TERRAIN, false) == null, "translucent pipeline cannot inherit opaque LOD");
            gpu.clearPipelineCache();
            check(!noPack.isValid(), "resource reload retires LOD pipeline variants");
            gpu.setReloadShaderSource((id,type) -> type == com.mojang.blaze3d.shaders.ShaderType.FRAGMENT
                    ? LinearWorldShadersSmoke.expanded(id,type) + "\nvoid custom_lod_source() {}\n"
                    : LinearWorldShadersSmoke.expanded(id,type));
            check(gpu.lodPipeline(info,false) == null, "resource-replaced shader cannot inherit an old LOD contract");
            gpu.setReloadShaderSource((id,type) -> null);
            check(gpu.lodPipeline(info,false) == null, "missing reload source cannot fall back to stale source-cache data");
            gpu.setReloadShaderSource(null);
            check(gpu.lodPipeline(info,false) != null, "restoring verified sources recovers the LOD pipeline");
        } finally { gpu.close(); }
        System.out.println("LOD world Metal passed: original vs merged BLOCK, no pack/Standard legacy/HDR, atlas mips, depth, odd sizes and reload retirement");
    }

    private static void compare(MetalGpuDevice gpu, MetalCompiledRenderPipeline compiled, LodBakedMesh.Simplified original,
            LodBakedMesh.Simplified coarse, boolean standard, boolean linear) {
        var device = gpu.metal();
        try (var queue = device.createCommandQueue();
             var atlas = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,8,4,3));
             var lightmap = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,16,16,1));
             var atlasView = atlas.createView(); var lightView = lightmap.createView();
             var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,MetalSampler.Filter.NEAREST,MetalSampler.AddressMode.CLAMP_TO_EDGE));
             var projection = device.createBuffer(64,MetalBuffer.StorageMode.SHARED);
             var section = device.createBuffer(96,MetalBuffer.StorageMode.SHARED);
             var globals = device.createBuffer(64,MetalBuffer.StorageMode.SHARED);
             var fog = device.createBuffer(48,MetalBuffer.StorageMode.SHARED)) {
            for (var uniform : List.of(projection,section,globals,fog)) try (var m = uniform.map()) {
                var b = m.bytes(); for (int i=0;i<b.capacity();i++) b.put(i,(byte)0);
            }
            try (var m=projection.map()) { new Matrix4f().scaling(.125f,.125f,0).m30(-1).m31(-1).m32(.5f).get(0,m.bytes()); }
            try (var m=section.map()) {
                new Matrix4f().get(0,m.bytes()); m.bytes().putFloat(64,1).putInt(72,8).putInt(76,4);
            }
            try (var m=fog.map()) { m.bytes().putFloat(16,100).putFloat(20,200).putFloat(24,100).putFloat(28,200); }
            for (int mip=0;mip<3;mip++) {
                int w=8>>mip,h=4>>mip;
                var b=ByteBuffer.allocateDirect(w*h*4);
                for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                    if(x>=w/2) b.put(new byte[]{0,-1,0,-1});
                    else if(mip>0) b.put(new byte[]{(byte)128,0,(byte)128,-1});
                    else b.put((x+y)%2==0?new byte[]{-1,0,0,-1}:new byte[]{0,0,-1,-1});
                }
                atlas.upload(queue,mip,b.flip());
            }
            var white=ByteBuffer.allocateDirect(16*16*4); while(white.hasRemaining()) white.putInt(-1); lightmap.upload(queue,0,white.flip());
            for(int size:new int[]{128,32,16,127}) {
                float[] reference=null;
                for(var mesh:List.of(original,coarse)) {
                    var format=linear?MetalTexture.Format.RGBA16_FLOAT:MetalTexture.Format.RGBA8_UNORM;
                    try(var vertices=device.createBuffer((long)mesh.quads()*4*28,MetalBuffer.StorageMode.SHARED);
                        var metadata=device.createBuffer((long)mesh.quads()*4*48,MetalBuffer.StorageMode.SHARED);
                        var indices=device.createBuffer((long)mesh.quads()*6*4,MetalBuffer.StorageMode.SHARED);
                        var scene=device.createTexture(new MetalTexture.Descriptor(format,size,size,1));
                        var a=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,size,size,1));
                        var n=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,size,size,1));
                        var l=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,size,size,1));
                        var depth=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.DEPTH32_FLOAT,size,size,1))) {
                        try(var v=vertices.map();var m=metadata.map()) { LodWorldMesh.write(mesh,v.bytes(),m.bytes()); }
                        try(var m=indices.map()) { for(int q=0;q<mesh.quads();q++) for(int i:new int[]{0,1,2,2,3,0}) m.bytes().putInt(q*4+i); }
                        try(var commands=queue.createCommandBuffer()) {
                            var colors=new ArrayList<MetalRenderPass.ColorAttachment>();
                            colors.add(MetalRenderPass.ColorAttachment.clear(scene,0,1,0,1));
                            if(standard) for(var t:List.of(a,n,l)) colors.add(MetalRenderPass.ColorAttachment.clear(t,0,0,0,0));
                            try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(colors,
                                    new MetalRenderPass.DepthAttachment(depth,MetalRenderPass.LoadAction.CLEAR,MetalRenderPass.StoreAction.STORE,0),0))) {
                                pass.setPipeline(compiled.metal(true,format));
                                pass.setVertexBuffer(16,vertices,0);
                                pass.setUniformBuffer(14,metadata,0,MetalRenderPass.STAGE_VERTEX);
                                for(int slot=0;slot<compiled.uniformLayout().size();slot++) {
                                    var name=compiled.uniformLayout().get(slot).name();
                                    var buffer=switch(name) { case "Projection"->projection;case "ChunkSection"->section;case "Globals"->globals;case "Fog"->fog;default->throw new AssertionError(name); };
                                    pass.setUniformBuffer(slot,buffer,0,compiled.bufferStages(slot));
                                }
                                for(int s=0;s<compiled.samplerLayout().size();s++) {
                                    int slot=compiled.uniformLayout().size()+s;
                                    pass.setTexture(slot,compiled.samplerLayout().get(s).equals("Sampler0")?atlasView:lightView,compiled.textureStages(slot));
                                    pass.setSampler(slot,sampler,compiled.textureStages(slot));
                                }
                                pass.drawIndexed(MetalRenderPass.Primitive.TRIANGLE,indices,0,MetalRenderPass.IndexType.UINT32,mesh.quads()*6,1,0,0);
                            }
                            commands.commitAndWait();
                        }
                        var pixels=scene.readback(queue,0).order(ByteOrder.nativeOrder());
                        var depths=depth.readback(queue,0).order(ByteOrder.nativeOrder());
                        float[] actual=new float[size*size*4];
                        int differences=0;
                        for(int p=0;p<size*size;p++) {
                            for(int c=0;c<4;c++) actual[p*4+c]=linear?Float.float16ToFloat(pixels.getShort((p*4+c)*2)):(pixels.get(p*4+c)&255)/255f;
                            check(actual[p*4+1]==0&&actual[p*4+3]==1,"no holes, alpha loss or neighboring atlas leakage");
                            check(Math.abs(depths.getFloat(p*4)-.5f)<1e-6,"world reverse-Z depth unchanged");
                            if(reference!=null&&Math.abs(reference[p*4]-actual[p*4])>.01) {
                                differences++;
                                // At odd extents the center pixel lies exactly on a block edge.
                                // Original unit triangles and a repeated rectangle may select
                                // opposite texels at that discontinuity; every other pixel must match.
                                check(size%2==1&&(p%size==size/2||p/size==size/2),
                                        "texture mismatch away from an exact block-edge tie: pixel="+p+" size="+size);
                            }
                        }
                        if(reference==null) reference=actual;
                        else check(differences<=2*size,"original/merged color mismatch: size="+size+" pixels="+differences+" standard="+standard+" linear="+linear);
                    }
                }
            }
        }
    }

    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
