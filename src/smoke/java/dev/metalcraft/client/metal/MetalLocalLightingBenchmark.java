package dev.metalcraft.client.metal;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** GPU timings for the required 0/64/256-light and 0/4-shadow local-light profiles. */
public final class MetalLocalLightingBenchmark {
	private static final String MSL = """
		#include <metal_stdlib>
		using namespace metal;

		struct Varyings { float4 position [[position]]; float2 uv; };
		vertex Varyings local_vertex(uint id [[vertex_id]]) {
		    const float2 p[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    float2 position = p[id % 3];
		    return {float4(position, 0.5, 1.0), position * 0.5 + 0.5};
		}

		struct Settings { uint globalCount; uint tileCount; uint shadowCount; uint seed; };
		struct Light { float4 positionRadius; float4 colorIntensity; };
		fragment float4 local_fragment(
		    Varyings in [[stage_in]],
		    constant Settings &settings [[buffer(0)]],
		    device const Light *lights [[buffer(1)]],
		    depth2d_array<float> shadows [[texture(0)]],
		    sampler nearestSampler [[sampler(0)]]) {
		    float3 normal = normalize(float3(0.2, 0.8, 0.55));
		    float3 position = float3((in.uv - 0.5) * 20.0, -8.0);
		    float3 result = float3(0.025);
		    uint count = min(settings.tileCount, min(settings.globalCount, 64u));
		    for (uint index = 0; index < count; ++index) {
		        Light light = lights[index];
		        float3 delta = light.positionRadius.xyz - position;
		        float distanceSquared = max(dot(delta, delta), 0.25);
		        float radius = light.positionRadius.w;
		        float window = saturate(1.0 - distanceSquared / (radius * radius));
		        float visibility = 1.0;
		        if (index < settings.shadowCount) {
		            visibility = 0.0;
		            for (int y = -1; y <= 1; ++y) for (int x = -1; x <= 1; ++x) {
		                visibility += shadows.sample(
		                    nearestSampler, in.uv + float2(x, y) / 256.0, index * 6u
		                ) > 0.4 ? 1.0 : 0.0;
		            }
		            visibility /= 9.0;
		        }
		        float diffuse = saturate(dot(normal, normalize(delta)));
		        result += light.colorIntensity.rgb * light.colorIntensity.w * diffuse
		            * window * window / distanceSquared * visibility;
		    }
		    return float4(result, 1.0);
		}
		""";

	private record Profile(int lights, int shadows, double[] repeats) {
		double median() {
			double[] sorted = this.repeats.clone();
			Arrays.sort(sorted);
			int middle = sorted.length / 2;
			return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) * 0.5;
		}

