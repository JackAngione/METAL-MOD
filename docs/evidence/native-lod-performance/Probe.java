package dev.metalcraft.client.chunk;

import java.nio.ByteBuffer;
import java.util.*;
import java.lang.management.ManagementFactory;

/** Isolated paired CPU probe; no Minecraft process or GPU timing. */
public final class Probe {
    static volatile long sink;
    static final com.sun.management.ThreadMXBean MEMORY=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    static final NativeSurfaceMesher.Layout LAYOUT=new NativeSurfaceMesher.Layout(28,0,12,16,24,-1);
    static final NativeSurfaceMesherBaseline.Layout OLD_LAYOUT=new NativeSurfaceMesherBaseline.Layout(28,0,12,16,24,-1);
    static long[] measure(Runnable action) {
        long bytes=MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId()), start=System.nanoTime();
        action.run();
        return new long[]{System.nanoTime()-start,MEMORY.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytes};
    }
    static long median(long[] values) { Arrays.sort(values); return values[values.length/2]; }
    static void pair(String label,Runnable before,Runnable after) {
        for(int i=0;i<5;i++) { before.run(); after.run(); }
        long[] oldTime=new long[9],newTime=new long[9],oldBytes=new long[9],newBytes=new long[9];
        for(int i=0;i<9;i++) {
            long[] old,newer;
            if(i%2==0) { old=measure(before); newer=measure(after); }
            else { newer=measure(after); old=measure(before); }
            oldTime[i]=old[0]; newTime[i]=newer[0]; oldBytes[i]=old[1]; newBytes[i]=newer[1];
        }
        System.out.printf(Locale.ROOT,"%s: beforeNs=%d afterNs=%d beforeBytes=%d afterBytes=%d%n",
                label,median(oldTime),median(newTime),median(oldBytes),median(newBytes));
    }
    public static void main(String[] args) throws Exception {
        double[] distances=new double[65536]; int[] previous=new int[distances.length];
        Random random=new Random(8013);
        for(int i=0;i<distances.length;i++) { distances[i]=random.nextDouble()*4096; previous[i]=1<<random.nextInt(5); }
        var policy=NativeLodSelection.policy(77,true,3,4);
        for(int i=0;i<distances.length;i++) if(policy.selectSquared(distances[i]*distances[i],previous[i])
                != NativeLodSelection.select(distances[i],77,previous[i],true,3,4)) throw new AssertionError("selection parity");
        pair("selection-65536",()-> {
            long sum=0; for(int i=0;i<distances.length;i++) sum+=NativeLodSelection.select(distances[i],77,previous[i],true,3,4); sink=sum;
        },()-> {
            long sum=0; for(int i=0;i<distances.length;i++) sum+=policy.selectSquared(distances[i]*distances[i],previous[i]); sink=sum;
        });
        var fixture=NativeGeometryLodSmoke.class.getDeclaredMethod("fixture",int.class); fixture.setAccessible(true);
        for(int shape=0;shape<5;shape++) {
            ByteBuffer input=(ByteBuffer)fixture.invoke(null,shape);
            for(int cell:new int[]{2,4,8,16}) {
                var old=NativeGeometryMesherBaseline.reduce(input,OLD_LAYOUT,cell,List.of());
                var newer=NativeGeometryMesher.reduce(input,LAYOUT,cell,List.of());
                if((old==null)!=(newer==null) || old!=null && (!Arrays.equals(old.vertices(),newer.vertices())
                        || old.movedVertices()!=newer.movedVertices())) throw new AssertionError("geometry parity");
            }
            pair("geometry-shape-"+shape+"-100-builds",()-> {
                for(int i=0;i<100;i++) { var r=NativeGeometryMesherBaseline.reduce(input,OLD_LAYOUT,4,List.of()); sink=r==null?0:r.quads(); }
            },()-> {
                for(int i=0;i<100;i++) { var r=NativeGeometryMesher.reduce(input,LAYOUT,4,List.of()); sink=r==null?0:r.quads(); }
            });
        }
        System.out.println("Exact output parity: five shapes x four tiers; scalar selection parity: 65,536 sections");
    }
}
