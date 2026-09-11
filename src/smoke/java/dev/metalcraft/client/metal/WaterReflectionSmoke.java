package dev.metalcraft.client.metal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** GPU fixtures for Standard's bounded, single-frame water screen-space reflections. */
final class WaterReflectionSmoke {
	private static final int RESULT_COUNT = 7;
	private static final int FLOAT4_BYTES = 4 * Float.BYTES;
	private static final float EPSILON = 2.0e-5F;

	private WaterReflectionSmoke() { }

	static void run() {
		String helpers;
		try {
			helpers = resource("/assets/metalcraft/shaderpacks/standard/shared/water.metal");
		} catch (IOException error) {
			throw new AssertionError("Could not load production water helpers", error);
		}
		MetalDevice device = MetalNative.openDefaultDevice().orElseThrow();
		try (device; MetalCommandQueue queue = device.createCommandQueue()) {
			runExtent(device, queue, helpers, 16, 8);
			runExtent(device, queue, helpers, 9, 5);
		}
		System.out.println("Water reflection GPU: disabled/baseline identity, hit refinement, misses, "
			+ "off-screen and foreground rejection, even/odd extents, bounded confidence and cave sun gating passed");
	}

	private static void runExtent(final MetalDevice device, final MetalCommandQueue queue,
		final String helpers, final int width, final int height) {
		try (MetalTexture color = texture(device, MetalTexture.Format.RGBA32_FLOAT, width, height);
			 MetalTexture hitDepth = texture(device, MetalTexture.Format.DEPTH32_FLOAT, width, height);
			 MetalTexture clearDepth = texture(device, MetalTexture.Format.DEPTH32_FLOAT, width, height);
			 MetalTexture thinDepth = texture(device, MetalTexture.Format.DEPTH32_FLOAT, width, height);
			 MetalTextureView colorView = color.createView();
			 MetalTextureView hitView = hitDepth.createView();
			 MetalTextureView clearView = clearDepth.createView();
			 MetalTextureView thinView = thinDepth.createView()) {
			color.upload(queue, 0, colors(width, height));
			hitDepth.upload(queue, 0, depths(width, height, 0.25F)); // plane at view z=-4
			clearDepth.upload(queue, 0, depths(width, height, 0.0F));
			thinDepth.upload(queue, 0, depths(width, height, 1.0F / 1.5F)); // foreground plane
			for (int quality = 0; quality <= 3; quality++) {
				runTier(device, queue, colorView, hitView, clearView, thinView, helpers, quality);
			}
		}
	}

