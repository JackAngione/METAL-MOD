package dev.metalcraft.client.metal;

import com.mojang.blaze3d.buffers.GpuFence;
import java.util.concurrent.locks.LockSupport;

/** Blaze3D fence adapter over a monotonically valued Metal shared event. */
final class MetalGpuFence implements GpuFence {
	private final MetalFence metal;
	private final long value;
	private boolean closed;

	MetalGpuFence(final MetalFence metal, final long value) {
		this.metal = metal;
		this.value = value;
	}

	@Override
	public boolean awaitCompletion(final long timeoutNS) {
		if (this.closed || this.metal.isSignaled(this.value)) {
			return true;
		}
		if (timeoutNS <= 0L) {
			return false;
		}
		long started = System.nanoTime();
		do {
			LockSupport.parkNanos(Math.min(100_000L, timeoutNS));
			if (this.metal.isSignaled(this.value)) {
				return true;
			}
		} while (System.nanoTime() - started < timeoutNS);
		return false;
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.metal.close();
		}
	}
}
