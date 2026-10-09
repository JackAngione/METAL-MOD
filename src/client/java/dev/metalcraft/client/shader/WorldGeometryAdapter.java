package dev.metalcraft.client.shader;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.logging.LogUtils;
import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalCommandBuffer;
import dev.metalcraft.client.metal.MetalCommandQueue;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalGpuTextureView;
import dev.metalcraft.client.metal.MetalLinearWorldSession;
import dev.metalcraft.client.metal.MetalPassCensus;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalSampler;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import dev.metalcraft.client.shader.sky.SkyFrameInputs;
import dev.metalcraft.client.shader.world.WorldLightingModule;
import dev.metalcraft.client.shader.world.WorldLocalLighting;
import dev.metalcraft.client.shader.world.WorldShadowModule;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.slf4j.Logger;

/**
 * Routes Minecraft's world geometry into the active pack's G-buffer pass.
 *
 * <p>Two substitutions and nothing else: the render pass gains pack G-buffer channels after
 * Minecraft's colour attachment, and each world pipeline is replaced by a stand-in with the same
 * bind-group layouts. Mixins call {@link #beginWorldPass} and {@link #substitute}.
 */
public final class WorldGeometryAdapter implements AutoCloseable {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int UNIFORM_ALIGNMENT = 256;
	private static final int RESOLVE_CAMERA_BYTES = 176;
	private static final boolean BASELINE_PROBE = Boolean.getBoolean("metalcraft.baselineLightingBenchmark");
	private static volatile @Nullable WorldGeometryAdapter active;

	public enum Program {
		TERRAIN("minecraft:core/terrain", "Position", "Color", "UV0", "UV2"),
		BLOCK("minecraft:core/block", "Position", "Color", "UV0", "UV2"),
		ENTITY("minecraft:core/entity", "Position", "Color", "UV0", "UV1", "UV2", "Normal");

		private final String vertexShader;
		private final List<String> vertexElements;

		Program(final String vertexShader, final String... vertexElements) {
			this.vertexShader = vertexShader;
			this.vertexElements = List.of(vertexElements);
		}

		Set<String> implementedDefines() {
			return this == ENTITY
				? Set.of("ALPHA_CUTOUT", "EMISSIVE", "NO_OVERLAY", "NO_CARDINAL_LIGHTING",
					"PER_FACE_LIGHTING", "APPLY_TEXTURE_MATRIX")
				: Set.of("ALPHA_CUTOUT");
		}

		String entryPoint(final String passId, final String stage) {
			return passId + "_" + this.name().toLowerCase(Locale.ROOT) + "_" + stage;
		}
	}

	public enum Material {
		SOLID,
		FOLIAGE,
		WATER,
		ENTITY,
		EMISSIVE
	}

	record Channel(String target, MetalGpuTextureView view, Vector4fc clearColor) {
	}

	private record Substitution(RenderPipeline pipeline, Program program, Material material) {
	}

	private final MetalGpuDevice device;
	private final ShaderTargetAllocator allocator;
	private final String packId;
	private final String passId;
	private final String source;
	private final String targetDefines;
	private final List<String> channelIds;
	private final ShaderPack.Pass resolvePass;
	private final String resolveSource;
	private final Function<String, Object> optionValue;
	private final List<ShaderPack.Option> uniformOptions;
	private final int optionBytes;
	private final int resolveCameraOffset;
	private final int lightingFrameOffset;
	private final int resolveFrameBytes;
	private final int shadowMapSlot;
	private final int shadowFrameSlot;
	private final int resolveCameraSlot;
	private final int lightingFrameSlot;
	private final boolean distantShadows;
	private final @Nullable WorldLocalLighting localLighting;
	private Map<RenderPipeline, Optional<Substitution>> substitutions = new IdentityHashMap<>();
	private Map<RenderPipeline, Optional<RenderPipeline>> windSubstitutions = new IdentityHashMap<>();
	private Map<RenderPipeline, Optional<RenderPipeline>> waterSubstitutions = new IdentityHashMap<>();
	private Map<RenderPipeline, Optional<RenderPipeline>> waterWithoutSnapshots = new IdentityHashMap<>();
	private Map<RenderPipeline, RenderPipeline> linearColorPipelines = new IdentityHashMap<>();
	private record CachedPrograms(
		Map<RenderPipeline, Optional<Substitution>> substitutions,
		Map<RenderPipeline, Optional<RenderPipeline>> water,
		Map<RenderPipeline, Optional<RenderPipeline>> waterWithoutSnapshots,
		Map<RenderPipeline, Optional<RenderPipeline>> wind,
		Map<RenderPipeline, RenderPipeline> colors,
		@Nullable MetalRenderPipeline resolve, MetalTexture.@Nullable Format resolveFormat, boolean unavailable) { }
	private final Map<FrameBindings.ColorEncoding, CachedPrograms> parkedPrograms = new java.util.EnumMap<>(FrameBindings.ColorEncoding.class);
	private @Nullable GpuFormat forwardPassColorFormat;
	private final Set<String> declined = new LinkedHashSet<>();
	private final Matrix4f inverseProjection = new Matrix4f();
	private final Matrix4f viewToCameraRelative = new Matrix4f();
	private final Matrix4f lastRasterProjection = new Matrix4f(), lastRasterView = new Matrix4f();
	private final Matrix4f captureMatrix = new Matrix4f();
	private boolean cachedRasterProjection, cachedRasterView;
	private List<Channel> channels;
	private @Nullable RenderPass gbufferPass;
	private @Nullable GpuTextureView sceneAttachment;
	private @Nullable GpuTextureView depthAttachment;
	private @Nullable MetalRenderPipeline resolvePipeline;
	private final Map<String, MetalRenderPipeline> diagnosticResolvePipelines = new java.util.HashMap<>();
	private MetalTexture.@Nullable Format resolveSceneFormat;
	private Supplier<WorldShadowModule.@Nullable Frame> shadowFrameSupplier = () -> null;
	private Supplier<WorldShadowModule.@Nullable Frame> distantShadowFrameSupplier = () -> null;
	private @Nullable MetalTexture unoccludedDepth;
	private @Nullable MetalTextureView unoccludedDepthView;
	private @Nullable MetalSampler unoccludedSampler;
	private @Nullable MetalBuffer unoccludedFrame;
	private @Nullable SkyFrameInputs cloudFrame;
	private boolean haveRasterProjection;
	private boolean haveRasterView;
	private boolean haveFog;
	private boolean pendingResolve;
	private boolean resolveUnavailable;
	private boolean closed;
	private FrameBindings.ColorEncoding colorEncoding = FrameBindings.ColorEncoding.LEGACY_ENCODED;
	private final Vector4f fogColor = new Vector4f();
	private float fogEnvironmentalStart = WorldLightingModule.DISABLED_FOG_DISTANCE;
	private float fogEnvironmentalEnd = WorldLightingModule.DISABLED_FOG_DISTANCE;
	private float fogRenderDistanceStart = WorldLightingModule.DISABLED_FOG_DISTANCE;
	private float fogRenderDistanceEnd = WorldLightingModule.DISABLED_FOG_DISTANCE;
	private float fogSkyEnd = WorldLightingModule.DISABLED_FOG_DISTANCE;
	private float fogCloudsEnd = WorldLightingModule.DISABLED_FOG_DISTANCE;

