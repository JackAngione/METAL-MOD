package dev.metalcraft.client.metal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** GPU checks for the production world-only underwater distortion helper. */
final class UnderwaterSurfaceSmoke {
	private static final int RESULT_COUNT = 12;
	private static final int FLOAT4_BYTES = 4 * Float.BYTES;
	private static final float EPSILON = 2.0e-5F;

	private UnderwaterSurfaceSmoke() { }

	static void run() {
		String helpers;
		try {
			helpers = resource("/assets/metalcraft/shaderpacks/standard/shared/underwater.metal");
		} catch (IOException error) {
			throw new AssertionError("Could not load production underwater helpers", error);
		}
		String source = "#include <metal_stdlib>\nusing namespace metal;\n" + helpers + """
			kernel void underwater_surface_fixture(device float4 *out [[buffer(0)]],
			    uint id [[thread_position_in_grid]]) {
			    if (id != 0u) return;
			    float2 uv = float2(0.43, 0.57);
			    float2 nativeExtent = float2(2560, 1440);
			    float2 oddExtent = float2(1919, 1079);
			    out[0] = float4(mc_underwater_uv(uv, nativeExtent, 17.25, 0), uv);
			    out[1] = float4(mc_underwater_uv(uv, nativeExtent, 17.25, -1), uv);
			    out[2] = float4(mc_underwater_uv(uv, nativeExtent, NAN, 1), uv);
			    out[3] = float4(mc_underwater_uv(uv, float2(0), 17.25, 1), uv);
			    out[4] = float4(mc_underwater_uv(uv, nativeExtent, 17.25, NAN), uv);
			    out[5] = float4(mc_underwater_uv(uv, nativeExtent, 17.25, 1), uv);
			    out[6] = float4(mc_underwater_uv(uv, oddExtent, 17.25, 1), uv);
			    out[7] = float4(mc_underwater_uv(uv, oddExtent, 1041.25, 1), uv);
			    out[8] = float4(mc_underwater_uv(float2(0, 0.5), oddExtent, 17.25, 1), float2(0, 0.5));
			    out[9] = float4(mc_underwater_uv(float2(1, 0.5), oddExtent, 17.25, 1), float2(1, 0.5));
			    out[10] = float4(mc_underwater_uv(float2(0.5, 0), nativeExtent, 17.25, 1), float2(0.5, 0));
			    out[11] = float4(mc_underwater_uv(float2(0.5, 1), nativeExtent, 17.25, 1), float2(0.5, 1));
			}
			""";

		MetalDevice device = MetalNative.openDefaultDevice().orElseThrow();
		try (device;
			 MetalComputePipeline pipeline = device.createComputePipeline(
				 new MetalComputePipeline.Descriptor(source, "underwater_surface_fixture"));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalBuffer results = device.createBuffer((long)RESULT_COUNT * FLOAT4_BYTES,
				 MetalBuffer.StorageMode.SHARED)) {
			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalComputePass compute = commands.beginComputePass()) {
				compute.setPipeline(pipeline);
				compute.setBuffer(0, results, 0);
				compute.dispatch(1, 1, 1, 1, 1, 1);
				compute.close();
				commands.commitAndWait();
			}
			try (var mapping = results.map()) {
				ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
				for (int result = 0; result <= 4; result++) assertIdentity(bytes, result, "identity case " + result);
				assertBounded(bytes, 5, 2560.0F, 1440.0F, "native extent");
				assertBounded(bytes, 6, 1919.0F, 1079.0F, "odd extent");
				assertEqual(bytes, 6, 7, "1024-second period");
				for (int result = 8; result < RESULT_COUNT; result++) {
					assertIdentity(bytes, result, "edge case " + result);
					assertInBounds(bytes, result, "edge case " + result);
				}
			}
		}
		System.out.println("Underwater surface GPU: identity, periodic, bounded odd/native, and edge fixtures passed");
	}

	private static String resource(final String path) throws IOException {
		try (var input = UnderwaterSurfaceSmoke.class.getResourceAsStream(path)) {
			if (input == null) throw new IOException("Missing resource " + path);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void assertIdentity(final ByteBuffer bytes, final int result, final String label) {
		for (int channel = 0; channel < 2; channel++) {
			float actual = get(bytes, result, channel);
			float expected = get(bytes, result, channel + 2);
			if (!Float.isFinite(actual) || actual != expected) {
				throw new AssertionError(label + " was not exact: " + actual + " != " + expected);
			}
		}
	}

	private static void assertBounded(final ByteBuffer bytes, final int result,
		final float width, final float height, final String label) {
		float x = get(bytes, result, 0);
		float y = get(bytes, result, 1);
		float sourceX = get(bytes, result, 2);
		float sourceY = get(bytes, result, 3);
		if (!Float.isFinite(x) || !Float.isFinite(y)
			|| Math.abs(x - sourceX) * width > 1.5F + EPSILON
			|| Math.abs(y - sourceY) * height > 1.5F + EPSILON) {
			throw new AssertionError(label + " displacement exceeded 1.5 pixels");
		}
		assertInBounds(bytes, result, label);
	}

	private static void assertInBounds(final ByteBuffer bytes, final int result, final String label) {
		float x = get(bytes, result, 0);
		float y = get(bytes, result, 1);
		if (!(x >= 0.0F && x <= 1.0F && y >= 0.0F && y <= 1.0F)) {
			throw new AssertionError(label + " escaped UV bounds: " + x + ", " + y);
		}
	}

	private static void assertEqual(final ByteBuffer bytes, final int first, final int second,
		final String label) {
		for (int channel = 0; channel < 2; channel++) {
			float a = get(bytes, first, channel);
			float b = get(bytes, second, channel);
			if (!Float.isFinite(a) || !Float.isFinite(b) || Math.abs(a - b) > EPSILON) {
				throw new AssertionError(label + " channel " + channel + " differed: " + a + " vs " + b);
			}
		}
	}

	private static float get(final ByteBuffer bytes, final int result, final int channel) {
		return bytes.getFloat(result * FLOAT4_BYTES + channel * Float.BYTES);
	}
}
