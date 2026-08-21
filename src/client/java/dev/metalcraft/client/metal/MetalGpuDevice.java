package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.DeviceType;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.HintsAndWorkarounds;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

/** Deep Blaze3D device adapter owning all direct-Metal implementation details. */
final class MetalGpuDevice implements GpuDeviceBackend {
	private static final Logger LOGGER = LogUtils.getLogger();
	private final ShaderSource defaultShaderSource;
	private final MetalDevice metal;
	private final MetalCommandQueue commandQueue;
	private final MetalCommandEncoder commandEncoder;
	private final DeviceInfo deviceInfo;
	private final Map<RenderPipeline, MetalCompiledRenderPipeline> pipelineCache = new IdentityHashMap<>();
	private final Map<ShaderKey, String> shaderSourceCache = new HashMap<>();
	private boolean closed;

	MetalGpuDevice(final MetalDevice metal, final ShaderSource defaultShaderSource) {
		this.metal = metal;
		this.defaultShaderSource = defaultShaderSource;
		this.commandQueue = metal.createCommandQueue();
		this.deviceInfo = new DeviceInfo(
			metal.name(),
			"Apple",
			System.getProperty("os.name", "macOS") + " " + System.getProperty("os.version", ""),
			true,
			"Metal",
			1.0F,
			new DeviceLimits(16, 256, 16384, Math.max(1L, metal.recommendedWorkingSetBytes()), Integer.MAX_VALUE, 1),
			new DeviceFeatures(true, true, true, true, true, true, true),
			Set.of("Metal"),
			new HintsAndWorkarounds(false, false),
			DeviceType.INTEGRATED
		);
		this.commandEncoder = new MetalCommandEncoder(this, this.commandQueue);
	}

	MetalDevice metal() {
		return this.metal;
	}

	MetalCompiledRenderPipeline getOrCompilePipeline(final RenderPipeline pipeline) {
		return this.pipelineCache.computeIfAbsent(pipeline, ignored -> this.compilePipeline(pipeline, this.defaultShaderSource));
	}

	@Override
	public GpuSurfaceBackend createSurface(final long windowHandle) {
		this.requireOpen();
		return new MetalGpuSurface(this, windowHandle);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		this.requireOpen();
		return this.commandEncoder;
	}

