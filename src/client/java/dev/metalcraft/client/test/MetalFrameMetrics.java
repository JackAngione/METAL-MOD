package dev.metalcraft.client.test;

import dev.metalcraft.client.metal.MetalPassCensus;
import dev.metalcraft.client.metal.MetalGpuFrameCapture;
import dev.metalcraft.client.metal.MetalStallProbe;
import dev.metalcraft.client.metal.MetalTaskCensus;
import dev.metalcraft.client.lod.LodLoadedRenderer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Render-thread capture that retains every rendered frame of one benchmark phase.
 *
 * <p>Frame pacing is derived from consecutive render-loop end timestamps rather than from the
 * duration of the loop body, so anything that happens between frames - presentation waits, swap
 * blocking, driver stalls - is counted in the interval instead of disappearing from the total.
 *
 * <p>Every frame also carries the {@link MetalStallProbe} accumulators for the render-path work
 * that can block or allocate. Percentiles alone could only say that the worst traversal frame was
 * an order of magnitude longer than the median; the retained attribution says what it was doing.
 */
public final class MetalFrameMetrics {
	private static final int WORST_FRAMES_REPORTED = 8;

	private static final List<GarbageCollectorMXBean> COLLECTORS = ManagementFactory.getGarbageCollectorMXBeans();

	private static long[] cpuFrameTimes = new long[16384];
	private static long[] frameIntervals = new long[16384];
	private static long[] outsideLoopTimes = new long[16384];
	private static long[] stalls = new long[16384 * MetalStallProbe.slots()];
	private static int size;
	private static boolean capturing;
	private static int warmupFramesRemaining;
	private static long previousFrameEndNs;
	private static long previousCollectionCount;
	private static long previousCollectionMillis;
	private static long frameStartNs;
	private static LodLoadedRenderer.Stats lodStart = LodLoadedRenderer.stats();

	private MetalFrameMetrics() {
	}

	private static long collectionCount() {
		long total = 0L;
		for (GarbageCollectorMXBean collector : COLLECTORS) {
			long count = collector.getCollectionCount();
			if (count > 0L) {
				total += count;
			}
		}
		return total;
	}

	private static long collectionMillis() {
		long total = 0L;
		for (GarbageCollectorMXBean collector : COLLECTORS) {
			long millis = collector.getCollectionTime();
			if (millis > 0L) {
				total += millis;
			}
		}
		return total;
	}

	/**
	 * @param warmupFrames leading frames to observe but discard, covering the disturbance caused by
	 *     the gametest thread handing control back to the client
	 */
	public static synchronized void beginCapture(final int warmupFrames) {
		size = 0;
		capturing = true;
		MetalGpuFrameCapture.beginCapture();
		warmupFramesRemaining = Math.max(1, warmupFrames);
		previousFrameEndNs = 0L;
		frameStartNs = 0L;
		previousCollectionCount = collectionCount();
		previousCollectionMillis = collectionMillis();
		MetalStallProbe.setEnabled(true);
		MetalTaskCensus.reset();
		MetalPassCensus.reset();
		lodStart = LodLoadedRenderer.sample();
	}

	/** Records that the render loop resumed, so time spent outside it can be separated out. */
	public static synchronized void recordFrameStart(final long startNs) {
		if (capturing) {
			frameStartNs = startNs;
			if (warmupFramesRemaining == 0) MetalGpuFrameCapture.beginFrame();
		}
	}

