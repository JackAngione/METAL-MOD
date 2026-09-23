package dev.metalcraft.client.gui;

import com.mojang.blaze3d.platform.Window;
import dev.metalcraft.api.MetalCraftShaderPackInfo;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.MetalCraftPlatform;
import dev.metalcraft.client.MetalCraftRenderResolution;
import dev.metalcraft.client.shader.ShaderPack;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** MetalCraft's renderer settings screen. */
public final class MetalCraftOptionsScreen extends Screen {
	private static final Component TITLE = Component.translatable("metalcraft.options.title");
	private enum Page { HOME, DISPLAY, SHADERS, SHADER_CATEGORY }
	private final Page page;
	private final String category;
	private HeaderAndFooterLayout layout;
	private final Screen lastScreen;
	private StringWidget resolutionStatus;

	public MetalCraftOptionsScreen(final Screen lastScreen) {
		this(lastScreen, Page.HOME, null);
	}

	private MetalCraftOptionsScreen(final Screen lastScreen, final Page page, final String category) {
		super(pageTitle(page, category));
		this.lastScreen = lastScreen;
		this.page = page;
		this.category = category;
	}

	private static Component pageTitle(final Page page, final String category) {
		return switch (page) {
			case HOME -> TITLE;
			case DISPLAY -> Component.translatable("metalcraft.options.display.title");
			case SHADERS -> Component.translatable("metalcraft.options.shaders.title");
			case SHADER_CATEGORY -> categoryLabel(category);
		};
	}

	private static Component categoryLabel(final String category) {
		return switch (category) {
			case "tonemap" -> Component.translatable("metalcraft.options.shader_category.tonemap");
			case "shadows" -> Component.translatable("metalcraft.options.shader_category.shadows");
			case "water" -> Component.translatable("metalcraft.options.shader_category.water");
			case "debug" -> Component.translatable("metalcraft.options.shader_category.debug");
			default -> {
				yield Component.literal(humanize(category));
			}
		};
	}

