package dev.metalcraft.client.metal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Controlled GPU A/B measurements for the three mechanisms isolated by shader-engine Phase 6. */
public final class MetalPhaseSixBenchmark {
	private static final String MSL = """
		#include <metal_stdlib>
		using namespace metal;

		struct Fullscreen { float4 position [[position]]; };
		vertex Fullscreen fullscreen_vertex(uint id [[vertex_id]]) {
		    const float2 p[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    return {float4(p[id % 3], 0.5, 1.0)};
		}

		struct GBuffer {
		    float4 scene [[color(0)]];
		    float4 albedo [[color(1)]];
		    float4 normal [[color(2)]];
		    float4 light [[color(3)]];
		};
		fragment GBuffer gbuffer_fill() {
		    return {float4(0.1, 0.1, 0.1, 1.0), float4(0.8, 0.6, 0.4, 1.0),
		        float4(0.5, 0.5, 0.85, 1.0), float4(0.0, 1.0, 0.0, 0.125)};
		}
		fragment GBuffer tile_resolve(GBuffer in) {
		    in.scene = float4(in.albedo.rgb * (0.2 + 0.8 * in.light.g) + in.normal.rgb * 0.05, 1.0);
		    return in;
		}
		fragment float4 sampled_resolve(
		    Fullscreen in [[stage_in]],
		    texture2d<float, access::read> albedo [[texture(0)]],
		    texture2d<float, access::read> normal [[texture(1)]],
		    texture2d<float, access::read> light [[texture(2)]]) {
		    uint2 p = uint2(in.position.xy);
		    float4 a = albedo.read(p), n = normal.read(p), l = light.read(p);
		    return float4(a.rgb * (0.2 + 0.8 * l.g) + n.rgb * 0.05, 1.0);
		}

		struct Layered { float4 position [[position]]; uint layer [[render_target_array_index]]; };
		vertex Layered layered_vertex(uint id [[vertex_id]], uint instance [[instance_id]]) {
		    const float2 p[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    return {float4(p[id % 3], 0.5, 1.0), instance & 3};
		}
		vertex Fullscreen single_layer_vertex(uint id [[vertex_id]]) {
		    const float2 p[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    return {float4(p[id % 3], 0.5, 1.0)};
		}
		fragment void depth_fragment() {}

		kernel void bloom_down(
		    texture2d<float, access::read> input [[texture(0)]],
		    texture2d<float, access::write> output [[texture(1)]],
		    uint2 p [[thread_position_in_grid]]) {
		    if (p.x >= output.get_width() || p.y >= output.get_height()) return;
		    uint2 q = p * 2;
		    float4 c = input.read(q) + input.read(q + uint2(1, 0))
		        + input.read(q + uint2(0, 1)) + input.read(q + uint2(1, 1));
		    output.write(c * 0.25, p);
		}
		kernel void bloom_up(
		    texture2d<float, access::read> halfImage [[texture(0)]],
		    texture2d<float, access::read> quarterImage [[texture(1)]],
		    texture2d<float, access::write> output [[texture(2)]],
		    uint2 p [[thread_position_in_grid]]) {
		    if (p.x >= output.get_width() || p.y >= output.get_height()) return;
		    output.write(halfImage.read(p) + quarterImage.read(p / 2) * 0.5, p);
		}
		fragment float4 bloom_down_fragment(
		    Fullscreen in [[stage_in]], texture2d<float, access::read> input [[texture(0)]]) {
		    uint2 q = uint2(in.position.xy) * 2;
		    return (input.read(q) + input.read(q + uint2(1, 0))
		        + input.read(q + uint2(0, 1)) + input.read(q + uint2(1, 1))) * 0.25;
		}
		fragment float4 bloom_up_fragment(
		    Fullscreen in [[stage_in]],
		    texture2d<float, access::read> halfImage [[texture(0)]],
		    texture2d<float, access::read> quarterImage [[texture(1)]]) {
		    uint2 p = uint2(in.position.xy);
		    return halfImage.read(p) + quarterImage.read(p / 2) * 0.5;
		}
		""";