	public static synchronized void recordFrame(final long cpuFrameTimeNs, final long frameEndNs) {
		if (!capturing) {
			return;
		}
		MetalGpuFrameCapture.endFrame();
		int slots = MetalStallProbe.slots();
		if (size == cpuFrameTimes.length) {
			cpuFrameTimes = Arrays.copyOf(cpuFrameTimes, size * 2);
			frameIntervals = Arrays.copyOf(frameIntervals, size * 2);
			outsideLoopTimes = Arrays.copyOf(outsideLoopTimes, size * 2);
			stalls = Arrays.copyOf(stalls, size * 2 * slots);
		}
		// Collection pauses land in the frame interval like any other stall, so they need a column
		// of their own; without one the frames they hit would read as unexplained.
		long collections = collectionCount();
		long collectionMillis = collectionMillis();
		MetalStallProbe.record(MetalStallProbe.Source.JVM_GC,
			(collectionMillis - previousCollectionMillis) * 1_000_000L,
			collections - previousCollectionCount, 0L);
		previousCollectionCount = collections;
		previousCollectionMillis = collectionMillis;
		MetalStallProbe.recordCompletedGpuWork();
		MetalPassCensus.drainCompleted();
		// Read unconditionally so the accumulators reset even for frames that are not retained.
		MetalStallProbe.takeFrame(stalls, size * slots);
		long interval = previousFrameEndNs == 0L ? 0L : frameEndNs - previousFrameEndNs;
		// Everything between the previous loop ending and this one starting: the render thread was
		// not rendering, so no probe inside the loop can see it.
		long outsideLoop = previousFrameEndNs == 0L || frameStartNs <= previousFrameEndNs
			? 0L
			: frameStartNs - previousFrameEndNs;
		previousFrameEndNs = frameEndNs;
		if (warmupFramesRemaining > 0) {
			warmupFramesRemaining--;
			lodStart = LodLoadedRenderer.sample();
			return;
		}
		if (cpuFrameTimeNs <= 0L || interval <= 0L) {
			return;
		}
		cpuFrameTimes[size] = cpuFrameTimeNs;
		frameIntervals[size] = interval;
		outsideLoopTimes[size] = outsideLoop;
		size++;
	}

	/** Reject a disturbed phase without publishing CPU or GPU timing samples. */
	public static synchronized int discardCapture() {
		capturing = false;
		int discarded = size;
		size = 0;
		MetalStallProbe.setEnabled(false);
		MetalGpuFrameCapture.endCapture();
		return discarded;
	}

	public static synchronized Phase endCapture(final String name) {
		capturing = false;
		// Taken before the probe is disabled, because the census is guarded on the same flag.
		List<MetalTaskCensus.TaskKind> tasks = MetalTaskCensus.take();
		List<MetalPassCensus.PassKind> passes = MetalPassCensus.take();
		MetalStallProbe.setEnabled(false);
		return Phase.of(name, Arrays.copyOf(cpuFrameTimes, size), Arrays.copyOf(frameIntervals, size),
			Arrays.copyOf(outsideLoopTimes, size), Arrays.copyOf(stalls, size * MetalStallProbe.slots()),
			tasks, passes, lodStart, LodLoadedRenderer.sample(), MetalGpuFrameCapture.endCapture());
	}

	/** One source's contribution to a set of frames. */
	public record StallTotal(MetalStallProbe.Source source, double totalMs, long count, long bytes) {
		String describe() {
			String scale = this.bytes == 0L
				? ""
				: String.format(Locale.ROOT, " %.1fMiB", this.bytes / (1024.0 * 1024.0));
			return String.format(Locale.ROOT, "%s=%.3fms/%d%s",
				this.source.name().toLowerCase(Locale.ROOT), this.totalMs, this.count, scale);
		}

		String toJson() {
			return String.format(Locale.ROOT, "{\"source\":\"%s\",\"totalMs\":%.3f,\"count\":%d,\"bytes\":%d}",
				this.source.name().toLowerCase(Locale.ROOT), this.totalMs, this.count, this.bytes);
		}
	}

