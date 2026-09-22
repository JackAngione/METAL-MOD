package dev.metalcraft.client.metal;

/**
 * The drawable size the presentation surface was last configured with.
 *
 * <p>Recorded here rather than re-derived by whoever asks, because the two are not always the same
 * number and the difference is the whole point. Minecraft sizes its render targets from
 * {@code Window.getWidth()} but sizes the surface from {@code glfwGetFramebufferSize}, so a harness
 * that reports the resolution it requested can be describing neither the pixels it drew nor the
 * pixels it presented. A benchmark that cannot state its own drawable size cannot support a
 * resolution claim, so the value is published from the one call that actually sets it.
 */
public final class MetalSurfaceProbe {
	private static volatile int width;
	private static volatile int height;
	private static volatile long generation;

	private MetalSurfaceProbe() {
	}

	static void configured(final int configuredWidth, final int configuredHeight) {
		width = configuredWidth;
		height = configuredHeight;
		generation++;
	}

	/** @return the last configured drawable size as {width, height}, or {0, 0} before the first configure */
	public static int[] drawableSize() {
		return new int[] {width, height};
	}

	/** Increments on every configure, so a caller can wait for one that follows its own resize. */
	public static long generation() {
		return generation;
	}

	/** AppKit/render-thread benchmark probe: active, visible, not minimized, not fully occluded. */
	public static int presentationState(final long glfwWindow) {
		return MetalNative.nWindowPresentationState(org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(glfwWindow));
	}
}
