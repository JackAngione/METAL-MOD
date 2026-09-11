package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.lod.LodCompilerCapture;
import dev.metalcraft.client.lod.LodLoadedRenderer;
import dev.metalcraft.client.lod.LodSettings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;

/** Live ownership checks on NORMAL terrain, keeping render/simulation at 16 and Default/Metal. */
final class MetalLodRenderGameTest {
    static void run(ClientGameTestContext context) {
        var saved = MetalCraftConfig.lod();
        String pack = context.computeOnClient(client -> ShaderPackRuntime.active().selectedPackId());
        int savedFov = context.computeOnClient(client -> client.options.fov().get());
        boolean savedHud = context.computeOnClient(client -> client.gui.hud.isHidden());
        boolean savedHalf = MetalCraftConfig.halfResolution();
        var reports = new LinkedHashMap<String, Object>();
        var builder = context.worldBuilder().adjustSettings(settings -> {
            var normal = settings.getSettings().worldgenLoadContext()
                    .lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                    .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
            settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
            settings.setSeed("metalcraft");
            settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            settings.setAllowCommands(true);
        });
        try {
            context.runOnClient(client -> MetalCraftConfig.setLod(LodSettings.defaults().withGeometry(2, 2).withEnabled(true)));
            net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave save;
            try (var world = builder.create()) {
                save = world.getWorldSave();
                context.waitFor(client -> client.level != null && client.player != null);
                world.getServer().runCommand("gamemode spectator @a");
                world.getServer().runCommand("tp @a -1535.5 236 -127.5 22.5 35");
                context.getInput().lookAt(22.5F, 35);
                world.getServer().runCommand("time set noon");
                world.getServer().runCommand("weather clear");
                context.runOnClient(client -> { if (!client.gui.hud.isHidden()) client.gui.hud.toggle(); });
                for (String selected : new String[]{ShaderPackRuntime.BUILTIN_ID, ShaderPackRuntime.NONE_ID}) {
                    context.runOnClient(client -> ShaderPackRuntime.active().selectPack(selected));
                    long previous = LodLoadedRenderer.stats().draws();
                    context.waitFor(client -> LodLoadedRenderer.stats().draws() > previous + 100, 2400);
                    context.waitTicks(100);
                    var stats = LodLoadedRenderer.stats();
                    if (stats.uploadFailures() != 0 || stats.originalTriangles() <= stats.replacementTriangles()
                            || stats.frameUploadBytes() > (1L << 20) || stats.chargedBytes() > (512L << 20))
                        throw new AssertionError("Invalid live LOD ownership/budgets: " + stats);
                    reports.put(selected, stats);
                    context.takeScreenshot("metalcraft-lod-live-" + selected);
                    context.runOnClient(client -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withEnabled(false)));
                    context.waitTicks(20);
                    long disabled = LodLoadedRenderer.stats().draws();
                    long disabledCaptures = LodCompilerCapture.stats().sections();
                    context.waitTicks(20);
                    if (LodLoadedRenderer.stats().draws() != disabled) throw new AssertionError("Disabled LOD still owns draws");
                    if (LodCompilerCapture.stats().sections() != disabledCaptures || LodCompilerCapture.stats().retainedBytes() != 0)
                        throw new AssertionError("Disabled LOD retained capture work");
                    context.takeScreenshot("metalcraft-lod-off-" + selected);
                    context.runOnClient(client -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withEnabled(true)));
                    for (int step = 0; step < 3; step++) {
                        world.getServer().runCommand("tp @a " + (-1535.5 + step * 24) + " 236 " + (-127.5 + step * 12)
                                + " " + (22.5 + step * 45) + " 35");
                        context.getInput().lookAt(22.5F + step * 45, 35);
                        int fov = step == 1 ? 30 : 70;
                        context.runOnClient(client -> client.options.fov().set(fov));
                        context.waitTicks(40);
                        context.takeScreenshot("metalcraft-lod-route-" + selected + "-" + step);
                        reports.put(selected + "-route-" + step, LodLoadedRenderer.stats());
                        reports.put(selected + "-camera-" + step, context.computeOnClient(client -> MetalBenchmarkEnvironment.camera()));
                    }
                    // A nearest section must regain ordinary detail immediately after approaching it.
                    context.runOnClient(client -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withGeometry(12, 2)));
                    context.waitTicks(20);
                    reports.put(selected + "-radius12", LodLoadedRenderer.stats());
                    context.runOnClient(client -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withGeometry(2, 2)));
                    world.getServer().runCommand("tp @a -1535.5 236 -127.5 22.5 35");
                    context.getInput().lookAt(22.5F, 35);
                }
                reports.put("tiers", LodLoadedRenderer.stats());
                if (LodLoadedRenderer.stats().tier1Draws() == 0 || LodLoadedRenderer.stats().tier2Draws() == 0)
                    throw new AssertionError("Route did not exercise both fine and coarse loaded neighbors");
                context.getInput().holdKey(options -> options.keyUp);
                try {
                    for (int tick = 0; tick < 80; tick++) {
                        context.getInput().lookAt(22.5F + tick * 2, 35);
                        context.waitTick();
                    }
                } finally { context.getInput().releaseKey(options -> options.keyUp); }
                context.takeScreenshot("metalcraft-lod-flight");
                reports.put("afterFlight", LodLoadedRenderer.stats());
                context.getInput().resizeWindow(1279, 719);
                context.waitTicks(20);
                context.takeScreenshot("metalcraft-lod-odd-resize");
                context.runOnClient(client -> MetalCraftConfig.setHalfResolution(true));
                context.waitTicks(20);
                context.takeScreenshot("metalcraft-lod-half");
                context.runOnClient(client -> MetalCraftConfig.setHalfResolution(false));
                context.waitTicks(20);
                boolean fullscreen = context.computeOnClient(client -> client.getWindow().isFullscreen());
                context.runOnClient(client -> client.getWindow().toggleFullScreen());
                context.waitFor(client -> client.getWindow().isFullscreen() != fullscreen);
                context.waitTicks(10);
                context.runOnClient(client -> client.getWindow().toggleFullScreen());
                context.waitFor(client -> client.getWindow().isFullscreen() == fullscreen);
                context.waitTicks(10);
                world.getServer().runCommand("tp @a -1535.5 236 -127.5 22.5 35");
                context.getInput().lookAt(22.5F, 35);
                long editedSection = net.minecraft.core.SectionPos.asLong(-97, 10, -8);
                LodCompilerCapture.watch(editedSection);
                world.getServer().runCommand("setblock -1540 175 -128 minecraft:stone");
                context.waitFor(client -> LodCompilerCapture.watchedCompiles() > 0, 2400);
                var ticket = LodCompilerCapture.watchedTicket();
                if (ticket == null) throw new AssertionError("No live edit identity");
                world.getServer().runCommand("setblock -1540 175 -128 minecraft:deepslate");
                context.waitFor(client -> !ticket.current(), 2400);
                long previous = LodLoadedRenderer.stats().draws();
                var reload = context.computeOnClient(client -> client.reloadResourcePacks());
                context.waitFor(client -> reload.isDone(), 2400);
                if (reload.isCompletedExceptionally()) throw new AssertionError("LOD reload failed");
                context.waitFor(client -> LodLoadedRenderer.stats().draws() > previous + 100, 2400);
                world.getServer().runCommand("tp @a 4096 173 4096 22.5 30");
                context.waitTicks(100);
                reports.put("afterReloadTeleport", LodLoadedRenderer.stats());
                reports.put("capture", LodCompilerCapture.stats());
                context.runOnClient(client -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID));
                world.getServer().runCommand("execute as @a in minecraft:the_nether run tp @s 0 80 0 22.5 30");
                context.waitFor(client -> client.level.dimension() == net.minecraft.world.level.Level.NETHER, 2400);
                context.getInput().lookAt(22.5F, 30);
                context.waitTicks(80);
                context.takeScreenshot("metalcraft-lod-nether");
                world.getServer().runCommand("execute as @a in minecraft:overworld run tp @s -1535.5 236 -127.5 22.5 35");
                context.waitFor(client -> client.level.dimension() == net.minecraft.world.level.Level.OVERWORLD, 2400);
                context.getInput().lookAt(22.5F, 35);
                long beforeReturn = LodLoadedRenderer.stats().draws();
                context.waitFor(client -> LodLoadedRenderer.stats().draws() > beforeReturn + 100, 2400);
                reports.put("afterDimensionReturn", LodLoadedRenderer.stats());
            }
            context.waitFor(client -> LodCompilerCapture.stats().retainedBytes() == 0
                    && LodLoadedRenderer.stats().chargedBytes() == 0, 2400);
            reports.put("afterClose", LodLoadedRenderer.stats());
            long beforeReopen = LodLoadedRenderer.stats().draws();
            try (var reopened = save.open()) {
                context.waitFor(client -> client.level != null && client.player != null, 2400);
                context.getInput().lookAt(22.5F, 35);
                context.waitFor(client -> LodLoadedRenderer.stats().draws() > beforeReopen + 100, 2400);
                context.takeScreenshot("metalcraft-lod-reopened");
                reports.put("afterReopen", LodLoadedRenderer.stats());
            }
            context.waitFor(client -> LodCompilerCapture.stats().retainedBytes() == 0
                    && LodLoadedRenderer.stats().chargedBytes() == 0, 2400);
            reports.put("afterReopenClose", LodLoadedRenderer.stats());
            Path report = Path.of("build", "lod-live-render.json");
            try {
                Files.createDirectories(report.getParent());
                Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(reports));
            } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            System.out.println("LOD live render passed: " + reports);
        } finally {
            context.runOnClient(client -> {
                MetalCraftConfig.setLod(saved);
                MetalCraftConfig.setHalfResolution(savedHalf);
                ShaderPackRuntime.active().selectPack(pack);
                client.options.fov().set(savedFov);
                if (client.gui.hud.isHidden() != savedHud) client.gui.hud.toggle();
            });
        }
    }
}
