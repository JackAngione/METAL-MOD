package dev.metalcraft.client.metal;

/** An acquired {@code CAMetalDrawable}; each drawable may be scheduled for presentation once. */
public final class MetalDrawable implements MetalRenderPass.ColorTarget, AutoCloseable {
	private final MetalSurface surface;
	private long handle;
	private boolean claimedForPresentation;

	MetalDrawable(final MetalSurface surface, final long handle) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal drawable handle cannot be zero");
		}
		this.surface = surface;
		this.handle = handle;
	}

	public MetalSurface surface() {
		return this.surface;
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	@Override
	public void close() {
		synchronized (this) {
			if (this.handle == 0L) {
				return;
			}
			MetalNative.nReleaseDrawable(this.handle);
			this.handle = 0L;
		}
		this.surface.forget(this);
	}

	synchronized long claimForPresentation() {
		if (this.handle == 0L) {
			throw new IllegalStateException("Metal drawable is closed");
		}
		if (this.claimedForPresentation) {
			throw new IllegalStateException("Metal drawable is already scheduled for presentation");
		}
		this.claimedForPresentation = true;
		return this.handle;
	}

	synchronized long requireOpenHandle() {
		if (this.handle == 0L) {
			throw new IllegalStateException("Metal drawable is closed");
		}
		return this.handle;
	}

	synchronized void releasePresentationClaim() {
		this.claimedForPresentation = false;
	}
}
