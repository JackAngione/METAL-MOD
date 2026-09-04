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
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalGpuTextureView;
import dev.metalcraft.client.metal.MetalPassCensus;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalTexture;
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
	private final MetalBuffer uniforms;
	private final Map<RenderPipeline, Optional<Substitution>> substitutions = new IdentityHashMap<>();
	private final Set<String> declined = new LinkedHashSet<>();
	private List<Channel> channels;
	private @Nullable RenderPass gbufferPass;
	private @Nullable GpuTextureView sceneAttachment;
	private @Nullable GpuTextureView depthAttachment;
	private @Nullable MetalRenderPipeline resolvePipeline;
	private MetalTexture.@Nullable Format resolveSceneFormat;
	private boolean pendingResolve;
	private boolean resolveUnavailable;
	private boolean closed;

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
		this.uniforms = device.metal().createBuffer(256L, MetalBuffer.StorageMode.SHARED);
		this.channels = this.captureChannels();
		this.writeUniforms();
		device.setDeferredResolve(this::encodeMergedResolve);
		active = this;
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
		this.pendingResolve = false;
		this.gbufferPass = null;
	}

	void refreshChannels() {
		this.channels = this.captureChannels();
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
			return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
		}
		try {
			this.ensureResolvePipeline(color);
		} catch (RuntimeException error) {
			this.resolveUnavailable = true;
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
		try {
			this.writeUniforms();
			openPass.setScissor(0, 0, this.sceneAttachment.getWidth(0), this.sceneAttachment.getHeight(0));
			openPass.setPipeline(this.resolvePipeline);
			openPass.setUniformBuffer(0, this.uniforms, 0L, MetalRenderPass.STAGE_FRAGMENT);
			openPass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
			this.pendingResolve = false;
			this.gbufferPass = null;
			return true;
		} catch (RuntimeException error) {
			LOGGER.error("Shader pack '{}' failed to encode the merged resolve", this.packId, error);
			this.pendingResolve = false;
			this.gbufferPass = null;
			return false;
		}
	}

	private void ensureResolvePipeline(final GpuTextureView scene) {
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
		MetalRenderPipeline replacement = this.device.metal().createRenderPipeline(new MetalRenderPipeline.Descriptor(
			this.resolveSource,
			"resolve_vertex",
			this.resolveSource,
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
				program.entryPoint(this.passId, "fragment")
			));
			if (!this.device.precompilePipeline(stand, null).isValid()) {
				this.device.forgetNativePipeline(stand);
				return Optional.empty();
			}
			return Optional.of(new Substitution(stand, program, material));
		} catch (RuntimeException error) {
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
		return preamble + this.source;
	}

	private void writeUniforms() {
		try (MetalBuffer.Mapping mapping = this.uniforms.map()) {
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

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.device.setDeferredResolve(null);
		if (!this.declined.isEmpty()) {
			LOGGER.info("Shader pack '{}' leaves {} pipeline(s) outside the G-buffer: {}",
				this.packId, this.declined.size(), this.declined);
		}
		for (Optional<Substitution> substitution : this.substitutions.values()) {
			substitution.ifPresent(value -> this.device.forgetNativePipeline(value.pipeline()));
		}
		this.substitutions.clear();
		this.gbufferPass = null;
		if (this.resolvePipeline != null) {
			this.resolvePipeline.close();
			this.resolvePipeline = null;
		}
		this.uniforms.close();
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
