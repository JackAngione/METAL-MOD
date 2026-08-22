package dev.metalcraft.client.test;

import dev.metalcraft.client.metal.MetalPresentProbe;
import java.util.Arrays;
import java.util.Locale;

/**
 * Render-thread capture that retains every rendered frame of one benchmark phase.
 *
 * <p>Frame pacing is derived from consecutive render-loop end timestamps rather than from the
 * duration of the loop body, so anything that happens between frames - presentation waits, swap
 * blocking, driver stalls - is counted in the interval instead of disappearing from the total.
 */
public final class MetalFrameMetrics {
	private static long[] cpuFrameTimes = new long[16384];
	private static long[] frameIntervals = new long[16384];
	private static long[] acquireTimes = new long[16384];
	private static int size;
	private static boolean capturing;
	private static int warmupFramesRemaining;
	private static long previousFrameEndNs;

	private MetalFrameMetrics() {
	}

	/**
	 * @param warmupFrames leading frames to observe but discard, covering the disturbance caused by
	 *     the gametest thread handing control back to the client
	 */
	public static synchronized void beginCapture(final int warmupFrames) {
		size = 0;
		capturing = true;
		warmupFramesRemaining = Math.max(1, warmupFrames);
		previousFrameEndNs = 0L;
		MetalPresentProbe.setEnabled(true);
	}

	public static synchronized void recordFrame(final long cpuFrameTimeNs, final long frameEndNs) {
		if (!capturing) {
			return;
		}
		// Read unconditionally so the accumulator resets even for frames that are not retained.
		long acquireNs = MetalPresentProbe.takeFrameAcquireNanos();
		long interval = previousFrameEndNs == 0L ? 0L : frameEndNs - previousFrameEndNs;
		previousFrameEndNs = frameEndNs;
		if (warmupFramesRemaining > 0) {
			warmupFramesRemaining--;
			return;
		}
		if (cpuFrameTimeNs <= 0L || interval <= 0L) {
			return;
		}
		if (size == cpuFrameTimes.length) {
			cpuFrameTimes = Arrays.copyOf(cpuFrameTimes, size * 2);
			frameIntervals = Arrays.copyOf(frameIntervals, size * 2);
			acquireTimes = Arrays.copyOf(acquireTimes, size * 2);
		}
		cpuFrameTimes[size] = cpuFrameTimeNs;
		frameIntervals[size] = interval;
		acquireTimes[size] = acquireNs;
		size++;
	}

	public static synchronized Phase endCapture(final String name) {
		capturing = false;
		MetalPresentProbe.setEnabled(false);
		return Phase.of(name, Arrays.copyOf(cpuFrameTimes, size), Arrays.copyOf(frameIntervals, size),
			Arrays.copyOf(acquireTimes, size));
	}

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
		double p99AcquireMs
	) {
		static Phase of(final String name, final long[] cpuFrameTimesNs, final long[] frameIntervalsNs,
				final long[] acquireTimesNs) {
			if (cpuFrameTimesNs.length == 0) {
				return new Phase(name, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
			}
			long total = 0L;
			for (long interval : frameIntervalsNs) {
				total = Math.addExact(total, interval);
			}
			long[] sortedCpu = cpuFrameTimesNs.clone();
			long[] sortedIntervals = frameIntervalsNs.clone();
			long[] sortedAcquire = acquireTimesNs.clone();
			Arrays.sort(sortedCpu);
			Arrays.sort(sortedIntervals);
			Arrays.sort(sortedAcquire);
			int frames = sortedCpu.length;
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
				percentile(sortedAcquire, 0.99) / 1_000_000.0
			);
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
					+ "p50AcquireMs=%.3f p99AcquireMs=%.3f",
				this.name, this.frames, this.durationSeconds, this.averageFps, this.onePercentLowFps,
				this.pointOnePercentLowFps, this.p50CpuMs, this.p95CpuMs, this.p99CpuMs,
				this.p50IntervalMs, this.p99IntervalMs, this.worstIntervalMs, this.p50AcquireMs, this.p99AcquireMs);
		}

		public String toJson() {
			return String.format(Locale.ROOT,
				"{\"phase\":\"%s\",\"frames\":%d,\"seconds\":%.3f,\"averageFps\":%.2f,\"onePercentLowFps\":%.2f,"
					+ "\"pointOnePercentLowFps\":%.2f,\"p50CpuMs\":%.3f,\"p95CpuMs\":%.3f,\"p99CpuMs\":%.3f,"
					+ "\"p50IntervalMs\":%.3f,\"p99IntervalMs\":%.3f,\"worstIntervalMs\":%.3f,"
					+ "\"p50AcquireMs\":%.3f,\"p99AcquireMs\":%.3f}",
				this.name, this.frames, this.durationSeconds, this.averageFps, this.onePercentLowFps,
				this.pointOnePercentLowFps, this.p50CpuMs, this.p95CpuMs, this.p99CpuMs,
				this.p50IntervalMs, this.p99IntervalMs, this.worstIntervalMs, this.p50AcquireMs, this.p99AcquireMs);
		}
	}
}
