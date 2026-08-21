package dev.metalcraft.client.test;

import java.util.Arrays;

/** Render-thread capture used by the lifecycle benchmark to retain every frame. */
public final class MetalFrameMetrics {
	private static long[] cpuFrameTimes = new long[4096];
	private static long[] frameIntervals = new long[4096];
	private static int size;
	private static boolean capturing;
	private static boolean skipNextFrame;

	private MetalFrameMetrics() {
	}

	public static synchronized void beginCapture() {
		size = 0;
		capturing = true;
		skipNextFrame = true;
	}

	public static synchronized void recordFrame(final long cpuFrameTimeNs, final long frameIntervalNs) {
		if (!capturing) return;
		if (skipNextFrame) {
			skipNextFrame = false;
			return;
		}
		if (cpuFrameTimeNs <= 0L || frameIntervalNs <= 0L) return;
		if (size == cpuFrameTimes.length) {
			cpuFrameTimes = Arrays.copyOf(cpuFrameTimes, size * 2);
			frameIntervals = Arrays.copyOf(frameIntervals, size * 2);
		}
		cpuFrameTimes[size] = cpuFrameTimeNs;
		frameIntervals[size] = frameIntervalNs;
		size++;
	}

	public static synchronized Snapshot endCapture() {
		capturing = false;
		return new Snapshot(Arrays.copyOf(cpuFrameTimes, size), Arrays.copyOf(frameIntervals, size));
	}

	public record Snapshot(long[] cpuFrameTimesNs, long[] frameIntervalsNs) {
		public Snapshot {
			cpuFrameTimesNs = cpuFrameTimesNs.clone();
			frameIntervalsNs = frameIntervalsNs.clone();
		}
	}
}