	WorldGeometryAdapter(
		final MetalGpuDevice device,
		final ShaderTargetAllocator allocator,
		final String packId,
		final String passId,
		final String source,
		final String targetDefines,
		final List<String> channelIds,
		final ShaderPack.Pass resolvePass,
		final String resolveSource,
		final List<ShaderPack.Option> uniformOptions,
		final Function<String, Object> optionValue
	) {
		this.device = device;
		this.allocator = allocator;
		this.packId = packId;
		this.passId = passId;
		this.source = source;
		this.targetDefines = targetDefines;
		this.channelIds = List.copyOf(channelIds);
		this.resolvePass = resolvePass;
		this.resolveSource = resolveSource;
		this.uniformOptions = List.copyOf(uniformOptions);
		this.optionBytes = Math.max(4, Math.multiplyExact(this.uniformOptions.size(), 4));
		this.resolveCameraOffset = Math.addExact(this.optionBytes, UNIFORM_ALIGNMENT - 1) & -UNIFORM_ALIGNMENT;
		this.lightingFrameOffset = Math.addExact(this.resolveCameraOffset, UNIFORM_ALIGNMENT);
		this.resolveFrameBytes = Math.addExact(this.lightingFrameOffset, WorldLightingModule.FRAME_BYTES);
		this.optionValue = optionValue;
		this.distantShadows = ShaderPackRuntime.BUILTIN_ID.equals(packId)
			&& ((Number)optionValue.apply("distant_shadow_distance")).floatValue()
				> ((Number)optionValue.apply("shadow_distance")).floatValue();
		int textureSlot = 0;
		int shadowMap = -1;
		for (String read : resolvePass.reads()) {
			if ("shadow_map".equals(read)) {
				shadowMap = textureSlot;
			}
			textureSlot++;
		}
		int bufferSlot = 1;
		int shadowFrame = -1;
		for (String buffer : resolvePass.buffers()) {
			if ("shadow_frame".equals(buffer)) {
				shadowFrame = bufferSlot;
			}
			bufferSlot++;
		}
		this.shadowMapSlot = shadowMap;
		this.shadowFrameSlot = shadowFrame;
		this.resolveCameraSlot = bufferSlot;
		this.lightingFrameSlot = bufferSlot + 1;
		this.localLighting = ShaderPackRuntime.BUILTIN_ID.equals(packId) && Boolean.TRUE.equals(optionValue.apply("local_lights"))
			? new WorldLocalLighting(device.metal()) : null;
		this.channels = this.captureChannels();
		if (this.shadowMapSlot >= 0 || this.shadowFrameSlot >= 0) {
			this.createUnoccludedBindings();
		}
		device.setDeferredResolve(this::encodeMergedResolve);
		device.setWorldUniformCapture(new dev.metalcraft.client.metal.WorldUniformCapture() {
			@Override public boolean needed() { return WorldGeometryAdapter.this.pendingResolve && !WorldGeometryAdapter.this.closed; }
			@Override public void capture(String name, ByteBuffer bytes) { WorldGeometryAdapter.this.captureWorldUniform(name, bytes); }
		});
		active = this;
	}

	public @Nullable WorldLocalLighting localLighting() { return this.localLighting; }

	public void setCloudFrame(final @Nullable SkyFrameInputs frame) {
		this.cloudFrame = frame;
	}

	public void setShadowFrameSupplier(final Supplier<WorldShadowModule.@Nullable Frame> supplier) {
		this.shadowFrameSupplier = supplier == null ? () -> null : supplier;
	}

	public void setDistantShadowFrameSupplier(final Supplier<WorldShadowModule.@Nullable Frame> supplier) {
		this.distantShadowFrameSupplier = supplier == null ? () -> null : supplier;
	}

	/**
	 * {@code projection} is the raster {@code ProjMat}; {@code view} maps camera-relative world to
	 * view space. Reconstruction uses their inverses. A change flushes a pending resolve first.
	 */
	public void setRasterTransforms(final Matrix4fc projection, final Matrix4fc view) {
		this.setRasterProjection(projection);
		this.setRasterView(view);
	}

	/**
	 * Fog distances and colour used when resolve re-applies fog after lighting. Unset fog is
	 * disabled (starts/ends beyond world distance), matching an unfogged seed.
	 */
	public void setFog(final Vector4fc color, final float environmentalStart, final float environmentalEnd,
		final float renderDistanceStart, final float renderDistanceEnd) {
		this.setFog(color, environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd,
			WorldLightingModule.DISABLED_FOG_DISTANCE, WorldLightingModule.DISABLED_FOG_DISTANCE);
	}

	public void setFog(final Vector4fc color, final float environmentalStart, final float environmentalEnd,
		final float renderDistanceStart, final float renderDistanceEnd,
		final float skyEnd, final float cloudsEnd) {
		if (color == null || !Float.isFinite(environmentalStart) || !Float.isFinite(environmentalEnd)
			|| !Float.isFinite(renderDistanceStart) || !Float.isFinite(renderDistanceEnd)
			|| !Float.isFinite(skyEnd) || !Float.isFinite(cloudsEnd)) {
			return;
		}
		if (this.haveFog && this.fogColor.equals(color, 1.0e-5F)
			&& this.fogEnvironmentalStart == environmentalStart && this.fogEnvironmentalEnd == environmentalEnd
			&& this.fogRenderDistanceStart == renderDistanceStart && this.fogRenderDistanceEnd == renderDistanceEnd
			&& this.fogSkyEnd == skyEnd && this.fogCloudsEnd == cloudsEnd) {
			return;
		}
		if (this.haveFog && this.pendingResolve) {
			this.flushPendingResolve();
		}
		this.fogColor.set(color);
		this.fogEnvironmentalStart = environmentalStart;
		this.fogEnvironmentalEnd = environmentalEnd;
		this.fogRenderDistanceStart = renderDistanceStart;
		this.fogRenderDistanceEnd = renderDistanceEnd;
		this.fogSkyEnd = skyEnd;
		this.fogCloudsEnd = cloudsEnd;
		this.haveFog = true;
	}

	public static @Nullable WorldGeometryAdapter active() {
		WorldGeometryAdapter current = active;
		return current == null || current.closed ? null : current;
	}

