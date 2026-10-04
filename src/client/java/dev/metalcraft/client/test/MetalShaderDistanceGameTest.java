package dev.metalcraft.client.test;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Short integration route. The caller supplies a disposable copy of an existing NORMAL save. */
final class MetalShaderDistanceGameTest {
    static void run(ClientGameTestContext context) {
        String world = System.getProperty("metalcraft.shaderDistanceTestWorld");
        if (world == null || world.isBlank()) throw new AssertionError("Supply a disposable existing-world copy with metalcraft.shaderDistanceTestWorld");
        context.runOnClient(c -> {
            check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "default engine uses Metal");
            check(c.getLevelSource().levelExists(world), "existing standard save copy");
            MetalCraftConfig.setLodEnabled(false);
            MetalCraftConfig.setHalfResolution(false);
            c.options.renderDistance().set(16);
            c.options.simulationDistance().set(16);
            ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
            c.createWorldOpenFlows().openWorld(world, () -> {});
        });
        context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 1200);
        var server = context.computeOnClient(c -> c.getSingleplayerServer());
        check(server.overworld().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator, "NORMAL terrain");
        try {
            context.runOnClient(c -> c.options.broadcastOptions());
            int x = context.computeOnClient(c -> c.player.blockPosition().getX());
            int z = context.computeOnClient(c -> c.player.blockPosition().getZ());
            int y = context.computeOnClient(c -> Math.min(280, Math.max(100, c.player.blockPosition().getY() + 24)));
            command(context, "gamemode spectator @a");
            command(context, "fill " + (x-12) + " " + (y-2) + " " + (z-12) + " " + (x+12) + " " + y + " " + (z+12) + " minecraft:stone");
            command(context, "fill " + (x-10) + " " + (y-1) + " " + (z-10) + " " + (x+10) + " " + y + " " + (z+10) + " minecraft:water");
            command(context, "tp @a " + x + " " + (y+5) + " " + z + " 0 60");
            command(context, "time set 2000");
            context.getInput().lookAt(0, 60);
            context.runOnClient(c -> {
                var runtime = ShaderPackRuntime.active();
                runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
                runtime.setOption("shadow_distance", 96);
                runtime.setOption("distant_shadow_distance", 256);
                runtime.setOption("water_enabled", true);
            });
            context.waitFor(c -> MetalGpuDevices.current().lastWorldHadOpaqueWaterInputs()
                && MetalGpuDevices.current().lastWorldWaterDraws() > 0, 500);
            context.runOnClient(c -> {
                var shadows = ShaderPackRuntime.active().worldShadows();
                check(shadows != null && shadows.currentDistantFrame() != null
                    && shadows.currentDistantFrame().cascadeCount() == 1, "live distant shadow frame");
            });
            context.takeScreenshot("shader-distance-water-above");
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("water_enabled", false));
            context.waitTicks(4);
            context.runOnClient(c -> check(!MetalGpuDevices.current().lastWorldHadOpaqueWaterInputs(), "disabled water skips opaque copies"));
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("water_enabled", true));
            context.waitFor(c -> MetalGpuDevices.current().lastWorldHadOpaqueWaterInputs(), 100);
            command(context, "tp @a " + x + " " + (y-1.5) + " " + z + " 0 -35");
            context.getInput().lookAt(0, -35);
            context.waitFor(c -> c.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogType
                == net.minecraft.world.level.material.FogType.WATER, 100);
            context.waitTicks(4);
            context.runOnClient(c -> {
                check(!MetalGpuDevices.current().lastWorldHadOpaqueWaterInputs(), "underwater skips opaque copies");
                check(MetalGpuDevices.current().lastWorldWaterDraws() > 0, "underwater retains shader water");
            });
            context.takeScreenshot("shader-distance-water-below");
            command(context, "tp @a " + x + " " + (y+5) + " " + z + " 135 15");
            context.getInput().lookAt(135, 15);
            context.waitTicks(6);
            context.takeScreenshot("shader-distance-terrain");
            context.runOnClient(c -> ShaderPackRuntime.active().reload());
            context.waitTicks(6);
            context.runOnClient(c -> {
                var runtime = ShaderPackRuntime.active();
                check(runtime.isActive() && runtime.lastError().isEmpty(), "reload remains healthy");
                check(runtime.worldShadows().currentDistantFrame() != null, "distant tier survives reload");
                runtime.setOption("distant_shadow_distance", 0);
            });
            context.waitTicks(4);
            context.runOnClient(c -> check(ShaderPackRuntime.active().worldShadows().currentDistantFrame() == null, "distant tier disables"));
            System.out.println("Shader distance integration passed: existing NORMAL save copy, Metal, 16/16, water on/off/underwater, distant frame, reload and disable");
        } finally {
            context.runOnClient(c -> c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE));
            context.waitFor(c -> c.level == null && server.isShutdown(), 1200);
            context.setScreen(TitleScreen::new);
        }
    }

    private static void command(ClientGameTestContext context, String command) {
        var done = context.computeOnClient(c -> {
            var server = c.getSingleplayerServer();
            return server.submit(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
        });
        context.waitFor(c -> done.isDone());
        done.join();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
