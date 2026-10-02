package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.lod.LodSystem;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** One short existing-world route for core backend changes, shaders, and 128-chunk LOD. */
final class MetalCoreIntegrationGameTest {
    static void run(ClientGameTestContext context) {
        String world = System.getProperty("metalcraft.coreTestWorld", "New World (4)");
        context.runOnClient(client -> {
            check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "default backend is Metal");
            check(client.getLevelSource().levelExists(world), "existing standard save is available: " + world);
            try (var access = client.getLevelSource().validateAndCreateAccess(world)) { }
            catch (Exception error) { throw new AssertionError("Test save is unavailable", error); }
            MetalCraftConfig.setHalfResolution(false);
            MetalCraftConfig.setLodEnabled(false);
            client.options.renderDistance().set(16);
            client.options.simulationDistance().set(16);
            ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
            client.createWorldOpenFlows().openWorld(world, () -> { });
        });
        context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 1200);
        var server = context.computeOnClient(c -> c.getSingleplayerServer());
        check(server.overworld().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator, "standard terrain, not flat");
        try {
            context.runOnClient(c -> c.options.broadcastOptions());
            context.getInput().lookAt(135, 35);
            awaitNativeTerrain(context);
            context.waitTicks(10);
            capture(context, "core-none-16");

            context.runOnClient(c -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID));
            context.waitTicks(10);
            check(context.computeOnClient(c -> ShaderPackRuntime.active().isActive()), "Standard shader activation");
            context.getInput().resizeWindow(1280, 720);
            context.waitFor(c -> c.getWindow().getWidth() == 1280 && c.getWindow().getHeight() == 720, 400);
            boolean fullscreen = context.computeOnClient(c -> c.getWindow().isFullscreen());
            context.runOnClient(c -> c.getWindow().toggleFullScreen());
            context.waitFor(c -> c.getWindow().isFullscreen() != fullscreen, 400);
            context.waitTicks(3);
            context.runOnClient(c -> c.getWindow().toggleFullScreen());
            context.waitFor(c -> c.getWindow().isFullscreen() == fullscreen, 400);
            context.waitTicks(3);
            capture(context, "core-standard-16-resized");

            var reload = context.computeOnClient(c -> c.reloadResourcePacks());
            context.waitFor(c -> reload.isDone() && c.gui.overlay() == null, 1200);
            reload.join();
            context.waitTicks(10);
            check(context.computeOnClient(c -> ShaderPackRuntime.active().isActive()), "Standard survives resource reload");
            capture(context, "core-standard-16-reloaded");

            context.runOnClient(c -> {
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
                MetalCraftConfig.setLodNativeDistance(16);
                MetalCraftConfig.setLodDetail(5);
                MetalCraftConfig.setLodEnabled(true);
                c.options.renderDistance().set(128);
                c.options.broadcastOptions();
            });
            context.getInput().lookAt(135, 12);
            context.waitFor(c -> LodSystem.active() && LodSystem.stats().drawnNodes > 0, 1200);
            context.waitTicks(10);
            capture(context, "core-lod-128");
            context.runOnClient(c -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID));
            context.waitFor(c -> ShaderPackRuntime.active().isActive() && LodSystem.stats().drawnNodes > 0, 600);
            context.waitTicks(10);
            capture(context, "core-lod-128-standard");

            context.runOnClient(c -> {
                MetalCraftConfig.setLodEnabled(false);
                c.options.renderDistance().set(16);
                c.options.broadcastOptions();
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
            });
            context.getInput().lookAt(135, 35);
            awaitNativeTerrain(context);
            context.waitTicks(10);
            check(!LodSystem.active(), "LOD disables cleanly");
            capture(context, "core-restored-16");
            System.out.println("Core Metal integration passed: existing NORMAL save, 16/16 core and Standard, resize/fullscreen, reload, async screenshots, 128-chunk LOD with/without Standard");
        } finally {
            context.runOnClient(c -> c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE));
            context.waitFor(c -> c.level == null && server.isShutdown(), 1200);
            context.setScreen(TitleScreen::new);
        }
    }

    private static void capture(ClientGameTestContext context, String name) {
        var path = context.takeScreenshot(name);
        try (var input = Files.newInputStream(path); var image = NativeImage.read(input)) {
            int first = image.getPixel(0, 0);
            boolean varies = false;
            int detailedPixels = 0;
            for (int y = 0; y < image.getHeight(); y += 16) for (int x = 0; x < image.getWidth(); x += 16)
                varies |= image.getPixel(x, y) != first;
            check(varies, "nonempty rendered screenshot: " + name);
            for (int y = 0; y < image.getHeight(); y += 4) for (int x = 0; x + 1 < image.getWidth(); x += 4) {
                int a = image.getPixel(x, y), b = image.getPixel(x + 1, y);
                int difference = Math.abs((a & 255) - (b & 255)) + Math.abs((a >> 8 & 255) - (b >> 8 & 255))
                    + Math.abs((a >> 16 & 255) - (b >> 16 & 255));
                if (difference > 24) detailedPixels++;
            }
            check(detailedPixels >= 100, "terrain detail present, not only a sky gradient: " + name);
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }

    private static void awaitNativeTerrain(ClientGameTestContext context) {
        for (int tick = 0; tick < 600; tick++) {
            // Fabric's tick handoff otherwise depresses the wall-clock chunk delivery estimate.
            context.runOnClient(c -> c.getSingleplayerServer().execute(() ->
                c.getSingleplayerServer().getPlayerList().getPlayers().forEach(player ->
                    player.connection.chunkSender.onChunkBatchReceivedByClient(
                        net.minecraft.server.network.PlayerChunkSender.MAX_CHUNKS_PER_TICK))));
            context.waitTicks(1);
            long sections = context.computeOnClient(c -> c.levelRenderer.visibleSections().stream()
                .filter(section -> section.getSectionMesh().getSectionDraw(
                    net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID) != null).count());
            if (sections >= 64) return;
        }
        throw new AssertionError("Native terrain did not finish loading enough visible solid sections");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
