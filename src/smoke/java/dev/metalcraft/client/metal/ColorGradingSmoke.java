package dev.metalcraft.client.metal;

import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.ShaderPack;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.WorldComposition;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Executes the production Metal grade against an independent pre-grading GPU reference. */
public final class ColorGradingSmoke {
    private static final List<String> UNIFORMS = List.of("exposure", "tonemap", "debug_view", "shadow_strength",
        "temperature", "tint", "contrast", "saturation", "vibrance", "gamma", "highlights", "shadows",
        "film_grain", "bloom", "depth_of_field");
    private static final float[] NEUTRAL = {1, 0, 0, 1, 0, 0, 1, 1, 0, 1, 0, 0, 0, 0, 0};
    private static final float[][] COLORS = {
        {0, 0, 0}, {0.001F, 0.002F, 0.003F}, {0.06F, 0.06F, 0.06F}, {0.18F, 0.18F, 0.18F},
        {0.5F, 0.5F, 0.5F}, {0.3F, 0.27F, 0.25F}, {0.6F, 0.05F, 0.02F}, {4, 2, 0.5F},
        {65504, 65504, 65504}, {-0.1F, 0.2F, 0.3F}
    };

    private ColorGradingSmoke() { }

    public static void main(String[] args) {
        try (var device = MetalNative.openDefaultDevice().orElseThrow()) { run(device); }
    }

    static void run(final MetalDevice device) {
        try (var fixture = new Fixture(device)) {
            checkNeutral(fixture);
            checkControls(fixture);
            checkExtremes(fixture);
            checkHostAbi(device, fixture);
        }
        System.out.println("Color grading GPU: old-pipeline neutral identity (HDR/legacy, invert, none/ACES, exposures), "
            + "Reinhard reference, white-balance directions/luminance, contrast, saturation/vibrance, gamma, "
            + "highlights/shadows, 256 extreme combinations, scene debug bypass and 60-byte host/Metal ABI passed");
    }

    private static void checkNeutral(Fixture fixture) {
        for (boolean linear : new boolean[]{false, true}) for (boolean invert : new boolean[]{false, true}) {
            try (var reference = pipeline(fixture.device, source(linear, invert, OLD_GRADE), MetalTexture.Format.RGBA16_FLOAT);
                 var production = pipeline(fixture.device, source(linear, invert, resource("grade.metal")), MetalTexture.Format.RGBA16_FLOAT)) {
                for (int tone : new int[]{0, 1}) for (float exposure : new float[]{0.25F, 0.5F, 1, 2, 4}) {
                    float[] options = NEUTRAL.clone(); options[0] = exposure; options[1] = tone;
                    ByteBuffer oldPixels = fixture.draw(reference, options);
                    ByteBuffer newPixels = fixture.draw(production, options);
                    for (int i = 0; i < oldPixels.remaining(); i++) {
                        if (oldPixels.get(i) != newPixels.get(i)) throw new AssertionError(
                            "Neutral differs from original GPU pipeline: linear=" + linear + " invert=" + invert
                                + " tone=" + tone + " exposure=" + exposure + " byte=" + i);
                    }
                }
                if (!linear && !invert) {
                    for (int tone : new int[]{0, 1, 2}) {
                        float[] options = NEUTRAL.clone(); options[1] = tone;
                        ByteBuffer neutral = fixture.draw(production, options);
                        options[8] = 0.05F;
                        ByteBuffer vibrant = fixture.draw(production, options);
                        for (int pixel : new int[]{2, 3, 4}) for (int c = 0; c < 3; c++) {
                            near("Legacy vibrance keeps grayscale/tone domain", half(neutral, pixel, c), half(vibrant, pixel, c), 0.001);
                        }
                        options[8] = 0; options[6] = 1.0001F;
                        ByteBuffer contrast = fixture.draw(production, options);
                        for (int pixel : new int[]{2, 3, 4}) for (int c = 0; c < 3; c++) {
                            near("Legacy contrast continuous at neutral", half(neutral, pixel, c), half(contrast, pixel, c), 0.001);
                        }
                    }
                }
            }
        }
    }

