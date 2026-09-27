package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.world.WorldLocalLighting;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.server.MinecraftServer;

/** Short interleaved whole-frame local-light cost probe in the existing standard-world fixture. */
final class LocalLightingBenchmark {
    static void run(ClientGameTestContext context) {
        boolean unlocked = MetalCraftConfig.unlockedFrameRate();
        var phases = new ArrayList<Object>();
        jdk.jfr.Recording recording = null;
        try {
            context.getInput().resizeWindow(1920, 1080);
            context.runOnClient(c -> MetalCraftConfig.setUnlockedFrameRate(true));
            context.waitTicks(80);
            if (Boolean.getBoolean("metalcraft.lightingJfr")) {
                recording = new jdk.jfr.Recording(jdk.jfr.Configuration.getConfiguration("profile"));
                recording.start();
            }
            for (String scene : System.getProperty("metalcraft.lightingBenchmarkScenes", "night_empty,night_torch,day_torch,night_previous").split(",")) {
                System.setProperty("metalcraft.benchmarkSkipMoon", Boolean.toString(scene.equals("night_previous")));
                command(context, scene.startsWith("day") ? "time set 6000" : "time set 18000");
                command(context, "setblock -5 201 0 " + (scene.endsWith("empty") ? "air" : "torch"));
                context.waitTicks(30);
                for (boolean enabled : new boolean[]{false, true, true, false}) {
                    context.runOnClient(c -> ShaderPackRuntime.active().setOption("local_lights", enabled));
                    context.waitTicks(16);
                    var before = context.computeOnClient(c -> profile());
                    context.runOnClient(c -> MetalFrameMetrics.beginCapture(8));
                    context.waitTicks(30);
                    var phase = context.computeOnClient(c -> MetalFrameMetrics.endCapture(scene + "-" + enabled));
                    var after = context.computeOnClient(c -> profile());
                    var row = new LinkedHashMap<String, Object>();
                    row.put("scene", scene); row.put("localLights", enabled); row.put("frame", phase);
                    context.runOnClient(c -> {
                        row.put("framebufferWidth", c.getWindow().getWidth());
                        row.put("framebufferHeight", c.getWindow().getHeight());
                        row.put("halfResolution", MetalCraftConfig.halfResolution());
                        row.put("renderDistance", c.options.renderDistance().get());
                        row.put("simulationDistance", c.options.simulationDistance().get());
                    });
                    row.put("before", before); row.put("after", after);
                    phases.add(row);
                    double prep = before == null || after.calls() == before.calls() ? 0
                        : (after.nanos() - before.nanos()) / 1e6 / (after.calls() - before.calls());
                    System.out.printf("LIGHT_BENCH %s enabled=%s fps=%.1f cpu=%.3f gpu=%s prepare=%.3f sources=%s rebuilds=%s%n",
                        scene, enabled, phase.averageFps(), phase.p50CpuMs(), phase.gpuFrame().p50Ms(), prep,
                        after == null ? 0 : after.sources(), after == null ? 0 : after.uploads() - before.uploads());
                }
            }
            Path output = Path.of("benchmarks", "local-lighting-" + System.getProperty("metalcraft.lightingBenchmarkLabel", "current") + ".json");
            Files.createDirectories(output.getParent());
            Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(phases));
            if (recording != null) {
                recording.stop();
                recording.dump(Path.of("benchmarks", "lighting-cpu.jfr"));
            }
        } catch (java.io.IOException | java.text.ParseException error) { throw new AssertionError(error); }
        finally {
            if (recording != null) recording.close();
            System.clearProperty("metalcraft.benchmarkSkipMoon");
            context.runOnClient(c -> MetalCraftConfig.setUnlockedFrameRate(unlocked));
        }
    }

    private static WorldLocalLighting.Profile profile() {
        var lights = ShaderPackRuntime.active().worldGeometry().localLighting();
        return lights == null ? null : lights.profile();
    }
    private static void command(ClientGameTestContext context, String command) {
        var future = context.computeOnClient(c -> {
            MinecraftServer server = c.getSingleplayerServer();
            return server.submit(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
        });
        context.waitFor(c -> future.isDone());
        future.join();
    }
}
