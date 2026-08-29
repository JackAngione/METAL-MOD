package dev.metalcraft.client.metal;

import com.mojang.blaze3d.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;

/** Blaze3D texture-view adapter retaining its Metal view until explicitly closed. */
final class MetalGpuTextureView extends GpuTextureView {
	/** Null only for a memoryless attachment, which can be attached but never sampled. */
	private final @Nullable MetalTextureView metal;

	MetalGpuTextureView(final MetalGpuTexture texture, final int baseMipLevel, final int mipLevels, final MetalTextureView metal) {
		super(texture, baseMipLevel, mipLevels);
		this.metal = metal;
	}

	static MetalGpuTextureView attachmentOnly(final MetalGpuTexture texture) {
		if (!texture.metal().isMemoryless()) {
			throw new IllegalArgumentException("Only a memoryless texture uses an attachment-only view");
		}
		return new MetalGpuTextureView(texture, 0, 1, null);
	}

	MetalTextureView metal() {
		if (this.metal == null) {
			throw new IllegalStateException("A memoryless attachment cannot be sampled as a texture view");
		}
		return this.metal;
	}

	@Override
	public void close() {
		if (this.metal != null) {
			this.metal.close();
		}
	}

	@Override
	public boolean isClosed() {
		return this.metal == null ? this.texture().isClosed() : this.metal.isClosed();
	}

	@Override
	public MetalGpuTexture texture() {
		return (MetalGpuTexture)super.texture();
	}
}
