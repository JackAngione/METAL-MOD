package dev.metalcraft.client.gui;

import com.mojang.blaze3d.platform.Window;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.MetalCraftPlatform;
import dev.metalcraft.client.MetalCraftRenderResolution;
import dev.metalcraft.client.metal.MetalShaderEngine;
import dev.metalcraft.client.shader.ShaderPack;
import java.util.List;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.ScrollableLayout;
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

		CycleButton<Boolean> halfResolution = CycleButton.onOffBuilder(MetalCraftConfig.halfResolution())
			.withTooltip(value -> Tooltip.create(Component.translatable("metalcraft.options.half_resolution.tooltip")))
			.create(0, 0, 310, 20, Component.translatable("metalcraft.options.half_resolution"), (button, enabled) -> {
				MetalCraftConfig.setHalfResolution(enabled);
				MetalCraftRenderResolution.apply(this.minecraft);
				this.updateResolutionStatus();
			});
		halfResolution.active = MetalCraftPlatform.isAppleSilicon();
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

		MetalShaderEngine shaderEngine = MetalShaderEngine.active();
		if (shaderEngine != null) {
			this.addShaderPackControls(contents, shaderEngine);
		}

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

	private void addShaderPackControls(final LinearLayout contents, final MetalShaderEngine engine) {
		contents.addChild(new StringWidget(Component.translatable("metalcraft.options.shader_pack_header"), this.font));
		Button packButton = Button.builder(packMessage(engine), button -> {
			List<MetalShaderEngine.PackChoice> packs = engine.availablePacks();
			int selected = 0;
			for (int index = 0; index < packs.size(); index++) {
				if (packs.get(index).id().equals(engine.selectedPackId())) {
					selected = index;
					break;
				}
			}
			MetalShaderEngine.PackChoice next = packs.get((selected + 1) % packs.size());
			try {
				engine.selectPack(next.id());
				this.minecraft.gui.setScreen(new MetalCraftOptionsScreen(this.lastScreen));
			} catch (RuntimeException error) {
				button.setMessage(Component.translatable("metalcraft.options.shader_pack_failed", next.name()));
				button.setTooltip(Tooltip.create(Component.literal(error.getMessage())));
			}
		}).width(310).build();
		contents.addChild(packButton);

		for (ShaderPack.Option option : engine.options()) {
			var optionWidget = option.type() == ShaderPack.OptionType.INT || option.type() == ShaderPack.OptionType.FLOAT
				? numericSlider(engine, option)
				: Button.builder(optionMessage(engine, option), button -> {
					engine.setOption(option.id(), nextValue(engine.optionValue(option.id()), option));
					button.setMessage(optionMessage(engine, option));
				}).width(310).build();
			optionWidget.setTooltip(Tooltip.create(Component.translatable(
				"metalcraft.options.shader_option.tooltip", option.category(), option.apply().name().toLowerCase()
			)));
			contents.addChild(optionWidget);
		}
	}

	private static AbstractSliderButton numericSlider(final MetalShaderEngine engine, final ShaderPack.Option option) {
		double minimum = option.min().orElseThrow();
		double maximum = option.max().orElseThrow();
		double current = ((Number)engine.optionValue(option.id())).doubleValue();
		return new AbstractSliderButton(0, 0, 310, 20, optionMessage(engine, option), (current - minimum) / (maximum - minimum)) {
			@Override
			protected void updateMessage() {
				this.setMessage(optionMessage(engine, option));
			}

			@Override
			protected void applyValue() {
				double raw = minimum + this.value * (maximum - minimum);
				double step = option.step().orElse((maximum - minimum) / 100.0);
				double stepped = minimum + Math.round((raw - minimum) / step) * step;
				Object value = option.type() == ShaderPack.OptionType.INT ? (int)Math.round(stepped) : stepped;
				engine.setOption(option.id(), value);
			}
		};
	}

	private static Component packMessage(final MetalShaderEngine engine) {
		return Component.translatable("metalcraft.options.shader_pack", engine.selectedPackName());
	}

	private static Component optionMessage(final MetalShaderEngine engine, final ShaderPack.Option option) {
		String name = option.id().replace('_', ' ');
		return Component.literal(name + ": " + engine.optionValue(option.id()));
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
