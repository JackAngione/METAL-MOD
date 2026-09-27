package dev.metalcraft.client.metal;

import dev.metalcraft.client.shader.sky.SkyFrameInputs;
import dev.metalcraft.client.shader.sky.StandardSkyRenderer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Frozen-original vs production Metal output; optional paired timings are not a build gate. */
public final class CloudPerformanceSmoke {
    private static final int WIDTH = 257, HEIGHT = 145;
    private static final float[][] VIEWS = {
        {96, 15}, {96, 75}, {96, 0}, {240, 15}, {240, -20}, {800, -20}, {511.99F, 0}, {96, -75}
    };
    private static final String SHAPE_KERNEL = """
        kernel void shape_fixture(device const float4 *inputs [[buffer(0)]],
            device float2 *output [[buffer(1)]], uint id [[thread_position_in_grid]]) {
            float4 sample = inputs[id];
            float shape = mc_cumulus_shape(sample.xyz, sample.w);
            float coverage = mc_cumulus_coverage(sample.xz, float(id % 3u) * 0.5);
            output[id] = float2(shape, mc_cumulus_density(shape, coverage, 0.34));
        }
        """;

    private CloudPerformanceSmoke() { }

    static void run(MetalDevice device) {
        try { checkOutput(device); }
        catch (IOException failure) { throw new AssertionError("Could not load cloud comparison shaders", failure); }
    }

