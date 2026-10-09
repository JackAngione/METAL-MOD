package dev.metalcraft.client.test;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.gui.MetalCraftOptionsScreen;
import dev.metalcraft.client.metal.MetalSurfaceProbe;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractScrollArea;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import org.lwjgl.glfw.GLFW;

/** Short settings integration route in an existing standard-world copy. */
final class MetalSettingsGameTest {
    static void run(ClientGameTestContext context) {
        String world = System.getProperty("metalcraft.settingsTestWorld");
        check(world != null && !world.isBlank(), "Supply an existing standard-world copy");
        try {
            context.runOnClient(c -> {
                check("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), "default backend is Metal");
                check(c.getLevelSource().levelExists(world), "existing world is available");
                MetalCraftConfig.setHalfResolution(false);
                MetalCraftConfig.setLodEnabled(false);
                c.options.renderDistance().set(16);
                c.options.simulationDistance().set(16);
                c.options.guiScale().set(4);
                c.resizeGui();
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID);
                c.createWorldOpenFlows().openWorld(world, () -> {});
            });
            context.getInput().resizeWindow(3840, 2160);
            context.waitFor(c -> c.level != null && c.player != null && c.getSingleplayerServer() != null, 1200);
            check(context.computeOnClient(c -> c.getSingleplayerServer().overworld().getChunkSource().getGenerator()
                instanceof NoiseBasedChunkGenerator), "normal terrain required");
            context.runOnClient(c -> c.options.broadcastOptions());
            context.waitFor(c -> c.levelRenderer.visibleSections().stream().filter(section -> section.getSectionMesh()
                .getSectionDraw(net.minecraft.client.renderer.chunk.ChunkSectionLayer.SOLID) != null).count() >= 16, 600);
            context.waitTicks(10);
            context.runOnClient(c -> {
                var target = c.gameRenderer.mainRenderTarget().getColorTextureView();
                var runtime = ShaderPackRuntime.active();
                int[] drawable = MetalSurfaceProbe.drawableSize();
                check(target.getWidth(0) == 3840 && target.getHeight(0) == 2160, "actual world target is 4K");
                check(runtime.frameWidth() == 3840 && runtime.frameHeight() == 2160, "shader scene is 4K");
                System.out.println("Settings dimensions: world=3840x2160, shaderScene=" + runtime.frameWidth() + "x" + runtime.frameHeight()
                    + ", presentation=" + c.getWindow().getWidth() + "x" + c.getWindow().getHeight()
                    + ", drawable=" + drawable[0] + "x" + drawable[1]
                    + ", windowPoints=" + c.getWindow().getScreenWidth() + "x" + c.getWindow().getScreenHeight());
            });
            context.setScreen(() -> new MetalCraftOptionsScreen(null));
            capture(context, "display");
            context.runOnClient(c -> {
                boolean before = MetalCraftConfig.unlockedFrameRate();
                button(c.gui.screen(), "Unlocked frame rate").onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                check(MetalCraftConfig.unlockedFrameRate() != before, "switch changes stored value");
                MetalCraftConfig.reload();
                check(MetalCraftConfig.unlockedFrameRate() != before, "switch persists to disk");
                button(c.gui.screen(), "Unlocked frame rate").onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                c.gui.screen().keyPressed(new KeyEvent(GLFW.GLFW_KEY_F, 0, GLFW.GLFW_MOD_SUPER));
                check(search(c.gui.screen()).isFocused(), "Command-F focuses search");
                search(c.gui.screen()).setValue("temperature");
            });
            context.waitTicks(2);
            Object executor = context.computeOnClient(c -> ShaderPackRuntime.active().executor().orElseThrow());
            context.runOnClient(c -> {
                var slider = (AbstractSliderButton)widgets(c.gui.screen()).stream().filter(w -> w instanceof AbstractSliderButton).findFirst().orElseThrow();
                slider.onClick(new MouseButtonEvent(slider.getX() + slider.getWidth() * 0.75, slider.getY() + 56,
                    new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false);
                check(((Number)ShaderPackRuntime.active().optionValue("temperature")).doubleValue() > 0.1, "global search slider applies live");
                check(ShaderPackRuntime.active().executor().orElseThrow() == executor, "uniform slider preserves shader executor");
                double before = ((Number)ShaderPackRuntime.active().optionValue("temperature")).doubleValue();
                slider.setFocused(true);
                slider.keyPressed(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
                slider.keyPressed(new KeyEvent(GLFW.GLFW_KEY_RIGHT, 0, 0));
                check(((Number)ShaderPackRuntime.active().optionValue("temperature")).doubleValue() > before, "keyboard slider advances a manifest step");
            });
            capture(context, "search");
            context.runOnClient(c -> search(c.gui.screen()).setValue("no-such-setting-xyz"));
            context.waitTicks(2);
            context.runOnClient(c -> {
                check(widgets(c.gui.screen()).stream().noneMatch(w -> w instanceof AbstractSliderButton), "empty search removes controls");
                c.gui.screen().keyPressed(new KeyEvent(GLFW.GLFW_KEY_ESCAPE, 0, 0));
                check(search(c.gui.screen()).getValue().isEmpty(), "Escape clears search before leaving");
            });
            context.waitTicks(2);
            press(context, "Terrain & Distance");
            context.runOnClient(c -> check(widgets(c.gui.screen()).stream().filter(w -> w instanceof AbstractSliderButton)
                .noneMatch(w -> w.active), "terrain dependents disabled when LOD is off"));
            capture(context, "terrain");
            press(context, "Shader Packs");
            press(context, "Color Grading");
            context.runOnClient(c -> check(c.gui.screen().getTitle().getString().equals("Color Grading"), "manifest category navigation"));
            capture(context, "grading");
            context.runOnClient(c -> {
                var area = contentArea(c.gui.screen());
                check(c.gui.screen().mouseScrolled(area.getX() + 20, area.getY() + 20, 0, -100), "wheel scroll handled");
                var reset = button(c.gui.screen(), "Reset Color Grading");
                check(reset.getY() >= area.getY() && reset.getBottom() <= area.getBottom(), "reset reachable by scrolling");
                double position = area.scrollAmount();
                c.gui.screen().mouseClicked(new MouseButtonEvent(reset.getX() + 25, reset.getY() + 20,
                    new MouseButtonInfo(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0)), false);
                check(area.scrollAmount() == position, "reset preserves scroll position");
                search(c.gui.screen()).setValue("temperature");
            });
            context.waitTicks(2);
            context.runOnClient(c -> search(c.gui.screen()).setValue(""));
            context.waitTicks(2);
            context.runOnClient(c -> check(contentArea(c.gui.screen()).scrollAmount() > 0, "clearing search restores page scroll"));
            capture(context, "grading-defaults");
            context.runOnClient(c -> {
                for (var option : ShaderPackRuntime.active().options()) if (option.category().equals("tonemap")) {
                    Object actual = ShaderPackRuntime.active().optionValue(option.id()), expected = option.defaultValue();
                    check(actual instanceof Number a && expected instanceof Number b ? a.doubleValue() == b.doubleValue() : actual.equals(expected),
                        "category default restored: " + option.id());
                }
                check(ShaderPackRuntime.active().executor().orElseThrow() == executor, "reset preserves shader executor");
                c.options.guiScale().set(8); c.resizeGui();
            });
            capture(context, "compact");
            press(context, "Categories");
            capture(context, "compact-categories");
            press(context, "Color Grading");
            context.runOnClient(c -> {
                for (var widget : c.gui.screen().children()) if (widget instanceof AbstractWidget w)
                    check(w.getX() >= 0 && w.getRight() <= c.gui.screen().width && w.getY() >= 0 && w.getBottom() <= c.gui.screen().height,
                        "compact top-level control remains in viewport");
                c.options.guiScale().set(4); c.resizeGui();
            });
            press(context, "Back");
            press(context, "Shader pack:");
            context.runOnClient(c -> check(ShaderPackRuntime.NONE_ID.equals(ShaderPackRuntime.active().selectedPackId()), "shader pack cycles to None"));
            capture(context, "none");
            press(context, "Shader pack:");
            context.runOnClient(c -> check(ShaderPackRuntime.BUILTIN_ID.equals(ShaderPackRuntime.active().selectedPackId()), "shader pack cycles back to Standard"));
            press(context, "Display & Performance");
            context.runOnClient(c -> MetalFrameMetrics.beginCapture(8));
            context.waitTicks(30);
            var timing = context.computeOnClient(c -> MetalFrameMetrics.endCapture("settings-ui-4k"));
            check(timing.frames() > 0, "bounded live frame sample");
            System.out.println("Settings UI timing smoke: " + timing.toLogLine() + " gpuMedianMs=" + timing.gpuFrame().p50Ms());
            press(context, "Done");
            context.runOnClient(c -> check(c.gui.screen() == null, "Done returns to game"));
            System.out.println("Settings UI integration passed: Metal, standard world, actual 4K, 16/16, persistent toggle, search/empty/escape, mouse and keyboard slider, reset, responsive layout, pack switch, return to game.");
        } finally {
            context.runOnClient(c -> {
                MetalFrameMetrics.discardCapture();
                if (c.level != null) c.level.disconnect(Component.literal("Settings test complete"));
                c.disconnect(new TitleScreen(), false);
            });
            context.waitFor(c -> !net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl.isServerRunning && c.level == null, 1200);
            context.setScreen(TitleScreen::new);
        }
    }
    private static void capture(ClientGameTestContext context, String name) { context.waitTicks(2); context.takeScreenshot("metalcraft-settings-" + name); }
    private static EditBox search(ContainerEventHandler parent) { return (EditBox)widgets(parent).stream().filter(w -> w instanceof EditBox).findFirst().orElseThrow(); }
    private static AbstractScrollArea contentArea(ContainerEventHandler parent) {
        return parent.children().stream().filter(w -> w instanceof AbstractScrollArea).map(w -> (AbstractScrollArea)w).toList().getLast();
    }
    private static Button button(ContainerEventHandler parent, String label) { return (Button)widgets(parent).stream()
        .filter(w -> w instanceof Button && w.getMessage().getString().startsWith(label)).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label)); }
    private static void press(ClientGameTestContext context, String label) { context.runOnClient(c -> {
        Button b = button(c.gui.screen(), label); check(b.active, "enabled button: " + label); b.onPress(new KeyEvent(GLFW.GLFW_KEY_ENTER, 0, 0));
    }); }
    private static List<AbstractWidget> widgets(ContainerEventHandler parent) {
        var widgets = new ArrayList<AbstractWidget>();
        for (var child : parent.children()) {
            if (child instanceof AbstractWidget widget) widgets.add(widget);
            if (child instanceof ContainerEventHandler container) widgets.addAll(widgets(container));
        }
        return widgets;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
