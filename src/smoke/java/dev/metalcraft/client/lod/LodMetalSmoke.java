package dev.metalcraft.client.lod;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalNative;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalSampler;
import dev.metalcraft.client.metal.MetalTexture;
import java.nio.ByteBuffer;
import java.util.List;
import org.joml.Matrix4f;

/** Hardware comparison of coarsened geometry against the finest prototype tier. */
public final class LodMetalSmoke {
    public static void main(String[] args) {
        dev.metalcraft.client.metal.MetalLodTimelineSmoke.run();
        dev.metalcraft.client.metal.MetalLodWorldSmoke.run();
        var empty = new TerrainSnapshot.Material(0,TerrainSnapshot.Policy.EMPTY,-1,0);
        var solid = new TerrainSnapshot.Material(1,TerrainSnapshot.Policy.OPAQUE_CUBE,-1,0);
        var key = new TerrainSnapshot.Key(1,"fixture",0,0,0,1,1);
        var snapshot = TerrainSnapshot.capture(key,(x,y,z)->x>=0&&x<16&&y>=0&&y<16&&z>=0&&z<16?solid:empty);
        var fine = TerrainHierarchy.build(snapshot,1);
        var coarse = TerrainHierarchy.build(snapshot,4);
        try (MetalDevice device = MetalNative.openDefaultDevice().orElseThrow();
             var queue = device.createCommandQueue();
             var renderer = new LodMetalGeometry(device,MetalTexture.Format.RGBA8_UNORM);
             var atlas = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,8,4,3));
             var atlasView = atlas.createView();
             var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,MetalSampler.Filter.NEAREST,MetalSampler.AddressMode.CLAMP_TO_EDGE));
             var matrix = device.createBuffer(64,MetalBuffer.StorageMode.SHARED)) {
            for (int mip=0;mip<3;mip++) {
                int width=8>>mip, height=4>>mip;
                ByteBuffer pixels=ByteBuffer.allocateDirect(width*height*4);
                for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
                    if(x>=width/2) pixels.put(new byte[]{0,-1,0,-1}); // poisonous neighboring tile
                    else if(mip>0) pixels.put(new byte[]{(byte)128,0,(byte)128,-1});
                    else if((x+y)%2==0) pixels.put(new byte[]{-1,0,0,-1});
                    else pixels.put(new byte[]{0,0,-1,-1});
                }
                pixels.flip(); atlas.upload(queue,mip,pixels);
            }
            try(var map=matrix.map()) {
                new Matrix4f().scaling(1f/8,1f/8,1f/32).m30(-1).m31(-1).m32(.1f).get(0,map.bytes());
            }
            LodMetalGeometry.Appearances appearances=(material,axis,sign)->new LodMetalGeometry.Appearance(0,0,.5f,1,0xffffffff);
            boolean rejected=false;
            try { LodMetalGeometry.upload(device,coarse,appearances,1).close(); }
            catch(IllegalArgumentException expected) { rejected=true; }
            check(rejected,"upload allowance enforced before allocation");
            for(int size:new int[]{128,32,16,127}) {
                byte[] reference=null;
                for(var mesh:List.of(fine,coarse)) {
                    try(var target=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,size,size,1));
                        var depth=device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.DEPTH32_FLOAT,size,size,1))) {
                        var uploaded=LodMetalGeometry.upload(device,mesh,appearances,16*1024*1024);
                        try(var commands=queue.createCommandBuffer()) {
                            try(var pass=commands.beginRenderPass(new MetalRenderPass.Descriptor(
                                    MetalRenderPass.ColorAttachment.clear(target,0,1,0,1),
                                    new MetalRenderPass.DepthAttachment(depth,MetalRenderPass.LoadAction.CLEAR,MetalRenderPass.StoreAction.STORE,0)))) {
                                renderer.encode(pass,uploaded,matrix,atlasView,sampler);
                            }
                            // Submission owns its encoded buffers even after the logical mesh is retired.
                            uploaded.close();
                            commands.commitAndWait();
                        } finally { uploaded.close(); }
                        ByteBuffer pixels=target.readback(queue,0);
                        byte[] actual=new byte[size*size*4]; pixels.get(actual);
                        for(int pixel=0;pixel<size*size;pixel++) {
                            check(actual[pixel*4+1]==0,"no holes or neighboring atlas leakage: size="+size+" tier="+mesh.tier()+" pixel="+pixel+" rgba="+(actual[pixel*4]&255)+","+(actual[pixel*4+1]&255)+","+(actual[pixel*4+2]&255)+","+(actual[pixel*4+3]&255));
                            check((actual[pixel*4+3]&255)==255,"opaque coverage preserved");
                        }
                        if(reference==null) reference=actual;
                        else {
                            int mismatches=0;
                            for(int i=0;i<actual.length;i++) if(Math.abs((actual[i]&255)-(reference[i]&255))>1) mismatches++;
                            check(mismatches==0,"fine/coarse texture and coverage equality at " + size + ": " + mismatches);
                        }
                        if(size==128) {
                            check((actual[0]&255)!= (actual[2*4]&255),"texture varies inside a block");
                            check(actual[0]==actual[8*4],"texture repeats at block frequency");
                        }
                        ByteBuffer depths=depth.readback(queue,0).order(java.nio.ByteOrder.nativeOrder());
                        for(int i=0;i<size*size;i++) check(Math.abs(depths.getFloat(i*4)-.6f)<.00001f,"reverse-Z depth and surface ownership");
                    }
                }
            }
            System.out.println("LOD Metal prototype passed: tiers 1/4, repeated atlas texture, bounded mips, odd dimensions, reverse-Z coverage and in-flight retirement");
        }
    }

    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