	public static RenderPass beginWorldPass(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView color,
		final Optional<Vector4fc> clearColor,
		final @Nullable GpuTextureView depth,
		final OptionalDouble clearDepth,
		final List<RenderPipeline> pipelines
	) {
		WorldGeometryAdapter binding = active();
		if (binding != null) {
			binding.validateSceneEncoding(color);
			binding.forwardPassColorFormat = color.texture().getFormat();
		}
		if (binding == null || depth == null || pipelines.isEmpty()
			|| BASELINE_PROBE && Boolean.getBoolean("metalcraft.baselineSkipGbuffer")) {
			return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
		}
		for (RenderPipeline pipeline : pipelines) {
			if (binding.substitutionFor(pipeline).isEmpty()) {
				resolveOpaque();
				return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
			}
		}
		if (!binding.matchesWorldSize(color)) {
			resolveOpaque();
			return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
		}
		return binding.begin(encoder, label, color, clearColor, depth, clearDepth);
	}

	public static RenderPipeline substitute(final RenderPass pass, final RenderPipeline pipeline) {
		WorldGeometryAdapter binding = active();
		if (binding == null) {
			return pipeline;
		}
		if (binding.gbufferPass != pass) {
			return binding.linearColorCompatible(pipeline);
		}
		binding.pendingResolve = true;
		return binding.substitutionFor(pipeline).map(Substitution::pipeline).orElseGet(() -> binding.linearColorCompatible(pipeline));
	}

	/**
	 * Fabulous HDR layers advertise {@code RGBA16_FLOAT} to Blaze3D. Vanilla pipelines declare
	 * RGBA8, so the pass format check rejects them unless a format-matched copy is supplied.
	 * Ordinary identity-routed main still reports RGBA8 and is left unchanged.
	 */
	private RenderPipeline linearColorCompatible(final RenderPipeline pipeline) {
		if (this.colorEncoding != FrameBindings.ColorEncoding.LINEAR_SRGB || this.forwardPassColorFormat == null) {
			return pipeline;
		}
		ColorTargetState state = pipeline.getColorTargetState();
		if (state == null || state.format() == this.forwardPassColorFormat) {
			return pipeline;
		}
		GpuFormat format = this.forwardPassColorFormat;
		return this.linearColorPipelines.computeIfAbsent(pipeline, original -> copyWithColorFormat(original, format));
	}

