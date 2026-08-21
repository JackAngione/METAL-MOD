package dev.metalcraft.client;

import com.mojang.blaze3d.platform.Window;
import dev.metalcraft.client.mixin.WindowFramebufferAccessor;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/** Applies MetalCraft's render scale while leaving the native presentation surface untouched. */
public final class MetalCraftRenderResolution {
	private MetalCraftRenderResolution() {
	}

	public static int scaleDimension(final int nativePixels) {
		if (!MetalCraftPlatform.isAppleSilicon() || !MetalCraftConfig.halfResolution()) {
			return nativePixels;
		}
		return Math.max(1, nativePixels / 2);
	}

	public static void apply(final Minecraft minecraft) {
		Window window = minecraft.getWindow();
		int[] nativeWidth = new int[1];
		int[] nativeHeight = new int[1];
		GLFW.glfwGetFramebufferSize(window.handle(), nativeWidth, nativeHeight);
		if (nativeWidth[0] <= 0 || nativeHeight[0] <= 0) {
			return;
		}

		WindowFramebufferAccessor framebuffer = (WindowFramebufferAccessor)(Object)window;
		framebuffer.metalcraft$setFramebufferWidth(scaleDimension(nativeWidth[0]));
		framebuffer.metalcraft$setFramebufferHeight(scaleDimension(nativeHeight[0]));
		minecraft.framebufferSizeChanged();
	}
}
