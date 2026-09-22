package dev.metalcraft.client.metal;

/** Exercises real completion callbacks, multi-buffer frames and capture isolation. */
public final class MetalGpuFrameCaptureSmoke {
    public static void main(String[] args) {
        try (var device = MetalNative.openDefaultDevice().orElseThrow()) { run(device); }
    }
    public static void run(MetalDevice device) {
        try (var queue = device.createCommandQueue();
             var texture = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 128, 128, 1))) {
            MetalGpuFrameCapture.beginCapture();
            MetalGpuFrameCapture.beginFrame();
            clear(queue, texture);
            clear(queue, texture);
            MetalGpuFrameCapture.endFrame();
            MetalGpuFrameCapture.beginFrame();
            MetalGpuFrameCapture.endFrame();
            var result = MetalGpuFrameCapture.endCapture();
            if (result.begunFrames() != 2 || result.emptyFrames() != 1 || result.pendingFrames() != 0
                    || result.invalidFrames() != 0 || result.samplesMs().length != 1
                    || result.commandBuffers()[0] != 2 || !(result.p50Ms() > 0))
                throw new AssertionError("GPU frame aggregation lost, double-counted or invented a sample: " + result);
            // An unsealed frame is incomplete, even if all its work already completed.
            MetalGpuFrameCapture.beginCapture();
            MetalGpuFrameCapture.beginFrame();
            clear(queue, texture);
            if (MetalGpuFrameCapture.endCapture().pendingFrames() != 1)
                throw new AssertionError("An unfinished frame was accepted");
            MetalGpuFrameCapture.beginCapture();
            MetalGpuFrameCapture.beginFrame();
            clear(queue, texture);
            MetalGpuFrameCapture.endFrame();
            result = MetalGpuFrameCapture.endCapture();
            if (result.begunFrames() != 1 || result.commandBuffers().length != 1 || result.commandBuffers()[0] != 1)
                throw new AssertionError("A previous capture contaminated the next one");
            // Hold a real GPU completion across the phase reset. A late callback must
            // not mutate the reused slot, even before the new phase submits its first frame.
            try (var event = device.createFence(); var signalQueue = device.createCommandQueue();
                 var delayed = queue.createCommandBuffer()) {
                MetalGpuFrameCapture.beginCapture();
                MetalGpuFrameCapture.beginFrame();
                delayed.waitFor(event, 1);
                try (var pass = delayed.beginRenderPass(new MetalRenderPass.Descriptor(
                        MetalRenderPass.ColorAttachment.clear(texture, .1, .2, .3, 1)))) { }
                delayed.commit();
                MetalGpuFrameCapture.endFrame();
                var pending = MetalGpuFrameCapture.endCapture();
                MetalGpuFrameCapture.beginCapture();
                try (var signal = signalQueue.createCommandBuffer()) {
                    signal.signal(event, 1);
                    signal.commitAndWait();
                }
                delayed.waitUntilCompleted();
                MetalGpuFrameCapture.beginFrame();
                clear(queue, texture);
                MetalGpuFrameCapture.endFrame();
                result = MetalGpuFrameCapture.endCapture();
                if (pending.pendingFrames() != 1 || result.pendingFrames() != 0
                        || result.samplesMs().length != 1 || result.commandBuffers()[0] != 1)
                    throw new AssertionError("A late GPU callback contaminated the next capture");
            }
        } finally { MetalGpuFrameCapture.endCapture(); }
        System.out.println("GPU frame capture passed: multi-buffer spans, empty/incomplete frames and phase isolation");
    }

    private static void clear(MetalCommandQueue queue, MetalTexture texture) {
        try (var commands = queue.createCommandBuffer()) {
            try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
                    MetalRenderPass.ColorAttachment.clear(texture, .2, .3, .4, 1)))) { }
            commands.commitAndWait();
        }
    }
}
