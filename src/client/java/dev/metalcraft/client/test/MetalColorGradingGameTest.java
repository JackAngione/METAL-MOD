package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.MetalCraftRenderResolution;
import dev.metalcraft.client.gui.MetalCraftOptionsScreen;
import dev.metalcraft.client.metal.MetalSurfaceProbe;
import dev.metalcraft.client.shader.ShaderPack;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.lwjgl.glfw.GLFW;

/** Bounded color-grading smoke route in a caller-supplied disposable NORMAL save copy. */
final class MetalColorGradingGameTest {
    private static final List<String> GRADING = List.of("exposure", "tonemap", "temperature", "tint",
        "contrast", "saturation", "vibrance", "gamma", "highlights", "shadows",
        "film_grain", "bloom", "depth_of_field");

    static void run(ClientGameTestContext context) {
        String world = System.getProperty("metalcraft.colorGradingTestWorld");
        check(world != null && !world.isBlank(), "Supply a disposable existing NORMAL save via metalcraft.colorGradingTestWorld");
        var original = context.computeOnClient(c -> new ClientSettings(ShaderPackRuntime.active().selectedPackId(),
            MetalCraftConfig.halfResolution(), MetalCraftConfig.lodEnabled(), c.options.renderDistance().get(),
            c.options.simulationDistance().get(), c.options.bobView().get(), c.options.cloudStatus().get(),
            c.gui.hud.isHidden(), c.options.vignette().get(), c.getWindow().getScreenWidth(), c.getWindow().getScreenHeight(), c.getWindow().isFullscreen()));
        Map<String, Object> saved = new LinkedHashMap<>();
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "default backend uses Metal");
                check(c.getLevelSource().levelExists(world), "existing standard-world copy is available: " + world);
                MetalCraftConfig.setHalfResolution(false);
                MetalCraftConfig.setLodEnabled(false);
                c.options.renderDistance().set(16);
                c.options.simulationDistance().set(16);
                c.options.cloudStatus().set(CloudStatus.OFF);
                c.options.bobView().set(false);
                if (!c.gui.hud.isHidden()) c.gui.hud.toggle();
                var runtime = ShaderPackRuntime.active();
                runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
                for (var option : runtime.options()) {
                    if (GRADING.contains(option.id()) || List.of("wind_enabled", "debug_view", "invert").contains(option.id()))
                        saved.put(option.id(), runtime.optionValue(option.id()));
                }
                check(saved.keySet().containsAll(GRADING), "all color-grading controls exist");
                for (var option : runtime.options()) if (GRADING.contains(option.id())) {
                    check("tonemap".equals(option.category()) && option.apply() == ShaderPack.ApplyMode.UNIFORM,
                        "grading controls are live uniforms: " + option.id());
                }
                neutral(runtime);
                runtime.setOption("debug_view", "off");
                runtime.setOption("wind_enabled", false);
                if (Boolean.TRUE.equals(runtime.optionValue("invert"))) runtime.setOption("invert", false);
                c.createWorldOpenFlows().openWorld(world, () -> {});
            });
            context.getInput().resizeWindow(3840, 2160);
            context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 1200);
            var setup = context.computeOnClient(c -> c.getSingleplayerServer().submit(() -> {
                var server = c.getSingleplayerServer();
                check(server.overworld().getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator,
                    "standard terrain required, not a flat world");
                server.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
                server.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
                server.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
                server.getGameRules().set(GameRules.SPAWN_MOBS, false, server);
            }));
            context.waitFor(c -> setup.isDone());
            setup.join();
            context.runOnClient(c -> c.options.broadcastOptions());
            command(context, "gamemode spectator @a");
            int[] camera = context.computeOnClient(c -> new int[] { c.player.blockPosition().getX(),
                Math.min(240, Math.max(90, c.player.blockPosition().getY() + 24)), c.player.blockPosition().getZ() });
            command(context, "tp @a " + camera[0] + " " + camera[1] + " " + camera[2] + " 135 25");
            command(context, "time set 6000");
            command(context, "weather clear");
            context.getInput().lookAt(135, 25);
            context.waitFor(c -> c.levelRenderer.visibleSections().stream().filter(section -> section.getSectionMesh()
                .getSectionDraw(net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID) != null).count() >= 16, 600);
            context.waitTicks(20);
            context.waitFor(c -> c.gameRenderer.mainRenderTarget().getColorTextureView().getWidth(0) == 3840
                && c.gameRenderer.mainRenderTarget().getColorTextureView().getHeight(0) == 2160, 200);
            Object executor = context.computeOnClient(c -> ShaderPackRuntime.active().executor().orElseThrow());
            verifyVignette(context, executor);
            verifyMenu(context, executor);
            context.runOnClient(c -> c.gui.setScreen(null));
            context.waitTicks(4);
            sample(context, "color-neutral-4k");

            // This opaque GUI swatch is intentionally outside the sampled world region.
            context.setScreen(GuiSwatch::new);
            context.waitTicks(4);
            Path neutral = capture(context, "neutral", executor);
            set(context, "temperature", 0.8);
            Path warm = capture(context, "warm", executor);
            set(context, "temperature", -0.8);
            Path cool = capture(context, "cool", executor);
            compare(neutral, warm, cool);
            context.runOnClient(c -> {
                neutral(ShaderPackRuntime.active());
                var runtime = ShaderPackRuntime.active();
                runtime.setOption("temperature", 0.25); runtime.setOption("tint", -0.15);
                runtime.setOption("contrast", 1.2); runtime.setOption("saturation", 0.8);
                runtime.setOption("vibrance", 0.3); runtime.setOption("gamma", 1.1);
                runtime.setOption("highlights", -0.25); runtime.setOption("shadows", 0.25);
            });
            capture(context, "graded", executor);
            context.runOnClient(c -> c.gui.setScreen(null));
            context.waitTicks(4);
            sample(context, "color-graded-4k");
            context.setScreen(GuiSwatch::new);
            context.runOnClient(c -> neutral(ShaderPackRuntime.active()));
            set(context, "tonemap", "aces");
            capture(context, "aces", executor);
            set(context, "tonemap", "reinhard");
            capture(context, "reinhard", executor);
            System.out.println("Color grading integration passed: existing NORMAL terrain, Metal, 3840x2160 world target, "
                + "16/16, immediate uniforms without executor replacement, submenu/slider/reset, warm/cool world pixels and ungraded GUI swatch. "
                + "Timings are brief same-scene smoke samples, not a general benchmark.");
        } finally {
            context.runOnClient(c -> {
                MetalFrameMetrics.discardCapture();
                c.gui.setScreen(null);
                if (ShaderPackRuntime.BUILTIN_ID.equals(ShaderPackRuntime.active().selectedPackId()))
                    saved.forEach(ShaderPackRuntime.active()::setOption);
                ShaderPackRuntime.active().selectPack(original.pack());
                MetalCraftConfig.setHalfResolution(original.half());
                MetalCraftConfig.setLodEnabled(original.lod());
                c.options.renderDistance().set(original.render());
                c.options.simulationDistance().set(original.simulation());
                c.options.bobView().set(original.bob());
                c.options.cloudStatus().set(original.clouds());
                c.options.vignette().set(original.vignette());
                if (c.gui.hud.isHidden() != original.hudHidden()) c.gui.hud.toggle();
                if (c.level != null) c.level.disconnect(Component.literal("Color grading test complete"));
                c.disconnect(new TitleScreen(), false);
            });
            context.waitFor(c -> !net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl.isServerRunning && c.level == null, 1200);
            context.getInput().resizeWindow(original.width(), original.height());
            context.runOnClient(c -> {
                if (c.getWindow().isFullscreen() != original.fullscreen()) c.getWindow().toggleFullScreen();
                MetalCraftRenderResolution.apply(c);
            });
            context.setScreen(TitleScreen::new);
        }
    }

    /** Real HUD composition must not multiply the ACES result by the vanilla corner mask. */
    private static void verifyVignette(ClientGameTestContext context, Object executor) {
        context.getInput().lookAt(135, -60);
        context.runOnClient(c -> { if (c.gui.hud.isHidden()) c.gui.hud.toggle(); });
        try {
            for (String tone : List.of("none", "reinhard", "aces")) {
                set(context, "tonemap", tone);
                context.runOnClient(c -> c.options.vignette().set(false));
                Path off = capture(context, tone + "-vignette-off", executor);
                context.runOnClient(c -> {
                    c.options.vignette().set(true);
                    // Exercise the strongest ordinary vignette, independent of local daylight.
                    c.gui.hud.vignetteBrightness = 1.0F;
                });
                Path on = capture(context, tone + "-vignette-on", executor);
                double difference = cornerDifference(off, on);
                System.out.println("Tone map vignette " + tone + ": bottomCornerMeanRgbDifference=" + difference);
                if (tone.equals("aces")) check(difference < 0.5, "ACES corner darkening remains: " + difference);
                else check(difference > 5, "vanilla vignette still works for " + tone + ": " + difference);
            }
            int warning = context.computeOnClient(c -> c.getSingleplayerServer().overworld().getWorldBorder().getWarningBlocks());
            try {
                command(context, "worldborder warning distance 60000000");
                context.waitTicks(4);
                Path borderOn = capture(context, "aces-border-warning", executor);
                context.runOnClient(c -> c.options.vignette().set(false));
                Path borderOff = capture(context, "aces-border-warning-off", executor);
                double difference = cornerDifference(borderOff, borderOn);
                check(difference > 5, "ACES preserves the world-border warning: " + difference);
                System.out.println("Tone map world-border warning: bottomCornerMeanRgbDifference=" + difference);
            } finally {
                command(context, "worldborder warning distance " + warning);
            }
            context.runOnClient(c -> c.options.vignette().set(true));
            sample(context, "aces-no-vignette-4k");
        } finally {
            context.runOnClient(c -> { if (!c.gui.hud.isHidden()) c.gui.hud.toggle(); neutral(ShaderPackRuntime.active()); });
            context.getInput().lookAt(135, 25);
        }
    }

    private static double cornerDifference(Path off, Path on) {
        try (var a = NativeImage.read(Files.newInputStream(off)); var b = NativeImage.read(Files.newInputStream(on))) {
            check(a.getWidth() == 3840 && a.getHeight() == 2160 && b.getWidth() == 3840 && b.getHeight() == 2160,
                "vignette comparison requires actual 4K captures");
            long difference = 0;
            int samples = 0;
            for (int y = 1800; y < 2100; y += 8) for (int side : new int[]{64, 3392}) for (int x = side; x < side + 384; x += 8) {
                int ap = a.getPixel(x, y), bp = b.getPixel(x, y);
                difference += Math.abs(net.minecraft.util.ARGB.red(ap) - net.minecraft.util.ARGB.red(bp))
                    + Math.abs(net.minecraft.util.ARGB.green(ap) - net.minecraft.util.ARGB.green(bp))
                    + Math.abs(net.minecraft.util.ARGB.blue(ap) - net.minecraft.util.ARGB.blue(bp));
                samples += 3;
            }
            return difference / (double)samples;
        } catch (IOException error) { throw new AssertionError(error); }
    }

    private static void verifyMenu(ClientGameTestContext context, Object executor) {
        context.setScreen(() -> new MetalCraftOptionsScreen(null));
        press(context, Component.translatable("metalcraft.options.shaders.title").getString());
        press(context, "Color Grading");
        context.runOnClient(c -> {
            check("Color Grading".equals(c.gui.screen().getTitle().getString()), "Color Grading submenu opened");
            AbstractSliderButton temperature = (AbstractSliderButton)widgets(c.gui.screen()).stream()
                .filter(widget -> widget instanceof AbstractSliderButton && widget.getMessage().getString().startsWith("Temperature"))
                .findFirst().orElseThrow(() -> new AssertionError("Temperature slider exists"));
            temperature.onClick(new MouseButtonEvent(temperature.getX() + temperature.getWidth() * 0.75,
                temperature.getY() + 10, new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false);
            check(((Number)ShaderPackRuntime.active().optionValue("temperature")).doubleValue() > 0.1,
                "slider applies immediately");
            check(ShaderPackRuntime.active().executor().orElseThrow() == executor, "slider does not reload shader pack");
        });
        press(context, "Reset Color Grading");
        context.runOnClient(c -> {
            for (var option : ShaderPackRuntime.active().options()) if (GRADING.contains(option.id()))
                check(equal(ShaderPackRuntime.active().optionValue(option.id()), option.defaultValue()), "reset restores " + option.id());
            check(ShaderPackRuntime.active().executor().orElseThrow() == executor, "reset does not reload shader pack");
        });
        context.waitTicks(2);
        context.takeScreenshot("metalcraft-color-grading-menu");
        press(context, Component.translatable("gui.back").getString());
        context.runOnClient(c -> check(c.gui.screen().getTitle().getString()
            .equals(Component.translatable("metalcraft.options.shaders.title").getString()), "Back returns to Shader Packs"));
    }

    private static List<AbstractWidget> widgets(ContainerEventHandler parent) {
        var found = new java.util.ArrayList<AbstractWidget>();
        for (GuiEventListener child : parent.children()) {
            if (child instanceof AbstractWidget widget) found.add(widget);
            if (child instanceof ContainerEventHandler container) found.addAll(widgets(container));
        }
        return found;
    }

    private static void press(ClientGameTestContext context, String label) {
        context.runOnClient(c -> {
            Button button = (Button)widgets(c.gui.screen()).stream().filter(widget -> widget instanceof Button
                && widget.getMessage().getString().startsWith(label)).findFirst()
                .orElseThrow(() -> new AssertionError("Button not found: " + label));
            check(button.active, "Button is enabled: " + label);
            button.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
        });
    }

    private static void neutral(ShaderPackRuntime runtime) {
        for (var option : runtime.options()) if (GRADING.contains(option.id())) runtime.setOption(option.id(), option.defaultValue());
    }

    private static void set(ClientGameTestContext context, String id, Object value) {
        context.runOnClient(c -> ShaderPackRuntime.active().setOption(id, value));
    }

    private static Path capture(ClientGameTestContext context, String name, Object executor) {
        context.waitTicks(4);
        context.runOnClient(c -> {
            var runtime = ShaderPackRuntime.active();
            check(runtime.isActive() && runtime.lastError().isEmpty(), "healthy shader runtime: " + runtime.lastError());
            check(runtime.executor().orElseThrow() == executor, "uniform change preserves executor: " + name);
            dimensions(name);
            c.gui.hud.getChat().clearMessages(true);
        });
        return context.takeScreenshot("metalcraft-color-grading-" + name);
    }

    private static void dimensions(String name) {
        var c = net.minecraft.client.Minecraft.getInstance();
        var target = c.gameRenderer.mainRenderTarget().getColorTextureView();
        var runtime = ShaderPackRuntime.active();
        int[] drawable = MetalSurfaceProbe.drawableSize();
        check(target.getWidth(0) == 3840 && target.getHeight(0) == 2160, "4K actual world target required");
        check(runtime.frameWidth() == 3840 && runtime.frameHeight() == 2160, "4K shader scene attachments required");
        check(drawable[0] > 0 && drawable[1] > 0, "native drawable dimensions available");
        System.out.println("Color grading " + name + ": worldTarget=" + target.getWidth(0) + "x" + target.getHeight(0)
            + ", shaderScene=" + runtime.frameWidth() + "x" + runtime.frameHeight()
            + ", presentationFramebuffer=" + c.getWindow().getWidth() + "x" + c.getWindow().getHeight()
            + ", windowPoints=" + c.getWindow().getScreenWidth() + "x" + c.getWindow().getScreenHeight()
            + ", drawable=" + drawable[0] + "x" + drawable[1]);
    }

    private static void sample(ClientGameTestContext context, String name) {
        context.runOnClient(c -> { dimensions(name); MetalFrameMetrics.beginCapture(8); });
        context.waitTicks(30);
        var phase = context.computeOnClient(c -> MetalFrameMetrics.endCapture(name));
        check(phase.frames() > 0, "live frame sample exists: " + name);
        System.out.println("Color grading timing smoke: " + phase.toLogLine() + " gpuMedianMs=" + phase.gpuFrame().p50Ms());
    }

    private static void compare(Path neutral, Path warm, Path cool) {
        try (var ni = Files.newInputStream(neutral); var wi = Files.newInputStream(warm); var ci = Files.newInputStream(cool);
             var n = NativeImage.read(ni); var w = NativeImage.read(wi); var c = NativeImage.read(ci)) {
            check(n.getWidth() == 3840 && n.getHeight() == 2160, "screenshot pixels match world target");
            long warmShift = 0, coolShift = 0;
            int samples = 0, changed = 0;
            for (int y = n.getHeight()/5; y < n.getHeight()*4/5; y += 8) for (int x = n.getWidth()/5; x < n.getWidth()*4/5; x += 8) {
                int np = n.getPixel(x, y), wp = w.getPixel(x, y), cp = c.getPixel(x, y);
                warmShift += redBlue(wp) - redBlue(np); coolShift += redBlue(cp) - redBlue(np);
                if (Math.abs(redBlue(wp)-redBlue(cp)) > 3) changed++;
                samples++;
            }
            check(changed > samples/10 && warmShift > samples && coolShift < -samples,
                "warm/cool uniforms visibly shift world color: warm=" + warmShift + ", cool=" + coolShift + ", changed=" + changed);
            int marker = n.getPixel(n.getWidth()*8/100, n.getHeight()*8/100);
            check(Math.abs(net.minecraft.util.ARGB.red(marker)-127) <= 1
                && Math.abs(net.minecraft.util.ARGB.green(marker)-63) <= 1
                && Math.abs(net.minecraft.util.ARGB.blue(marker)-191) <= 1, "opaque GUI exclusion swatch is actually present");
            for (int y = n.getHeight()*7/100; y < n.getHeight()/10; y += 4) for (int x = n.getWidth()*7/100; x < n.getWidth()/10; x += 4) {
                check(n.getPixel(x,y) == w.getPixel(x,y) && n.getPixel(x,y) == c.getPixel(x,y), "GUI swatch excluded from world grading");
            }
            System.out.println("Color grading pixel smoke: samples=" + samples + ", changed=" + changed
                + ", warmRedBlueDelta=" + warmShift/(double)samples + ", coolRedBlueDelta=" + coolShift/(double)samples);
        } catch (IOException error) { throw new AssertionError(error); }
    }

    private static int redBlue(int pixel) { return net.minecraft.util.ARGB.red(pixel) - net.minecraft.util.ARGB.blue(pixel); }
    private static boolean equal(Object a, Object b) {
        return a instanceof Number an && b instanceof Number bn ? an.doubleValue() == bn.doubleValue() : a.equals(b);
    }
    private static void command(ClientGameTestContext context, String text) {
        var done = context.computeOnClient(c -> c.getSingleplayerServer().submit(() -> c.getSingleplayerServer()
            .getCommands().performPrefixedCommand(c.getSingleplayerServer().createCommandSourceStack(), text)));
        context.waitFor(c -> done.isDone()); done.join();
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
    private record ClientSettings(String pack, boolean half, boolean lod, int render, int simulation, boolean bob,
        CloudStatus clouds, boolean hudHidden, boolean vignette, int width, int height, boolean fullscreen) {}

    /** A fixed opaque overlay validates the GUI boundary while keeping natural terrain visible. */
    private static final class GuiSwatch extends Screen {
        GuiSwatch() { super(Component.literal("Color grading GUI exclusion probe")); }
        @Override public boolean isPauseScreen() { return false; }
        @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(width/20, height/20, width/8, height/8, 0xFF7F3FBF);
        }
    }
}
