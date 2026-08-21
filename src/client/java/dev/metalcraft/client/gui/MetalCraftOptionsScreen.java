package dev.metalcraft.client.gui;

import com.mojang.blaze3d.platform.Window;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.MetalCraftPlatform;
import dev.metalcraft.client.MetalCraftRenderResolution;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
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

		CycleButton<Boolean> halfResolution = CycleButton.onOffBuilder(MetalCraftConfig.halfResolution())
			.withTooltip(value -> Tooltip.create(Component.translatable("metalcraft.options.half_resolution.tooltip")))
			.create(0, 0, 310, 20, Component.translatable("metalcraft.options.half_resolution"), (button, enabled) -> {
				MetalCraftConfig.setHalfResolution(enabled);
				MetalCraftRenderResolution.apply(this.minecraft);
				this.updateResolutionStatus();
			});
		halfResolution.active = MetalCraftPlatform.isAppleSilicon();
		contents.addChild(halfResolution);

		this.resolutionStatus = new StringWidget(Component.empty(), this.font);
		contents.addChild(this.resolutionStatus);
		this.updateResolutionStatus();
		this.layout.addToContents(contents, LayoutSettings::alignHorizontallyCenter);
		this.layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose()).width(200).build());
		this.layout.visitWidgets(this::addRenderableWidget);
		this.repositionElements();
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
