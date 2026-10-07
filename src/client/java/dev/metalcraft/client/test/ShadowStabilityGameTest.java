package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.metal.MetalSurfaceProbe;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Walks and turns at a cliff in a copy of an existing standard save, tracking sun visibility on
 * fixed block faces. Faces edge-on to or turned from the afternoon sun once took their shade from
 * the cascade texel grid, so it shifted whenever the camera carried a cascade split across them.
 */
final class ShadowStabilityGameTest {
    private static final int GRID = 12;
    /** Up to this fraction of tracked faces may change: real shadow edges re-filter between cascades. */
    private static final double MAX_CHANGED = 0.025;

    private record Receiver(Vec3 point, BlockPos block, Direction face, float visibility) { }
    private record Pose(double x, double y, double z, float yaw, float pitch) { }
    private record Stats(int changed, int compared, java.util.Map<Direction, Integer> faces) {
        double fraction() { return compared == 0 ? 1 : changed / (double)compared; }
    }

    static void run(ClientGameTestContext context) {
        String source = System.getProperty("metalcraft.shadowStabilityWorld", "New World (5)");
        // The cliff and afternoon sun of the recorded reproduction: east faces turn from the sun, south faces are edge-on.
        Pose start = new Pose(344.886, 89.494, -9.567, 118.95F, -4.35F);
        String world = copySave(source);
        String pack = context.computeOnClient(c -> ShaderPackRuntime.active().selectedPackId());
        boolean bob = context.computeOnClient(c -> c.options.bobView().get());
        boolean hud = context.computeOnClient(c -> c.gui.hud.isHidden());
        CloudStatus clouds = context.computeOnClient(c -> c.options.cloudStatus().get());
        context.runOnClient(c -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID));
        Object wind = context.computeOnClient(c -> ShaderPackRuntime.active().optionValue("wind_enabled"));
        Object debug = context.computeOnClient(c -> ShaderPackRuntime.active().optionValue("debug_view"));
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "Default selected Metal");
                MetalCraftConfig.setLodEnabled(false);
                MetalCraftConfig.setHalfResolution(false);
                c.options.renderDistance().set(16);
                c.options.simulationDistance().set(16);
                c.options.cloudStatus().set(CloudStatus.OFF);
                c.options.bobView().set(false);
                if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
                // Foliage motion moves real shadows; this check isolates camera motion.
                ShaderPackRuntime.active().setOption("wind_enabled", false);
                ShaderPackRuntime.active().setOption("debug_view", "visibility");
                c.createWorldOpenFlows().openWorld(world, () -> { });
            });
            context.getInput().resizeWindow(3840, 2160);
            context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 1200);
            server(context, s -> {
                check(s.overworld().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator, "standard world required");
                s.getGameRules().set(GameRules.ADVANCE_TIME, false, s);
                s.getGameRules().set(GameRules.ADVANCE_WEATHER, false, s);
                s.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, s);
                s.getGameRules().set(GameRules.SPAWN_MOBS, false, s);
            });
            command(context, "gamemode spectator @a");
            command(context, "weather clear");
            command(context, "time set 8027");
            teleport(context, start);
            context.waitTicks(200);
            context.runOnClient(c -> {
                var target = c.gameRenderer.mainRenderTarget().getColorTextureView();
                int[] drawable = MetalSurfaceProbe.drawableSize();
                System.out.println("Shadow stability capture: worldTarget=" + target.getWidth(0) + "x" + target.getHeight(0)
                    + ", drawable=" + drawable[0] + "x" + drawable[1]);
                check(target.getWidth(0) == 3840 && target.getHeight(0) == 2160, "4K world render target");
            });

            Path basePath = capture(context, "base");
            List<Receiver> receivers = context.computeOnClient(c -> collect(c, basePath));
            check(receivers.size() > 2000, "too few tracked block faces: " + receivers.size());
            checkFaces(receivers);

            double worst = 0;
            // Back away from the cliff as in the recording, then turn: both carry cascade splits
            // across the faces. Each pose is captured at rest so it reprojects exactly.
            double forwardX = -Math.sin(Math.toRadians(start.yaw)), forwardZ = Math.cos(Math.toRadians(start.yaw));
            var poses = new ArrayList<Pose>();
            for (int step = 1; step <= 8; step++) poses.add(new Pose(start.x - forwardX * step * 0.5, start.y, start.z - forwardZ * step * 0.5, start.yaw, start.pitch));
            for (float turn : new float[]{2, 5, -5}) poses.add(new Pose(start.x, start.y, start.z, start.yaw + turn, start.pitch));
            for (Pose pose : poses) {
                teleport(context, pose);
                context.waitTicks(3);
                Path path = capture(context, "pose-" + poses.indexOf(pose));
                Stats stats = context.computeOnClient(c -> compare(c, receivers, path));
                System.out.println(String.format(java.util.Locale.ROOT, "Shadow stability pose back=%.1f turn=%.0f: changed=%d/%d %s",
                    Math.hypot(pose.x - start.x, pose.z - start.z), pose.yaw - start.yaw, stats.changed, stats.compared, stats.faces));
                worst = Math.max(worst, stats.fraction());
            }
            check(worst <= MAX_CHANGED, "Fixed block faces changed sun visibility under camera motion: " + worst);
            System.out.println("Shadow stability live passed: existing NORMAL world, Metal 4K 16/16, " + receivers.size()
                + " block faces, worst changed fraction=" + String.format(java.util.Locale.ROOT, "%.4f", worst));
        } finally {
            context.runOnClient(c -> {
                if (c.gui.hud.isHidden() != hud) c.gui.hud.toggle();
                c.options.bobView().set(bob);
                c.options.cloudStatus().set(clouds);
                if (ShaderPackRuntime.BUILTIN_ID.equals(ShaderPackRuntime.active().selectedPackId())) {
                    ShaderPackRuntime.active().setOption("wind_enabled", wind);
                    ShaderPackRuntime.active().setOption("debug_view", debug);
                }
                ShaderPackRuntime.active().selectPack(pack);
                if (c.level != null) c.level.disconnect(net.minecraft.network.chat.Component.literal("Shadow stability test complete"));
                c.disconnect(new TitleScreen(), false);
            });
            context.waitFor(c -> !net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl.isServerRunning && c.level == null, 1200);
            context.setScreen(TitleScreen::new);
        }
    }

    /** East faces turn from the afternoon sun; south faces are edge-on and at most half lit. */
    private static void checkFaces(List<Receiver> receivers) {
        int east = 0, eastLit = 0, south = 0, southBright = 0;
        for (Receiver receiver : receivers) {
            if (receiver.face == Direction.EAST) { east++; if (receiver.visibility > 13) eastLit++; }
            if (receiver.face == Direction.SOUTH) { south++; if (receiver.visibility > 140) southBright++; }
        }
        System.out.println("Shadow stability faces: east lit=" + eastLit + "/" + east + ", south above half=" + southBright + "/" + south);
        check(east > 500 && eastLit <= east / 100, "Faces turned from the sun received sunlight: " + eastLit + "/" + east);
        check(south > 500 && southBright <= south / 100, "Edge-on faces took grid-dependent sunlight: " + southBright + "/" + south);
    }

    private static Matrix4f frame(Minecraft client) {
        var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        return new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);
    }

    private static Vec3 cameraPosition(Minecraft client) {
        return client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.pos;
    }

    /** Full-block faces away from their edges, with nothing between them and the camera. */
    private static List<Receiver> collect(Minecraft client, Path image) {
        Matrix4f matrix = frame(client);
        Matrix4f inverse = new Matrix4f(matrix).invert();
        Vec3 camera = cameraPosition(client);
        var receivers = new ArrayList<Receiver>();
        try (var screenshot = NativeImage.read(Files.newInputStream(image))) {
            int width = screenshot.getWidth(), height = screenshot.getHeight();
            for (int py = GRID / 2; py < height; py += GRID) for (int px = GRID / 2; px < width; px += GRID) {
                var far = inverse.transform(new Vector4f((px + 0.5F) / width * 2 - 1, 1 - (py + 0.5F) / height * 2, 0.5F, 1));
                Vec3 direction = new Vec3(far.x / far.w, far.y / far.w, far.z / far.w).normalize();
                BlockHitResult hit = client.level.clip(new ClipContext(camera, camera.add(direction.scale(96)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
                if (hit.getType() != HitResult.Type.BLOCK || !client.level.getBlockState(hit.getBlockPos()).isSolidRender()) continue;
                Vec3 point = hit.getLocation();
                if (!awayFromEdges(point, hit.getDirection()) || !clearSight(client, camera, point)) continue;
                float value = sample(screenshot, project(matrix, camera, point));
                if (!Float.isNaN(value)) receivers.add(new Receiver(point, hit.getBlockPos(), hit.getDirection(), value));
            }
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
        return receivers;
    }

    /** Counts receivers still in view whose visibility moved by more than 24/255. */
    private static Stats compare(Minecraft client, List<Receiver> receivers, Path image) {
        Matrix4f matrix = frame(client);
        Vec3 camera = cameraPosition(client);
        int changed = 0, compared = 0;
        var faces = new java.util.EnumMap<Direction, Integer>(Direction.class);
        try (var screenshot = NativeImage.read(Files.newInputStream(image))) {
            for (Receiver receiver : receivers) {
                float[] ndc = project(matrix, camera, receiver.point);
                if (ndc == null) continue;
                BlockHitResult hit = client.level.clip(new ClipContext(camera,
                    receiver.point.add(receiver.point.subtract(camera).normalize().scale(0.01)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty()));
                if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(receiver.block)
                    || hit.getDirection() != receiver.face || hit.getLocation().distanceTo(receiver.point) > 1.0e-3
                    || !clearSight(client, camera, receiver.point)) continue;
                float value = sample(screenshot, ndc);
                if (Float.isNaN(value)) continue;
                compared++;
                if (Math.abs(value - receiver.visibility) > 24) {
                    changed++;
                    faces.merge(receiver.face, 1, Integer::sum);
                }
            }
        } catch (java.io.IOException error) {
            throw new AssertionError(error);
        }
        return new Stats(changed, compared, faces);
    }

    /** Plants draw outside their outline boxes, so every cell before the face must be air. */
    private static boolean clearSight(Minecraft client, Vec3 camera, Vec3 point) {
        Vec3 end = point.subtract(point.subtract(camera).normalize().scale(1.0e-3));
        return !BlockGetter.traverseBlocks(camera, end, client.level,
            (level, position) -> level.getBlockState(position).isAir() ? null : Boolean.TRUE, level -> Boolean.FALSE);
    }

    private static boolean awayFromEdges(Vec3 point, Direction face) {
        double[] coordinates = {point.x, point.y, point.z};
        for (int i = 0; i < 3; i++) {
            if (i == face.getAxis().ordinal()) continue;
            double fraction = coordinates[i] - Math.floor(coordinates[i]);
            if (fraction < 0.15 || fraction > 0.85) return false;
        }
        return true;
    }

    private static float[] project(Matrix4f matrix, Vec3 camera, Vec3 point) {
        var clip = matrix.transform(new Vector4f((float)(point.x - camera.x), (float)(point.y - camera.y), (float)(point.z - camera.z), 1));
        return clip.w <= 0 ? null : new float[]{clip.x / clip.w, clip.y / clip.w};
    }

    private static float sample(NativeImage image, float[] ndc) {
        if (ndc == null) return Float.NaN;
        float px = (ndc[0] * 0.5F + 0.5F) * image.getWidth() - 0.5F;
        float py = (0.5F - ndc[1] * 0.5F) * image.getHeight() - 0.5F;
        int x0 = (int)Math.floor(px), y0 = (int)Math.floor(py);
        if (x0 < 2 || y0 < 2 || x0 + 3 >= image.getWidth() || y0 + 3 >= image.getHeight()) return Float.NaN;
        float value = 0;
        for (int dy = 0; dy <= 1; dy++) for (int dx = 0; dx <= 1; dx++) {
            float weight = (dx == 0 ? 1 - (px - x0) : px - x0) * (dy == 0 ? 1 - (py - y0) : py - y0);
            value += weight * net.minecraft.util.ARGB.red(image.getPixel(x0 + dx, y0 + dy));
        }
        return value;
    }

    private static Path capture(ClientGameTestContext context, String name) {
        context.runOnClient(c -> {
            check(ShaderPackRuntime.active().isActive(), "Standard active: " + ShaderPackRuntime.active().lastError());
            c.gui.hud.getChat().clearMessages(true);
        });
        return context.takeScreenshot("metalcraft-shadow-stability-" + name);
    }

    private static void teleport(ClientGameTestContext context, Pose pose) {
        command(context, String.format(java.util.Locale.ROOT, "tp @a %.4f %.4f %.4f %.3f %.3f", pose.x, pose.y, pose.z, pose.yaw, pose.pitch));
        context.getInput().lookAt(pose.yaw, pose.pitch);
    }

    /** Reuses one disposable copy of the source save across runs. */
    private static String copySave(String sourceName) {
        Path saves = Path.of("saves").toAbsolutePath().normalize();
        Path target = saves.resolve("MetalCraft Shadow Stability");
        if (Files.isRegularFile(target.resolve("level.dat"))) return target.getFileName().toString();
        Path source = saves.resolve(sourceName).normalize();
        check(source.getParent().equals(saves) && Files.isRegularFile(source.resolve("level.dat")), "existing standard save is available: " + source);
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                if (path.getFileName().toString().equals("session.lock")) continue;
                Path destination = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        } catch (java.io.IOException error) {
            throw new AssertionError("Could not copy disposable test world", error);
        }
        System.out.println("Shadow stability disposable save copy: " + target + "; source retained: " + source);
        return target.getFileName().toString();
    }

    private static void server(ClientGameTestContext context, Consumer<MinecraftServer> action) {
        CompletableFuture<?> done = context.computeOnClient(c -> {
            var server = c.getSingleplayerServer();
            return server.submit(() -> action.accept(server));
        });
        context.waitFor(c -> done.isDone());
        done.join();
    }

    private static void command(ClientGameTestContext context, String command) {
        server(context, s -> s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), command));
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
