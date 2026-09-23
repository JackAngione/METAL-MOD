package dev.metalcraft.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.chunk.NativeLodSelection;
import dev.metalcraft.client.lod.LodCapabilities;
import dev.metalcraft.client.lod.LodSettings;
import java.util.Locale;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LayoutSettings;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Terrain and distance settings, including native detail and staged extended LOD. */
public final class MetalCraftLodOptionsScreen extends Screen {
    private final Screen parent;
    private HeaderAndFooterLayout layout;

    public MetalCraftLodOptionsScreen(Screen parent) {
        super(Component.translatable("metalcraft.options.terrain.title"));
        this.parent = parent;
    }

    private static Component text(String key) { return Component.translatable("metalcraft.lod." + key); }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this);
        layout.addTitleHeader(title, font);
        int width = Math.max(150, Math.min(310, this.width - 40));
        LinearLayout rows = LinearLayout.vertical().spacing(8);
        rows.defaultCellSetting().alignHorizontallyCenter();
        rows.addChild(new StringWidget(Component.translatable("metalcraft.options.terrain.native_section"), font));
        addNativeTerrainControls(rows, width);
        rows.addChild(new StringWidget(Component.translatable("metalcraft.options.terrain.extended_section"), font));
        LodSettings settings = MetalCraftConfig.lod();
        LodCapabilities capabilities = LodCapabilities.current("Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()));
        String reason = !capabilities.metal() ? "requires_metal" : capabilities.geometry() ? "experimental_geometry"
                : LodCapabilities.GEOMETRY_AVAILABLE ? "unsupported_pack" : "pending_geometry";
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
        if (capabilities.geometry()) {
            rows.addChild(CycleButton.onOffBuilder(settings.smoothTransitions())
                    .withTooltip(v -> Tooltip.create(text("smoothing.tooltip")))
                    .create(0, 0, width, 20, text("smoothing"), (b, v) -> {
                        var current = MetalCraftConfig.lod();
                        MetalCraftConfig.setLod(current.withRuntimeLimits(v, current.meshBudgetMiB(), current.backgroundWork()));
                    }));
            rows.addChild(CycleButton.<Integer>builder(v -> v == 0 ? text("automatic") : Component.translatable("metalcraft.lod.memory_mib", v), settings.meshBudgetMiB())
                    .withValues(0, 128, 256, 512, 1024, 2048)
                    .withTooltip(v -> Tooltip.create(text("memory.tooltip")))
                    .create(0, 0, width, 20, text("memory"), (b, v) -> {
                        var current = MetalCraftConfig.lod();
                        MetalCraftConfig.setLod(current.withRuntimeLimits(current.smoothTransitions(), v, current.backgroundWork()));
                    }));
            rows.addChild(CycleButton.<LodSettings.Work>builder(v -> text("work." + v.name().toLowerCase(Locale.ROOT)), settings.backgroundWork())
                    .withValues(LodSettings.Work.values())
                    .withTooltip(v -> Tooltip.create(text("work.tooltip")))
                    .create(0, 0, width, 20, text("work"), (b, v) -> {
                        var current = MetalCraftConfig.lod();
                        MetalCraftConfig.setLod(current.withRuntimeLimits(current.smoothTransitions(), current.meshBudgetMiB(), v));
                    }));
        }
        if (capabilities.extendedHorizon()) {
            rows.addChild(new MultiLineTextWidget(text("horizon.tooltip"), font).setMaxWidth(width).setCentered(true));
            var generation=CycleButton.onOffBuilder(settings.generateTerrain())
                    .withTooltip(v -> Tooltip.create(text("generation.tooltip")))
                    .create(0,0,width,20,text("generation"),(b,v) -> MetalCraftConfig.setLod(MetalCraftConfig.lod().withGeneration(v)));
            generation.active=minecraft.level==null || minecraft.getSingleplayerServer()!=null;
            rows.addChild(generation);
            if(minecraft.level!=null && minecraft.getSingleplayerServer()==null)
                rows.addChild(new MultiLineTextWidget(text("generation.remote"),font).setMaxWidth(width).setCentered(true));
            rows.addChild(CycleButton.<Integer>builder(v -> Component.translatable("metalcraft.lod.chunks",v),settings.horizonChunks())
                    .withValues(16,32,64,128,256)
                    .create(0,0,width,20,text("horizon"),(b,v) -> {
                        var s=MetalCraftConfig.lod(); MetalCraftConfig.setLod(s.withHorizon(v,s.diskCache(),s.diskBudgetMiB()));
                    }));
            rows.addChild(CycleButton.onOffBuilder(settings.diskCache())
                    .create(0,0,width,20,text("disk_cache"),(b,v) -> {
                        var s=MetalCraftConfig.lod(); MetalCraftConfig.setLod(s.withHorizon(s.horizonChunks(),v,s.diskBudgetMiB()));
                    }));
            rows.addChild(CycleButton.<Integer>builder(v -> Component.translatable("metalcraft.lod.memory_mib",v),settings.diskBudgetMiB())
                    .withValues(512,1024,2048,4096,8192)
                    .create(0,0,width,20,text("disk_budget"),(b,v) -> {
                        var s=MetalCraftConfig.lod(); MetalCraftConfig.setLod(s.withHorizon(s.horizonChunks(),s.diskCache(),v));
                    }));
            rows.addChild(Button.builder(text("clear_cache"),b -> dev.metalcraft.client.lod.LodDistantRenderer.clearCache()).width(width).build());
        }
        rows.addChild(new MultiLineTextWidget(text("pending_stages"), font).setMaxWidth(width).setCentered(true));
        rows.addChild(Button.builder(text("reset"), b -> {
            MetalCraftConfig.setLod(LodSettings.defaults());
            minecraft.gui.setScreen(new MetalCraftLodOptionsScreen(parent));
        }).width(width).build());
        ScrollableLayout scroll = new ScrollableLayout(minecraft, rows, Math.max(40, height - 70));
        scroll.setMinWidth(width + 16);
        layout.addToContents(scroll, LayoutSettings::alignHorizontallyCenter);
        layout.addToFooter(Button.builder(Component.translatable("gui.back"), b -> onClose()).width(Math.min(200, width)).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    private void addNativeTerrainControls(LinearLayout rows, int width) {
        rows.addChild(CycleButton.onOffBuilder(MetalCraftConfig.nativeTerrainLod())
                .withTooltip(v -> Tooltip.create(Component.translatable("metalcraft.options.native_lod.tooltip")))
                .create(0, 0, width, 20, Component.translatable("metalcraft.options.native_lod"),
                        (b, v) -> MetalCraftConfig.setNativeTerrainLod(v)));
        rows.addChild(CycleButton.<Integer>builder(
                v -> Component.translatable("metalcraft.options.native_lod_reduction." + v), MetalCraftConfig.nativeLodReduction())
                .withValues(0, 1, 2, 3, 4, 5)
                .withTooltip(v -> Tooltip.create(Component.translatable("metalcraft.options.native_lod_reduction.tooltip")))
                .create(0, 0, width, 20, Component.translatable("metalcraft.options.native_lod_reduction"),
                        (b, v) -> MetalCraftConfig.setNativeLodReduction(v)));
        rows.addChild(nativeQualityDistanceSlider(width));
        rows.addChild(CycleButton.<Integer>builder(
                v -> Component.translatable("metalcraft.options.horizon_detail." + v), MetalCraftConfig.horizonDetail())
                .withValues(1, 2, 3, 4, 5)
                .withTooltip(v -> Tooltip.create(Component.translatable("metalcraft.options.horizon_detail.tooltip")))
                .create(0, 0, width, 20, Component.translatable("metalcraft.options.horizon_detail"),
                        (b, v) -> MetalCraftConfig.setHorizonDetail(v)));
        rows.addChild(CycleButton.onOffBuilder(MetalCraftConfig.nativeLodPixels())
                .withTooltip(v -> Tooltip.create(Component.translatable("metalcraft.options.native_lod_pixels.tooltip")))
                .create(0, 0, width, 20, Component.translatable("metalcraft.options.native_lod_pixels"),
                        (b, v) -> MetalCraftConfig.setNativeLodPixels(v)));
        rows.addChild(CycleButton.onOffBuilder(MetalCraftConfig.clearDistanceFog())
                .withTooltip(v -> Tooltip.create(Component.translatable("metalcraft.options.clear_distance_fog.tooltip")))
                .create(0, 0, width, 20, Component.translatable("metalcraft.options.clear_distance_fog"),
                        (b, v) -> MetalCraftConfig.setClearDistanceFog(v)));
        rows.addChild(new MultiLineTextWidget(Component.translatable("metalcraft.options.native_distance"), font)
                .setMaxWidth(width).setCentered(true));
    }

    private AbstractSliderButton nativeQualityDistanceSlider(int width) {
        int minimum = NativeLodSelection.MIN_NATIVE_DISTANCE;
        int span = NativeLodSelection.MAX_NATIVE_DISTANCE - minimum;
        AbstractSliderButton slider = new AbstractSliderButton(0, 0, width, 20,
                Component.translatable("metalcraft.options.native_quality_distance", MetalCraftConfig.nativeQualityDistance()),
                (MetalCraftConfig.nativeQualityDistance() - minimum) / (double)span) {
            private int chunks() { return minimum + (int)Math.round(this.value * span); }

            @Override
            protected void updateMessage() {
                this.setMessage(Component.translatable("metalcraft.options.native_quality_distance", chunks()));
            }

            @Override
            protected void applyValue() {
                int chunks = chunks();
                this.value = (chunks - minimum) / (double)span;
                MetalCraftConfig.setNativeQualityDistance(chunks);
            }
        };
        slider.setTooltip(Tooltip.create(Component.translatable("metalcraft.options.native_quality_distance.tooltip")));
        return slider;
    }

    @Override protected void repositionElements() { if(layout != null) layout.arrangeElements(); }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
}