	@Override
	public GpuSampler createSampler(
		final AddressMode addressModeU,
		final AddressMode addressModeV,
		final FilterMode minFilter,
		final FilterMode magFilter,
		final int maxAnisotropy,
		final OptionalDouble maxLod
	) {
		MetalSampler.Descriptor descriptor = new MetalSampler.Descriptor(
			filter(minFilter), filter(magFilter), address(addressModeU), address(addressModeV), maxAnisotropy, maxLod.orElse(Double.POSITIVE_INFINITY)
		);
		return new MetalGpuSampler(this.metal.createSampler(descriptor), addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(
		final @Nullable Supplier<String> label,
		final @GpuTexture.Usage int usage,
		final GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		return this.createTexture(label == null ? null : label.get(), usage, format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTexture createTexture(
		final @Nullable String label,
		final @GpuTexture.Usage int usage,
		final GpuFormat format,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		boolean cubemap = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
		if ((!cubemap && depthOrLayers != 1) || (cubemap && depthOrLayers != 6)) {
			throw new UnsupportedOperationException("Direct Metal texture arrays and 3D textures are not implemented");
		}
		MetalTexture texture = this.metal.createTexture(
			Blaze3DMetalMappings.textureDescriptor(format, usage, width, height, depthOrLayers, mipLevels)
		);
		return new MetalGpuTexture(usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels, texture);
	}

	@Override
	public GpuTextureView createTextureView(final GpuTexture texture) {
		return this.createTextureView(texture, 0, texture.getMipLevels());
	}

	@Override
	public GpuTextureView createTextureView(final GpuTexture texture, final int baseMipLevel, final int mipLevels) {
		MetalGpuTexture metalTexture = requireTexture(texture);
		return new MetalGpuTextureView(metalTexture, baseMipLevel, mipLevels, metalTexture.metal().createView(baseMipLevel, mipLevels));
	}

	@Override
	public MetalGpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final long size) {
		MetalBuffer.StorageMode storageMode = (usage & (GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE)) != 0
			? MetalBuffer.StorageMode.SHARED
			: MetalBuffer.StorageMode.PRIVATE;
		long allocationSize = (usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0
			? Math.addExact(size, 255L)
			: size;
		return new MetalGpuBuffer(usage, size, this.metal.createBuffer(allocationSize, storageMode));
	}

	@Override
	public GpuBuffer createBuffer(final @Nullable Supplier<String> label, final @GpuBuffer.Usage int usage, final ByteBuffer data) {
		MetalGpuBuffer buffer = this.createBuffer(label, usage | GpuBuffer.USAGE_COPY_DST, data.remaining());
		this.commandEncoder.writeToBuffer(buffer.slice(), data);
		return buffer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of();
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public CompiledRenderPipeline precompilePipeline(final RenderPipeline pipeline, final @Nullable ShaderSource shaderSource) {
		ShaderSource selected = shaderSource == null ? this.defaultShaderSource : shaderSource;
		return this.pipelineCache.computeIfAbsent(pipeline, ignored -> this.compilePipeline(pipeline, selected));
	}

	@Override
	public void clearPipelineCache() {
		this.commandEncoder.finishPendingWork();
		this.pipelineCache.values().forEach(MetalCompiledRenderPipeline::close);
		this.pipelineCache.clear();
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			this.commandEncoder.close();
			this.pipelineCache.values().forEach(MetalCompiledRenderPipeline::close);
			this.pipelineCache.clear();
			this.commandQueue.close();
			this.metal.close();
		}
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(final int size) {
		return this.metal.createTimestampQueryPool(size);
	}

	@Override
	public long getTimestampNow() {
		try (MetalTimestampQueryPool query = this.metal.createTimestampQueryPool(1);
			 MetalCommandBuffer commands = this.commandQueue.createCommandBuffer()) {
			commands.writeTimestamp(query, 0);
			commands.commitAndWait();
			return query.getValue(0).orElseThrow();
		}
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.deviceInfo;
	}

	private MetalCompiledRenderPipeline compilePipeline(final RenderPipeline pipeline, final ShaderSource shaderSource) {
		String vertex = this.resolveShader(pipeline.getVertexShader(), ShaderType.VERTEX, pipeline.getShaderDefines(), shaderSource);
		String fragment = this.resolveShader(pipeline.getFragmentShader(), ShaderType.FRAGMENT, pipeline.getShaderDefines(), shaderSource);
		if (vertex == null || fragment == null) {
			LOGGER.error("Couldn't find Metal shader sources for pipeline {}", pipeline.getLocation());
			return new MetalCompiledRenderPipeline(pipeline, null, null);
		}

		try {
			String vertexWithDefines = GlslPreprocessor.injectDefines(vertex, pipeline.getShaderDefines());
			String vertexWithBindings = Blaze3DMetalMappings.shaderWithResourceBindings(vertexWithDefines, pipeline);
			String fragmentWithBindings = Blaze3DMetalMappings.shaderWithResourceBindings(
				GlslPreprocessor.injectDefines(fragment, pipeline.getShaderDefines()), pipeline
			);
			MetalShaderTranslator.PipelineTranslation shaders = MetalShaderTranslator.translatePipeline(
				Blaze3DMetalMappings.vertexShaderWithLocations(vertexWithBindings, pipeline.getVertexFormatBindings()),
				pipeline.getVertexShader().toDebugFileName(),
				fragmentWithBindings,
				pipeline.getFragmentShader().toDebugFileName()
			);
			MetalRenderPipeline.Descriptor descriptor = Blaze3DMetalMappings.pipelineDescriptor(pipeline, shaders);
			MetalRenderPipeline withoutDepth = this.metal.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				descriptor.vertexSource(), descriptor.vertexFunction(), descriptor.fragmentSource(), descriptor.fragmentFunction(),
				descriptor.colorTargets(), null, descriptor.vertexDescriptor(), MetalRenderPipeline.DepthState.DISABLED, descriptor.rasterState()
			));
			MetalRenderPipeline.Descriptor depthDescriptor = pipeline.wantsDepthTexture() ? descriptor : new MetalRenderPipeline.Descriptor(
				descriptor.vertexSource(), descriptor.vertexFunction(), descriptor.fragmentSource(), descriptor.fragmentFunction(),
				descriptor.colorTargets(), MetalTexture.Format.DEPTH32_FLOAT, descriptor.vertexDescriptor(), descriptor.depthState(), descriptor.rasterState()
			);
			MetalRenderPipeline withDepth = this.metal.createRenderPipeline(depthDescriptor);
			return new MetalCompiledRenderPipeline(pipeline, withDepth, withoutDepth);
		} catch (RuntimeException error) {
			LOGGER.error("Couldn't compile direct Metal pipeline {}", pipeline.getLocation(), error);
			return new MetalCompiledRenderPipeline(pipeline, null, null);
		}
	}

	private @Nullable String resolveShader(
		final Identifier id,
		final ShaderType type,
		final ShaderDefines defines,
		final ShaderSource shaderSource
	) {
		ShaderKey key = new ShaderKey(id, type, defines);
		String source = shaderSource.get(id, type);
		if (source != null) {
			this.shaderSourceCache.put(key, source);
			return source;
		}
		return this.shaderSourceCache.get(key);
	}

	private void requireOpen() {
		if (this.closed) {
			throw new IllegalStateException("Metal GPU device is closed");
		}
	}

	private static MetalGpuTexture requireTexture(final GpuTexture texture) {
		if (texture instanceof MetalGpuTexture metalTexture) {
			return metalTexture;
		}
		throw new IllegalArgumentException("Texture does not belong to the direct Metal backend");
	}

	private static MetalSampler.Filter filter(final FilterMode filter) {
		return switch (filter) {
			case NEAREST -> MetalSampler.Filter.NEAREST;
			case LINEAR -> MetalSampler.Filter.LINEAR;
		};
	}

	private static MetalSampler.AddressMode address(final AddressMode address) {
		return switch (address) {
			case REPEAT -> MetalSampler.AddressMode.REPEAT;
			case CLAMP_TO_EDGE -> MetalSampler.AddressMode.CLAMP_TO_EDGE;
		};
	}

	private record ShaderKey(Identifier id, ShaderType type, ShaderDefines defines) {
	}
}
