package dev.metalcraft.client.metal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Independent GPU equivalence oracle for skipping water bands already removed by their filter. */
public final class WaterFilteringSmoke {
	private static final int CASES = 4 * 2 * 72 * 64;
	private static final int BYTES_PER_CASE = 8 * Float.BYTES;
	private static final String KERNEL = """
		struct FilterFixture { uint count; int quality; float footprint; uint horizontal; };
		kernel void water_filter_fixture(device float4 *out [[buffer(0)]],
		    constant FilterFixture &fixture [[buffer(1)]], uint id [[thread_position_in_grid]]) {
		    if (id >= fixture.count) return;
		    int quality = MC_FILTER_BENCHMARK ? 3 : fixture.quality;
		    bool horizontal = fixture.horizontal != 0;
		    float footprint = fixture.footprint;
		    uint sample = id;
		    if (quality < 0) {
		        quality = int(id % 4u);
		        horizontal = (id / 4u) % 2u != 0;
		        uint footprintIndex = (id / 8u) % 72u;
		        sample = id / (8u * 72u);
		        if (footprintIndex < 32u) {
		            footprint = exp2(float(footprintIndex) * 0.5 - 8.0) * 0.01;
		        } else {
		            // Both sides of the start/end of all seven detail and three distant filters.
		            uint boundary = footprintIndex - 32u;
		            uint band = boundary / 4u;
		            const float lengths[10] = {5,5,13,13,10,10,13,5,5,13};
		            float frequency = band < 7u ? 0.25 * exp2(float(band))
		                : exp2(float(band - 7u)) / 64.0;
		            footprint = (boundary % 4u < 2u ? 0.15 : 0.45)
		                / (frequency * sqrt(lengths[band]));
		            footprint = as_type<float>(as_type<uint>(footprint)
		                + (boundary % 2u == 0u ? -1 : 1));
		        }
		    }
		    float2 surface = float2(float(sample % 8u) * 36.57 - 128.0001,
		        float((sample / 8u) % 8u) * 36.59 - 0.0001);
		    float time = float(sample % 4u) * 512.125;
		    McWaterCurrentWarp current = horizontal ? mc_water_current_warp(surface, time)
		        : McWaterCurrentWarp{float2(0), float2(0), float2(0)};
		#if MC_FILTER_REFERENCE
		    float3 detail = mc_water_reference_detail_height_gradient(surface, footprint,
		        quality, time, horizontal, current);
		    float2 distant = mc_water_reference_distant_slope(surface, time, footprint, current);
		#else
		    float3 detail = mc_water_detail_height_gradient(surface, footprint,
		        quality, time, horizontal, current);
		    float2 distant = mc_water_distant_slope(surface, time, footprint, current);
		#endif
		    out[id * 2u] = float4(detail, 0);
		    out[id * 2u + 1u] = float4(distant, 0, 0);
		}
		""";

	private WaterFilteringSmoke() { }

	static void run() {
		try (var device = MetalNative.openDefaultDevice().orElseThrow();
			 var queue = device.createCommandQueue();
			 var reference = pipeline(device, true, false);
			 var optimized = pipeline(device, false, false);
			 var parameters = device.createBuffer(16, MetalBuffer.StorageMode.SHARED);
			 var expected = device.createBuffer((long)CASES * BYTES_PER_CASE, MetalBuffer.StorageMode.SHARED);
			 var actual = device.createBuffer((long)CASES * BYTES_PER_CASE, MetalBuffer.StorageMode.SHARED)) {
			parameters(parameters, CASES, -1, 0, true);
			dispatch(queue, reference, parameters, expected, CASES);
			dispatch(queue, optimized, parameters, actual, CASES);
			float maxError = 0;
			try (var oldMapping = expected.map(); var newMapping = actual.map()) {
				ByteBuffer oldBytes = oldMapping.bytes().order(ByteOrder.nativeOrder());
				ByteBuffer newBytes = newMapping.bytes().order(ByteOrder.nativeOrder());
				for (int offset = 0; offset < CASES * BYTES_PER_CASE; offset += Float.BYTES) {
					float a = oldBytes.getFloat(offset), b = newBytes.getFloat(offset);
					float error = Math.abs(a - b);
					if (!Float.isFinite(a) || !Float.isFinite(b) || error > 2.0e-6F) {
						throw new AssertionError("Water filter changed case " + offset / BYTES_PER_CASE
							+ " channel " + (offset % BYTES_PER_CASE) / Float.BYTES + ": " + a + " -> " + b);
					}
					maxError = Math.max(maxError, error);
				}
			}
			System.out.println("Water filtering GPU: " + CASES + " reference comparisons passed; max error=" + maxError);
		}
	}

