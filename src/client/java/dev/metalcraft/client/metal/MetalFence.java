package dev.metalcraft.client.metal;

import java.util.concurrent.atomic.AtomicLong;

/** A monotonically valued GPU fence backed by {@code MTLSharedEvent}. */
public final class MetalFence implements AutoCloseable {
	private final MetalDevice device;
	private final AtomicLong nextValue = new AtomicLong(1L);
	private long handle;

	MetalFence(final MetalDevice device, final long handle) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal fence handle cannot be zero");
		}
		this.device = device;
		this.handle = handle;
	}

	public MetalDevice device() {
		return this.device;
	}

	public long reserveValue() {
		long value = this.nextValue.getAndIncrement();
		if (value <= 0L) {
			throw new IllegalStateException("Metal fence value space is exhausted");
		}
		return value;
	}

	public synchronized long completedValue() {
		return MetalNative.nFenceValue(this.requireOpenHandle());
	}

	public synchronized boolean isSignaled(final long value) {
		if (value <= 0L) {
			throw new IllegalArgumentException("Metal fence values must be positive");
		}
		return this.completedValue() >= value;
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
			MetalNative.nReleaseFence(this.handle);
			this.handle = 0L;
		}
		this.device.forget(this);
	}

	synchronized long requireOpenHandle() {
		if (this.handle == 0L) {
			throw new IllegalStateException("Metal fence is closed");
		}
		return this.handle;
	}
}
