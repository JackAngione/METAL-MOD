package dev.metalcraft.client.test;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.gui.MetalCraftOptionsScreen;
import dev.metalcraft.client.shader.world.ShadowFrameReuse;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import dev.metalcraft.client.metal.MetalSurfaceProbe;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/** Bounded shadow comparison in a caller-supplied disposable copy of an existing NORMAL save. */
final class RealtimeShadowDistanceGameTest {
    static void run(ClientGameTestContext context) {
        String world = System.getProperty("metalcraft.realtimeShadowDistanceTestWorld");
        if (world == null || world.isBlank()) throw new AssertionError("Supply a disposable existing-world copy with metalcraft.realtimeShadowDistanceTestWorld");
        context.runOnClient(c -> {
            check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "default engine uses Metal");
            check(c.getLevelSource().levelExists(world), "existing standard save copy");
            MetalCraftConfig.setLodEnabled(false);
            MetalCraftConfig.setHalfResolution(false);
            c.options.renderDistance().set(16);
            c.options.simulationDistance().set(16);
            c.options.enableVsync().set(false);
            c.options.framerateLimit().set(net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF);
            ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID);
            c.createWorldOpenFlows().openWorld(world, () -> {});
        });
        context.getInput().resizeWindow(3840, 2160);
        context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 1200);
        var gameServer = context.computeOnClient(c -> c.getSingleplayerServer());
        try {
            server(context, s -> {
                check(s.overworld().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator, "NORMAL terrain");
                s.getGameRules().set(GameRules.ADVANCE_TIME, false, s);
                s.getGameRules().set(GameRules.ADVANCE_WEATHER, false, s);
                s.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, s);
                s.getGameRules().set(GameRules.SPAWN_MOBS, false, s);
            });
            context.runOnClient(c -> {
                c.options.broadcastOptions();
                var runtime = ShaderPackRuntime.active();
                var range = runtime.options().stream().filter(o -> o.id().equals("shadow_distance")).findFirst().orElseThrow();
                check(range.min().orElseThrow() == 32 && range.max().orElseThrow() == 256
                    && range.step().orElseThrow() == 16, "realtime range 2..16 chunks stored as blocks");
                runtime.setOption("distant_shadow_distance", 512);
                runtime.setOption("debug_view", "off");
            });
            command(context, "gamemode spectator @a");
            command(context, "weather clear");
            command(context, "time set 8027");
            command(context, "tp @a 344.886 89.494 -9.567 118.95 -4.35");
            context.getInput().lookAt(118.95F, -4.35F);
            context.waitFor(c -> ShaderPackRuntime.active().worldShadows() != null
                && ShaderPackRuntime.active().worldShadows().lastDrawCount() > 0, 500);
            settleTerrain(context);
            verifyMenu(context);
            verifyResolution(context);
            for (int blocks : new int[] {32, 96, 256, 96}) {
                context.runOnClient(c -> ShaderPackRuntime.active().setOption("shadow_distance", blocks));
                context.waitTicks(8);
                context.runOnClient(c -> {
                    var runtime = ShaderPackRuntime.active();
                    check(((Number)runtime.optionValue("shadow_distance")).intValue() == blocks, "range applied in blocks");
                    check(runtime.worldShadows().currentDistantFrame() != null
                        && runtime.worldShadows().currentDistantFrame().cascadeCount() == 1, "cheap distant tier active");
                });
                capturePhase(context, "stationary-" + blocks / 16 + "-chunks", 30);
                context.takeScreenshot("realtime-shadow-" + blocks / 16 + "-chunks");
            }
            context.runOnClient(c -> {
                var runtime = ShaderPackRuntime.active();
                runtime.setOption("shadow_distance", 96);
                runtime.setOption("distant_shadow_distance", 256);
                runtime.reload();
                check(((Number)runtime.optionValue("shadow_distance")).intValue() == 96, "6 chunks survives reload");
            });
            context.waitTicks(8);
            long stationaryReuse = context.computeOnClient(c -> ShaderPackRuntime.active().worldShadows().distantReusedFrames());
            capturePhase(context, "stationary-cached-distant", 30);
            context.runOnClient(c -> check(ShaderPackRuntime.active().worldShadows().distantReusedFrames() > stationaryReuse,
                "stationary distant map reused"));
            long movingReuse = context.computeOnClient(c -> ShaderPackRuntime.active().worldShadows().distantReusedFrames());
            context.runOnClient(c -> MetalFrameMetrics.beginCapture(3));
            for (int i = 1; i <= 6; i++) {
                command(context, "tp @a " + (344.886 + i * 0.25) + " 89.494 -9.567 118.95 -4.35");
                context.waitTicks(2);
            }
            context.runOnClient(c -> {
                System.out.println("Shadow distance performance " + MetalFrameMetrics.endCapture("moving-cached-distant").toLogLine());
                check(ShaderPackRuntime.active().worldShadows().distantReusedFrames() > movingReuse, "moving distant map reused");
            });
            context.takeScreenshot("realtime-shadow-moving-transition");
            assertRefresh(context, "sun change", () -> command(context, "time set 10000"));
            assertRefresh(context, "teleport", () -> command(context, "tp @a 360.886 89.494 -9.567 118.95 -4.35"));
            assertRefresh(context, "terrain mesh edit", () -> command(context, "setblock 358 85 -10 minecraft:gold_block"));
            context.runOnClient(c -> ShaderPackRuntime.active().setOption("distant_shadow_distance", 0));
            context.waitTicks(4);
            context.runOnClient(c -> {
                var runtime = ShaderPackRuntime.active();
                check(runtime.worldShadows().currentDistantFrame() == null, "cheap tier disables");
                check(runtime.isActive() && runtime.lastError().isEmpty(), "runtime remains healthy");
            });
            System.out.println("Shadow distance integration passed: copied NORMAL save, Metal, 4K actual target, 16/16, 2/6/16 chunks, reload, stationary/moving reuse, sun/teleport/mesh refresh and disable");
        } finally {
            context.runOnClient(c -> {
                MetalFrameMetrics.discardCapture();
                c.disconnectFromWorld(net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE);
            });
            context.waitFor(c -> c.level == null && gameServer.isShutdown(), 1200);
            context.setScreen(TitleScreen::new);
        }
    }

    private static void settleTerrain(ClientGameTestContext context) {
        int quiet = 0;
        long revision = -1;
        for (int tick = 0; tick < 400 && quiet < 12; tick++) {
            context.waitTick();
            long now = context.computeOnClient(c -> ShadowFrameReuse.meshRevision());
            boolean ready = context.computeOnClient(c -> {
                int cx = c.player.blockPosition().getX() >> 4, cz = c.player.blockPosition().getZ() >> 4;
                for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++)
                    if (dx * dx + dz * dz <= 225 && !c.level.hasChunk(cx + dx, cz + dz)) return false;
                return c.levelRenderer.sectionRenderDispatcher().isQueueEmpty()
                    && !c.level.getChunkSource().getLightEngine().hasLightWork()
                    && c.levelRenderer.visibleSections().stream().filter(section -> section.getSectionMesh()
                        .getSectionDraw(net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID) != null).count() >= 32;
            });
            quiet = ready && now == revision ? quiet + 1 : 0;
            revision = now;
        }
        check(quiet >= 12, "terrain received and mesh/light queues quiet before timing");
    }

    private static void verifyMenu(ClientGameTestContext context) {
        context.setScreen(() -> new MetalCraftOptionsScreen(null));
        press(context, Component.translatable("metalcraft.options.shaders.title").getString());
        press(context, Component.translatable("metalcraft.options.shader_category.shadows").getString());
        context.runOnClient(c -> {
            var runtime = ShaderPackRuntime.active();
            runtime.setOption("shadow_distance", 96);
            var slider = (AbstractSliderButton)widgets(c.gui.screen()).stream()
                .filter(w -> w instanceof AbstractSliderButton && w.getMessage().getString().startsWith("Realtime shadow distance:"))
                .findFirst().orElseThrow();
            var resources = runtime.worldShadows();
            var click = new MouseButtonEvent(slider.getX() + 4, slider.getY() + 10,
                new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
            slider.onClick(click, false);
            check(((Number)runtime.optionValue("shadow_distance")).intValue() == 96 && runtime.worldShadows() == resources,
                "pointer click previews without rebuilding");
            var drag = new MouseButtonEvent(slider.getX() + slider.getWidth() - 4, slider.getY() + 10,
                new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
            try {
                var method = slider.getClass().getDeclaredMethod("onDrag", MouseButtonEvent.class, double.class, double.class);
                method.setAccessible(true);
                method.invoke(slider, drag, (double)slider.getWidth() - 8, 0.0);
            } catch (ReflectiveOperationException error) { throw new AssertionError("invoke actual slider drag", error); }
            check(((Number)runtime.optionValue("shadow_distance")).intValue() == 96 && runtime.worldShadows() == resources,
                "pointer drag previews without rebuilding");
            check(slider.getMessage().getString().contains("16 chunks"), "slider previews chunks");
            slider.onRelease(drag);
            check(((Number)runtime.optionValue("shadow_distance")).intValue() == 256 && runtime.worldShadows() != resources,
                "release applies preview once");
            var applied = runtime.worldShadows();
            slider.onRelease(drag);
            check(runtime.worldShadows() == applied, "second release does not rebuild");
            slider.setFocused(true);
            try {
                var keyboardMode = AbstractSliderButton.class.getDeclaredField("canChangeValue");
                keyboardMode.setAccessible(true);
                if (!keyboardMode.getBoolean(slider)) slider.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
            } catch (ReflectiveOperationException error) { throw new AssertionError("inspect slider keyboard activation", error); }
            slider.keyPressed(new KeyEvent(GLFW.GLFW_KEY_LEFT, 0, 0));
            check(((Number)runtime.optionValue("shadow_distance")).intValue() == 240, "keyboard applies one chunk immediately");
        });
        context.takeScreenshot("realtime-shadow-controls");
        context.setScreen(() -> null);
        System.out.println("Shadow distance controls passed: pointer preview, release once, chunks label, keyboard step");
    }

    private static void capturePhase(ClientGameTestContext context, String label, int ticks) {
        long[] before = counters(context);
        context.runOnClient(c -> MetalFrameMetrics.beginCapture(3));
        context.waitTicks(ticks);
        long[] after = counters(context);
        context.runOnClient(c -> System.out.println("Shadow distance performance " + MetalFrameMetrics.endCapture(label).toLogLine()
            + " detailedUpdates=" + (after[0] - before[0]) + " detailedReuse=" + (after[1] - before[1])
            + " distantUpdates=" + (after[2] - before[2]) + " distantReuse=" + (after[3] - before[3])));
    }

    private static long[] counters(ClientGameTestContext context) {
        return context.computeOnClient(c -> {
            var shadows = ShaderPackRuntime.active().worldShadows();
            return new long[] {shadows.updatedFrames(), shadows.reusedFrames(), shadows.distantUpdatedFrames(), shadows.distantReusedFrames()};
        });
    }

    private static void assertRefresh(ClientGameTestContext context, String reason, Runnable mutation) {
        long before = counters(context)[2];
        mutation.run();
        context.waitFor(c -> ShaderPackRuntime.active().worldShadows().distantUpdatedFrames() > before, 100);
        System.out.println("Shadow distance invalidation passed: " + reason);
    }

    private static void verifyResolution(ClientGameTestContext context) {
        context.runOnClient(c -> {
            var target = c.gameRenderer.mainRenderTarget().getColorTextureView();
            var runtime = ShaderPackRuntime.active();
            int[] drawable = MetalSurfaceProbe.drawableSize();
            check(target.getWidth(0) == 3840 && target.getHeight(0) == 2160, "actual world target is 4K");
            check(MetalGpuDevices.current().lastWorldRenderWidth() == 3840
                && MetalGpuDevices.current().lastWorldRenderHeight() == 2160, "actual linear world session is 4K");
            check(runtime.frameWidth() == 3840 && runtime.frameHeight() == 2160, "shader scene is 4K");
            System.out.println("Shadow distance dimensions: worldTarget=" + target.getWidth(0) + "x" + target.getHeight(0)
                + " linearWorld=" + MetalGpuDevices.current().lastWorldRenderWidth() + "x" + MetalGpuDevices.current().lastWorldRenderHeight()
                + " shaderScene=" + runtime.frameWidth() + "x" + runtime.frameHeight()
                + " drawable=" + drawable[0] + "x" + drawable[1]);
        });
    }

    private static void server(ClientGameTestContext context, Consumer<MinecraftServer> action) {
        CompletableFuture<?> done = context.computeOnClient(client -> {
            var server = client.getSingleplayerServer();
            return server.submit(() -> action.accept(server));
        });
        context.waitFor(client -> done.isDone());
        done.join();
    }

    private static void command(ClientGameTestContext context, String command) {
        server(context, server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }

    private static List<AbstractWidget> widgets(ContainerEventHandler parent) {
        var found = new ArrayList<AbstractWidget>();
        for (GuiEventListener child : parent.children()) {
            if (child instanceof AbstractWidget widget) found.add(widget);
            if (child instanceof ContainerEventHandler container) found.addAll(widgets(container));
        }
        return found;
    }

    private static void press(ClientGameTestContext context, String label) {
        context.runOnClient(client -> {
            Button button = (Button)widgets(client.gui.screen()).stream().filter(widget -> widget instanceof Button
                && widget.getMessage().getString().startsWith(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Button not found: " + label));
            check(button.active, "Button is enabled: " + label);
            button.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
        });
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
