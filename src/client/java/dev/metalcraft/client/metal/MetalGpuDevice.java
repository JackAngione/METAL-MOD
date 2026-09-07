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
import dev.metalcraft.client.MetalCraftPlatform;
import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
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
public final class MetalGpuDevice implements GpuDeviceBackend {
	private static final Logger LOGGER = LogUtils.getLogger();
	private final ShaderSource defaultShaderSource;
	private final MetalDevice metal;
	private final MetalCommandQueue commandQueue;
	private final MetalCommandEncoder commandEncoder;
	private final DeviceInfo deviceInfo;
	private final Map<RenderPipeline, MetalCompiledRenderPipeline> pipelineCache = new IdentityHashMap<>();
	private final Map<RenderPipeline, MetalCompiledRenderPipeline> linearPipelineCache = new IdentityHashMap<>();
	private final Map<RenderPipeline, Map<LinearWorldPostShaders.Semantic, MetalCompiledRenderPipeline>>
		linearPostPipelineCache = new IdentityHashMap<>();
	private final Map<RenderPipeline, NativeProgram> nativePipelines = new IdentityHashMap<>();
	private final Map<ShaderKey, String> shaderSourceCache = new HashMap<>();
	// Triangle-fan indices are a pure function of vertex count, and the pattern for a large fan
	// contains the pattern for every smaller one as a prefix. One buffer filled once therefore
	// serves every fan draw, replacing a create-map-fill-destroy cycle that ran per draw call.
	private MetalBuffer fanShortIndices;
	private int fanShortCapacity;
	private MetalBuffer fanIntIndices;
	private int fanIntCapacity;
	// A superseded buffer may still be referenced by an in-flight command buffer, so growth retires
	// the old one rather than closing it. Doubling means this happens a handful of times at most.
	private final List<MetalBuffer> retiredFanIndices = new ArrayList<>();
	private boolean closed;
	/** Built on first use; most sessions that never recycle an item-atlas slot never compile it. */
	private MetalRegionClear regionClear;
	private MetalWorldGrade worldGrade;
	private MetalWorldTargets linearWorldTargets;
	private final @Nullable ShaderPackRuntime shaderPackRuntime;
	private long shaderGeneration = 1L;
	private long nativeGeneration = 1L;
	private boolean forceLegacyFrame;
	private @Nullable MetalLinearWorldSession linearWorldSession;
	private @Nullable MetalOpaqueSnapshotOwner opaqueSnapshots;
	private boolean lastWorldHadOpaqueWaterInputs;
	private boolean lastWorldFabulous;
	private int lastWorldWaterDraws;
	private int lastWorldOpaqueWaterWidth;
	private int lastWorldOpaqueWaterHeight;
	public int lastWorldWaterDraws() { return this.lastWorldWaterDraws; }
	public int lastWorldOpaqueWaterWidth() { return this.lastWorldOpaqueWaterWidth; }
	public int lastWorldOpaqueWaterHeight() { return this.lastWorldOpaqueWaterHeight; }
	public boolean lastWorldFabulous() { return this.lastWorldFabulous; }
	private @Nullable ShaderSource reloadShaderSource;
	private final Map<RenderPipeline, LinearWorldPostShaders.Semantic> linearPostContracts = new IdentityHashMap<>();

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
			new DeviceLimits(
				16, 256, 16384, Math.max(1L, metal.recommendedWorkingSetBytes()),
				Integer.MAX_VALUE, MetalRenderPass.MAX_COLOR_ATTACHMENTS
			),
			new DeviceFeatures(true, true, true, true, true, true, true),
			Set.of("Metal"),
			new HintsAndWorkarounds(false, false),
			DeviceType.INTEGRATED
		);
		this.commandEncoder = new MetalCommandEncoder(this, this.commandQueue);
		this.shaderPackRuntime = MetalCraftPlatform.isAppleSilicon()
			&& !Boolean.getBoolean("metalcraft.shaders.disable")
			? ShaderPackRuntime.createDefault(this)
			: null;
	}

	@Nullable ShaderPackRuntime shaderPackRuntime() {
		return this.shaderPackRuntime;
	}

	public MetalDevice metal() {
		return this.metal;
	}

	/** Encodes a scoped native pass on the world's queue, after any deferred Blaze3D pass. */
	public void encodeNativePass(final MetalRenderPass.Descriptor descriptor, final String label,
		final java.util.function.Consumer<MetalRenderPass> encode) {
		this.commandEncoder.encodeNativePass(descriptor, MetalPassCensus.kindFor(label), encode);
	}

	/** Called after the complete world graph, before hand depth is cleared. */
	public void gradeWorld(final GpuTextureView color, final GpuTextureView depth) {
		if (this.shaderPackRuntime == null || !this.shaderPackRuntime.isActive()) return;
		if (!(color instanceof MetalGpuTextureView scene) || !(depth instanceof MetalGpuTextureView worldDepth)) return;
		try {
			dev.metalcraft.client.shader.WorldGeometryAdapter.resolveOpaque();
			if (this.worldGrade == null) this.worldGrade = new MetalWorldGrade();
			this.worldGrade.encode(this, this.commandEncoder.commands(), this.shaderPackRuntime, scene, worldDepth);
		} catch (RuntimeException error) {
			this.shaderPackRuntime.markFailed("World grading failed: " + error.getMessage(), error);
			LOGGER.error("World grading failed; preserving the world scene", error);
		}
	}

	/** Allocate world resources only when a caller is ready to render every producer in linear space. */
	public MetalWorldTargets prepareLinearWorldTargets(final int width, final int height) {
		this.requireOpen();
		if (this.linearWorldSession != null) {
			MetalWorldTargets current = this.linearWorldTargets;
			if (current != null && current.color().getWidth(0) == width && current.color().getHeight(0) == height) {
				return current;
			}
			throw new IllegalStateException("Cannot resize linear world targets during an active session");
		}
		if (this.linearWorldTargets == null) this.linearWorldTargets = new MetalWorldTargets(this);
		// End a deferred pass before resize may retire the attachments it references.
		this.commandEncoder.commands();
		this.linearWorldTargets.resize(width, height);
		return this.linearWorldTargets;
	}

	/**
	 * Begins a fail-closed HDR world frame after preflight. {@code null} means render this frame
	 * through the existing encoded path, including the one forced-legacy frame after a poisoned HDR
	 * session. {@code MetalLinearWorldActivation} wraps live {@code LevelRenderer.render} with this API.
	 */
	public @Nullable MetalLinearWorldSession beginLinearWorld(
		final GpuTextureView mainColor,
		final GpuTextureView mainDepth,
		final boolean fabulous,
		final Collection<RenderPipeline> knownPipelines,
		final @Nullable ShaderSource shaderSource
	) {
		return this.beginLinearWorld(mainColor, mainDepth, fabulous, knownPipelines, List.of(), shaderSource);
	}

	public @Nullable MetalLinearWorldSession beginLinearWorld(
		final GpuTextureView mainColor,
		final GpuTextureView mainDepth,
		final boolean fabulous,
		final Collection<RenderPipeline> knownPipelines,
		final Collection<MetalLinearWorldSession.PostContract> knownPost,
		final @Nullable ShaderSource shaderSource
	) {
		this.requireOpen();
		if (this.linearWorldSession != null) {
			throw new IllegalStateException("A linear world session is already active");
		}
		this.lastWorldHadOpaqueWaterInputs = false;
		this.lastWorldFabulous = false;
		this.lastWorldWaterDraws = 0;
		this.lastWorldOpaqueWaterWidth = 0;
		this.lastWorldOpaqueWaterHeight = 0;
		if (knownPipelines == null || knownPost == null) {
			throw new NullPointerException("Linear world preflight collections are required");
		}
		if (this.forceLegacyFrame) {
			this.forceLegacyFrame = false;
			return null;
		}
		if (this.shaderPackRuntime == null || !this.shaderPackRuntime.isActive()
			|| !ShaderPackRuntime.BUILTIN_ID.equals(this.shaderPackRuntime.selectedPackId())) {
			return null;
		}
		if (!(mainColor instanceof MetalGpuTextureView colorView)
			|| !(mainDepth instanceof MetalGpuTextureView depthView)
			|| colorView.attachment().device() != this.metal
			|| depthView.attachment().device() != this.metal) {
			throw new IllegalArgumentException("Linear world attachments must belong to this Metal device");
		}
		int width = colorView.getWidth(0);
		int height = colorView.getHeight(0);
		if (width != depthView.getWidth(0) || height != depthView.getHeight(0)) {
			throw new IllegalArgumentException("Linear world color and depth extents must match");
		}
		ShaderSource selected = this.linearShaderSource(shaderSource);
		try {
			for (RenderPipeline pipeline : knownPipelines) {
				this.precompileLinearWorldPipeline(pipeline, selected);
			}
			for (MetalLinearWorldSession.PostContract contract : knownPost) {
				this.linearPostContracts.put(contract.pipeline(), contract.semantic());
				this.precompileLinearWorldPostPipeline(contract.pipeline(), selected, contract.semantic());
			}
		} catch (RuntimeException error) {
			LOGGER.error("Linear world preflight failed; keeping the encoded world path", error);
			return null;
		}
		MetalWorldTargets targets = this.prepareLinearWorldTargets(width, height);
		MetalLinearWorldSession.Token token = new MetalLinearWorldSession.Token(
			colorView, colorView.texture(), depthView, depthView.texture(),
			targets.color(), targets.depth(), width, height,
			this.shaderGeneration, this.nativeGeneration, fabulous, selected);
		MetalLinearWorldSession session = new MetalLinearWorldSession(this, token);
		for (RenderPipeline pipeline : knownPipelines) session.approve(pipeline);
		for (MetalLinearWorldSession.PostContract contract : knownPost) session.approve(contract.pipeline());
		if (this.opaqueSnapshots != null) this.opaqueSnapshots.invalidate();
		this.linearWorldSession = session;
		return session;
	}

	public @Nullable MetalLinearWorldSession linearWorldSession() {
		return this.linearWorldSession;
	}

	/** Capture once after opaque resolve and before forward features change world attachments. */
	public void captureOpaqueWaterInputs() {
		MetalLinearWorldSession session = this.linearWorldSession;
		if (session == null || session.isClosed() || session.isPoisoned()) return;
		if (this.opaqueSnapshots == null) this.opaqueSnapshots = new MetalOpaqueSnapshotOwner(this);
		if (this.opaqueSnapshots.current().isPresent()) return;
		this.opaqueSnapshots.capture(this.commandEncoder, session.hdrColor(), session.hdrDepth());
	}

	/** Empty selects baseline water; snapshots never escape their healthy HDR world session. */
	public java.util.Optional<MetalOpaqueSnapshotOwner.Snapshot> opaqueWaterInputs() {
		MetalLinearWorldSession session = this.linearWorldSession;
		if (session == null || session.isClosed() || session.isPoisoned() || this.opaqueSnapshots == null) {
			return java.util.Optional.empty();
		}
		return this.opaqueSnapshots.current();
	}

	void invalidateOpaqueWaterInputs() {
		if (this.opaqueSnapshots != null) this.opaqueSnapshots.invalidate();
	}

	/** Diagnostic for the completed world frame; does not expose retired snapshot handles. */
	public boolean lastWorldHadOpaqueWaterInputs() { return this.lastWorldHadOpaqueWaterInputs; }

	void endLinearWorld(final MetalLinearWorldSession session) {
		if (this.linearWorldSession != session) return;
		this.lastWorldWaterDraws = session.isPoisoned() ? 0 : session.waterDraws();
		this.lastWorldFabulous = !session.isPoisoned() && session.token().fabulous();
		java.util.Optional<MetalOpaqueSnapshotOwner.Snapshot> snapshot = session.isPoisoned()
			|| this.opaqueSnapshots == null ? java.util.Optional.empty() : this.opaqueSnapshots.current();
		this.lastWorldHadOpaqueWaterInputs = snapshot.isPresent();
		this.lastWorldOpaqueWaterWidth = snapshot.map(MetalOpaqueSnapshotOwner.Snapshot::width).orElse(0);
		this.lastWorldOpaqueWaterHeight = snapshot.map(MetalOpaqueSnapshotOwner.Snapshot::height).orElse(0);
		if (session.isPoisoned()) this.forceLegacyFrame = true;
		this.linearWorldSession = null;
		if (this.opaqueSnapshots != null) this.opaqueSnapshots.invalidate();
	}

	/**
	 * HDR-owned passes may bind only a verified linear pipeline. Never select the legacy cache.
	 * Unseen supported pipelines compile here; failure poisons the session and suppresses the draw.
	 */
	@Nullable MetalCompiledRenderPipeline linearPipelineFor(final MetalLinearWorldSession session,
		final RenderPipeline pipeline) {
		if (session == null || pipeline == null) throw new NullPointerException("Linear pipeline lookup needs a session and pipeline");
		if (session.isClosed()) throw new IllegalStateException("Linear world session is closed");
		if (session.token().shaderGeneration() != this.shaderGeneration
			|| session.token().nativeGeneration() != this.nativeGeneration) {
			session.poison();
			this.forceLegacyFrame = true;
			return null;
		}
		try {
			LinearWorldPostShaders.Semantic post = this.linearPostContracts.get(pipeline);
			MetalCompiledRenderPipeline compiled = post != null
				? (MetalCompiledRenderPipeline)this.precompileLinearWorldPostPipeline(
					pipeline, session.token().shaderSource(), post)
				: (MetalCompiledRenderPipeline)this.precompileLinearWorldPipeline(
					pipeline, session.token().shaderSource());
			session.approve(pipeline);
			return compiled;
		} catch (RuntimeException error) {
			session.poison();
			this.forceLegacyFrame = true;
			LOGGER.error("Unsupported pipeline in an HDR-owned pass; suppressing draws: {}",
				pipeline.getLocation(), error);
			return null;
		}
	}

	public void setReloadShaderSource(final @Nullable ShaderSource source) {
		this.requireOpen();
		if (this.reloadShaderSource == source) return;
		this.reloadShaderSource = source;
		this.shaderGeneration++;
		this.poisonLinearWorldSession();
	}

	public @Nullable ShaderSource reloadShaderSource() {
		return this.reloadShaderSource;
	}

	long shaderGeneration() {
		return this.shaderGeneration;
	}

	public void registerLinearWorldPostContract(final RenderPipeline pipeline,
		final LinearWorldPostShaders.Semantic semantic) {
		this.requireOpen();
		if (pipeline == null || semantic == null) {
			throw new NullPointerException("A linear post contract needs a pipeline and semantic");
		}
		this.linearPostContracts.put(pipeline, semantic);
	}

	private ShaderSource linearShaderSource(final @Nullable ShaderSource override) {
		if (override != null) return override;
		return this.reloadShaderSource != null ? this.reloadShaderSource : this.defaultShaderSource;
	}

	private void poisonLinearWorldSession() {
		if (this.linearWorldSession != null) {
			this.linearWorldSession.poison();
			this.forceLegacyFrame = true;
		}
	}

	/** Explicit linear producer handoff. The caller must have finished the whole world graph. */
	public void gradeLinearWorld(final GpuTextureView output) {
		this.requireOpen();
		if (this.shaderPackRuntime == null || !this.shaderPackRuntime.isActive() || this.linearWorldTargets == null) {
			throw new IllegalStateException("Linear world grading requires active pack and prepared world targets");
		}
		if (!(output instanceof MetalGpuTextureView destination) || destination.attachment().device() != this.metal) {
			throw new IllegalArgumentException("World output must belong to this Metal device");
		}
		dev.metalcraft.client.shader.WorldGeometryAdapter.resolveOpaque();
		if (this.worldGrade == null) this.worldGrade = new MetalWorldGrade();
		this.worldGrade.encode(this, this.commandEncoder.commands(), this.shaderPackRuntime,
			this.linearWorldTargets.color(), this.linearWorldTargets.depth(), destination,
			dev.metalcraft.client.shader.FrameBindings.ColorEncoding.LINEAR_SRGB);
	}

	/** Borrowed native resources for world modules; ownership stays with Blaze3D. */
	public MetalBuffer nativeBuffer(final com.mojang.blaze3d.buffers.GpuBuffer buffer) {
		if (!(buffer instanceof MetalGpuBuffer metal) || metal.metal().device() != this.metal) {
			throw new IllegalArgumentException("Buffer does not belong to this Metal device");
		}
		return metal.metal();
	}

	public MetalTextureView nativeTextureView(final com.mojang.blaze3d.textures.GpuTextureView view) {
		if (!(view instanceof MetalGpuTextureView metal) || metal.attachment().device() != this.metal) {
			throw new IllegalArgumentException("Texture view does not belong to this Metal device");
		}
		return metal.metal();
	}

	public MetalSampler nativeSampler(final com.mojang.blaze3d.textures.GpuSampler sampler) {
		if (!(sampler instanceof MetalGpuSampler metal) || metal.metal().device() != this.metal) {
			throw new IllegalArgumentException("Sampler does not belong to this Metal device");
		}
		return metal.metal();
	}

	public void setDeferredResolve(final @Nullable DeferredResolveHook hook) {
		this.commandEncoder.setDeferredResolve(hook);
	}

	private @Nullable WorldUniformCapture worldUniformCapture;

	public void setWorldUniformCapture(final @Nullable WorldUniformCapture capture) {
		this.worldUniformCapture = capture;
	}

	@Nullable WorldUniformCapture worldUniformCapture() {
		return this.worldUniformCapture;
	}

	/**
	 * Declares that a Blaze3D pipeline's programs are pack MSL rather than Minecraft's GLSL.
	 *
	 * <p>First-time {@code LINEAR_SRGB} admission during an open HDR session is expected: the
	 * geometry adapter builds stand-ins on first use. Replacement, forgetting, or a legacy
	 * program still changes {@code nativeGeneration} and poisons the open session.
	 */
	public void registerNativePipeline(final RenderPipeline pipeline, final NativeProgram program) {
		if (pipeline == null || program == null) {
			throw new NullPointerException("A native Metal pipeline needs both a Blaze3D pipeline and a program");
		}
		NativeProgram previous = this.nativePipelines.get(pipeline);
		if (program.equals(previous)) {
			return;
		}
		this.retirePipeline(pipeline);
		this.nativePipelines.put(pipeline, program);
		boolean admitLinearStandIn = previous == null
			&& program.colorEncoding() == FrameBindings.ColorEncoding.LINEAR_SRGB
			&& this.linearWorldSession != null
			&& !this.linearWorldSession.isClosed();
		if (!admitLinearStandIn) {
			this.nativeGeneration++;
			this.poisonLinearWorldSession();
		}
	}

	public void forgetNativePipeline(final RenderPipeline pipeline) {
		this.nativePipelines.remove(pipeline);
		this.retirePipeline(pipeline);
		this.nativeGeneration++;
		this.poisonLinearWorldSession();
	}

	void forgetNativePipelines() {
		for (RenderPipeline pipeline : this.nativePipelines.keySet()) {
			this.retirePipeline(pipeline);
		}
		this.nativePipelines.clear();
		this.nativeGeneration++;
		this.poisonLinearWorldSession();
	}

	private void retirePipeline(final RenderPipeline pipeline) {
		MetalCompiledRenderPipeline legacy = this.pipelineCache.remove(pipeline);
		if (legacy != null) legacy.close();
		MetalCompiledRenderPipeline linear = this.linearPipelineCache.remove(pipeline);
		if (linear != null) linear.close();
		Map<LinearWorldPostShaders.Semantic, MetalCompiledRenderPipeline> post = this.linearPostPipelineCache.remove(pipeline);
		if (post != null) post.values().forEach(MetalCompiledRenderPipeline::close);
	}

	public void resolveDeferredShaderPass() {
		this.commandEncoder.flushDeferredResolve();
	}

	public MetalGpuTextureView wrapAttachment(final MetalTexture texture, final String label) {
		int usage = texture.isMemoryless()
			? GpuTexture.USAGE_RENDER_ATTACHMENT
			: GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
		GpuFormat format = Blaze3DMetalMappings.gpuFormat(texture.descriptor().format());
		MetalGpuTexture gpu = new MetalGpuTexture(
			usage, label, format,
			texture.descriptor().width(), texture.descriptor().height(),
			texture.descriptor().depthOrLayers(), texture.descriptor().mipLevels(),
			texture
		);
		MetalTextureView view = texture.isMemoryless() ? null : texture.createView();
		return new MetalGpuTextureView(gpu, 0, texture.descriptor().mipLevels(), view);
	}

	public record NativeProgram(String source, String vertexFunction, String fragmentFunction,
		FrameBindings.ColorEncoding colorEncoding) {
		public NativeProgram(final String source, final String vertexFunction, final String fragmentFunction) {
			this(source, vertexFunction, fragmentFunction, FrameBindings.ColorEncoding.LEGACY_ENCODED);
		}

		public NativeProgram {
			if (source == null || vertexFunction == null || fragmentFunction == null || colorEncoding == null) {
				throw new NullPointerException("A native Metal program needs a source, both entry points, and a color encoding");
			}
		}
	}

	MetalRegionClear regionClear() {
		if (this.regionClear == null) this.regionClear = new MetalRegionClear(this.metal);
		return this.regionClear;
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
		MetalTexture metal = metalTexture.metal();
		MetalTextureView view = metal.isMemoryless() ? null : metal.createView(baseMipLevel, mipLevels);
		return new MetalGpuTextureView(metalTexture, baseMipLevel, mipLevels, view);
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

	/**
	 * Prepares verified linear world semantics in a cache separate from legacy draw selection.
	 * Callers must gate the entire world before using these variants; HDR storage alone is insufficient.
	 * Unsupported or resource-replaced shader sources throw rather than silently selecting legacy math.
	 */
	public CompiledRenderPipeline precompileLinearWorldPipeline(final RenderPipeline pipeline, final @Nullable ShaderSource shaderSource) {
		this.requireOpen();
		NativeProgram nativeProgram = this.nativePipelines.get(pipeline);
		if (nativeProgram != null && nativeProgram.colorEncoding() != FrameBindings.ColorEncoding.LINEAR_SRGB) {
			throw new IllegalArgumentException("Native pipeline does not declare linear-sRGB output: " + pipeline.getLocation());
		}
		ShaderSource selected = shaderSource == null ? this.defaultShaderSource : shaderSource;
		MetalCompiledRenderPipeline compiled = this.linearPipelineCache.computeIfAbsent(
			pipeline, ignored -> this.compilePipeline(pipeline, selected, true));
		if (!compiled.isValid()) {
			this.linearPipelineCache.remove(pipeline);
			compiled.close();
			throw new IllegalArgumentException("Could not compile linear world pipeline " + pipeline.getLocation());
		}
		return compiled;
	}

	/**
	 * Prepares one explicitly declared, source-verified linear post operation. Shader identifiers
	 * alone never select the semantic because the same source may participate in encoded post chains.
	 */
	public CompiledRenderPipeline precompileLinearWorldPostPipeline(final RenderPipeline pipeline,
		final @Nullable ShaderSource shaderSource, final LinearWorldPostShaders.Semantic semantic) {
		this.requireOpen();
		if (pipeline == null || semantic == null) {
			throw new NullPointerException("A linear post pipeline needs a pipeline and semantic");
		}
		if (this.nativePipelines.containsKey(pipeline)) {
			throw new IllegalArgumentException("Native pipelines require a separate explicit post contract: " + pipeline.getLocation());
		}
		ShaderSource selected = shaderSource == null ? this.defaultShaderSource : shaderSource;
		// Verify the current reload's sources even when this pipeline/semantic pair was compiled
		// previously. A changed ShaderSource must never inherit an older semantic cache entry.
		String vertex = selected.get(pipeline.getVertexShader(), ShaderType.VERTEX);
		String fragment = selected.get(pipeline.getFragmentShader(), ShaderType.FRAGMENT);
		if (vertex == null || fragment == null) {
			throw new IllegalArgumentException("Missing linear world post sources for " + pipeline.getLocation());
		}
		vertex = LinearWorldPostShaders.vertex(semantic, pipeline.getVertexShader(), vertex);
		fragment = LinearWorldPostShaders.fragment(semantic, pipeline.getFragmentShader(), fragment);
		Map<LinearWorldPostShaders.Semantic, MetalCompiledRenderPipeline> variants =
			this.linearPostPipelineCache.computeIfAbsent(pipeline,
				ignored -> new EnumMap<>(LinearWorldPostShaders.Semantic.class));
		MetalCompiledRenderPipeline cached = variants.get(semantic);
		if (cached != null) return cached;
		MetalCompiledRenderPipeline compiled = this.compilePostPipeline(pipeline, vertex, fragment);
		if (!compiled.isValid()) {
			compiled.close();
			throw new IllegalArgumentException("Could not compile linear world post pipeline " + pipeline.getLocation());
		}
		variants.put(semantic, compiled);
		return compiled;
	}

	@Override
	public void clearPipelineCache() {
		this.commandEncoder.finishPendingWork();
		if (this.linearWorldSession != null) {
			this.linearWorldSession.poison();
			this.linearWorldSession.close();
		}
		this.shaderGeneration++;
		if (this.opaqueSnapshots != null) {
			this.opaqueSnapshots.close();
			this.opaqueSnapshots = null;
		}
		this.linearPostContracts.clear();
		if (this.worldGrade != null) {
			this.worldGrade.close();
			this.worldGrade = null;
		}
		if (this.linearWorldTargets != null) {
			this.linearWorldTargets.close();
			this.linearWorldTargets = null;
		}
		this.pipelineCache.values().forEach(MetalCompiledRenderPipeline::close);
		this.pipelineCache.clear();
		this.linearPipelineCache.values().forEach(MetalCompiledRenderPipeline::close);
		this.linearPipelineCache.clear();
		this.linearPostPipelineCache.values().forEach(cache -> cache.values().forEach(MetalCompiledRenderPipeline::close));
		this.linearPostPipelineCache.clear();
		if (this.shaderPackRuntime != null) {
			this.shaderPackRuntime.reload();
			if (this.shaderPackRuntime.frameWidth() > 0 && this.shaderPackRuntime.frameHeight() > 0) {
				this.shaderPackRuntime.resize(this.shaderPackRuntime.frameWidth(), this.shaderPackRuntime.frameHeight());
			}
		}
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.closed = true;
			if (this.linearWorldSession != null) this.linearWorldSession.close();
			this.commandEncoder.close();
			if (this.shaderPackRuntime != null) {
				this.shaderPackRuntime.close();
			}
			if (this.opaqueSnapshots != null) this.opaqueSnapshots.close();
			if (this.worldGrade != null) this.worldGrade.close();
			if (this.linearWorldTargets != null) this.linearWorldTargets.close();
			this.pipelineCache.values().forEach(MetalCompiledRenderPipeline::close);
			this.pipelineCache.clear();
			this.linearPipelineCache.values().forEach(MetalCompiledRenderPipeline::close);
			this.linearPipelineCache.clear();
			this.linearPostPipelineCache.values().forEach(cache -> cache.values().forEach(MetalCompiledRenderPipeline::close));
			this.linearPostPipelineCache.clear();
			if (this.regionClear != null) {
				this.regionClear.close();
				this.regionClear = null;
			}
			this.retiredFanIndices.forEach(MetalBuffer::close);
			this.retiredFanIndices.clear();
			if (this.fanShortIndices != null) {
				this.fanShortIndices.close();
				this.fanShortIndices = null;
			}
			if (this.fanIntIndices != null) {
				this.fanIntIndices.close();
				this.fanIntIndices = null;
			}
			this.commandQueue.close();
			this.metal.close();
		}
	}

	/** @return a shared index buffer whose first {@code (vertexCount - 2) * 3} indices fan {@code vertexCount} vertices */
	synchronized MetalBuffer fanIndices(final int vertexCount, final boolean useShorts) {
		int capacity = useShorts ? this.fanShortCapacity : this.fanIntCapacity;
		MetalBuffer current = useShorts ? this.fanShortIndices : this.fanIntIndices;
		if (current != null && capacity >= vertexCount) {
			return current;
		}

		int grown = Math.max(Math.max(vertexCount, 1024), Math.multiplyExact(capacity, 2));
		MetalBuffer replacement = this.createFanIndices(grown, useShorts);
		if (current != null) {
			this.retiredFanIndices.add(current);
		}
		if (useShorts) {
			this.fanShortIndices = replacement;
			this.fanShortCapacity = grown;
		} else {
			this.fanIntIndices = replacement;
			this.fanIntCapacity = grown;
		}
		return replacement;
	}

	private MetalBuffer createFanIndices(final int vertexCapacity, final boolean useShorts) {
		int indexCount = Math.multiplyExact(vertexCapacity - 2, 3);
		int bytes = Math.multiplyExact(indexCount, useShorts ? Short.BYTES : Integer.BYTES);
		MetalBuffer buffer = this.metal.createBuffer(bytes, MetalBuffer.StorageMode.SHARED);
		try (MetalBuffer.Mapping mapping = buffer.map()) {
			if (useShorts) {
				ShortBuffer output = mapping.bytes().order(ByteOrder.nativeOrder()).asShortBuffer();
				for (int vertex = 1; vertex < vertexCapacity - 1; vertex++) {
					output.put((short)0).put((short)vertex).put((short)(vertex + 1));
				}
			} else {
				IntBuffer output = mapping.bytes().order(ByteOrder.nativeOrder()).asIntBuffer();
				for (int vertex = 1; vertex < vertexCapacity - 1; vertex++) {
					output.put(0).put(vertex).put(vertex + 1);
				}
			}
		}
		return buffer;
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
		return this.compilePipeline(pipeline, shaderSource, false);
	}

	private MetalCompiledRenderPipeline compilePipeline(final RenderPipeline pipeline, final ShaderSource shaderSource, final boolean linear) {
		NativeProgram program = this.nativePipelines.get(pipeline);
		if (program != null) {
			if (linear && program.colorEncoding() != FrameBindings.ColorEncoding.LINEAR_SRGB) {
				throw new IllegalArgumentException("Native pipelines require an explicit linear-sRGB contract");
			}
			return this.compileNativePipeline(pipeline, program);
		}
		String vertex = this.resolveShader(pipeline.getVertexShader(), ShaderType.VERTEX, pipeline.getShaderDefines(), shaderSource);
		String fragment = this.resolveShader(pipeline.getFragmentShader(), ShaderType.FRAGMENT, pipeline.getShaderDefines(), shaderSource);
		if (vertex == null || fragment == null) {
			if (linear) throw new IllegalArgumentException("Missing linear world sources for " + pipeline.getLocation());
			LOGGER.error("Couldn't find Metal shader sources for pipeline {}", pipeline.getLocation());
			return new MetalCompiledRenderPipeline(pipeline, null, null, null);
		}

		if (linear) {
			LinearWorldShaders.verify(pipeline.getVertexShader(), ".vsh", vertex);
			fragment = LinearWorldShaders.fragment(pipeline.getFragmentShader(), fragment);
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
			return MetalCompiledRenderPipeline.compile(this.metal, pipeline, descriptor, shaders);
		} catch (RuntimeException error) {
			if (linear) throw new IllegalArgumentException("Could not compile linear world pipeline " + pipeline.getLocation(), error);
			LOGGER.error("Couldn't compile direct Metal pipeline {}", pipeline.getLocation(), error);
			return new MetalCompiledRenderPipeline(pipeline, null, null, null);
		}
	}

	private MetalCompiledRenderPipeline compileNativePipeline(final RenderPipeline pipeline, final NativeProgram program) {
		try {
			MetalRenderPipeline.Descriptor descriptor = Blaze3DMetalMappings.pipelineDescriptor(
				pipeline, program.source(), program.vertexFunction(), program.source(), program.fragmentFunction());
			return MetalCompiledRenderPipeline.compile(this.metal, pipeline, descriptor, null);
		} catch (RuntimeException error) {
			LOGGER.error("Couldn't compile shader-pack Metal pipeline {}", pipeline.getLocation(), error);
			return new MetalCompiledRenderPipeline(pipeline, null, null, null);
		}
	}

	private MetalCompiledRenderPipeline compilePostPipeline(final RenderPipeline pipeline,
		final String vertex, final String fragment) {
		try {
			String vertexWithBindings = Blaze3DMetalMappings.shaderWithResourceBindings(
				GlslPreprocessor.injectDefines(vertex, pipeline.getShaderDefines()), pipeline);
			String fragmentWithBindings = Blaze3DMetalMappings.shaderWithResourceBindings(
				GlslPreprocessor.injectDefines(fragment, pipeline.getShaderDefines()), pipeline);
			MetalShaderTranslator.PipelineTranslation shaders = MetalShaderTranslator.translatePipeline(
				Blaze3DMetalMappings.vertexShaderWithLocations(vertexWithBindings, pipeline.getVertexFormatBindings()),
				pipeline.getVertexShader().toDebugFileName(), fragmentWithBindings,
				pipeline.getFragmentShader().toDebugFileName());
			MetalRenderPipeline.Descriptor descriptor = Blaze3DMetalMappings.pipelineDescriptor(pipeline, shaders);
			return MetalCompiledRenderPipeline.compile(this.metal, pipeline, descriptor, shaders);
		} catch (RuntimeException error) {
			throw new IllegalArgumentException("Could not compile linear world post pipeline " + pipeline.getLocation(), error);
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