	private record Pair(String name, double[] optimized, double[] baseline) {
		double optimizedMedian() { return median(this.optimized); }
		double baselineMedian() { return median(this.baseline); }
		double changePercent() { return 100.0 * (this.optimizedMedian() / this.baselineMedian() - 1.0); }
		String describe() {
			return String.format(Locale.ROOT,
				"%s optimized=%.4fms baseline=%.4fms change=%+.1f%% optimizedSpread=%.1f%% baselineSpread=%.1f%%",
				this.name, this.optimizedMedian(), this.baselineMedian(), this.changePercent(),
				spread(this.optimized), spread(this.baseline));
		}
		String toJson() {
			return String.format(Locale.ROOT,
				"{\"name\":\"%s\",\"optimizedMedianMs\":%.6f,\"baselineMedianMs\":%.6f,\"changePercent\":%.3f,"
					+ "\"optimizedRepeatsMs\":%s,\"baselineRepeatsMs\":%s}",
				this.name, this.optimizedMedian(), this.baselineMedian(), this.changePercent(),
				arrayJson(this.optimized), arrayJson(this.baseline));
		}
	}

	@FunctionalInterface
	private interface Iteration { void run(); }

	private MetalPhaseSixBenchmark() {}

	public static void main(final String[] arguments) throws IOException {
		if (!MetalNative.load()) {
			throw new AssertionError("MetalCraft native library did not load", MetalNative.loadFailure().orElse(null));
		}
		int repeats = Math.max(3, integerProperty("metalcraft.benchmarkRepeats", 3));
		int iterations = Math.max(10, integerProperty("metalcraft.benchmarkIterations", 30));
		int[] resolution = resolutionProperty(System.getProperty("metalcraft.benchmarkResolution", "1920x1080"));
		int shadowSize = Math.max(256, integerProperty("metalcraft.benchmarkShadowSize", 2048));
		List<Pair> results;
		String deviceName;
		try (MetalDevice device = MetalNative.openDefaultDevice().orElseThrow()) {
			deviceName = device.name();
			if (!device.supportsPassGpuTiming()) {
				throw new AssertionError("Phase 6 requires GPU counter sampling on the active Metal device");
			}
			results = List.of(
				benchmarkTileResolve(device, resolution[0], resolution[1], repeats, iterations),
				benchmarkLayeredCascades(device, shadowSize, repeats, iterations),
				benchmarkBloom(device, resolution[0], resolution[1], repeats, iterations)
			);
		}
		for (Pair result : results) {
			System.out.println("Phase 6 benchmark: " + result.describe());
		}
		Path output = Path.of("build", "reports", "phase6-mechanisms.json");
		Files.createDirectories(output.getParent());
		String json = String.format(Locale.ROOT,
			"{\"device\":\"%s\",\"width\":%d,\"height\":%d,\"shadowSize\":%d,"
				+ "\"repeats\":%d,\"iterationsPerRepeat\":%d,\"results\":[%s]}%n",
			deviceName.replace("\\", "\\\\").replace("\"", "\\\""),
			resolution[0], resolution[1], shadowSize, repeats, iterations,
			String.join(",", results.stream().map(Pair::toJson).toList()));
		Files.writeString(output, json);
		System.out.println("Phase 6 benchmark written to " + output.toAbsolutePath());
	}

