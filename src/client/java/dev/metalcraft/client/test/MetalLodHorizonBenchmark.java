package dev.metalcraft.client.test;

import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.MetalCraftRenderResolution;
import dev.metalcraft.client.lod.LodDistantRenderer;
import dev.metalcraft.client.metal.MetalSurfaceProbe;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.network.PlayerChunkSender;

/** Paired stationary cost of adding an explored patch beyond 16 chunks, never dense-horizon coverage. */
final class MetalLodHorizonBenchmark {
    static List<Map<String, Object>> measure(ClientGameTestContext context, TestSingleplayerContext world, int horizon) {
        var saved = MetalCraftConfig.lod();
        boolean half = MetalCraftConfig.halfResolution();
        String pack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        var results = new ArrayList<Map<String, Object>>();
        try {
            var tracked = world.getServer().computeOnServer(server -> {
                var positions = new ArrayList<net.minecraft.world.level.ChunkPos>();
                server.getPlayerList().getPlayers().getFirst().getChunkTrackingView().forEach(positions::add);
                return List.copyOf(positions);
            });
            if (tracked.size() < 1000) throw new AssertionError("Horizon benchmark lacks the 16-chunk server footprint");
            boolean loaded = false;
            for (int tick = 0; tick < 2400; tick++) {
                world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().forEach(player ->
                        player.connection.chunkSender.onChunkBatchReceivedByClient(PlayerChunkSender.MAX_CHUNKS_PER_TICK)));
                context.waitTick();
                if (tick % 20 == 0 && context.computeOnClient(c -> tracked.stream().allMatch(p -> c.level.hasChunk(p.x(), p.z())))) {
                    loaded = true; break;
                }
            }
            if (!loaded) throw new AssertionError("Missing server-tracked terrain in horizon benchmark");
            for (String selectedPack : List.of(ShaderPackRuntime.BUILTIN_ID, ShaderPackRuntime.NONE_ID)) {
                for (boolean selectedHalf : new boolean[]{false, true}) {
                    context.runOnClient(c -> {
                        ShaderPackRuntime.active().selectPack(selectedPack);
                        MetalCraftConfig.setHalfResolution(selectedHalf);
                        c.options.enableVsync().set(false);
                        c.options.framerateLimit().set(net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF);
                        c.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                        c.invalidateSurfaceConfiguration();
                        MetalCraftRenderResolution.apply(c);
                    });
                    context.waitTicks(40);
                    for (int repeat = 0; repeat < 3; repeat++) for (int order = 0; order < 2; order++) {
                        // Reverse order on alternating repeats to expose ordering/thermal bias.
                        boolean enabled = ((repeat + order) & 1) != 0;
                        context.runOnClient(c -> MetalCraftConfig.setLod(saved.withEnabled(enabled)));
                        context.waitTicks(40);
                        world.getConnection().waitForChunksRender(false, 2400);
                        if (enabled) MetalLodHorizonGameTest.await(context, "benchmark warm cache", () ->
                                LodDistantRenderer.stats().frameFarthestBlocks() > horizon * 10
                                && LodDistantRenderer.stats().cache() != null
                                && LodDistantRenderer.stats().cache().queuedNodes() == 0);
                        // Exclude initial residency uploads from steady-state measurements.
                        long lastUploads = -1;
                        int stable = 0;
                        for (int tick = 0; tick < 400 && stable < 20; tick++) {
                            context.waitTick();
                            long uploads = LodDistantRenderer.stats().uploads();
                            stable = uploads == lastUploads ? stable + 1 : 0;
                            lastUploads = uploads;
                        }
                        if (stable < 20) throw new AssertionError("Horizon GPU residency did not stabilize");
                        var before = LodDistantRenderer.stats();
                        String name = "horizon-" + horizon + "-" + selectedPack + "-half-" + selectedHalf + "-lod-" + enabled + "#" + (repeat + 1);
                        context.runOnClient(c -> MetalFrameMetrics.beginCapture(30));
                        context.waitTicks(80);
                        var phase = context.computeOnClient(c -> MetalFrameMetrics.endCapture(name));
                        var after = LodDistantRenderer.stats();
                        if (phase.frames() < 120 || phase.gpuFrame().samplesMs().length < phase.frames() * .95
                                || phase.gpuFrame().invalidFrames() != 0 || phase.gpuFrame().overflowFrames() != 0)
                            throw new AssertionError("Incomplete horizon frame timing: " + name);
                        if (enabled && (after.draws() <= before.draws() || after.frameSections() == 0
                                || after.uploadFailures() != before.uploadFailures() || after.gpuBytes() > LodDistantRenderer.MAX_GPU_BYTES))
                            throw new AssertionError("Horizon benchmark lost valid bounded terrain ownership");
                        var row = new LinkedHashMap<String, Object>();
                        row.put("phase", com.google.gson.JsonParser.parseString(phase.toJson()));
                        row.put("horizon", horizon); row.put("enabled", enabled); row.put("repeat", repeat + 1);
                        row.put("environment", context.computeOnClient(c -> MetalBenchmarkEnvironment.describe()));
                        row.put("camera", context.computeOnClient(c -> MetalBenchmarkEnvironment.camera()));
                        row.put("drawable", MetalSurfaceProbe.drawableSize());
                        row.put("trackedChunks", tracked.size()); row.put("missingTrackedChunks", 0);
                        row.put("distantBefore", before); row.put("distantAfter", after);
                        results.add(row);
                        System.out.println("LOD horizon benchmark: " + name + " CPU interval=" + phase.p50IntervalMs()
                                + " GPU=" + phase.gpuFrame().p50Ms() + " ms, represented sections=" + after.frameSections());
                        if (repeat == 0) context.takeScreenshot(name);
                    }
                }
            }
            return results;
        } finally {
            context.runOnClient(c -> {
                MetalCraftConfig.setLod(saved);
                MetalCraftConfig.setHalfResolution(half);
                ShaderPackRuntime.active().selectPack(pack);
                c.invalidateSurfaceConfiguration();
            });
        }
    }
}
