package dev.metalcraft.client.gui;

import com.mojang.blaze3d.platform.Window;
import dev.metalcraft.api.MetalCraftShaderPackInfo;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.MetalCraftPlatform;
import dev.metalcraft.client.MetalCraftRenderResolution;
import dev.metalcraft.client.shader.ShaderPack;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.util.ArrayList;
import java.util.List;
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
	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final Screen lastScreen;
	private StringWidget resolutionStatus;

	public MetalCraftOptionsScreen(final Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		this.layout.addTitleHeader(TITLE, this.font);

		LinearLayout contents = LinearLayout.vertical().spacing(12);
		contents.defaultCellSetting().alignHorizontallyCenter();
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

		this.addShaderPackControls(contents, appleSilicon);

		this.resolutionStatus = new StringWidget(Component.empty(), this.font);
		contents.addChild(this.resolutionStatus);
		this.updateResolutionStatus();
		ScrollableLayout scrolling = new ScrollableLayout(this.minecraft, contents, Math.max(40, this.height - 70));
		scrolling.setMinWidth(330);
		this.layout.addToContents(scrolling, LayoutSettings::alignHorizontallyCenter);
		this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
	}

	private void addShaderPackControls(final LinearLayout contents, final boolean appleSilicon) {
		contents.addChild(new StringWidget(Component.translatable("metalcraft.options.shader_pack_header"), this.font));
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
				this.minecraft.gui.setScreen(new MetalCraftOptionsScreen(this.lastScreen));
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
		for (ShaderPack.Option option : runtime.options()) {
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
		String name = option.id().replace('_', ' ');
		return Component.literal(name + ": " + runtime.optionValue(option.id()));
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
		this.layout.arrangeElements();
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.lastScreen);
	}
}
