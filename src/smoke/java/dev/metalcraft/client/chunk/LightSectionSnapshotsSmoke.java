package dev.metalcraft.client.chunk;

import com.sun.management.ThreadMXBean;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.DataLayer;

public final class LightSectionSnapshotsSmoke {
    private static volatile Object sink;
    private record Branch(LightSectionSnapshots actual, Long2ObjectOpenHashMap<DataLayer> expected) { }
    public static void main(String[] args) throws Exception {
        var random = new Random(81917);
        var branches = new ArrayList<Branch>();
        branches.add(new Branch(new LightSectionSnapshots(), new Long2ObjectOpenHashMap<>()));
        long[] keys = {0, Long.MIN_VALUE, Long.MAX_VALUE, -1, SectionPos.asLong(-128,-4,128)};
        for (int i = 0; i < 30_000; i++) {
            var branch = branches.get(random.nextInt(branches.size()));
            long key = i % 7 == 0 ? keys[random.nextInt(keys.length)]
                    : SectionPos.asLong(random.nextInt(33)-16, random.nextInt(24)-4, random.nextInt(33)-16);
            switch (random.nextInt(4)) {
                case 0, 1 -> {
                    var value = new DataLayer(random.nextInt(16));
                    check(branch.actual.put(key,value) == branch.expected.put(key,value), "put return");
                }
                case 2 -> check(branch.actual.remove(key) == branch.expected.remove(key), "remove return");
                case 3 -> check(branch.actual.get(key) == branch.expected.get(key), "lookup");
            }
            check(branch.actual.size() == branch.expected.size(), "size");
            if (i % 173 == 0) {
                if (branches.size() == 16) branches.remove(0);
                branches.add(new Branch(branch.actual.copy(), branch.expected.clone()));
                for (var b : branches) b.expected.forEach((k,v) -> check(b.actual.get(k) == v, "fork isolation"));
            }
        }
        for (var b : branches) {
            b.expected.keySet().forEach((long key) -> b.actual.remove(key));
            check(b.actual.size() == 0 && b.actual.allocatedPages() == 0, "unload releases all pages");
        }
        var writer = new LightSectionSnapshots();
        writer.put(0,new DataLayer(3));
        var snapshot = writer.copy();
        DataLayer edited = writer.get(0).copy();
        writer.put(0,edited); edited.set(1,2,3,14);
        check(snapshot.get(0).get(1,2,3) == 3 && writer.get(0).get(1,2,3) == 14, "native payload COW");
        publication();
        allocation();
        System.out.println("Lighting snapshots passed: branch mutation, negative/extreme keys, removal, payload isolation and concurrent publication");
    }

    private static void publication() throws Exception {
        var writer = new LightSectionSnapshots();
        var published = new AtomicReference<LightSectionSnapshots>();
        var failure = new AtomicReference<Throwable>();
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread reader = Thread.ofPlatform().start(() -> {
            try {
                while (!done.get()) {
                    var snapshot = published.get();
                    if (snapshot == null) continue;
                    int value = snapshot.get(0).get(0,0,0);
                    for (int i=0;i<256;i++) check(snapshot.get(i).get(0,0,0)==value,"published map is immutable during next update");
                }
            } catch (Throwable error) { failure.set(error); }
        });
        try {
            for (int round=0;round<1000;round++) {
                var value = new DataLayer(round & 15);
                for (int i=0;i<256;i++) writer.put(i,value);
                published.set(writer.copy());
            }
        } finally { done.set(true); reader.join(); }
        if (failure.get()!=null) throw new AssertionError(failure.get());
    }

    private static void allocation() {
        var flat = new Long2ObjectOpenHashMap<DataLayer>();
        var paged = new LightSectionSnapshots();
        var value = new DataLayer(15);
        for (int i=0;i<262144;i++) { flat.put(i,value); paged.put(i,value); }
        ThreadMXBean bean = (ThreadMXBean)ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().threadId();
        for (int i=0;i<5;i++) { sink=flat.clone(); sink=paged.copy(); }
        long[] flatBytes=new long[9],pagedBytes=new long[9],flatNs=new long[9],pagedNs=new long[9];
        for (int i=0;i<9;i++) {
            long before=bean.getThreadAllocatedBytes(id),start=System.nanoTime();
            var f=flat.clone();
            for (int k=0;k<64;k++) f.put(k,new DataLayer(k&15));
            sink=f; flatNs[i]=System.nanoTime()-start; flatBytes[i]=bean.getThreadAllocatedBytes(id)-before;
            before=bean.getThreadAllocatedBytes(id);start=System.nanoTime();
            var p=paged.copy();
            for (int k=0;k<64;k++) p.put(k,new DataLayer(k&15));
            sink=p;pagedNs[i]=System.nanoTime()-start;pagedBytes[i]=bean.getThreadAllocatedBytes(id)-before;
        }
        java.util.Arrays.sort(flatBytes);java.util.Arrays.sort(pagedBytes);java.util.Arrays.sort(flatNs);java.util.Arrays.sort(pagedNs);
        check(pagedBytes[4] < flatBytes[4]/10,"snapshot allocation must shrink substantially");
        long before=bean.getThreadAllocatedBytes(id);sink=paged.copy();long empty=bean.getThreadAllocatedBytes(id)-before;
        check(empty<4096,"copy allocation independent of loaded sections");
        System.out.printf("262144 sections, snapshot + 64 edits: flat %d bytes / %.3f ms, paged %d bytes / %.3f ms; unchanged snapshot %d bytes%n",
                flatBytes[4],flatNs[4]/1e6,pagedBytes[4],pagedNs[4]/1e6,empty);
    }
    private static void check(boolean test,String message) { if (!test) throw new AssertionError(message); }
}
