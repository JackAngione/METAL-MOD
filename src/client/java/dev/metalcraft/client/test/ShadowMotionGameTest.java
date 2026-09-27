package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.NativeImage;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CloudStatus;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Tracks fixed world receivers while the real terrain/resolve camera turns. */
final class ShadowMotionGameTest {
    static void run(ClientGameTestContext context) {
        var clouds = context.computeOnClient(c -> c.options.cloudStatus().get());
        try {
            CompletableFuture<?> time = context.computeOnClient(c -> c.getSingleplayerServer().submit(() ->
                c.getSingleplayerServer().getCommands().performPrefixedCommand(
                    c.getSingleplayerServer().createCommandSourceStack(), "time set " + Integer.getInteger("metalcraft.shadowMotionTime", 12400))));
            context.waitFor(c -> time.isDone());
            time.join();
            context.runOnClient(c -> {
                c.options.cloudStatus().set(CloudStatus.OFF);
                c.gui.hud.getChat().clearMessages(true);
                ShaderPackRuntime.active().setOption("debug_view", "visibility");
            });
            command(context, "forceload add -100 -160 100 20");
            CompletableFuture<?> chunks = context.computeOnClient(c -> c.getSingleplayerServer().submit(() -> {
                var level = c.getSingleplayerServer().overworld();
                for (int z = -10; z <= 1; z++) for (int x = -7; x <= 6; x++) level.getChunk(x, z);
            }));
            context.waitFor(c -> chunks.isDone(), 600);
            chunks.join();
            command(context, "fill -100 200 -160 0 200 20 white_concrete");
            command(context, "fill 1 200 -160 100 200 20 white_concrete");
            CompletableFuture<?> floor = context.computeOnClient(c -> c.getSingleplayerServer().submit(() -> {
                var level = c.getSingleplayerServer().overworld();
                for (int x : new int[]{-100, 100}) for (int z : new int[]{-160, 20})
                    if (!level.getBlockState(new net.minecraft.core.BlockPos(x, 200, z)).is(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getValue(net.minecraft.resources.Identifier.parse("minecraft:white_concrete"))))
                        throw new AssertionError("Shadow motion floor was not loaded/built");
            }));
            context.waitFor(c -> floor.isDone());
            floor.join();
            context.getInput().lookAt(141, 15);
            context.waitTicks(50);
            String label = System.getProperty("metalcraft.shadowMotionLabel", "fixed");
            String recording = System.getProperty("metalcraft.shadowMotionRecordDir");
            if (recording != null) {
                Path directory = Path.of(recording);
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("ready"), Long.toString(ProcessHandle.current().pid()));
                context.waitFor(c -> Files.isRegularFile(directory.resolve("recording")), 200);
                context.waitTicks(15);
            }
            float lightHeight = context.computeOnClient(c -> (float)Math.abs(Math.cos(
                c.gameRenderer.gameRenderState().levelRenderState.skyRenderState.sunAngle)));
            System.out.println("Shadow motion light height: " + lightHeight);
            int dark = 0, samples = 0;
            var lines = new ArrayList<String>();
            lines.add("frame,yaw,x,z,px,py,visibility");
            for (int frame = 0; frame < 17; frame++) {
                float yaw = 141 + frame * 0.5F;
                context.getInput().lookAt(yaw, 15);
                context.waitTicks(3);
                Path path = context.takeScreenshot("metalcraft-shadow-motion-" + label + "-" + frame);
                Matrix4f matrix = context.computeOnClient(c -> {
                    var camera = c.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
                    return new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix)
                        .translate((float)-camera.pos.x, (float)-camera.pos.y, (float)-camera.pos.z);
                });
                try (var image = NativeImage.read(Files.newInputStream(path))) {
                    for (int iz = 0; iz < 41; iz++) for (int ix = 0; ix < 41; ix++) {
                        float x = -90 + ix * 2.0F, z = -140 + iz * 3.0F;
                        var clip = matrix.transform(new Vector4f(x, 201.001F, z, 1));
                        float px = (clip.x / clip.w * 0.5F + 0.5F) * image.getWidth() - 0.5F;
                        float py = (0.5F - clip.y / clip.w * 0.5F) * image.getHeight() - 0.5F;
                        if (clip.w <= 0 || px < image.getWidth() * 0.25F || px > image.getWidth() * 0.75F
                            || py < image.getHeight() * 0.25F || py > image.getHeight() * 0.75F) continue;
                        int x0 = (int)Math.floor(px), y0 = (int)Math.floor(py);
                        if (x0 < 0 || y0 < 0 || x0 + 1 >= image.getWidth() || y0 + 1 >= image.getHeight()) continue;
                        float value = 0;
                        for (int dy = 0; dy <= 1; dy++) for (int dx = 0; dx <= 1; dx++) {
                            float weight = (dx == 0 ? 1 - (px - x0) : px - x0) * (dy == 0 ? 1 - (py - y0) : py - y0);
                            value += weight * net.minecraft.util.ARGB.red(image.getPixel(x0 + dx, y0 + dy));
                        }
                        samples++;
                        if (value < 170) dark++;
                        lines.add(frame + "," + yaw + "," + x + "," + z + "," + px + "," + py + "," + value);
                    }
                }
            }
            Files.createDirectories(Path.of("benchmarks"));
            Files.write(Path.of("benchmarks/shadow-motion-" + label + ".csv"), lines);
            if (recording != null) {
                context.runOnClient(c -> ShaderPackRuntime.active().setOption("debug_view", "off"));
                context.waitTicks(30);
                Files.writeString(Path.of(recording).resolve("done"), "done");
            }
            System.out.println("Shadow motion flat-plane check: " + dark + " false-shadow samples / " + samples + ", label=" + label);
            if (samples < 100) throw new AssertionError("Shadow motion fixture lacks visible receivers");
            if (!label.equals("before") && dark > samples / 100) throw new AssertionError("Flat ground self-shadowed during camera motion: " + dark + "/" + samples);
        } catch (java.io.IOException error) { throw new AssertionError(error); }
        finally {
            command(context, "forceload remove -100 -160 100 20");
            context.runOnClient(c -> c.options.cloudStatus().set(clouds));
        }
    }
    private static void command(ClientGameTestContext context, String command) {
        CompletableFuture<?> done = context.computeOnClient(c -> c.getSingleplayerServer().submit(() ->
            c.getSingleplayerServer().getCommands().performPrefixedCommand(c.getSingleplayerServer().createCommandSourceStack(), command)));
        context.waitFor(c -> done.isDone());
        done.join();
    }
}
