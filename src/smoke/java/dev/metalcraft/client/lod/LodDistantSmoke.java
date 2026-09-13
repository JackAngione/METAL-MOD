package dev.metalcraft.client.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/** Persistent data and queued interleavings, using the production cache with a manually stepped worker. */
final class LodDistantSmoke {
    private static final long BUDGET = 16L << 20;
    static void run() {
        try {
            Path root = Files.createTempDirectory("metalcraft-distant-smoke");
            try {
                nodes();
                storage(root.resolve("store"));
                globalBudget(root.resolve("budget"));
                scheduling(root.resolve("queue"));
                chunkBoundary(root.resolve("boundary"));
                generatedOwnership(root.resolve("generated"));
                revisionRollover(root.resolve("rollover"));
                cancellation(root.resolve("cancel"));
                compressedQueue(root.resolve("compressed"));
            } finally {
                try (var files = Files.walk(root)) {
                    for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
                }
            }
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        System.out.println("Distant cache smoke passed: exact parents, persistence, corruption, global budget/lock, visible priority, stale queues, clear/close races");
    }

    private static LodDistantNode node(int x, int quads) {
        var bytes = ByteBuffer.allocate(quads * 4 * LodDistantNode.STRIDE).order(ByteOrder.LITTLE_ENDIAN);
        var random = new Random(x);
        for (int i=0; i<quads*4; i++) {
            bytes.putFloat(random.nextFloat()*16).putFloat(random.nextFloat()*16).putFloat(random.nextFloat()*16);
            bytes.putInt(random.nextInt()).putFloat(random.nextFloat()).putFloat(random.nextFloat()).putInt(random.nextInt());
        }
        return new LodDistantNode(new LodDistantNode.Key(0,x,0,0),
                List.of(new LodDistantNode.Layer(0,bytes.array())),0,1);
    }
    private static void nodes() {
        var leaf = node(-21,3);
        var original = leaf.layers().getFirst().vertices();
        var copy = leaf.layers().getFirst().vertices(); copy[0]++;
        check(Arrays.equals(original,leaf.layers().getFirst().vertices()),"immutable leaf storage");
        var parent = LodDistantNode.parent(leaf.key().parent(),List.of(leaf));
        check(parent.sections()==1 && parent.bytes()==leaf.bytes(),"partial parents do not fill unknown children");
        var a=leaf.layers().getFirst().buffer(); var b=parent.layers().getFirst().buffer();
        for (int offset=0; offset<a.limit(); offset+=28) {
            check(a.getFloat(offset)+leaf.key().originX()==b.getFloat(offset)+parent.key().originX(),"negative-coordinate world surface preserved");
            for(int byteIndex=12;byteIndex<28;byteIndex++) check(a.get(offset+byteIndex)==b.get(offset+byteIndex),"material/light/UV preserved");
        }
        int largeQuads=LodDistantNode.MAX_BYTES/112/2+1;
        var left=node(0,largeQuads); var right=node(1,largeQuads);
        var manifest=LodDistantNode.parent(left.key().parent(),List.of(left,right));
        check(!manifest.drawable() && manifest.children()==3 && manifest.sections()==2,"oversized parent refines without surface truncation");
        var empty=new LodDistantNode(new LodDistantNode.Key(0,2,0,0),List.of(),0,1);
        var emptyParent=LodDistantNode.parent(empty.key().parent(),List.of(empty));
        var occupiedParent=LodDistantNode.parent(left.key().parent(),List.of(node(0,1)));
        check(LodDistantNode.parent(emptyParent.key().parent(),List.of(emptyParent,occupiedParent)).drawable(),
                "known empty parents do not prevent batching adjacent terrain");
    }
    private static void storage(Path root) throws java.io.IOException {
        var leaf=node(-21,3); Path directory;
        try (var store=new LodDistantStore(root,"world-a","overworld","materials-a",BUDGET)) {
            store.putLeaf(leaf); directory=store.directory();
            check(store.keys().size()==9,"all eight ancestors persisted");
            boolean denied=false;
            try(var ignored=new LodDistantStore(root,"world-b","overworld","materials-a",BUDGET)) { }
            catch(java.io.IOException expected) { denied=true; }
            check(denied,"concurrent clients cannot acquire the same cache root");
        }
        try (var store=new LodDistantStore(root,"world-a","overworld","materials-a",BUDGET)) {
            check(Arrays.equals(leaf.layers().getFirst().vertices(),store.read(leaf.key()).layers().getFirst().vertices()),"restart byte round trip");
            Path path=directory.resolve("0_-21_0_0.lod");
            byte[] damaged=Files.readAllBytes(path); damaged[32]^=1; Files.write(path,damaged);
            check(store.read(leaf.key())==null && store.corruptEntries()==1 && store.keys().isEmpty(),"checksum failure revokes every ancestor");
            store.putLeaf(leaf);
            Files.write(path,new byte[]{1,2,3});
        }
        try(var store=new LodDistantStore(root,"world-a","overworld","materials-a",BUDGET)) {
            check(store.keys().isEmpty() && store.corruptEntries()==1,"startup corruption cannot leave old parent geometry");
            store.putLeaf(leaf);
        }
        for (String[] identity : List.of(new String[]{"world-b","overworld","materials-a"},
                new String[]{"world-a","nether","materials-a"},new String[]{"world-a","overworld","materials-b"})) {
            try(var store=new LodDistantStore(root,identity[0],identity[1],identity[2],BUDGET)) {
                check(store.keys().isEmpty(),"world/dimension/material namespaces isolated");
            }
        }
    }
    private static void globalBudget(Path root) throws java.io.IOException {
        long budget=8L<<20;
        for(int world=0;world<4;world++) {
            try(var store=new LodDistantStore(root,"world-"+world,"dimension","materials",budget)) {
                store.putLeaf(node(0,4000));
                check(store.diskBytes()<=budget,"global inventory respects disk budget");
                long actual;
                try(var files=Files.walk(root)) {
                    actual=files.filter(p->p.toString().endsWith(".lod")).mapToLong(p->{try{return Files.size(p);}catch(Exception e){throw new AssertionError(e);}}).sum();
                }
                check(actual==store.diskBytes() && actual<=budget,"disk budget includes inactive namespaces");
            }
        }
        try(var store=new LodDistantStore(root,"world-3","dimension","materials",budget)) {
            store.clear(); check(store.diskBytes()==0 && store.keys().isEmpty(),"clear covers every namespace");
        }
        try(var files=Files.walk(root)) { check(files.noneMatch(p->p.toString().endsWith(".lod")),"no old world entries after clear"); }
    }
    private static LodDistantCache.View view(java.util.function.Predicate<LodDistantNode.Key> visible) {
        return new LodDistantCache.View(0,8,0,16,256,BUDGET,0,0,1,1,visible);
    }
    private static void drain(ArrayDeque<Runnable> work) {
        int turns=0;
        while(!work.isEmpty()) { check(++turns<100,"bounded worker progress"); work.remove().run(); }
    }
    private static void scheduling(Path root) throws java.io.IOException {
        try(var store=new LodDistantStore(root,"world","dim","mat",BUDGET)) {
            for(int x:new int[]{-80,-40,40,41,80}) store.putLeaf(node(x,4));
        }
        var work=new ArrayDeque<Runnable>();
        var revisions=new LodRevisionTracker(10000);
        var cache=new LodDistantCache(budget->new LodDistantStore(root,"world","dim","mat",budget),work::add);
        // Chunk arrival can race the first disk inventory/resource fingerprint on reopen.
        for(int x=1000;x<5097;x++) cache.invalidate(new LodDistantNode.Key(0,x,0,0));
        var earlyDirty=new LodDistantNode.Key(0,80,0,0);
        cache.invalidate(earlyDirty);
        cache.view(view(k->k.originX()>0)); drain(work);
        var result=cache.takeResult();
        check(result!=null && !result.nodes().isEmpty() && cache.stats().dropped()==0,
                "initial chunk arrival preserves unrelated persisted nodes: "+cache.stats());
        check(!cache.entry(earlyDirty).stored() && cache.needsRecapture(earlyDirty),
                "received cached terrain is revoked and repaired after opening inventory");
        check(result.nodes().stream().allMatch(c->c.node().key().originX()>0),"offscreen nodes cannot consume result budget");
        for(var a:result.nodes()) for(var b:result.nodes()) if(a!=b)
            check(!a.node().key().contains(b.node().key()),"selected parents and children never overlap");
        // More work than one drain: the last dirty leaf still has old bytes on disk.
        for(int x=100;x<116;x++) cache.capture(node(x,1),revisions.capture(x,0,0));
        var edited=node(40,8); cache.capture(edited,revisions.capture(40,0,0));
        work.remove().run(); result=cache.takeResult();
        check(result.nodes().stream().noneMatch(c->c.node().key().contains(edited.key())),"queued edit cannot stamp stale disk bytes with a new revision");
        drain(work);
        result=cache.takeResult();
        check(result.nodes().stream().anyMatch(c->c.node().key().contains(edited.key())),"current replacement becomes selectable");
        check(!cache.needsRecapture(edited.key()),"persisted current leaf needs no hidden rebuild");
        cache.invalidate(edited.key());
        check(cache.needsRecapture(edited.key()),"received dirty leaf remains eligible for offscreen recapture");
        drain(work); result=cache.takeResult();
        var survivor=new LodDistantNode.Key(0,41,0,0);
        check(result.nodes().stream().anyMatch(c->c.node().key().level()>0 && c.node().key().contains(survivor)),
                "removing a received leaf must repair parents for unchanged siblings");
        cache.capture(node(42,5),revisions.capture(42,0,0)); revisions.dirty(42,0,0); drain(work);
        check(cache.stats().saved()==17,"stale worker capture never persists");
        // A teleport dirties thousands of newly received sections unrelated to explored terrain.
        for(int x=1000;x<5097;x++) cache.invalidate(new LodDistantNode.Key(0,x,0,0));
        drain(work);
        result=cache.takeResult();
        check(result!=null && result.nodes().stream().anyMatch(c->c.node().key().contains(survivor)),
                "teleport invalidations must retain unrelated explored terrain: " + cache.stats());
        long generation=cache.generation();
        cache.clear(); check(cache.takeResult()==null && cache.generation()>generation,"clear revokes published results immediately"); drain(work);
        check(cache.stats().diskBytes()==0,"clear serializes with pending writes");
        for(int x=0;x<5000;x++) cache.capture(node(x,1),revisions.capture(x,0,0));
        check(work.size()==1 && cache.generation()>generation+1,"overload uses bounded coalesced scheduling and generation revocation");
        cache.close(); drain(work);
        check(cache.takeResult()==null,"closed cache never publishes");
        try(var ignored=new LodDistantStore(root,"world","dim","mat",BUDGET)) { }
    }
    private static void chunkBoundary(Path root) throws java.io.IOException {
        // Follow appendOwned's needsView/view protocol with terrain leaving the
        // loaded square after a tiny step, including floor semantics below zero.
        for (int axis=0;axis<2;axis++) for (int direction:new int[]{-1,1}) {
            double before=direction>0 ? 15.99 : 0.01;
            double after=direction>0 ? 16.01 : -0.01;
            int section=direction>0 ? -16 : 16;
            var key=new LodDistantNode.Key(0,axis==0?section:0,0,axis==1?section:0);
            var leaf=new LodDistantNode(key,node(0,1).layers(),0,1);
            Path directory=root.resolve(axis+"-"+direction);
            try(var store=new LodDistantStore(directory,"world","dim","mat",BUDGET)) {
                store.putLeaf(leaf);
            }
            var work=new ArrayDeque<Runnable>();
            var cache=new LodDistantCache(budget->new LodDistantStore(directory,"world","dim","mat",budget),work::add);
            try {
                cache.view(new LodDistantCache.View(axis==0?before:8,8,axis==1?before:8,
                        16,32,BUDGET,0,0,1,1,k->true));
                drain(work);
                check(cache.takeResult().nodes().isEmpty(),"loaded terrain retains sole ownership before crossing");
                double x=axis==0?after:8,z=axis==1?after:8;
                check(key.outsideLoaded(x,z,16),"fixture section leaves loaded square");
                if(cache.needsView(x,8,z,16,32,BUDGET,0,0,1,1)) {
                    cache.view(new LodDistantCache.View(x,8,z,16,32,BUDGET,0,0,1,1,k->true));
                }
                drain(work);
                var result=cache.takeResult();
                check(result!=null && result.nodes().stream().anyMatch(c->c.node().key().contains(key)),
                        "chunk-boundary crossing must select newly distant terrain: axis="+axis+", direction="+direction);
                check(!cache.needsView(x+.001,8,z+.001,16,32,BUDGET,0,0,1,1),
                        "small movement within a chunk does not reschedule the worker");
            } finally {
                cache.close(); drain(work);
            }
        }
    }
    private static void generatedOwnership(Path root) {
        var work=new ArrayDeque<Runnable>();
        var cache=new LodDistantCache(budget->new LodDistantStore(root,"world","dim","mat",budget),work::add);
        var revisions=new LodRevisionTracker(128);
        var leaf=node(40,1);
        try {
            cache.view(view(k->true)); drain(work);
            var ticket=revisions.capture(40,0,0);
            check(cache.captureGenerated(leaf,ticket,0,cache.generation()),"generated snapshot fills an unknown section");
            drain(work);
            check(cache.entry(leaf.key()).stored(),"generated section is persisted");
            check(!cache.captureGenerated(node(40,2),ticket,cache.version(leaf.key()),cache.generation()),"generated data cannot replace a stored section");
            cache.invalidate(leaf.key()); drain(work);
            long old=cache.version(leaf.key());
            cache.capture(node(40,3),revisions.capture(41,0,0));
            check(!cache.captureGenerated(leaf,ticket,old,cache.generation()),"received pending output wins over generated snapshot");
            drain(work);
            var result=cache.takeResult();
            check(result.nodes().stream().anyMatch(c->c.node().bytes()==3*112),"received current payload retained");
            cache.invalidate(leaf.key()); drain(work); old=cache.version(leaf.key());
            revisions.resources();
            check(!cache.captureGenerated(leaf,ticket,old,cache.generation()),"cancelled generation cannot repopulate the cache");
            ticket=revisions.capture(40,0,0);
            long epoch=cache.generation(); cache.clear(); drain(work);
            check(!cache.captureGenerated(leaf,ticket,0,epoch),"pre-clear generated snapshot cannot reuse zero revision in a new epoch");
            check(cache.captureGenerated(leaf,ticket,0,cache.generation()),"new generation can fill a cleared cache"); drain(work);
        } finally { cache.close(); drain(work); }
    }
    private static void revisionRollover(Path root) {
        var work=new ArrayDeque<Runnable>();
        var cache=new LodDistantCache(budget->new LodDistantStore(root,"world","dim","mat",budget),work::add,128);
        var revisions=new LodRevisionTracker(128);
        try {
            for(int i=0;i<32;i++) {
                cache.view(view(k->true));
                int x=40+i*256;
                cache.capture(node(x,1),revisions.capture(x,0,0)); drain(work);
            }
            check(cache.generation()>0 && cache.stats().dropped()==0,"drained revision rollover never drops saved terrain");
            for(int i=0;i<32;i++) check(cache.entry(new LodDistantNode.Key(0,40+i*256,0,0)).stored(),"rollover preserves every disk leaf");
            cache.view(view(k->true)); drain(work);
            check(!cache.takeResult().nodes().isEmpty(),"new revision epoch selects preserved cache data");
        } finally { cache.close(); drain(work); }
    }
    private static void cancellation(Path root) throws java.io.IOException {
        var work=new ArrayDeque<Runnable>(); var reference=new AtomicReference<LodDistantCache>();
        var cache=new LodDistantCache(budget->{
            reference.get().clear(); // Clear arrives while initial store/fingerprint is being prepared.
            return new LodDistantStore(root,"world","dim","mat",budget);
        },work::add);
        reference.set(cache);
        cache.view(view(k->true));
        cache.capture(node(40,1),new LodRevisionTracker(4).capture(40,0,0));
        drain(work);
        check(cache.takeResult()==null && cache.stats().saved()==0,"clear during initialization rejects old work and results");
        cache.close(); drain(work);
        try(var ignored=new LodDistantStore(root,"world","dim","mat",BUDGET)) { }
    }
    private static void compressedQueue(Path root) {
        var work=new ArrayDeque<Runnable>();
        var cache=new LodDistantCache(budget->new LodDistantStore(root,"world","dim","mat",budget),work::add);
        var revisions=new LodRevisionTracker(128);
        byte[] quad=node(0,1).layers().getFirst().vertices(), repeated=new byte[5000*quad.length];
        for(int offset=0;offset<repeated.length;offset+=quad.length) System.arraycopy(quad,0,repeated,offset,quad.length);
        cache.view(view(k->true));
        for(int x=200;x<264;x++) cache.capture(new LodDistantNode(new LodDistantNode.Key(0,x,0,0),
                List.of(new LodDistantNode.Layer(0,repeated)),0,1),revisions.capture(x,0,0));
        drain(work);
        check(cache.stats().captured()==64 && cache.stats().saved()==64 && cache.stats().dropped()==0,
                "compressed queue retains a 35 MiB raw capture burst within its 16 MiB budget");
        cache.close(); drain(work);
    }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
}
