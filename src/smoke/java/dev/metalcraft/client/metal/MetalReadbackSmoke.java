package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.joml.Vector4f;

final class MetalReadbackSmoke {
    static void run() {
        var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
        var encoder = (MetalCommandEncoder)gpu.createCommandEncoder();
        try (var texture = gpu.createTexture("async readback", GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
                 GpuFormat.RGBA8_UNORM, 4, 4, 1, 1);
             var output = gpu.createBuffer(() -> "async output", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, 64);
             var event = gpu.metal().createFence(); var signalQueue = gpu.metal().createCommandQueue()) {
            encoder.commands().waitFor(event, 1);
            encoder.clearColorTexture(texture, new Vector4f(1, 0, 0, 1));
            int[] callbacks = {0};
            Thread owner = Thread.currentThread();
            encoder.copyTextureToBuffer(texture, output, 0, () -> {
                if (Thread.currentThread() != owner) throw new AssertionError("Readback callback changed threads");
                try (var mapped = output.map(true, false)) {
                    if ((mapped.data().get(0) & 255) != 255 || mapped.data().get(1) != 0)
                        throw new AssertionError("Readback callback ran before GPU copy completed");
                }
                callbacks[0]++;
            }, 0);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean timedOut = new AtomicBoolean();
            // A watchdog releases the GPU if submit incorrectly blocks, so a regression fails
            // instead of deadlocking the entire smoke suite.
            Thread signal = Thread.ofPlatform().daemon(true).start(() -> {
                try {
                    timedOut.set(!release.await(2, TimeUnit.SECONDS));
                    try (var commands = signalQueue.createCommandBuffer()) {
                        commands.signal(event, 1);
                        commands.commitAndWait();
                    }
                } catch (InterruptedException error) { throw new AssertionError(error); }
            });
            try {
                encoder.submit();
                if (timedOut.get() || callbacks[0] != 0) throw new AssertionError("Readback blocked submit or ran prematurely");
            } finally {
                release.countDown();
                try { signal.join(); } catch (InterruptedException error) { throw new AssertionError(error); }
            }
            encoder.finishPendingWork();
            encoder.submit();
            if (callbacks[0] != 1) throw new AssertionError("Readback callback missing or duplicated");

            encoder.copyTextureToBuffer(texture, output, 0, () -> { throw new IllegalStateException("expected callback failure"); }, 0);
            encoder.copyTextureToBuffer(texture, output, 0, () -> callbacks[0]++, 0);
            try {
                encoder.finishPendingWork();
                throw new AssertionError("Callback exception was lost");
            } catch (IllegalStateException expected) {
                if (!expected.getMessage().equals("expected callback failure")) throw expected;
            }
            encoder.finishPendingWork();
            if (callbacks[0] != 2) throw new AssertionError("Failing callback skipped or repeated another callback");
        } finally { gpu.close(); }
        System.out.println("Metal readbacks passed: nonblocking submit, GPU dependency, owner-thread callbacks, error cleanup");
    }
}