	/** Optional paired GPU timing; the ordinary smoke gate checks correctness only. */
	public static void main(String[] args) {
		run();
		int count = 256 * 256;
		try (var device = MetalNative.openDefaultDevice().orElseThrow();
			 var queue = device.createCommandQueue();
			 var reference = pipeline(device, true, true);
			 var optimized = pipeline(device, false, true);
			 var parameters = device.createBuffer(16, MetalBuffer.StorageMode.SHARED);
			 var results = device.createBuffer((long)count * BYTES_PER_CASE, MetalBuffer.StorageMode.SHARED)) {
			for (float footprint : new float[]{0.0002F, 0.04F, 0.2F, 1.0F, 16.0F}) {
				parameters(parameters, count, 3, footprint, true);
				double[] before = new double[15], after = new double[15];
				for (int pair = -5; pair < before.length; pair++) {
					for (int turn = 0; turn < 2; turn++) {
						boolean current = ((pair + turn) & 1) != 0;
						MetalGpuFrameCapture.beginCapture();
						MetalGpuFrameCapture.beginFrame();
						dispatch(queue, current ? optimized : reference, parameters, results, count);
						MetalGpuFrameCapture.endFrame();
						Double time = MetalGpuFrameCapture.endCapture().p50Ms();
						if (time == null || !Double.isFinite(time) || time <= 0) {
							throw new AssertionError("Missing completed GPU timing sample");
						}
						if (pair >= 0) (current ? after : before)[pair] = time;
					}
				}
				Arrays.sort(before);
				Arrays.sort(after);
				double oldMedian = before[before.length / 2], newMedian = after[after.length / 2];
				System.out.printf("Water filter GPU, high detail, %d samples, footprint=%.4f: %.4f -> %.4f ms (%+.1f%%)%n",
					count, footprint, oldMedian, newMedian, (newMedian / oldMedian - 1) * 100);
			}
		}
	}

	private static MetalComputePipeline pipeline(MetalDevice device, boolean reference, boolean benchmark) {
		try (var input = WaterFilteringSmoke.class.getResourceAsStream(
			"/assets/metalcraft/shaderpacks/standard/shared/water.metal")) {
			if (input == null) throw new IOException("Missing water helpers");
			String source = "#define MC_FILTER_REFERENCE " + (reference ? 1 : 0) + "\n"
				+ "#define MC_FILTER_BENCHMARK " + (benchmark ? 1 : 0) + "\n"
				+ new String(input.readAllBytes(), StandardCharsets.UTF_8)
				+ Files.readString(Path.of("src/smoke/resources/water-filter-reference.metal")) + KERNEL;
			return device.createComputePipeline(new MetalComputePipeline.Descriptor(source, "water_filter_fixture"));
		} catch (IOException error) {
			throw new AssertionError("Could not load water filter fixture", error);
		}
	}

	private static void parameters(MetalBuffer parameters, int count, int quality, float footprint,
		boolean horizontal) {
		try (var mapping = parameters.map()) {
			mapping.bytes().order(ByteOrder.nativeOrder()).putInt(count).putInt(quality)
				.putFloat(footprint).putInt(horizontal ? 1 : 0);
		}
	}

	private static void dispatch(MetalCommandQueue queue, MetalComputePipeline pipeline,
		MetalBuffer parameters, MetalBuffer results, int count) {
		try (var commands = queue.createCommandBuffer()) {
			try (var compute = commands.beginComputePass()) {
				compute.setPipeline(pipeline);
				compute.setBuffer(0, results, 0);
				compute.setBuffer(1, parameters, 0);
				compute.dispatch((count + 63) / 64, 1, 1, 64, 1, 1);
			}
			commands.commitAndWait();
		}
	}
}
