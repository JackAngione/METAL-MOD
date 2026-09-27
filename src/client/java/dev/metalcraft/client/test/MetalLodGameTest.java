package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.lod.LodStats;
import dev.metalcraft.client.lod.LodSystem;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/**
 * Short distant-terrain route at 128 render / 16 simulation distance with a 12-chunk native
 * radius: activation and loading limits, time for the view to settle, cost against native-only
 * rendering, movement, a brief 1024-chunk view, the Standard pack, and turning the feature off. Reuses an existing
 * standard save when present ({@code metalcraft.lodTestWorld}), otherwise creates one.
 */
final class MetalLodGameTest {
    private static final int RENDER = 128, NATIVE = 12, SIMULATION = 16, DETAIL = 5, EXTREME = 1024;
    /** Preferred standard saves, in order; a save another running instance holds is skipped. */
    private static final String[] WORLDS = {System.getProperty("metalcraft.lodTestWorld", "New World (4)"), "New World", "New World (3)"};

    private MetalLodGameTest() { }

    static void run(ClientGameTestContext context) {
        int savedRender = context.computeOnClient(c -> c.options.renderDistance().get());
        int savedSimulation = context.computeOnClient(c -> c.options.simulationDistance().get());
        boolean savedVsync = context.computeOnClient(c -> c.options.enableVsync().get());
        boolean savedLod = MetalCraftConfig.lodEnabled(), savedFog = MetalCraftConfig.clearDistanceFog();
        int savedNative = MetalCraftConfig.lodNativeDistance(), savedDetail = MetalCraftConfig.lodDetail();
        String savedPack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        Map<String, Object> report = new LinkedHashMap<>();
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "Metal backend");
                c.options.renderDistance().set(RENDER);
                c.options.simulationDistance().set(SIMULATION);
                c.options.enableVsync().set(false);
                MetalCraftConfig.setLodEnabled(true);
                MetalCraftConfig.setLodNativeDistance(NATIVE);
                MetalCraftConfig.setLodDetail(DETAIL);
                MetalCraftConfig.setClearDistanceFog(true);
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
            });
            context.getInput().resizeWindow(1280, 720);
            try (World world = World.open(context)) {
                report.put("world", world.name);
                world.command("gamemode spectator @a");
                world.command("time set noon");
                world.command("weather clear");
                int[] start = context.computeOnClient(c -> new int[]{c.player.getBlockX(), c.player.getBlockZ()});
                world.command("tp @a " + start[0] + " 200 " + start[1] + " 135 12");
                context.getInput().lookAt(135, 12);
                long loadStarted = System.nanoTime();
                context.waitFor(c -> LodSystem.active() && !c.levelRenderer.visibleSections().isEmpty(), 1200);
                check(context.computeOnClient(c -> c.options.getEffectiveRenderDistance()) == NATIVE, "client meshes the native radius only");
                check(world.computeOnServer(server -> server.getPlayerList().getViewDistance()) == NATIVE, "server sends the native radius only");
                report.put("settleSeconds", settle(context, 2400));
                report.put("settleFromWorldSeconds", (System.nanoTime() - loadStarted) / 1e9);
                LodStats settled = LodSystem.stats();
                check(settled.drawnNodes > 0 && settled.gpuBytes > 0, "distant terrain drawn: " + json(settled));
                int clientChunks = context.computeOnClient(c -> c.level.getChunkSource().getLoadedChunksCount());
                check(clientChunks <= (2 * NATIVE + 5) * (2 * NATIVE + 5), "client chunks bounded by native radius: " + clientChunks);
                report.put("clientChunks", clientChunks);
                report.put("serverChunks", world.computeOnServer(server -> server.overworld().getChunkSource().getLoadedChunksCount()));
                report.put("settled", settled);
                report.put("nativeSettleSeconds", nativeSettle(context));
                context.takeScreenshot("lod-128-none");

                report.put("lod128", measure(context, "lod-128"));
                report.put("lod128Stats", LodSystem.stats());
                // Same view without distant terrain: the ordinary native radius alone.
                context.runOnClient(c -> c.options.renderDistance().set(NATIVE));
                context.waitFor(c -> !LodSystem.active(), 200);
                context.waitTicks(40);
                report.put("native12", measure(context, "native-12"));
                context.runOnClient(c -> c.options.renderDistance().set(RENDER));
                context.waitFor(c -> LodSystem.active(), 200);
                settle(context, 1200);

                // Fly 512 blocks: the native radius and the distant view both move.
                world.command("tp @a " + (start[0] + 512) + " 200 " + start[1] + " 135 12");
                report.put("moveSettleSeconds", settle(context, 2400));
                report.put("moved", LodSystem.stats());
                // Captured before native chunks arrive: distant models stand in for them.
                context.takeScreenshot("lod-128-moved-early");
                report.put("moveNativeSettleSeconds", nativeSettle(context));
                context.takeScreenshot("lod-128-moved");

                // Extreme total distance: only distant terrain grows; loading stays at the native radius.
                context.runOnClient(c -> c.options.renderDistance().set(EXTREME));
                report.put("extremeSettleSeconds", settle(context, 2400));
                check(context.computeOnClient(c -> c.options.getEffectiveRenderDistance()) == NATIVE, "native radius unchanged at extreme distance");
                report.put("extremeStats", LodSystem.stats());
                report.put("extreme", measure(context, "lod-" + EXTREME));
                context.takeScreenshot("lod-" + EXTREME);
                context.runOnClient(c -> c.options.renderDistance().set(RENDER));
                settle(context, 1200);

                context.runOnClient(c -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID));
                context.waitTicks(20);
                check(context.computeOnClient(c -> ShaderPackRuntime.active().isActive()), "Standard pack active");
                context.takeScreenshot("lod-128-standard");
                report.put("standard", measure(context, "lod-128-standard"));
                context.runOnClient(c -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID));

                // Off restores ordinary loading; keep the render distance small first.
                context.runOnClient(c -> {
                    c.options.renderDistance().set(SIMULATION);
                    MetalCraftConfig.setLodEnabled(false);
                });
                context.waitTicks(10);
                check(!context.computeOnClient(c -> LodSystem.active()) && LodSystem.stats().gpuBytes == 0, "disabled distant terrain releases its resources");
                check(context.computeOnClient(c -> c.options.getEffectiveRenderDistance()) == SIMULATION, "ordinary render distance restored");
            }
            write(report);
            System.out.println("Distant terrain game test passed: " + json(report));
        } finally {
            context.runOnClient(c -> {
                c.options.renderDistance().set(savedRender);
                c.options.simulationDistance().set(savedSimulation);
                c.options.enableVsync().set(savedVsync);
                MetalCraftConfig.setLodEnabled(savedLod);
                MetalCraftConfig.setLodNativeDistance(savedNative);
                MetalCraftConfig.setLodDetail(savedDetail);
                MetalCraftConfig.setClearDistanceFog(savedFog);
                ShaderPackRuntime.active().selectPack(savedPack);
            });
        }
    }

    /** Seconds until every wanted node is built and uploaded. */
    private static double settle(ClientGameTestContext context, int timeoutTicks) {
        long started = System.nanoTime();
        context.waitTicks(5);
        context.waitFor(c -> {
            LodStats stats = LodSystem.stats();
            return stats.pending == 0 && stats.inFlight == 0 && stats.drawnNodes > 0;
        }, timeoutTicks);
        return (System.nanoTime() - started) / 1e9;
    }

    /** Seconds until the native radius around the camera is loaded and every queued section is compiled. */
    private static double nativeSettle(ClientGameTestContext context) {
        long started = System.nanoTime();
        context.waitFor(c -> {
            var camera = c.gameRenderer.mainCamera().position();
            int centerX = net.minecraft.core.SectionPos.blockToSectionCoord(camera.x), centerZ = net.minecraft.core.SectionPos.blockToSectionCoord(camera.z);
            int radius = NATIVE - 1;
            for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
                if (x * x + z * z <= radius * radius && !c.level.getChunkSource().hasChunk(centerX + x, centerZ + z)) return false;
            }
            return c.levelRenderer.hasRenderedAllSections();
        }, 2400);
        context.waitTicks(10);
        return (System.nanoTime() - started) / 1e9;
    }

    private static Map<String, Object> measure(ClientGameTestContext context, String name) {
        context.runOnClient(ignored -> MetalFrameMetrics.beginCapture(30));
        context.waitTicks(100);
        MetalFrameMetrics.Phase phase = context.computeOnClient(ignored -> MetalFrameMetrics.endCapture(name));
        System.out.printf("%s: %.1f FPS average, %.1f 1%% low, CPU p50 %.2f ms p95 %.2f ms%n", name, phase.averageFps(),
            phase.onePercentLowFps(), phase.p50CpuMs(), phase.p95CpuMs());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("frames", phase.frames());
        summary.put("averageFps", phase.averageFps());
        summary.put("onePercentLowFps", phase.onePercentLowFps());
        summary.put("p50CpuMs", phase.p50CpuMs());
        summary.put("p95CpuMs", phase.p95CpuMs());
        summary.put("p99CpuMs", phase.p99CpuMs());
        summary.put("lodPrepareP50Ms", phase.lod().p50PrepareMs());
        summary.put("lodPrepareP95Ms", phase.lod().p95PrepareMs());
        return summary;
    }

    private static void write(Map<String, Object> report) {
        try {
            Path path = Path.of("build", "lod-test.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, json(report));
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String json(Object value) {
        return new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** An existing save opened through the world list, or a fresh standard world. */
    private static final class World implements AutoCloseable {
        private final ClientGameTestContext context;
        private final TestSingleplayerContext created;
        final String name;

        private World(ClientGameTestContext context, TestSingleplayerContext created, String name) {
            this.context = context;
            this.created = created;
            this.name = name;
        }

        static World open(ClientGameTestContext context) {
            for (String name : WORLDS) {
                if (!context.computeOnClient(c -> available(c, name))) continue;
                context.runOnClient(c -> c.createWorldOpenFlows().openWorld(name, () -> { }));
                context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 2400);
                return new World(context, null, name);
            }
            TestSingleplayerContext created = context.worldBuilder().adjustSettings(settings -> {
                var normal = settings.getSettings().worldgenLoadContext().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                    .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
                settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
                settings.setSeed("metalcraft");
                settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                settings.setAllowCommands(true);
            }).create();
            context.waitFor(c -> c.level != null && c.player != null, 2400);
            return new World(context, created, "new NORMAL world (seed metalcraft)");
        }

        /** Exists and is not locked by another running game. */
        private static boolean available(net.minecraft.client.Minecraft client, String name) {
            if (!client.getLevelSource().levelExists(name)) return false;
            try (var access = client.getLevelSource().validateAndCreateAccess(name)) {
                return true;
            } catch (Exception unavailable) {
                return false;
            }
        }

        void command(String command) {
            if (this.created != null) {
                this.created.getServer().runCommand(command);
                return;
            }
            this.computeOnServer(server -> {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
                return Boolean.TRUE;
            });
        }

        /**
         * Runs on the server thread. The harness ticks client and server in lockstep, so the client
         * thread must keep ticking while it waits rather than block on the server.
         */
        <T> T computeOnServer(java.util.function.Function<net.minecraft.server.MinecraftServer, T> function) {
            var result = new java.util.concurrent.atomic.AtomicReference<T>();
            var done = new java.util.concurrent.atomic.AtomicBoolean();
            this.context.runOnClient(c -> {
                var server = c.getSingleplayerServer();
                server.execute(() -> {
                    result.set(function.apply(server));
                    done.set(true);
                });
            });
            this.context.waitFor(c -> done.get(), 400);
            return result.get();
        }

        @Override
        public void close() {
            if (this.created != null) {
                this.created.close();
                return;
            }
            // The pause menu's Save and Quit, then the harness's own return to the title screen.
            var server = this.context.computeOnClient(c -> c.getSingleplayerServer());
            this.context.runOnClient(c -> c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE));
            this.context.waitFor(c -> c.level == null && server.isShutdown(), 1200);
            this.context.setScreen(TitleScreen::new);
        }
    }
}
