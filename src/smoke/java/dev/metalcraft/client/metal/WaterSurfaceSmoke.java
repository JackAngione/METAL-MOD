package dev.metalcraft.client.metal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** GPU checks for the production animated-water and reflection helpers. */
final class WaterSurfaceSmoke {
	private static final int RESULT_COUNT = 45;
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

			    out[26] = float4(mc_water_thickness(float3(0, 0, -2), float3(0, 0, -2), 0.5), 0, 0, 0);
			    out[27] = float4(mc_water_thickness(float3(0, 0, -1), float3(0, 0, -100), 0.01), 0, 0, 0);
			    out[28] = float4(mc_water_thickness(float3(0, 0, -1), float3(0, 0, -100), 0.0), 0, 0, 0);
			    out[29] = float4(mc_water_thickness(float3(0, 0, -1), float3(0, 0, -0.5), 0.8), 0, 0, 0);
			    out[30] = float4(mc_water_refraction_offset_pixels(float3(0.5, -0.5, 1), 24, 0), 0, 0);
			    out[31] = float4(mc_water_refraction_offset_pixels(float3(2, -2, 0), 24, 2), 0, 0);
			    out[32] = float4(
			        mc_water_sample_in_bounds(float2(0.5, 0.5), float2(8, 4)) ? 1.0 : 0.0,
			        mc_water_sample_in_bounds(float2(7.5, 3.5), float2(8, 4)) ? 1.0 : 0.0,
			        mc_water_sample_in_bounds(float2(7.51, 2), float2(8, 4)) ? 1.0 : 0.0,
			        mc_water_sample_in_bounds(float2(-0.1, 2), float2(8, 4)) ? 1.0 : 0.0);
			    out[33] = float4(mc_water_absorb(float3(0.8, 0.6, 0.4), float3(0.1, 0.4, 0.7), 0), 0);
			    out[34] = float4(mc_water_absorb(float3(1), float3(0), 24), 0);
			    out[35] = float4(mc_water_absorb(float3(0.8, 0.6, 0.4), float3(0.1), NAN), 0);
			    out[36] = float4(mc_water_thickness(float3(0, 0, 0), float3(0, 0, -2), 0.5), 0, 0, 0);
			    float3 foamPosition = mc_water_periodic_world_position(int3(4, 0, -7), float3(2.5, 8, 3.25));
			    out[37] = float4(mc_water_contact_foam(0.2, float3(0, 1, 0), foamPosition, 17.25, 0), 0, 0, 0);
			    out[38] = float4(mc_water_contact_foam(0.2, float3(0, 1, 0), foamPosition, 0.0, 1), 0, 0, 0);
			    out[39] = float4(mc_water_contact_foam(0.2, float3(0, 1, 0), foamPosition, 1.0, 1), 0, 0, 0);
			    out[40] = float4(mc_water_contact_foam(-1, float3(0, 1, 0), foamPosition, 17.25, 1), 0, 0, 0);
			    out[41] = float4(mc_water_contact_foam(1.0, float3(0, 1, 0), foamPosition, 17.25, 1), 0, 0, 0);
			    out[42] = float4(mc_water_contact_foam(0.2, float3(1, 0, 0), foamPosition, 17.25, 1), 0, 0, 0);
			    out[43] = float4(mc_water_contact_foam(0.2, float3(0, -1, 0), foamPosition, 17.25, 1), 0, 0, 0);
			    out[44] = float4(mc_water_contact_foam(NAN, float3(0, 1, 0), foamPosition, 17.25, 1), 0, 0, 0);
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
				assertVector(bytes, 26, new float[]{0}, EPSILON, "zero thickness");
				assertVector(bytes, 27, new float[]{24}, EPSILON, "maximum thickness clamp");
				assertVector(bytes, 28, new float[]{-1}, EPSILON, "clear depth rejection");
				assertVector(bytes, 29, new float[]{-1}, EPSILON, "foreground depth rejection");
				assertVector(bytes, 30, new float[]{0, 0}, EPSILON, "refraction-off identity");
				assertVector(bytes, 31, new float[]{8, -8}, EPSILON, "bounded refraction offset");
				assertVector(bytes, 32, new float[]{1, 1, 0, 0}, EPSILON, "image-bound rejection");
				assertVector(bytes, 33, new float[]{0.8F, 0.6F, 0.4F}, EPSILON, "zero-thickness absorption identity");
				assertAbsorptionOrdering(bytes, 34);
				assertVector(bytes, 35, new float[]{0.8F, 0.6F, 0.4F}, EPSILON, "invalid-thickness absorption identity");
				assertVector(bytes, 36, new float[]{-1}, EPSILON, "near-plane surface rejection");
				assertVector(bytes, 37, new float[]{0}, EPSILON, "zero-strength foam identity");
				assertUnitInterval(bytes, 38, true, "valid shallow contact foam");
				assertUnitInterval(bytes, 39, false, "animated shallow contact foam");
				if (Math.abs(get(bytes, 38, 0) - get(bytes, 39, 0)) <= EPSILON) {
					throw new AssertionError("Contact foam did not animate");
				}
				assertVector(bytes, 40, new float[]{0}, EPSILON, "missing/sky foam rejection");
				assertVector(bytes, 41, new float[]{0}, EPSILON, "distant opaque foam rejection");
				assertVector(bytes, 42, new float[]{0}, EPSILON, "vertical-face foam rejection");
				assertVector(bytes, 43, new float[]{0}, EPSILON, "downward-face foam rejection");
				assertVector(bytes, 44, new float[]{0}, EPSILON, "invalid-thickness foam rejection");
			}
		}
		System.out.println("Water surface GPU: normals/reflections, bounded refraction/absorption, and "
			+ "animated contact-foam identity/rejection fixtures passed");
	}

	private static void assertUnitInterval(final ByteBuffer bytes, final int result,
		final boolean requirePositive, final String label) {
		float value = get(bytes, result, 0);
		if (!Float.isFinite(value) || value < 0.0F || value > 1.0F || (requirePositive && value <= 0.0F)) {
			throw new AssertionError(label + " was outside the expected unit interval: " + value);
		}
	}

	private static void assertAbsorptionOrdering(final ByteBuffer bytes, final int result) {
		float red = get(bytes, result, 0);
		float green = get(bytes, result, 1);
		float blue = get(bytes, result, 2);
		if (!(red >= 0.0F && red < green && green < blue && blue < 1.0F)) {
			throw new AssertionError("Maximum-thickness RGB absorption was not progressive: "
				+ red + ", " + green + ", " + blue);
		}
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
