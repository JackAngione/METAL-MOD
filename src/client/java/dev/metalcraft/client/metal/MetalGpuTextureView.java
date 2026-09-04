package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/** Blaze3D texture-view adapter retaining its Metal view until explicitly closed. */
public final class MetalGpuTextureView extends GpuTextureView {
	private final @Nullable MetalTextureView metal;
	private boolean closed;

	MetalGpuTextureView(final MetalGpuTexture texture, final int baseMipLevel, final int mipLevels, final @Nullable MetalTextureView metal) {
		super(texture, baseMipLevel, mipLevels);
		this.metal = metal;
	}

	MetalTextureView metal() {
		if (this.metal == null) {
			throw new IllegalStateException("A memoryless Metal texture cannot be sampled as a shader resource");
		}
		return this.metal;
	}

	public MetalTexture attachment() {
		return this.texture().metal();
	}

	public GpuFormat gpuFormat() {
		return super.texture().getFormat();
	}

	@Override
	public void close() {
		this.closed = true;
		if (this.metal != null) {
			this.metal.close();
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed || (this.metal != null && this.metal.isClosed());
	}

	@Override
	public MetalGpuTexture texture() {
		return (MetalGpuTexture)super.texture();
	}
}
