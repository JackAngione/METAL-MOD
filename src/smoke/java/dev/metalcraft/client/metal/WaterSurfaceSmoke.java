package dev.metalcraft.client.metal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** GPU checks for the production animated-water and reflection helpers. */
final class WaterSurfaceSmoke {
	private static final int RESULT_COUNT = 26;
	private static final int FLOAT4_BYTES = 4 * Float.BYTES;
	private static final float EPSILON = 2.0e-5F;

	private WaterSurfaceSmoke() { }

	static void run() {
		String helpers;
		try {
			helpers = resource("/assets/metalcraft/shaderpacks/standard/shared/water.metal");
		} catch (IOException error) {
			throw new AssertionError("Could not load production water helpers", error);
		}
		String source = "#include <metal_stdlib>\nusing namespace metal;\n" + helpers + """
			kernel void water_surface_fixture(
			    device float4 *out [[buffer(0)]],
			    uint id [[thread_position_in_grid]]
			) {
			    if (id != 0u) return;

			    float3 position = mc_water_periodic_world_position(int3(2, -3, 5), float3(3.25, 7.5, 11.75));
			    out[0] = float4(mc_water_animated_normal(float3(0, 1, 0), float3(0.8, 0, -0.3), position, 17.25, 0.65), 0);
			    out[1] = float4(mc_water_animated_normal(float3(0, 2, 0), float3(1, 0, 0), position, 17.25, 0.0), 0);
			    out[2] = float4(mc_water_animated_normal(float3(0), float3(0), position, 17.25, 1.0), 0);
			    out[3] = float4(mc_water_animated_normal(float3(0, 1, 0), float3(0.3, 0, 0.7), position, 17.25, 1.0), 0);
			    out[4] = float4(mc_water_animated_normal(float3(0, 1, 0), float3(0.3, 0, 0.7), position, 1041.25, 1.0), 0);

			    out[5] = float4(mc_water_periodic_world_position(int3(0), float3(16, 4, 9)), 0);
			    out[6] = float4(mc_water_periodic_world_position(int3(16, 0, 0), float3(0, 4, 9)), 0);
			    out[7] = float4(mc_water_periodic_world_position(int3(-16, 0, 0), float3(16, 4, 9)), 0);
			    out[8] = float4(mc_water_periodic_world_position(int3(0), float3(0, 4, 9)), 0);
			    out[9] = float4(mc_water_periodic_world_position(int3(2, -3, 5), float3(3.25, 7.5, 11.75)), 0);
			    out[10] = float4(mc_water_periodic_world_position(int3(258, 253, 261), float3(3.25, 7.5, 11.75)), 0);

			    out[11] = float4(
			        mc_water_fresnel(1.0, 0.0),
			        mc_water_fresnel(0.0, 0.0),
			        mc_water_fresnel(0.0, 1.0),
			        mc_water_fresnel(-2.0, -1.0));
			    float3 base = float3(0.08, 0.16, 0.24);
			    out[12] = float4(mc_water_reflection(base, float3(0, 1, 0), float3(1, 1.0e-8, 0), 0.0, 1.0,
			        float4(normalize(float3(0.2, 1, 0.1)), 64.0), float4(4, 3, 2, 1)), 0);
			    out[13] = float4(mc_water_reflection(base, float3(0, 1, 0), float3(1, 1.0e-8, 0), 1.0, 1.0,
			        float4(normalize(float3(0.2, 1, 0.1)), 64.0), float4(4, 3, 2, 1)), 0);
			    out[14] = float4(mc_water_reflection(base, float3(0), float3(0), 0.0, 1.0,
			        float4(0, 0, 0, 64.0), float4(4, 3, 2, 1)), 0);
			    out[15] = float4(mc_water_reflection(base, float3(0, 1, 0), float3(0, 1, 0), 0.3, 1.0,
			        float4(0), float4(0)), 0);
			    out[16] = float4(mc_water_animated_normal(float3(0, 1, 0), float3(0.8, 0, -0.3), out[9].xyz, 17.25, 0.65), 0);
			    out[17] = float4(mc_water_animated_normal(float3(0, 1, 0), float3(0.8, 0, -0.3), out[10].xyz, 17.25, 0.65), 0);

			    float3 edgeA = mc_water_periodic_world_position(int3(240, 0, 0), float3(15, 4, 9));
			    float3 edgeB = mc_water_periodic_world_position(int3(240, 0, 0), float3(16, 4, 9));
			    out[18] = float4(edgeA, 0);
			    out[19] = float4(edgeB, 0);
			    out[20] = float4((edgeA + edgeB) * 0.5, 0);
			    out[21] = float4(mc_water_periodic_world_position(int3(240, 0, 0), float3(15.5, 4, 9)), 0);
			    out[22] = float4(mc_water_animated_normal(float3(0, 1, 0), float3(0.8, 0, -0.3), out[20].xyz, 17.25, 0.65), 0);
			    out[23] = float4(mc_water_animated_normal(float3(0, 1, 0), float3(0.8, 0, -0.3), out[21].xyz, 17.25, 0.65), 0);
			    out[24] = float4(mc_water_animated_normal(float3(0, 1, 0), float3(0.8, 0, -0.3), out[7].xyz, 17.25, 0.65), 0);
			    out[25] = float4(mc_water_animated_normal(float3(0, 1, 0), float3(0.8, 0, -0.3), out[8].xyz, 17.25, 0.65), 0);
			}
			""";

		MetalDevice device = MetalNative.openDefaultDevice().orElseThrow();
		try (device;
			 MetalComputePipeline pipeline = device.createComputePipeline(
				 new MetalComputePipeline.Descriptor(source, "water_surface_fixture"));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalBuffer results = device.createBuffer((long)RESULT_COUNT * FLOAT4_BYTES, MetalBuffer.StorageMode.SHARED)) {
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
				assertUnitFinite(bytes, 0, "animated normal");
				assertVector(bytes, 1, new float[]{0, 1, 0}, EPSILON, "zero-strength normalized base");
				assertUnitFinite(bytes, 2, "degenerate normal/flow fallback");
				assertEqual(bytes, 3, 4, EPSILON, "1024-second animation period");
				assertEqual(bytes, 5, 6, EPSILON, "positive chunk seam");
				assertPeriodOffset(bytes, 7, 8, "negative wrapped chunk seam");
				assertEqual(bytes, 9, 10, EPSILON, "256-block world period");
				assertVector(bytes, 11, new float[]{0.02F, 1.0F, 0.02F, 1.0F}, 3.0e-5F,
					"Fresnel endpoints and clamping");
				assertFinite(bytes, 12, "roughness zero grazing reflection");
				assertFinite(bytes, 13, "roughness one grazing reflection");
				assertFinite(bytes, 14, "degenerate reflection inputs");
				assertVector(bytes, 15, new float[]{0.08F, 0.16F, 0.24F}, EPSILON,
					"missing environment/sun base fallback");
				assertEqual(bytes, 16, 17, EPSILON, "animated normal world-period invariance");
				assertVector(bytes, 18, new float[]{255, 4, 9}, EPSILON, "unwrapped edge start");
				assertVector(bytes, 19, new float[]{256, 4, 9}, EPSILON, "unwrapped edge end");
				assertVector(bytes, 20, new float[]{255.5F, 4, 9}, EPSILON, "interpolated wrap edge");
				assertEqual(bytes, 20, 21, EPSILON, "interpolated and direct wrap-edge position");
				assertEqual(bytes, 22, 23, EPSILON, "interpolated and direct wrap-edge normal");
				assertEqual(bytes, 24, 25, EPSILON, "wrapped adjacent-section normal");
			}
		}
		System.out.println("Water surface GPU: normalized/zero-strength normals, temporal and spatial periods, "
			+ "chunk seams, Fresnel endpoints, and finite roughness/degenerate reflection extremes passed");
	}

	private static String resource(final String path) throws IOException {
		try (var input = WaterSurfaceSmoke.class.getResourceAsStream(path)) {
			if (input == null) throw new IOException("Missing resource " + path);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void assertUnitFinite(final ByteBuffer bytes, final int result, final String label) {
		float x = get(bytes, result, 0);
		float y = get(bytes, result, 1);
		float z = get(bytes, result, 2);
		if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
			throw new AssertionError(label + " was not finite: " + x + ", " + y + ", " + z);
		}
		float length = (float)Math.sqrt(x * x + y * y + z * z);
		if (Math.abs(length - 1.0F) > 2.0e-4F) {
			throw new AssertionError(label + " was not unit length: " + length);
		}
	}

	private static void assertFinite(final ByteBuffer bytes, final int result, final String label) {
		for (int channel = 0; channel < 3; channel++) {
			float value = get(bytes, result, channel);
			if (!Float.isFinite(value)) {
				throw new AssertionError(label + " channel " + channel + " was " + value);
			}
		}
	}

	private static void assertEqual(final ByteBuffer bytes, final int first, final int second,
		final float tolerance, final String label) {
		float[] expected = {get(bytes, first, 0), get(bytes, first, 1), get(bytes, first, 2)};
		assertVector(bytes, second, expected, tolerance, label);
	}

	private static void assertPeriodOffset(final ByteBuffer bytes, final int first, final int second,
		final String label) {
		boolean hasPeriodOffset = false;
		for (int channel = 0; channel < 3; channel++) {
			float delta = Math.abs(get(bytes, first, channel) - get(bytes, second, channel));
			if (Math.min(delta, Math.abs(delta - 256.0F)) > EPSILON) {
				throw new AssertionError(label + " channel " + channel
					+ " differed by neither 0 nor one spatial period: " + delta);
			}
			hasPeriodOffset |= Math.abs(delta - 256.0F) <= EPSILON;
		}
		if (!hasPeriodOffset) {
			throw new AssertionError(label + " did not cross a spatial-period boundary");
		}
	}

	private static void assertVector(final ByteBuffer bytes, final int result, final float[] expected,
		final float tolerance, final String label) {
		for (int channel = 0; channel < expected.length; channel++) {
			float actual = get(bytes, result, channel);
			if (!Float.isFinite(actual) || Math.abs(actual - expected[channel]) > tolerance) {
				throw new AssertionError(label + " channel " + channel + " expected " + expected[channel]
					+ ", got " + actual);
			}
		}
	}

	private static float get(final ByteBuffer bytes, final int result, final int channel) {
		return bytes.getFloat(result * FLOAT4_BYTES + channel * Float.BYTES);
	}
}
