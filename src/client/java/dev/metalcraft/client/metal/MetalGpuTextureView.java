package dev.metalcraft.client.metal;

import com.mojang.blaze3d.textures.GpuTextureView;

/** Blaze3D texture-view adapter retaining its Metal view until explicitly closed. */
final class MetalGpuTextureView extends GpuTextureView {
	private final MetalTextureView metal;

	MetalGpuTextureView(final MetalGpuTexture texture, final int baseMipLevel, final int mipLevels, final MetalTextureView metal) {
		super(texture, baseMipLevel, mipLevels);
		this.metal = metal;
	}

	MetalTextureView metal() {
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

	@Override
	public MetalGpuTexture texture() {
		return (MetalGpuTexture)super.texture();
	}
}
