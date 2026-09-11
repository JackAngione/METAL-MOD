package dev.metalcraft.client.metal;

import dev.metalcraft.client.lod.LodMeshResidency;
import dev.metalcraft.client.lod.TerrainSnapshot;

/** Real world-queue completion, including abandoned draws and repeated command-buffer rotations. */
public final class MetalLodTimelineSmoke {
    public static void run() {
        var device = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
        try (var residency = new LodMeshResidency<MetalBuffer>(4096);
             var target = device.metal().createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 4, 4, 1))) {
            var encoder = (MetalCommandEncoder)device.createCommandEncoder();
            check(device.completedResourceSubmission() == 0, "unused timeline starts at zero");
            long previous = 0;
            for (int iteration = 0; iteration < 20; iteration++) {
                var key = new LodMeshResidency.Key(new TerrainSnapshot.Key(1, "fixture", iteration, 0, 0, 1, 1), 1);
                check(residency.upload(key, 4096, () -> device.metal().createBuffer(4096, MetalBuffer.StorageMode.SHARED)), "admit buffer");
                long submission = device.reserveResourceSubmission();
                check(submission > previous && submission == device.reserveResourceSubmission(), "one monotonic ID per submission");
                MetalBuffer resource = residency.use(key, submission);
                if (iteration % 2 == 0) {
                    device.encodeNativePass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(target, 1, 0, 0, 1)),
                            "LOD completion fixture", pass -> {
                                // Reserving in a live pass must not end it or create another encoder.
                                check(device.reserveResourceSubmission() == submission, "reservation preserves active pass");
                                pass.setVertexBuffer(16, resource, 0);
                            });
                }
                residency.invalidate(ignored -> true);
                residency.beginFrame(device.completedResourceSubmission());
                check(residency.chargedBytes() == 4096 && !resource.isClosed(), "unsubmitted borrow stays charged");
                encoder.submit();
                // Only the smoke harness polls until completion. Production gets one nonblocking poll.
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (device.completedResourceSubmission() < submission && System.nanoTime() < deadline) Thread.onSpinWait();
                check(device.completedResourceSubmission() >= submission, "world queue signaled completion");
                residency.beginFrame(device.completedResourceSubmission());
                check(residency.chargedBytes() == 0 && resource.isClosed(), "completed borrow releases resident budget");
                previous = submission;
            }
            encoder.submit();
            check(device.completedResourceSubmission() == previous, "empty unreserved submit adds no event value");
        } finally { device.close(); }
        System.out.println("LOD world-queue timeline passed: active-pass reservations, deferred residency, empty borrows, 20 submissions");
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
