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
		 * Handing a recorded batch of binds and draws to Metal, once per batch rather than per draw.
		 *
		 * <p>Its byte count is the size of the recorded stream, so dividing by the record size gives
		 * the commands the crossing carried - which is the number that says whether batching is
		 * reaching the pass that matters.
		 */
		COMMAND_BATCH,
		/**
		 * The whole of {@code Minecraft.renderFrame}, as a container rather than a leaf.
		 *
		 * <p>It encloses {@code ACQUIRE}, {@code SUBMIT}, {@code PRESENT}, {@code LEVEL_END_FRAME},
		 * and Minecraft's own frame timer, so it is not counted toward the interval. It exists to
		 * bisect: a spike frame whose {@code render_frame} matches its interval has its missing time
		 * inside {@code renderFrame} but outside the frame timer, and one whose {@code render_frame}
		 * matches {@code cpuMs} has it somewhere earlier in {@code runTick}.
		 */
		RENDER_FRAME,
		/**
		 * Committing the frame's Metal work, at the end of the render loop.
		 *
		 * <p>Overlaps {@code SUBMIT_WAIT}, which is raised from inside it when a pending readback
		 * callback turns the submission into a blocking wait.
		 */
		SUBMIT,
		/** Presenting the drawable, after Minecraft has already stopped its own frame timer. */
		PRESENT,
		/**
		 * {@code LevelRenderer.endFrame()}, which also runs after Minecraft's frame timer stops.
		 */
		LEVEL_END_FRAME,
		/**
		 * Everything in {@code runTick} before the packet queue is drained.
		 *
		 * <p>The close-request check, a pending resource reload, the social presence handler, and
		 * advancing the delta tracker.
		 */
		CLIENT_PRE_RENDER,
		/** Draining the client's queued network packets, before any rendering happens. */
		CLIENT_PACKETS,
		/**
		 * From the end of packet processing to the end of {@code runAllTasks}.
		 *
		 * <p>Named for the task queue, and under the Fabric client gametest harness it is mostly not
		 * the task queue. The harness parks the render thread in {@code postRunTasksHook}, injected
		 * at the same {@code INVOKE runAllTasks} that closes this phase and applied closer to the
		 * call, so the handoff to the test thread lands inside this bracket. Read {@link #TASK_DRAIN}
		 * for the queue itself and the difference between the two for the harness.
		 */
		CLIENT_TASKS,
		/**
		 * The main-thread task queue's drain, timed from inside {@code runAllTasks}.
		 *
		 * <p>A detail rather than a phase: it sits inside {@code CLIENT_TASKS} and explains the part
		 * of it that is really Minecraft's.
		 */
		TASK_DRAIN,
		/**
		 * The client gametest harness blocking the render thread to hand the frame to the test thread.
		 *
		 * <p>Not the game's cost and not this renderer's, but it is inside the frame interval and it
		 * is large - 175 ms of one 180 ms traversal frame. Without a column of its own it is charged
		 * to whichever phase the injection order happens to put it in, which is how it spent months
		 * being read as Minecraft's task queue.
		 */
		HARNESS_HANDOFF,
		/** One client game tick. A frame runs up to ten of them before it renders anything. */
		CLIENT_TICK,
		/**
		 * Between the task drain returning and the tick section starting.
		 *
		 * <p>Minecraft puts little here - profiler bookkeeping, the texture manager's tick, and the
		 * first per-tick gizmo collection. Under the Fabric client gametest harness it is where the
		 * frame is handed to the test thread, and that dominates it: {@code postRunTasks} sets the
		 * client to accept tasks, enters a {@code Phaser} phase, and then blocks on
		 * {@code CLIENT_SEMAPHORE.acquire()} until the test thread gives the frame back. The
		 * semaphore wait is the long part - {@link #HARNESS_HANDOFF} covers only the phaser - and on
		 * a 177 ms traversal frame this phase held 170.8 ms of it.
		 *
		 * <p>Every measurement this project has taken runs under that harness, so read this phase as
		 * the harness unless the run is not a gametest.
		 */
		CLIENT_POST_TASKS,
		/**
		 * The tick section's own overhead, outside the ticks themselves.
		 *
		 * <p>The per-tick gizmo collections opened and closed around each {@code tick()} after the
		 * first, and draining the gizmos the ticks produced.
		 */
		CLIENT_GIZMOS,
		/**
		 * Between the last tick and {@code renderFrame}: the per-frame main-thread gizmo
		 * collection, {@code soundManager.updateSource}, and {@code mouseHandler
		 * .handleAccumulatedMovement}.
		 *
		 * <p>This is the stretch that a 50 ms pan frame attributed 45.8 ms to when it was still
		 * lumped into the unphased remainder.
		 */
		CLIENT_PRE_FRAME,
		/** After {@code renderFrame} returns: the pause transition and the delta tracker's state. */
		CLIENT_POST_RENDER,
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
		 * Consecutive render passes continued in one Metal encoder instead of opening a new one.
		 *
		 * <p>A count, not a duration - the nanoseconds are always zero, and the number after the
		 * slash is what this source exists to report. What it saves is a tile-memory resolve and
		 * reload of the pass's attachments, which happens on the GPU and so shows up in
		 * {@link MetalPassCensus} and {@code GPU_FRAME} rather than on the render thread.
		 */
		RENDER_PASS_MERGE,
		/**
		 * Time the JVM spent collecting during the frame.
		 *
		 * <p>Not a Metal call, but a collection pause lands in the frame interval exactly like a
		 * blocked acquire does, and without a column for it every such frame would be reported as
		 * unattributed.
		 */
		JVM_GC,
		/** Loaded-terrain selection and bounded uploads before dispatcher locking. */
		LOD_PREPARE;

		static final Source[] VALUES = values();

		/**
		 * Whether this source is one of the stretches that {@code runTick} is divided into.
		 *
		 * <p>Sources come in three kinds, and confusing them is what made the frame interval
		 * impossible to balance. These <em>phases</em> partition the render loop end to end, from
		 * {@code runTick}'s first statement to its last.
		 * Everything else is a <em>detail</em> that happens inside one of them - an acquire and a
		 * present sit inside {@code RENDER_FRAME}, a buffer map sits inside whichever phase asked
		 * for it, and a collection pause can land in any of them. {@code GPU_FRAME} is neither: it
		 * runs on the GPU while the render thread carries on.
		 *
		 * <p>So the unexplained remainder of a frame is its interval minus the phases, never minus
		 * every source. Summing details as well subtracts the same milliseconds two or three times:
		 * the frame that finally explained the traversal stall spent 170 ms in {@code CLIENT_TASKS}
		 * with a 47 ms collection inside it, and counting both drove the remainder to -42 ms.
		 */
		public boolean isPhase() {
			return switch (this) {
				case CLIENT_PRE_RENDER, CLIENT_PACKETS, CLIENT_TASKS, CLIENT_POST_TASKS, CLIENT_TICK,
					CLIENT_GIZMOS, CLIENT_PRE_FRAME, RENDER_FRAME, CLIENT_POST_RENDER -> true;
				default -> false;
			};
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
	/** Likewise: the handoff is bracketed from a static mixin, which has nowhere to put a field. */
	private static long handoffStartedNs;
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

	/** @see Source#HARNESS_HANDOFF */
	public static void beginHandoff() {
		handoffStartedNs = begin();
	}

	public static void endHandoff() {
		end(Source.HARNESS_HANDOFF, handoffStartedNs);
		handoffStartedNs = 0L;
	}

	/**
	 * Closes one stretch and opens the next at the same instant.
	 *
	 * <p>What {@link #begin()} and {@link #end} cannot do is partition a method: a begin/end pair
	 * per stretch leaves the gaps between the pairs uncounted, and a pair whose end sits behind a
	 * branch may never fire at all. A cursor handed from one boundary to the next has neither
	 * problem - every nanosecond between the first boundary and the last lands in exactly one
	 * source, whichever branches the frame took.
	 *
	 * @param startedNs the cursor, from {@link #begin()} or a previous split; {@code 0} records
	 *     nothing and returns {@code 0}, so a disabled probe costs one comparison per boundary
	 * @return the new cursor to pass to the next boundary
	 */
	public static long split(final Source source, final long startedNs) {
		if (startedNs == 0L) {
			return 0L;
		}
		long now = System.nanoTime();
		record(source, now - startedNs, 1L, 0L);
		return now;
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
