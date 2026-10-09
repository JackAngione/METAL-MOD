package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.MetalCraftRenderResolution;
import dev.metalcraft.client.mixin.WindowFramebufferAccessor;
import org.lwjgl.glfw.GLFW;
import dev.metalcraft.client.gui.MetalCraftLodOptionsScreen;
import dev.metalcraft.client.lod.LodStats;
import dev.metalcraft.client.lod.LodSystem;
import dev.metalcraft.client.metal.MetalSurfaceProbe;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/**
 * Short distant-terrain route at 128 render / 16 simulation distance with a 12-chunk native
 * radius: activation and loading limits, time for the view to settle, cost against native-only
 * rendering, the highest (textured) detail, movement, a brief 1024-chunk view, the Standard pack,
 * and turning the feature off. Reuses an existing
 * standard save when present ({@code metalcraft.lodTestWorld}), otherwise creates one.
 */
final class MetalLodGameTest {
    private static final int RENDER = 128, NATIVE = 12, SIMULATION = 16, DETAIL = 5, HIGHEST_DETAIL = 8, EXTREME = 1024;
    /** Preferred standard saves, in order; a save another running instance holds is skipped. */
    private static final String[] WORLDS = {System.getProperty("metalcraft.lodTestWorld", "New World (4)"), "New World", "New World (3)"};

    private MetalLodGameTest() { }

