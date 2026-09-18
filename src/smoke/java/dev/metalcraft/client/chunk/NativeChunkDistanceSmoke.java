package dev.metalcraft.client.chunk;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.world.level.ChunkPos;

/** Exact multi-source distance oracle, including values that cannot fit vanilla byte storage. */
public final class NativeChunkDistanceSmoke {
    public static void main(String[] args) {
        var sources = new LongOpenHashSet();
        var graph = new WidePlayerDistanceGraph(node -> sources.contains(node) ? 0 : Integer.MAX_VALUE,
                (node, oldLevel, level) -> { });
        long a = ChunkPos.pack(-13, 21), b = ChunkPos.pack(5, -7);
        sources.add(a); graph.updateSource(a, 0, true);
        verify(graph, sources);
        for (int radius : new int[]{33, 36, 64, 127, 128, 255, 256}) {
            graph.setViewDistance(radius);
            verify(graph, sources);
            check(graph.level(ChunkPos.pack(-13 + radius, 21)) == radius, "inclusive outer loading ring " + radius);
            check(graph.level(ChunkPos.pack(-13 + radius + 1, 21)) == NativeChunkDistance.NO_LEVEL, "outside ring " + radius);
        }
        sources.add(b); graph.updateSource(b, 0, true);
        verify(graph, sources);
        // Source removal and addition coalesce before the native ticket reconciliation pass.
        sources.remove(a); graph.updateSource(a, Integer.MAX_VALUE, false);
        long moved = ChunkPos.pack(-12, 22); sources.add(moved); graph.updateSource(moved, 0, true);
        verify(graph, sources);
        graph.setViewDistance(36); verify(graph, sources);
        sources.remove(b); graph.updateSource(b, Integer.MAX_VALUE, false); verify(graph, sources);
        sources.remove(moved); graph.updateSource(moved, Integer.MAX_VALUE, false); verify(graph, sources);
        check(graph.size() == 0 && graph.queued() == 0, "disconnect drains graph");

        // Queue cancellation: add/remove before any propagation, then a distant teleport.
        sources.add(a); graph.updateSource(a, 0, true);
        sources.remove(a); graph.updateSource(a, Integer.MAX_VALUE, false);
        long teleported = ChunkPos.pack(1024, -2048);
        sources.add(teleported); graph.updateSource(teleported, 0, true); verify(graph, sources);
        graph.setViewDistance(256); graph.setViewDistance(16); verify(graph, sources);
        check(graph.radius() == 32, "ordinary distances do not propagate a 256-chunk graph");
        for (int distance : new int[]{2, 16, 32, 33, 127, 128, 255, 256})
            check(NativeChunkDistance.wireDistance(distance) == Math.min(distance, 32), "wire compatibility " + distance);
        System.out.println("Native chunk distance smoke passed: 33/36/64/127/128/255/256, exact multi-source distances, move/teleport/remove/resize/drain, wire bounds");
    }

    private static void verify(WidePlayerDistanceGraph graph, LongOpenHashSet sources) {
        graph.runUpdates();
        check(graph.queued() == 0, "queue drains");
        var expected = new LongOpenHashSet();
        int radius = graph.radius();
        for (long source : sources) {
            var pos = ChunkPos.unpack(source);
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++)
                expected.add(ChunkPos.pack(pos.x() + dx, pos.z() + dz));
        }
        check(graph.size() == expected.size(), "complete coverage: " + graph.size() + " vs " + expected.size());
        for (long node : expected) {
            var pos = ChunkPos.unpack(node);
            int closest = Integer.MAX_VALUE;
            for (long source : sources) {
                var origin = ChunkPos.unpack(source);
                closest = Math.min(closest, Math.max(Math.abs(origin.x() - pos.x()), Math.abs(origin.z() - pos.z())));
            }
            check(graph.level(node) == closest, "exact distance at " + pos + ": " + graph.level(node) + " vs " + closest);
        }
        graph.forEach((node, level) -> check(expected.contains(node), "no stale nodes"));
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
