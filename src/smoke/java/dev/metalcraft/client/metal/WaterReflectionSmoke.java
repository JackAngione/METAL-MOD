package dev.metalcraft.client.metal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** GPU fixtures for Standard's bounded, single-frame water screen-space reflections. */
final class WaterReflectionSmoke {
	private static final int RESULT_COUNT = 17;
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
			+ "off-screen and foreground rejection, even/odd extents, stable planar confidence, dielectric Fresnel, transparent energy/coverage, master toggle and cave sun gating passed");
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
				runTier(device, queue, colorView, hitView, clearView, thinView, helpers, quality, true);
				runTier(device, queue, colorView, hitView, clearView, thinView, helpers, quality, false);
			}
		}
	}

	private static void runTier(final MetalDevice device, final MetalCommandQueue queue,
		final MetalTextureView color, final MetalTextureView hitDepth, final MetalTextureView clearDepth,
		final MetalTextureView thinDepth, final String helpers, final int quality, final boolean enabled) {
		String source = "#define MC_OPTION_REFLECTIONS_ENABLED " + (enabled ? 1 : 0)
			+ "\n#define MC_OPTION_WATER_REFLECTION_QUALITY " + quality
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
			    // Fixed raster coordinates away from the center line: terrain flips clip Y,
			    // then Metal's viewport maps +Y to the top. A round trip alone would allow
			    // projection and reconstruction to share the same incorrect extra flip.
			    float2 extent = float2(color.get_width(), color.get_height());
			    float2 pixel;
			    float depth;
			    bool projected = mc_water_ssr_project(float3(0,1,-2), projection, extent, pixel, depth);
			    out[7] = float4(pixel / extent - float2(0.5,0.75), depth - 0.5, projected ? 0 : 1);
			    projected = mc_water_ssr_project(float3(0,-1,-2), projection, extent, pixel, depth);
			    out[8] = float4(pixel / extent - float2(0.5,0.25), depth - 0.5, projected ? 0 : 1);
			    out[9] = float4(mc_water_ssr_view_from_device_depth(extent * float2(0.5,0.75),
			        0.5, extent, inverseProjection) - float3(0,1,-2), 0);
			    out[10] = float4(mc_water_ssr_view_from_device_depth(extent * float2(0.5,0.25),
			        0.5, extent, inverseProjection) - float3(0,-1,-2), 0);
                out[11] = float4(mc_dielectric_fresnel(1, 1.333), mc_dielectric_fresnel(1, 1.5),
                    mc_dielectric_fresnel(1, 1.31), mc_dielectric_fresnel(0, 1.5));
                McWaterSsrHit fixedHit = {float3(0.8, 0.4, 0.2), 1.0, float3(0), 0.0};
                out[12] = mc_transparent_reflection(float4(base, 0.2), float3(0,0,1), float3(0,0,1),
                    0.06, 1.5, 0, float4(0), float4(0), fixedHit);
                out[13] = mc_transparent_reflection(float4(base, 0), float3(0,0,1), float3(0,0,1),
                    0.06, 1.5, 0, float4(0), float4(0), fixedHit);
                out[14] = mc_transparent_reflection(float4(base, 0.2), normal, float3(0,0,1),
                    0.06, 1.5, 0, float4(0), float4(0), clear) - float4(base, 0.2);
                out[15] = float4(mc_water_configured_reflection_with_ssr(base, float3(0,0,1), float3(0,0,1),
                    0.08, 1, float4(0), environment, fixedHit) - base, 0);
                float minimumConfidence = 1, maximumConfidence = 0;
                for (uint sample = 0; sample < 64; sample++) {
                    McWaterSsrHit planar = mc_water_screen_space_reflection(float3(0, 0, -2 - float(sample) / 64), normal,
                        projection, inverseProjection, color, hitDepth);
                    minimumConfidence = min(minimumConfidence, planar.confidence);
                    maximumConfidence = max(maximumConfidence, planar.confidence);
                }
                out[16] = float4(minimumConfidence, maximumConfidence, 0, 0);


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
				if (!enabled || quality <= 1) {
					for (int result = 0; result <= 4; result++) assertZero(bytes, result, "quality " + quality);
				} else {
					assertHit(bytes, 0, "quality " + quality + " retained positive refinement endpoint");
					for (int result = 1; result <= 4; result++) assertZero(bytes, result, "quality " + quality);
				}
				assertZero(bytes, 5, "quality " + quality + " miss composition");
                near(get(bytes, 16, 0), enabled && quality > 1 ? 1 : 0, "flat reflector minimum confidence");
                near(get(bytes, 16, 1), enabled && quality > 1 ? 1 : 0, "flat reflector maximum confidence");
                float[] iors = {1.333F, 1.5F, 1.31F};
                for (int channel = 0; channel < 3; channel++) {
                    float f = (iors[channel] - 1) / (iors[channel] + 1);
                    near(get(bytes, 11, channel), f * f, "material Fresnel");
                }
                near(get(bytes, 11, 3), 1, "grazing Fresnel");
                boolean reflecting = enabled && quality > 0;
                float[] base = {0.10F, 0.12F, 0.14F}, radiance = {0.8F, 0.4F, 0.2F};
                for (int channel = 0; channel < 3; channel++) {
                    near(get(bytes, 12, channel), reflecting
                        ? (base[channel] * 0.2F * 0.96F + radiance[channel] * 0.04F) / 0.232F
                        : base[channel], "glass energy with partial coverage");
                    near(get(bytes, 13, channel), reflecting ? radiance[channel] : base[channel], "clear glass reflection");
                }
                near(get(bytes, 12, 3), reflecting ? 0.232F : 0.2F, "glass coverage");
                near(get(bytes, 13, 3), reflecting ? 0.04F : 0, "clear glass coverage");
                assertZeroRgb(bytes, 14, "unlit cave glass identity");
                if (!reflecting) assertZero(bytes, 15, "master toggle disables water reflection");
                else if (get(bytes, 15, 0) <= EPSILON) throw new AssertionError("Master toggle did not enable water reflection");

				assertZeroRgb(bytes, 6, "quality " + quality + " cave sun gating");
				for (int result = 7; result <= 10; result++) {
					assertZeroRgb(bytes, result, "off-center raster coordinates " + result);
					if (get(bytes, result, 3) != 0) throw new AssertionError("Off-center projection rejected");
				}
				float caveSsrDelta = get(bytes, 6, 3);
				if (enabled && quality > 1 && (!Float.isFinite(caveSsrDelta) || caveSsrDelta <= EPSILON)) {
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

    private static void near(float actual, float expected, String label) {
        if (!Float.isFinite(actual) || Math.abs(actual - expected) > EPSILON)
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

	private static float get(final ByteBuffer bytes, final int result, final int channel) {
		return bytes.getFloat(result * FLOAT4_BYTES + channel * Float.BYTES);
	}
}