	public static RenderPipeline copyWithColorFormat(final RenderPipeline pipeline, final GpuFormat format) {
		ColorTargetState original = pipeline.getColorTargetState();
		ColorTargetState remapped = new ColorTargetState(original.blendFunction(), format, original.writeMask());
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.parse("metalcraft:linear_color/"
				+ pipeline.getLocation().getPath() + "_" + format.name().toLowerCase(Locale.ROOT) + "_"
				+ Integer.toHexString(System.identityHashCode(pipeline))))
			.withVertexShader(pipeline.getVertexShader())
			.withFragmentShader(pipeline.getFragmentShader())
			.withDepthStencilState(Optional.ofNullable(pipeline.getDepthStencilState()))
			.withPolygonMode(pipeline.getPolygonMode())
			.withCull(pipeline.isCull())
			.withPrimitiveTopology(pipeline.getPrimitiveTopology())
			.withColorTargetState(0, remapped);
		for (BindGroupLayout layout : pipeline.getBindGroupLayouts()) {
			builder = builder.withBindGroupLayout(layout);
		}
		VertexFormat[] bindings = pipeline.getVertexFormatBindings();
		for (int index = 0; index < bindings.length; index++) {
			if (bindings[index] != null) {
				builder = builder.withVertexBinding(index, bindings[index]);
			}
		}
		for (String flag : pipeline.getShaderDefines().flags()) {
			builder = builder.withShaderDefine(flag);
		}
		for (var value : pipeline.getShaderDefines().values().entrySet()) {
			String number = value.getValue();
			if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
				builder = builder.withShaderDefine(value.getKey(), Float.parseFloat(number));
			} else {
				builder = builder.withShaderDefine(value.getKey(), Integer.parseInt(number));
			}
		}
		return builder.build();
	}

	public static void resolveOpaque() {
		WorldGeometryAdapter binding = active();
		if (binding != null && binding.pendingResolve) {
			binding.device.resolveDeferredShaderPass();
		}
	}

	public void beginFrame() {
		this.beginFrame(FrameBindings.ColorEncoding.LEGACY_ENCODED);
	}

	/**
	 * Selects matching geometry and deferred lighting semantics before world rendering begins.
	 * The caller must first preflight all forward producers and route the entire world coherently.
	 * A floating-point attachment alone never selects this contract. The live caller stays legacy
	 * until the sky, forward, Fabulous and output boundaries are ready together.
	 */
	public void beginFrame(final FrameBindings.ColorEncoding encoding) {
		if (this.closed) throw new IllegalStateException("World geometry adapter is closed");
		if (encoding == null) throw new NullPointerException("encoding");
		if (encoding == FrameBindings.ColorEncoding.LINEAR_SRGB && !ShaderPackRuntime.BUILTIN_ID.equals(this.packId)) {
			throw new IllegalArgumentException("Only Standard declares linear world geometry semantics");
		}
		// Submit a pending resolve with its original semantics before retiring its programs.
		this.flushPendingResolve();
		if (this.pendingResolve) {
			throw new IllegalStateException("Close the current world pass before beginning another frame");
		}
		if (this.colorEncoding != encoding) {
			// The hand/HUD needs legacy semantics after each HDR world. Keep both sets
			// of programs alive instead of compiling them again on every transition.
			this.parkedPrograms.put(this.colorEncoding, new CachedPrograms(this.substitutions,
				this.waterSubstitutions, this.waterWithoutSnapshots, this.windSubstitutions, this.linearColorPipelines, this.resolvePipeline,
				this.resolveSceneFormat, this.resolveUnavailable));
			CachedPrograms cached = this.parkedPrograms.remove(encoding);
			this.substitutions = cached == null ? new IdentityHashMap<>() : cached.substitutions();
			this.waterSubstitutions = cached == null ? new IdentityHashMap<>() : cached.water();
			this.waterWithoutSnapshots = cached == null ? new IdentityHashMap<>() : cached.waterWithoutSnapshots();
			this.windSubstitutions = cached == null ? new IdentityHashMap<>() : cached.wind();
			this.linearColorPipelines = cached == null ? new IdentityHashMap<>() : cached.colors();
			this.resolvePipeline = cached == null ? null : cached.resolve();
			this.resolveSceneFormat = cached == null ? null : cached.resolveFormat();
			this.resolveUnavailable = cached != null && cached.unavailable();
			this.forwardPassColorFormat = null;
			this.colorEncoding = encoding;
		}
		this.pendingResolve = false;
		this.gbufferPass = null;
		this.haveRasterProjection = false;
		this.haveRasterView = false;
		this.cachedRasterProjection = this.cachedRasterView = false;
	}

	void refreshChannels() {
		this.channels = this.captureChannels();
		this.retireParkedPrograms();
		this.clearResolvePipeline();
	}

	private void clearResolvePipeline() {
		if (this.resolvePipeline != null) {
			this.resolvePipeline.close();
		}
		this.resolvePipeline = null;
		this.resolveSceneFormat = null;
	}

	private List<Channel> captureChannels() {
		List<Channel> captured = new ArrayList<>(this.channelIds.size());
		for (String id : this.channelIds) {
			MetalGpuTextureView view = this.allocator.view(id);
			if (view == null) {
				throw new IllegalStateException("G-buffer channel '" + id + "' is not allocated");
			}
			captured.add(new Channel(id, view, clearColorFor(id)));
		}
		return List.copyOf(captured);
	}

	private boolean matchesWorldSize(final GpuTextureView color) {
		for (Channel channel : this.channels) {
			if (channel.view().getWidth(0) != color.getWidth(0) || channel.view().getHeight(0) != color.getHeight(0)) {
				return false;
			}
		}
		return true;
	}

	private RenderPass begin(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView color,
		final Optional<Vector4fc> clearColor,
		final GpuTextureView depth,
		final OptionalDouble clearDepth
	) {
		if (this.pendingResolve && (color != this.sceneAttachment || depth != this.depthAttachment
			|| clearColor.isPresent() || clearDepth.isPresent())) {
			resolveOpaque();
		}
		if (this.resolveUnavailable) {
			if (this.colorEncoding == FrameBindings.ColorEncoding.LINEAR_SRGB) {
				throw new IllegalStateException("Linear world resolve is unavailable");
			}
			return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
		}
		try {
			this.ensureResolvePipeline(color);
		} catch (RuntimeException error) {
			this.resolveUnavailable = true;
			if (this.colorEncoding == FrameBindings.ColorEncoding.LINEAR_SRGB) throw error;
			LOGGER.error("Shader pack '{}' could not compile its resolve; using vanilla geometry until reload", this.packId, error);
			resolveOpaque();
			return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
		}
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(label)
			.withColorAttachment(color, clearColor);
		for (Channel channel : this.channels) {
			descriptor = descriptor.withColorAttachment(
				channel.view(), this.pendingResolve ? Optional.empty() : Optional.of(channel.clearColor())
			);
		}
		descriptor = descriptor.withDepthAttachment(depth, clearDepth)
			.withRenderArea(new RenderPass.RenderArea(0, 0, color.getWidth(0), color.getHeight(0)));
		RenderPass pass = encoder.createRenderPass(descriptor);
		this.sceneAttachment = color;
		this.depthAttachment = depth;
		this.pendingResolve = true;
		this.gbufferPass = pass;
		return pass;
	}

	private boolean encodeMergedResolve(final MetalRenderPass openPass) {
		if (!this.pendingResolve || this.resolvePipeline == null) {
			return false;
		}
		if (BASELINE_PROBE && Boolean.getBoolean("metalcraft.baselineSkipResolve")) {
			this.pendingResolve = false;
			return true;
		}
		// Reserve immutable, aligned slices from the encoder's existing upload arena. The arena
		// retires only after this submission completes, including resolves flushed during submit.
		// Allocating through transientMemory does not close or split the active Metal render pass.
		try (GpuBufferSlice.MappedView mapping = this.device.createCommandEncoder().transientMemory().allocateGpuMapped(
			this.resolveFrameBytes, UNIFORM_ALIGNMENT, GpuBuffer.USAGE_UNIFORM, this.resolveFrameBytes, 1)
		) {
			ByteBuffer bytes = mapping.data();
			this.writeUniforms(bytes.slice(0, this.optionBytes).order(bytes.order()));
			this.writeResolveCamera(bytes.slice(this.resolveCameraOffset, RESOLVE_CAMERA_BYTES).order(bytes.order()));
			WorldLightingModule.write(bytes.slice(this.lightingFrameOffset, WorldLightingModule.FRAME_BYTES).order(bytes.order()), this.fogColor,
				this.fogEnvironmentalStart, this.fogEnvironmentalEnd,
				this.fogRenderDistanceStart, this.fogRenderDistanceEnd,
				this.fogSkyEnd, this.fogCloudsEnd);
			GpuBufferSlice slice = mapping.slice();
			MetalBuffer uniforms = this.device.nativeBuffer(slice.buffer());
			openPass.setScissor(0, 0, this.sceneAttachment.getWidth(0), this.sceneAttachment.getHeight(0));
			openPass.setPipeline(BASELINE_PROBE && Boolean.getBoolean("metalcraft.baselineScalarShadows")
				? this.diagnosticResolvePipelines.computeIfAbsent(this.colorEncoding + "/" + this.resolveSceneFormat,
					key -> this.compileResolvePipeline(this.resolveSceneFormat, true)) : this.resolvePipeline);
			openPass.setUniformBuffer(0, uniforms, slice.offset(), MetalRenderPass.STAGE_FRAGMENT);
			openPass.setUniformBuffer(this.resolveCameraSlot, uniforms, slice.offset() + this.resolveCameraOffset, MetalRenderPass.STAGE_FRAGMENT);
			openPass.setUniformBuffer(this.lightingFrameSlot, uniforms, slice.offset() + this.lightingFrameOffset, MetalRenderPass.STAGE_FRAGMENT);
			this.bindShadowResources(openPass);
			if (this.localLighting != null) this.localLighting.bind(openPass, this.lightingFrameSlot + 1);
			openPass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
			this.pendingResolve = false;
			return true;
		} catch (RuntimeException error) {
			LOGGER.error("Shader pack '{}' failed to encode the merged resolve", this.packId, error);
			this.pendingResolve = false;
			this.gbufferPass = null;
			if (this.colorEncoding == FrameBindings.ColorEncoding.LINEAR_SRGB) throw error;
			return false;
		}
	}

	private void validateSceneEncoding(final GpuTextureView scene) {
		if (this.colorEncoding == FrameBindings.ColorEncoding.LINEAR_SRGB
			&& this.resolvedSceneFormat(scene) != MetalTexture.Format.RGBA16_FLOAT) {
			throw new IllegalArgumentException("Linear world geometry requires RGBA16_FLOAT scene storage");
		}
	}

	/** Format after linear-session identity routing, which the command encoder applies at pass creation. */
	private MetalTexture.Format resolvedSceneFormat(final GpuTextureView scene) {
		MetalLinearWorldSession session = this.device.linearWorldSession();
		if (session != null && !session.isClosed()
			&& (scene == session.token().originalColor()
				|| (scene instanceof MetalGpuTextureView view
					&& view.texture() == session.token().originalColorTexture()))) {
			return session.hdrColor().attachment().descriptor().format();
		}
		return scene instanceof MetalGpuTextureView metalView
			? metalView.attachment().descriptor().format()
			: MetalTexture.Format.RGBA8_UNORM;
	}

	private String encodedSource(final String source) {
		return this.colorEncoding == FrameBindings.ColorEncoding.LINEAR_SRGB
			? source.replace("#define MC_SCENE_LINEAR_HDR 0\n", "#define MC_SCENE_LINEAR_HDR 1\n") : source;
	}

	private void ensureResolvePipeline(final GpuTextureView scene) {
		this.validateSceneEncoding(scene);
		MetalTexture.Format sceneFormat = this.resolvedSceneFormat(scene);
		if (this.resolvePipeline != null && this.resolveSceneFormat == sceneFormat) {
			return;
		}
		MetalRenderPipeline replacement = this.compileResolvePipeline(sceneFormat, false);
		if (this.resolvePipeline != null) this.resolvePipeline.close();
		this.resolvePipeline = replacement;
		this.resolveSceneFormat = sceneFormat;
		MetalPassCensus.kindFor("Metal Mod shader: " + this.resolvePass.id());
	}

	private MetalRenderPipeline compileResolvePipeline(final MetalTexture.Format sceneFormat, final boolean scalar) {
		List<MetalRenderPipeline.ColorTarget> targets = new ArrayList<>();
		targets.add(MetalRenderPipeline.ColorTarget.opaque(sceneFormat));
		for (Channel channel : this.channels) {
			targets.add(MetalRenderPipeline.ColorTarget.opaque(channel.view().attachment().descriptor().format()));
		}
		String selectedSource = this.encodedSource(this.resolveSource);
		if (scalar) {
			selectedSource = "#define MC_SHADOW_GATHER 0\n" + selectedSource;
		}
		if (this.distantShadows) {
			// Standard-owned optional bindings follow the five local-light buffers. Custom packs
			// keep their declared ABI, and disabled distant shadows compile away completely.
			selectedSource = "#define MC_BUFFER_DISTANT_SHADOW_FRAME " + (this.lightingFrameSlot + 6)
				+ "\n#define MC_TEX_DISTANT_SHADOW_MAP " + (this.shadowMapSlot + 1) + "\n" + selectedSource;
		}
		return this.device.metal().createRenderPipeline(new MetalRenderPipeline.Descriptor(
			selectedSource,
			"resolve_vertex",
			selectedSource,
			"resolve_fragment",
			targets,
			MetalTexture.Format.DEPTH32_FLOAT,
			MetalRenderPipeline.VertexDescriptor.EMPTY,
			MetalRenderPipeline.DepthState.DISABLED,
			MetalRenderPipeline.RasterState.DEFAULT
		));
	}

	private Optional<Substitution> substitutionFor(final RenderPipeline pipeline) {
		Optional<Substitution> known = this.substitutions.get(pipeline);
		if (known != null) {
			return known;
		}
		Optional<Substitution> built = this.build(pipeline);
		this.substitutions.put(pipeline, built);
		if (built.isEmpty()) {
			this.declined.add(String.valueOf(pipeline.getLocation()));
		}
		return built;
	}

	/** Only terrain with captured plant vertices uses this variant; all other draws keep their PSO. */
	public Optional<RenderPipeline> windPipeline(final RenderPipeline pipeline) {
		if (this.closed || this.colorEncoding != FrameBindings.ColorEncoding.LINEAR_SRGB
			|| !ShaderPackRuntime.BUILTIN_ID.equals(this.packId)
			|| !Boolean.TRUE.equals(this.optionValue.apply("wind_enabled"))
			|| ((Number)this.optionValue.apply("wind_strength")).floatValue() == 0) return Optional.empty();
		var cached = this.windSubstitutions.get(pipeline);
		if (cached != null) return cached;
		// The opaque pass has already substituted its vanilla pipeline. Recover the original's
		// alpha-test defines: rebuilding from the stand-in would turn leaf/grass holes opaque.
		RenderPipeline original = pipeline;
		for (var entry : this.substitutions.entrySet()) {
			if (entry.getValue().isPresent() && entry.getValue().get().pipeline() == pipeline) {
				original = entry.getKey();
				break;
			}
		}
		if (programFor(original) != Program.TERRAIN || !hasVertexElements(original, Program.TERRAIN)
			|| isBlended(original) || !Program.TERRAIN.implementedDefines().containsAll(declaredDefines(original))) return Optional.empty();
		Map<String, Integer> slots = resourceSlots(original);
		if (!slots.keySet().containsAll(List.of("Projection", "Fog", "Globals", "ChunkSection", "Sampler0", "Sampler2"))
			|| slots.values().stream().anyMatch(slot -> slot >= 8)) return Optional.empty();
		Material material = materialFor(original, Program.TERRAIN);
		RenderPipeline stand = this.standIn(original, Program.TERRAIN, material);
		try {
			this.device.registerNativePipeline(stand, new MetalGpuDevice.NativeProgram(
				"#define MC_TERRAIN_WIND 1\n" + this.programSource(original, Program.TERRAIN, material, slots, "ChunkSection"),
				"gbuffer_terrain_vertex", "gbuffer_terrain_fragment", FrameBindings.ColorEncoding.LINEAR_SRGB));
			if (!this.device.precompileLinearWorldPipeline(stand, null).isValid()) {
				throw new IllegalStateException("Could not compile wind terrain");
			}
			var result = Optional.of(stand);
			this.windSubstitutions.put(pipeline, result);
			return result;
		} catch (RuntimeException error) {
			this.device.forgetNativePipeline(stand);
			throw error;
		}
	}

	/** Forward water shares vanilla terrain coverage, sorting and color semantics. */
	public Optional<RenderPipeline> waterPipeline(final RenderPipeline pipeline) {
		return this.waterPipeline(pipeline, true);
	}

	public boolean waterEnabled() {
		return !this.closed && ShaderPackRuntime.BUILTIN_ID.equals(this.packId)
			&& Boolean.TRUE.equals(this.optionValue.apply("water_enabled"));
	}

	/** Underwater and Fabulous water retain waves/reflections without unused snapshot bindings. */
	public Optional<RenderPipeline> waterPipeline(final RenderPipeline pipeline, final boolean opaqueInputs) {
		if (this.closed || this.colorEncoding != FrameBindings.ColorEncoding.LINEAR_SRGB
			|| !ShaderPackRuntime.BUILTIN_ID.equals(this.packId)
			|| programFor(pipeline) != Program.TERRAIN || !hasVertexElements(pipeline, Program.TERRAIN)
			|| !isBlended(pipeline) || !Program.TERRAIN.implementedDefines().containsAll(declaredDefines(pipeline))) {
			return Optional.empty();
		}
		return (opaqueInputs ? this.waterSubstitutions : this.waterWithoutSnapshots).computeIfAbsent(pipeline, original -> {
			Map<String, Integer> slots = resourceSlots(original);
			if (!slots.keySet().containsAll(List.of("Projection", "Fog", "Globals", "ChunkSection", "Sampler0", "Sampler2"))
				|| slots.values().stream().anyMatch(slot -> slot >= 8)) return Optional.empty();
			RenderPipeline stand = this.standIn(original, Program.TERRAIN, Material.WATER, true);
			try {
				this.device.registerNativePipeline(stand, new MetalGpuDevice.NativeProgram(
					"#define MC_WATER_FORWARD 1\n#define MC_WATER_OPAQUE_INPUTS " + (opaqueInputs ? 1 : 0) + "\n"
						+ this.programSource(original, Program.TERRAIN, Material.WATER, slots, "ChunkSection"),
					"gbuffer_terrain_vertex", "gbuffer_terrain_fragment", FrameBindings.ColorEncoding.LINEAR_SRGB));
				if (!this.device.precompileLinearWorldPipeline(stand, null).isValid()) {
					this.device.forgetNativePipeline(stand);
					return Optional.empty();
				}
				return Optional.of(stand);
			} catch (RuntimeException error) {
				this.device.forgetNativePipeline(stand);
				LOGGER.error("Water forward pipeline unavailable; retaining baseline terrain", error);
				return Optional.empty();
			}
		});
	}

	private Optional<Substitution> build(final RenderPipeline pipeline) {
		Program program = programFor(pipeline);
		if (program == null || !hasVertexElements(pipeline, program) || isBlended(pipeline)) {
			return Optional.empty();
		}
		Set<String> defines = declaredDefines(pipeline);
		if (!program.implementedDefines().containsAll(defines)) {
			return Optional.empty();
		}
		Map<String, Integer> slots = resourceSlots(pipeline);
		for (String required : List.of("Projection", "Fog", "Sampler0")) {
			if (!slots.containsKey(required)) {
				return Optional.empty();
			}
		}
		String transforms = program == Program.TERRAIN ? "ChunkSection" : "DynamicTransforms";
		if (!slots.containsKey(transforms) || program == Program.TERRAIN && !slots.containsKey("Globals")) {
			return Optional.empty();
		}
		if (!slots.containsKey("Sampler2") && (program != Program.ENTITY || !defines.contains("EMISSIVE"))) {
			return Optional.empty();
		}
		if (program == Program.ENTITY) {
			if (!slots.containsKey("Sampler1") && !defines.contains("NO_OVERLAY")) {
				return Optional.empty();
			}
			boolean needsLighting = defines.contains("PER_FACE_LIGHTING") || !defines.contains("NO_CARDINAL_LIGHTING");
			if (needsLighting && !slots.containsKey("Lighting")) {
				return Optional.empty();
			}
			if (defines.contains("APPLY_TEXTURE_MATRIX") && !slots.containsKey("DynamicTransforms")) {
				return Optional.empty();
			}
		}
		Material material = materialFor(pipeline, program);
		try {
			RenderPipeline stand = this.standIn(pipeline, program, material);
			this.device.registerNativePipeline(stand, new MetalGpuDevice.NativeProgram(
				this.programSource(pipeline, program, material, slots, transforms),
				program.entryPoint(this.passId, "vertex"),
				program.entryPoint(this.passId, "fragment"),
				this.colorEncoding
			));
			if (!this.device.precompilePipeline(stand, null).isValid()) {
				this.device.forgetNativePipeline(stand);
				if (this.colorEncoding == FrameBindings.ColorEncoding.LINEAR_SRGB) {
					throw new IllegalStateException("Could not compile linear geometry for " + pipeline.getLocation());
				}
				return Optional.empty();
			}
			return Optional.of(new Substitution(stand, program, material));
		} catch (RuntimeException error) {
			if (this.colorEncoding == FrameBindings.ColorEncoding.LINEAR_SRGB) throw error;
			LOGGER.error("Shader pack '{}' could not stand in for pipeline {}", this.packId, pipeline.getLocation(), error);
			return Optional.empty();
		}
	}

	private RenderPipeline standIn(final RenderPipeline pipeline, final Program program, final Material material) {
		return this.standIn(pipeline, program, material, false);
	}

	private RenderPipeline standIn(final RenderPipeline pipeline, final Program program, final Material material, final boolean water) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.parse("metalcraft:gbuffer/"
				+ program.name().toLowerCase(Locale.ROOT) + "_" + material.name().toLowerCase(Locale.ROOT)
				+ "_" + Integer.toHexString(System.identityHashCode(pipeline))))
			.withVertexShader(pipeline.getVertexShader())
			.withFragmentShader(pipeline.getFragmentShader())
			.withDepthStencilState(Optional.ofNullable(pipeline.getDepthStencilState()))
			.withPolygonMode(pipeline.getPolygonMode())
			.withCull(pipeline.isCull())
			.withPrimitiveTopology(pipeline.getPrimitiveTopology());
		for (BindGroupLayout layout : pipeline.getBindGroupLayouts()) {
			builder = builder.withBindGroupLayout(layout);
		}
		VertexFormat[] bindings = pipeline.getVertexFormatBindings();
		for (int index = 0; index < bindings.length; index++) {
			if (bindings[index] != null) {
				builder = builder.withVertexBinding(index, bindings[index]);
			}
		}
		builder = builder.withColorTargetState(0, pipeline.getColorTargetState());
		for (int index = 0; !water && index < this.channels.size(); index++) {
			builder = builder.withColorTargetState(index + 1, new ColorTargetState(
				Optional.empty(),
				this.channels.get(index).view().gpuFormat(),
				ColorTargetState.WRITE_ALL
			));
		}
		return builder.build();
	}

	private String programSource(
		final RenderPipeline pipeline,
		final Program program,
		final Material material,
		final Map<String, Integer> slots,
		final String transforms
	) {
		StringBuilder preamble = new StringBuilder(this.targetDefines);
		preamble.append("#define MC_PASS_")
			.append(this.passId.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_')).append(" 1\n");
		preamble.append("#define MC_PROGRAM_").append(program.name()).append(" 1\n");
		preamble.append("#define MC_MATERIAL ").append(material.ordinal()).append('\n');
		preamble.append("#define MC_SLOT_TRANSFORMS ").append(slots.get(transforms)).append('\n');
		for (Map.Entry<String, Integer> slot : slots.entrySet()) {
			preamble.append("#define MC_SLOT_")
				.append(slot.getKey().toUpperCase(Locale.ROOT))
				.append(' ').append(slot.getValue()).append('\n');
		}
		for (String optional : List.of("Sampler1", "Sampler2", "Lighting", "Globals")) {
			preamble.append("#define MC_HAS_").append(optional.toUpperCase(Locale.ROOT))
				.append(slots.containsKey(optional) ? " 1\n" : " 0\n");
		}
		for (String define : program.implementedDefines()) {
			preamble.append("#define MC_DEFINE_").append(define)
				.append(declaredDefines(pipeline).contains(define) ? " 1\n" : " 0\n");
		}
		String cutout = pipeline.getShaderDefines().values().get("ALPHA_CUTOUT");
		preamble.append("#define MC_HAS_ALPHA_CUTOUT ").append(cutout == null ? "0\n" : "1\n");
		preamble.append("#define MC_ALPHA_CUTOUT ").append(cutout == null ? "0.0" : cutout).append('\n');
		for (Material value : Material.values()) {
			preamble.append("#define MC_MATERIAL_").append(value.name()).append(' ').append(value.ordinal()).append('\n');
		}
		return this.encodedSource(preamble + this.source);
	}

	private void writeUniforms(final ByteBuffer bytes) {
		ShaderOptionUniforms.write(bytes, this.uniformOptions, this.optionValue);
	}

	private void writeResolveCamera(final ByteBuffer bytes) {
		this.inverseProjection.get(0, bytes);
		this.viewToCameraRelative.get(64, bytes);
		int width = this.sceneAttachment == null ? 1 : this.sceneAttachment.getWidth(0);
		int height = this.sceneAttachment == null ? 1 : this.sceneAttachment.getHeight(0);
		bytes.putFloat(128, width);
		bytes.putFloat(132, height);
		bytes.putLong(136, 0L);
		SkyFrameInputs clouds = this.cloudFrame;
		if (clouds != null && clouds.hasClouds()) {
			clouds.cloudOrigin().get(144, bytes);
			bytes.putFloat(160, clouds.cloudSettings().x);
			bytes.putFloat(164, clouds.cloudSettings().y);
			bytes.putFloat(168, clouds.sunRain().w);
			bytes.putFloat(172, 0.0F);
		} else {
			for (int i = 144; i < RESOLVE_CAMERA_BYTES; i++) bytes.put(i, (byte)0);
		}
	}

	private void captureWorldUniform(final String name, final ByteBuffer bytes) {
		if (!this.pendingResolve || this.closed) return;
		if ("Fog".equals(name)) {
			if (bytes.remaining() >= 40) {
				this.setFog(new Vector4f(bytes.getFloat(0), bytes.getFloat(4), bytes.getFloat(8), bytes.getFloat(12)),
					bytes.getFloat(16), bytes.getFloat(20), bytes.getFloat(24), bytes.getFloat(28),
					bytes.getFloat(32), bytes.getFloat(36));
			}
			return;
		}
		if (bytes.remaining() < 64) return;
		// JOML's enabled Unsafe path assumes direct buffers; private-upload mirrors are heap-backed.
		Matrix4f matrix = this.captureMatrix.set(
			bytes.getFloat(0), bytes.getFloat(4), bytes.getFloat(8), bytes.getFloat(12),
			bytes.getFloat(16), bytes.getFloat(20), bytes.getFloat(24), bytes.getFloat(28),
			bytes.getFloat(32), bytes.getFloat(36), bytes.getFloat(40), bytes.getFloat(44),
			bytes.getFloat(48), bytes.getFloat(52), bytes.getFloat(56), bytes.getFloat(60));
		if (!matrix.isFinite()) return;
		if ("Projection".equals(name)) {
			this.setRasterProjection(matrix);
		} else if ("ChunkSection".equals(name) || "DynamicTransforms".equals(name)) {
			this.setRasterView(matrix);
		}
	}

	private void setRasterProjection(final Matrix4fc projection) {
		if (this.cachedRasterProjection && this.lastRasterProjection.equals(projection)
			&& this.lastRasterProjection.properties() == projection.properties()) return;
		Matrix4f inverse = new Matrix4f(projection).invert();
		if (!inverse.isFinite() || inverse.determinant() == 0.0F) {
			return;
		}
		this.lastRasterProjection.set(projection);
		this.cachedRasterProjection = true;
		if (this.haveRasterProjection && this.inverseProjection.equals(inverse, 1.0e-5F)) {
			return;
		}
		if (this.haveRasterProjection) {
			this.flushPendingResolve();
		}
		this.inverseProjection.set(inverse);
		this.haveRasterProjection = true;
	}

	private void setRasterView(final Matrix4fc view) {
		if (this.cachedRasterView && this.lastRasterView.equals(view)
			&& this.lastRasterView.properties() == view.properties()) return;
		Matrix4f inverse = new Matrix4f(view).invert();
		if (!inverse.isFinite() || inverse.determinant() == 0.0F) {
			return;
		}
		this.lastRasterView.set(view);
		this.cachedRasterView = true;
		if (this.haveRasterView && this.viewToCameraRelative.equals(inverse, 1.0e-5F)) {
			return;
		}
		if (this.haveRasterView) {
			this.flushPendingResolve();
		}
		this.viewToCameraRelative.set(inverse);
		this.haveRasterView = true;
	}

	private void flushPendingResolve() {
		if (this.pendingResolve && this.resolvePipeline != null) {
			this.device.resolveDeferredShaderPass();
		}
	}

	private void bindShadowResources(final MetalRenderPass pass) {
		if (this.distantShadows) {
			WorldShadowModule.Frame distant = this.distantShadowFrameSupplier.get();
			if (distant != null) {
				distant.bindUniforms(pass, this.lightingFrameSlot + 6, MetalRenderPass.STAGE_FRAGMENT);
				distant.bindDepth(pass, this.shadowMapSlot + 1);
			} else {
				pass.setUniformBuffer(this.lightingFrameSlot + 6, this.unoccludedFrame, 0, MetalRenderPass.STAGE_FRAGMENT);
				pass.setTexture(this.shadowMapSlot + 1, this.unoccludedDepthView, MetalRenderPass.STAGE_FRAGMENT);
				pass.setSampler(this.shadowMapSlot + 1, this.unoccludedSampler, MetalRenderPass.STAGE_FRAGMENT);
			}
		}
		WorldShadowModule.Frame frame = this.shadowFrameSupplier.get();
		if (frame != null) {
			if (this.shadowFrameSlot >= 0) {
				frame.bindUniforms(pass, this.shadowFrameSlot, MetalRenderPass.STAGE_FRAGMENT);
			}
			if (this.shadowMapSlot >= 0) {
				frame.bindDepth(pass, this.shadowMapSlot);
			}
			return;
		}
		if (this.unoccludedFrame == null || this.unoccludedDepthView == null || this.unoccludedSampler == null) {
			return;
		}
		if (this.shadowFrameSlot >= 0) {
			pass.setUniformBuffer(this.shadowFrameSlot, this.unoccludedFrame, 0L, MetalRenderPass.STAGE_FRAGMENT);
		}
		if (this.shadowMapSlot >= 0) {
			pass.setTexture(this.shadowMapSlot, this.unoccludedDepthView, MetalRenderPass.STAGE_FRAGMENT);
			pass.setSampler(this.shadowMapSlot, this.unoccludedSampler, MetalRenderPass.STAGE_FRAGMENT);
		}
	}

	private void createUnoccludedBindings() {
		MetalDevice metal = this.device.metal();
		MetalTexture depth = metal.createTexture(MetalTexture.Descriptor.array(
			MetalTexture.Format.DEPTH32_FLOAT, 4, 4, 2,
			MetalTexture.USAGE_RENDER_TARGET | MetalTexture.USAGE_SHADER_READ
		));
		MetalTextureView view = null;
		MetalSampler sampler = null;
		MetalBuffer frame = null;
		try {
			view = depth.createView();
			sampler = metal.createSampler(new MetalSampler.Descriptor(
				MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE
			));
			frame = metal.createBuffer(WorldShadowModule.FRAME_BYTES, MetalBuffer.StorageMode.SHARED);
			try (MetalBuffer.Mapping mapping = frame.map()) {
				ByteBuffer bytes = mapping.bytes();
				for (int index = 0; index < WorldShadowModule.FRAME_BYTES; index++) {
					bytes.put(index, (byte)0);
				}
			}
			try (MetalCommandQueue queue = metal.createCommandQueue();
				 MetalCommandBuffer commands = queue.createCommandBuffer()) {
				try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					List.of(),
					new MetalRenderPass.DepthAttachment(
						depth, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 1.0
					),
					depth.descriptor().sliceCount()
				))) {
					// Layered clear to unoccluded depth.
				}
				commands.commitAndWait();
			}
			this.unoccludedDepth = depth;
			this.unoccludedDepthView = view;
			this.unoccludedSampler = sampler;
			this.unoccludedFrame = frame;
		} catch (RuntimeException error) {
			if (frame != null) {
				frame.close();
			}
			if (sampler != null) {
				sampler.close();
			}
			if (view != null) {
				view.close();
			}
			depth.close();
			throw error;
		}
	}

	private void retireParkedPrograms() {
		for (CachedPrograms cached : this.parkedPrograms.values()) {
			cached.substitutions().values().forEach(value -> value.ifPresent(s -> this.device.forgetNativePipeline(s.pipeline())));
			cached.water().values().forEach(value -> value.ifPresent(this.device::forgetNativePipeline));
			cached.waterWithoutSnapshots().values().forEach(value -> value.ifPresent(this.device::forgetNativePipeline));
			cached.wind().values().forEach(value -> value.ifPresent(this.device::forgetNativePipeline));
			if (cached.resolve() != null) cached.resolve().close();
		}
		this.parkedPrograms.clear();
	}

	private void forgetSubstitutions() {
		this.retireParkedPrograms();
		for (Optional<Substitution> substitution : this.substitutions.values()) {
			substitution.ifPresent(value -> this.device.forgetNativePipeline(value.pipeline()));
		}
		this.substitutions.clear();
		for (Optional<RenderPipeline> water : this.waterSubstitutions.values()) {
			water.ifPresent(this.device::forgetNativePipeline);
		}
		this.waterSubstitutions.clear();
		this.waterWithoutSnapshots.values().forEach(value -> value.ifPresent(this.device::forgetNativePipeline));
		this.waterWithoutSnapshots.clear();
		this.windSubstitutions.values().forEach(value -> value.ifPresent(this.device::forgetNativePipeline));
		this.windSubstitutions.clear();
		this.linearColorPipelines.clear();
		this.forwardPassColorFormat = null;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.diagnosticResolvePipelines.values().forEach(MetalRenderPipeline::close);
		this.diagnosticResolvePipelines.clear();
		if (this.localLighting != null) this.localLighting.close();
		this.device.setDeferredResolve(null);
		this.device.setWorldUniformCapture(null);
		if (!this.declined.isEmpty()) {
			LOGGER.info("Shader pack '{}' leaves {} pipeline(s) outside the G-buffer: {}",
				this.packId, this.declined.size(), this.declined);
		}
		this.forgetSubstitutions();
		this.gbufferPass = null;
		if (this.resolvePipeline != null) {
			this.resolvePipeline.close();
			this.resolvePipeline = null;
		}
		if (this.unoccludedSampler != null) {
			this.unoccludedSampler.close();
			this.unoccludedSampler = null;
		}
		if (this.unoccludedDepthView != null) {
			this.unoccludedDepthView.close();
			this.unoccludedDepthView = null;
		}
		if (this.unoccludedDepth != null) {
			this.unoccludedDepth.close();
			this.unoccludedDepth = null;
		}
		if (this.unoccludedFrame != null) {
			this.unoccludedFrame.close();
			this.unoccludedFrame = null;
		}
		if (active == this) {
			active = null;
		}
	}

	public static @Nullable Program programFor(final RenderPipeline pipeline) {
		String vertexShader = String.valueOf(pipeline.getVertexShader());
		for (Program program : Program.values()) {
			if (program.vertexShader.equals(vertexShader)) {
				return program;
			}
		}
		return null;
	}

	private static Material materialFor(final RenderPipeline pipeline, final Program program) {
		Set<String> flags = pipeline.getShaderDefines().flags();
		if (program == Program.ENTITY) {
			return flags.contains("EMISSIVE") ? Material.EMISSIVE : Material.ENTITY;
		}
		return declaredDefines(pipeline).contains("ALPHA_CUTOUT") ? Material.FOLIAGE : Material.SOLID;
	}

	static Set<String> declaredDefines(final RenderPipeline pipeline) {
		Set<String> declared = new LinkedHashSet<>(pipeline.getShaderDefines().flags());
		declared.addAll(pipeline.getShaderDefines().values().keySet());
		return declared;
	}

	public static boolean isBlended(final RenderPipeline pipeline) {
		ColorTargetState state = pipeline.getColorTargetState();
		return state == null || state.blendFunction().isPresent();
	}

	static boolean hasVertexElements(final RenderPipeline pipeline, final Program program) {
		List<String> present = new ArrayList<>();
		for (VertexFormat binding : pipeline.getVertexFormatBindings()) {
			if (binding != null) {
				for (VertexFormatElement element : binding.getElements()) {
					present.add(element.name());
				}
			}
		}
		return present.equals(program.vertexElements);
	}

	private static Map<String, Integer> resourceSlots(final RenderPipeline pipeline) {
		Map<String, Integer> slots = new LinkedHashMap<>();
		for (BindGroupLayout.UniformDescription uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
			slots.put(uniform.name(), slots.size());
		}
		for (String sampler : BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts())) {
			slots.put(sampler, slots.size());
		}
		return slots;
	}

	static Vector4fc clearColorFor(final String target) {
		return target.endsWith("normal")
			? new Vector4f(0.5F, 0.5F, 0.0F, 0.0F)
			: new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
	}
}
