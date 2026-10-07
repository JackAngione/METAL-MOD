package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.metalcraft.client.MetalCraftPlatform;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Minecraft backend entry point for Metal Mod's direct Metal renderer. */
public final class MetalBackend implements GpuBackend {
	@Override
	public String getName() {
		return "Metal";
	}

	@Override
	public void setWindowHints() {
		// A CAMetalLayer is attached after the window exists; GLFW must not create an OpenGL context.
		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
	}

	@Override
	public void handleWindowCreationErrors(final GLFWErrorCapture.@Nullable Error error) throws BackendCreationException {
		if (error == null) {
			throw failure("Failed to create a GLFW window for Metal", null);
		}
		throw failure(String.format(Locale.ROOT, "GLFW_ERROR: 0x%X", error.error()), null);
	}

	@Override
	public GpuDevice createDevice(
		final long window,
		final ShaderSource defaultShaderSource,
		final GpuDebugOptions debugOptions,
		final Runnable criticalShaderLoader
	) throws BackendCreationException {
		if (!MetalCraftPlatform.shouldUseDirectMetal()) {
			throw failure("Direct Metal is unavailable on this platform or has been disabled", null);
		}
		if (!MetalNative.load()) {
			throw failure("Metal Mod could not load its direct Metal bridge", MetalNative.loadFailure().orElse(null));
		}
		MetalDevice metal = MetalNative.openDefaultDevice().orElseThrow(() -> failure("Metal did not provide a default device", null));
		try {
			return new GpuDevice(new MetalGpuDevice(metal, defaultShaderSource), criticalShaderLoader);
		} catch (RuntimeException error) {
			metal.close();
			throw failure("Metal Mod could not create its Blaze3D device adapter", error);
		}
	}

	/** Performs direct Metal availability checks before Minecraft commits to this backend. */
	public static @Nullable BackendCreationException checkBackendAvailable() {
		if (!MetalCraftPlatform.shouldUseDirectMetal()) {
			return failure("Direct Metal is unavailable on this platform or has been disabled", null);
		}
		if (!MetalNative.load()) {
			return failure("Metal Mod could not load its direct Metal bridge", MetalNative.loadFailure().orElse(null));
		}

		try (MetalDevice ignored = MetalNative.openDefaultDevice().orElse(null)) {
			return ignored == null ? failure("Metal did not provide a default device", null) : null;
		} catch (RuntimeException error) {
			return failure("Metal Mod could not open the default Metal device", error);
		}
	}

	private static BackendCreationException failure(final String message, final @Nullable Throwable cause) {
		BackendCreationException exception = new BackendCreationException(message, BackendCreationException.Reason.OTHER);
		if (cause != null) {
			exception.initCause(cause);
		}
		return exception;
	}
}
