package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteOrder;
import org.joml.Vector4f;

final class MetalAttachmentSmoke {
    static void run() {
        var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
        var encoder = (MetalCommandEncoder)gpu.createCommandEncoder();
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
        try (var color = gpu.createTexture("clear color", usage, GpuFormat.RGBA8_UNORM, 8, 8, 1, 1);
             var depth = gpu.createTexture("clear depth", usage, GpuFormat.D32_FLOAT, 8, 8, 1, 1);
             var colorView = gpu.createTextureView(color); var depthView = gpu.createTextureView(depth);
             var queue = gpu.metal().createCommandQueue()) {
            MetalStallProbe.setEnabled(true);
            long[] stats = new long[MetalStallProbe.slots()];
            MetalStallProbe.takeFrame(stats, 0);
            encoder.clearColorAndDepthTextures(color, new Vector4f(1, 0, 0, 1), depth, .75);
            encoder.createRenderPass(RenderPassDescriptor.create(() -> "load after clear")
                .withColorAttachment(colorView).withDepthAttachment(depthView)
                .withRenderArea(new RenderPass.RenderArea(0, 0, 8, 8)));
            encoder.submitRenderPass();
            MetalStallProbe.takeFrame(stats, 0);
            boolean merging = Boolean.parseBoolean(System.getProperty("metalcraft.passMerging", "true"));
            long merges = stats[MetalStallProbe.Source.RENDER_PASS_MERGE.ordinal() * MetalStallProbe.FIELDS + MetalStallProbe.FIELD_COUNT];
            if (merging && merges != 1 || !merging && merges != 0) throw new AssertionError("Clear pass was not folded correctly");
            encoder.clearDepthTexture(depth, .25);
            encoder.finishPendingWork();
            assertColor(((MetalGpuTexture)color).metal().readback(queue, 0), 255, 0);
            var depths = ((MetalGpuTexture)depth).metal().readback(queue, 0).order(ByteOrder.nativeOrder());
            if (depths.getFloat(0) != .25f) throw new AssertionError("Depth overwrite changed clear value");

            encoder.clearColorAndDepthTextures(color, new Vector4f(0, 1, 0, 1), depth, .5);
            // A partial clear must preserve everything outside its rectangle.
            encoder.clearColorAndDepthTextures(color, new Vector4f(1, 0, 0, 1), depth, .1, 0, 0, 4, 4);
            encoder.finishPendingWork();
            var pixels = ((MetalGpuTexture)color).metal().readback(queue, 0);
            int red = 0, green = 0;
            for (int i = 0; i < 64; i++) {
                if ((pixels.get(i * 4) & 255) == 255) red++;
                if ((pixels.get(i * 4 + 1) & 255) == 255) green++;
            }
            if (red != 16 || green != 48) throw new AssertionError("Partial clear discarded live color pixels");
        } finally { MetalStallProbe.setEnabled(false); gpu.close(); }
        System.out.println("Metal attachments passed: clear folding, depth-only overwrite, preserved color, partial clear");
    }

    private static void assertColor(java.nio.ByteBuffer pixels, int red, int green) {
        for (int i = 0; i < pixels.remaining(); i += 4)
            if ((pixels.get(i) & 255) != red || (pixels.get(i + 1) & 255) != green)
                throw new AssertionError("Depth clear discarded live color");
    }
}