    private static void checkControls(Fixture fixture) {
        float[][] neutral = fixture.grade(NEUTRAL);
        float[][] warm = fixture.grade(changed(4, 1)), cool = fixture.grade(changed(4, -1));
        if (!(warm[3][0] / warm[3][2] > neutral[3][0] / neutral[3][2]
            && cool[3][0] / cool[3][2] < neutral[3][0] / neutral[3][2])) fail("Temperature directions");
        float[][] magenta = fixture.grade(changed(5, 1)), green = fixture.grade(changed(5, -1));
        if (!(magenta[3][1] < neutral[3][1] && magenta[3][0] > neutral[3][0]
            && magenta[3][2] > neutral[3][2] && green[3][1] > neutral[3][1])) fail("Tint directions");
        for (float[][] balance : new float[][][]{warm, cool, magenta, green}) {
            double luminance = decode(balance[3][0]) * 0.2126 + decode(balance[3][1]) * 0.7152 + decode(balance[3][2]) * 0.0722;
            near("White balance preserves luminance", quantized(COLORS[3][0]), luminance, 0.0005);
        }
        float[][] contrast = fixture.grade(changed(6, 1.5F));
        if (!(contrast[2][0] < neutral[2][0] && contrast[4][0] > neutral[4][0])) fail("Contrast dark/light direction");
        near("Contrast pivot", neutral[3][0], contrast[3][0], 0.001);
        float[] grayOptions = changed(7, 0); grayOptions[8] = 1;
        float[][] gray = fixture.grade(grayOptions);
        for (int i = 0; i < COLORS.length; i++) {
            near("Saturation zero stays grayscale with vibrance", gray[i][0], gray[i][1], 0);
            near("Saturation zero stays grayscale with vibrance", gray[i][1], gray[i][2], 0);
        }
        if (!(chroma(fixture.grade(changed(7, 2))[5]) > chroma(neutral[5]))) fail("Saturation direction");
        float[][] vibrant = fixture.grade(changed(8, 1)), muted = fixture.grade(changed(8, -1));
        if (!(chroma(vibrant[5]) > chroma(neutral[5]) && chroma(muted[5]) < chroma(neutral[5]))) fail("Vibrance direction");
        double mutedBoost = chroma(vibrant[5]) / chroma(neutral[5]);
        double saturatedBoost = chroma(vibrant[6]) / chroma(neutral[6]);
        if (!(mutedBoost > saturatedBoost + 0.2)) fail("Vibrance must preferentially affect muted colors");
        float[][] brightGamma = fixture.grade(changed(9, 2)), darkGamma = fixture.grade(changed(9, 0.5F));
        if (!(brightGamma[3][0] > neutral[3][0] && darkGamma[3][0] < neutral[3][0])) fail("Gamma direction");
        near("Gamma linear-before-transfer", encode(Math.sqrt(quantized(COLORS[3][0]))), brightGamma[3][0], 0.001);
        float[][] lifted = fixture.grade(changed(11, 1)), crushed = fixture.grade(changed(11, -1));
        if (!(lifted[2][0] > neutral[2][0] && crushed[2][0] < neutral[2][0])) fail("Shadow direction");
        near("Shadows exclude upper tones", neutral[4][0], lifted[4][0], 0);
        float[][] highlights = fixture.grade(changed(10, 1)), recovered = fixture.grade(changed(10, -1));
        if (!(highlights[4][0] > neutral[4][0] && recovered[4][0] < neutral[4][0])) fail("Highlight direction");
        near("Highlights exclude lower tones", neutral[2][0], highlights[2][0], 0);
        float[][] reinhard = fixture.grade(changed(1, 2)), aces = fixture.grade(changed(1, 1));
        near("Reinhard HDR reference", encode(4.0 / 5.0), reinhard[7][0], 0.001);
        if (!(neutral[7][0] > aces[7][0] && aces[7][0] > reinhard[7][0] + 0.05)) fail("Tone mappers must differ");
        float[] debugOptions = {4, 2, 1, 1, 1, -1, 1.5F, 2, 1, 0.5F, 1, -1, 0, 0, 0};
        float[][] debug = fixture.grade(debugOptions);
        for (int i = 0; i < COLORS.length; i++) for (int c = 0; c < 3; c++) {
            near("Scene debug bypass", neutral[i][c], debug[i][c], 0);
        }
    }

    private static void checkExtremes(Fixture fixture) {
        float[] minimum = {-1, -1, 0.5F, 0, -1, 0.5F, -1, -1};
        float[] maximum = {1, 1, 1.5F, 2, 1, 2, 1, 1};
        for (int mask = 0; mask < 256; mask++) {
            float[] options = NEUTRAL.clone(); options[0] = (mask & 1) == 0 ? 0.25F : 4; options[1] = mask % 3;
            for (int c = 0; c < 8; c++) options[c + 4] = (mask & (1 << c)) == 0 ? minimum[c] : maximum[c];
            float[][] pixels = fixture.grade(options);
            for (int i = 0; i < pixels.length; i++) for (int c = 0; c < 4; c++) {
                if (!Float.isFinite(pixels[i][c]) || pixels[i][c] < 0 || pixels[i][c] > 1) {
                    throw new AssertionError("Invalid extreme output mask=" + mask + " pixel=" + i + " channel=" + c);
                }
            }
        }
    }