	@Override
	protected void init() {
		this.layout = new HeaderAndFooterLayout(this);
		this.layout.addTitleHeader(this.title, this.font);
		LinearLayout contents = LinearLayout.vertical().spacing(8);
		contents.defaultCellSetting().alignHorizontallyCenter();
		switch (this.page) {
			case HOME -> this.addHomeControls(contents);
			case DISPLAY -> this.addDisplayControls(contents);
			case SHADERS -> this.addShaderPackControls(contents);
			case SHADER_CATEGORY -> this.addShaderOptions(contents);
		}
		ScrollableLayout scrolling = new ScrollableLayout(this.minecraft, contents, Math.max(40, this.height - 70));
		scrolling.setMinWidth(330);
		this.layout.addToContents(scrolling, LayoutSettings::alignHorizontallyCenter);
		this.layout.addToFooter(Button.builder(this.page == Page.HOME ? CommonComponents.GUI_DONE : Component.translatable("gui.back"),
			button -> this.onClose()).width(200).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	private void addHomeControls(final LinearLayout contents) {
		this.addPageButton(contents, "metalcraft.options.display.title", Page.DISPLAY);
		contents.addChild(Button.builder(Component.translatable("metalcraft.options.terrain.title"),
			button -> this.minecraft.gui.setScreen(new MetalCraftLodOptionsScreen(this))).width(310).build());
		this.addPageButton(contents, "metalcraft.options.shaders.title", Page.SHADERS);
	}

	private void addPageButton(final LinearLayout contents, final String key, final Page destination) {
		contents.addChild(Button.builder(Component.translatable(key),
			button -> this.minecraft.gui.setScreen(new MetalCraftOptionsScreen(this, destination, null))).width(310).build());
	}

	private void addDisplayControls(final LinearLayout contents) {
		MultiLineTextWidget description = new MultiLineTextWidget(Component.translatable("metalcraft.options.half_resolution.description"), this.font)
			.setMaxWidth(310)
			.setCentered(true);
		contents.addChild(description);

		boolean appleSilicon = MetalCraftPlatform.isAppleSilicon();
		CycleButton<Boolean> halfResolution = CycleButton.onOffBuilder(MetalCraftConfig.halfResolution())
			.withTooltip(value -> Tooltip.create(Component.translatable("metalcraft.options.half_resolution.tooltip")))
			.create(0, 0, 310, 20, Component.translatable("metalcraft.options.half_resolution"), (button, enabled) -> {
				MetalCraftConfig.setHalfResolution(enabled);
				MetalCraftRenderResolution.apply(this.minecraft);
				this.updateResolutionStatus();
			});
		halfResolution.active = appleSilicon;
		contents.addChild(halfResolution);

		CycleButton<Boolean> unlockedFrameRate = CycleButton.onOffBuilder(MetalCraftConfig.unlockedFrameRate())
			.withTooltip(value -> Tooltip.create(Component.translatable("metalcraft.options.unlocked_frame_rate.tooltip")))
			.create(0, 0, 310, 20, Component.translatable("metalcraft.options.unlocked_frame_rate"), (button, enabled) -> {
				MetalCraftConfig.setUnlockedFrameRate(enabled);
				// The layer's synchronization is only read while the surface is configured, so the
				// change reaches Metal on the reconfiguration this schedules, not on the next frame.
				this.minecraft.invalidateSurfaceConfiguration();
			});
		contents.addChild(unlockedFrameRate);

		this.resolutionStatus = new StringWidget(Component.empty(), this.font);
		contents.addChild(this.resolutionStatus);
		this.updateResolutionStatus();
	}

	private void addShaderPackControls(final LinearLayout contents) {
		boolean appleSilicon = MetalCraftPlatform.isAppleSilicon();
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		Button packButton = Button.builder(packMessage(runtime), button -> {
			if (runtime == null) {
				return;
			}
			List<MetalCraftShaderPackInfo> packs = packChoices(runtime);
			int selected = 0;
			for (int index = 0; index < packs.size(); index++) {
				if (packs.get(index).id().equals(runtime.selectedPackId())) {
					selected = index;
					break;
				}
			}
			MetalCraftShaderPackInfo next = packs.get((selected + 1) % packs.size());
			try {
				runtime.selectPack(next.id());
				this.minecraft.gui.setScreen(new MetalCraftOptionsScreen(this.lastScreen, Page.SHADERS, null));
			} catch (RuntimeException error) {
				button.setMessage(Component.translatable("metalcraft.options.shader_pack_failed", next.name()));
				button.setTooltip(Tooltip.create(Component.literal(error.getMessage() == null ? error.toString() : error.getMessage())));
			}
		}).width(310).build();
		packButton.active = appleSilicon && runtime != null;
		contents.addChild(packButton);

		if (runtime == null || !appleSilicon) {
			return;
		}
		Map<String, Integer> categories = new LinkedHashMap<>();
		for (ShaderPack.Option option : runtime.options()) {
			categories.merge(option.category(), 1, Integer::sum);
		}
		if (ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId()) && categories.containsKey("debug")) {
			int debugOptions = categories.remove("debug");
			categories.put("debug", debugOptions);
		}
		for (Map.Entry<String, Integer> entry : categories.entrySet()) {
			String selectedCategory = entry.getKey();
			contents.addChild(Button.builder(Component.translatable("metalcraft.options.shader_category.open",
				categoryLabel(selectedCategory), entry.getValue()), button ->
				this.minecraft.gui.setScreen(new MetalCraftOptionsScreen(this, Page.SHADER_CATEGORY, selectedCategory)))
				.width(310).build());
		}
		if (categories.isEmpty()) {
			contents.addChild(new MultiLineTextWidget(Component.translatable("metalcraft.options.shader_no_options"), this.font)
				.setMaxWidth(310).setCentered(true));
		}
	}

	private void addShaderOptions(final LinearLayout contents) {
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		if (runtime == null || !MetalCraftPlatform.isAppleSilicon()) return;
		for (ShaderPack.Option option : runtime.options()) {
			if (!option.category().equals(this.category)) continue;
			var optionWidget = option.type() == ShaderPack.OptionType.INT || option.type() == ShaderPack.OptionType.FLOAT
				? numericSlider(runtime, option)
				: Button.builder(optionMessage(runtime, option), button -> {
					runtime.setOption(option.id(), nextValue(runtime.optionValue(option.id()), option));
					button.setMessage(optionMessage(runtime, option));
				}).width(310).build();
			optionWidget.setTooltip(Tooltip.create(isStandardWaterOption(runtime, option)
				? Component.translatable("metalcraft.water." + option.id() + ".tooltip")
				: Component.translatable("metalcraft.options.shader_option.tooltip",
					option.category(), option.apply().name().toLowerCase())));
			contents.addChild(optionWidget);
		}
	}

	private static List<MetalCraftShaderPackInfo> packChoices(final ShaderPackRuntime runtime) {
		List<MetalCraftShaderPackInfo> packs = new ArrayList<>();
		packs.add(new MetalCraftShaderPackInfo(ShaderPackRuntime.NONE_ID, "None"));
		packs.addAll(runtime.availablePacks());
		return packs;
	}

	private static AbstractSliderButton numericSlider(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		double minimum = option.min().orElseThrow();
		double maximum = option.max().orElseThrow();
		double current = ((Number)runtime.optionValue(option.id())).doubleValue();
		return new AbstractSliderButton(0, 0, 310, 20, optionMessage(runtime, option), (current - minimum) / (maximum - minimum)) {
			@Override
			protected void updateMessage() {
				this.setMessage(optionMessage(runtime, option));
			}

			@Override
			protected void applyValue() {
				double raw = minimum + this.value * (maximum - minimum);
				double step = option.step().orElse((maximum - minimum) / 100.0);
				double stepped = minimum + Math.round((raw - minimum) / step) * step;
				Object value = option.type() == ShaderPack.OptionType.INT ? (int)Math.round(stepped) : stepped;
				runtime.setOption(option.id(), value);
			}
		};
	}

	private static Component packMessage(final ShaderPackRuntime runtime) {
		if (runtime == null || ShaderPackRuntime.NONE_ID.equals(runtime.selectedPackId())) {
			return Component.translatable(
				"metalcraft.options.shader_pack",
				Component.translatable("metalcraft.options.shader_pack_none")
			);
		}
		return Component.translatable("metalcraft.options.shader_pack", runtime.selectedPackName());
	}

	private static Component optionMessage(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		if (isStandardWaterOption(runtime, option)) {
			Object value = runtime.optionValue(option.id());
			Component label = option.id().equals("water_detail")
				? Component.translatable("metalcraft.water.detail." + ((Number)value).intValue())
				: option.id().equals("water_detail_distance")
				? Component.literal(Integer.toString(((Number)value).intValue()))
				: value instanceof Number number
				? Component.literal(Math.round(number.doubleValue() * 100) + "%")
				: Component.translatable("metalcraft.water.value." + value);
			return Component.translatable("metalcraft.water." + option.id(), label);
		}
		Object value = runtime.optionValue(option.id());
		Component valueLabel = value instanceof Boolean bool
			? Component.translatable("metalcraft.options.value." + bool)
			: value instanceof String string ? Component.literal(humanize(string)) : Component.literal(value.toString());
		return Component.literal(humanize(option.id()) + ": ").append(valueLabel);
	}

	private static String humanize(final String identifier) {
		String label = identifier.replace('_', ' ').replace('-', ' ');
		return label.isEmpty() ? identifier : Character.toUpperCase(label.charAt(0)) + label.substring(1);
	}

	private static boolean isStandardWaterOption(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		return ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId()) && option.id().startsWith("water_");
	}

