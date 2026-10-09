package dev.metalcraft.client.gui.settings;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.api.MetalCraftShaderPackInfo;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.MetalCraftPlatform;
import dev.metalcraft.client.MetalCraftRenderResolution;
import dev.metalcraft.client.lod.LodSettings;
import dev.metalcraft.client.lod.LodSystem;
import dev.metalcraft.client.shader.ShaderPack;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** The mod's settings catalog. Add/remove definitions here; navigation, search and controls follow. */
public final class MetalCraftSettings {
    private static final BooleanSupplier ALWAYS = () -> true;
    private MetalCraftSettings() {}
    public static Component text(String key, Object... args) { return Component.translatable(key, args); }
    private static Component ui(String key, Object... args) { return text("metalcraft.settings." + key, args); }

    public static List<SettingsPage> pages(Minecraft client, Consumer<String> navigate, Runnable reload) {
        var pages = new ArrayList<SettingsPage>();
        pages.add(new SettingsPage("display", text("metalcraft.options.display.title"), ui("nav.display"), ui("display.description"), 0xFF4799FF, "D", List.of(
            toggle("half_resolution", ui("group.rendering"), text("metalcraft.options.half_resolution"),
                text("metalcraft.options.half_resolution.description"), MetalCraftPlatform::isAppleSilicon,
                MetalCraftConfig::halfResolution, value -> { MetalCraftConfig.setHalfResolution(value); MetalCraftRenderResolution.apply(client); }),
            toggle("unlocked_frame_rate", ui("group.rendering"), text("metalcraft.options.unlocked_frame_rate"),
                text("metalcraft.options.unlocked_frame_rate.tooltip"), ALWAYS, MetalCraftConfig::unlockedFrameRate,
                value -> { MetalCraftConfig.setUnlockedFrameRate(value); client.invalidateSurfaceConfiguration(); }),
            new Setting("resolution", ui("group.live"), () -> {
                var target = client.gameRenderer.mainRenderTarget().getColorTextureView();
                return ui("resolution", target.getWidth(0), target.getHeight(0));
            }, ui("resolution.description"), ALWAYS, new Setting.Info())
        )));
        pages.add(new SettingsPage("terrain", text("metalcraft.options.terrain.title"), ui("nav.terrain"), ui("terrain.description"), 0xFF54BC99, "T", List.of(
            toggle("lod_enabled", ui("group.distance"), text("metalcraft.lod.enabled"), text("metalcraft.lod.enabled.tooltip"),
                ALWAYS, MetalCraftConfig::lodEnabled, MetalCraftConfig::setLodEnabled),
            new Setting("native_distance", ui("group.distance"), () -> text("metalcraft.lod.native_distance", MetalCraftConfig.lodNativeDistance()),
                text("metalcraft.lod.native_distance.tooltip"), MetalCraftConfig::lodEnabled,
                new Setting.Slider(LodSettings.MIN_NATIVE_DISTANCE, LodSettings.MAX_NATIVE_DISTANCE, 1,
                    MetalCraftConfig::lodNativeDistance, value -> MetalCraftConfig.setLodNativeDistance(value.intValue()))),
            new Setting("lod_detail", ui("group.distance"), () -> text("metalcraft.lod.detail", text("metalcraft.lod.detail." + MetalCraftConfig.lodDetail())),
                text("metalcraft.lod.detail.tooltip"), MetalCraftConfig::lodEnabled,
                new Setting.Slider(LodSettings.MIN_DETAIL, LodSettings.MAX_DETAIL, 1, MetalCraftConfig::lodDetail,
                    value -> MetalCraftConfig.setLodDetail(value.intValue()))),
            toggle("clear_distance_fog", ui("group.atmosphere"), text("metalcraft.options.clear_distance_fog"),
                text("metalcraft.options.clear_distance_fog.tooltip"), ALWAYS, MetalCraftConfig::clearDistanceFog, MetalCraftConfig::setClearDistanceFog),
            new Setting("terrain_status", ui("group.live"), () -> terrainStatus(client), text("metalcraft.lod.limits"), ALWAYS, new Setting.Info()),
            new Setting("view_distance", ui("group.live"), () -> ui("view_distance", client.options.renderDistance().get()),
                text("metalcraft.lod.summary", client.options.renderDistance().get()), ALWAYS, new Setting.Info())
        )));
        var runtime = ShaderPackRuntime.active();
        var shaderRows = new ArrayList<Setting>();
        shaderRows.add(new Setting("shader_pack", ui("group.pack"), () -> text("metalcraft.options.shader_pack",
            runtime == null || ShaderPackRuntime.NONE_ID.equals(runtime.selectedPackId()) ? text("metalcraft.options.shader_pack_none") : runtime.selectedPackName()),
            ui("pack.description"), () -> MetalCraftPlatform.isAppleSilicon() && runtime != null, new Setting.Action(() -> {
                var packs = new ArrayList<MetalCraftShaderPackInfo>();
                packs.add(new MetalCraftShaderPackInfo(ShaderPackRuntime.NONE_ID, "None"));
                packs.addAll(runtime.availablePacks());
                int current = 0;
                for (int i = 0; i < packs.size(); i++) if (packs.get(i).id().equals(runtime.selectedPackId())) current = i;
                try { runtime.selectPack(packs.get((current + 1) % packs.size()).id()); }
                finally { reload.run(); }
            })));
        var categories = new LinkedHashSet<String>();
        if (runtime != null && MetalCraftPlatform.isAppleSilicon()) {
            for (var option : runtime.options()) categories.add(option.category());
            if (categories.remove("debug")) categories.add("debug");
            for (String category : categories) {
                shaderRows.add(new Setting("category." + category, ui("group.adjustments"), () -> ShaderSettingLabels.categoryLabel(category),
                    ui("category.description"), ALWAYS, new Setting.Action(() -> navigate.accept("shader." + category))));
            }
        }
        if (categories.isEmpty()) shaderRows.add(new Setting("no_options", ui("group.adjustments"),
            () -> text("metalcraft.options.shader_no_options"), ui("pack.empty"), ALWAYS, new Setting.Info()));
        pages.add(new SettingsPage("shaders", text("metalcraft.options.shaders.title"), ui("shaders.description"), 0xFFB79AFF, "S", shaderRows));
        for (String category : categories) {
            var settings = new ArrayList<Setting>();
            for (var option : runtime.options()) {
                if (!category.equals(option.category())) continue;
                Setting.Control control = switch (option.type()) {
                    case BOOL -> new Setting.Toggle(() -> (Boolean)runtime.optionValue(option.id()), value -> runtime.setOption(option.id(), value));
                    case ENUM -> new Setting.Action(() -> {
                        int index = option.values().indexOf(runtime.optionValue(option.id()));
                        runtime.setOption(option.id(), option.values().get((index + 1) % option.values().size()));
                    });
                    case INT, FLOAT -> new Setting.Slider(option.min().orElseThrow(), option.max().orElseThrow(),
                        option.step().orElse(option.type() == ShaderPack.OptionType.INT ? 1 : (option.max().orElseThrow() - option.min().orElseThrow()) / 100),
                        () -> (Number)runtime.optionValue(option.id()), value -> {
                            if (option.type() == ShaderPack.OptionType.INT) runtime.setOption(option.id(), (int)Math.round(value));
                            else runtime.setOption(option.id(), value);
                        });
                };
                Component group = ShaderSettingLabels.isStandardGradingOption(runtime, option)
                    ? text("metalcraft.grading.group." + ShaderSettingLabels.gradingGroup(option.id())) : ui("group.adjustments");
                settings.add(new Setting("shader." + option.id(), group, () -> ShaderSettingLabels.optionMessage(runtime, option),
                    ShaderSettingLabels.description(runtime, option), ALWAYS, control));
            }
            settings.add(new Setting("reset." + category, ui("group.defaults"),
                () -> "tonemap".equals(category) && ShaderSettingLabels.isStandardPack(runtime)
                    ? text("metalcraft.grading.reset") : ui("reset", ShaderSettingLabels.categoryLabel(category)),
                ui("reset.description"), ALWAYS, new Setting.Action(() -> {
                    for (var option : runtime.options()) if (category.equals(option.category())) runtime.setOption(option.id(), option.defaultValue());
                })));
            pages.add(new SettingsPage("shader." + category, ShaderSettingLabels.categoryLabel(category),
                "tonemap".equals(category) && ShaderSettingLabels.isStandardPack(runtime)
                    ? text("metalcraft.grading.description") : ui("shader.description", runtime.selectedPackName()),
                0xFFB79AFF, "S", settings));
        }
        return List.copyOf(pages);
    }

    private static Setting toggle(String id, Component group, Component label, Component description,
                                  BooleanSupplier enabled, BooleanSupplier value, Consumer<Boolean> save) {
        return new Setting(id, group, () -> label, description, enabled, new Setting.Toggle(value, save));
    }

    private static Component terrainStatus(Minecraft client) {
        var status = LodSystem.status();
        String key = "metalcraft.lod.status." + status.name().toLowerCase(Locale.ROOT);
        return switch (status) {
            case ACTIVE -> {
                var stats = LodSystem.stats();
                int remaining = stats.pending + stats.inFlight;
                yield remaining > 0 ? text("metalcraft.lod.status.building", remaining, stats.drawnNodes, Math.round(stats.gpuBytes / 1048576.0))
                    : text(key, stats.drawnNodes, Math.round(stats.gpuBytes / 1048576.0));
            }
            case NOT_METAL -> text(key, RenderSystem.getDevice().getDeviceInfo().backendName());
            case RENDER_DISTANCE -> text(key, client.options.renderDistance().get(), MetalCraftConfig.lodNativeDistance());
            default -> text(key);
        };
    }
}
