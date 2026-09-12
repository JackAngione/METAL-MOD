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
    public static final long QUEUE_BYTES = 16L << 20, RESULT_BYTES = 16L << 20;
    public static final int MAX_NODES = 128, MAX_PENDING = 4096, MAX_VERSIONS = 65536;
    public record View(double x, double y, double z, int loaded, int horizon, long diskBudget) { }
    public record Candidate(LodDistantNode node, long version) { }
    public record Result(List<Candidate> nodes, View view, long generation) { }
    private record Update(LodDistantNode node, LodRevisionTracker.Ticket ticket, long version) { }
    public record Stats(boolean ready, long captured, long saved, long cacheReads, long dropped, long failures,
                        long queuedBytes, int queuedNodes, long diskBytes, long corrupt, long evictions, int indexedNodes) { }
    private final Path root;
    private final String world, dimension, atlasLayout;
    private final ResourceManager resources;
    private final LinkedHashMap<LodDistantNode.Key,Update> pending = new LinkedHashMap<>();
    private final ConcurrentHashMap<LodDistantNode.Key,Long> versions = new ConcurrentHashMap<>();
    private final AtomicReference<Result> result = new AtomicReference<>();
    private volatile Stats stats = new Stats(false,0,0,0,0,0,0,0,0,0,0,0);
    private volatile boolean closed;
    private boolean scheduled, clear, failed;
    private long serial, queuedBytes, captured, saved, reads, dropped, failures;
    private volatile long generation;
    private volatile View view;
    private LodDistantStore store;

    public LodDistantCache(Path root, String world, String dimension, String atlasLayout, ResourceManager resources) {
        this.root=root; this.world=world; this.dimension=dimension; this.atlasLayout=atlasLayout; this.resources=resources;
    }
    public Stats stats() { return stats; }
    public long generation() { return generation; }
    public long version(LodDistantNode.Key key) { return versions.getOrDefault(key,0L); }
    public Result takeResult() { return result.getAndSet(null); }

    public synchronized void view(View next) {
        if (closed || failed) return;
        if (view == null || next.loaded != view.loaded || next.horizon != view.horizon || next.diskBudget != view.diskBudget
                || Math.abs(next.x-view.x)>16 || Math.abs(next.y-view.y)>16 || Math.abs(next.z-view.z)>16) {
            view=next; schedule();
        }
    }
    public synchronized void invalidate(LodDistantNode.Key key) { enqueue(key,null,null); }
    public synchronized void capture(LodDistantNode node, LodRevisionTracker.Ticket ticket) {
        if (closed || failed || ticket == null || !ticket.current()) return;
        captured++;
        enqueue(node.key(),node,ticket);
    }
    private void enqueue(LodDistantNode.Key key, LodDistantNode node, LodRevisionTracker.Ticket ticket) {
        if (closed || failed) return;
        if (pending.size() >= MAX_PENDING || versions.size() > MAX_VERSIONS - 10) {
            // Bounded overload recovery is deliberately lossy, never stale: revoke the entire generation.
            clear=true; generation++; pending.clear(); queuedBytes=0;
            versions.clear(); result.set(null); dropped++;
        }
        long revision=++serial;
        for (var parent=key;;parent=parent.parent()) {
            versions.put(parent,revision);
            if (parent.level()==LodDistantNode.MAX_LEVEL) break;
        }
        Update old=pending.remove(key);
        if (old!=null && old.node!=null) queuedBytes-=old.node.bytes();
        if (node!=null && queuedBytes+node.bytes()>QUEUE_BYTES) { node=null; ticket=null; dropped++; }
        pending.put(key,new Update(node,ticket,revision));
        if (node!=null) queuedBytes+=node.bytes();
        schedule();
    }
    public synchronized void clear() {
        clear=true; failed=false; generation++; pending.clear(); queuedBytes=0; versions.clear(); result.set(null); schedule();
    }
    private void schedule() {
        if (!scheduled && !closed && !failed) { scheduled=true; WORKER.execute(this::drain); }
    }
    private String fingerprint() throws IOException {
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
                String identity=world;
                if (world.startsWith("local:")) {
                    Path save=Path.of(world.substring(6)).toRealPath();
                    var attributes=java.nio.file.Files.readAttributes(save,java.nio.file.attribute.BasicFileAttributes.class);
                    identity="local:"+save+":"+attributes.creationTime()+":"+attributes.fileKey();
                }
                store=new LodDistantStore(root,identity,dimension,fingerprint(),requested.diskBudget);
            }
            boolean mustClear;
            synchronized(this) { mustClear=clear; clear=false; }
            if (mustClear) store.clear();
            // Yield between small batches so camera changes and shutdown cannot wait behind an unbounded backlog.
            for (int count=0;count<16 && !closed;count++) {
                Map.Entry<LodDistantNode.Key,Update> entry;
                synchronized(this) {
                    entry=pending.pollFirstEntry();
                    if(entry==null) break;
                    if(entry.getValue().node!=null) queuedBytes-=entry.getValue().node.bytes();
                }
                Update update=entry.getValue();
                if(version(entry.getKey())!=update.version) continue;
                store.invalidate(entry.getKey());
                if(update.node!=null && update.ticket.current()) {
                    store.putLeaf(update.node); saved++;
                    if (!update.ticket.current() || version(entry.getKey())!=update.version) store.invalidate(entry.getKey());
                }
            }
            if(closed) return;
            store.setBudget(requested.diskBudget);
            List<LodDistantNode.Key> keys=new ArrayList<>(store.keys());
            keys.removeIf(k -> !k.outside(requested.x,requested.z,(requested.loaded+2)*16.0)
                    || k.distanceSquared(requested.x,requested.y,requested.z) >= Math.pow(requested.horizon*16.0,2));
            keys.sort(Comparator.comparingInt(LodDistantNode.Key::level).reversed()
                    .thenComparingDouble(k -> k.distanceSquared(requested.x,requested.y,requested.z))
                    .thenComparingInt(LodDistantNode.Key::x).thenComparingInt(LodDistantNode.Key::y).thenComparingInt(LodDistantNode.Key::z));
            List<Candidate> selected=new ArrayList<>(); long bytes=0;
            for(var key:keys) {
                if(closed || selected.size()>=MAX_NODES) break;
                if(selected.stream().anyMatch(c -> c.node.key().contains(key))) continue;
                long version=version(key);
                var node=store.read(key); reads++;
                if(node==null || !node.drawable() || bytes+node.bytes()>RESULT_BYTES || version!=version(key)) continue;
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
                scheduled=false;
                if(!closed && !failed && (!pending.isEmpty() || clear || requested!=view)) schedule();
            }
        }
    }
    @Override public synchronized void close() { closed=true; generation++; pending.clear(); queuedBytes=0; result.set(null); }
}
