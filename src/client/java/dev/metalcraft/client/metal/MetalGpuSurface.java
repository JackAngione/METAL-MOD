package dev.metalcraft.client.metal;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collection;
import java.util.List;

/** CAMetalLayer-backed implementation of Minecraft's presentation interface. */
final class MetalGpuSurface implements GpuSurfaceBackend {
	private final MetalGpuDevice device;
	private final long windowHandle;
	private MetalSurface metal;
	private MetalDrawable drawable;
	private boolean configured;

	MetalGpuSurface(final MetalGpuDevice device, final long windowHandle) {
		this.device = device;
		this.windowHandle = windowHandle;
	}

	@Override
	public void configure(final GpuSurface.Configuration config) throws SurfaceException {
		try {
			if (this.metal == null) {
				this.metal = this.device.metal().attachToGlfwWindow(this.windowHandle, config.width(), config.height());
			} else {
				this.metal.resize(config.width(), config.height());
			}
			this.metal.setDisplaySyncEnabled(config.presentMode() != GpuSurface.PresentMode.IMMEDIATE);
			this.configured = true;
		} catch (RuntimeException error) {
			throw new SurfaceException("Metal could not configure the window surface: " + error.getMessage());
		}
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (!this.configured || this.metal == null) {
			throw new SurfaceException("Metal surface is not configured");
		}
		this.drawable = this.metal.acquireDrawable().orElseThrow(() -> new SurfaceException("Metal did not provide a drawable"));
	}

	@Override
	public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
		if (!(commandEncoder instanceof MetalCommandEncoder metalEncoder)) {
			throw new IllegalArgumentException("Command encoder does not belong to the direct Metal backend");
		}
		if (!(textureView instanceof MetalGpuTextureView metalView)) {
			throw new IllegalArgumentException("Presented texture does not belong to the direct Metal backend");
		}
		if (this.drawable == null) {
			throw new IllegalStateException("Metal surface has no acquired drawable");
		}
		metalEncoder.blitToDrawable(metalView.texture().metal(), this.drawable);
	}

	@Override
	public void present() {
		if (this.drawable == null) {
			throw new IllegalStateException("Metal surface has no acquired drawable");
		}
		this.drawable.close();
		this.drawable = null;
	}

	@Override
	public void close() {
		if (this.drawable != null) {
			this.drawable.close();
			this.drawable = null;
		}
		if (this.metal != null) {
			this.metal.close();
			this.metal = null;
		}
		this.configured = false;
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}
}
