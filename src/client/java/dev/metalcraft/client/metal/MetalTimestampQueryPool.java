package dev.metalcraft.client.metal;

import com.mojang.blaze3d.systems.GpuQueryPool;
import java.util.OptionalLong;

/** A non-blocking timestamp query pool backed by {@code MTLCounterSampleBuffer}. */
public final class MetalTimestampQueryPool implements GpuQueryPool {
	private final MetalDevice device;
	private final int size;
	private long handle;

	MetalTimestampQueryPool(final MetalDevice device, final long handle, final int size) {
		if (handle == 0L || size <= 0) {
			throw new IllegalArgumentException("A Metal timestamp query pool requires a handle and positive size");
		}
		this.device = device;
		this.handle = handle;
		this.size = size;
	}

	public MetalDevice device() {
		return this.device;
	}

	@Override
	public int size() {
		return this.size;
	}

	@Override
	public synchronized OptionalLong getValue(final int index) {
		return this.getValues(index, 1)[0];
	}

	@Override
	public synchronized OptionalLong[] getValues(final int index, final int count) {
		this.checkRange(index, count);
		long[] values = MetalNative.nTimestampQueryValues(this.requireOpenHandle(), index, count);
		if (values == null || values.length != count * 2) {
			throw new IllegalStateException("Metal returned malformed timestamp query results");
		}
		OptionalLong[] result = new OptionalLong[count];
		for (int offset = 0; offset < count; offset++) {
			result[offset] = values[offset * 2 + 1] == 0L
				? OptionalLong.empty()
				: OptionalLong.of(values[offset * 2]);
		}
		return result;
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
			MetalNative.nReleaseTimestampQueryPool(this.handle);
			this.handle = 0L;
		}
		this.device.forget(this);
	}

	synchronized long requireOpenHandle() {
		if (this.handle == 0L) {
			throw new IllegalStateException("Metal timestamp query pool is closed");
		}
		return this.handle;
	}

	private void checkRange(final int index, final int count) {
		if (index < 0 || count < 0 || index > this.size || count > this.size - index) {
			throw new IndexOutOfBoundsException(
				"Timestamp query range of " + count + " starting at " + index + " exceeds pool size " + this.size
			);
		}
	}
}