	private static void runTier(final MetalDevice device, final MetalCommandQueue queue,
		final MetalTextureView color, final MetalTextureView hitDepth, final MetalTextureView clearDepth,
		final MetalTextureView thinDepth, final String helpers, final int quality) {
		String source = "#define MC_OPTION_WATER_REFLECTION_QUALITY " + quality
			+ "\n#include <metal_stdlib>\nusing namespace metal;\n" + helpers + """
			kernel void water_reflection_fixture(device float4 *out [[buffer(0)]],
			    texture2d<float> color [[texture(0)]], depth2d<float> hitDepth [[texture(1)]],
			    depth2d<float> clearDepth [[texture(2)]], depth2d<float> thinDepth [[texture(3)]],
			    uint id [[thread_position_in_grid]]) {
			    if (id != 0u) return;
			    // Infinite-far reverse-Z perspective: ndc=(x/-z,y/-z,1/-z).
			    float4x4 projection(float4(1,0,0,0), float4(0,1,0,0),
			        float4(0,0,0,-1), float4(0,0,1,0));
			    float4x4 inverseProjection(float4(1,0,0,0), float4(0,1,0,0),
			        float4(0,0,0,1), float4(0,0,-1,0));
			    float3 surface = float3(0, 0, -2);
			    float3 normal = float3(0, 1, 0); // reflects the camera ray deeper at the center pixel
			    McWaterSsrHit hit = mc_water_screen_space_reflection(surface, normal,
			        projection, inverseProjection, color, hitDepth);
			    McWaterSsrHit clear = mc_water_screen_space_reflection(surface, normal,
			        projection, inverseProjection, color, clearDepth);
			    McWaterSsrHit offscreen = mc_water_screen_space_reflection(float3(1.95, 0, -2),
			        normalize(float3(0.45, 0, 0.89)), projection, inverseProjection, color, hitDepth);
			    McWaterSsrHit thin = mc_water_screen_space_reflection(surface, normal,
			        projection, inverseProjection, color, thinDepth);
			    McWaterSsrHit invalid = mc_water_screen_space_reflection(float3(NAN), normal,
			        projection, inverseProjection, color, hitDepth);
			    out[0] = float4(hit.color, hit.confidence);
			    out[1] = float4(clear.color, clear.confidence);
			    out[2] = float4(offscreen.color, offscreen.confidence);
			    out[3] = float4(thin.color, thin.confidence);
			    out[4] = float4(invalid.color, invalid.confidence);
			    float3 base = float3(0.10, 0.12, 0.14);
			    float4 sun = float4(0, 1, 0, 8);
			    float4 environment = float4(0.4, 0.5, 0.6, 1);
			    float3 baseline = mc_water_reflection(base, normal, float3(0,0,1), 0.3, 0,
			        sun, environment);
			    float3 missComposed = mc_water_reflection_with_ssr(base, normal, float3(0,0,1),
			        0.3, 0, sun, environment, clear);
			    out[5] = float4(missComposed - baseline, 0);
			    float3 caveWithSun = mc_water_reflection_with_ssr(base, normal, float3(0,0,1),
			        0.3, 0, sun, environment, hit);
			    float3 caveWithoutSun = mc_water_reflection_with_ssr(base, normal, float3(0,0,1),
			        0.3, 0, float4(0), environment, hit);
			    out[6] = float4(caveWithSun - caveWithoutSun, length(caveWithSun - baseline));
			}
			""";
		try (MetalComputePipeline pipeline = device.createComputePipeline(
				new MetalComputePipeline.Descriptor(source, "water_reflection_fixture"));
			 MetalBuffer results = device.createBuffer((long)RESULT_COUNT * FLOAT4_BYTES,
				 MetalBuffer.StorageMode.SHARED)) {
			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalComputePass compute = commands.beginComputePass()) {
				compute.setPipeline(pipeline);
				compute.setBuffer(0, results, 0);
				compute.setTexture(0, color);
				compute.setTexture(1, hitDepth);
				compute.setTexture(2, clearDepth);
				compute.setTexture(3, thinDepth);
				compute.dispatch(1, 1, 1, 1, 1, 1);
				compute.close();
				commands.commitAndWait();
			}
			try (MetalBuffer.Mapping mapping = results.map()) {
				ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
				if (quality <= 1) {
					for (int result = 0; result <= 4; result++) assertZero(bytes, result, "quality " + quality);
				} else {
					assertHit(bytes, 0, "quality " + quality + " retained positive refinement endpoint");
					for (int result = 1; result <= 4; result++) assertZero(bytes, result, "quality " + quality);
				}
				assertZero(bytes, 5, "quality " + quality + " miss composition");
				assertZeroRgb(bytes, 6, "quality " + quality + " cave sun gating");
				float caveSsrDelta = get(bytes, 6, 3);
				if (quality > 1 && (!Float.isFinite(caveSsrDelta) || caveSsrDelta <= EPSILON)) {
					throw new AssertionError("quality " + quality + " did not compose a nonzero cave SSR hit");
				}
			}
		}
	}

	private static MetalTexture texture(final MetalDevice device, final MetalTexture.Format format,
		final int width, final int height) {
		return device.createTexture(new MetalTexture.Descriptor(format, width, height, 1));
	}

	private static ByteBuffer colors(final int width, final int height) {
		ByteBuffer bytes = ByteBuffer.allocateDirect(width * height * 4 * Float.BYTES).order(ByteOrder.nativeOrder());
		for (int i = 0; i < width * height; i++) bytes.putFloat(0.75F).putFloat(0.25F).putFloat(0.125F).putFloat(1);
		return bytes.flip();
	}

	private static ByteBuffer depths(final int width, final int height, final float value) {
		ByteBuffer bytes = ByteBuffer.allocateDirect(width * height * Float.BYTES).order(ByteOrder.nativeOrder());
		for (int i = 0; i < width * height; i++) bytes.putFloat(value);
		return bytes.flip();
	}

	private static String resource(final String path) throws IOException {
		try (var input = WaterReflectionSmoke.class.getResourceAsStream(path)) {
			if (input == null) throw new IOException("Missing resource " + path);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void assertHit(final ByteBuffer bytes, final int result, final String label) {
		float confidence = get(bytes, result, 3);
		if (!(confidence > 0 && confidence <= 1) || !Float.isFinite(confidence)) {
			throw new AssertionError(label + " confidence was not bounded and positive: " + confidence);
		}
		float[] expected = {0.75F, 0.25F, 0.125F};
		for (int channel = 0; channel < 3; channel++) {
			if (Math.abs(get(bytes, result, channel) - expected[channel]) > EPSILON) {
				throw new AssertionError(label + " sampled the wrong hit color");
			}
		}
	}

	private static void assertZero(final ByteBuffer bytes, final int result, final String label) {
		for (int channel = 0; channel < 4; channel++) {
			if (get(bytes, result, channel) != 0.0F) {
				throw new AssertionError(label + " result " + result + " was not an exact miss");
			}
		}
	}

	private static void assertZeroRgb(final ByteBuffer bytes, final int result, final String label) {
		for (int channel = 0; channel < 3; channel++) {
			float value = get(bytes, result, channel);
			if (!Float.isFinite(value) || Math.abs(value) > EPSILON) {
				throw new AssertionError(label + " channel " + channel + " was " + value);
			}
		}
	}

	private static float get(final ByteBuffer bytes, final int result, final int channel) {
		return bytes.getFloat(result * FLOAT4_BYTES + channel * Float.BYTES);
	}
}
