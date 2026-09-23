package dev.metalcraft.client.shader.sky;

import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import dev.metalcraft.client.shader.SceneColor;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;

/** Bundled NASA albedo + height, packed in RGBA and mipmapped in linear light once per load. */
final class MoonSurfaceTexture implements AutoCloseable {
    private final MetalTexture texture;
    private final MetalTextureView view;

    MoonSurfaceTexture(MetalDevice device) {
        MetalTexture texture = null;
        try (var source = MoonSurfaceTexture.class.getResourceAsStream(
                "/assets/metalcraft/shaderpacks/standard/textures/moon-albedo.jpg");
             var heightSource = MoonSurfaceTexture.class.getResourceAsStream(
                "/assets/metalcraft/shaderpacks/standard/textures/moon-height.jpg")) {
            if (source == null || heightSource == null) throw new IOException("Missing bundled lunar surface");
            var image = ImageIO.read(source);
            var elevation = ImageIO.read(heightSource);
            if (image == null || elevation == null || image.getWidth() != 1024 || image.getHeight() != 512
                || elevation.getWidth() != 1024 || elevation.getHeight() != 512) {
                throw new IOException("Expected 1024x512 lunar maps");
            }
            int width = image.getWidth(), height = image.getHeight();
            int levels = 32 - Integer.numberOfLeadingZeros(width);
            texture = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,
                width, height, levels, MetalTexture.USAGE_SHADER_READ));
            byte[] pixels = new byte[width * height * 4];
            int[] linear = new int[256];
            for (int i = 0; i < 256; i++) linear[i] = Math.round(SceneColor.srgbToLinear(i / 255.0F) * 255);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, y), offset = (y * width + x) * 4;
                for (int c = 0; c < 3; c++) pixels[offset + c] = (byte)linear[(rgb >>> (16 - c * 8)) & 255];
                pixels[offset + 3] = (byte)elevation.getRaster().getSample(x, y, 0);
            }
            try (var queue = device.createCommandQueue()) {
                for (int level = 0; level < levels; level++) {
                    texture.upload(queue, level, ByteBuffer.wrap(pixels));
                    if (level + 1 == levels) break;
                    int nextWidth = Math.max(1, width / 2), nextHeight = Math.max(1, height / 2);
                    byte[] next = new byte[nextWidth * nextHeight * 4];
                    for (int y = 0; y < nextHeight; y++) for (int x = 0; x < nextWidth; x++) for (int c = 0; c < 4; c++) {
                        int sum = 0;
                        for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                            sum += pixels[(Math.min(height - 1, y * 2 + dy) * width
                                + Math.min(width - 1, x * 2 + dx)) * 4 + c] & 255;
                        }
                        next[(y * nextWidth + x) * 4 + c] = (byte)((sum + 2) / 4);
                    }
                    pixels = next; width = nextWidth; height = nextHeight;
                }
            }
            this.view = texture.createView();
            this.texture = texture;
        } catch (IOException | RuntimeException failure) {
            if (texture != null) texture.close();
            throw new IllegalStateException("Could not load lunar surface", failure);
        }
    }

    MetalTextureView view() { return this.view; }

    @Override public void close() {
        this.view.close();
        this.texture.close();
    }
}