	/** One retained frame, with everything the probe attributed to it. */
	public record FrameDetail(int index, double intervalMs, double cpuMs, double outsideLoopMs,
			List<StallTotal> stalls) {
		String describe() {
			StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
				"frame=%d intervalMs=%.3f cpuMs=%.3f outsideLoopMs=%.3f",
				this.index, this.intervalMs, this.cpuMs, this.outsideLoopMs));
			double phases = 0.0;
			for (StallTotal stall : this.stalls) {
				text.append(' ').append(stall.describe());
				// Only phases partition the loop; details sit inside them and a collection pause can
				// land in any of them, so summing everything would subtract time twice.
				if (stall.source().isPhase()) {
					phases += stall.totalMs();
				}
			}
			// What is left of the loop once its phases are removed. The phases cover runTick from
			// its first statement to its last, so this is only the sliver outside runTick itself,
			// and it reads as a few microseconds either side of zero. Anything larger means a
			// boundary has stopped matching the code it was aimed at.
			return text.append(String.format(Locale.ROOT, " unphasedMs=%.3f",
				this.intervalMs - this.outsideLoopMs - phases)).toString();
		}

		String toJson() {
			return String.format(Locale.ROOT,
				"{\"index\":%d,\"intervalMs\":%.3f,\"cpuMs\":%.3f,\"outsideLoopMs\":%.3f,\"stalls\":[%s]}",
				this.index, this.intervalMs, this.cpuMs, this.outsideLoopMs,
				String.join(",", this.stalls.stream().map(StallTotal::toJson).toList()));
		}
	}

	/** Counters at retained-frame boundaries, plus selection/upload cost percentiles. */
	public record LodPhase(LodLoadedRenderer.Stats start, LodLoadedRenderer.Stats end,
			double p50PrepareMs, double p95PrepareMs, double p99PrepareMs) { }

	/** Frame-pacing statistics for one benchmark phase. */
	public record Phase(
		String name,
		int frames,
		double durationSeconds,
		double averageFps,
		double onePercentLowFps,
		double pointOnePercentLowFps,
		double p50CpuMs,
		double p95CpuMs,
		double p99CpuMs,
		double p50IntervalMs,
		double p99IntervalMs,
		double worstIntervalMs,
		double p50AcquireMs,
		double p99AcquireMs,
		double p50OutsideLoopMs,
		double p99OutsideLoopMs,
		List<StallTotal> phaseStalls,
		List<StallTotal> tailStalls,
		List<FrameDetail> worstFrames,
		List<MetalTaskCensus.TaskKind> taskKinds,
		List<MetalPassCensus.PassKind> passKinds,
		LodPhase lod,
		MetalGpuFrameCapture.Result gpuFrame
	) {
		static Phase of(final String name, final long[] cpuFrameTimesNs, final long[] frameIntervalsNs,
				final long[] outsideLoopNs, final long[] stallsNs,
				final List<MetalTaskCensus.TaskKind> taskKinds,
				final List<MetalPassCensus.PassKind> passKinds,
				final LodLoadedRenderer.Stats lodStart, final LodLoadedRenderer.Stats lodEnd,
				final MetalGpuFrameCapture.Result gpuFrame) {
			long[] prepare = column(stallsNs, cpuFrameTimesNs.length, MetalStallProbe.Source.LOD_PREPARE, MetalStallProbe.FIELD_NANOS);
			Arrays.sort(prepare);
			LodPhase lod = new LodPhase(lodStart, lodEnd,
				prepare.length == 0 ? 0 : percentile(prepare, .50) / 1_000_000.0,
				prepare.length == 0 ? 0 : percentile(prepare, .95) / 1_000_000.0,
				prepare.length == 0 ? 0 : percentile(prepare, .99) / 1_000_000.0);
			if (cpuFrameTimesNs.length == 0) {
				return new Phase(name, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
					List.of(), List.of(), List.of(), taskKinds, passKinds, lod, gpuFrame);
			}
			long total = 0L;
			for (long interval : frameIntervalsNs) {
				total = Math.addExact(total, interval);
			}
			int frames = cpuFrameTimesNs.length;
			long[] sortedCpu = cpuFrameTimesNs.clone();
			long[] sortedIntervals = frameIntervalsNs.clone();
			long[] sortedAcquire = column(stallsNs, frames, MetalStallProbe.Source.ACQUIRE, MetalStallProbe.FIELD_NANOS);
			long[] sortedOutside = outsideLoopNs.clone();
			Arrays.sort(sortedCpu);
			Arrays.sort(sortedIntervals);
			Arrays.sort(sortedAcquire);
			Arrays.sort(sortedOutside);

			// The worst frames are ranked on interval rather than CPU time: a frame that blocks
			// outside the measured CPU section is exactly the case this is meant to explain.
			Integer[] order = new Integer[frames];
			for (int index = 0; index < frames; index++) {
				order[index] = index;
			}
			Arrays.sort(order, (left, right) -> Long.compare(frameIntervalsNs[right], frameIntervalsNs[left]));

			List<FrameDetail> worstFrames = new ArrayList<>();
			for (int rank = 0; rank < Math.min(WORST_FRAMES_REPORTED, frames); rank++) {
				int index = order[rank];
				worstFrames.add(new FrameDetail(index, frameIntervalsNs[index] / 1_000_000.0,
					cpuFrameTimesNs[index] / 1_000_000.0, outsideLoopNs[index] / 1_000_000.0,
					sum(stallsNs, new int[] {index})));
			}
			int tailSize = Math.max(1, frames / 100);
			int[] tail = new int[tailSize];
			for (int rank = 0; rank < tailSize; rank++) {
				tail[rank] = order[rank];
			}
			int[] all = new int[frames];
			for (int index = 0; index < frames; index++) {
				all[index] = index;
			}

			double seconds = total / 1_000_000_000.0;
			return new Phase(
				name,
				frames,
				seconds,
				frames / seconds,
				lowFps(sortedIntervals, 0.99),
				lowFps(sortedIntervals, 0.999),
				percentile(sortedCpu, 0.50) / 1_000_000.0,
				percentile(sortedCpu, 0.95) / 1_000_000.0,
				percentile(sortedCpu, 0.99) / 1_000_000.0,
				percentile(sortedIntervals, 0.50) / 1_000_000.0,
				percentile(sortedIntervals, 0.99) / 1_000_000.0,
				sortedIntervals[frames - 1] / 1_000_000.0,
				percentile(sortedAcquire, 0.50) / 1_000_000.0,
				percentile(sortedAcquire, 0.99) / 1_000_000.0,
				percentile(sortedOutside, 0.50) / 1_000_000.0,
				percentile(sortedOutside, 0.99) / 1_000_000.0,
				sum(stallsNs, all),
				sum(stallsNs, tail),
				List.copyOf(worstFrames),
				taskKinds,
				passKinds,
				lod,
				gpuFrame
			);
		}

		/** Totals for the named frames, ordered by descending time and excluding sources with none. */
		private static List<StallTotal> sum(final long[] stallsNs, final int[] frameIndices) {
			int slots = MetalStallProbe.slots();
			List<StallTotal> totals = new ArrayList<>();
			for (MetalStallProbe.Source source : MetalStallProbe.Source.values()) {
				int base = source.ordinal() * MetalStallProbe.FIELDS;
				long nanos = 0L;
				long count = 0L;
				long bytes = 0L;
				for (int index : frameIndices) {
					int offset = index * slots + base;
					nanos += stallsNs[offset + MetalStallProbe.FIELD_NANOS];
					count += stallsNs[offset + MetalStallProbe.FIELD_COUNT];
					bytes += stallsNs[offset + MetalStallProbe.FIELD_BYTES];
				}
				if (count != 0L) {
					totals.add(new StallTotal(source, nanos / 1_000_000.0, count, bytes));
				}
			}
			totals.sort((left, right) -> Double.compare(right.totalMs(), left.totalMs()));
			return List.copyOf(totals);
		}

		private static long[] column(final long[] stallsNs, final int frames,
				final MetalStallProbe.Source source, final int field) {
			int slots = MetalStallProbe.slots();
			int base = source.ordinal() * MetalStallProbe.FIELDS + field;
			long[] values = new long[frames];
			for (int index = 0; index < frames; index++) {
				values[index] = stallsNs[index * slots + base];
			}
			return values;
		}

		/**
		 * Mean frame rate of the slowest tail of frames, which is what a player perceives as a
		 * stutter. A single tail sample is too noisy to gate on, so the whole tail is averaged.
		 */
		private static double lowFps(final long[] sortedIntervals, final double quantile) {
			int tailStart = (int)Math.ceil(quantile * sortedIntervals.length) - 1;
			tailStart = Math.min(Math.max(tailStart, 0), sortedIntervals.length - 1);
			long total = 0L;
			for (int index = tailStart; index < sortedIntervals.length; index++) {
				total += sortedIntervals[index];
			}
			int count = sortedIntervals.length - tailStart;
			return count * 1_000_000_000.0 / total;
		}

		private static long percentile(final long[] sortedValues, final double quantile) {
			int index = Math.max(0, (int)Math.ceil(quantile * sortedValues.length) - 1);
			return sortedValues[index];
		}

		public String toLogLine() {
			return String.format(Locale.ROOT,
				"phase=%s frames=%d seconds=%.1f avgFps=%.1f onePercentLow=%.1f pointOnePercentLow=%.1f "
					+ "p50CpuMs=%.3f p95CpuMs=%.3f p99CpuMs=%.3f p50IntervalMs=%.3f p99IntervalMs=%.3f worstIntervalMs=%.3f "
					+ "p50AcquireMs=%.3f p99AcquireMs=%.3f p50OutsideLoopMs=%.3f p99OutsideLoopMs=%.3f",
				this.name, this.frames, this.durationSeconds, this.averageFps, this.onePercentLowFps,
				this.pointOnePercentLowFps, this.p50CpuMs, this.p95CpuMs, this.p99CpuMs,
				this.p50IntervalMs, this.p99IntervalMs, this.worstIntervalMs, this.p50AcquireMs, this.p99AcquireMs,
				this.p50OutsideLoopMs, this.p99OutsideLoopMs);
		}

		/** Lines attributing this phase's stalls, worst frames first. */
		public List<String> toAttributionLines() {
			List<String> lines = new ArrayList<>();
			lines.add("phase=" + this.name + " whole-phase " + describe(this.phaseStalls));
			lines.add("phase=" + this.name + " worst-1% " + describe(this.tailStalls));
			lines.add("phase=" + this.name + " tasks "
				+ String.join(" ", this.taskKinds.stream().map(MetalTaskCensus.TaskKind::describe).toList()));
			// Per-pass GPU spans. Concurrent with everything above them - the GPU runs a frame's
			// passes while the render thread records the next - and, unlike the phases, concurrent
			// with each other too, so they rank passes rather than partitioning the frame.
			lines.add("phase=" + this.name + " gpuPassSpans "
				+ String.join(" ", this.passKinds.stream().map(MetalPassCensus.PassKind::describe).toList()));
			for (FrameDetail frame : this.worstFrames) {
				lines.add("phase=" + this.name + " " + frame.describe());
			}
			return lines;
		}

		private static String describe(final List<StallTotal> totals) {
			if (totals.isEmpty()) {
				return "(nothing attributed)";
			}
			return String.join(" ", totals.stream().map(StallTotal::describe).toList());
		}

		public String toJson() {
			return String.format(Locale.ROOT,
				"{\"phase\":\"%s\",\"frames\":%d,\"seconds\":%.3f,\"averageFps\":%.2f,\"onePercentLowFps\":%.2f,"
					+ "\"pointOnePercentLowFps\":%.2f,\"p50CpuMs\":%.3f,\"p95CpuMs\":%.3f,\"p99CpuMs\":%.3f,"
					+ "\"p50IntervalMs\":%.3f,\"p99IntervalMs\":%.3f,\"worstIntervalMs\":%.3f,"
					+ "\"p50AcquireMs\":%.3f,\"p99AcquireMs\":%.3f,"
					+ "\"p50OutsideLoopMs\":%.3f,\"p99OutsideLoopMs\":%.3f,"
					+ "\"phaseStalls\":[%s],\"worstOnePercentStalls\":[%s],\"worstFrames\":[%s],"
					+ "\"tasks\":[%s],\"gpuPassSpans\":[%s],\"lod\":%s,\"gpuFrame\":%s}",
				this.name, this.frames, this.durationSeconds, this.averageFps, this.onePercentLowFps,
				this.pointOnePercentLowFps, this.p50CpuMs, this.p95CpuMs, this.p99CpuMs,
				this.p50IntervalMs, this.p99IntervalMs, this.worstIntervalMs, this.p50AcquireMs, this.p99AcquireMs,
				this.p50OutsideLoopMs, this.p99OutsideLoopMs,
				String.join(",", this.phaseStalls.stream().map(StallTotal::toJson).toList()),
				String.join(",", this.tailStalls.stream().map(StallTotal::toJson).toList()),
				String.join(",", this.worstFrames.stream().map(FrameDetail::toJson).toList()),
				String.join(",", this.taskKinds.stream().map(MetalTaskCensus.TaskKind::toJson).toList()),
				String.join(",", this.passKinds.stream().map(MetalPassCensus.PassKind::toJson).toList()),
				new com.google.gson.Gson().toJson(this.lod), new com.google.gson.Gson().toJson(this.gpuFrame));
		}
	}
}
