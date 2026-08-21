package dev.metalcraft.client.metal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** An owned {@code CAMetalLayer} attached to a GLFW Cocoa content view. */
public final class MetalSurface implements AutoCloseable {
	private final MetalDevice device;
	private final Set<MetalDrawable> drawables = Collections.newSetFromMap(new IdentityHashMap<>());
	private long handle;
	private int width;
	private int height;
	private boolean closing;

	MetalSurface(final MetalDevice device, final long handle, final int width, final int height) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal surface handle cannot be zero");
		}
		this.device = device;
		this.handle = handle;
		this.width = positiveDimension(width, "width");
		this.height = positiveDimension(height, "height");
	}

	public MetalDevice device() {
		return this.device;
	}

	public synchronized void resize(final int width, final int height) {
		int checkedWidth = positiveDimension(width, "width");
		int checkedHeight = positiveDimension(height, "height");
		MetalNative.nResizeSurface(this.requireOpenHandle(), checkedWidth, checkedHeight);
		this.width = checkedWidth;
		this.height = checkedHeight;
	}

	public synchronized void setDisplaySyncEnabled(final boolean enabled) {
		MetalNative.nSetSurfaceDisplaySync(this.requireOpenHandle(), enabled);
	}

	public synchronized Optional<MetalDrawable> acquireDrawable() {
		long drawableHandle = MetalNative.nAcquireDrawable(this.requireOpenHandle());
		if (drawableHandle == 0L) {
			return Optional.empty();
		}
		MetalDrawable drawable = new MetalDrawable(this, drawableHandle);
		this.drawables.add(drawable);
		return Optional.of(drawable);
	}

	public synchronized int width() {
		return this.width;
	}

	public synchronized int height() {
		return this.height;
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	@Override
	public void close() {
		List<MetalDrawable> ownedDrawables;
		synchronized (this) {
			if (this.handle == 0L || this.closing) {
				return;
			}
			this.closing = true;
			ownedDrawables = new ArrayList<>(this.drawables);
		}

		for (MetalDrawable drawable : ownedDrawables) {
			drawable.close();
		}

		synchronized (this) {
			MetalNative.nReleaseSurface(this.handle);
			this.handle = 0L;
			this.drawables.clear();
			this.closing = false;
		}
		this.device.forget(this);
	}

	synchronized void forget(final MetalDrawable drawable) {
		this.drawables.remove(drawable);
	}

	private long requireOpenHandle() {
		if (this.handle == 0L || this.closing) {
			throw new IllegalStateException("Metal surface is closed");
		}
		return this.handle;
	}

	private static int positiveDimension(final int dimension, final String name) {
		if (dimension <= 0) {
			throw new IllegalArgumentException("Metal surface " + name + " must be positive");
		}
		return dimension;
	}
}
