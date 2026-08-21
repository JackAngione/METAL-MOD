package dev.metalcraft.client.metal;

/** An owned full-range view of an {@link MetalTexture}. */
public final class MetalTextureView implements AutoCloseable {
	private final MetalTexture texture;
	private long handle;

	MetalTextureView(final MetalTexture texture, final long handle) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal texture-view handle cannot be zero");
		}
		this.texture = texture;
		this.handle = handle;
	}

	public MetalTexture texture() {
		return this.texture;
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	synchronized long requireOpenHandle() {
		if (this.handle == 0L) {
			throw new IllegalStateException("Metal texture view is closed");
		}
		return this.handle;
	}

	@Override
	public void close() {
		synchronized (this) {
			if (this.handle == 0L) {
				return;
			}
			MetalNative.nReleaseTextureView(this.handle);
			this.handle = 0L;
		}
		this.texture.forget(this);
	}
}
