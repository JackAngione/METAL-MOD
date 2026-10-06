package dev.metalcraft.client.metal;

import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.WorldComposition;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import org.joml.Matrix4f;

/** Spatial and temporal output checks through the production Standard pack and Metal executor. */
public final class PostEffectsSmoke {
    private PostEffectsSmoke() { }

    public static void main(String[] args) {
        try (var device = MetalNative.openDefaultDevice().orElseThrow()) {
            if (args.length == 6 && args[0].equals("--bloom-capture")) {
                checkBloomCapture(device, Path.of(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]), Integer.parseInt(args[5]));
            } else {
                run(device);
            }
        }
    }

    /** Replays an ungraded live capture through Metal to tune halos without relaunching a world. */
    private static void checkBloomCapture(MetalDevice device, Path path, int left, int top, int right, int bottom) {
        java.awt.image.BufferedImage image;
        try { image = javax.imageio.ImageIO.read(path.toFile()); }
        catch (IOException error) { throw new AssertionError(error); }
        check(image != null && image.getWidth() == 3840 && image.getHeight() == 2160, "capture must be a 4K world image");
        int x0 = (left - 112) & ~3, y0 = (top - 112) & ~3;
        int width = ((right + 115) & ~3) - x0, height = ((bottom + 115) & ~3) - y0;
        check(x0 >= 0 && y0 >= 0 && x0 + width <= image.getWidth() && y0 + height <= image.getHeight(), "source halo is inside capture");
        try (var f = new Fixture(device)) {
            f.scene(width, height, "black", false);
            ByteBuffer colors = ByteBuffer.allocateDirect(width * height * 8).order(ByteOrder.nativeOrder());
            for (int y = y0; y < y0 + height; y++) for (int x = x0; x < x0 + width; x++) {
                int pixel = image.getRGB(x, y);
                for (int shift : new int[] {16, 8, 0}) colors.putShort(Float.floatToFloat16(
                    dev.metalcraft.client.shader.SceneColor.srgbToLinear(((pixel >>> shift) & 255) / 255.0F)));
                colors.putShort(Float.floatToFloat16(1));
            }
            f.scene.upload(f.queue, 0, colors.flip());
            byte[] baseline = f.frame(false);
            double previousEnergy = 0;
            for (int tier = 1; tier <= 3; tier++) {
                f.option("bloom", tier);
                byte[] graded = f.frame(false);
                double energy = 0, near = 0; int nearCount = 0, changed = 0;
                for (int y = top - 104; y <= bottom + 104; y += 2)
                    for (int x = left - 104; x <= right + 104; x += 2) {
                        int distance = Math.max(Math.max(left - x, x - right), Math.max(top - y, y - bottom));
                        if (distance < 2) continue;
                        int pixel = (y - y0) * width + x - x0;
                        double base = luminance(baseline, pixel);
                        if (base >= 95) continue;
                        double gain = luminance(graded, pixel) - base;
                        energy += gain;
                        if (distance <= 20) { near += gain; nearCount++; }
                        if (gain >= 1) changed++;
                    }
                double nearGain = near / nearCount;
                System.out.println("Captured-world bloom level " + tier + ": near gain=" + nearGain + ", halo energy=" + energy + ", visible halo samples=" + changed);
                check(nearGain >= 1 && changed >= 200, "bloom must visibly escape the real source silhouette");
                check(energy > previousEnergy * 1.1, "captured halo strength increases across tiers");
                previousEnergy = energy;
            }
        }
    }

    private static double luminance(byte[] pixels, int pixel) {
        return 0.2126 * channel(pixels, pixel, 2) + 0.7152 * channel(pixels, pixel, 1) + 0.0722 * channel(pixels, pixel, 0);
    }

    static void run(MetalDevice device) {
        try (var fixture = new Fixture(device)) {
            checkGrain(fixture);
            checkBloom(fixture);
            checkDepthOfField(fixture);
            checkLifecycle(fixture);
        }
        System.out.println("Post effects GPU: Off identity, animated monochrome grain/tier amplitude, HDR bloom halo/tier strength, "
            + "depth focus/blur, missing projection, debug bypass, resize and reload passed");
    }

    private static void checkGrain(Fixture f) {
        f.scene(128, 96, "gray", false);
        byte[] off = f.frame(true);
        check(Arrays.equals(off, f.frame(true)), "Off must be temporally stable");
        Object executor = f.runtime.executor().orElseThrow();
        double previous = 0;
        for (int tier = 1; tier <= 3; tier++) {
            f.option("film_grain", tier);
            check(!f.runtime.executor().orElseThrow().requiresWorldDepth(), "grain must not request depth");
            byte[] a = f.frame(false), b = f.frame(false);
            double sum = 0, square = 0;
            int changes = 0;
            for (int pixel = 0; pixel < f.width * f.height; pixel++) {
                int value = channel(a, pixel, 0), delta = value - channel(off, pixel, 0);
                check(value == channel(a, pixel, 1) && value == channel(a, pixel, 2), "grain must be monochrome on gray");
                check(channel(a, pixel, 3) == 255, "grain preserves opacity");
                sum += delta; square += delta * delta;
                if (value != channel(b, pixel, 0)) changes++;
            }
            double mean = sum / (f.width * f.height), rms = Math.sqrt(square / (f.width * f.height));
            check(Math.abs(mean) < 1, "grain must not shift average brightness: " + mean);
            check(rms > new double[] {0, 4.5, 9.5, 14.5}[tier], "grain must be visibly stronger at every tier: " + rms);
            check(rms > previous + 0.1, "grain tier must increase strength: " + rms + " after " + previous);
            check(changes > f.width * f.height / 10, "grain must animate between frames");
            previous = rms;
            System.out.println("Film grain level " + tier + ": RMS=" + rms + " mean=" + mean);
        }
        check(executor == f.runtime.executor().orElseThrow(), "strength sliders must not rebuild executor");
        f.off();
        check(Arrays.equals(off, f.frame(false)), "grain Off restores original pixels");
        // A narrow strip at 4K height exercises presentation scaling without
        // allocating a full world. Grain must survive a 2x downsample.
        f.scene(128, 2160, "gray", false);
        byte[] fullResolution = f.frame(false);
        f.option("film_grain", 1);
        byte[] grain = f.frame(false);
        double square = 0; int cells = 0;
        for (int y = 0; y < f.height; y += 2) for (int x = 0; x < f.width; x += 2) {
            double delta = 0;
            for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                int pixel = (y + dy) * f.width + x + dx;
                delta += channel(grain, pixel, 0) - channel(fullResolution, pixel, 0);
            }
            square += delta * delta / 16; cells++;
        }
        double downsampledRms = Math.sqrt(square / cells);
        check(downsampledRms > 3, "Low grain must survive Retina downsampling: " + downsampledRms);
        System.out.println("Low film grain after 2x downsample at 2160p: RMS=" + downsampledRms);
        f.off();
    }

    private static void checkBloom(Fixture f) {
        f.scene(256, 192, "bloom", false);
        byte[] off = f.frame(false);
        long previous = 0;
        for (int tier = 1; tier <= 3; tier++) {
            f.option("bloom", tier);
            check(!f.runtime.executor().orElseThrow().requiresWorldDepth(), "bloom must not request depth");
            byte[] pixels = f.frame(false);
            long halo = 0;
            for (int y = 0; y < f.height; y++) for (int x = 0; x < f.width; x++) {
                if (Math.abs(x - f.width / 2) <= 4 && Math.abs(y - f.height / 2) <= 4) continue;
                int index = y * f.width + x;
                halo += channel(pixels, index, 0) - channel(off, index, 0);
            }
            check(halo > previous, "bloom tier must increase halo energy: " + halo + " after " + previous);
            previous = halo;
            check(channel(pixels, 0, 0) == 0, "bloom must not brighten distant black pixels");
            System.out.println("Bloom level " + tier + ": halo=" + halo);
        }
        f.off();
        check(Arrays.equals(off, f.frame(false)), "bloom Off restores original pixels");
        f.scene(256, 192, "bloom_edge", false);
        f.option("bloom", 3);
        byte[] smooth = f.frame(false);
        int worstSlopeJump = 0;
        for (int x = f.width / 2 + 2; x < f.width / 2 + 72; x++) {
            int pixel = f.height / 2 * f.width + x;
            int left = channel(smooth, pixel - 1, 0), center = channel(smooth, pixel, 0), right = channel(smooth, pixel + 1, 0);
            check(right <= center + 1, "bloom halo fades away from a single edge");
            worstSlopeJump = Math.max(worstSlopeJump, Math.abs(left - 2 * center + right));
        }
        check(worstSlopeJump <= 2, "bloom halo must not contain visible stair steps or repeated edge copies: " + worstSlopeJump);
        System.out.println("High bloom edge smoothness: largest slope change=" + worstSlopeJump + " encoded levels");
        f.off();
        for (String pattern : new String[] {"bloom_ordinary", "bloom_dim"}) {
            f.scene(256, 192, pattern, false);
            byte[] baseline = f.frame(false);
            long previousHalo = 0;
            for (int tier = 1; tier <= 3; tier++) {
                f.option("bloom", tier);
                byte[] pixels = f.frame(false);
                long halo = 0;
                for (int y = 0; y < f.height; y++) for (int x = 0; x < f.width; x++) {
                    if (Math.abs(x - f.width / 2) <= 4 && Math.abs(y - f.height / 2) <= 4) continue;
                    int pixel = y * f.width + x;
                    halo += channel(pixels, pixel, 0) - channel(baseline, pixel, 0);
                }
                // The dim patch is inside the soft threshold and should start
                // glowing gently, without making all midtones bloom like lights.
                long minimumHalo = pattern.equals("bloom_dim") ? 100 : 1000;
                check(halo > minimumHalo && halo > previousHalo, "ordinary scene highlights need growing halos: " + pattern + " / " + halo);
                previousHalo = halo;
                System.out.println("Bloom " + pattern + " level " + tier + ": halo=" + halo);
            }
            f.off();
        }
        f.scene(128, 96, "dark", false);
        byte[] dark = f.frame(false);
        f.option("bloom", 3);
        check(Arrays.equals(dark, f.frame(false)), "bloom must not wash out a dark scene");
        f.off();
        f.scene(128, 96, "black", false);
        byte[] black = f.frame(false);
        f.option("bloom", 3);
        check(Arrays.equals(black, f.frame(false)), "bloom must not add light to a black scene");
        f.off();
    }

    private static void checkDepthOfField(Fixture f) {
        f.scene(128, 96, "depth", false);
        byte[] off = f.frame(true);
        double sharp = edgeEnergy(off, f.width, f.width * 3 / 4, f.width - 8, 12, f.height - 12);
        for (int tier = 1; tier <= 3; tier++) {
            f.option("depth_of_field", tier);
            check(f.runtime.executor().orElseThrow().requiresWorldDepth(), "DOF must request actual world depth");
            byte[] pixels = f.frame(true);
            double far = edgeEnergy(pixels, f.width, f.width * 3 / 4, f.width - 8, 12, f.height - 12);
            check(far < sharp * 0.99, "DOF must soften out-of-focus detail: " + far + " / " + sharp);
            check(far > sharp * new double[] {0, 0.60, 0.35, 0.15}[tier], "DOF must retain scene detail rather than replacing it with blur: " + far + " / " + sharp);
            check(regionDifference(off, pixels, f.width, 12, f.width / 3, 12, f.height - 12) < 1,
                "DOF must preserve the focused plane");
            System.out.println("Depth of field level " + tier + ": far edge energy=" + far + " baseline=" + sharp);
        }
        byte[] fallback = f.frame(true, null);
        check(Arrays.equals(off, fallback), "missing projection disables DOF safely");
        f.scene(128, 96, "depth", true);
        byte[] refocused = f.frame(true);
        check(regionDifference(off, refocused, f.width, f.width * 3 / 4, f.width - 8, 12, f.height - 12) < 1,
            "center depth must focus the new far plane");
        check(edgeEnergy(refocused, f.width, 12, f.width / 3, 12, f.height - 12)
            < edgeEnergy(off, f.width, 12, f.width / 3, 12, f.height - 12) * 0.98, "refocusing must blur the old near plane");
        f.off();
        // At an eight-block focus, both four-block foreground and twenty-block
        // scenery should be exactly sharp, even at High.
        f.scene(128, 96, "depth", false);
        f.depths(8, 4, 20);
        byte[] broadBand = f.frame(false);
        for (int tier = 1; tier <= 3; tier++) {
            f.option("depth_of_field", tier);
            check(Arrays.equals(broadBand, f.frame(true)), "DOF keeps the expanded 4–20 block focus range sharp at tier " + tier);
        }
        f.off();
        check(!f.runtime.executor().orElseThrow().requiresWorldDepth(), "DOF Off releases depth requirement");
        check(Arrays.equals(off, f.frame(false)), "DOF Off needs no depth and restores pixels");
        // A disk blur has a non-monotonic frequency response on one repeating
        // checker. Measure its spatial footprint separately to verify strength.
        f.scene(256, 96, "impulse", false);
        byte[] impulse = f.frame(false);
        int previousFootprint = 0;
        for (int tier = 1; tier <= 3; tier++) {
            f.option("depth_of_field", tier);
            byte[] blurred = f.frame(true);
            int footprint = 0;
            for (int x = f.width * 2 / 3; x < f.width; x++) {
                int pixel = f.height / 2 * f.width + x;
                if (Math.abs(channel(blurred, pixel, 0) - channel(impulse, pixel, 0)) > 2) footprint++;
            }
            check(footprint > previousFootprint, "DOF tier increases blur footprint: " + footprint + " after " + previousFootprint);
            previousFootprint = footprint;
            System.out.println("Depth of field level " + tier + ": impulse footprint=" + footprint);
        }
        f.off();
    }

    private static void checkLifecycle(Fixture f) {
        f.scene(127, 93, "depth", false);
        byte[] off = f.frame(true);
        check(f.effectTextures().isEmpty(), "Off must not allocate effect targets");
        f.option("film_grain", 3); f.option("bloom", 3); f.option("depth_of_field", 3);
        f.frame(true);
        var allocated = f.effectTextures();
        check(allocated.size() == 5, "bloom/DOF own four quarter targets and a one-pixel focus target");
        for (var texture : allocated) {
            var descriptor = texture.descriptor();
            check(descriptor.width() == 1 && descriptor.height() == 1
                || descriptor.width() == 32 && descriptor.height() == 24, "odd sizes round up to quarter resolution");
        }
        f.option("debug_view", "scene");
        check(Arrays.equals(off, f.frame(true)), "scene debug bypasses every post effect");
        check(f.effectTextures().isEmpty() && allocated.stream().allMatch(MetalTexture::isClosed), "debug bypass releases effect targets");
        f.option("debug_view", "off");
        f.option("film_grain", 0);
        byte[] before = f.frame(true);
        var beforeReload = f.effectTextures();
        f.runtime.reload();
        check(beforeReload.stream().allMatch(MetalTexture::isClosed), "reload closes prior effect targets");
        check(f.runtime.isActive() && f.runtime.lastError().isEmpty(), "effects survive reload");
        check(Arrays.equals(before, f.frame(true)), "reload keeps deterministic bloom and DOF pixels");
        var beforeResize = f.effectTextures();
        f.scene(63, 47, "depth", true);
        f.frame(true);
        check(beforeResize.stream().filter(t -> t.descriptor().width() != 1).allMatch(MetalTexture::isClosed), "resize retires former quarter targets");
        var beforeOff = f.effectTextures();
        f.off();
        f.frame(false);
        check(f.effectTextures().isEmpty() && beforeOff.stream().allMatch(MetalTexture::isClosed), "Off releases every optional target");
        f.scene(1, 1, "black", false);
        f.option("bloom", 3); f.option("depth_of_field", 3);
        byte[] smallest = f.frame(true);
        check(channel(smallest, 0, 0) == 0 && channel(smallest, 0, 3) == 255, "1x1 black scene is safe");
        f.off();
    }

    private static double regionDifference(byte[] a, byte[] b, int width, int x0, int x1, int y0, int y1) {
        double total = 0; int count = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) {
            total += Math.abs(channel(a, y * width + x, 0) - channel(b, y * width + x, 0)); count++;
        }
        return total / Math.max(1, count);
    }
    private static double edgeEnergy(byte[] a, int width, int x0, int x1, int y0, int y1) {
        double total = 0; int count = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1 - 1; x++) {
            total += Math.abs(channel(a, y * width + x, 0) - channel(a, y * width + x + 1, 0)); count++;
        }
        return total / Math.max(1, count);
    }
    private static int channel(byte[] pixels, int index, int component) { return pixels[index * 4 + component] & 255; }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class Fixture implements AutoCloseable {
        private final MetalDevice device;
        private final MetalCommandQueue queue;
        private final ShaderPackRuntime runtime;
        private final Path directory;
        private MetalTexture scene, depth;
        private MetalTextureView sceneView, depthView;
        private int width, height;
        // Exact infinite-far, reverse-Z perspective: device depth = 0.1 / view distance.
        private final Matrix4f projection = new Matrix4f().zero().m00(1).m11(1).m23(-1).m32(0.1F);

        Fixture(MetalDevice device) {
            this.device = device;
            try { directory = Files.createTempDirectory("metalcraft-post-effects-"); }
            catch (IOException error) { throw new AssertionError(error); }
            queue = device.createCommandQueue();
            runtime = new ShaderPackRuntime(device, directory.resolve("packs"), directory.resolve("settings.json"));
            runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
            check(runtime.isActive(), "Standard must compile with effects: " + runtime.lastError());
        }

        void option(String id, Object value) { runtime.setOption(id, value); }
        void off() { option("film_grain", 0); option("bloom", 0); option("depth_of_field", 0); }

        void scene(int width, int height, String pattern, boolean farFocus) {
            closeInputs(); this.width = width; this.height = height;
            scene = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, width, height, 1));
            depth = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.DEPTH32_FLOAT, width, height, 1));
            sceneView = scene.createView(); depthView = depth.createView();
            ByteBuffer colors = ByteBuffer.allocateDirect(width * height * 8).order(ByteOrder.nativeOrder());
            ByteBuffer depths = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                float value = switch (pattern) {
                    case "gray" -> 0.214F;
                    case "bloom" -> Math.abs(x - width / 2) <= 4 && Math.abs(y - height / 2) <= 4 ? 8 : 0;
                    case "bloom_ordinary" -> Math.abs(x - width / 2) <= 4 && Math.abs(y - height / 2) <= 4 ? 0.5F : 0;
                    case "bloom_dim" -> Math.abs(x - width / 2) <= 4 && Math.abs(y - height / 2) <= 4 ? 0.2F : 0;
                    case "bloom_edge" -> x < width / 2 ? 0.5F : 0;
                    case "dark" -> 0.05F;
                    case "depth" -> (x / 4) % 2 == 0 ? 0.04F : 0.65F;
                    case "impulse" -> x >= width * 4 / 5 && x < width * 4 / 5 + 4 ? 0.65F : 0.04F;
                    default -> 0;
                };
                for (int c = 0; c < 3; c++) colors.putShort(Float.floatToFloat16(value));
                colors.putShort(Float.floatToFloat16(1));
                boolean center = Math.abs(x - width / 2) < 8 && Math.abs(y - height / 2) < 8;
                float distance = center ? farFocus ? 40 : 4 : x < width * 2 / 3 ? 4 : 40;
                depths.putFloat(0.1F / distance);
            }
            scene.upload(queue, 0, colors.flip()); depth.upload(queue, 0, depths.flip()); runtime.resize(width, height);
        }

        void depths(float focusDistance, float nearDistance, float farDistance) {
            ByteBuffer depths = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder());
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                boolean center = Math.abs(x - width / 2) < 8 && Math.abs(y - height / 2) < 8;
                float distance = center ? focusDistance : x < width * 2 / 3 ? nearDistance : farDistance;
                depths.putFloat(0.1F / distance);
            }
            depth.upload(queue, 0, depths.flip());
        }

        byte[] frame(boolean withDepth) { return frame(withDepth, projection); }
        byte[] frame(boolean withDepth, Matrix4f matrix) {
            try (var commands = queue.createCommandBuffer()) {
                boolean encoded = runtime.executor().orElseThrow().encode(commands, WorldComposition.world(scene, sceneView, width, height,
                    withDepth ? depth : null, withDepth ? depthView : null, matrix, FrameBindings.ColorEncoding.LINEAR_SRGB));
                check(encoded, "post effects must encode: " + runtime.lastError()); commands.commitAndWait();
            }
            ByteBuffer pixels = runtime.target("post_color").readback(queue, 0);
            byte[] output = new byte[pixels.remaining()]; pixels.get(output); return output;
        }

        java.util.List<MetalTexture> effectTextures() {
            // Keep resource observability in the test instead of exporting a production diagnostic API.
            try {
                Object executor = runtime.executor().orElseThrow();
                var field = executor.getClass().getDeclaredField("postEffects"); field.setAccessible(true);
                Object effects = field.get(executor);
                var textures = new java.util.ArrayList<MetalTexture>();
                for (String name : new String[] {"bloomA", "bloomB", "dofA", "dofB", "focus"}) {
                    var targetField = effects.getClass().getDeclaredField(name); targetField.setAccessible(true);
                    Object target = targetField.get(effects);
                    if (target == null) continue;
                    var texture = target.getClass().getDeclaredMethod("texture"); texture.setAccessible(true);
                    textures.add((MetalTexture)texture.invoke(target));
                }
                return textures;
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }

        private void closeInputs() {
            if (sceneView != null) sceneView.close(); if (depthView != null) depthView.close();
            if (scene != null) scene.close(); if (depth != null) depth.close();
        }
        public void close() {
            runtime.close(); closeInputs(); queue.close();
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException error) { throw new AssertionError(error); }
        }
    }
}
