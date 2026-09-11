package dev.metalcraft.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.lod.LodCapabilities;
import dev.metalcraft.client.lod.LodSettings;
import java.util.Locale;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Keyboard-accessible staged LOD settings, independent of shader-pack preferences. */
public final class MetalCraftLodOptionsScreen extends Screen {
    private final Screen parent;
    private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);

    public MetalCraftLodOptionsScreen(Screen parent) {
        super(text("title"));
        this.parent = parent;
    }

    private static Component text(String key) { return Component.translatable("metalcraft.lod." + key); }

    @Override
    protected void init() {
        layout.addTitleHeader(title, font);
        int width = Math.max(150, Math.min(310, this.width - 40));
        LinearLayout rows = LinearLayout.vertical().spacing(8);
        rows.defaultCellSetting().alignHorizontallyCenter();
        LodSettings settings = MetalCraftConfig.lod();
        LodCapabilities capabilities = LodCapabilities.current("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()));
        String reason = capabilities.metal() ? "pending_geometry" : "requires_metal";
        rows.addChild(new MultiLineTextWidget(text(reason), font).setMaxWidth(width).setCentered(true));
        var enabled = CycleButton.onOffBuilder(capabilities.effective(settings).enabled())
                .withTooltip(v -> Tooltip.create(text(reason)))
                .create(0, 0, width, 20, text("enabled"), (b, v) -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withEnabled(v)));
        enabled.active = capabilities.metal() && capabilities.geometry();
        rows.addChild(enabled);

        // Tuning values can be prepared now, but cannot enable an unavailable rendering stage.
        rows.addChild(CycleButton.<LodSettings.Preset>builder(v -> text("preset." + v.name().toLowerCase(Locale.ROOT)), settings.preset())
                .withValues(LodSettings.Preset.values())
                .withTooltip(v -> Tooltip.create(text("preset.tooltip")))
                .create(0, 0, width, 20, text("preset"), (b, v) -> {
                    MetalCraftConfig.setLod(MetalCraftConfig.lod().withPreset(v));
                    minecraft.gui.setScreen(new MetalCraftLodOptionsScreen(parent));
                }));
        rows.addChild(CycleButton.<Integer>builder(v -> Component.translatable("metalcraft.lod.chunks", v), settings.fullDetailChunks())
                .withValues(2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
                .withTooltip(v -> Tooltip.create(text("radius.tooltip")))
                .create(0, 0, width, 20, text("radius"), (b, v) -> {
                    MetalCraftConfig.setLod(MetalCraftConfig.lod().withGeometry(v, MetalCraftConfig.lod().errorPixels()));
                    minecraft.gui.setScreen(new MetalCraftLodOptionsScreen(parent));
                }));
        rows.addChild(CycleButton.<Double>builder(v -> Component.translatable("metalcraft.lod.pixels", v), settings.errorPixels())
                .withValues(0.5, 1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0)
                .withTooltip(v -> Tooltip.create(text("error.tooltip")))
                .create(0, 0, width, 20, text("error"), (b, v) -> {
                    MetalCraftConfig.setLod(MetalCraftConfig.lod().withGeometry(MetalCraftConfig.lod().fullDetailChunks(), v));
                    minecraft.gui.setScreen(new MetalCraftLodOptionsScreen(parent));
                }));
        rows.addChild(new MultiLineTextWidget(text("pending_stages"), font).setMaxWidth(width).setCentered(true));
        rows.addChild(Button.builder(text("reset"), b -> {
            MetalCraftConfig.setLod(LodSettings.defaults());
            minecraft.gui.setScreen(new MetalCraftLodOptionsScreen(parent));
        }).width(width).build());
        ScrollableLayout scroll = new ScrollableLayout(minecraft, rows, Math.max(40, height - 70));
        scroll.setMinWidth(width + 16);
        layout.addToContents(scroll, LayoutSettings::alignHorizontallyCenter);
        layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(Math.min(200, width)).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override protected void repositionElements() { layout.arrangeElements(); }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
