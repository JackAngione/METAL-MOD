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
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
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
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Metal Mod's renderer settings screen. */
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
			case "tonemap" -> Component.translatable(isStandardPack(ShaderPackRuntime.active())
				? "metalcraft.options.shader_category.color_grading" : "metalcraft.options.shader_category.tonemap");
			case "shadows" -> Component.translatable("metalcraft.options.shader_category.shadows");
			case "water" -> Component.translatable("metalcraft.options.shader_category.water");
			case "wind" -> Component.translatable("metalcraft.options.shader_category.wind");
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
		scrolling.setMinWidth(Math.min(330, Math.max(140, this.width - 10)));
		this.layout.addToContents(scrolling, LayoutSettings::alignHorizontallyCenter);
		this.layout.addToFooter(Button.builder(this.page == Page.HOME ? CommonComponents.GUI_DONE : Component.translatable("gui.back"),
			button -> this.onClose()).width(200).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	private void addHomeControls(final LinearLayout contents) {
		this.addPageButton(contents, "metalcraft.options.display.title", Page.DISPLAY);
		contents.addChild(Button.builder(Component.translatable("metalcraft.options.terrain.title"),
			button -> this.minecraft.gui.setScreen(new MetalCraftLodOptionsScreen(this))).width(this.controlWidth()).build());
		this.addPageButton(contents, "metalcraft.options.shaders.title", Page.SHADERS);
	}

	private void addPageButton(final LinearLayout contents, final String key, final Page destination) {
		contents.addChild(Button.builder(Component.translatable(key),
			button -> this.minecraft.gui.setScreen(new MetalCraftOptionsScreen(this, destination, null))).width(this.controlWidth()).build());
	}

	private void addDisplayControls(final LinearLayout contents) {
		MultiLineTextWidget description = new MultiLineTextWidget(Component.translatable("metalcraft.options.half_resolution.description"), this.font)
			.setMaxWidth(this.controlWidth())
			.setCentered(true);
		contents.addChild(description);

		boolean appleSilicon = MetalCraftPlatform.isAppleSilicon();
		CycleButton<Boolean> halfResolution = CycleButton.onOffBuilder(MetalCraftConfig.halfResolution())
			.withTooltip(value -> Tooltip.create(Component.translatable("metalcraft.options.half_resolution.tooltip")))
			.create(0, 0, this.controlWidth(), 20, Component.translatable("metalcraft.options.half_resolution"), (button, enabled) -> {
				MetalCraftConfig.setHalfResolution(enabled);
				MetalCraftRenderResolution.apply(this.minecraft);
				this.updateResolutionStatus();
			});
		halfResolution.active = appleSilicon;
		contents.addChild(halfResolution);

		CycleButton<Boolean> unlockedFrameRate = CycleButton.onOffBuilder(MetalCraftConfig.unlockedFrameRate())
			.withTooltip(value -> Tooltip.create(Component.translatable("metalcraft.options.unlocked_frame_rate.tooltip")))
			.create(0, 0, this.controlWidth(), 20, Component.translatable("metalcraft.options.unlocked_frame_rate"), (button, enabled) -> {
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
		}).width(this.controlWidth()).build();
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
				.width(this.controlWidth()).build());
		}
		if (categories.isEmpty()) {
			contents.addChild(new MultiLineTextWidget(Component.translatable("metalcraft.options.shader_no_options"), this.font)
				.setMaxWidth(this.controlWidth()).setCentered(true));
		}
	}

	private void addShaderOptions(final LinearLayout contents) {
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		if (runtime == null || !MetalCraftPlatform.isAppleSilicon()) return;
		boolean grading = isStandardPack(runtime) && "tonemap".equals(this.category);
		Map<ShaderPack.Option, AbstractWidget> optionWidgets = new LinkedHashMap<>();
		String previousGroup = "";
		if (grading) {
			contents.addChild(new MultiLineTextWidget(Component.translatable("metalcraft.grading.description"), this.font)
				.setMaxWidth(this.controlWidth()).setCentered(true));
		}
		for (ShaderPack.Option option : runtime.options()) {
			if (!option.category().equals(this.category)) continue;
			if (isStandardGradingOption(runtime, option)) {
				String group = gradingGroup(option.id());
				if (!group.equals(previousGroup)) {
					contents.addChild(new StringWidget(Component.translatable("metalcraft.grading.group." + group), this.font));
					previousGroup = group;
				}
			}
			var optionWidget = option.type() == ShaderPack.OptionType.INT || option.type() == ShaderPack.OptionType.FLOAT
				? new ShaderOptionSlider(runtime, option, this.controlWidth())
				: Button.builder(optionMessage(runtime, option), button -> {
					runtime.setOption(option.id(), nextValue(runtime.optionValue(option.id()), option));
					button.setMessage(optionMessage(runtime, option));
				}).width(this.controlWidth()).build();
			optionWidget.setTooltip(Tooltip.create(isStandardGradingOption(runtime, option)
				? Component.translatable("metalcraft.grading." + option.id() + ".tooltip")
				: isStandardWindOption(runtime, option)
				? Component.translatable("metalcraft.wind." + option.id() + ".tooltip")
				: isStandardWaterOption(runtime, option)
				? Component.translatable("metalcraft.water." + option.id() + ".tooltip")
				: isShadowDistanceOption(runtime, option)
				? Component.translatable("metalcraft.shadows." + option.id() + ".tooltip")
				: Component.translatable("metalcraft.options.shader_option.tooltip",
					option.category(), option.apply().name().toLowerCase())));
			contents.addChild(optionWidget);
			optionWidgets.put(option, optionWidget);
		}
		if (grading && !optionWidgets.isEmpty()) {
			Button reset = Button.builder(Component.translatable("metalcraft.grading.reset"), button -> {
				// Only this category is reset; defaults remain owned by the selected pack's manifest.
				for (ShaderPack.Option option : optionWidgets.keySet()) {
					runtime.setOption(option.id(), option.defaultValue());
				}
				// Refresh in place so keyboard focus and the scroll position survive a reset.
				for (Map.Entry<ShaderPack.Option, AbstractWidget> entry : optionWidgets.entrySet()) {
					if (entry.getValue() instanceof ShaderOptionSlider slider) {
						slider.refreshValue();
					} else {
						entry.getValue().setMessage(optionMessage(runtime, entry.getKey()));
					}
				}
			}).width(this.controlWidth()).build();
			reset.setTooltip(Tooltip.create(Component.translatable("metalcraft.grading.reset.tooltip")));
			contents.addChild(reset);
		}
	}

	private static List<MetalCraftShaderPackInfo> packChoices(final ShaderPackRuntime runtime) {
		List<MetalCraftShaderPackInfo> packs = new ArrayList<>();
		packs.add(new MetalCraftShaderPackInfo(ShaderPackRuntime.NONE_ID, "None"));
		packs.addAll(runtime.availablePacks());
		return packs;
	}

	private static final class ShaderOptionSlider extends AbstractSliderButton {
		private final ShaderPackRuntime runtime;
		private final ShaderPack.Option option;

		private ShaderOptionSlider(final ShaderPackRuntime runtime, final ShaderPack.Option option, final int width) {
			super(0, 0, width, 20, optionMessage(runtime, option), normalizedValue(runtime, option));
			this.runtime = runtime;
			this.option = option;
		}

		private static double normalizedValue(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
			double minimum = option.min().orElseThrow();
			return (((Number)runtime.optionValue(option.id())).doubleValue() - minimum) / (option.max().orElseThrow() - minimum);
		}

		private void refreshValue() {
			this.value = normalizedValue(this.runtime, this.option);
			this.updateMessage();
		}

		@Override
		protected void updateMessage() {
			this.setMessage(optionMessage(this.runtime, this.option));
		}

		@Override
		public boolean keyPressed(final KeyEvent event) {
			if (this.canChangeValue && (event.isLeft() || event.isRight())) {
				double range = this.option.max().orElseThrow() - this.option.min().orElseThrow();
				double step = this.option.step().orElse(range / 100.0);
				// A snapped handle must cross a whole option step when using the keyboard.
				double increment = Math.max(step / range, 1.0 / (this.getWidth() - 8));
				this.setValue(this.value + (event.isLeft() ? -increment : increment));
				return true;
			}
			return super.keyPressed(event);
		}

		@Override
		protected void applyValue() {
			double minimum = this.option.min().orElseThrow();
			double maximum = this.option.max().orElseThrow();
			double raw = minimum + this.value * (maximum - minimum);
			double step = this.option.step().orElse((maximum - minimum) / 100.0);
			double stepped = Math.clamp(minimum + Math.round((raw - minimum) / step) * step, minimum, maximum);
			Object value = this.option.type() == ShaderPack.OptionType.INT ? (int)Math.round(stepped) : stepped;
			this.runtime.setOption(this.option.id(), value);
			this.refreshValue();
		}
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

	private static boolean isStandardWindOption(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		return ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId()) && option.category().equals("wind");
	}

	private static Component optionMessage(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		if (isStandardGradingOption(runtime, option)) {
			return Component.translatable("metalcraft.grading." + option.id(), gradingValue(option.id(), runtime.optionValue(option.id())));
		}
		if (isStandardWindOption(runtime, option)) {
			Object value = runtime.optionValue(option.id());
			Component label = value instanceof Number number
				? Component.literal(Math.round(number.doubleValue() * 100) + "%")
				: Component.translatable("metalcraft.options.value." + value);
			return Component.translatable("metalcraft.wind." + option.id(), label);
		}
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
		if (isShadowDistanceOption(runtime, option)) {
			return Component.translatable("metalcraft.shadows." + option.id(), ((Number)value).intValue());
		}
		Component valueLabel = value instanceof Boolean bool
			? Component.translatable("metalcraft.options.value." + bool)
			: value instanceof String string ? Component.literal(humanize(string)) : Component.literal(value.toString());
		return Component.literal(humanize(option.id()) + ": ").append(valueLabel);
	}

	private static String humanize(final String identifier) {
		String label = identifier.replace('_', ' ').replace('-', ' ');
		return label.isEmpty() ? identifier : Character.toUpperCase(label.charAt(0)) + label.substring(1);
	}

	private static boolean isStandardPack(final ShaderPackRuntime runtime) {
		return runtime != null && ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId());
	}

	private static boolean isStandardGradingOption(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		return isStandardPack(runtime) && "tonemap".equals(option.category()) && switch (option.id()) {
			case "exposure", "tonemap", "temperature", "tint", "contrast", "saturation", "vibrance", "gamma", "highlights", "shadows",
				"film_grain", "bloom", "depth_of_field" -> true;
			default -> false;
		};
	}

	private static String gradingGroup(final String id) {
		return switch (id) {
			case "exposure", "tonemap" -> "tone";
			case "temperature", "tint" -> "balance";
			case "contrast", "saturation", "vibrance" -> "color";
			case "film_grain", "bloom", "depth_of_field" -> "effects";
			default -> "light";
		};
	}

	private static Component gradingValue(final String id, final Object value) {
		if (id.equals("tonemap")) return Component.translatable("metalcraft.grading.tonemap." + value);
		if (id.equals("film_grain") || id.equals("bloom") || id.equals("depth_of_field")) {
			return Component.translatable("metalcraft.grading.strength." + ((Number)value).intValue());
		}
		double numeric = ((Number)value).doubleValue();
		if (id.equals("exposure") || id.equals("gamma")) {
			return Component.literal(String.format(Locale.ROOT, "%.2f", numeric) + (id.equals("exposure") ? "×" : ""));
		}
		long percent = Math.round(numeric * 100);
		if (id.equals("temperature") || id.equals("tint")) {
			if (percent == 0) return Component.translatable("metalcraft.grading.neutral");
			String direction = id.equals("temperature") ? (percent < 0 ? "cool" : "warm") : (percent < 0 ? "green" : "magenta");
			return Component.translatable("metalcraft.grading.direction." + direction, Math.abs(percent));
		}
		boolean signed = id.equals("vibrance") || id.equals("highlights") || id.equals("shadows");
		return Component.literal((signed && percent > 0 ? "+" : "") + percent + "%");
	}

	private int controlWidth() {
		return Math.min(310, Math.max(120, this.width - 30));
	}

	private static boolean isStandardWaterOption(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		return ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId()) && option.id().startsWith("water_");
	}

	private static boolean isShadowDistanceOption(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		return ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId())
			&& (option.id().equals("shadow_distance") || option.id().equals("distant_shadow_distance"));
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
