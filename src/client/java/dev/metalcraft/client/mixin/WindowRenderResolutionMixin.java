package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.metalcraft.client.MetalCraftRenderResolution;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Window.class)
abstract class WindowRenderResolutionMixin {
	@Redirect(
		method = "refreshFramebufferSize",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwGetFramebufferSize(J[I[I)V")
	)
	private void metalcraft$scaleInitialFramebufferSize(final long window, final int[] width, final int[] height) {
		GLFW.glfwGetFramebufferSize(window, width, height);
		width[0] = MetalCraftRenderResolution.scaleDimension(width[0]);
		height[0] = MetalCraftRenderResolution.scaleDimension(height[0]);
	}

	@ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), argsOnly = true, ordinal = 0)
	private int metalcraft$scaleResizedFramebufferWidth(final int width) {
		return MetalCraftRenderResolution.scaleDimension(width);
	}

	@ModifyVariable(method = "onFramebufferResize", at = @At("HEAD"), argsOnly = true, ordinal = 1)
	private int metalcraft$scaleResizedFramebufferHeight(final int height) {
		return MetalCraftRenderResolution.scaleDimension(height);
	}
}
