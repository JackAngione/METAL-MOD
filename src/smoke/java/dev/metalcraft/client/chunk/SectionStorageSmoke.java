package dev.metalcraft.client.chunk;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LevelHeightAccessor;

/** Native storage oracle plus sparse high-distance allocation and lifecycle checks. */
public final class SectionStorageSmoke {
    private static volatile Object sink;
    private static final RotatingSectionStorage.ValueCreator<Value> FACTORY = Value::new;
    private static final class Value implements RotatingSectionStorage.Value {
        final int index;
        long node;
        int resets;
        Value(int index,long node) { this.index=index; this.node=node; }
        public long getSectionNode() { return node; }
        public void setSectionNode(long next) { node=next; resets++; }
    }
    public static void main(String[] args) throws Exception {
        oracle(); dirtyStateOracle(); concurrentCreation(); sparseTable(); measure();
        System.out.println("Section storage: native indices, movement/teleport recycling, all bounds, cleanup, concurrent creation, sparse graph parity and allocation checks passed");
    }
    private static void dirtyStateOracle() throws Exception {
        var ctor=SectionUpdateTracker.SectionDirtyState.class.getDeclaredConstructor(boolean.class,boolean.class,long.class);
        ctor.setAccessible(true);
        var lazy=new LazySectionStorage<SectionUpdateTracker.SectionDirtyState>(128,-4,19,(i,n)->{
            try { return ctor.newInstance(true,false,n); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        var nativeTracker=new SectionUpdateTracker(LevelHeightAccessor.create(-64,384),128);
        for(int x:new int[]{0,1,300,-300,0}) {
            var center=SectionPos.of(x,9,0);lazy.repositionCenter(center);nativeTracker.repositionCamera(center);
            long node=SectionPos.asLong(x+8,9,0);
            var expected=nativeTracker.getDirtyState(node);var actual=lazy.getValue(node);
            check(actual.getSectionNode()==expected.getSectionNode() && actual.getSectionNode()==node,"native dirty-state positioning");
            check(actual.isDirty()==expected.isDirty() && actual.isDirtyFromPlayer()==expected.isDirtyFromPlayer(),"native dirty-state flags");
            actual.setNotDirty();expected.setNotDirty();actual.setDirty(true);expected.setDirty(true);
            check(actual.isDirty() && actual.isDirtyFromPlayer(),"player edits retained");
        }
    }
    private static void oracle() {
        var nativeStorage = new RotatingSectionStorage<>(64,-4,19,FACTORY);
        var lazy = new LazySectionStorage<>(64,-4,19,FACTORY);
        check(lazy.getValue(0,0,0)==null && lazy.residentEntries()==0,"unpositioned access");
        check(lazy.size()==nativeStorage.size() && lazy.radius()==64 && lazy.minY()==-4 && lazy.maxY()==19 && lazy.height()==24,"native shape");
        var random=new Random(89324);
        for(var center: new SectionPos[]{SectionPos.of(0,0,0),SectionPos.of(1,0,-1),SectionPos.of(-50,8,23),SectionPos.of(1400,-4,-2200),SectionPos.of(1400,-4,-2200)}) {
            var retained=new ArrayList<Value>(); lazy.forEach(retained::add);
            check(nativeStorage.repositionCenter(center)==lazy.repositionCenter(center),"move result");
            check(center.equals(lazy.centerSectionPos()),"camera center");
            for(Value value:retained) {
                var expected=nativeStorage.getValue(value.node);
                check(expected!=null && expected.index==value.index && value==lazy.getValue(value.node),"recycled identity/index retained");
            }
            for(int n=0;n<5000;n++) {
                int x=center.x()+random.nextInt(137)-68,y=random.nextInt(28)-6,z=center.z()+random.nextInt(137)-68;
                var a=nativeStorage.getValue(x,y,z);var b=lazy.getValue(x,y,z);
                check((a==null)==(b==null),"bounds parity");
                if(a!=null) {
                    check(a.index==b.index && a.node==b.node,"node/index parity");
                    check(lazy.getValueAt(new BlockPos(x*16,y*16,z*16))==b,"block lookup");
                }
            }
            check(lazy.getValue(center.x()+65,0,center.z())==null && lazy.getValue(center.x(),20,center.z())==null,"outer boundary");
            check(lazy.getValue(center.x()+64,19,center.z()+64)!=null,"inclusive boundary");
        }
        int[] count={0}; for(Value value:lazy) count[0]++;
        check(count[0]==lazy.residentEntries() && lazy.spliterator().getExactSizeIfKnown()==count[0],"cleanup covers every resident");
        int before=lazy.residentEntries();lazy.forEach(v -> v.resets++);
        check(lazy.residentEntries()==before,"cleanup does not materialize empty slots");
        // A resident recycled across a full wrap must reset exactly once, even on return.
        var one=new LazySectionStorage<>(128,-4,19,FACTORY);one.repositionCenter(SectionPos.of(0,0,0));
        var value=one.getValue(0,0,0);one.repositionCenter(SectionPos.of(257,0,0));
        check(value.resets==1 && value.node==SectionPos.asLong(257,0,0) && one.getValue(257,0,0)==value,"teleport reset");
        one.repositionCenter(SectionPos.of(0,0,0));check(value.resets==2,"return reset");
        // Some native factories (SectionDirtyState) ignore the constructor node;
        // eager storage relies on its first reposition to initialize the value.
        var deferred=new LazySectionStorage<>(128,-4,19,(i,n)->new Value(i,0));
        deferred.repositionCenter(SectionPos.of(0,0,0));
        var initialized=deferred.getValue(8,9,0);
        check(initialized.node==SectionPos.asLong(8,9,0) && initialized.resets==1,"factory with deferred positioning");
    }
    private static void concurrentCreation() throws Exception {
        var created=new AtomicInteger();
        var lazy=new LazySectionStorage<>(128,-4,19,(i,n)->{created.incrementAndGet();return new Value(i,n);});
        lazy.repositionCenter(SectionPos.of(0,0,0));
        try(var pool=Executors.newFixedThreadPool(4)) {
            var tasks=new ArrayList<java.util.concurrent.Callable<Value>>();
            for(int i=0;i<100;i++) tasks.add(()->lazy.getValue(-7,3,12));
            var futures=pool.invokeAll(tasks);Value first=futures.getFirst().get();
            for(var f:futures) check(f.get()==first,"concurrent identity");
        }
        check(created.get()==1 && lazy.residentEntries()==1,"single creation");
    }
    private static void sparseTable() {
        int size=257*257*24;
        var nativeSlots=new Object[size];var table=new PagedSectionTable<Object>(size);var random=new Random(523);
        check(table.allocatedPages()==0,"empty graph allocates no pages");
        for(int i=0;i<50000;i++) {
            int index=random.nextInt(size);Object value=i%5==0?null:new Object();
            table.put(index,value);nativeSlots[index]=value;
            check(table.get(index)==nativeSlots[index],"graph put/get");
        }
        int count=0;for(int i=0;i<size;i++) { check(table.get(i)==nativeSlots[i],"graph array parity");if(nativeSlots[i]!=null)count++; }
        check(count==table.entries() && table.get(-1)==null && table.get(size)==null,"graph bounds/count");
        var small=new PagedSectionTable<Object>(257);Object last=new Object();small.put(256,last);check(small.get(256)==last,"short final page");
    }
    private static void measure() {
        ThreadMXBean bean=(ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        // Warm class initialization outside the samples.
        sink=new SectionUpdateTracker(LevelHeightAccessor.create(-64,384),2);
        sink=new LazySectionStorage<>(2,-4,19,FACTORY);
        long tid=Thread.currentThread().threadId(),start=bean.getThreadAllocatedBytes(tid);
        sink=new SectionUpdateTracker(LevelHeightAccessor.create(-64,384),128);
        long eager=bean.getThreadAllocatedBytes(tid)-start;
        start=bean.getThreadAllocatedBytes(tid);
        var sparse=new LazySectionStorage<>(128,-4,19,FACTORY);sink=sparse;
        long lazy=bean.getThreadAllocatedBytes(tid)-start;
        check(lazy<eager/100,"allocation reduction");
        sparse.repositionCenter(SectionPos.of(0,0,0));
        for(int z=-16;z<=16;z++)for(int x=-16;x<=16;x++)for(int y=0;y<4;y++)sparse.getValue(x,y,z);
        long visits=sparse.repositionVisits();sparse.repositionCenter(SectionPos.of(1,0,0));
        check(sparse.repositionVisits()-visits==sparse.residentEntries(),"move visits only resident entries");
        System.out.printf("128-distance bookkeeping: logical slots=%d native dirty-tracker construction=%d allocated bytes lazy construction=%d bytes resident=%d move visits=%d pages=%d%n",
            sparse.size(),eager,lazy,sparse.residentEntries(),sparse.repositionVisits()-visits,sparse.allocatedPages());
        var eagerStorage=new RotatingSectionStorage<>(128,-4,19,FACTORY);
        eagerStorage.repositionCenter(SectionPos.of(0,0,0));
        var centers=new SectionPos[]{SectionPos.of(0,0,0),SectionPos.of(1,0,0)};
        var nativeTimes=new ArrayList<Long>();var lazyTimes=new ArrayList<Long>();
        for(int i=0;i<25;i++) {
            var center=centers[i%2];
            long t=System.nanoTime();eagerStorage.repositionCenter(center);long nativeNs=System.nanoTime()-t;
            t=System.nanoTime();sparse.repositionCenter(center);long lazyNs=System.nanoTime()-t;
            if(i>=10) { nativeTimes.add(nativeNs);lazyTimes.add(lazyNs); }
        }
        nativeTimes.sort(Long::compare);lazyTimes.sort(Long::compare);
        System.out.printf("128-distance camera reposition (4356 resident slots; 15 samples): native median=%.3f ms lazy median=%.3f ms%n",
            nativeTimes.get(7)/1e6,lazyTimes.get(7)/1e6);
        start=bean.getThreadAllocatedBytes(tid);sink=new Object[sparse.size()];long denseGraph=bean.getThreadAllocatedBytes(tid)-start;
        start=bean.getThreadAllocatedBytes(tid);sink=new PagedSectionTable<>(sparse.size());long pagedGraph=bean.getThreadAllocatedBytes(tid)-start;
        System.out.printf("128-distance visibility table: dense=%d allocated bytes empty paged=%d bytes%n",denseGraph,pagedGraph);
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
