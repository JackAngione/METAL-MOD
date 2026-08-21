package dev.metalcraft.client.mixin;

import dev.metalcraft.client.gui.MetalCraftOptionsScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin {
	@Inject(method = "addOptions", at = @At("TAIL"))
	private void metalcraft$addRendererSettings(final CallbackInfo callback) {
		Screen current = (Screen)(Object)this;
		OptionsList list = ((OptionsSubScreenAccessor)this).metalcraft$getOptionsList();
		list.addHeader(Component.translatable("metalcraft.options.video_header"));
		list.addBig(
			Button.builder(Component.translatable("metalcraft.options.open"), button -> Minecraft.getInstance().gui.setScreen(new MetalCraftOptionsScreen(current)))
				.build()
		);
	}
}