    /** Regress the shadow/storage lock inversion during native distance changes and LOD handoff. */
    static void resize(ClientGameTestContext context) {
        int savedRender = context.computeOnClient(c -> c.options.renderDistance().get());
        int savedSimulation = context.computeOnClient(c -> c.options.simulationDistance().get());
        boolean savedLod = MetalCraftConfig.lodEnabled();
        int savedNative = MetalCraftConfig.lodNativeDistance(), savedDetail = MetalCraftConfig.lodDetail();
        String savedPack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "Metal backend");
                c.options.renderDistance().set(RENDER);
                c.options.simulationDistance().set(16);
                MetalCraftConfig.setLodEnabled(true);
                MetalCraftConfig.setLodNativeDistance(16);
                MetalCraftConfig.setLodDetail(7);
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID);
            });
            context.getInput().resizeWindow(1280, 720);
            try (World world = World.open(context)) {
                check(!world.computeOnServer(server -> server.overworld().isFlat()), "standard world");
                context.waitFor(c -> LodSystem.active() && LodSystem.stats().drawnNodes > 0, 1200);
                context.waitFor(c -> ShaderPackRuntime.active().worldShadows() != null
                    && ShaderPackRuntime.active().worldShadows().lastDrawCount() > 0, 400);
                for (int distance : new int[]{200, 86, 71}) {
                    System.out.println("Distance resize: LOD -> " + distance);
                    context.runOnClient(c -> c.options.renderDistance().set(distance));
                    context.waitTicks(10);
                }
                System.out.println("Distance resize: disable LOD at 71");
                context.runOnClient(c -> MetalCraftConfig.setLodEnabled(false));
                context.waitFor(c -> c.levelRenderer.viewArea().getViewDistance() == 71, 400);
                context.waitTicks(20);
                for (int distance : new int[]{200, 16, 128, 32, 71, 16}) {
                    System.out.println("Distance resize: native -> " + distance);
                    context.runOnClient(c -> { c.options.renderDistance().set(distance); c.options.broadcastOptions(); });
                    context.waitFor(c -> c.levelRenderer.viewArea().getViewDistance() == distance, 400);
                    context.waitTicks(10);
                }
                check(world.computeOnServer(server -> server.getPlayerList().getViewDistance()) == 16, "server distance shrinks");
                context.waitFor(c -> !c.levelRenderer.visibleSections().isEmpty(), 400);
                context.waitFor(c -> ShaderPackRuntime.active().worldShadows() != null
                    && ShaderPackRuntime.active().worldShadows().lastDrawCount() > 0, 400);
                System.out.println("Distance resize passed: standard world, Metal Standard shadows, LOD shrink, disable at 71, native 200 -> 16 and repeated resizing");
            }
        } finally {
            context.runOnClient(c -> {
                c.options.renderDistance().set(savedRender);
                c.options.simulationDistance().set(savedSimulation);
                MetalCraftConfig.setLodEnabled(savedLod);
                MetalCraftConfig.setLodNativeDistance(savedNative);
                MetalCraftConfig.setLodDetail(savedDetail);
                ShaderPackRuntime.active().selectPack(savedPack);
            });
        }
    }

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
                // Highest detail, set in the distant terrain screen as a player would: the view re-plans
                // at once although the screen pauses the game, and once it is complete the nodes only
                // the previous detail used are released.
                LodStats balanced = LodSystem.stats();
                context.setScreen(() -> new MetalCraftLodOptionsScreen(new PauseScreen(true)));
                context.runOnClient(c -> MetalCraftConfig.setLodDetail(HIGHEST_DETAIL));
                context.waitTicks(2);
                LodStats replanning = LodSystem.stats();
                check(replanning.updating && replanning.pending + replanning.inFlight > 0, "detail change starts rebuilding: " + json(replanning));
                report.put("detail8SettleSeconds", settle(context, 2400));
                LodStats highest = LodSystem.stats();
                check(!highest.updating, "detail change completes: " + json(highest));
                check(highest.texturedNodes > 0 && highest.drawnNodes > balanced.drawnNodes,
                    "highest detail draws finer, textured distant terrain: " + json(highest));
                context.setScreen(() -> null);
                report.put("detail8Stats", highest);
                report.put("detail8", measure(context, "lod-128-detail-8"));
                context.takeScreenshot("lod-128-detail-8");
                context.runOnClient(c -> MetalCraftConfig.setLodDetail(DETAIL));
                settle(context, 1200);
                LodStats back = LodSystem.stats();
                check(back.gpuBytes < highest.gpuBytes, "returning to detail " + DETAIL + " releases the finer view: " + json(back));
                report.put("detailRestoredStats", back);
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

    /**
     * Brief 4K captures and timings ({@code -PmetalLodTest=visual}) across the native boundary
     * at the requested detail steps, followed by the same view with native terrain only.
     */
    static void visual(ClientGameTestContext context) {
        int savedRender = context.computeOnClient(c -> c.options.renderDistance().get());
        int savedSimulation = context.computeOnClient(c -> c.options.simulationDistance().get());
        boolean savedLod = MetalCraftConfig.lodEnabled(), savedFog = MetalCraftConfig.clearDistanceFog();
        boolean savedHalf = MetalCraftConfig.halfResolution();
        boolean savedVsync = context.computeOnClient(c -> c.options.enableVsync().get());
        int savedFpsLimit = context.computeOnClient(c -> c.options.framerateLimit().get());
        int[] savedWindow = context.computeOnClient(c -> new int[]{c.getWindow().getScreenWidth(), c.getWindow().getScreenHeight()});
        boolean savedFullscreen = context.computeOnClient(c -> c.getWindow().isFullscreen());
        Map<String, Object> report = new LinkedHashMap<>();
        int savedNative = MetalCraftConfig.lodNativeDistance(), savedDetail = MetalCraftConfig.lodDetail();
        String savedPack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        int nativeDistance = Integer.getInteger("metalcraft.lodVisualNative", 8);
        int[] details = java.util.Arrays.stream(System.getProperty("metalcraft.lodVisualDetails", "7,8").split(","))
            .mapToInt(Integer::parseInt).toArray();
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "Metal backend");
                MetalCraftConfig.setHalfResolution(false);
                c.options.enableVsync().set(false);
                c.options.framerateLimit().set(net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF);
                if (c.getWindow().isFullscreen()) c.getWindow().toggleFullScreen();
                c.options.renderDistance().set(RENDER);
                c.options.simulationDistance().set(SIMULATION);
                MetalCraftConfig.setLodEnabled(true);
                MetalCraftConfig.setLodNativeDistance(nativeDistance);
                MetalCraftConfig.setLodDetail(details[0]);
                MetalCraftConfig.setClearDistanceFog(true);
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
            });
            float scale = context.computeOnClient(c -> {
                float[] x = new float[1], y = new float[1];
                GLFW.glfwGetWindowContentScale(c.getWindow().handle(), x, y);
                return Math.max(1F, x[0]);
            });
            context.getInput().resizeWindow(Math.round(3840 / scale), Math.round(2160 / scale));
            context.waitTicks(5);
            context.runOnClient(c -> {
                var framebuffer = (WindowFramebufferAccessor)(Object)c.getWindow();
                framebuffer.metalcraft$setFramebufferWidth(3840);
                framebuffer.metalcraft$setFramebufferHeight(2160);
                c.framebufferSizeChanged();
            });
            try (World world = World.open(context)) {
                check(!world.computeOnServer(server -> server.overworld().isFlat()), "standard world");
                report.put("world", world.name);
                context.waitFor(c -> c.gameRenderer.mainRenderTarget().getColorTextureView().getWidth(0) == 3840
                    && c.gameRenderer.mainRenderTarget().getColorTextureView().getHeight(0) == 2160, 200);
                world.command("gamemode spectator @a");
                world.command("time set noon");
                world.command("weather clear");
                int[] start = context.computeOnClient(c -> new int[]{c.player.getBlockX(), c.player.getBlockZ()});
                int ground = world.computeOnServer(server -> server.overworld()
                    .getChunk(start[0] >> 4, start[1] >> 4).getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, start[0] & 15, start[1] & 15));
                int eye = Math.max(ground, 63) + Integer.getInteger("metalcraft.lodVisualHeight", 24);
                world.command("tp @a " + start[0] + " " + eye + " " + start[1] + " 135 8");
                context.getInput().lookAt(135, 8);
                context.waitFor(c -> LodSystem.active() && !c.levelRenderer.visibleSections().isEmpty(), 1200);
                nativeSettle(context, nativeDistance);
                for (int detail : details) {
                    context.runOnClient(c -> MetalCraftConfig.setLodDetail(detail));
                    settle(context, 2400);
                    context.waitTicks(20);
                    report.put("detail" + detail + "Dimensions", context.computeOnClient(c -> visualDimensions("detail-" + detail)));
                    report.put("detail" + detail + "Stats", LodSystem.stats());
                    report.put("detail" + detail + "Timing", measure(context, "lod-visual-detail-" + detail));
                    System.out.println("Distant terrain visual detail " + detail + ": " + json(LodSystem.stats()));
                    context.takeScreenshot("lod-visual-detail-" + detail);
                }
                // The same view with only the native radius, for comparison.
                context.runOnClient(c -> c.options.renderDistance().set(nativeDistance));
                context.waitFor(c -> !LodSystem.active(), 200);
                context.waitTicks(40);
                context.takeScreenshot("lod-visual-native");
            }
            write(report);
        } finally {
            context.runOnClient(c -> {
                c.options.renderDistance().set(savedRender);
                c.options.simulationDistance().set(savedSimulation);
                MetalCraftConfig.setHalfResolution(savedHalf);
                c.options.enableVsync().set(savedVsync);
                c.options.framerateLimit().set(savedFpsLimit);
                MetalCraftConfig.setLodEnabled(savedLod);
                MetalCraftConfig.setLodNativeDistance(savedNative);
                MetalCraftConfig.setLodDetail(savedDetail);
                MetalCraftConfig.setClearDistanceFog(savedFog);
                ShaderPackRuntime.active().selectPack(savedPack);
            });
            context.getInput().resizeWindow(savedWindow[0], savedWindow[1]);
            context.runOnClient(c -> {
                if (c.getWindow().isFullscreen() != savedFullscreen) c.getWindow().toggleFullScreen();
                MetalCraftRenderResolution.apply(c);
            });
        }
    }

    private static Map<String, Object> visualDimensions(String phase) {
        var c = net.minecraft.client.Minecraft.getInstance();
        var target = c.gameRenderer.mainRenderTarget().getColorTextureView();
        int[] drawable = MetalSurfaceProbe.drawableSize();
        check(target.getWidth(0) == 3840 && target.getHeight(0) == 2160, "actual LOD world target is 4K");
        check(!MetalCraftConfig.halfResolution() && c.options.renderDistance().get() == RENDER
            && c.options.simulationDistance().get() == SIMULATION, "full-resolution 128/16 LOD testing");
        check(drawable[0] > 0 && drawable[1] > 0, "drawable dimensions available");
        System.out.println("LOD visual " + phase + ": worldTarget=" + target.getWidth(0) + "x" + target.getHeight(0)
            + ", presentationFramebuffer=" + c.getWindow().getWidth() + "x" + c.getWindow().getHeight()
            + ", windowPoints=" + c.getWindow().getScreenWidth() + "x" + c.getWindow().getScreenHeight()
            + ", drawable=" + drawable[0] + "x" + drawable[1]);
        return Map.of("backend", RenderSystem.getDevice().getDeviceInfo().backendName(),
            "worldTarget", new int[]{target.getWidth(0), target.getHeight(0)},
            "presentationFramebuffer", new int[]{c.getWindow().getWidth(), c.getWindow().getHeight()},
            "windowPoints", new int[]{c.getWindow().getScreenWidth(), c.getWindow().getScreenHeight()},
            "drawable", drawable, "renderDistance", c.options.renderDistance().get(),
            "simulationDistance", c.options.simulationDistance().get(), "nativeDistance", MetalCraftConfig.lodNativeDistance(),
            "vsync", c.options.enableVsync().get(), "fpsLimit", c.options.framerateLimit().get());
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
    private static double nativeSettle(ClientGameTestContext context) { return nativeSettle(context, NATIVE); }

    private static double nativeSettle(ClientGameTestContext context, int nativeDistance) {
        long started = System.nanoTime();
        context.waitFor(c -> {
            var camera = c.gameRenderer.mainCamera().position();
            int centerX = net.minecraft.core.SectionPos.blockToSectionCoord(camera.x), centerZ = net.minecraft.core.SectionPos.blockToSectionCoord(camera.z);
            int radius = nativeDistance - 1;
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
        // GPU time shows the cost of distant geometry even when the window's frame rate is paced.
        Double gpuP50 = phase.gpuFrame().p50Ms(), gpuP95 = phase.gpuFrame().p95Ms();
        System.out.printf("%s: %.1f FPS average, %.1f 1%% low, CPU p50 %.2f ms p95 %.2f ms, GPU p50 %s ms p95 %s ms%n", name, phase.averageFps(),
            phase.onePercentLowFps(), phase.p50CpuMs(), phase.p95CpuMs(), gpuP50 == null ? "-" : String.format("%.2f", gpuP50),
            gpuP95 == null ? "-" : String.format("%.2f", gpuP95));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("frames", phase.frames());
        summary.put("averageFps", phase.averageFps());
        summary.put("onePercentLowFps", phase.onePercentLowFps());
        summary.put("p50CpuMs", phase.p50CpuMs());
        summary.put("p95CpuMs", phase.p95CpuMs());
        summary.put("p99CpuMs", phase.p99CpuMs());
        summary.put("p50GpuMs", gpuP50);
        summary.put("p95GpuMs", gpuP95);
        // Average GPU span per pass: occupancy for ranking, not additive (see MetalPassCensus).
        Map<String, Double> passes = new LinkedHashMap<>();
        for (var pass : phase.passKinds()) passes.put(pass.name(), pass.count() == 0 ? 0 : pass.totalMs() / pass.count());
        summary.put("gpuPassMs", passes);
        passes.forEach((pass, millis) -> { if (pass.startsWith("Section layers")) System.out.printf("  %s: %.3f ms%n", pass, millis); });
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
