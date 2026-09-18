package dev.metalcraft.client.chunk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.function.LongToIntFunction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.LeveledPriorityQueue;

/**
 * Integer-level adapter for Minecraft's eight-neighbor player-loading graph.
 * Uses the native leveled queue and decrease/invalidate/recompute propagation;
 * callbacks feed the existing PlayerTicketTracker and its throttled dispatcher.
 * This owns no chunks, tickets, generation jobs, simulation or render geometry.
 */
public final class WidePlayerDistanceGraph {
    private static final int ABSENT = NativeChunkDistance.NO_LEVEL;
    private static final int NO_PENDING = Integer.MAX_VALUE;
    private final Long2IntOpenHashMap levels = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap pending = new Long2IntOpenHashMap();
    private final LongOpenHashSet sources = new LongOpenHashSet();
    private final LongToIntFunction sourceLevel;
    private final LevelChange changed;
    private LeveledPriorityQueue queue = new LeveledPriorityQueue(ABSENT, 16);
    private int radius = NativeChunkDistance.VANILLA_MAX;

    @FunctionalInterface public interface LevelChange { void accept(long node, int oldLevel, int newLevel); }
    @FunctionalInterface public interface EntryConsumer { void accept(long node, int level); }

    public WidePlayerDistanceGraph(LongToIntFunction sourceLevel, LevelChange changed) {
        this.sourceLevel = sourceLevel;
        this.changed = changed;
        levels.defaultReturnValue(ABSENT);
        pending.defaultReturnValue(NO_PENDING);
    }

    public int level(long node) { return levels.get(node); }
    public int size() { return levels.size(); }
    public int queued() { return pending.size(); }
    public int radius() { return radius; }

    public void forEach(EntryConsumer consumer) {
        for (var entry : levels.long2IntEntrySet()) consumer.accept(entry.getLongKey(), entry.getIntValue());
    }

    public void updateSource(long node, int newLevel, boolean decreased) {
        if (sourceLevel.applyAsInt(node) == 0) sources.add(node); else sources.remove(node);
        checkEdge(ChunkPos.INVALID_CHUNK_POS, node, newLevel, decreased);
    }

    /** Rebuild distance bookkeeping only. Native callbacks coalesce before tickets are reconciled. */
    public void setViewDistance(int distance) {
        int next = Math.clamp(distance, NativeChunkDistance.VANILLA_MAX, NativeChunkDistance.MAX);
        if (next == radius) return;
        radius = next;
        forEach((node, level) -> changed.accept(node, level, ABSENT));
        levels.clear();
        pending.clear();
        queue = new LeveledPriorityQueue(ABSENT, 16);
        for (long source : sources) checkEdge(ChunkPos.INVALID_CHUNK_POS, source, 0, true);
    }

    public void runUpdates() {
        while (!queue.isEmpty()) {
            long node = queue.removeFirstLong();
            int old = clamp(level(node));
            int computed = pending.remove(node);
            if (computed < old) {
                setLevel(node, computed);
                neighbors(node, computed, true);
            } else if (computed > old) {
                setLevel(node, radius + 1);
                if (computed != radius + 1) {
                    queue.enqueue(node, computed);
                    pending.put(node, computed);
                }
                neighbors(node, old, false);
            }
        }
    }

    private int clamp(int value) { return Math.clamp(value, 0, radius + 1); }

    private void setLevel(long node, int value) {
        int old = value > radius ? levels.remove(node) : levels.put(node, value);
        changed.accept(node, old, value);
    }

    private void checkEdge(long from, long to, int incoming, boolean decreased) {
        int current = clamp(level(to));
        int previous = pending.get(to);
        boolean consistent = previous == NO_PENDING;
        if (consistent) previous = current;
        int computed = decreased ? Math.min(previous, clamp(incoming)) : compute(to, from, clamp(incoming));
        int oldPriority = Math.min(current, previous);
        if (computed != current) {
            int priority = Math.min(current, computed);
            if (!consistent && oldPriority != priority) queue.dequeue(to, oldPriority, priority);
            queue.enqueue(to, priority);
            pending.put(to, computed);
        } else if (!consistent) {
            queue.dequeue(to, oldPriority, ABSENT);
            pending.remove(to);
        }
    }

    private int compute(long node, long excluded, int incoming) {
        int result = incoming;
        if (excluded != ChunkPos.INVALID_CHUNK_POS) result = Math.min(result, clamp(sourceLevel.applyAsInt(node)));
        var pos = ChunkPos.unpack(node);
        int x = pos.x(), z = pos.z();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            long neighbor = ChunkPos.pack(x + dx, z + dz);
            if (neighbor != excluded) result = Math.min(result, clamp(level(neighbor) + 1));
        }
        return result;
    }

    private void neighbors(long node, int value, boolean decreased) {
        if (decreased && value >= radius) return;
        var pos = ChunkPos.unpack(node);
        int x = pos.x(), z = pos.z();
        int incoming = clamp(value + 1);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            long neighbor = ChunkPos.pack(x + dx, z + dz);
            if (decreased) checkEdge(node, neighbor, incoming, true);
            else {
                int previous = pending.get(neighbor);
                if (incoming == (previous == NO_PENDING ? clamp(level(neighbor)) : previous))
                    checkEdge(node, neighbor, radius + 1, false);
            }
        }
    }
}
