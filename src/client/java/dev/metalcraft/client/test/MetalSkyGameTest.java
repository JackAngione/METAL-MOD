package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.NativeImage;
import dev.metalcraft.client.metal.MetalLinearWorldActivation;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.gamerules.GameRules;

/** Short NORMAL-world sky sweep: real camera, custom celestial models, clouds, reload and resize. */
final class MetalSkyGameTest {
    static void run(ClientGameTestContext context) {
        String savedPack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        CloudStatus savedClouds = context.computeOnClient(c -> c.options.cloudStatus().get());
        boolean savedTransparency = context.computeOnClient(c -> c.options.improvedTransparency().get());
        boolean savedBobbing = context.computeOnClient(c -> c.options.bobView().get());
        var builder = context.worldBuilder().adjustSettings(settings -> {
            var normal = settings.getSettings().worldgenLoadContext()
                .lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
                .getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
            settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
            settings.setSeed("12345");
            settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
            settings.setAllowCommands(true);
            settings.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
            settings.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
        });
        try (var world = builder.create()) {
            context.waitFor(c -> c.level != null && c.player != null);
            context.runOnClient(c -> {
                c.options.renderDistance().set(16);
                c.options.simulationDistance().set(16);
                c.options.cloudStatus().set(CloudStatus.FANCY);
                c.options.improvedTransparency().set(false);
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID);
            });
            world.getServer().runCommand("gamemode spectator @a");
            world.getServer().runCommand("tp @a 12 125 16 90 -12");
            world.getServer().runCommand("weather clear");
            world.getServer().runCommand("time set 6000");
            context.getInput().lookAt(90, -12);
            context.waitFor(c -> c.levelRenderer.visibleSections().stream().filter(section -> {
                var mesh = section.getSectionMesh();
                return mesh.getSectionDraw(net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID) != null;
            }).count() >= 64, 1200);
            context.waitTicks(10);
            if (Boolean.getBoolean("metalcraft.skyMotionTest")) {
                // A short walkway in the generated NORMAL world isolates real walking
                // bob from jumping/falling. It is not a flat-world test preset.
                world.getServer().runCommand("fill -16 123 14 16 123 18 stone");
                world.getServer().runCommand("gamemode survival @a");
                world.getServer().runCommand("tp @a 12 124 16 90 -12");
                world.getServer().runCommand("time set 11000");
                checkWalking(context);
                return;
            }
            Path day = capture(context, "day");
            context.runOnClient(c -> c.options.cloudStatus().set(CloudStatus.OFF));
            Path off = capture(context, "clouds-off");
            assertSkyDifference(day, off);
            context.runOnClient(c -> c.options.cloudStatus().set(CloudStatus.FAST));
            capture(context, "fast");
            context.runOnClient(c -> c.options.cloudStatus().set(CloudStatus.FANCY));
            world.getServer().runCommand("time set 11000");
            capture(context, "sun");
            world.getServer().runCommand("time set 12500");
            capture(context, "sunset");
            world.getServer().runCommand("time set 23500");
            context.getInput().lookAt(-90, -12);
            capture(context, "sunrise");
            world.getServer().runCommand("time set 18000");
            capture(context, "night");
            // Two short lunar views in this same generated world; all eight phases
            // are covered by the GPU fixtures without extending the live sweep.
            context.runOnClient(c -> c.options.cloudStatus().set(CloudStatus.OFF));
            context.getInput().lookAt(-90, -88);
            capture(context, "moon-full");
            world.getServer().runCommand("time set 66000");
            capture(context, "moon-quarter");
            context.runOnClient(c -> c.options.cloudStatus().set(CloudStatus.FANCY));
            world.getServer().runCommand("time set 6000");
            context.getInput().lookAt(-90, -12);
            world.getServer().runCommand("weather rain");
            world.getServer().runOnServer(server -> server.overworld().setRainLevel(1));
            context.runOnClient(c -> c.level.setRainLevel(1));
            capture(context, "rain");
            world.getServer().runCommand("weather clear");
            world.getServer().runOnServer(server -> server.overworld().setRainLevel(0));
            context.runOnClient(c -> c.level.setRainLevel(0));
            var reload = context.computeOnClient(c -> c.reloadResourcePacks());
            context.waitFor(c -> reload.isDone(), 1200);
            if (reload.isCompletedExceptionally()) throw new AssertionError("Sky resource reload failed");
            context.waitFor(c -> c.gui.overlay() == null);
            context.getInput().resizeWindow(1280, 720);
            capture(context, "reloaded-resized");
            context.runOnClient(c -> {
                c.getGpuWarnlistManager().dismissWarning();
                c.options.improvedTransparency().set(true);
            });
            context.waitFor(c -> MetalLinearWorldActivation.lastLiveFabulous());
            capture(context, "fabulous");
            context.runOnClient(c -> {
                var renderer = ShaderPackRuntime.active().worldSky();
                int width = c.gameRenderer.mainRenderTarget().getColorTextureView().getWidth(0);
                if (renderer.cloudWidth() != (width + 1) / 2) throw new AssertionError("Cloud target did not follow scene size");
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
                if (ShaderPackRuntime.active().worldSky() != null) throw new AssertionError("Sky resources survived pack disable");
            });
            context.waitTicks(5);
            context.takeScreenshot("metalcraft-sky-none");
            context.runOnClient(c -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID));
            capture(context, "restored");
            System.out.println("Standard sky live passed: NORMAL world, Metal, 16/16, day/sunrise/sunset/night/rain, custom sun/moon and lunar phases, cloud modes, resource reload, resize, Fabulous, pack restoration");
        } finally {
            context.runOnClient(c -> {
                c.options.cloudStatus().set(savedClouds);
                c.options.improvedTransparency().set(savedTransparency);
                c.options.bobView().set(savedBobbing);
                ShaderPackRuntime.active().selectPack(savedPack);
            });
        }
    }

    private static void checkWalking(ClientGameTestContext context) {
        context.runOnClient(c -> {
            c.options.bobView().set(true);
            c.options.cloudStatus().set(CloudStatus.OFF);
        });
        context.getInput().lookAt(90, -12);
        context.waitFor(c -> c.player.onGround());
        Path standing = capture(context, "walk-standing");
        double[] anchor = sunCenter(standing);
        double startX = context.computeOnClient(c -> c.player.getX());
        double maxShift = 0;
        float maxBob = 0;
        context.getInput().holdKey(options -> options.keyUp);
        try {
            for (int sample = 0; sample < 4; sample++) {
                Path walking = capture(context, "walk-" + sample);
                double[] center = sunCenter(walking);
                double shift = Math.hypot(center[0] - anchor[0], center[1] - anchor[1]);
                maxShift = Math.max(maxShift, shift);
                maxBob = Math.max(maxBob, context.computeOnClient(c -> c.gameRenderer.gameRenderState()
                    .levelRenderState.cameraRenderState.entityRenderState.bob));
                // Normal view-bob rotation is sub-degree. Near-plane translation
                // used to send the sun tens/hundreds of pixels away or out of view.
                if (shift > anchor[2] / 60.0) throw new AssertionError("Walking moved the sun by " + shift + " pixels");
            }
        } finally { context.getInput().releaseKey(options -> options.keyUp); }
        double distance = Math.abs(context.computeOnClient(c -> c.player.getX()) - startX);
        if (distance < 2 || maxBob < 0.02F) throw new AssertionError("Walking test did not exercise view bob: distance=" + distance + ", bob=" + maxBob);
        context.runOnClient(c -> c.options.cloudStatus().set(CloudStatus.FANCY));
        capture(context, "walk-clouds");
        System.out.println("Standard sky walking live passed: NORMAL world, Metal, 16/16, view bob enabled; distance="
            + distance + ", max bob=" + maxBob + ", max solar shift=" + maxShift + " pixels");
    }

    private static double[] sunCenter(Path path) {
        try (NativeImage image = NativeImage.read(Files.newInputStream(path))) {
            double xSum = 0, ySum = 0;
            int count = 0;
            for (int y = 0; y < image.getHeight() * 2 / 3; y++) for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getPixel(x, y);
                if ((rgb & 255) < 240 || ((rgb >>> 8) & 255) < 240 || ((rgb >>> 16) & 255) < 240) continue;
                xSum += x; ySum += y; count++;
            }
            if (count < 12) throw new AssertionError("Sun left the walking test view: " + path);
            return new double[]{xSum / count, ySum / count, image.getHeight()};
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private static Path capture(ClientGameTestContext context, String label) {
        context.waitTicks(8);
        context.runOnClient(c -> {
            var runtime = ShaderPackRuntime.active();
            if (!runtime.isActive() || runtime.lastError().isPresent() || runtime.worldSky() == null
                || runtime.worldSky().renderedFrames() < 1 || !MetalLinearWorldActivation.lastLiveUsedHdr()) {
                throw new AssertionError("Standard sky inactive at " + label + ": " + runtime.lastError());
            }
            c.gui.hud.getChat().clearMessages(true);
        });
        Path image = context.takeScreenshot("metalcraft-sky-" + label);
        if (!label.equals("night") && !label.startsWith("moon-")) assertVisibleSky(image);
        return image;
    }

    private static void assertVisibleSky(Path path) {
        try (NativeImage image = NativeImage.read(Files.newInputStream(path))) {
            long lit = 0;
            for (int y = 0; y < image.getHeight() / 3; y++) for (int x = 0; x < image.getWidth(); x++) {
                int color = image.getPixel(x, y);
                if (((color & 255) + ((color >>> 8) & 255) + ((color >>> 16) & 255)) > 90) lit++;
            }
            if (lit < image.getWidth() * image.getHeight() / 20) throw new AssertionError("Day/twilight sky is black: " + path);
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }

    private static void assertSkyDifference(Path first, Path second) {
        try (NativeImage a = NativeImage.read(Files.newInputStream(first));
             NativeImage b = NativeImage.read(Files.newInputStream(second))) {
            long different = 0;
            for (int y = 0; y < a.getHeight() / 2; y++) for (int x = 0; x < a.getWidth(); x++) {
                int av = a.getPixel(x, y), bv = b.getPixel(x, y);
                if (Math.abs((av & 255) - (bv & 255)) > 12
                    || Math.abs(((av >>> 8) & 255) - ((bv >>> 8) & 255)) > 12) different++;
            }
            if (different < a.getWidth() * a.getHeight() / 100) throw new AssertionError("Cloud toggle did not change visible sky");
            System.out.println("Visible cloud toggle changed " + different + " upper-frame pixels");
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
}