	private static Object nextValue(final Object current, final ShaderPack.Option option) {
		return switch (option.type()) {
			case BOOL -> !((Boolean)current);
			case ENUM -> {
				int index = option.values().indexOf(current);
				yield option.values().get((index + 1) % option.values().size());
			}
			case INT -> {
				int minimum = (int)option.min().orElseThrow();
				int maximum = (int)option.max().orElseThrow();
				int step = (int)option.step().orElse(1.0);
				int next = ((Number)current).intValue() + step;
				yield next > maximum ? minimum : next;
			}
			case FLOAT -> {
				double minimum = option.min().orElseThrow();
				double maximum = option.max().orElseThrow();
				double step = option.step().orElse((maximum - minimum) / 10.0);
				double next = ((Number)current).doubleValue() + step;
				yield next > maximum + 0.000001 ? minimum : next;
			}
		};
	}

	private void updateResolutionStatus() {
		if (this.resolutionStatus == null) {
			return;
		}
		Window window = this.minecraft.getWindow();
		this.resolutionStatus.setMessage(
			Component.translatable("metalcraft.options.render_resolution", window.getWidth(), window.getHeight())
		);
	}

	@Override
	protected void repositionElements() {
		if (this.layout != null) this.layout.arrangeElements();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.lastScreen);
	}
}
