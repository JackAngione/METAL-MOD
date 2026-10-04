package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.NativeImage;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.world.level.gamerules.GameRules;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Paired 4K reads through the real terrain, uniform capture and deferred resolve paths. */
final class ShadowFlickerGameTest {
    static void run(ClientGameTestContext context) throws java.io.IOException {
        var server = context.computeOnClient(c -> c.getSingleplayerServer());
        var setup = server.submit(() -> {
            server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            var source = server.createCommandSourceStack();
            for (String command : new String[]{
                "time set 2000", "fill 4580 200 400 4740 200 570 white_concrete",
                "fill 4668 201 510 4672 213 514 stone", "tp @a 4702.5 216 546.5 135 30"
            }) server.getCommands().performPrefixedCommand(source, command);
        });
        context.waitFor(c -> setup.isDone()); setup.join();
        context.runOnClient(c -> {
            c.options.vignette().set(false);
            var runtime = ShaderPackRuntime.active();
            runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
            runtime.setOption("water_enabled", false);
            runtime.setOption("wind_enabled", false);
            runtime.setOption("local_lights", false);
            runtime.setOption("shadow_cascades", 4);
            runtime.setOption("shadow_resolution", 1024);
            runtime.setOption("shadow_distance", 256);
            runtime.setOption("shadow_caster_distance", 256);
            runtime.setOption("distant_shadow_distance", 512);
            runtime.setOption("debug_view", "visibility");
            c.gui.hud.getChat().clearMessages(true);
        });
        context.getInput().lookAt(135, 30);
        context.waitTicks(50);
        context.runOnClient(c -> c.gui.hud.getChat().clearMessages(true));
        if (Boolean.getBoolean("metalcraft.shadowSurfaceTest")) {
            checkUnoccludedSurface(context);
            return;
        }
        var lines = new ArrayList<String>();
        lines.add("frame,changedPixels,maxDifference");
        for (int frame = 0; frame < 6; frame++) {
            context.getInput().lookAt(135 + frame * 0.25F, 30);
            context.runOnClient(c -> System.setProperty("metalcraft.baselineScalarShadows", "true"));
            context.waitTicks(3);
            Path scalar = context.takeScreenshot("shadow-flicker-scalar-" + frame);
            context.runOnClient(c -> System.setProperty("metalcraft.baselineScalarShadows", "false"));
            context.waitTicks(3);
            Path grouped = context.takeScreenshot("shadow-flicker-grouped-" + frame);
            try (var a = NativeImage.read(Files.newInputStream(scalar)); var b = NativeImage.read(Files.newInputStream(grouped))) {
                if (a.getWidth() != 3840 || a.getHeight() != 2160 || b.getWidth() != 3840 || b.getHeight() != 2160)
                    throw new AssertionError("Shadow flicker test must render at 4K");
                int changed = 0, max = 0;
                for (int y = 0; y < 2160; y++) for (int x = 0; x < 3840; x++) {
                    int delta = Math.abs(net.minecraft.util.ARGB.red(a.getPixel(x, y)) - net.minecraft.util.ARGB.red(b.getPixel(x, y)));
                    if (delta > 3) changed++;
                    max = Math.max(max, delta);
                }
                lines.add(frame + "," + changed + "," + max);
                System.out.println("SHADOW_FLICKER frame=" + frame + " changed=" + changed + " max=" + max);
                if (changed != 0) throw new AssertionError("Static shadow filter mismatch: " + changed + " pixels");
            }
        }
        Files.createDirectories(Path.of("diagnostics/shadow-flicker"));
        Files.write(Path.of("diagnostics/shadow-flicker/comparison.csv"), lines);
        var resume = server.submit(() -> server.getGameRules().set(GameRules.ADVANCE_TIME, true, server));
        context.waitFor(c -> resume.isDone()); resume.join();
        context.waitTicks(4);
        for (boolean moving : new boolean[]{false, true}) {
            long[] before = context.computeOnClient(c -> counts());
            for (int tick = 0; tick < 20; tick++) {
                if (moving) context.getInput().lookAt(136.25F + tick * 0.1F, 30);
                context.waitTicks(1);
            }
            long[] after = context.computeOnClient(c -> counts());
            long updates = after[0] - before[0], reused = after[1] - before[1];
            System.out.println("SHADOW_FLICKER animatedLight moving=" + moving + " updates=" + updates + " reused=" + reused);
            if (updates == 0) throw new AssertionError("Animated light did not refresh shadows");
        }
    }

