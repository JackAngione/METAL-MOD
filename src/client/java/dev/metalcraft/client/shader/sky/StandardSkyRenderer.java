package dev.metalcraft.client.shader.sky;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalLinearWorldSession;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalSampler;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/** Standard's linear sky and premultiplied cloud layer, before any terrain draws. */
public final class StandardSkyRenderer implements AutoCloseable {
    private static final boolean BASELINE_PROBE = Boolean.getBoolean("metalcraft.baselineLightingBenchmark");
    private final MetalDevice device;
    private final MetalRenderPipeline atmosphere;
    private final MetalRenderPipeline celestials;
    private final MetalRenderPipeline clouds;
    private final MetalRenderPipeline composite;
    private final MetalSampler sampler;
    private final MoonSurfaceTexture moonSurface;
    private @Nullable MetalTexture cloudTarget;
    private @Nullable MetalTextureView cloudView;
    private @Nullable MetalLinearWorldSession renderedSession;
    private @Nullable Matrix4f rasterProjection;
    private long renderedFrames;

    public StandardSkyRenderer(MetalDevice device, String sharedSource, String celestialSource, String source) {
        this.device = device;
        String combined = "#include <metal_stdlib>\nusing namespace metal;\n" + sharedSource + "\n" + celestialSource + "\n" + source;
        MetalRenderPipeline background = null, bodies = null, layer = null, blend = null;
        MetalSampler filtered = null;
        MoonSurfaceTexture lunar = null;
        try {
            background = pipeline(device, combined, "sky_atmosphere", false);
            bodies = pipeline(device, combined, "sky_celestials", true);
            layer = pipeline(device, combined, "sky_clouds", false);
            blend = pipeline(device, combined, "sky_composite", true);
            filtered = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.LINEAR,
                MetalSampler.Filter.LINEAR, MetalSampler.AddressMode.CLAMP_TO_EDGE));
            lunar = new MoonSurfaceTexture(device);
        } catch (RuntimeException failure) {
            if (background != null) background.close();
            if (bodies != null) bodies.close();
            if (layer != null) layer.close();
            if (blend != null) blend.close();
            if (filtered != null) filtered.close();
            if (lunar != null) lunar.close();
            throw failure;
        }
        this.atmosphere = background;
        this.celestials = bodies;
        this.clouds = layer;
        this.composite = blend;
        this.sampler = filtered;
        this.moonSurface = lunar;
    }

    private static MetalRenderPipeline pipeline(MetalDevice device, String source, String fragment, boolean blend) {
        var blending = blend ? new MetalRenderPipeline.BlendState(
            MetalRenderPipeline.BlendFactor.ONE, MetalRenderPipeline.BlendFactor.ONE_MINUS_SOURCE_ALPHA,
            MetalRenderPipeline.BlendOperation.ADD, MetalRenderPipeline.BlendFactor.ONE,
            MetalRenderPipeline.BlendFactor.ONE_MINUS_SOURCE_ALPHA, MetalRenderPipeline.BlendOperation.ADD) : null;
        return device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source, "sky_vertex", source, fragment,
            List.of(new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA16_FLOAT, MetalRenderPipeline.WRITE_ALL, blending)),
            null, MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
            MetalRenderPipeline.RasterState.DEFAULT));
    }

    public boolean replacesClouds(@Nullable MetalLinearWorldSession session) {
        return session != null && session == this.renderedSession && !session.isClosed() && !session.isPoisoned();
    }

    public long renderedFrames() { return this.renderedFrames; }
    public int cloudWidth() { return this.cloudTarget == null ? 0 : this.cloudTarget.descriptor().width(); }

    /** Captured after view bob/hurt/nausea, before LevelRenderer builds its framegraph. */
    public void rasterProjection(@Nullable Matrix4fc projection) {
        this.rasterProjection = projection == null ? null : new Matrix4f(projection);
    }

    public @Nullable Matrix4fc rasterProjection() { return this.rasterProjection; }

    public void render(MetalGpuDevice gpu, SkyFrameInputs inputs, Runnable stars) {
        var session = gpu.linearWorldSession();
        if (session == null || session.isClosed() || session.isPoisoned()) {
            throw new IllegalStateException("Standard sky requires an active linear Metal world");
        }
        MetalTexture scene = session.hdrColor().attachment();
        if (BASELINE_PROBE && Boolean.getBoolean("metalcraft.baselineSkipSky")) {
            this.renderedSession = session;
            return;
        }
        // A fresh immutable buffer is pinned by the command buffer; no in-flight uniform overwrite.
        try (MetalBuffer frame = this.device.createBuffer(SkyFrameInputs.UNIFORM_BYTES, MetalBuffer.StorageMode.SHARED)) {
            try (var mapping = frame.map()) { inputs.write(mapping.bytes()); }
            gpu.encodeNativePass(targetPass(scene, MetalRenderPass.LoadAction.LOAD), "MetalCraft sky: atmosphere",
                pass -> this.encodeAtmosphere(pass, frame));
            stars.run();
            gpu.encodeNativePass(targetPass(scene, MetalRenderPass.LoadAction.LOAD), "MetalCraft sky: sun and moon",
                pass -> this.encodeCelestials(pass, frame));
            if (inputs.hasClouds()) {
                this.resizeClouds(scene.descriptor().width(), scene.descriptor().height());
                gpu.encodeNativePass(targetPass(this.cloudTarget, MetalRenderPass.LoadAction.DONT_CARE), "MetalCraft sky: clouds",
                    pass -> this.encodeClouds(pass, frame));
                gpu.encodeNativePass(targetPass(scene, MetalRenderPass.LoadAction.LOAD), "MetalCraft sky: composite",
                    pass -> this.encodeComposite(pass, this.cloudView));
            }
        }
        this.renderedSession = session;
        this.renderedFrames++;
    }

    public void encodeAtmosphere(MetalRenderPass pass, MetalBuffer frame) {
        pass.setPipeline(this.atmosphere);
        pass.setUniformBuffer(0, frame, 0, MetalRenderPass.STAGE_FRAGMENT);
        pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
    }

    public void encodeClouds(MetalRenderPass pass, MetalBuffer frame) {
        pass.setPipeline(this.clouds);
        pass.setUniformBuffer(0, frame, 0, MetalRenderPass.STAGE_FRAGMENT);
        pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
    }

    public void encodeCelestials(MetalRenderPass pass, MetalBuffer frame) {
        pass.setPipeline(this.celestials);
        pass.setUniformBuffer(0, frame, 0, MetalRenderPass.STAGE_FRAGMENT);
        pass.setTexture(0, this.moonSurface.view(), MetalRenderPass.STAGE_FRAGMENT);
        pass.setSampler(0, this.sampler, MetalRenderPass.STAGE_FRAGMENT);
        pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
    }

    public void encodeComposite(MetalRenderPass pass, MetalTextureView layer) {
        pass.setPipeline(this.composite);
        pass.setTexture(0, layer, MetalRenderPass.STAGE_FRAGMENT);
        pass.setSampler(0, this.sampler, MetalRenderPass.STAGE_FRAGMENT);
        pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
    }

    public static MetalRenderPass.Descriptor targetPass(MetalTexture target, MetalRenderPass.LoadAction load) {
        return new MetalRenderPass.Descriptor(List.of(new MetalRenderPass.ColorAttachment(target, load,
            MetalRenderPass.StoreAction.STORE, 0, 0, 0, 0)), null);
    }

    private void resizeClouds(int width, int height) {
        width = Math.max(1, (width + 1) / 2);
        height = Math.max(1, (height + 1) / 2);
        if (this.cloudTarget != null && this.cloudTarget.descriptor().width() == width
            && this.cloudTarget.descriptor().height() == height) return;
        this.closeTarget();
        this.cloudTarget = this.device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, width, height, 1));
        this.cloudView = this.cloudTarget.createView();
    }

    private void closeTarget() {
        if (this.cloudView != null) this.cloudView.close();
        if (this.cloudTarget != null) this.cloudTarget.close();
        this.cloudView = null;
        this.cloudTarget = null;
    }

    @Override public void close() {
        this.renderedSession = null;
        this.closeTarget();
        this.sampler.close();
        this.moonSurface.close();
        this.composite.close();
        this.clouds.close();
        this.celestials.close();
        this.atmosphere.close();
    }
}