    private static void checkHostAbi(MetalDevice device, Fixture fixture) {
        Path directory;
        try { directory = Files.createTempDirectory("metalcraft-color-abi-"); }
        catch (IOException error) { throw new AssertionError(error); }
        try (var runtime = new ShaderPackRuntime(device, directory.resolve("packs"), directory.resolve("settings.json"));
             var direct = pipeline(device, source(true, false, resource("grade.metal")), MetalTexture.Format.BGRA8_UNORM);
             var output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.BGRA8_UNORM, COLORS.length, 1, 1))) {
            runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
            runtime.resize(COLORS.length, 1);
            List<String> ids = runtime.options().stream().filter(o -> o.apply() == ShaderPack.ApplyMode.UNIFORM)
                .map(ShaderPack.Option::id).toList();
            if (!UNIFORMS.equals(ids)) throw new AssertionError("Uniform ABI changed: " + ids);
            var executor = runtime.executor().orElseThrow();
            // Effects have spatial/temporal inputs and are covered by PostEffectsSmoke.
            for (int field = 0; field < 12; field++) {
                float[] options = NEUTRAL.clone();
                options[field] = switch (field) {
                    case 0 -> 1.35F; case 1 -> 2; case 2 -> 1; case 3 -> 2;
                    case 6, 7, 9 -> 1.35F; default -> 0.35F;
                };
                for (int i = 0; i < options.length; i++) {
                    runtime.setOption(UNIFORMS.get(i), i == 1 ? new String[]{"none", "aces", "reinhard"}[(int)options[i]]
                        : i == 2 ? options[i] == 1 ? "scene" : "off" : options[i]);
                }
                if (runtime.executor().orElseThrow() != executor) fail("Uniform update rebuilt the executor");
                try (var commands = fixture.queue.createCommandBuffer()) {
                    if (!executor.encode(commands, WorldComposition.world(fixture.scene, fixture.sceneView, COLORS.length, 1,
                        null, null, null, FrameBindings.ColorEncoding.LINEAR_SRGB))) fail("Host grading encode declined");
                    commands.commitAndWait();
                }
                ByteBuffer host = runtime.target("post_color").readback(fixture.queue, 0);
                ByteBuffer manual = fixture.draw(direct, options, output);
                for (int i = 0; i < host.remaining(); i++) if (host.get(i) != manual.get(i)) {
                    throw new AssertionError("Host/Metal option offset mismatch for " + UNIFORMS.get(field) + " at byte " + i);
                }
            }
            if (!WorldComposition.excludesHud(WorldComposition.PACK_POST)
                || WorldComposition.excludesHud(WorldComposition.Stage.PRESENT)) fail("World grade includes HUD/presentation");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            } catch (IOException error) { throw new AssertionError(error); }
        }
    }

    private static float[] changed(int field, float value) { float[] options = NEUTRAL.clone(); options[field] = value; return options; }
    private static float half(ByteBuffer bytes, int pixel, int channel) { return Float.float16ToFloat(bytes.order(ByteOrder.nativeOrder()).getShort(pixel * 8 + channel * 2)); }
    private static double quantized(float value) { return Float.float16ToFloat(Float.floatToFloat16(value)); }
    private static double encode(double linear) { return linear <= 0.0031308 ? 12.92 * Math.max(0, linear) : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055; }
    private static double decode(double encoded) { return encoded <= 0.04045 ? encoded / 12.92 : Math.pow((encoded + 0.055) / 1.055, 2.4); }
    private static double chroma(float[] encoded) {
        double[] linear = {decode(encoded[0]), decode(encoded[1]), decode(encoded[2])};
        return (Arrays.stream(linear).max().orElseThrow() - Arrays.stream(linear).min().orElseThrow()) / Arrays.stream(linear).max().orElseThrow();
    }
    private static void fail(String label) { throw new AssertionError(label); }
    private static void near(String label, double expected, double actual, double tolerance) {
        if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }

    private static String source(boolean linear, boolean invert, String grade) {
        return "#define MC_PASS_GRADE 1\n#define MC_SCENE_LINEAR_HDR " + (linear ? 1 : 0)
            + "\n#define MC_TEX_SCENE 0\n#define MC_OPTION_INVERT " + (invert ? 1 : 0) + "\n"
            + resource("shared/options.metal") + grade.replace("#include \"shared/color.metal\"", resource("shared/color.metal"))
                .replace("#include \"shared/post_effects.metal\"", resource("shared/post_effects.metal"));
    }
    private static String resource(String path) {
        try (var input = ColorGradingSmoke.class.getResourceAsStream("/assets/metalcraft/shaderpacks/standard/" + path)) {
            if (input == null) throw new IOException("Missing " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) { throw new AssertionError(error); }
    }
    private static MetalRenderPipeline pipeline(MetalDevice device, String source, MetalTexture.Format format) {
        return device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source, "grade_vertex", source, "grade_fragment",
            List.of(MetalRenderPipeline.ColorTarget.opaque(format)), null, MetalRenderPipeline.VertexDescriptor.EMPTY,
            MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT));
    }

    private static final class Fixture implements AutoCloseable {
        final MetalDevice device;
        final MetalCommandQueue queue;
        final MetalTexture scene;
        final MetalTextureView sceneView;
        final MetalTexture output;
        final MetalBuffer uniform;
        final MetalSampler sampler;
        final MetalRenderPipeline production;

        Fixture(MetalDevice device) {
            this.device = device;
            queue = device.createCommandQueue();
            scene = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, COLORS.length, 1, 1));
            sceneView = scene.createView();
            output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, COLORS.length, 1, 1));
            uniform = device.createBuffer(60, MetalBuffer.StorageMode.SHARED);
            sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
            production = pipeline(device, source(true, false, resource("grade.metal")), MetalTexture.Format.RGBA16_FLOAT);
            ByteBuffer pixels = ByteBuffer.allocateDirect(COLORS.length * 8).order(ByteOrder.nativeOrder());
            for (float[] color : COLORS) { for (float value : color) pixels.putShort(Float.floatToFloat16(value)); pixels.putShort(Float.floatToFloat16(0.5F)); }
            pixels.flip(); scene.upload(queue, 0, pixels);
        }
        float[][] grade(float[] options) {
            ByteBuffer pixels = draw(production, options).order(ByteOrder.nativeOrder());
            float[][] result = new float[COLORS.length][4];
            for (int i = 0; i < COLORS.length; i++) for (int c = 0; c < 4; c++) result[i][c] = Float.float16ToFloat(pixels.getShort(i * 8 + c * 2));
            return result;
        }
        ByteBuffer draw(MetalRenderPipeline pipeline, float[] options) { return draw(pipeline, options, output); }
        ByteBuffer draw(MetalRenderPipeline pipeline, float[] options, MetalTexture target) {
            try (var mapping = uniform.map()) {
                ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
                for (int i = 0; i < options.length; i++) { if (i == 1 || i == 2 || i >= 12) bytes.putInt(i * 4, (int)options[i]); else bytes.putFloat(i * 4, options[i]); }
            }
            try (var commands = queue.createCommandBuffer()) {
                try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(target, 0, 0, 0, 0)))) {
                    pass.setPipeline(pipeline);
                    pass.setUniformBuffer(0, uniform, 0, MetalRenderPass.STAGE_FRAGMENT);
                    pass.setTexture(0, sceneView, MetalRenderPass.STAGE_FRAGMENT);
                    pass.setSampler(0, sampler, MetalRenderPass.STAGE_FRAGMENT);
                    pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
                }
                commands.commitAndWait();
            }
            return target.readback(queue, 0);
        }
        public void close() { production.close(); sampler.close(); uniform.close(); output.close(); sceneView.close(); scene.close(); queue.close(); }
    }

    // Original pipeline, intentionally frozen independently of the implementation under test.
    private static final String OLD_GRADE = """
        #include <metal_stdlib>
        using namespace metal;
        #include "shared/color.metal"
        struct GradeVaryings { float4 position [[position]]; float2 uv; };
        vertex GradeVaryings grade_vertex(uint vertexId [[vertex_id]]) {
            const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
            float2 p = corners[vertexId % 3];
            return {float4(p, 0.0, 1.0), float2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5)};
        }
        static float3 acesFitted(float3 x) {
            const float a = 2.51, b = 0.03, c = 2.43, d = 0.59, e = 0.14;
            return saturate((x * (a * x + b)) / (x * (c * x + d) + e));
        }
        fragment float4 grade_fragment(GradeVaryings in [[stage_in]],
            texture2d<float> sceneTex [[texture(MC_TEX_SCENE)]], sampler sceneSampler [[sampler(MC_TEX_SCENE)]],
            constant PackOptions &options [[buffer(0)]]) {
            float3 sampled = sceneTex.sample(sceneSampler, in.uv).rgb;
            if (options.debugView == 1) {
        #if MC_SCENE_LINEAR_HDR
                return float4(mc_linear_to_srgb(sampled), 1.0);
        #else
                return float4(sampled, 1.0);
        #endif
            }
            float3 color = sampled * options.exposure;
        #if MC_OPTION_INVERT && !MC_SCENE_LINEAR_HDR
            color = float3(1.0) - color;
        #endif
            if (options.tonemap == 1) color = acesFitted(color);
        #if MC_SCENE_LINEAR_HDR
            color = mc_linear_to_srgb(color);
        #if MC_OPTION_INVERT
            color = float3(1.0) - color;
        #endif
        #endif
            return float4(color, 1.0);
        }
        """;
}
