package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteOrder;
import org.joml.Vector4f;

/** GPU fixture for the typed stored opaque color/depth snapshot owner. */
final class OpaqueSnapshotSmoke {
	private static final int SOURCE_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
		| GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;

	private OpaqueSnapshotSmoke() { }

	static void run() {
		var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
		try (var queue = gpu.metal().createCommandQueue();
			 var owner = new MetalOpaqueSnapshotOwner(gpu);
			 var nativeInputs = inputs(gpu, "native", 9, 5);
			 var halfInputs = inputs(gpu, "half", 5, 3);
			 var lifetimeCopy = (MetalGpuTexture)gpu.createTexture("opaque-snapshot-lifetime", SOURCE_USAGE,
				 GpuFormat.RGBA16_FLOAT, 9, 5, 1, 1);
			 var lifetimeDepth = (MetalGpuTexture)gpu.createTexture("opaque-snapshot-lifetime-depth", SOURCE_USAGE,
				 GpuFormat.D32_FLOAT, 9, 5, 1, 1)) {
			var encoder = (MetalCommandEncoder)gpu.createCommandEncoder();
			encoder.clearColorAndDepthTextures(nativeInputs.color(), new Vector4f(2.5F, 0.25F, 0.125F, 1),
				nativeInputs.depth(), 0.0);
			MetalOpaqueSnapshotOwner.Snapshot nativeSnapshot = owner.capture(
				encoder, nativeInputs.colorView(), nativeInputs.depthView()).orElseThrow();
			if (nativeSnapshot.width() != 9 || nativeSnapshot.height() != 5
				|| nativeSnapshot.color().gpuFormat() != GpuFormat.RGBA16_FLOAT
				|| nativeSnapshot.depth().gpuFormat() != GpuFormat.D32_FLOAT
				|| nativeSnapshot.color().attachment() == nativeInputs.colorView().attachment()
				|| nativeSnapshot.depth().attachment() == nativeInputs.depthView().attachment()) {
				throw new AssertionError("Native opaque snapshot contract mismatch");
			}

			MetalGpuTextureView retiredColor = nativeSnapshot.color();
			MetalGpuTextureView retiredDepth = nativeSnapshot.depth();
			// Mutating the producers after capture must not change the stored opaque inputs.
			encoder.clearColorAndDepthTextures(nativeInputs.color(), new Vector4f(0, 0, 0, 0),
				nativeInputs.depth(), 0.9375);
			encoder.copyTextureToTexture(retiredColor.texture(), lifetimeCopy, 0, 0, 0, 0, 0, 9, 5);
			encoder.copyTextureToTexture(retiredDepth.texture(), lifetimeDepth, 0, 0, 0, 0, 0, 9, 5);
			encoder.clearColorAndDepthTextures(halfInputs.color(), new Vector4f(1.75F, 0.5F, 0.0625F, 1),
				halfInputs.depth(), 0.8125);
			MetalOpaqueSnapshotOwner.Snapshot halfSnapshot = owner.capture(
				encoder, halfInputs.colorView(), halfInputs.depthView()).orElseThrow();
			if (halfSnapshot.width() != 5 || halfSnapshot.height() != 3) {
				throw new AssertionError("Half-resolution odd snapshot extent mismatch");
			}
			if (!retiredColor.isClosed() || !retiredDepth.isClosed()) {
				throw new AssertionError("Resized opaque snapshot resources were not retired");
			}
			assertStale(nativeSnapshot);
			encoder.finishPendingWork();

			assertColor(lifetimeCopy.metal().readback(queue, 0), 2.5, 0.25, 0.125, 1.0);
			assertDepth(lifetimeDepth.metal().readback(queue, 0), 0.0);
			assertColor(halfSnapshot.color().attachment().readback(queue, 0), 1.75, 0.5, 0.0625, 1.0);
			assertDepth(halfSnapshot.depth().attachment().readback(queue, 0), 0.8125);

			MetalGpuTextureView reusedColor = halfSnapshot.color();
			MetalGpuTextureView reusedDepth = halfSnapshot.depth();
			encoder.clearColorAndDepthTextures(halfInputs.color(), new Vector4f(0.75F, 0.375F, 0.1875F, 1),
				halfInputs.depth(), 0.625);
			MetalOpaqueSnapshotOwner.Snapshot repeated = owner.capture(
				encoder, halfInputs.colorView(), halfInputs.depthView()).orElseThrow();
			if (repeated.color() != reusedColor || repeated.depth() != reusedDepth) {
				throw new AssertionError("Same-size capture reallocated opaque snapshot storage");
			}
			assertStale(halfSnapshot);
			encoder.finishPendingWork();
			assertColor(repeated.color().attachment().readback(queue, 0), 0.75, 0.375, 0.1875, 1.0);
			assertDepth(repeated.depth().attachment().readback(queue, 0), 0.625);

			MetalGpuTextureView ownedColor = repeated.color();
			if (owner.capture(encoder, ownedColor, repeated.depth()).isPresent() || owner.current().isPresent()) {
				throw new AssertionError("Sampled/read-write alias was accepted as a snapshot source");
			}
			assertStale(repeated);

			try (var wrongColor = gpu.createTexture("opaque-snapshot-wrong-color", SOURCE_USAGE,
				GpuFormat.RGBA8_UNORM, 5, 3, 1, 1);
				 var wrongColorView = gpu.createTextureView(wrongColor)) {
				if (owner.capture(encoder, (MetalGpuTextureView)wrongColorView, halfInputs.depthView()).isPresent()) {
					throw new AssertionError("Non-HDR opaque color source was accepted");
				}
			}

			try (var wrongExtent = inputs(gpu, "wrong-extent", 4, 3)) {
				if (owner.capture(encoder, halfInputs.colorView(), wrongExtent.depthView()).isPresent()) {
					throw new AssertionError("Mismatched opaque snapshot extents were accepted");
				}
			}

			try (var noCopy = gpu.createTexture("opaque-snapshot-no-copy", GpuTexture.USAGE_TEXTURE_BINDING,
				GpuFormat.RGBA16_FLOAT, 5, 3, 1, 1);
				 var noCopyView = gpu.createTextureView(noCopy)) {
				if (owner.capture(encoder, (MetalGpuTextureView)noCopyView, halfInputs.depthView()).isPresent()) {
					throw new AssertionError("Opaque source without copy usage was accepted");
				}
			}

			MetalOpaqueSnapshotOwner.Snapshot recovered = owner.capture(
				encoder, halfInputs.colorView(), halfInputs.depthView()).orElseThrow();
			if (owner.current().orElseThrow() != recovered) throw new AssertionError("Recovered snapshot was not current");
			MetalGpuTextureView closeColor = recovered.color();
			MetalGpuTextureView closeDepth = recovered.depth();
			owner.close();
			if (!closeColor.isClosed() || !closeDepth.isClosed() || owner.current().isPresent()) {
				throw new AssertionError("Opaque snapshot close did not retire resources and clear validity");
			}
			assertStale(recovered);
			try {
				owner.capture(encoder, halfInputs.colorView(), halfInputs.depthView());
				throw new AssertionError("Closed opaque snapshot owner accepted a capture");
			} catch (IllegalStateException expected) { }
		} finally {
			gpu.close();
		}
		System.out.println("Opaque snapshot: stored HDR/D32 copies, odd/native and half extents, reuse, alias fallback, stale invalidation, resize and in-flight retirement passed");
	}

