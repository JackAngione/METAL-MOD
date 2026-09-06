package dev.metalcraft.client.shader;

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
import dev.metalcraft.client.metal.MetalPassCensus;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalSampler;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import dev.metalcraft.client.shader.world.WorldLightingModule;
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
	private final int shadowMapSlot;
	private final int shadowFrameSlot;
	private final int resolveCameraSlot;
	private final int lightingFrameSlot;
	private final Map<RenderPipeline, Optional<Substitution>> substitutions = new IdentityHashMap<>();
	private final Set<String> declined = new LinkedHashSet<>();
	private final Matrix4f inverseProjection = new Matrix4f();
	private final Matrix4f viewToCameraRelative = new Matrix4f();
	private List<Channel> channels;
	private @Nullable RenderPass gbufferPass;
	private @Nullable GpuTextureView sceneAttachment;
	private @Nullable GpuTextureView depthAttachment;
	private @Nullable MetalRenderPipeline resolvePipeline;
	private MetalTexture.@Nullable Format resolveSceneFormat;
	private Supplier<WorldShadowModule.@Nullable Frame> shadowFrameSupplier = () -> null;
	private @Nullable MetalTexture unoccludedDepth;
	private @Nullable MetalTextureView unoccludedDepthView;
	private @Nullable MetalSampler unoccludedSampler;
	private @Nullable MetalBuffer unoccludedFrame;
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
		this.optionValue = optionValue;
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
		this.channels = this.captureChannels();
		if (this.shadowMapSlot >= 0 || this.shadowFrameSlot >= 0) {
			this.createUnoccludedBindings();
		}
		device.setDeferredResolve(this::encodeMergedResolve);
		device.setWorldUniformCapture(this::captureWorldUniform);
		active = this;
	}

	public void setShadowFrameSupplier(final Supplier<WorldShadowModule.@Nullable Frame> supplier) {
		this.shadowFrameSupplier = supplier == null ? () -> null : supplier;
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
		if (binding != null) binding.validateSceneEncoding(color);
		if (binding == null || depth == null || pipelines.isEmpty()) {
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
		if (binding == null || binding.gbufferPass != pass) {
			return pipeline;
		}
		binding.pendingResolve = true;
		return binding.substitutionFor(pipeline).map(Substitution::pipeline).orElse(pipeline);
	}

	public static void resolveOpaque() {
		WorldGeometryAdapter binding = active();
		if (binding != null && binding.pendingResolve) {
			binding.device.resolveDeferredShaderPass();
		}
	}

	@Nullable RenderPipeline standInFor(final RenderPipeline pipeline) {
		return this.substitutionFor(pipeline).map(Substitution::pipeline).orElse(null);
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
			this.forgetSubstitutions();
			this.clearResolvePipeline();
			this.colorEncoding = encoding;
			this.resolveUnavailable = false;
		}
		this.pendingResolve = false;
		this.gbufferPass = null;
		this.haveRasterProjection = false;
		this.haveRasterView = false;
	}

	void refreshChannels() {
		this.channels = this.captureChannels();
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
		// Each resolve owns immutable uploads. Native command-buffer pinning keeps them alive
		// after close; neither a later draw group nor the next frame can overwrite their bytes.
		try (
			MetalBuffer uniforms = this.device.metal().createBuffer(
				Math.max(4L, this.uniformOptions.size() * 4L), MetalBuffer.StorageMode.SHARED);
			MetalBuffer camera = this.device.metal().createBuffer(160L, MetalBuffer.StorageMode.SHARED);
			MetalBuffer lighting = this.device.metal().createBuffer(
				WorldLightingModule.FRAME_BYTES, MetalBuffer.StorageMode.SHARED)
		) {
			this.writeUniforms(uniforms);
			this.writeResolveCamera(camera);
			WorldLightingModule.write(lighting, this.fogColor,
				this.fogEnvironmentalStart, this.fogEnvironmentalEnd,
				this.fogRenderDistanceStart, this.fogRenderDistanceEnd,
				this.fogSkyEnd, this.fogCloudsEnd);
			openPass.setScissor(0, 0, this.sceneAttachment.getWidth(0), this.sceneAttachment.getHeight(0));
			openPass.setPipeline(this.resolvePipeline);
			openPass.setUniformBuffer(0, uniforms, 0L, MetalRenderPass.STAGE_FRAGMENT);
			openPass.setUniformBuffer(this.resolveCameraSlot, camera, 0L, MetalRenderPass.STAGE_FRAGMENT);
			openPass.setUniformBuffer(this.lightingFrameSlot, lighting, 0L, MetalRenderPass.STAGE_FRAGMENT);
			this.bindShadowResources(openPass);
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
			&& (!(scene instanceof MetalGpuTextureView metalView)
				|| metalView.attachment().descriptor().format() != MetalTexture.Format.RGBA16_FLOAT)) {
			throw new IllegalArgumentException("Linear world geometry requires RGBA16_FLOAT scene storage");
		}
	}

	private String encodedSource(final String source) {
		return this.colorEncoding == FrameBindings.ColorEncoding.LINEAR_SRGB
			? source.replace("#define MC_SCENE_LINEAR_HDR 0\n", "#define MC_SCENE_LINEAR_HDR 1\n") : source;
	}

	private void ensureResolvePipeline(final GpuTextureView scene) {
		this.validateSceneEncoding(scene);
		MetalTexture.Format sceneFormat = scene instanceof MetalGpuTextureView metalView
			? metalView.attachment().descriptor().format()
			: MetalTexture.Format.RGBA8_UNORM;
		if (this.resolvePipeline != null && this.resolveSceneFormat == sceneFormat) {
			return;
		}
		List<MetalRenderPipeline.ColorTarget> targets = new ArrayList<>();
		targets.add(MetalRenderPipeline.ColorTarget.opaque(sceneFormat));
		for (Channel channel : this.channels) {
			targets.add(MetalRenderPipeline.ColorTarget.opaque(channel.view().attachment().descriptor().format()));
		}
		String selectedSource = this.encodedSource(this.resolveSource);
		MetalRenderPipeline replacement = this.device.metal().createRenderPipeline(new MetalRenderPipeline.Descriptor(
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
		if (this.resolvePipeline != null) {
			this.resolvePipeline.close();
		}
		this.resolvePipeline = replacement;
		this.resolveSceneFormat = sceneFormat;
		MetalPassCensus.kindFor("MetalCraft shader: " + this.resolvePass.id());
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
		for (int index = 0; index < this.channels.size(); index++) {
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

	private void writeUniforms(final MetalBuffer uniforms) {
		try (MetalBuffer.Mapping mapping = uniforms.map()) {
			ByteBuffer bytes = mapping.bytes();
			bytes.clear();
			while (bytes.hasRemaining()) {
				bytes.put((byte)0);
			}
			bytes.rewind();
			for (ShaderPack.Option option : this.uniformOptions) {
				Object value = this.optionValue.apply(option.id());
				switch (option.type()) {
					case BOOL -> bytes.putInt(Boolean.TRUE.equals(value) ? 1 : 0);
					case INT -> bytes.putInt(((Number)value).intValue());
					case FLOAT -> bytes.putFloat(((Number)value).floatValue());
					case ENUM -> bytes.putInt(option.values().indexOf(value));
				}
			}
		}
	}

	private void writeResolveCamera(final MetalBuffer camera) {
		try (MetalBuffer.Mapping mapping = camera.map()) {
			ByteBuffer bytes = mapping.bytes();
			this.inverseProjection.get(0, bytes);
			this.viewToCameraRelative.get(64, bytes);
			int width = this.sceneAttachment == null ? 1 : this.sceneAttachment.getWidth(0);
			int height = this.sceneAttachment == null ? 1 : this.sceneAttachment.getHeight(0);
			bytes.putFloat(128, width);
			bytes.putFloat(132, height);
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
		Matrix4f matrix = new Matrix4f(
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
		Matrix4f inverse = new Matrix4f(projection).invert();
		if (!inverse.isFinite() || inverse.determinant() == 0.0F) {
			return;
		}
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
		Matrix4f inverse = new Matrix4f(view).invert();
		if (!inverse.isFinite() || inverse.determinant() == 0.0F) {
			return;
		}
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

	private void forgetSubstitutions() {
		for (Optional<Substitution> substitution : this.substitutions.values()) {
			substitution.ifPresent(value -> this.device.forgetNativePipeline(value.pipeline()));
		}
		this.substitutions.clear();
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
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