    private static long[] counts() {
        var shadows = ShaderPackRuntime.active().worldShadows();
        return new long[]{shadows.updatedFrames(), shadows.reusedFrames()};
    }

    private static void checkUnoccludedSurface(ClientGameTestContext context) throws java.io.IOException {
        var server = context.computeOnClient(c -> c.getSingleplayerServer());
        var setup = server.submit(() -> {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set 12400");
            // Sunlight lies in XY: avoid every Z row containing a real raised caster.
            var points = new ArrayList<net.minecraft.core.BlockPos>();
            var world = server.overworld();
            for (int z = 410; z <= 550; z += 2) {
                boolean clear = true;
                for (int x = 4580; x <= 4740 && clear; x++) {
                    for (int dz = -2; dz <= 2 && clear; dz++) {
                        clear = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z + dz) <= 201;
                    }
                }
                if (clear) for (int x = 4590; x <= 4730; x += 2) points.add(new net.minecraft.core.BlockPos(x, 201, z));
            }
            return points;
        });
        context.waitFor(c -> setup.isDone());
        var points = setup.join();
        context.waitTicks(4);
        context.runOnClient(c -> c.gui.hud.getChat().clearMessages(true));
        int total = 0, falseShadows = 0;
        var litReceivers = new java.util.HashSet<net.minecraft.core.BlockPos>();
        for (int frame = 0; frame < 10; frame++) {
            context.getInput().lookAt(131 + frame * .6F, 20);
            context.waitTicks(2);
            Matrix4f matrix = context.computeOnClient(c -> {
                var camera = c.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
                return new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix)
                    .translate((float)-camera.pos.x, (float)-camera.pos.y, (float)-camera.pos.z);
            });
            Path path = context.takeScreenshot("shadow-surface-" + frame);
            int dark = 0, samples = 0;
            try (var image = NativeImage.read(Files.newInputStream(path))) {
                if (image.getWidth() != 3840 || image.getHeight() != 2160) throw new AssertionError("Requires 4K");
                for (var point : points) {
                    var clip = matrix.transform(new Vector4f(point.getX()+.5F,201.001F,point.getZ()+.5F,1));
                    int x = Math.round((clip.x/clip.w*.5F+.5F)*3840-.5F);
                    int y = Math.round((.5F-clip.y/clip.w*.5F)*2160-.5F);
                    if (clip.w <= 0 || x < 32 || x >= 3808 || y < 32 || y >= 2128) continue;
                    int value = net.minecraft.util.ARGB.red(image.getPixel(x,y));
                    if (frame == 0) {
                        // Exclude real caster shadows and their filtered edges. Heightmap
                        // row exclusions alone cannot account for every loaded offscreen caster.
                        boolean lit = true;
                        for (int dy = -8; dy <= 8 && lit; dy += 4) for (int dx = -8; dx <= 8 && lit; dx += 4)
                            lit = net.minecraft.util.ARGB.red(image.getPixel(x+dx,y+dy)) >= 254;
                        if (lit) litReceivers.add(point);
                    }
                    if (!litReceivers.contains(point)) continue;
                    samples++;
                    if (value < 250) dark++;
                }
            }
            total += samples; falseShadows += dark;
            System.out.println("SHADOW_SURFACE frame="+frame+" false="+dark+" samples="+samples);
        }
        if (litReceivers.size() < 1000 || total < 1000 || falseShadows > total/1000)
            throw new AssertionError("Unoccluded surfaces flicker: "+falseShadows+"/"+total);
    }
}
