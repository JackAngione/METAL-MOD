package dev.metalcraft.client.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded synthetic production-store stress; does not claim generated-world or GPU coverage. */
public final class LodDenseProbe {
    private static final long BUDGET = 512L << 20;

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("metalcraft-dense-probe");
        var report = new com.google.gson.JsonObject();
        report.addProperty("scope", "Synthetic opaque sections through production disk/cache worker; no live terrain or GPU performance claim");
        try {
            var rows = new com.google.gson.JsonArray();
            for (int quads : new int[]{1, 640}) rows.add(run(root.resolve("quads-" + quads), quads));
            report.add("cases", rows);
        } finally {
            try (var paths = Files.walk(root)) {
                for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        Path output = Path.of("build/reports/lod-dense-probe.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println(report);
    }

    private static com.google.gson.JsonObject run(Path root, int quads) throws Exception {
        var keys = new ArrayList<LodDistantNode.Key>();
        // A contiguous 32x32-section patch entirely outside the ordinary loaded square.
        // 640 quads/section exceeds the 64 MiB result budget while remaining disk-bounded.
        long started = System.nanoTime();
        try (var store = new LodDistantStore(root, "fixture", "dimension", "atlas", BUDGET)) {
            for (int z = 32; z < 64; z++) {
                var row = new ArrayList<LodDistantNode>();
                for (int x = 32; x < 64; x++) {
                    var key = new LodDistantNode.Key(0, x, 0, z);
                    keys.add(key);
                    row.add(node(key, quads, 0));
                }
                store.updateLeaves(row.subList(0, 16), java.util.Set.of());
                store.updateLeaves(row.subList(16, 32), java.util.Set.of());
            }
        }
        long constructionNanos = System.nanoTime() - started;
        var work = new ArrayDeque<Runnable>();
        var revisions = new LodRevisionTracker(4096);
        var cache = new LodDistantCache(budget -> new LodDistantStore(root, "fixture", "dimension", "atlas", budget), work::add);
        var result = new com.google.gson.JsonObject();
        try {
            cache.view(new LodDistantCache.View(0, 8, 0, 16, 128, BUDGET, 0, 0, 1, 1, k -> true));
            drain(work);
            var selected = cache.takeResult();
            result.addProperty("quadsPerSection", quads);
            result.addProperty("exploredFixtureSections", keys.size());
            result.addProperty("constructionNanos", constructionNanos);
            result.addProperty("representedSections", coverage(selected, keys, cache));
            result.addProperty("selectedNodes", selected.nodes().size());
            result.addProperty("selectedBytes", selected.nodes().stream().mapToLong(c -> c.node().bytes()).sum());
            var repairNanos = new com.google.gson.JsonArray();
            // Continuous producer: submit the next batch before the previous batch drains.
            // Same leaves coalesce to their latest revision; worker admission remains bounded.
            for (int round = 0; round < 24; round++) {
                long begin = System.nanoTime();
                for (int i = 0; i < 32; i++) {
                    var key = keys.get(i);
                    revisions.dirty(key.x(), key.y(), key.z());
                    cache.invalidate(key);
                    cache.capture(node(key, quads, round + 1), revisions.capture(key.x(), key.y(), key.z()));
                }
                check(work.size() == 1, "one scheduled worker under producer churn");
                work.remove().run();
                var partial = cache.takeResult();
                if (partial != null) coverage(partial, keys, cache);
                check(cache.stats().queuedBytes() <= LodDistantCache.QUEUE_BYTES
                        && cache.stats().queuedNodes() <= 32, "coalesced repair backlog bounded");
                check(cache.stats().failures() == 0 && cache.stats().dropped() == 0, "repair churn lost data");
                repairNanos.add(System.nanoTime() - begin);
            }
            long settleStarted = System.nanoTime();
            drain(work);
            result.addProperty("finalDrainNanos", System.nanoTime() - settleStarted);
            for (var key : keys.subList(0, 32)) {
                var entry = cache.entry(key);
                check(entry.stored() && entry.receivedRevision() == entry.persistedRevision(), "latest edit persisted");
            }
            var finalResult = cache.takeResult();
            result.addProperty("finalRepresentedSections", coverage(finalResult, keys, cache));
            result.add("producerRoundNanos", repairNanos);
            result.add("stats", new com.google.gson.Gson().toJsonTree(cache.stats()));
            result.add("diagnostics", new com.google.gson.Gson().toJsonTree(cache.diagnostics()));
            check(cache.stats().diskBytes() <= BUDGET, "disk budget");
            if (quads == 1) check(coverage(finalResult, keys, cache) == keys.size(), "unconstrained dense patch has complete coverage");
        } finally {
            cache.close();
            drain(work);
        }
        try (var store = new LodDistantStore(root, "fixture", "dimension", "atlas", BUDGET)) {
            for (var key : keys.subList(0, 32))
                check(store.read(key).layers().getFirst().buffer().getInt(12) == 24, "reopen retains final edit payload");
        }
        return result;
    }

    private static int coverage(LodDistantCache.Result result, List<LodDistantNode.Key> keys, LodDistantCache cache) {
        check(result != null && result.nodes().size() <= LodDistantCache.MAX_NODES, "bounded published result");
        check(result.nodes().stream().mapToLong(c -> c.node().bytes()).sum() <= LodDistantCache.RESULT_BYTES, "result byte budget");
        int covered = 0;
        for (var candidate : result.nodes()) check(candidate.version() == cache.version(candidate.node().key()), "no stale repair selection");
        for (var key : keys) {
            long owners = result.nodes().stream().filter(c -> c.node().key().contains(key)).count();
            check(owners <= 1, "no overlapping parent/child owners");
            covered += owners;
        }
        return covered;
    }

    private static LodDistantNode node(LodDistantNode.Key key, int quads, int revision) {
        var bytes = ByteBuffer.allocate(quads * 112).order(ByteOrder.LITTLE_ENDIAN);
        for (int q = 0; q < quads; q++) for (int corner : new int[]{0, 1, 3, 2}) {
            bytes.putFloat((q % 16) + (corner & 1)).putFloat(q / 256f)
                    .putFloat((q / 16 % 16) + (corner >> 1)).putInt(revision)
                    .putFloat(corner & 1).putFloat(corner >> 1).putInt(240);
        }
        return new LodDistantNode(key, List.of(new LodDistantNode.Layer(0, bytes.array())), 0, 1);
    }

    private static void drain(ArrayDeque<Runnable> work) {
        int count = 0;
        while (!work.isEmpty()) {
            check(++count < 1000, "worker made bounded progress");
            work.remove().run();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
