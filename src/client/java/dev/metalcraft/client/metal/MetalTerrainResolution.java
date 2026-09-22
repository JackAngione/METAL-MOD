package dev.metalcraft.client.metal;

/** Reusable reduced-resolution shading targets. Depth/coverage are rasterized at scene resolution. */
public final class MetalTerrainResolution implements AutoCloseable {
    static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("metalcraft.terrainResolution", "true"));
    private final MetalDevice device;
    private final Band[] bands = new Band[3];
    private int width, height;
    private MetalTexture.Format format;
    private long halfDraws, quarterDraws;

    MetalTerrainResolution(MetalDevice device) { this.device = device; }

    Band band(int tier, int width, int height, MetalTexture.Format format) {
        if (width != this.width || height != this.height || format != this.format) {
            close();
            this.width = width; this.height = height; this.format = format;
        }
        if (bands[tier] == null) bands[tier] = new Band(device, width, height, tier, format);
        return bands[tier];
    }

    void drawn(int tier) { if (tier == 1) halfDraws++; else quarterDraws++; }
    Stats stats() {
        return new Stats(width, height, bands[1] == null ? 0 : bands[1].width,
                bands[1] == null ? 0 : bands[1].height, bands[2] == null ? 0 : bands[2].width,
                bands[2] == null ? 0 : bands[2].height, halfDraws, quarterDraws);
    }
    public record Stats(int sceneWidth, int sceneHeight, int halfWidth, int halfHeight,
                 int quarterWidth, int quarterHeight, long halfDraws, long quarterDraws) { }

    @Override public void close() {
        for (int i = 1; i <= 2; i++) if (bands[i] != null) { bands[i].close(); bands[i] = null; }
    }

    static final class Band implements AutoCloseable {
        final int width, height;
        final MetalTexture color, depth;
        final MetalTextureView colorView, depthView;
        final MetalBuffer parameters;
        final MetalRenderPass.Descriptor descriptor;

        Band(MetalDevice device, int sceneWidth, int sceneHeight, int tier, MetalTexture.Format format) {
            int divisor = 1 << tier;
            width = Math.max(1, (sceneWidth + divisor - 1) / divisor);
            height = Math.max(1, (sceneHeight + divisor - 1) / divisor);
            color = device.createTexture(new MetalTexture.Descriptor(format, width, height, 1));
            MetalTexture allocatedDepth = null;
            MetalBuffer allocatedParameters = null;
            try {
                allocatedDepth = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.DEPTH32_FLOAT, width, height, 1));
                allocatedParameters = device.createBuffer(16, MetalBuffer.StorageMode.SHARED);
                try (var mapping = allocatedParameters.map()) {
                    mapping.bytes().putFloat(sceneWidth).putFloat(sceneHeight).putFloat(width).putFloat(height);
                }
                colorView = color.createView(); depthView = allocatedDepth.createView();
            } catch (RuntimeException failure) {
                color.close();
                if (allocatedDepth != null) allocatedDepth.close();
                if (allocatedParameters != null) allocatedParameters.close();
                throw failure;
            }
            depth = allocatedDepth; parameters = allocatedParameters;
            // Minecraft 26.2 uses reverse Z. No low-resolution depth is copied into scene depth.
            descriptor = new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(color, 0, 0, 0, 0),
                    new MetalRenderPass.DepthAttachment(depth, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0));
        }
        @Override public void close() { color.close(); depth.close(); parameters.close(); }
    }

    /** Inject after binding vanilla resources, so reserved slots cannot be remapped by name. */
    static String reconstructFragment(String source) {
        String entry = "void main() {";
        if (!source.contains(entry) || source.indexOf(entry) != source.lastIndexOf(entry))
            throw new IllegalArgumentException("Unsupported terrain fragment entry point");
        String gradients = "";
        if (source.contains("dFdx(uv)")) {
            // Helpers after a divergent early return must not evaluate derivatives.
            // Capture the original UV gradients for all lanes before reconstruction.
            int versionEnd = source.indexOf('\n');
            source = source.substring(0, versionEnd + 1) + "vec2 mcFineDu; vec2 mcFineDv;\n" + source.substring(versionEnd + 1);
            source = source.replace("dFdx(uv)", "mcFineDu").replace("dFdy(uv)", "mcFineDv");
            gradients = "mcFineDu = dFdx(texCoord0); mcFineDv = dFdy(texCoord0);\n";
        }
        return source.replace(entry, """
                layout(binding = 14) uniform sampler2D mcLodColor;
                layout(binding = 15) uniform sampler2D mcLodDepth;
                layout(std140, binding = 15) uniform McLodExtent { vec4 mcLodExtent; };
                void main() {
                """ + gradients + """
                    vec2 mcRatio = mcLodExtent.xy / mcLodExtent.zw;
                    ivec2 mcPixel = clamp(ivec2(gl_FragCoord.xy / mcRatio), ivec2(0), ivec2(mcLodExtent.zw) - 1);
                    float mcDepth = texelFetch(mcLodDepth, mcPixel, 0).r;
                    // Extrapolate this primitive's depth plane to the coarse sample center.
                    // A depth match reuses coarse color; silhouettes, thin features and
                    // disocclusions run the original material below at full resolution.
                    vec2 mcOffset = (vec2(mcPixel) + 0.5) * mcRatio - gl_FragCoord.xy;
                    float mcExpected = gl_FragCoord.z + dot(vec2(dFdx(gl_FragCoord.z), dFdy(gl_FragCoord.z)), mcOffset);
                    if (mcDepth > 0.0 && abs(mcDepth - mcExpected) <= max(1e-7, abs(mcExpected) * 0.0002)) {
                        fragColor = texelFetch(mcLodColor, mcPixel, 0);
                        return;
                    }
                """);
    }
}
