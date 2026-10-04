package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Interleaved baseline attribution in an existing NORMAL save; diagnostics never change defaults. */
final class BaselineLightingBenchmark {
    static void run(ClientGameTestContext context) {
        String world = System.getProperty("metalcraft.baselineWorld", "ShaderDistanceAudit20261003");
        String label = System.getProperty("metalcraft.baselineLabel", "before");
        boolean workOnly = Boolean.getBoolean("metalcraft.baselineWorkOnly");
        var report = new LinkedHashMap<String, Object>();
        var phases = new ArrayList<MetalFrameMetrics.Phase>();
        var reuse = new ArrayList<Object>();
        context.runOnClient(c -> {
            if (!c.getLevelSource().levelExists(world)) throw new AssertionError("Missing existing test save: " + world);
            if (!"Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName())) throw new AssertionError("Requires Metal");
            MetalCraftConfig.setLodEnabled(false);
            MetalCraftConfig.setHalfResolution(false);
            MetalCraftConfig.setUnlockedFrameRate(true);
            c.options.renderDistance().set(16);
            c.options.simulationDistance().set(16);
            c.options.cloudStatus().set(CloudStatus.OFF);
            c.options.enableVsync().set(false);
            c.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
            c.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            c.options.bobView().set(false);
            if (c.getWindow().isFullscreen()) c.getWindow().toggleFullScreen();
            ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
            c.createWorldOpenFlows().openWorld(world, () -> {});
        });
        context.getInput().resizeWindow(Integer.getInteger("metalcraft.baselineWidth", 3840),
            Integer.getInteger("metalcraft.baselineHeight", 2160));
        context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 1200);
        var server = context.computeOnClient(c -> c.getSingleplayerServer());
        if (!(server.overworld().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator)) throw new AssertionError("Requires NORMAL terrain");
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
            recording.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(2));
            context.runOnClient(c -> c.options.broadcastOptions());
            var configured = server.submit(() -> {
                server.getGameRules().set(GameRules.ADVANCE_TIME, true, server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 6000");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode spectator @a");
                // Fixed natural terrain next to the earlier pool fixture, not on its platform.
                int x = 4702, z = 546;
                int y = server.overworld().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 3;
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "tp @a " + x + " " + y + " " + z + " 135 15");
            });
            context.waitFor(c -> configured.isDone()); configured.join();
            context.getInput().lookAt(135, 15);
            var trackedFuture = server.submit(() -> {
                var chunks = new ArrayList<net.minecraft.world.level.ChunkPos>();
                server.getPlayerList().getPlayers().getFirst().getChunkTrackingView().forEach(chunks::add);
                return java.util.List.copyOf(chunks);
            });
            context.waitFor(c -> trackedFuture.isDone());
            var tracked = trackedFuture.join();
            boolean settled = false;
            for (int i = 0; i < 1200; i++) {
                context.runOnClient(c -> server.execute(() -> server.getPlayerList().getPlayers().forEach(player ->
                    player.connection.chunkSender.onChunkBatchReceivedByClient(net.minecraft.server.network.PlayerChunkSender.MAX_CHUNKS_PER_TICK))));
                context.waitTicks(1);
                if (context.computeOnClient(c -> !tracked.isEmpty() && tracked.stream().allMatch(p -> c.level.hasChunk(p.x(), p.z()))
                    && c.levelRenderer.sectionRenderDispatcher().isQueueEmpty()
                    && !c.level.getChunkSource().getLightEngine().hasLightWork())) { settled = true; break; }
            }
            if (!settled) throw new AssertionError("Terrain did not settle; refusing baseline timings");
            // Receiving chunks is not enough: visibility discovery schedules more mesh work.
            // Require a quiet publication interval before comparing shader variants.
            long stableRevision = -1;
            int quietTicks = 0;
            for (int i = 0; i < 600 && quietTicks < 40; i++) {
                context.waitTicks(1);
                long revision = dev.metalcraft.client.shader.world.ShadowFrameReuse.meshRevision();
                boolean idle = context.computeOnClient(c -> c.levelRenderer.sectionRenderDispatcher().isQueueEmpty()
                    && !c.level.getChunkSource().getLightEngine().hasLightWork());
                quietTicks = idle && revision == stableRevision ? quietTicks + 1 : 0;
                stableRevision = revision;
            }
            if (quietTicks < 40) throw new AssertionError("Terrain mesh publication did not settle");
            if (Boolean.getBoolean("metalcraft.shadowFlickerTest")) {
                ShadowFlickerGameTest.run(context);
                return;
            }
            if (!workOnly) MetalBenchmarkEnvironment.focus(context);
            context.runOnClient(c -> {
                c.gui.hud.getChat().clearMessages(true);
                report.put("device", MetalGpuDevices.current().metal().name());
                report.put("width", c.gameRenderer.mainRenderTarget().getColorTextureView().getWidth(0));
                report.put("height", c.gameRenderer.mainRenderTarget().getColorTextureView().getHeight(0));
                report.put("renderDistance", c.options.renderDistance().get());
                report.put("simulationDistance", c.options.simulationDistance().get());
                report.put("camera", c.player.position().toString());
                report.put("visibleSections", c.levelRenderer.visibleSections().size());
                report.put("trackedChunks", tracked.size());
                report.put("terrainSettled", true);
                report.put("scope", workOnly ? "Work counters and functional checks only; background FPS is not valid performance evidence" : "Focused, interleaved same-scene CPU/GPU comparison");
            });
            context.takeScreenshot("baseline-lighting-" + label);
            recording.start();
            String[] variants = System.getProperty("metalcraft.baselineVariants", "standard,no_local,no_shadow_draw,no_resolve,none").split(",");
            int repeats = Integer.getInteger("metalcraft.baselineRepeats", 2);
            for (int repeat = 0; repeat < repeats; repeat++) {
                for (int i = 0; i < variants.length; i++) {
                    String variant = variants[repeat % 2 == 0 ? i : variants.length - 1 - i];
                    context.runOnClient(c -> {
                        var runtime = ShaderPackRuntime.active();
                        boolean scalar = variant.equals("scalar_shadows");
                        System.setProperty("metalcraft.baselineScalarShadows", Boolean.toString(scalar));
                        String pack = variant.equals("none") ? ShaderPackRuntime.NONE_ID : ShaderPackRuntime.BUILTIN_ID;
                        if (!runtime.selectedPackId().equals(pack)) runtime.selectPack(pack);
                        if (!variant.equals("none")) {
                            option(runtime, "water_enabled", false);
                            option(runtime, "wind_enabled", false);
                            option(runtime, "local_lights", !variant.equals("no_local"));
                            option(runtime, "shadow_cascades", 2);
                            option(runtime, "shadow_resolution", 768);
                            option(runtime, "shadow_distance", 96);
                            option(runtime, "shadow_caster_distance", 144);
                            option(runtime, "distant_shadow_distance", 320);
                            option(runtime, "debug_view", "off");
                        }
                        System.setProperty("metalcraft.baselineSkipShadows", Boolean.toString(variant.equals("no_shadow_draw")));
                        System.setProperty("metalcraft.baselineSkipResolve", Boolean.toString(variant.equals("no_resolve")));
                        System.setProperty("metalcraft.baselineSkipSky", Boolean.toString(variant.equals("no_sky")));
                        System.setProperty("metalcraft.baselineSkipGrade", Boolean.toString(variant.equals("no_grade")));
                        System.setProperty("metalcraft.baselineSkipGbuffer", Boolean.toString(variant.equals("no_gbuffer")));
                        System.setProperty("metalcraft.baselineDisableShadowReuse", Boolean.toString(variant.equals("uncached")));
                    });
                    context.waitTicks(16);
                    if (!workOnly) MetalBenchmarkEnvironment.focus(context);
                    var presentation = workOnly ? null : context.computeOnClient(c -> new MetalBenchmarkEnvironment.Presentation());
                    long[] before = context.computeOnClient(c -> shadowCounts());
                    context.runOnClient(c -> MetalFrameMetrics.beginCapture(8));
                    for (int tick = 0; tick < Integer.getInteger("metalcraft.baselineSampleTicks", 30); tick++) {
                        context.waitTicks(1);
                        if (presentation != null) context.runOnClient(c -> presentation.check());
                    }
                    String name = variant + "#" + repeat;
                    var phase = context.computeOnClient(c -> MetalFrameMetrics.endCapture(name));
                    phases.add(phase);
                    report.put(name + "Scene", context.computeOnClient(c -> java.util.Map.of(
                        "visibleSections", c.levelRenderer.visibleSections().size(),
                        "meshRevision", dev.metalcraft.client.shader.world.ShadowFrameReuse.meshRevision())));
                    if (presentation != null) report.put(name + "Presentation", context.computeOnClient(c -> presentation.describe()));
                    long[] after = context.computeOnClient(c -> shadowCounts());
                    reuse.add(java.util.Map.of("phase", name, "updates", after[0]-before[0], "reused", after[1]-before[1]));
                    System.out.printf("BASELINE_LIGHTING %s fps=%.1f cpu=%.3f gpu=%s acquire=%.3f%n", name,
                        phase.averageFps(), phase.p50CpuMs(), phase.gpuFrame().p50Ms(), phase.p50AcquireMs());
                }
            }
            recording.stop();
            // An actual mesh publication must refresh shadow depth; merely moving the camera
            // must preserve the caster inventory while refusing reuse of its previous maps.
            context.runOnClient(c -> System.setProperty("metalcraft.baselineDisableShadowReuse", "false"));
            context.waitTicks(4);
            long revisionBefore = dev.metalcraft.client.shader.world.ShadowFrameReuse.meshRevision();
            var edit = server.submit(() -> {
                var pos = new net.minecraft.core.BlockPos(4704, 109, 548);
                var blocks = server.overworld();
                var next = blocks.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.STONE)
                    ? net.minecraft.world.level.block.Blocks.DIRT : net.minecraft.world.level.block.Blocks.STONE;
                blocks.setBlockAndUpdate(pos, next.defaultBlockState());
            });
            context.waitFor(c -> edit.isDone()); edit.join();
            context.waitFor(c -> dev.metalcraft.client.shader.world.ShadowFrameReuse.meshRevision() != revisionBefore, 200);
            long[] countsBeforeMove = context.computeOnClient(c -> shadowCounts());
            context.getInput().lookAt(145, 15);
            context.waitTicks(2);
            long[] countsAfterMove = context.computeOnClient(c -> shadowCounts());
            if (countsAfterMove[0] <= countsBeforeMove[0]) throw new AssertionError("Camera movement retained stale shadow maps");
            Path dir = Path.of("diagnostics", "baseline-lighting");
            Files.createDirectories(dir);
            recording.dump(dir.resolve(label + ".jfr"));
            report.put("phases", phases);
            report.put("shadowFrames", reuse);
            Files.writeString(dir.resolve(label + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        finally {
            System.clearProperty("metalcraft.baselineSkipShadows");
            System.clearProperty("metalcraft.baselineSkipResolve");
            System.clearProperty("metalcraft.baselineSkipSky");
            System.clearProperty("metalcraft.baselineSkipGrade");
            System.clearProperty("metalcraft.baselineSkipGbuffer");
            System.clearProperty("metalcraft.baselineScalarShadows");
            System.clearProperty("metalcraft.baselineDisableShadowReuse");
            context.runOnClient(c -> c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE));
            context.waitFor(c -> c.level == null && server.isShutdown(), 1200);
            context.runOnClient(c -> {
                var shadows = ShaderPackRuntime.active().worldShadows();
                if (shadows != null && (shadows.currentFrame() != null || shadows.cachedCasterSections() != 0))
                    throw new AssertionError("World unload retained cached shadow scene: frame=" + shadows.currentFrame()
                        + ", casters=" + shadows.cachedCasterSections());
            });
            context.setScreen(TitleScreen::new);
        }
    }

    private static long[] shadowCounts() {
        var shadows = ShaderPackRuntime.active().worldShadows();
        return shadows == null ? new long[]{0, 0} : new long[]{shadows.updatedFrames(), shadows.reusedFrames()};
    }

    private static void option(ShaderPackRuntime runtime, String id, Object value) {
        if (!runtime.optionValue(id).equals(value)) runtime.setOption(id, value);
    }
}