    private static void checkOutput(MetalDevice device) throws IOException {
        checkShape(device);
        long pixels = 0;
        try (var queue = device.createCommandQueue();
             var reference = pipeline(device, true);
             var optimized = pipeline(device, false);
             var frame = device.createBuffer(SkyFrameInputs.UNIFORM_BYTES, MetalBuffer.StorageMode.SHARED);
             var output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, WIDTH, HEIGHT, 1))) {
            for (int view = 0; view < VIEWS.length; view++) for (int mode = 0; mode <= 2; mode++) {
                for (int weather = 0; weather < 4; weather++) for (int wind = 0; wind < 2; wind++) {
                    write(frame, fixture(view, mode, weather, wind, WIDTH, HEIGHT));
                    draw(queue, reference, frame, output);
                    ByteBuffer expected = output.readback(queue, 0).order(ByteOrder.nativeOrder());
                    draw(queue, optimized, frame, output);
                    ByteBuffer actual = output.readback(queue, 0).order(ByteOrder.nativeOrder());
                    compare(expected, actual, "view=" + view + ", mode=" + mode + ", weather=" + weather + ", wind=" + wind);
                    pixels += (long)WIDTH * HEIGHT;
                }
            }
            // Different pixel footprints exercise the cirrus filter and alpha skip
            // at an ordinary game/cloud target size as well as the odd smoke extent.
            try (var large = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 1024, 576, 1))) {
                for (int view = 0; view < 6; view++) {
                    write(frame, fixture(view, 2, view % 4, 0, 1024, 576));
                    draw(queue, reference, frame, large);
                    ByteBuffer expected = large.readback(queue, 0).order(ByteOrder.nativeOrder());
                    draw(queue, optimized, frame, large);
                    compare(expected, large.readback(queue, 0).order(ByteOrder.nativeOrder()), "large view=" + view);
                    pixels += 1024L * 576;
                }
            }
        }
        System.out.println("Cloud performance GPU: exact RGBA16 equality for " + pixels
            + " pixels across 198 views/modes/weather/wind cases (below/inside/above clouds, horizon, night, and two extents)");
    }

    private static void compare(ByteBuffer expected, ByteBuffer actual, String description) {
        if (expected.remaining() != actual.remaining()) throw new AssertionError("Cloud output extent changed");
        for (int offset = 0; offset < expected.remaining(); offset += 2) {
            short a = expected.getShort(offset), b = actual.getShort(offset);
            if (!Float.isFinite(Float.float16ToFloat(a)) || !Float.isFinite(Float.float16ToFloat(b)) || a != b) {
                throw new AssertionError("Cloud output changed: " + description + ", component=" + offset / 2
                    + ": " + Float.float16ToFloat(a) + " -> " + Float.float16ToFloat(b));
            }
        }
    }

    private static void checkShape(MetalDevice device) throws IOException {
        var footprints = new ArrayList<Float>();
        footprints.add(0F);
        for (int octave = 0; octave < 3; octave++) for (float edge : new float[]{0.25F, 0.8F}) {
            float boundary = edge / (1 << octave);
            footprints.add(Math.nextDown(boundary));
            footprints.add(boundary);
            footprints.add(Math.nextUp(boundary));
        }
        for (int i = 0; footprints.size() < 64; i++) footprints.add((float)Math.pow(2, i * 0.3 - 12));
        int count = footprints.size() * 512;
        try (var queue = device.createCommandQueue();
             var reference = device.createComputePipeline(new MetalComputePipeline.Descriptor(shared(true) + SHAPE_KERNEL, "shape_fixture"));
             var optimized = device.createComputePipeline(new MetalComputePipeline.Descriptor(shared(false) + SHAPE_KERNEL, "shape_fixture"));
             var inputs = device.createBuffer(count * 16L, MetalBuffer.StorageMode.SHARED);
             var expected = device.createBuffer(count * 8L, MetalBuffer.StorageMode.SHARED);
             var actual = device.createBuffer(count * 8L, MetalBuffer.StorageMode.SHARED)) {
            var random = new Random(260926);
            try (var mapping = inputs.map()) {
                var bytes = mapping.bytes();
                for (int sample = 0; sample < 512; sample++) {
                    float x = random.nextFloat() * 4096 - 2048;
                    float y = random.nextFloat() * 1.25F;
                    float z = random.nextFloat() * 4096 - 2048;
                    for (float footprint : footprints) bytes.putFloat(x).putFloat(y).putFloat(z).putFloat(footprint);
                }
            }
            for (boolean current : new boolean[]{false, true}) {
                try (var commands = queue.createCommandBuffer()) {
                    try (var pass = commands.beginComputePass()) {
                        pass.setPipeline(current ? optimized : reference);
                        pass.setBuffer(0, inputs, 0);
                        pass.setBuffer(1, current ? actual : expected, 0);
                        pass.dispatch(count / 64, 1, 1, 64, 1, 1);
                    }
                    commands.commitAndWait();
                }
            }
            float maximum = 0;
            try (var a = expected.map(); var b = actual.map()) {
                for (int offset = 0; offset < count * 8; offset += 4) {
                    float before = a.bytes().getFloat(offset), after = b.bytes().getFloat(offset);
                    float error = Math.abs(before - after);
                    if (!Float.isFinite(before) || !Float.isFinite(after) || Float.floatToRawIntBits(before) != Float.floatToRawIntBits(after))
                        throw new AssertionError("Cloud shape/density changed at " + offset / 8 + ": " + before + " -> " + after);
                    maximum = Math.max(maximum, error);
                }
            }
            System.out.println("Cloud shape GPU: " + count + " float comparisons, including filter boundaries and terrain density; max error=" + maximum);
        }
    }

    public static void main(String[] args) throws IOException {
        try (var device = MetalNative.openDefaultDevice().orElseThrow()) {
            run(device);
            benchmark(device);
        }
    }

    private static void benchmark(MetalDevice device) throws IOException {
        int width = 1024, height = 576;
        String[] names = {"upward-fancy", "horizon-fancy", "inside-fancy", "above-fancy", "rain-fancy", "upward-fast"};
        int[][] cases = {{1, 2, 0}, {2, 2, 0}, {3, 2, 0}, {5, 2, 0}, {0, 2, 1}, {1, 1, 0}};
        var rows = new ArrayList<String>();
        try (var queue = device.createCommandQueue();
             var reference = pipeline(device, true);
             var optimized = pipeline(device, false);
             var frame = device.createBuffer(SkyFrameInputs.UNIFORM_BYTES, MetalBuffer.StorageMode.SHARED);
             var output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, width, height, 1))) {
            for (int round = 0; round < 3; round++) for (int scenario = 0; scenario < cases.length; scenario++) {
                int[] c = cases[scenario];
                write(frame, fixture(c[0], c[1], c[2], 0, width, height));
                double[] before = new double[21], after = new double[21];
                for (int pair = -6; pair < before.length; pair++) for (int turn = 0; turn < 2; turn++) {
                    boolean current = ((pair + turn + round) & 1) != 0;
                    MetalGpuFrameCapture.beginCapture();
                    MetalGpuFrameCapture.beginFrame();
                    // Amortize command scheduling and sustain GPU clocks with a short
                    // batch. Every draw has its own encoder and full attachment store.
                    draw(queue, current ? optimized : reference, frame, output, 16);
                    MetalGpuFrameCapture.endFrame();
                    Double time = MetalGpuFrameCapture.endCapture().p50Ms();
                    if (time == null || !Double.isFinite(time) || time <= 0) throw new AssertionError("Missing cloud GPU timing");
                    if (pair >= 0) (current ? after : before)[pair] = time / 16;
                }
                rows.add("{\"round\":" + round + ",\"case\":\"" + names[scenario]
                    + "\",\"beforeMs\":" + Arrays.toString(before) + ",\"afterMs\":" + Arrays.toString(after) + "}");
                Arrays.sort(before); Arrays.sort(after);
                System.out.printf("Cloud GPU round %d %s: %.4f -> %.4f ms (%+.1f%%)%n", round, names[scenario],
                    before[10], after[10], (after[10] / before[10] - 1) * 100);
            }
        }
        Path report = Path.of("build/reports/cloud-performance/measurements.json");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{\"device\":\"" + device.name() + "\",\"width\":" + width + ",\"height\":" + height
            + ",\"drawsPerSample\":16,\"warmupPairs\":6,\"measuredPairs\":21,\"results\":[\n" + String.join(",\n", rows) + "\n]}\n");
    }

    private static MetalRenderPipeline pipeline(MetalDevice device, boolean reference) throws IOException {
        String source = shared(reference) + resource("shared/celestials.metal") + resource("sky.metal");
        return device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source, "sky_vertex", source, "sky_clouds",
            List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT)), null,
            MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT));
    }

    private static String shared(boolean reference) throws IOException {
        return "#include <metal_stdlib>\nusing namespace metal;\n" + (reference
            ? Files.readString(Path.of("src/smoke/resources/sky-performance-reference.metal")) : resource("shared/sky.metal"));
    }

    private static String resource(String name) throws IOException {
        try (var input = CloudPerformanceSmoke.class.getResourceAsStream("/assets/metalcraft/shaderpacks/standard/" + name)) {
            if (input == null) throw new IOException("Missing sky resource " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static SkyFrameInputs fixture(int view, int mode, int weather, int wind, int width, int height) {
        var transform = new Matrix4f().rotateY((float)Math.PI / 2).rotateX((float)Math.toRadians(VIEWS[view][1]))
            .mul(new Matrix4f().perspective((float)Math.toRadians(75), (float)width / height, 10000, 0.1F, true).invert());
        return new SkyFrameInputs(transform,
            weather == 3 ? new Vector4f(0.002F, 0.004F, 0.012F, 1) : new Vector4f(0.24F, 0.45F, 0.80F, 1),
            weather == 3 ? new Vector4f(0, -1, 0, 1) : weather == 2 ? new Vector4f(-0.9995F, 0.03F, 0, 1) : new Vector4f(0, 1, 0, weather == 1 ? 0.15F : 1),
            new Vector4f(wind == 0 ? 1234 : 262143.75F, VIEWS[view][0], wind == 0 ? 5678 : 0.125F, 192),
            new Vector4f(mode, wind == 0 ? 1 : 0.35F, 0, 0), new Vector4f(0.40F, 0.48F, 0.58F, 1), new Vector4f(0, -1, 0, 0));
    }

    private static void write(MetalBuffer frame, SkyFrameInputs inputs) {
        try (var mapping = frame.map()) { inputs.write(mapping.bytes()); }
    }

    private static void draw(MetalCommandQueue queue, MetalRenderPipeline pipeline, MetalBuffer frame, MetalTexture output) {
        draw(queue, pipeline, frame, output, 1);
    }

    private static void draw(MetalCommandQueue queue, MetalRenderPipeline pipeline, MetalBuffer frame, MetalTexture output, int repeats) {
        try (var commands = queue.createCommandBuffer()) {
            for (int i = 0; i < repeats; i++) try (var pass = commands.beginRenderPass(StandardSkyRenderer.targetPass(output, MetalRenderPass.LoadAction.DONT_CARE))) {
                pass.setPipeline(pipeline);
                pass.setUniformBuffer(0, frame, 0, MetalRenderPass.STAGE_FRAGMENT);
                pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
            }
            commands.commitAndWait();
        }
    }
}
