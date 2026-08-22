package dev.metalcraft.client.metal;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Per-frame attribution of blocking and allocating work on the render thread.
 *
 * <p>The benchmark can already say that a frame took 36 ms while the CPU section reported 8 ms, but
 * not what the remaining 28 ms was. This probe closes that gap by timing every render-path operation
 * that is capable of stalling a frame - drawable acquisition, GPU waits, Metal object creation, and
 * mapping - and letting the frame recorder snapshot the accumulators once per frame. The worst
 * frames then carry their own explanation instead of needing one inferred.
 *
 * <p>Only events raised on the render thread are counted. Chunk meshing and resource loading create
 * Metal objects from worker threads, and that work does not stall the frame, so attributing it to
 * whichever frame happened to be in flight would be actively misleading.
 *
 * <p>Disabled by default: the render path pays one volatile read per event.
 */
public final class MetalStallProbe {
	/** A render-path operation that can block or allocate, and therefore can end up in a spike. */
	public enum Source {
		/** Waiting for the display to hand back a drawable. */
		ACQUIRE,
		/** Blocking on the GPU at submission because a readback callback is pending. */
		SUBMIT_WAIT,
		/** Blocking on the GPU because the transient arena being reused is still in flight. */
		ARENA_WAIT,
		/** Creating an {@code MTLBuffer}, including a new transient arena. */
		BUFFER_CREATE,
		/** Exposing an {@code MTLBuffer}'s contents as a direct {@code ByteBuffer}. */
		BUFFER_MAP,
		/** Creating an {@code MTLTexture}. */
		TEXTURE_CREATE,
		/** Releasing an {@code MTLBuffer} or {@code MTLTexture}, which walks the native registry. */
		RESOURCE_RELEASE,
		/** Creating an {@code MTLRenderPipelineState}, which compiles MSL. */
		PIPELINE_CREATE,
		/** Copying Java bytes into a staging slice before a buffer or texture upload. */
		UPLOAD_COPY,
		/** Allocating a direct {@code ByteBuffer} for Blaze3D to build one frame's data in. */
		CPU_ALLOC,
		/**
		 * GPU busy time from command buffers that completed during the frame.
		 *
		 * <p>Unlike every other source here this is not time the render thread spent blocked - the
		 * GPU runs while the CPU does. It is here because a frame whose interval is not explained by
		 * anything on the CPU is usually explained by the GPU, and until now that could only be
		 * asserted. Command buffers can overlap on the GPU and are attributed to whichever frame
		 * they finished in, so read it as an occupancy signal, not as this frame's GPU cost.
		 */
		GPU_FRAME,
		/**
		 * Time the JVM spent collecting during the frame.
		 *
		 * <p>Not a Metal call, but a collection pause lands in the frame interval exactly like a
		 * blocked acquire does, and without a column for it every such frame would be reported as
		 * unattributed.
		 */
		JVM_GC;

		static final Source[] VALUES = values();

		/**
		 * Whether this source occupies the render thread, and so accounts for part of a frame's
		 * interval.
		 *
		 * <p>Every source but one does. GPU time runs alongside the render thread rather than
		 * blocking it, so subtracting it from the interval to find what is left unexplained would
		 * count the same milliseconds twice and can drive the remainder negative.
		 */
		public boolean blocksRenderThread() {
			return this != GPU_FRAME;
		}
	}

	/** Values recorded per source: elapsed nanoseconds, event count, and bytes moved or allocated. */
	public static final int FIELDS = 3;
	public static final int FIELD_NANOS = 0;
	public static final int FIELD_COUNT = 1;
	public static final int FIELD_BYTES = 2;

	private static final int SLOTS = Source.VALUES.length * FIELDS;
	private static final AtomicLongArray ACCUMULATORS = new AtomicLongArray(SLOTS);
	/** Only ever touched by the render thread, under the {@link #isEnabled()} guard. */
	private static final long[] GPU_WORK = new long[2];
	private static volatile Thread renderThread;

	private MetalStallProbe() {
	}

	/**
	 * Starts attributing events raised on the calling thread, which must be the render thread.
	 *
	 * @param enabled {@code false} stops attribution and clears the accumulators
	 */
	public static void setEnabled(final boolean enabled) {
		renderThread = null;
		for (int slot = 0; slot < SLOTS; slot++) {
			ACCUMULATORS.set(slot, 0L);
		}
		if (enabled) {
			renderThread = Thread.currentThread();
		}
	}

	public static boolean isEnabled() {
		return renderThread == Thread.currentThread();
	}

	/** @return {@code System.nanoTime()} while recording, or {@code 0} when this event is not counted */
	public static long begin() {
		return isEnabled() ? System.nanoTime() : 0L;
	}

	/**
	 * Records one event started by {@link #begin()}.
	 *
	 * @param startedNs the value {@link #begin()} returned; {@code 0} discards the event
	 * @param bytes bytes allocated or moved, or {@code 0} when the operation moves none
	 */
	public static void end(final Source source, final long startedNs, final long bytes) {
		if (startedNs == 0L) {
			return;
		}
		record(source, System.nanoTime() - startedNs, 1L, bytes);
	}

	/** Records an event that was timed elsewhere, such as a collection pause the JVM reports. */
	public static void record(final Source source, final long nanos, final long count, final long bytes) {
		int base = source.ordinal() * FIELDS;
		ACCUMULATORS.addAndGet(base + FIELD_NANOS, nanos);
		ACCUMULATORS.addAndGet(base + FIELD_COUNT, count);
		if (bytes != 0L) {
			ACCUMULATORS.addAndGet(base + FIELD_BYTES, bytes);
		}
	}

	/**
	 * Folds the GPU time of command buffers that have completed since the last call into this frame.
	 *
	 * <p>Drained here rather than recorded by the completion handler itself, because handlers run on
	 * Metal's threads and this probe deliberately counts only what the render thread can see.
	 */
	public static void recordCompletedGpuWork() {
		if (!isEnabled() || !MetalNative.isLoaded()) {
			return;
		}
		MetalNative.nTakeGpuWork(GPU_WORK);
		if (GPU_WORK[1] != 0L) {
			record(Source.GPU_FRAME, GPU_WORK[0], GPU_WORK[1], 0L);
		}
	}

	public static void end(final Source source, final long startedNs) {
		end(source, startedNs, 0L);
	}

	/**
	 * Copies the accumulators for the frame that just ended into {@code destination} and starts the
	 * next frame.
	 *
	 * @param destination receives {@link #SLOTS} values at {@code offset}, ordered by source ordinal
	 *     then by field
	 */
	public static void takeFrame(final long[] destination, final int offset) {
		for (int slot = 0; slot < SLOTS; slot++) {
			destination[offset + slot] = ACCUMULATORS.getAndSet(slot, 0L);
		}
	}

	public static int slots() {
		return SLOTS;
	}
}