		String json() {
			return String.format(Locale.ROOT,
				"{\"lights\":%d,\"shadows\":%d,\"medianGpuMs\":%.6f,\"repeatsMs\":[%s]}",
				this.lights, this.shadows, this.median(), String.join(",", Arrays.stream(this.repeats)
					.mapToObj(value -> String.format(Locale.ROOT, "%.6f", value)).toList()));
		}
	}

	private MetalLocalLightingBenchmark() {
	}

	public static void main(final String[] arguments) throws IOException {
		if (!MetalNative.load()) {
			throw new AssertionError("MetalCraft native library did not load", MetalNative.loadFailure().orElse(null));
		}
		int repeats = Math.max(3, integerProperty("metalcraft.benchmarkRepeats", 3));
		int iterations = Math.max(10, integerProperty("metalcraft.benchmarkIterations", 20));
		int[] resolution = resolutionProperty(System.getProperty("metalcraft.benchmarkResolution", "1280x720"));
		List<Profile> profiles = new ArrayList<>();
		String deviceName;
		try (MetalDevice device = MetalNative.openDefaultDevice().orElseThrow()) {
			deviceName = device.name();
			if (!device.supportsPassGpuTiming()) throw new AssertionError("Local-light benchmark requires GPU timing");
			try (MetalCommandQueue queue = device.createCommandQueue();
				 MetalRenderPipeline pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
					 MSL, "local_vertex", MSL, "local_fragment",
					 List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT)), null,
					 MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
					 MetalRenderPipeline.RasterState.DEFAULT));
				 MetalTexture output = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.RGBA16_FLOAT, resolution[0], resolution[1], 1, MetalTexture.USAGE_ALL));
				 MetalTexture shadow = device.createTexture(MetalTexture.Descriptor.array(
					 MetalTexture.Format.DEPTH32_FLOAT, 256, 256, 24, MetalTexture.USAGE_ALL));
				 MetalTextureView shadowView = shadow.createView();
				 MetalSampler sampler = device.createSampler(new MetalSampler.Descriptor(
					 MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
				 MetalBuffer settings = device.createBuffer(16, MetalBuffer.StorageMode.SHARED);
				 MetalBuffer lights = device.createBuffer(256L * 32L, MetalBuffer.StorageMode.SHARED)) {
				fillLights(lights);
				clearShadows(queue, shadow);
				for (int shadowCount : new int[]{0, 4}) {
					for (int lightCount : new int[]{0, 64, 256}) {
						writeSettings(settings, lightCount, shadowCount);
						Profile profile = measure(
							queue, pipeline, output, shadowView, sampler, settings, lights,
							lightCount, shadowCount, repeats, iterations
						);
						profiles.add(profile);
						System.out.printf(Locale.ROOT,
							"Local-light benchmark: lights=%d shadows=%d median=%.4fms repeats=%s%n",
							lightCount, shadowCount, profile.median(), Arrays.toString(profile.repeats()));
					}
				}
			}
		}
		Path output = Path.of("build", "reports", "local-lighting.json");
		Files.createDirectories(output.getParent());
		Files.writeString(output, String.format(Locale.ROOT,
			"{\"device\":\"%s\",\"width\":%d,\"height\":%d,\"profiles\":[%s]}%n",
			deviceName.replace("\\", "\\\\").replace("\"", "\\\""), resolution[0], resolution[1],
			String.join(",", profiles.stream().map(Profile::json).toList())));
		System.out.println("Local-light benchmark written to " + output.toAbsolutePath());
	}

	private static Profile measure(
		final MetalCommandQueue queue,
		final MetalRenderPipeline pipeline,
		final MetalTexture output,
		final MetalTextureView shadow,
		final MetalSampler sampler,
		final MetalBuffer settings,
		final MetalBuffer lights,
		final int lightCount,
		final int shadowCount,
		final int repeats,
		final int iterations
	) {
		for (int warmup = 0; warmup < 10; warmup++) draw(queue, pipeline, output, shadow, sampler, settings, lights);
		double[] medians = new double[repeats];
		MetalStallProbe.setEnabled(true);
		try {
			drainGpuTime();
			for (int repeat = 0; repeat < repeats; repeat++) {
				double[] samples = new double[iterations];
				for (int iteration = 0; iteration < iterations; iteration++) {
					draw(queue, pipeline, output, shadow, sampler, settings, lights);
					samples[iteration] = drainGpuTime();
				}
				Arrays.sort(samples);
				medians[repeat] = samples[samples.length / 2];
			}
		} finally {
			MetalStallProbe.setEnabled(false);
		}
		return new Profile(lightCount, shadowCount, medians);
	}

	private static void draw(
		final MetalCommandQueue queue,
		final MetalRenderPipeline pipeline,
		final MetalTexture output,
		final MetalTextureView shadow,
		final MetalSampler sampler,
		final MetalBuffer settings,
		final MetalBuffer lights
	) {
		try (MetalCommandBuffer commands = queue.createCommandBuffer();
			 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				 MetalRenderPass.ColorAttachment.clear(output, 0.0, 0.0, 0.0, 1.0)))) {
			pass.setPipeline(pipeline);
			pass.setUniformBuffer(0, settings, 0L, MetalRenderPass.STAGE_FRAGMENT);
			pass.setUniformBuffer(1, lights, 0L, MetalRenderPass.STAGE_FRAGMENT);
			pass.setTexture(0, shadow, MetalRenderPass.STAGE_FRAGMENT);
			pass.setSampler(0, sampler, MetalRenderPass.STAGE_FRAGMENT);
			pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
			pass.close();
			commands.commitAndWait();
		}
	}

	private static void clearShadows(final MetalCommandQueue queue, final MetalTexture shadow) {
		try (MetalCommandBuffer commands = queue.createCommandBuffer();
			 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				 List.of(), new MetalRenderPass.DepthAttachment(
					 shadow, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 1.0), 24))) {
			pass.close();
			commands.commitAndWait();
		}
	}

	private static void fillLights(final MetalBuffer lights) {
		try (MetalBuffer.Mapping mapping = lights.map()) {
			var bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			for (int index = 0; index < 256; index++) {
				float angle = (float)(index * Math.PI * 2.0 / 256.0);
				bytes.putFloat((float)Math.cos(angle) * 8.0F).putFloat((index % 9) - 4.0F)
					.putFloat(-4.0F - index % 12).putFloat(24.0F);
				bytes.putFloat(0.5F + (index % 3 == 0 ? 0.5F : 0.0F))
					.putFloat(0.5F + (index % 3 == 1 ? 0.5F : 0.0F))
					.putFloat(0.5F + (index % 3 == 2 ? 0.5F : 0.0F)).putFloat(2.0F);
			}
		}
	}

	private static void writeSettings(final MetalBuffer settings, final int lights, final int shadows) {
		try (MetalBuffer.Mapping mapping = settings.map()) {
			var bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			bytes.putInt(lights).putInt(Math.min(lights, 64)).putInt(Math.min(lights, shadows)).putInt(0);
		}
	}

	private static double drainGpuTime() {
		long[] frame = new long[MetalStallProbe.slots()];
		MetalStallProbe.recordCompletedGpuWork();
		MetalStallProbe.takeFrame(frame, 0);
		int base = MetalStallProbe.Source.GPU_FRAME.ordinal() * MetalStallProbe.FIELDS;
		double millis = frame[base + MetalStallProbe.FIELD_NANOS] / 1_000_000.0;
		if (millis <= 0.0 || frame[base + MetalStallProbe.FIELD_COUNT] == 0L) {
			throw new AssertionError("Metal reported no GPU duration for a local-light benchmark frame");
		}
		return millis;
	}

	private static int integerProperty(final String key, final int fallback) {
		String value = System.getProperty(key);
		return value == null ? fallback : Integer.parseInt(value);
	}

	private static int[] resolutionProperty(final String value) {
		String[] dimensions = value.toLowerCase(Locale.ROOT).split("x", -1);
		if (dimensions.length != 2) throw new IllegalArgumentException("Benchmark resolution must be WIDTHxHEIGHT");
		return new int[]{Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1])};
	}
}
