package dev.metalcraft.client.metal;

import java.util.Arrays;

/** Optional, bounded benchmark capture. Never waits for a GPU completion. */
public final class MetalGpuFrameCapture {
    private static boolean active;

    private MetalGpuFrameCapture() { }

    /** OS process-lifetime peaks, including startup; negative values mean unavailable. */
    public static long[] processMemoryAndThermalState() {
        return MetalNative.isLoaded() ? MetalNative.nProcessMemoryAndThermalState() : new long[]{-1, -1, -1, -1, -1};
    }

    public static void beginCapture() {
        active = MetalNative.isLoaded();
        if (active) MetalNative.nBeginGpuFrameCapture();
    }

    public static void beginFrame() { if (active) MetalNative.nBeginGpuCaptureFrame(); }
    public static void endFrame() { if (active) MetalNative.nEndGpuCaptureFrame(); }

    public static Result endCapture() {
        if (!active) return new Result(0, 0, 0, 0, 0, new double[0], new long[0], null, null, null);
        active = false;
        long[] raw = MetalNative.nEndGpuFrameCapture();
        if (raw == null || raw.length < 5 || (raw.length - 5) % 2 != 0)
            throw new IllegalStateException("Malformed GPU frame capture");
        int count = (raw.length - 5) / 2;
        double[] samples = new double[count];
        long[] buffers = new long[count];
        for (int i = 0; i < count; i++) {
            samples[i] = raw[5 + i * 2] / 1e6;
            buffers[i] = raw[6 + i * 2];
        }
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        return new Result(raw[0], raw[1], raw[2], raw[3], raw[4], samples, buffers,
                quantile(sorted, .5), quantile(sorted, .95), quantile(sorted, .99));
    }

    private static Double quantile(double[] sorted, double q) {
        return sorted.length == 0 ? null : sorted[Math.max(0, (int)Math.ceil(sorted.length * q) - 1)];
    }

    /** GPU span from first start to last end of all buffers committed on the capture
     * thread within a render frame, including gaps, rather than a sum of overlapping spans.
     * In-flight, invalid, empty and overflow frames remain explicit; no zero samples are invented.
     * Samples retain frame order. Background-thread submissions are outside this contract. */
    public record Result(long begunFrames, long pendingFrames, long invalidFrames, long emptyFrames,
                         long overflowFrames, double[] samplesMs, long[] commandBuffers,
                         Double p50Ms, Double p95Ms, Double p99Ms) { }
}
