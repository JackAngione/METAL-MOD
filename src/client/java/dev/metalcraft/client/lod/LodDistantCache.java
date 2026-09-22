package dev.metalcraft.client.lod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.packs.resources.ResourceManager;

/** Bounded single-worker scheduling. Immutable compiler output is the only terrain input. */
public final class LodDistantCache implements AutoCloseable {
    private static final java.util.concurrent.ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r,"MetalCraft distant terrain"); t.setDaemon(true); return t;
    });
    public static final long QUEUE_BYTES = 16L << 20, RESULT_BYTES = 64L << 20;
    public static final int MAX_NODES = 128, MAX_PENDING = 4096, MAX_VERSIONS = 65536;
    public record View(double x, double y, double z, int loaded, int horizon, long diskBudget,
                       float yaw, float pitch, float projectionX, float projectionY,
                       java.util.function.Predicate<LodDistantNode.Key> visible) { }
    @FunctionalInterface interface StoreFactory { LodDistantStore open(long budget) throws IOException; }
    public record Candidate(LodDistantNode node, long version) { }
    public record Result(List<Candidate> nodes, View view, long generation) { }
    private record Update(byte[] packed, LodRevisionTracker.Ticket ticket, long version) { }
    public record Stats(boolean ready, long captured, long saved, long cacheReads, long dropped, long failures,
                        long queuedBytes, int queuedNodes, long diskBytes, long corrupt, long evictions, int indexedNodes) { }
    private final StoreFactory factory;
    private final java.util.concurrent.Executor worker;
    private final int maxVersions;
    private final LinkedHashMap<LodDistantNode.Key,Update> pending = new LinkedHashMap<>();
    private final Set<LodDistantNode.Key> openingDirty = new HashSet<>();
    private final ConcurrentHashMap<LodDistantNode.Key,Long> versions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LodDistantNode.Key,Long> persistedVersions = new ConcurrentHashMap<>();
    private final Set<LodDistantNode.Key> knownLeaves = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Result> result = new AtomicReference<>();
    private volatile Stats stats = new Stats(false,0,0,0,0,0,0,0,0,0,0,0);
    private volatile boolean closed;
    private volatile boolean initialized;
    private volatile Set<LodDistantNode.Key> diskLeaves = Set.of();
    private boolean scheduled, clear, clearAll, failed;
    private long serial, queuedBytes, captured, saved, reads, dropped, failures;
    private long peakQueuedBytes;
    private LodDistantStore.Diagnostics storeDiagnostics = new LodDistantStore.Diagnostics(0,0,0,0);
    public record Diagnostics(long peakQueuedBytes, LodDistantStore.Diagnostics store) { }
    public synchronized Diagnostics diagnostics() { return new Diagnostics(peakQueuedBytes, storeDiagnostics); }
    private volatile long recaptures;
    private volatile long generation;
    private volatile View view;
    private LodDistantStore store;

    public LodDistantCache(Path root, String world, String dimension, String atlasLayout, ResourceManager resources) {
        this(budget -> {
            String identity = world;
            if (world.startsWith("local:")) {
                Path save = Path.of(world.substring(6)).toRealPath();
                var attributes = java.nio.file.Files.readAttributes(save, java.nio.file.attribute.BasicFileAttributes.class);
                identity = "local:" + save + ":" + attributes.creationTime() + ":" + attributes.fileKey();
            }
            return new LodDistantStore(root, identity, dimension, fingerprint(resources, atlasLayout), budget);
        }, WORKER);
    }
    LodDistantCache(StoreFactory factory, java.util.concurrent.Executor worker) {
        this(factory,worker,MAX_VERSIONS);
    }
    LodDistantCache(StoreFactory factory, java.util.concurrent.Executor worker,int maxVersions) {
        if(maxVersions<128 || maxVersions>MAX_VERSIONS) throw new IllegalArgumentException("Invalid revision budget");
        this.factory = factory;
        this.worker = worker;
        this.maxVersions=maxVersions;
    }
    public Stats stats() { return stats; }
    public long generation() { return generation; }
    public long version(LodDistantNode.Key key) { return versions.getOrDefault(key,0L); }
    public Result takeResult() { return result.getAndSet(null); }
    public record EntryState(long receivedRevision,long persistedRevision,boolean stored) { }
    public EntryState entry(LodDistantNode.Key key) {
        return new EntryState(version(key),persistedVersions.getOrDefault(key,-1L),diskLeaves.contains(key));
    }
    boolean columnStored(int x,int z,int minSection,int maxSection) {
        for(int y=minSection;y<maxSection;y++) if(!storedCurrent(new LodDistantNode.Key(0,x,y,z))) return false;
        return true;
    }
    boolean storedCurrent(LodDistantNode.Key key) {
        long revision=version(key);
        return diskLeaves.contains(key) && (revision==0 || persistedVersions.getOrDefault(key,-1L)==revision);
    }
    java.util.Iterator<LodDistantNode.Key> knownIterator() { return knownLeaves.iterator(); }
    boolean needsRecapture(LodDistantNode.Key key) {
        long revision=version(key);
        return knownLeaves.contains(key) && revision>0 && persistedVersions.getOrDefault(key,-1L)!=revision;
    }
    void recaptureRequested() { recaptures++; }
    public long recaptures() { return recaptures; }
    public synchronized void reject(LodDistantNode.Key key) {
        knownLeaves.remove(key);
        enqueue(key,null,null);
    }

    public static java.util.concurrent.CompletableFuture<Void> clearRoot(Path root,long budget) {
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            try(var store=new LodDistantStore(root,"maintenance","maintenance","maintenance",budget)) { store.clear(); }
            catch(IOException error) {
                com.mojang.logging.LogUtils.getLogger().warn("Distant cache clear failed: {}",error.toString());
                throw new java.io.UncheckedIOException(error);
            }
        },WORKER);
    }

    public boolean needsView(double x, double y, double z, int loaded, int horizon, long budget,
                             float yaw, float pitch, float projectionX, float projectionY) {
        View previous = view;
        return previous == null || previous.loaded != loaded || previous.horizon != horizon || previous.diskBudget != budget
                // Surface ownership changes at chunk boundaries, even when camera
                // movement is below the ordinary selection refresh threshold.
                || Math.floor(x/16) != Math.floor(previous.x/16) || Math.floor(z/16) != Math.floor(previous.z/16)
                || Math.abs(x-previous.x)>8 || Math.abs(y-previous.y)>8 || Math.abs(z-previous.z)>8
                || Math.abs(yaw-previous.yaw)>3 || Math.abs(pitch-previous.pitch)>3
                || projectionX != previous.projectionX || projectionY != previous.projectionY;
    }
    public synchronized void view(View next) {
        if (closed || failed) return;
        view = next; schedule();
    }
    public synchronized void invalidate(LodDistantNode.Key key) {
        // Chunk arrival dirties thousands of never-cached sections. They cannot stale
        // any stored surface, and must not flush unrelated explored terrain on teleport.
        if (!initialized && !versions.containsKey(key)) {
            // Until inventory finishes, remember received dirty coordinates without
            // spending the much smaller mesh queue on mostly unknown terrain.
            if (openingDirty.size()<MAX_VERSIONS) openingDirty.add(key);
            else { clear=true; openingDirty.clear(); generation++; dropped++; }
            return;
        }
        if (initialized && !versions.containsKey(key) && !diskLeaves.contains(key)) return;
        enqueue(key,null,null);
    }
    public void capture(LodDistantNode node,LodRevisionTracker.Ticket ticket) {
        capture(node,ticket,false,0,0);
    }
    /** Generated copies may only fill missing data; received compiler output always wins. */
    boolean captureGenerated(LodDistantNode node,LodRevisionTracker.Ticket ticket,long expectedVersion,long expectedGeneration) {
        return capture(node,ticket,true,expectedVersion,expectedGeneration);
    }
    private boolean capture(LodDistantNode node,LodRevisionTracker.Ticket ticket,boolean generated,long expectedVersion,long expectedGeneration) {
        long admittedGeneration=generation;
        if (closed || ticket==null || !ticket.current()) return false;
        // Compression runs on the submitting compiler worker, outside the cache monitor.
        // Queue pressure is charged to actual compressed storage; no raw mesh remains retained.
        byte[] packed;
        try { packed=LodDistantStore.encode(node); }
        catch(IOException unsupported) { if(!generated) reject(node.key()); return false; }
        synchronized(this) {
            if (closed || failed || generation!=admittedGeneration || !ticket.current()) return false;
            if(generated && (generation!=expectedGeneration || version(node.key())!=expectedVersion || diskLeaves.contains(node.key())
                    || pending.containsKey(node.key()))) return false;
            captured++;
            openingDirty.remove(node.key());
            enqueue(node.key(),packed,ticket);
            knownLeaves.add(node.key());
            return true;
        }
    }
    private void enqueue(LodDistantNode.Key key, byte[] packed, LodRevisionTracker.Ticket ticket) {
        if (closed || failed) return;
        if (pending.size() >= MAX_PENDING || versions.size() > maxVersions - 10) {
            // Bounded overload recovery is deliberately lossy, never stale: revoke the entire generation.
            clear=true; generation++; pending.clear(); queuedBytes=0;
            versions.clear(); persistedVersions.clear(); knownLeaves.clear(); result.set(null); dropped++;
        }
        long revision=++serial;
        for (var parent=key;;parent=parent.parent()) {
            versions.put(parent,revision);
            if (parent.level()==LodDistantNode.MAX_LEVEL) break;
        }
        Update old=pending.remove(key);
        if (old!=null && old.packed!=null) queuedBytes-=old.packed.length;
        if (packed!=null && queuedBytes+packed.length>QUEUE_BYTES) { packed=null; ticket=null; dropped++; }
        pending.put(key,new Update(packed,ticket,revision));
        if (packed!=null) queuedBytes+=packed.length;
        peakQueuedBytes = Math.max(peakQueuedBytes, queuedBytes);
        schedule();
    }
    public synchronized void clear() {
        if (closed) return;
        clear=true; clearAll=true; failed=false; generation++; pending.clear(); queuedBytes=0;
        versions.clear(); persistedVersions.clear(); knownLeaves.clear(); openingDirty.clear(); result.set(null); schedule();
    }
    private void schedule() {
        if (!scheduled && !closed && !failed && view != null) { scheduled=true; worker.execute(this::drain); }
    }
    private static String fingerprint(ResourceManager resources, String atlasLayout) throws IOException {
        try {
            var hash=java.security.MessageDigest.getInstance("SHA-256");
            hash.update(("26.2/BLOCK28/opaque-v1/"+atlasLayout).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for (String prefix:List.of("models","blockstates","textures","atlases")) {
                var entries=new ArrayList<>(resources.listResources(prefix,id -> true).entrySet());
                entries.sort(Comparator.comparing(e -> e.getKey().toString()));
                for (var entry:entries) {
                    hash.update(entry.getKey().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try(var input=entry.getValue().open()) {
                        byte[] buffer=new byte[8192]; int count;
                        while((count=input.read(buffer))!=-1) hash.update(buffer,0,count);
                    }
                }
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private void drain() {
        long startedGeneration=generation;
        View requested=view;
        try {
            if (closed || requested==null) return;
            if (store==null) {
                store=factory.open(requested.diskBudget);
                synchronized(this) {
                    diskLeaves=leaves(store.keys());
                    knownLeaves.addAll(diskLeaves);
                    initialized=true;
                    if (!clear) for (var key:openingDirty)
                        if (diskLeaves.contains(key)) enqueue(key,null,null);
                    openingDirty.clear();
                }
            }
            boolean mustClear, allNamespaces;
            synchronized(this) { mustClear=clear; allNamespaces=clearAll; clear=false; clearAll=false; }
            if (mustClear) {
                if (allNamespaces) store.clear(); else store.clearNamespace();
            }
            // Yield between small batches so camera changes and shutdown cannot wait behind an unbounded backlog.
            var writes = new ArrayList<Map.Entry<LodDistantNode.Key,Update>>();
            var removed = new HashSet<LodDistantNode.Key>();
            for (int count=0;count<16 && !closed;count++) {
                Map.Entry<LodDistantNode.Key,Update> entry;
                synchronized(this) {
                    entry=pending.pollFirstEntry();
                    if(entry==null) break;
                    if(entry.getValue().packed!=null) queuedBytes-=entry.getValue().packed.length;
                }
                Update update=entry.getValue();
                if(version(entry.getKey())!=update.version) continue;
                if(update.packed!=null && update.ticket.current()) writes.add(entry);
                else removed.add(entry.getKey());
            }
            if (!writes.isEmpty() || !removed.isEmpty()) {
                var leaves=new ArrayList<LodDistantNode>();
                for(var entry:writes) leaves.add(LodDistantStore.decode(entry.getKey(),entry.getValue().packed));
                store.updateLeaves(leaves,removed);
                var stale=new HashSet<LodDistantNode.Key>();
                for (var entry : writes) {
                    var update=entry.getValue(); saved++;
                    if (!update.ticket.current() || version(entry.getKey())!=update.version) stale.add(entry.getKey());
                    else persistedVersions.put(entry.getKey(),update.version);
                }
                if (!stale.isEmpty()) store.updateLeaves(List.of(),stale);
            }
            if(closed) return;
            store.setBudget(requested.diskBudget);
            // Pending invalidations have already revoked GPU versions. Never label old disk
            // bytes with the new version while their invalidation is still queued.
            Set<LodDistantNode.Key> blocked = new HashSet<>();
            synchronized (this) {
                for (var leaf : pending.keySet()) for (var k=leaf;;k=k.parent()) {
                    blocked.add(k);
                    if (k.level()==LodDistantNode.MAX_LEVEL) break;
                }
            }
            List<LodDistantNode.Key> keys=new ArrayList<>(store.keys());
            keys.removeIf(k -> !k.outsideLoaded(requested.x,requested.z,requested.loaded)
                    || k.distanceSquared(requested.x,requested.y,requested.z) >= Math.pow(requested.horizon*16.0,2)
                    || blocked.contains(k) || !requested.visible.test(k));
            keys.sort(Comparator.comparingDouble((LodDistantNode.Key k) -> k.distanceSquared(requested.x,requested.y,requested.z))
                    .thenComparing(Comparator.comparingInt(LodDistantNode.Key::level).reversed())
                    .thenComparingInt(LodDistantNode.Key::x).thenComparingInt(LodDistantNode.Key::y).thenComparingInt(LodDistantNode.Key::z));
            List<Candidate> selected=new ArrayList<>(); long bytes=0;
            for(var key:keys) {
                if(closed || selected.size()>=MAX_NODES) break;
                if(selected.stream().anyMatch(c -> c.node.key().contains(key) || key.contains(c.node.key()))) continue;
                long version=version(key);
                var node=store.read(key); reads++;
                if(node==null || !node.drawable() || bytes+node.bytes()>RESULT_BYTES || version!=version(key)
                        || node.distanceSquared(requested.x,requested.y,requested.z)>=Math.pow(requested.horizon*16.0,2)) continue;
                selected.add(new Candidate(node,version)); bytes+=node.bytes();
            }
            if(!closed && generation==startedGeneration) result.set(new Result(List.copyOf(selected),requested,startedGeneration));
        } catch (IOException | RuntimeException error) {
            synchronized(this) { failures++; failed=true; generation++; pending.clear(); queuedBytes=0; }
            result.set(null);
            com.mojang.logging.LogUtils.getLogger().warn("Distant terrain cache unavailable: {}",error.toString());
        } finally {
            synchronized(this) {
                stats=new Stats(store!=null,captured,saved,reads,dropped,failures,queuedBytes,pending.size(),
                        store==null?0:store.diskBytes(),store==null?0:store.corruptEntries(),store==null?0:store.evictions(),store==null?0:store.keys().size());
                if (store!=null) {
                    storeDiagnostics = store.diagnostics();
                    diskLeaves=leaves(store.keys());
                    persistedVersions.keySet().retainAll(diskLeaves);
                }
                // Large generated horizons outlive the bounded revision table. At
                // a drained boundary start a new GPU epoch without deleting disk data.
                if(!closed && !failed && !clear && pending.isEmpty() && versions.size()>maxVersions-Math.min(1024,maxVersions/4)) {
                    generation++; versions.clear(); persistedVersions.clear(); result.set(null);
                    knownLeaves.retainAll(diskLeaves); view=null;
                }
                if (closed || failed) closeStore();
                scheduled=false;
                if(!closed && !failed && (!pending.isEmpty() || clear || requested!=view)) schedule();
            }
        }
    }
    private static Set<LodDistantNode.Key> leaves(Set<LodDistantNode.Key> keys) {
        return keys.stream().filter(k->k.level()==0).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private void closeStore() {
        if (store == null) return;
        try { store.close(); }
        catch (IOException error) { failures++; }
        store = null;
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed=true; generation++; pending.clear(); openingDirty.clear(); queuedBytes=0; result.set(null);
        // Filesystem ownership always leaves on the worker, even when the cache is idle.
        if (!scheduled) worker.execute(this::closeStore);
    }
}