	private static Inputs inputs(final MetalGpuDevice gpu, final String label, final int width, final int height) {
		MetalGpuTexture color = null, depth = null;
		MetalGpuTextureView colorView = null, depthView = null;
		try {
			color = (MetalGpuTexture)gpu.createTexture(label + " color", SOURCE_USAGE,
				GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
			colorView = (MetalGpuTextureView)gpu.createTextureView(color);
			depth = (MetalGpuTexture)gpu.createTexture(label + " depth", SOURCE_USAGE,
				GpuFormat.D32_FLOAT, width, height, 1, 1);
			depthView = (MetalGpuTextureView)gpu.createTextureView(depth);
			return new Inputs(color, colorView, depth, depthView);
		} catch (RuntimeException error) {
			if (colorView != null) colorView.close();
			if (depthView != null) depthView.close();
			if (color != null) color.close();
			if (depth != null) depth.close();
			throw error;
		}
	}

	private static void assertStale(final MetalOpaqueSnapshotOwner.Snapshot snapshot) {
		try {
			snapshot.color();
			throw new AssertionError("Stale opaque snapshot remained accessible");
		} catch (IllegalStateException expected) { }
	}

	private static void assertColor(final java.nio.ByteBuffer pixels,
		final double red, final double green, final double blue, final double alpha) {
		pixels.order(ByteOrder.nativeOrder());
		check(red, Float.float16ToFloat(pixels.getShort(0)), 0.002);
		check(green, Float.float16ToFloat(pixels.getShort(2)), 0.002);
		check(blue, Float.float16ToFloat(pixels.getShort(4)), 0.002);
		check(alpha, Float.float16ToFloat(pixels.getShort(6)), 0.002);
	}

	private static void assertDepth(final java.nio.ByteBuffer pixels, final double depth) {
		pixels.order(ByteOrder.nativeOrder());
		check(depth, pixels.getFloat(0), 0.00001);
	}

	private static void check(final double expected, final double actual, final double tolerance) {
		if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance) {
			throw new AssertionError("Expected " + expected + ", got " + actual);
		}
	}

	private record Inputs(
		MetalGpuTexture color,
		MetalGpuTextureView colorView,
		MetalGpuTexture depth,
		MetalGpuTextureView depthView
	) implements AutoCloseable {
		@Override
		public void close() {
			this.colorView.close();
			this.depthView.close();
			this.color.close();
			this.depth.close();
		}
	}
}
