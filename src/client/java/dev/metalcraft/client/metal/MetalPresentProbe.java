package dev.metalcraft.client.metal;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-frame timing of drawable acquisition.
 *
 * <p>Acquisition is where a frame waits for the display. When the presented drawables are not
 * being consumed as fast as they are produced, {@code nextDrawable} blocks, and that wait lands
 * outside every CPU section the benchmark measures - which is exactly the blind spot that left an
 * unexplained gap between 9 ms of per-frame CPU work and a 15 ms frame interval.
 *
 * <p>Disabled by default so the render path pays only a volatile read per frame.
 */
public final class MetalPresentProbe {
	private static volatile boolean enabled;
	private static final AtomicLong FRAME_ACQUIRE_NANOS = new AtomicLong();

	private MetalPresentProbe() {
	}

	public static void setEnabled(final boolean value) {
		FRAME_ACQUIRE_NANOS.set(0L);
		enabled = value;
	}

	public static boolean isEnabled() {
		return enabled;
	}

	public static void recordAcquire(final long nanos) {
		if (enabled) {
			FRAME_ACQUIRE_NANOS.addAndGet(nanos);
		}
	}

	/** Reads the accumulated acquire time for the frame that just ended and starts the next one. */
	public static long takeFrameAcquireNanos() {
		return FRAME_ACQUIRE_NANOS.getAndSet(0L);
	}
}
