package dev.metalcraft.client.metal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ranks render passes by how long each one occupies the GPU.
 *
 * <p>{@link MetalStallProbe.Source#GPU_FRAME} can already say that the GPU was busy for 3.4 ms
 * while the render thread waited 4.55 ms on a drawable, which was enough to tell a GPU-bound run
 * from a display-paced one. It cannot say which pass those milliseconds were in, and that is the
 * question the remaining GPU-side work turns on: storing a dead attachment as {@code DontCare} or
 * merging two compatible passes is only worth doing to a pass that is actually costing something.
 *
 * <p>So this counts one level below the frame, keyed by the label Blaze3D gives each pass. The
 * timing is taken by the GPU at the pass's own stage boundaries and resolved when its command
 * buffer completes; see the pass census block in {@code metalcraft.m} for why those are the only
 * boundaries available on Apple silicon, and why the resolved values need no conversion.
 *
 * <p><b>What a pass reports is a span, and spans overlap - so they cannot be added.</b> The value
 * is the wall time from the pass's vertex stage starting to its fragment stage ending, and Apple's
 * GPU pipelines one pass's tiling against the previous pass's fragment work. Measured directly on
 * an M4 Max with four passes in one command buffer, the third pass ran entirely inside the second's
 * span, and the spans summed to 1.30x the command buffer's own GPU time; fragment-only spans still
 * summed to 1.15x. A pass's span also includes time it spent waiting rather than working.
 *
 * <p>So read these the way {@code GPU_FRAME} is read - as occupancy, for ranking passes against
 * each other - and never as a partition of the frame's GPU time. There is no additive decomposition
 * available at this sampling granularity. The aggregate below is named {@code sumOfSpans} rather
 * than {@code total} for that reason: this project has already lost time once to a residual driven
 * to -42 ms by summing figures that overlapped.
 *
 * <p>Labels are interned to a small integer here, so the render path hands native code an int per
 * pass rather than a string. There are only {@link #kinds()} of them: a run that somehow presents
 * more distinct labels than that charges the rest to {@link #OTHER_KIND}, because losing the name
 * of a rare pass is better than growing an unbounded table on the render path.
 *
 * <p>Only passes begun while {@link MetalStallProbe#isEnabled()} are timed. A pass that is not
 * timed costs one comparison, and the sample buffer is never created at all.
 */
public final class MetalPassCensus {
	/** A pass that is not being measured, and which therefore carries no counter samples. */
	public static final int UNTIMED_KIND = -1;
	private static final int REPORTED_KINDS = 12;

	/** Interned on the render thread while capturing, and read on the same thread when reporting. */
	private static final Map<String, Integer> KINDS = new HashMap<>();
	private static final List<String> NAMES = new ArrayList<>();
	private static long[] drained = new long[0];
	private static long[] totals = new long[0];
	private static int otherKind = UNTIMED_KIND;

	private MetalPassCensus() {
	}

	/** The kind reserved for passes beyond the table's capacity, or {@link #UNTIMED_KIND}. */
	public static int otherKind() {
		return otherKind;
	}

	public static int kinds() {
		return MetalNative.isLoaded() ? MetalNative.nGpuPassKinds() : 0;
	}

	/** Clears the census and starts a new phase, discarding anything the GPU has already reported. */
	public static void reset() {
		KINDS.clear();
		NAMES.clear();
		otherKind = UNTIMED_KIND;
		int kinds = kinds();
		if (drained.length != kinds * 2) {
			drained = new long[kinds * 2];
			totals = new long[kinds * 2];
		} else {
			java.util.Arrays.fill(totals, 0L);
		}
		if (kinds > 0) {
			// Passes that completed before the phase began belong to the previous one.
			MetalNative.nTakeGpuPassWork(drained);
		}
	}

	/**
	 * The kind to time {@code label} under, interning it on first sight.
	 *
	 * @return {@link #UNTIMED_KIND} when nothing is being measured, so the caller skips the label
	 */
	public static int kindFor(final String label) {
		if (!MetalStallProbe.isEnabled() || label == null || kinds() == 0) {
			return UNTIMED_KIND;
		}
		Integer existing = KINDS.get(label);
		if (existing != null) {
			return existing;
		}
		// The last kind is kept for everything that does not fit, so a full table still accounts
		// for the time rather than dropping it.
		int capacity = kinds();
		if (NAMES.size() >= capacity - 1) {
			if (otherKind == UNTIMED_KIND) {
				otherKind = capacity - 1;
				NAMES.add("(other passes)");
			}
			return otherKind;
		}
		int kind = NAMES.size();
		NAMES.add(label);
		KINDS.put(label, kind);
		return kind;
	}

	/** Interned kind names in slot order; does not truncate to the reporter's 12-cap. */
	static List<String> internedNames() {
		return List.copyOf(NAMES);
	}

	/** Folds the GPU time of passes whose command buffers completed since the last call. */
	public static void drainCompleted() {
		if (!MetalStallProbe.isEnabled() || !MetalNative.isLoaded() || drained.length == 0) {
			return;
		}
		MetalNative.nTakeGpuPassWork(drained);
		for (int slot = 0; slot < drained.length; slot++) {
			totals[slot] += drained[slot];
		}
	}

	/**
	 * One pass label's GPU occupancy across a phase.
	 *
	 * @param totalMs summed span, which overlaps other passes' spans and is comparable between
	 *     labels but not addable across them
	 */
	public record PassKind(String name, double totalMs, long count) {
		public String describe() {
			return String.format(Locale.ROOT, "%s=%.3fms/%d", this.name, this.totalMs, this.count);
		}

		public String toJson() {
			return String.format(Locale.ROOT, "{\"pass\":\"%s\",\"totalMs\":%.3f,\"count\":%d}",
				this.name.replace("\\", "\\\\").replace("\"", "\\\""), this.totalMs, this.count);
		}
	}

	/**
	 * @return the summed span across every pass, followed by the passes that occupied the GPU
	 *     longest this phase, at most {@value #REPORTED_KINDS} of them
	 */
	public static List<PassKind> take() {
		drainCompleted();
		List<PassKind> passes = new ArrayList<>(NAMES.size());
		long totalNanos = 0L;
		long totalCount = 0L;
		for (int kind = 0; kind < NAMES.size() && kind * 2 + 1 < totals.length; kind++) {
			long nanos = totals[kind * 2];
			long count = totals[kind * 2 + 1];
			totalNanos += nanos;
			totalCount += count;
			if (count != 0L) {
				passes.add(new PassKind(NAMES.get(kind), nanos / 1_000_000.0, count));
			}
		}
		passes.sort((left, right) -> Double.compare(right.totalMs(), left.totalMs()));
		// Reported even when it is zero, for the reason MetalTaskCensus reports its own total: a
		// phase whose passes were never sampled and a phase with nothing to sample are different
		// findings, and a line that vanishes cannot tell them apart. Named for what it actually is,
		// because these spans overlap and a reader who adds them against the frame will be wrong.
		List<PassKind> reported = new ArrayList<>(1 + Math.min(REPORTED_KINDS, passes.size()));
		reported.add(new PassKind("sumOfSpans", totalNanos / 1_000_000.0, totalCount));
		reported.addAll(passes.subList(0, Math.min(REPORTED_KINDS, passes.size())));
		return List.copyOf(reported);
	}
}
