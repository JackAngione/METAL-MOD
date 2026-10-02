package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteBuffer;

/** Actual GPU copies: narrow rows, subregions ending at buffer end, and byte-aligned fallbacks. */
final class MetalTransferSmoke {
    static void run() {
        var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
        try {
            MetalCommandEncoder encoder = (MetalCommandEncoder)gpu.createCommandEncoder();
            for (GpuFormat format : new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.R8_UNORM}) {
                int pixel = format.blockSize(), sourceWidth = 7, width = 3, height = 2;
                for (int prefix : new int[]{0, 1}) {
                    // The source ends at the final copied pixel, not at an imaginary padded row.
                    int size = prefix + (sourceWidth + 2 + width) * pixel;
                    ByteBuffer bytes = ByteBuffer.allocateDirect(size);
                    for (int i = 0; i < size; i++) bytes.put(i, (byte)(i * 17 + 3));
                    try (var input = gpu.createBuffer(() -> "transfer source", GpuBuffer.USAGE_COPY_SRC, bytes);
                         var texture = gpu.createTexture("transfer target", GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST,
                             format, width, height, 1, 1);
                         var output = gpu.createBuffer(() -> "transfer readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
                             prefix + (long)width * height * pixel)) {
                        encoder.copyBufferToTexture(input.slice(prefix, size - prefix), 2, 0, sourceWidth, height,
                            texture, 0, 0, width, height, 0, 0);
                        boolean[] called = {false};
                        Thread owner = Thread.currentThread();
                        encoder.copyTextureToBuffer(texture, output, prefix, () -> {
                            if (Thread.currentThread() != owner) throw new AssertionError("Readback left the render owner");
                            called[0] = true;
                        }, 0);
                        encoder.finishPendingWork();
                        if (!called[0]) throw new AssertionError("Readback callback missing");
                        try (var mapping = output.map(0, output.size(), true, false)) {
                            for (int y = 0; y < height; y++) for (int x = 0; x < width * pixel; x++) {
                                byte expected = bytes.get(prefix + (y * sourceWidth + 2) * pixel + x);
                                byte actual = mapping.data().get(prefix + y * width * pixel + x);
                                if (expected != actual) throw new AssertionError("Subregion copy changed pixel bytes");
                            }
                        }
                    }
                }
            }
        }
        finally { gpu.close(); }
        System.out.println("Metal transfers passed: tight R8/RGBA rows, private sources, subregions, exact bounds, unaligned fallback");
    }
}
