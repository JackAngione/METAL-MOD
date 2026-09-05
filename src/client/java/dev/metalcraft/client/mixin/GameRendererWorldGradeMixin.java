package dev.metalcraft.client.mixin;

import dev.metalcraft.client.shader.ShaderPackRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The first hand-depth clear occurs after the world frame graph and before hand/overlays. */
@Mixin(GameRenderer.class)
abstract class GameRendererWorldGradeMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target =
		"Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
	private void metalcraft$gradeWorld(final CallbackInfo callback) {
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		if (runtime == null || !runtime.isActive()) return;
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		runtime.gradeWorld(target.getColorTextureView(), target.getDepthTextureView());
	}
}
