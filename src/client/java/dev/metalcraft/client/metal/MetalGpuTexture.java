package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

/** Blaze3D texture adapter backed by one owned two-dimensional or cubemap Metal texture. */
final class MetalGpuTexture extends GpuTexture {
	private final MetalTexture metal;

	MetalGpuTexture(
		final @GpuTexture.Usage int usage,
		final String label,
		final GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels,
		final MetalTexture metal
	) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.metal = metal;
	}

	MetalTexture metal() {
		return this.metal;
	}

	@Override
	public void close() {
		this.metal.close();
	}

	@Override
	public boolean isClosed() {
		return this.metal.isClosed();
	}
}
