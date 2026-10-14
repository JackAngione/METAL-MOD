package dev.metalcraft.client.test;

import static dev.metalcraft.client.test.MetalGameTestSupport.copySaveFiles;
import static dev.metalcraft.client.test.MetalGameTestSupport.press;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.MetalCraftRenderResolution;
import dev.metalcraft.client.gui.MetalCraftOptionsScreen;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.metal.MetalSurfaceProbe;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Short, paired material/toggle captures in a disposable copy of an existing standard save. */
final class MetalReflectionsGameTest {
    static void run(ClientGameTestContext context) {
        String source = System.getProperty("metalcraft.reflectionsTestWorld");
        check(source != null && Path.of(source).getNameCount() == 1
            && !source.equals(".") && !source.equals(".."), "Supply an existing save name");
        String world = copySave(source);
        Map<String, Object> saved = new LinkedHashMap<>();
        String pack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        boolean half = MetalCraftConfig.halfResolution(), unlocked = MetalCraftConfig.unlockedFrameRate();
        boolean lod = MetalCraftConfig.lodEnabled();
        int[] old = context.computeOnClient(c -> new int[]{c.options.renderDistance().get(), c.options.simulationDistance().get(),
            c.getWindow().getScreenWidth(), c.getWindow().getScreenHeight()});
        boolean fullscreen = context.computeOnClient(c -> c.getWindow().isFullscreen());
        boolean bob = context.computeOnClient(c -> c.options.bobView().get());
        boolean hidden = context.computeOnClient(c -> c.gui.hud.isHidden());
        CloudStatus clouds = context.computeOnClient(c -> c.options.cloudStatus().get());
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "default backend is Metal");
                MetalCraftConfig.setHalfResolution(false);
                MetalCraftConfig.setUnlockedFrameRate(true);
                MetalCraftConfig.setLodEnabled(false);
                c.options.renderDistance().set(16);
                c.options.simulationDistance().set(16);
                c.options.bobView().set(false);
                c.options.cloudStatus().set(CloudStatus.OFF);
                if (c.getWindow().isFullscreen()) c.getWindow().toggleFullScreen();
                if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
                var runtime = ShaderPackRuntime.active();
                runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
                for (var option : runtime.options()) saved.put(option.id(), runtime.optionValue(option.id()));
                for (var option : runtime.options()) runtime.setOption(option.id(), option.defaultValue());
                runtime.setOption("wind_enabled", false);
                runtime.setOption("water_wave_strength", 0.0);
                runtime.setOption("water_detail", 0);
                runtime.setOption("water_foam", 0.0);
                runtime.setOption("reflections_enabled", false);
                c.createWorldOpenFlows().openWorld(world, () -> {});
            });
            context.getInput().resizeWindow(3840, 2160);
            context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 1200);
            var fixture = context.computeOnClient(c -> c.getSingleplayerServer().submit(() -> {
                var server = c.getSingleplayerServer();
                var level = server.overworld();
                check(level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator, "standard world required");
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
                var player = server.getPlayerList().getPlayers().getFirst();
                int x = player.blockPosition().getX(), z = player.blockPosition().getZ();
                int y = Math.max(90, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 8);
                command(server, "time set noon");
                command(server, "weather clear");
                command(server, "gamemode spectator @a");
                for (int dx = -12; dx <= 12; dx++) for (int dz = 3; dz <= 22; dz++) {
                    level.setBlock(new BlockPos(x + dx, y - 1, z + dz), Blocks.STONE.defaultBlockState(), 3);
                    var material = dz >= 4 && dz <= 20
                        ? dx >= -10 && dx <= -4 ? Blocks.WATER
                        : dx >= -2 && dx <= 4 ? Blocks.GLASS
                        : dx >= 6 && dx <= 10 ? Blocks.ICE : Blocks.STONE : Blocks.STONE;
                    level.setBlock(new BlockPos(x + dx, y, z + dz), material.defaultBlockState(), 3);
                }
                for (int dx = -12; dx <= 12; dx++) for (int dy = 1; dy <= 9; dy++) {
                    var material = Math.floorMod(dx, 6) < 3 ? Blocks.CONCRETE.red() : Blocks.CONCRETE.white();
                    level.setBlock(new BlockPos(x + dx, y + dy, z + 22), material.defaultBlockState(), 3);
                }
                command(server, "tp @a " + (x + 0.5) + " " + (y + 3) + " " + (z + 0.5) + " 0 14");
                return new BlockPos(x, y, z);
            }));
            context.waitFor(c -> fixture.isDone());
            BlockPos origin = fixture.join();
            context.runOnClient(c -> c.options.broadcastOptions());
            context.getInput().lookAt(0, 14);
            for (int tick = 0; tick < 100; tick++) {
                context.runOnClient(c -> c.getSingleplayerServer().execute(() -> c.getSingleplayerServer().getPlayerList()
                    .getPlayers().forEach(p -> p.connection.chunkSender.onChunkBatchReceivedByClient(PlayerChunkSender.MAX_CHUNKS_PER_TICK))));
                context.waitTick();
            }
            context.waitFor(c -> c.levelRenderer.sectionRenderDispatcher().isQueueEmpty(), 800);
            context.waitTicks(30);
            context.runOnClient(c -> {
                dimensions();
                check(MetalGpuDevices.current().lastWorldHadOpaqueWaterInputs(), "reflection scene snapshots are available");
            });
            Path off = capture(context, "off");
            sample(context, "reflections-off-4k");
            context.setScreen(() -> new MetalCraftOptionsScreen(null));
            press(context, Component.translatable("metalcraft.options.shaders.title").getString());
            press(context, "Reflections");
            press(context, "Reflections:");
            context.runOnClient(c -> check(Boolean.TRUE.equals(ShaderPackRuntime.active().optionValue("reflections_enabled")), "UI toggle enables reflections"));
            context.takeScreenshot("metalcraft-reflections-menu");
            context.runOnClient(c -> c.gui.setScreen(null));
            Path on = capture(context, "on");
            sample(context, "reflections-on-4k");
            for (int lane = 0; lane < 3; lane++) {
                int dx = new int[]{-7, 1, 8}[lane];
                int[] pixel = project(context, origin.offset(dx, 1, 12));
                int changed = changed(off, on, pixel);
                check(changed > 100, "reflection visible on " + new String[]{"water", "glass", "ice"}[lane] + ": " + changed);
            }
            set(context, "water_reflection_quality", "baseline");
            Path sky = capture(context, "sky-only");
            check(changed(sky, on, project(context, origin.offset(1, 1, 12))) > 100, "visible scenery appears in glass reflection");
            set(context, "water_reflection_quality", "ssr_low");
            set(context, "water_enabled", false);
            Path independent = capture(context, "water-effects-off");
            set(context, "reflections_enabled", false);
            Path bothOff = capture(context, "all-off");
            check(changed(independent, bothOff, project(context, origin.offset(1, 1, 12))) > 100,
                "glass reflections work independently of water effects");
            set(context, "water_enabled", true);
            Path restored = capture(context, "restored-off");
            check(changed(off, restored, project(context, origin.offset(1, 1, 12))) < 20, "toggle restores glass baseline");
            System.out.println("Reflection integration passed: water/glass/ice, scenery hit, UI toggle, independent water setting, restored baseline.");
        } finally {
            context.runOnClient(c -> {
                MetalFrameMetrics.discardCapture();
                c.gui.setScreen(null);
                saved.forEach(ShaderPackRuntime.active()::setOption);
                ShaderPackRuntime.active().selectPack(pack);
                MetalCraftConfig.setHalfResolution(half);
                MetalCraftConfig.setUnlockedFrameRate(unlocked);
                MetalCraftConfig.setLodEnabled(lod);
                c.options.renderDistance().set(old[0]);
                c.options.simulationDistance().set(old[1]);
                c.options.bobView().set(bob);
                c.options.cloudStatus().set(clouds);
                if (c.gui.hud.isHidden() != hidden) c.gui.hud.toggle();
                if (c.level != null) c.level.disconnect(Component.literal("Reflection test complete"));
                c.disconnect(new TitleScreen(), false);
            });
            context.waitFor(c -> !net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl.isServerRunning && c.level == null, 1200);
            context.getInput().resizeWindow(old[2], old[3]);
            context.runOnClient(c -> {
                if (c.getWindow().isFullscreen() != fullscreen) c.getWindow().toggleFullScreen();
                MetalCraftRenderResolution.apply(c);
            });
            context.setScreen(TitleScreen::new);
        }
    }

    private static void command(net.minecraft.server.MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
    }
    private static void set(ClientGameTestContext context, String id, Object value) {
        context.runOnClient(c -> ShaderPackRuntime.active().setOption(id, value));
    }
    private static Path capture(ClientGameTestContext context, String name) {
        context.waitTicks(12);
        context.runOnClient(c -> { dimensions(); c.gui.hud.getChat().clearMessages(true); });
        return context.takeScreenshot("metalcraft-reflections-" + name);
    }
    private static void dimensions() {
        var c = net.minecraft.client.Minecraft.getInstance();
        var target = c.gameRenderer.mainRenderTarget().getColorTextureView();
        var runtime = ShaderPackRuntime.active();
        int[] drawable = MetalSurfaceProbe.drawableSize();
        check(target.getWidth(0) == 3840 && target.getHeight(0) == 2160, "actual world target is 4K");
        check(runtime.frameWidth() == 3840 && runtime.frameHeight() == 2160, "shader scene is 4K");
        check(c.options.renderDistance().get() == 16 && c.options.simulationDistance().get() == 16, "distances 16/16");
        System.out.println("Reflections: worldTarget=3840x2160, shaderScene=3840x2160, presentationFramebuffer="
            + c.getWindow().getWidth() + "x" + c.getWindow().getHeight() + ", drawable=" + drawable[0] + "x" + drawable[1]);
    }
    private static void sample(ClientGameTestContext context, String name) {
        context.runOnClient(c -> MetalFrameMetrics.beginCapture(8));
        context.waitTicks(30);
        var phase = context.computeOnClient(c -> MetalFrameMetrics.endCapture(name));
        check(phase.frames() > 0, "timing sample recorded");
        System.out.println("Reflection timing smoke: " + phase.toLogLine() + " gpuMedianMs=" + phase.gpuFrame().p50Ms());
    }
    private static int[] project(ClientGameTestContext context, BlockPos pos) {
        return context.computeOnClient(c -> {
            var camera = c.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
            var matrix = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);
            var clip = matrix.transform(new Vector4f((float)(pos.getX() + 0.5 - camera.pos.x),
                (float)(pos.getY() - camera.pos.y), (float)(pos.getZ() + 0.5 - camera.pos.z), 1));
            int px = Math.round((clip.x / clip.w * 0.5F + 0.5F) * 3840);
            int py = Math.round((0.5F - clip.y / clip.w * 0.5F) * 2160);
            check(px > 80 && px < 3760 && py > 60 && py < 2100, "material sample visible: " + px + "," + py);
            return new int[]{px, py};
        });
    }
    private static int changed(Path before, Path after, int[] pixel) {
        try (var a = NativeImage.read(Files.newInputStream(before)); var b = NativeImage.read(Files.newInputStream(after))) {
            int count = 0;
            for (int y = pixel[1] - 40; y < pixel[1] + 40; y++) for (int x = pixel[0] - 60; x < pixel[0] + 60; x++) {
                int ca = a.getPixel(x, y), cb = b.getPixel(x, y);
                for (int shift : new int[]{0, 8, 16}) if (Math.abs(((ca >> shift) & 255) - ((cb >> shift) & 255)) > 3) { count++; break; }
            }
            System.out.println("Reflection changed pixels at " + pixel[0] + "," + pixel[1] + ": " + count);
            return count;
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
    private static String copySave(String source) {
        Path saves = Path.of("saves").toAbsolutePath().normalize();
        Path input = saves.resolve(source);
        check(Files.isRegularFile(input.resolve("level.dat")), "existing save found");
        String name = "Reflection Test " + java.util.UUID.randomUUID().toString().substring(0, 8);
        Path output = saves.resolve(name);
        try { copySaveFiles(input, output); }
        catch (java.io.IOException error) { throw new AssertionError(error); }
        return name;
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
