package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.metalcraft.client.shader.water.UnderwaterAppearance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ScreenEffectRenderer.class)
abstract class ScreenEffectRendererWaterMixin {
	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void metalcraft$omitWaterVeil(final Minecraft minecraft, final PoseStack poses,
		final SubmitNodeCollector collector, final CallbackInfo callback) {
		// Distance fog supplies immersion; a screen texture otherwise tints even near objects.
		if (UnderwaterAppearance.enabled()) callback.cancel();
	}
}
