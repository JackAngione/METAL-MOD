package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.opengl.GlBackend;
import com.mojang.blaze3d.systems.GpuBackend;
import dev.metalcraft.client.MetalCraftPlatform;
import dev.metalcraft.client.metal.MetalBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PreferredGraphicsApi.class)
abstract class PreferredGraphicsApiMixin {
	@Inject(method = "getBackendsToTry", at = @At("HEAD"), cancellable = true)
	private void metalcraft$preferMetalOnAppleSilicon(final CallbackInfoReturnable<GpuBackend[]> callback) {
		PreferredGraphicsApi selected = (PreferredGraphicsApi)(Object)this;
		if (selected == PreferredGraphicsApi.DEFAULT && MetalCraftPlatform.shouldUseDirectMetal()) {
			callback.setReturnValue(new GpuBackend[]{new MetalBackend(), new GlBackend()});
		}
	}
}
