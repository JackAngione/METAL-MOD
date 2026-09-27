package dev.metalcraft.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.lod.LodSettings;
import dev.metalcraft.client.lod.LodStats;
import dev.metalcraft.client.lod.LodSystem;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Distant terrain: total distance comes from Render Distance; native distance and detail are set here. */
public final class MetalCraftLodOptionsScreen extends Screen {
    private final Screen parent;
    private HeaderAndFooterLayout layout;
    private MultiLineTextWidget status;

    public MetalCraftLodOptionsScreen(Screen parent) {
        super(Component.translatable("metalcraft.options.terrain.title"));
        this.parent = parent;
    }

    private static Component text(String key, Object... arguments) { return Component.translatable("metalcraft.lod." + key, arguments); }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(title, font);
        int width = Math.max(150, Math.min(310, this.width - 40));
        LinearLayout rows = LinearLayout.vertical().spacing(8);
        rows.defaultCellSetting().alignHorizontallyCenter();
        rows.addChild(new MultiLineTextWidget(text("summary", minecraft.options.renderDistance().get()), font)
                .setMaxWidth(width).setCentered(true));
        status = new MultiLineTextWidget(statusText(), font).setMaxWidth(width).setCentered(true);
        rows.addChild(status);
        rows.addChild(CycleButton.onOffBuilder(MetalCraftConfig.lodEnabled())
                .withTooltip(v -> Tooltip.create(text("enabled.tooltip")))
                .create(0, 0, width, 20, text("enabled"), (b, v) -> MetalCraftConfig.setLodEnabled(v)));
        rows.addChild(slider(width, LodSettings.MIN_NATIVE_DISTANCE, LodSettings.MAX_NATIVE_DISTANCE, MetalCraftConfig.lodNativeDistance(),
                chunks -> text("native_distance", chunks), MetalCraftConfig::setLodNativeDistance, text("native_distance.tooltip")));
        rows.addChild(slider(width, LodSettings.MIN_DETAIL, LodSettings.MAX_DETAIL, MetalCraftConfig.lodDetail(),
                detail -> text("detail", Component.translatable("metalcraft.lod.detail." + detail)), MetalCraftConfig::setLodDetail,
                text("detail.tooltip")));
        rows.addChild(CycleButton.onOffBuilder(MetalCraftConfig.clearDistanceFog())
                .withTooltip(v -> Tooltip.create(Component.translatable("metalcraft.options.clear_distance_fog.tooltip")))
                .create(0, 0, width, 20, Component.translatable("metalcraft.options.clear_distance_fog"),
                        (b, v) -> MetalCraftConfig.setClearDistanceFog(v)));
        rows.addChild(new MultiLineTextWidget(text("limits"), font).setMaxWidth(width).setCentered(true));
        ScrollableLayout scroll = new ScrollableLayout(minecraft, rows, Math.max(40, height - 70));
        scroll.setMinWidth(width + 16);
        layout.addToContents(scroll, LayoutSettings::alignHorizontallyCenter);
        layout.addToFooter(Button.builder(Component.translatable("gui.back"), b -> onClose()).width(Math.min(200, width)).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    /** Slider over whole numbers from minimum to maximum inclusive. */
    private static AbstractSliderButton slider(int width, int minimum, int maximum, int current, IntFunction<Component> label,
                                               IntConsumer save, Component tooltip) {
        int span = maximum - minimum;
        AbstractSliderButton slider = new AbstractSliderButton(0, 0, width, 20, label.apply(current), (current - minimum) / (double)span) {
            private int value() { return minimum + (int)Math.round(this.value * span); }

            @Override
            protected void updateMessage() { this.setMessage(label.apply(this.value())); }

            @Override
            protected void applyValue() {
                int value = this.value();
                this.value = (value - minimum) / (double)span;
                save.accept(value);
            }
        };
        slider.setTooltip(Tooltip.create(tooltip));
        return slider;
    }

    /** Whether distant terrain is running now, and if not, what stops it. */
    private Component statusText() {
        var current = LodSystem.status();
        String key = "status." + current.name().toLowerCase(Locale.ROOT);
        return switch (current) {
            case ACTIVE -> {
                LodStats stats = LodSystem.stats();
                yield text(key, stats.drawnNodes, Math.round(stats.gpuBytes / 1048576.0));
            }
            case NOT_METAL -> text(key, RenderSystem.getDevice().getDeviceInfo().backendName());
            case RENDER_DISTANCE -> text(key, minecraft.options.renderDistance().get(), MetalCraftConfig.lodNativeDistance());
            default -> text(key);
        };
    }

    @Override
    public void tick() {
        super.tick();
        Component next = statusText();
        if (status != null && !next.equals(status.getMessage())) {
            status.setMessage(next);
            repositionElements();
        }
    }

    @Override protected void repositionElements() { if (layout != null) layout.arrangeElements(); }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