	private static Pair benchmarkTileResolve(
		final MetalDevice device, final int width, final int height, final int repeats, final int iterations
	) {
		List<MetalRenderPipeline.ColorTarget> fillTargets = List.of(
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)
		);
		List<MetalRenderPipeline.ColorTarget> tileResolveTargets = List.of(
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT),
			new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA8_UNORM, 0, null),
			new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA8_UNORM, 0, null),
			new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA8_UNORM, 0, null)
		);
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalRenderPipeline fill = pipeline(device, "fullscreen_vertex", "gbuffer_fill", fillTargets, null,
				 MetalRenderPipeline.RasterState.DEFAULT);
			 MetalRenderPipeline tileResolve = pipeline(device, "fullscreen_vertex", "tile_resolve", tileResolveTargets, null,
				 MetalRenderPipeline.RasterState.DEFAULT);
			 MetalRenderPipeline sampledResolve = pipeline(device, "fullscreen_vertex", "sampled_resolve",
				 List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT)), null,
				 MetalRenderPipeline.RasterState.DEFAULT);
			 MetalTexture tileScene = texture(device, MetalTexture.Format.RGBA16_FLOAT, width, height);
			 MetalTexture tileAlbedo = memoryless(device, width, height);
			 MetalTexture tileNormal = memoryless(device, width, height);
			 MetalTexture tileLight = memoryless(device, width, height);
			 MetalTexture storedScene = texture(device, MetalTexture.Format.RGBA16_FLOAT, width, height);
			 MetalTexture storedAlbedo = texture(device, MetalTexture.Format.RGBA8_UNORM, width, height);
			 MetalTexture storedNormal = texture(device, MetalTexture.Format.RGBA8_UNORM, width, height);
			 MetalTexture storedLight = texture(device, MetalTexture.Format.RGBA8_UNORM, width, height);
			 MetalTextureView albedoView = storedAlbedo.createView();
			 MetalTextureView normalView = storedNormal.createView();
			 MetalTextureView lightView = storedLight.createView()) {
			Iteration merged = () -> {
				try (MetalCommandBuffer commands = queue.createCommandBuffer();
					 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(List.of(
						 clear(tileScene, MetalRenderPass.StoreAction.STORE), clear(tileAlbedo, MetalRenderPass.StoreAction.DONT_CARE),
						 clear(tileNormal, MetalRenderPass.StoreAction.DONT_CARE), clear(tileLight, MetalRenderPass.StoreAction.DONT_CARE)
					 ), null))) {
					pass.setPipeline(fill); pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
					pass.setPipeline(tileResolve); pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
					pass.close(); commands.commitAndWait();
				}
			};
			Iteration stored = () -> {
				try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
					try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(List.of(
						clear(storedScene, MetalRenderPass.StoreAction.STORE), clear(storedAlbedo, MetalRenderPass.StoreAction.STORE),
						clear(storedNormal, MetalRenderPass.StoreAction.STORE), clear(storedLight, MetalRenderPass.StoreAction.STORE)
					), null))) {
						pass.setPipeline(fill); pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
					}
					try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
						new MetalRenderPass.ColorAttachment(storedScene, MetalRenderPass.LoadAction.LOAD,
							MetalRenderPass.StoreAction.STORE, 0, 0, 0, 1)))) {
						pass.setPipeline(sampledResolve);
						pass.setTexture(0, albedoView, MetalRenderPass.STAGE_FRAGMENT);
						pass.setTexture(1, normalView, MetalRenderPass.STAGE_FRAGMENT);
						pass.setTexture(2, lightView, MetalRenderPass.STAGE_FRAGMENT);
						pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
					}
					commands.commitAndWait();
				}
			};
			return compare("memoryless-merged-vs-stored-separate", merged, stored, repeats, iterations);
		}
	}

	private static Pair benchmarkLayeredCascades(
		final MetalDevice device, final int size, final int repeats, final int iterations
	) {
		MetalRenderPipeline.DepthState depthState = new MetalRenderPipeline.DepthState(
			true, true, MetalRenderPipeline.CompareFunction.LESS_EQUAL, 0, 0);
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalRenderPipeline layered = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 MSL, "layered_vertex", MSL, "depth_fragment", List.of(), MetalTexture.Format.DEPTH32_FLOAT,
				 MetalRenderPipeline.VertexDescriptor.EMPTY, depthState,
				 new MetalRenderPipeline.RasterState(MetalRenderPipeline.CullMode.NONE,
					 MetalRenderPipeline.FillMode.FILL, MetalRenderPipeline.TopologyClass.TRIANGLE)));
			 MetalRenderPipeline single = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 MSL, "single_layer_vertex", MSL, "depth_fragment", List.of(), MetalTexture.Format.DEPTH32_FLOAT,
				 MetalRenderPipeline.VertexDescriptor.EMPTY, depthState, MetalRenderPipeline.RasterState.DEFAULT));
			 MetalTexture depth = device.createTexture(MetalTexture.Descriptor.array(
				 MetalTexture.Format.DEPTH32_FLOAT, size, size, 4,
				 MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_RENDER_TARGET))) {
			Iteration onePass = () -> {
				MetalRenderPass.DepthAttachment attachment = new MetalRenderPass.DepthAttachment(depth, 0, 0,
					MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 1.0);
				try (MetalCommandBuffer commands = queue.createCommandBuffer();
					 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(List.of(), attachment, 4))) {
					pass.setPipeline(layered); pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 4, 0);
					pass.close(); commands.commitAndWait();
				}
			};
			Iteration fourPasses = () -> {
				try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
					for (int layer = 0; layer < 4; layer++) {
						MetalRenderPass.DepthAttachment attachment = new MetalRenderPass.DepthAttachment(depth, 0, layer,
							MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 1.0);
						try (MetalRenderPass pass = commands.beginRenderPass(
							new MetalRenderPass.Descriptor(List.of(), attachment, 0))) {
							pass.setPipeline(single); pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
						}
					}
					commands.commitAndWait();
				}
			};
			return compare("layered-cascades-vs-four-encoders", onePass, fourPasses, repeats, iterations);
		}
	}

	private static Pair benchmarkBloom(
		final MetalDevice device, final int width, final int height, final int repeats, final int iterations
	) {
		int halfWidth = Math.max(1, width / 2), halfHeight = Math.max(1, height / 2);
		int quarterWidth = Math.max(1, width / 4), quarterHeight = Math.max(1, height / 4);
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalComputePipeline down = device.createComputePipeline(new MetalComputePipeline.Descriptor(MSL, "bloom_down"));
			 MetalComputePipeline up = device.createComputePipeline(new MetalComputePipeline.Descriptor(MSL, "bloom_up"));
			 MetalRenderPipeline downFragment = pipeline(device, "fullscreen_vertex", "bloom_down_fragment",
				 List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT)), null,
				 MetalRenderPipeline.RasterState.DEFAULT);
			 MetalRenderPipeline upFragment = pipeline(device, "fullscreen_vertex", "bloom_up_fragment",
				 List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT)), null,
				 MetalRenderPipeline.RasterState.DEFAULT);
			 MetalTexture input = texture(device, MetalTexture.Format.RGBA16_FLOAT, width, height);
			 MetalTexture computeHalf = texture(device, MetalTexture.Format.RGBA16_FLOAT, halfWidth, halfHeight);
			 MetalTexture computeQuarter = texture(device, MetalTexture.Format.RGBA16_FLOAT, quarterWidth, quarterHeight);
			 MetalTexture computeOutput = texture(device, MetalTexture.Format.RGBA16_FLOAT, halfWidth, halfHeight);
			 MetalTexture fragmentHalf = texture(device, MetalTexture.Format.RGBA16_FLOAT, halfWidth, halfHeight);
			 MetalTexture fragmentQuarter = texture(device, MetalTexture.Format.RGBA16_FLOAT, quarterWidth, quarterHeight);
			 MetalTexture fragmentOutput = texture(device, MetalTexture.Format.RGBA16_FLOAT, halfWidth, halfHeight);
			 MetalTextureView inputView = input.createView();
			 MetalTextureView computeHalfView = computeHalf.createView();
			 MetalTextureView computeQuarterView = computeQuarter.createView();
			 MetalTextureView computeOutputView = computeOutput.createView();
			 MetalTextureView fragmentHalfView = fragmentHalf.createView();
			 MetalTextureView fragmentQuarterView = fragmentQuarter.createView()) {
			Iteration compute = () -> {
				try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
					dispatchDown(commands, down, inputView, computeHalfView, halfWidth, halfHeight);
					dispatchDown(commands, down, computeHalfView, computeQuarterView, quarterWidth, quarterHeight);
					try (MetalComputePass pass = commands.beginComputePass()) {
						pass.setPipeline(up); pass.setTexture(0, computeHalfView); pass.setTexture(1, computeQuarterView);
						pass.setTexture(2, computeOutputView); pass.dispatchCovering(halfWidth, halfHeight, 8, 8);
					}
					commands.commitAndWait();
				}
			};
			Iteration fragment = () -> {
				try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
					drawDown(commands, downFragment, inputView, fragmentHalf);
					drawDown(commands, downFragment, fragmentHalfView, fragmentQuarter);
					try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
						MetalRenderPass.ColorAttachment.clear(fragmentOutput, 0, 0, 0, 1)))) {
						pass.setPipeline(upFragment); pass.setTexture(0, fragmentHalfView, MetalRenderPass.STAGE_FRAGMENT);
						pass.setTexture(1, fragmentQuarterView, MetalRenderPass.STAGE_FRAGMENT);
						pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
					}
					commands.commitAndWait();
				}
			};
			return compare("compute-bloom-vs-fragment-ping-pong", compute, fragment, repeats, iterations);
		}
	}

	private static void dispatchDown(final MetalCommandBuffer commands, final MetalComputePipeline pipeline,
		final MetalTextureView input, final MetalTextureView output, final int width, final int height) {
		try (MetalComputePass pass = commands.beginComputePass()) {
			pass.setPipeline(pipeline); pass.setTexture(0, input); pass.setTexture(1, output);
			pass.dispatchCovering(width, height, 8, 8);
		}
	}

	private static void drawDown(final MetalCommandBuffer commands, final MetalRenderPipeline pipeline,
		final MetalTextureView input, final MetalTexture output) {
		try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
			MetalRenderPass.ColorAttachment.clear(output, 0, 0, 0, 1)))) {
			pass.setPipeline(pipeline); pass.setTexture(0, input, MetalRenderPass.STAGE_FRAGMENT);
			pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
		}
	}

	private static Pair compare(final String name, final Iteration optimized, final Iteration baseline,
		final int repeats, final int iterations) {
		for (int warmup = 0; warmup < 20; warmup++) { optimized.run(); baseline.run(); }
		double[] optimizedRepeats = new double[repeats];
		double[] baselineRepeats = new double[repeats];
		MetalStallProbe.setEnabled(true);
		try {
			drainGpuTime();
			for (int repeat = 0; repeat < repeats; repeat++) {
				List<Double> optimizedSamples = new ArrayList<>(iterations);
				List<Double> baselineSamples = new ArrayList<>(iterations);
				for (int iteration = 0; iteration < iterations; iteration++) {
					if ((iteration + repeat) % 2 == 0) {
						optimizedSamples.add(measure(optimized)); baselineSamples.add(measure(baseline));
					} else {
						baselineSamples.add(measure(baseline)); optimizedSamples.add(measure(optimized));
					}
				}
				optimizedRepeats[repeat] = sampleMedian(optimizedSamples);
				baselineRepeats[repeat] = sampleMedian(baselineSamples);
			}
		} finally {
			MetalStallProbe.setEnabled(false);
		}
		return new Pair(name, optimizedRepeats, baselineRepeats);
	}

	private static double measure(final Iteration iteration) {
		iteration.run();
		return drainGpuTime();
	}

	private static double drainGpuTime() {
		long[] frame = new long[MetalStallProbe.slots()];
		MetalStallProbe.recordCompletedGpuWork();
		MetalStallProbe.takeFrame(frame, 0);
		int base = MetalStallProbe.Source.GPU_FRAME.ordinal() * MetalStallProbe.FIELDS;
		double millis = frame[base + MetalStallProbe.FIELD_NANOS] / 1_000_000.0;
		if (millis <= 0.0 || frame[base + MetalStallProbe.FIELD_COUNT] == 0L) {
			throw new AssertionError("Metal reported no GPU duration for a completed benchmark command buffer");
		}
		return millis;
	}

	private static MetalRenderPipeline pipeline(final MetalDevice device, final String vertex, final String fragment,
		final List<MetalRenderPipeline.ColorTarget> colors, final MetalTexture.Format depth,
		final MetalRenderPipeline.RasterState raster) {
		return device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
			MSL, vertex, MSL, fragment, colors, depth, MetalRenderPipeline.VertexDescriptor.EMPTY,
			MetalRenderPipeline.DepthState.DISABLED, raster));
	}

	private static MetalTexture texture(final MetalDevice device, final MetalTexture.Format format,
		final int width, final int height) {
		return device.createTexture(new MetalTexture.Descriptor(format, width, height, 1, MetalTexture.USAGE_ALL));
	}

	private static MetalTexture memoryless(final MetalDevice device, final int width, final int height) {
		return device.createTexture(MetalTexture.Descriptor.memoryless(MetalTexture.Format.RGBA8_UNORM, width, height));
	}

	private static MetalRenderPass.ColorAttachment clear(final MetalTexture texture,
		final MetalRenderPass.StoreAction store) {
		return new MetalRenderPass.ColorAttachment(texture, 0, MetalRenderPass.LoadAction.CLEAR, store, 0, 0, 0, 1);
	}

	private static int integerProperty(final String key, final int fallback) {
		String value = System.getProperty(key);
		return value == null ? fallback : Integer.parseInt(value);
	}

	private static int[] resolutionProperty(final String value) {
		String[] dimensions = value.toLowerCase(Locale.ROOT).split("x", -1);
		if (dimensions.length != 2) throw new IllegalArgumentException("Benchmark resolution must be WIDTHxHEIGHT");
		return new int[] {Integer.parseInt(dimensions[0]), Integer.parseInt(dimensions[1])};
	}

	private static double sampleMedian(final List<Double> samples) {
		double[] values = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		return median(values);
	}

	private static double median(final double[] values) {
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		int middle = sorted.length / 2;
		return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) * 0.5;
	}

	private static double spread(final double[] values) {
		double median = median(values);
		return median == 0.0 ? 0.0 : 100.0 * (Arrays.stream(values).max().orElse(0.0)
			- Arrays.stream(values).min().orElse(0.0)) / median;
	}

	private static String arrayJson(final double[] values) {
		return "[" + String.join(",", Arrays.stream(values)
			.mapToObj(value -> String.format(Locale.ROOT, "%.6f", value)).toList()) + "]";
	}
}
