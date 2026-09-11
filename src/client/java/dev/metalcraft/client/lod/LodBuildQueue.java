package dev.metalcraft.client.lod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

/** Bounded admission and stale-result filtering. The owner never waits for worker completion. */
public final class LodBuildQueue {
    private record Region(long session, String dimension, int x, int y, int z) {
        static Region of(TerrainSnapshot.Key k) { return new Region(k.session(), k.dimension(), k.x(), k.y(), k.z()); }
    }
    private record WorkId(TerrainSnapshot.Key key, int tier) { }
    private record Result(WorkId id, long reservedBytes, TerrainHierarchy.Mesh mesh, RuntimeException failure) { }

    private final Executor executor;
    private final BiFunction<TerrainSnapshot, Integer, TerrainHierarchy.Mesh> builder;
    private final int maxJobs;
    private final long maxBytes;
    private final Map<Region, TerrainSnapshot.Key> revisions = new HashMap<>();
    private final Set<WorkId> pending = new HashSet<>();
    private final ConcurrentLinkedQueue<Result> completed = new ConcurrentLinkedQueue<>();
    private long reservedBytes;
    private long failures;

    public LodBuildQueue(Executor executor, int maxJobs, long maxBytes) {
        this(executor, maxJobs, maxBytes, TerrainHierarchy::build);
    }

    LodBuildQueue(Executor executor, int maxJobs, long maxBytes,
            BiFunction<TerrainSnapshot, Integer, TerrainHierarchy.Mesh> builder) {
        if (maxJobs < 1 || maxBytes < 1) throw new IllegalArgumentException("Positive budgets required");
        this.executor = java.util.Objects.requireNonNull(executor);
        this.maxJobs = maxJobs;
        this.maxBytes = maxBytes;
        this.builder = java.util.Objects.requireNonNull(builder);
    }

    /** Call when current terrain changes, including all affected neighboring halo revisions. */
    public void current(TerrainSnapshot.Key key) { revisions.put(Region.of(key), key); }

    /** Release world/region revision entries on unload; old workers remain charged until drained. */
    public void unload(TerrainSnapshot.Key key) { revisions.remove(Region.of(key)); }
    public void reset() { revisions.clear(); }

    public boolean submit(TerrainSnapshot snapshot, int tier) {
        if (tier < 1 || tier > 4) throw new IllegalArgumentException("LOD tier must be 1–4");
        WorkId id = new WorkId(snapshot.key(), tier);
        // Worst case: six faces per voxel, hierarchy nodes, plus snapshot. Charge the maximum
        // while building and while waiting for upload, rather than only charging finished meshes.
        long bytes = snapshot.retainedBytes() + 4096L * 6 * 128 + 4681L * 128;
        if (!snapshot.key().equals(revisions.get(Region.of(snapshot.key()))) || pending.contains(id)
                || pending.size() >= maxJobs || bytes > maxBytes - reservedBytes) return false;
        pending.add(id);
        reservedBytes += bytes;
        try {
            executor.execute(() -> {
                TerrainHierarchy.Mesh mesh = null;
                RuntimeException failure = null;
                try { mesh = builder.apply(snapshot, tier); }
                catch (RuntimeException error) { failure = error; }
                completed.add(new Result(id, bytes, mesh, failure));
            });
        } catch (RuntimeException rejection) {
            pending.remove(id);
            reservedBytes -= bytes;
            return false;
        }
        return true;
    }

    /** Transfers only current results fitting the upload allowance; oversized results are discarded. */
    public List<TerrainHierarchy.Mesh> drain(long uploadBytes) {
        if (uploadBytes < 0) throw new IllegalArgumentException("Negative upload allowance");
        List<TerrainHierarchy.Mesh> ready = new ArrayList<>();
        int count = completed.size();
        for (int i = 0; i < count; i++) {
            Result result = completed.poll();
            if (result == null) break;
            boolean current = result.id.key().equals(revisions.get(Region.of(result.id.key())));
            if (result.failure != null) failures++;
            TerrainHierarchy.Mesh mesh = result.mesh;
            if (current && result.failure == null && mesh != null && mesh.supported()
                    && mesh.estimatedBytes() <= uploadBytes) {
                ready.add(mesh);
                uploadBytes -= mesh.estimatedBytes();
            }
            // Rejected or stale output leaves ordinary rendering as owner. Caller may resubmit
            // next frame at a coarser tier; no result can clog the queue forever.
            pending.remove(result.id);
            reservedBytes -= result.reservedBytes;
        }
        return List.copyOf(ready);
    }

    public int pendingJobs() { return pending.size(); }
    public long reservedBytes() { return reservedBytes; }
    public long failures() { return failures; }
}
