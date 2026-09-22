package dev.metalcraft.client.test;

import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.gui.MetalCraftLodOptionsScreen;
import dev.metalcraft.client.gui.MetalCraftOptionsScreen;
import dev.metalcraft.client.lod.LodSettings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.TitleScreen;
import org.lwjgl.glfw.GLFW;

/** Exercises persistence and the actual screen with keyboard navigation in an isolated client. */
final class MetalLodSettingsGameTest {
    static void run(ClientGameTestContext context) {
        LodSettings original = MetalCraftConfig.lod();
        int expectedRadius = Integer.getInteger("metalcraft.lodExpectedRadius", -1);
        if (expectedRadius >= 0) check(original.fullDetailChunks() == expectedRadius, "persisted preferences loaded at process startup");
        boolean originalHalf = MetalCraftConfig.halfResolution();
        String pack = context.computeOnClient(c -> ShaderPackRuntime.active() == null ? "" : ShaderPackRuntime.active().selectedPackId());
        try {
            LodSettings custom = original.withGeometry(9, 1.5).withEnabled(false).withGeneration(false);
            MetalCraftConfig.setLod(custom);
            MetalCraftConfig.reload();
            check(MetalCraftConfig.lod().equals(custom), "disk reload round trip");
            var config = FabricLoader.getInstance().getConfigDir().resolve("metalcraft.json");
            try {
                var json = com.google.gson.JsonParser.parseString(Files.readString(config)).getAsJsonObject();
                json.getAsJsonObject("lod").addProperty("errorPixels", "invalid");
                Files.writeString(config, json.toString());
            } catch (java.io.IOException error) { throw new AssertionError(error); }
            MetalCraftConfig.reload();
            check(MetalCraftConfig.lod().errorPixels() == 2 && MetalCraftConfig.lod().fullDetailChunks() == 9,
                    "malformed field keeps unrelated settings");
            check(MetalCraftConfig.halfResolution() == originalHalf, "global scale independent");

            context.setScreen(() -> new MetalCraftOptionsScreen(new TitleScreen()));
            focusButton(context, "Level of Detail…");
            context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
            context.waitForScreen(MetalCraftLodOptionsScreen.class);
            context.getInput().resizeWindow(640, 480);
            context.waitTicks(5);
            context.takeScreenshot("metalcraft-lod-settings-small");
            context.runOnClient(c -> {
                var screen = c.gui.screen();
                boolean available = dev.metalcraft.client.lod.LodCapabilities.current(true).geometry();
                boolean horizon = dev.metalcraft.client.lod.LodCapabilities.current(true).extendedHorizon();
                check(widgets(screen).stream().filter(e -> e instanceof CycleButton<?>).count() == 4 + (available ? 3 : 0) + (horizon ? 4 : 0),
                        "resize does not duplicate option widgets");
                boolean disabled = widgets(screen).stream().filter(e -> e instanceof CycleButton<?>).map(e -> (CycleButton<?>)e)
                        .anyMatch(b -> !b.active && b.getMessage().getString().contains("Enable terrain LOD"));
                check(disabled != available, "rendering control matches current capability");
            });
            if (context.computeOnClient(c -> dev.metalcraft.client.lod.LodCapabilities.current(true).geometry())) {
                focusButton(context, "Enable terrain LOD");
                context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
                context.waitTicks(2);
                check(MetalCraftConfig.lod().enabled(), "keyboard enables the opt-in preview without a development flag");
                focusButton(context, "Generate distant terrain");
                context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
                context.waitTicks(2);
                check(MetalCraftConfig.lod().generateTerrain(), "keyboard enables single-player generation preference");
            }
            focusButton(context, "Reset to Defaults");
            context.takeScreenshot("metalcraft-lod-settings-keyboard-reset");
            context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
            context.waitTicks(2);
            check(MetalCraftConfig.lod().equals(LodSettings.defaults()), "keyboard reset restores defaults");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitForScreen(MetalCraftOptionsScreen.class);
            context.runOnClient(c -> check(widgets(c.gui.screen()).stream()
                    .filter(w -> w instanceof Button && w.getMessage().getString().equals("Level of Detail…")).count() == 1,
                    "back navigation does not duplicate the parent layout"));
            check(pack.equals(context.computeOnClient(c -> ShaderPackRuntime.active() == null ? "" : ShaderPackRuntime.active().selectedPackId())), "pack selection unchanged");
            context.runOnClient(c -> {
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID);
                check(MetalCraftConfig.lod().equals(LodSettings.defaults()), "pack activation does not replace LOD preferences");
                ShaderPackRuntime.active().selectPack(ShaderPackRuntime.NONE_ID);
                check(MetalCraftConfig.lod().equals(LodSettings.defaults()), "pack deactivation does not replace LOD preferences");
                ShaderPackRuntime.active().selectPack(pack);
            });
            System.out.println("LOD settings UI passed: disk reload, malformed recovery, small window, keyboard reset/back and pack independence");
        } finally {
            MetalCraftConfig.setLod(original);
            context.runOnClient(c -> {
                if (ShaderPackRuntime.active() != null && !pack.equals(ShaderPackRuntime.active().selectedPackId())) ShaderPackRuntime.active().selectPack(pack);
            });
            context.setScreen(TitleScreen::new);
        }
    }

    private static java.util.List<AbstractWidget> widgets(net.minecraft.client.gui.components.events.ContainerEventHandler root) {
        java.util.List<AbstractWidget> result = new java.util.ArrayList<>();
        for (var child : root.children()) {
            if (child instanceof AbstractWidget widget) result.add(widget);
            if (child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler container) result.addAll(widgets(container));
        }
        return result;
    }

    private static void focusButton(ClientGameTestContext context, String label) {
        for (int i = 0; i < 40; i++) {
            context.getInput().pressKey(GLFW.GLFW_KEY_TAB);
            context.waitTick();
            boolean focused = context.computeOnClient(c -> widgets(c.gui.screen()).stream()
                    .anyMatch(w -> w.isFocused() && w.getMessage().getString().startsWith(label)));
            if (focused) return;
        }
        throw new AssertionError("Keyboard could not reach " + label);
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
