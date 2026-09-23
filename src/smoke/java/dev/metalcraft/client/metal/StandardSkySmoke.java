package dev.metalcraft.client.metal;

import dev.metalcraft.client.shader.SceneColor;
import dev.metalcraft.client.shader.sky.SkyFrameInputs;
import dev.metalcraft.client.shader.sky.StandardSkyRenderer;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Renders the production Metal sky, reads HDR pixels, and checks composition/weather. */
public final class StandardSkySmoke {
    private static final int WIDTH = 384, HEIGHT = 216;

    public static void run(MetalDevice device) {
        try (var renderer = new StandardSkyRenderer(device, resource("shared/sky.metal"), resource("shared/celestials.metal"), resource("sky.metal"));
             var queue = device.createCommandQueue();
             var scene = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, WIDTH, HEIGHT, 1));
             var clouds = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, WIDTH, HEIGHT, 1));
             var cloudView = clouds.createView();
             var frame = device.createBuffer(SkyFrameInputs.UNIFORM_BYTES, MetalBuffer.StorageMode.SHARED)) {
            float dayMean = 0;
            for (String variant : new String[]{"day", "sunset", "night", "rain", "off", "above"}) {
                SkyFrameInputs inputs = fixture(variant);
                try (var mapping = frame.map()) { inputs.write(mapping.bytes()); }
                try (var commands = queue.createCommandBuffer()) {
                    try (var pass = commands.beginRenderPass(StandardSkyRenderer.targetPass(scene, MetalRenderPass.LoadAction.DONT_CARE))) {
                        renderer.encodeAtmosphere(pass, frame);
                        renderer.encodeCelestials(pass, frame);
                    }
                    try (var pass = commands.beginRenderPass(StandardSkyRenderer.targetPass(clouds, MetalRenderPass.LoadAction.DONT_CARE))) {
                        renderer.encodeClouds(pass, frame);
                    }
                    commands.commitAndWait();
                }
                ByteBuffer before = scene.readback(queue, 0).order(ByteOrder.nativeOrder());
                ByteBuffer layer = clouds.readback(queue, 0).order(ByteOrder.nativeOrder());
                int holes = 0, dense = 0;
                for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                    float alpha = component(layer, pixel, 3);
                    if (alpha < 0.01) holes++;
                    if (alpha > 0.4) dense++;
                    for (int channel = 0; channel < 4; channel++) {
                        float value = component(layer, pixel, channel);
                        if (!Float.isFinite(value) || value < 0 || (channel == 3 && value > 1)) {
                            throw new AssertionError(variant + " non-finite/invalid cloud output " + value);
                        }
                    }
                }
                if (variant.equals("off") && holes != WIDTH * HEIGHT) throw new AssertionError("Cloud toggle leaves coverage");
                if (variant.equals("day") && (holes < 100 || dense < 100)) {
                    throw new AssertionError("Clouds lack separated formations: holes=" + holes + " dense=" + dense);
                }
                try (var commands = queue.createCommandBuffer()) {
                    try (var pass = commands.beginRenderPass(StandardSkyRenderer.targetPass(scene, MetalRenderPass.LoadAction.LOAD))) {
                        renderer.encodeComposite(pass, cloudView);
                    }
                    commands.commitAndWait();
                }
                ByteBuffer result = scene.readback(queue, 0).order(ByteOrder.nativeOrder());
                double luminance = 0;
                for (int pixel = 0; pixel < WIDTH * HEIGHT; pixel++) {
                    float alpha = component(layer, pixel, 3);
                    for (int channel = 0; channel < 3; channel++) {
                        float value = component(result, pixel, channel);
                        float expected = component(layer, pixel, channel) + component(before, pixel, channel) * (1 - alpha);
                        if (!Float.isFinite(value) || Math.abs(value - expected) > 0.005) {
                            throw new AssertionError(variant + " premultiplied sky blend mismatch: " + value + " vs " + expected);
                        }
                    }
                    luminance += component(result, pixel, 0) * 0.2126 + component(result, pixel, 1) * 0.7152
                        + component(result, pixel, 2) * 0.0722;
                }
                float mean = (float)(luminance / (WIDTH * HEIGHT));
                if (variant.equals("day")) dayMean = mean;
                if (variant.equals("night") && mean > dayMean * 0.15) throw new AssertionError("Night sky too bright");
                if (variant.equals("rain") && mean > dayMean * 0.85) throw new AssertionError("Rain did not darken sky");
                if (variant.equals("sunset")) {
                    int center = (int)(HEIGHT * 0.37) * WIDTH + WIDTH / 2;
                    if (component(before, center, 0) < component(before, center, 2) * 1.4) {
                        throw new AssertionError("Sunset horizon has no warm scattering");
                    }
                }
                save(result, variant);
                System.out.println("Standard sky " + variant + ": mean=" + mean + ", holes=" + holes + ", dense=" + dense);
            }
            checkCelestials(renderer, queue, scene, frame);
            checkWalkingProjection(renderer, queue, scene, clouds, cloudView, frame);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
        System.out.println("Standard sky GPU: finite HDR, cloud gaps, toggle, night/rain, sunset and premultiplied blending passed");
    }

    /** Walking translates the view-bob matrix inside the projection. Only its
     * rotation may change an infinite sky's direction; a near-plane point must not. */
    private static void checkWalkingProjection(StandardSkyRenderer renderer, MetalCommandQueue queue,
            MetalTexture scene, MetalTexture clouds, MetalTextureView cloudView, MetalBuffer frame) {
        double maximumDifference = 0;
        for (String variant : new String[]{"sunset", "night"}) {
            SkyFrameInputs base = fixture(variant);
            Matrix4f view = new Matrix4f().rotateY((float)Math.PI / 2).rotateX((float)Math.toRadians(15)).invert();
            Matrix4f projection = new Matrix4f().perspective((float)Math.toRadians(75), (float)WIDTH / HEIGHT, 10000, 0.05F, true);
            Matrix4f tilt = new Matrix4f().rotateZ(0.009F).rotateX(0.008F);
            ByteBuffer reference = walkingFixture(renderer, queue, scene, clouds, cloudView, frame, base,
                new Matrix4f(projection).mul(tilt).mul(view).invert());
            for (float shift : new float[]{-0.08F, 0.04F, 0.1F}) {
                // Same rotation/FOV, different phases of the actual view-bob translation.
                Matrix4f translated = new Matrix4f(projection).translate(shift * 0.5F, -Math.abs(shift), 0)
                    .mul(tilt).mul(view).invert();
                ByteBuffer moved = walkingFixture(renderer, queue, scene, clouds, cloudView, frame, base, translated);
                double difference = pixelDifference(reference, moved);
                maximumDifference = Math.max(maximumDifference, difference);
                if (difference > 0.0003) throw new AssertionError("Sky moves with view-bob translation: "
                    + variant + ", shift=" + shift + ", mean HDR difference=" + difference);
            }
            ByteBuffer turned = walkingFixture(renderer, queue, scene, clouds, cloudView, frame, base,
                new Matrix4f(projection).rotateY(0.25F).mul(tilt).mul(view).invert());
            if (pixelDifference(reference, turned) < 0.001) throw new AssertionError("Sky stopped following camera rotation");
        }
        System.out.println("Standard sky walking GPU: sun/moon, atmosphere and clouds invariant under view-bob translation; camera rotation retained; max mean HDR difference=" + maximumDifference);
    }

    private static ByteBuffer walkingFixture(StandardSkyRenderer renderer, MetalCommandQueue queue,
            MetalTexture scene, MetalTexture clouds, MetalTextureView cloudView, MetalBuffer frame,
            SkyFrameInputs base, Matrix4f clipToWorld) {
        var inputs = new SkyFrameInputs(clipToWorld, base.skyColor(), base.sunRain(), base.cloudOrigin(),
            base.cloudSettings(), base.fogColor(), base.moonPhase());
        try (var mapping = frame.map()) { inputs.write(mapping.bytes()); }
        try (var commands = queue.createCommandBuffer()) {
            try (var pass = commands.beginRenderPass(StandardSkyRenderer.targetPass(scene, MetalRenderPass.LoadAction.DONT_CARE))) {
                renderer.encodeAtmosphere(pass, frame);
                renderer.encodeCelestials(pass, frame);
            }
            try (var pass = commands.beginRenderPass(StandardSkyRenderer.targetPass(clouds, MetalRenderPass.LoadAction.DONT_CARE))) {
                renderer.encodeClouds(pass, frame);
            }
            try (var pass = commands.beginRenderPass(StandardSkyRenderer.targetPass(scene, MetalRenderPass.LoadAction.LOAD))) {
                renderer.encodeComposite(pass, cloudView);
            }
            commands.commitAndWait();
        }
        return scene.readback(queue, 0).order(ByteOrder.nativeOrder());
    }

    private static double pixelDifference(ByteBuffer first, ByteBuffer second) {
        double sum = 0;
        for (int i = 0; i < WIDTH * HEIGHT; i++) for (int c = 0; c < 3; c++) {
            float a = component(first, i, c), b = component(second, i, c);
            if (!Float.isFinite(a) || !Float.isFinite(b)) throw new AssertionError("Non-finite walking sky");
            sum += Math.abs(a - b);
        }
        return sum / (WIDTH * HEIGHT * 3);
    }

    private static void checkCelestials(StandardSkyRenderer renderer, MetalCommandQueue queue,
                                         MetalTexture target, MetalBuffer frame) throws java.io.IOException {
        double[] energy = new double[8];
        for (int phase = 0; phase < 8; phase++) {
            ByteBuffer pixels = celestialFixture(renderer, queue, target, frame, false, phase, 1, false);
            int minX = WIDTH, maxX = 0, minY = HEIGHT, maxY = 0, covered = 0;
            double left = 0, right = 0;
            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
                int pixel = y * WIDTH + x;
                float a = component(pixels, pixel, 3);
                if (a > 0.5) {
                    minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y); maxY = Math.max(maxY, y); covered++;
                }
                float value = component(pixels, pixel, 0);
                for (int c = 0; c < 4; c++) {
                    if (!Float.isFinite(component(pixels, pixel, c)) || component(pixels, pixel, c) < 0) {
                        throw new AssertionError("Invalid moon phase " + phase);
                    }
                }
                energy[phase] += value;
                if (x < WIDTH / 2) left += value; else right += value;
            }
            int box = (maxX - minX + 1) * (maxY - minY + 1);
            if (Math.abs((maxX - minX) - (maxY - minY)) > 2 || covered < 1000
                || covered < box * 0.72 || covered > box * 0.83) {
                throw new AssertionError("Moon silhouette is not a circular opaque sphere: phase=" + phase);
            }
            // Northern-view waning is lit on the left; waxing on the right.
            if (phase == 2 && left < right * 3 || phase == 6 && right < left * 3) {
                throw new AssertionError("Waning/waxing terminator is on the wrong side: " + phase);
            }
            save(pixels, "moon-phase-" + phase);
        }
        if (energy[4] > energy[0] * 0.06 || energy[2] < energy[0] * 0.20 || energy[2] > energy[0] * 0.65
            || energy[3] >= energy[2] || energy[1] <= energy[2]
            || energy[5] >= energy[6] || energy[7] <= energy[6]) {
            throw new AssertionError("Incorrect lunar illumination: " + java.util.Arrays.toString(energy));
        }
        ByteBuffer rain = celestialFixture(renderer, queue, target, frame, false, 0, 0.15F, false);
        double rainyEnergy = 0;
        for (int i = 0; i < WIDTH * HEIGHT; i++) rainyEnergy += component(rain, i, 0);
        if (Math.abs(rainyEnergy / energy[0] - 0.15) > 0.005) throw new AssertionError("Moon ignores rain brightness");
        ByteBuffer sun = celestialFixture(renderer, queue, target, frame, true, 0, 1, false);
        int center = HEIGHT / 2 * WIDTH + WIDTH / 2;
        int limb = HEIGHT / 2 * WIDTH + WIDTH / 2 + 33;
        if (component(sun, center, 0) < 2 || component(sun, center, 0) < component(sun, limb, 0) * 1.2
            || component(sun, center, 3) < 0.99 || component(sun, 0, 3) != 0) {
            throw new AssertionError("Sun lacks an HDR disc with limb darkening");
        }
        save(sun, "sun-disc");
        ByteBuffer behind = celestialFixture(renderer, queue, target, frame, true, 0, 1, true);
        for (int i = 0; i < WIDTH * HEIGHT; i++) for (int c = 0; c < 4; c++) {
            if (component(behind, i, c) != 0) throw new AssertionError("Celestial body mirrored behind camera");
        }
        System.out.println("Standard celestials GPU: circular opaque moons, eight phases, waxing/waning, rain, HDR sun limb and rear culling passed; energy="
            + java.util.Arrays.toString(energy));
    }

    private static ByteBuffer celestialFixture(StandardSkyRenderer renderer, MetalCommandQueue queue,
                                                MetalTexture target, MetalBuffer frame, boolean sun,
                                                int phase, float weather, boolean behind) {
        var transform = new Matrix4f().rotateY((float)Math.PI / 2).rotateX((float)Math.toRadians(15))
            .mul(new Matrix4f().perspective((float)Math.toRadians(12), (float)WIDTH / HEIGHT, 10000, 0.1F, true).invert());
        var visible = new Vector4f(-0.9659258F, 0.258819F, 0, 0);
        var hidden = new Vector4f(visible).negate();
        var solar = new Vector4f(sun && !behind ? visible : hidden); solar.w = weather;
        var lunar = new Vector4f(!sun && !behind ? visible : hidden); lunar.w = phase;
        var inputs = new SkyFrameInputs(transform, new Vector4f(0.002F, 0.004F, 0.012F, 1), solar,
            new Vector4f(0, 96, 0, 192), new Vector4f(), new Vector4f(0.004F, 0.006F, 0.012F, 1), lunar);
        try (var mapping = frame.map()) { inputs.write(mapping.bytes()); }
        try (var commands = queue.createCommandBuffer()) {
            try (var pass = commands.beginRenderPass(StandardSkyRenderer.targetPass(target, MetalRenderPass.LoadAction.CLEAR))) {
                renderer.encodeCelestials(pass, frame);
            }
            commands.commitAndWait();
        }
        return target.readback(queue, 0).order(ByteOrder.nativeOrder());
    }

    private static SkyFrameInputs fixture(String variant) {
        boolean night = variant.equals("night"), rain = variant.equals("rain"), sunset = variant.equals("sunset");
        var transform = new Matrix4f().rotateY((float)Math.PI / 2).rotateX((float)Math.toRadians(variant.equals("above") ? -20 : 15))
            .mul(new Matrix4f().perspective((float)Math.toRadians(75), (float)WIDTH / HEIGHT, 10000, 0.1F, true).invert());
        return new SkyFrameInputs(transform,
            night ? new Vector4f(0.002F, 0.004F, 0.012F, 1) : new Vector4f(0.24F, 0.45F, 0.80F, 1),
            night ? new Vector4f(0, -1, 0, 1) : sunset ? new Vector4f(-0.9995F, 0.03F, 0, 1) : new Vector4f(0, 1, 0, rain ? 0.15F : 1),
            new Vector4f(1234, variant.equals("above") ? 800 : 96, 5678, 192),
            new Vector4f(variant.equals("off") ? 0 : 2, 1, 0, 0),
            night ? new Vector4f(0.004F, 0.006F, 0.012F, 1) : new Vector4f(0.40F, 0.48F, 0.58F, 1),
            night ? new Vector4f(-0.9659258F, 0.258819F, 0, 0) : new Vector4f(0, -1, 0, 0));
    }

    private static String resource(String name) throws java.io.IOException {
        try (var input = StandardSkySmoke.class.getResourceAsStream("/assets/metalcraft/shaderpacks/standard/" + name)) {
            if (input == null) throw new java.io.IOException("Missing sky asset " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static float component(ByteBuffer bytes, int pixel, int channel) {
        return Float.float16ToFloat(bytes.getShort(pixel * 8 + channel * 2));
    }

    private static void save(ByteBuffer pixels, String name) throws java.io.IOException {
        var image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
            int rgb = 0;
            for (int c = 0; c < 3; c++) rgb = (rgb << 8) | Math.clamp(Math.round(SceneColor.linearToSrgb(
                component(pixels, y * WIDTH + x, c)) * 255), 0, 255);
            // Match Minecraft's final presentation orientation.
            image.setRGB(x, HEIGHT - 1 - y, rgb);
        }
        Path directory = Path.of("build/reports/sky");
        Files.createDirectories(directory);
        ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
    }
}
