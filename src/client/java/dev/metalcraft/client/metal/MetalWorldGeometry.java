package dev.metalcraft.client.metal;

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
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.slf4j.Logger;

/**
 * Routes Minecraft's world geometry into the active pack's G-buffer pass.
 *
 * <p>The substitution happens above the backend, at the two places Minecraft opens a render pass
 * over its main target and binds a pipeline: {@code ChunkSectionsToRender.renderGroup} for terrain
 * and {@code PreparedRenderType.drawFromBuffer} for entities, block entities and every other
 * feature. By the time {@code MetalRenderPassBackend.setPipeline} sees a pipeline the pass has
 * already begun with one colour attachment, so the attachments have to be added where the pass is
 * created rather than where the pipeline is bound.
 *
 * <p>Two things are substituted and nothing else. The render pass gains the pack's G-buffer
 * channels after Minecraft's own colour attachment, which stays at index zero so unsubstituted
 * geometry, the sky, and the composite that follows all still land where they always did. The
 * pipeline is replaced by a stand-in carrying the same bind-group layouts, vertex bindings and
 * fixed-function state, so every uniform and texture the caller binds by name resolves to the same
 * Metal slot; only the programs differ, and those come from the pack as MSL.
 *
 * <h2>What is routed, and what is not</h2>
 *
 * A draw reaches the G-buffer when all of these hold, and is left alone otherwise:
 *
 * <ul>
 *   <li>its vertex program is one of Minecraft's three world programs - {@code core/terrain},
 *       {@code core/block}, {@code core/entity} - which is what selects the pack program;</li>
 *   <li>colour target zero has no blend function, because a blended draw cannot write a G-buffer:
 *       a normal, a material class and a light level are not quantities that blend. This is what
 *       keeps translucent terrain, translucent entities, and the glint layer out on their own
 *       terms rather than by name;</li>
 *   <li>its vertex format is the one the program reads, its bind-group layout carries the uniforms
 *       and samplers the program needs, and every compile-time define it was built with is one the
 *       pack's program implements. An unrecognised define is a refusal, because a variant this
 *       pack does not reproduce would draw the same geometry a quietly different way;</li>
 *   <li>the pass has a depth attachment, which the geometry shares with Minecraft so that one
 *       depth order covers substituted and unsubstituted draws alike.</li>
 * </ul>
 *
 * <p>Everything else keeps drawing into Minecraft's colour attachment alone and contributes nothing
 * to the G-buffer: the sky, clouds, weather, particles, block outlines, text, entity outlines, and
 * any modded render type, along with two whole programs this phase does not implement - Minecraft's
 * item program ({@code core/item}), which draws held items, dropped items, item frames and maps,
 * and its GUI programs. Water, glass and stained glass are translucent terrain and deliberately
 * remain in Minecraft's ordered forward path after the deferred resolve. The set of pipelines that
 * were offered and declined is logged once per pack, so the unhandled tail is stated rather than
 * found in a screenshot.
 */
public final class MetalWorldGeometry implements AutoCloseable {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static volatile @Nullable MetalWorldGeometry active;

	/** Minecraft's world vertex programs, and the pack program each one selects. */
	public enum Program {
		/** Chunk terrain: camera-relative positions rebuilt from the section's origin. */
		TERRAIN("minecraft:core/terrain", "Position", "Color", "UV0", "UV2"),
		/** Loose block models - the block-entity and moving-block path - offset by a model matrix. */
		BLOCK("minecraft:core/block", "Position", "Color", "UV0", "UV2"),
		/** Entities and block entities, the one world format that carries a real vertex normal. */
		ENTITY("minecraft:core/entity", "Position", "Color", "UV0", "UV1", "UV2", "Normal");

		private final String vertexShader;
		private final List<String> vertexElements;

		Program(final String vertexShader, final String... vertexElements) {
			this.vertexShader = vertexShader;
			this.vertexElements = List.of(vertexElements);
		}

		/**
		 * The compile-time defines this pack's program reproduces.
		 *
		 * <p>Minecraft's world programs are one source each with several variants compiled out of
		 * it, and a variant this pack does not implement would render differently from the vanilla
		 * pipeline it replaced - the same geometry, quietly lit or masked another way. So an
		 * unrecognised define is a refusal rather than something to ignore, and adding a variant to
		 * the MSL means adding its name here.
		 */
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

	/**
	 * What a surface is, written into the G-buffer so a later phase can light it differently.
	 *
	 * <p>The ordinal is the value the programs write, so these may be reordered only alongside the
	 * pack's own {@code MC_MATERIAL_*} constants, which the engine emits from this enum.
	 */
	public enum Material {
		SOLID,
		FOLIAGE,
		WATER,
		ENTITY,
		EMISSIVE
	}

	/** One pack G-buffer channel, in the colour-attachment order the geometry pass declared. */
	record Channel(String target, MetalGpuTextureView view, Vector4fc clearColor) {
	}

	/** A vanilla pipeline and the pack pipeline standing in for it. */
	private record Substitution(RenderPipeline pipeline, Program program, Material material) {
	}

	private final MetalGpuDevice device;
	private final MetalShaderEngine engine;
	private final MetalPipelineCache pipelineCache;
	private final String passId;
	private final String packId;
	private final String source;
	/** Colour attachments after Minecraft's own, which always occupies index zero. */
	private final List<Channel> channels;
	private final String targetDefines;
	private final Map<RenderPipeline, Optional<Substitution>> substitutions = new IdentityHashMap<>();
	private final Set<String> declined = new LinkedHashSet<>();
	/**
	 * The pass currently carrying G-buffer attachments, held so the pipeline substitution can tell
	 * whether the pass it is about to bind into actually has anywhere to write. The two always
	 * nest - a pass is created, a pipeline is bound, draws follow, the pass closes - so one field
	 * is enough and a stale one cannot be mistaken for a live one, because identity is the test.
	 */
	private @Nullable RenderPass gbufferPass;
	/** See {@link #worldDepth()}. */
	private @Nullable MetalTextureView worldDepth;
	/**
	 * Whether the G-buffer still holds last frame's contents.
	 *
	 * <p>Minecraft clears its own colour and depth with a standalone clear rather than a load
	 * action, and then opens a fresh render pass per feature draw over the same attachments. So the
	 * pack's channels are cleared by the load action of whichever pass reaches them first, and every
	 * pass after it in the same frame loads instead - which is also what lets the encoder merge
	 * them, since a clear is the one thing an already-running encoder cannot be asked to do.
	 */
	private boolean cleared;
	private boolean closed;

	MetalWorldGeometry(
		final MetalGpuDevice device,
		final MetalShaderEngine engine,
		final MetalPipelineCache pipelineCache,
		final String packId,
		final String passId,
		final String source,
		final String targetDefines,
		final List<Channel> channels
	) {
		this.device = device;
		this.engine = engine;
		this.pipelineCache = pipelineCache;
		this.packId = packId;
		this.passId = passId;
		this.source = source;
		this.targetDefines = targetDefines;
		this.channels = List.copyOf(channels);
	}

	/** @return the binding for the loaded pack's geometry pass, or null when no pack declares one */
	public static @Nullable MetalWorldGeometry active() {
		MetalWorldGeometry current = active;
		return current == null || current.closed ? null : current;
	}

	static void setActive(final @Nullable MetalWorldGeometry binding) {
		active = binding;
	}

	/**
	 * Opens a world render pass, with the pack's G-buffer channels attached when every pipeline the
	 * caller is about to bind into it has a stand-in.
	 *
	 * <p>Every pipeline, not the first one: Metal matches a pipeline to a pass index by index, so a
	 * pass that carries G-buffer attachments can only ever be given pipelines that write them. The
	 * terrain path binds one pipeline per chunk layer into a single pass, and the wireframe debug
	 * key replaces all of them at once, so the question has to be asked of the whole set before the
	 * pass exists. The feature path passes a single pipeline and the same rule answers it.
	 *
	 * <p>Refusing is always safe: the pass is then exactly the one Minecraft asked for.
	 */
	public static RenderPass beginWorldPass(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView color,
		final Optional<Vector4fc> clearColor,
		final @Nullable GpuTextureView depth,
		final OptionalDouble clearDepth,
		final List<RenderPipeline> pipelines
	) {
		RenderPass shadow = MetalWorldShadow.beginPass(encoder, label, pipelines);
		if (shadow != null) {
			return shadow;
		}
		MetalWorldGeometry binding = active();
		if (binding == null || depth == null || pipelines.isEmpty()) {
			return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
		}
		for (RenderPipeline pipeline : pipelines) {
			if (binding.substitutionFor(pipeline).isEmpty()) {
				// A different attachment layout would end the encoder and discard a memoryless
				// G-buffer. Resolve it while it is still resident, then let this draw use
				// Minecraft's ordinary forward path. Blended water, glass and stained glass all
				// arrive here after the opaque boundary and therefore composite over the lit scene.
				binding.engine.resolveOpaque();
				return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
			}
		}
		// Every attachment on one pass has to be the same size, and the pack's targets were sized
		// from the surface rather than from whatever Minecraft is rendering the world into. Where
		// the two disagree, resize to what the world actually is and let the next frame carry the
		// G-buffer; declining costs one frame of it and cannot produce a rejected descriptor.
		if (!binding.matchesWorldSize(color)) {
			binding.engine.resolveOpaque();
			binding.engine.resizeToWorld(color.getWidth(0), color.getHeight(0));
			return encoder.createRenderPass(label, color, clearColor, depth, clearDepth);
		}
		return binding.begin(encoder, label, color, clearColor, depth, clearDepth);
	}

	private boolean matchesWorldSize(final GpuTextureView color) {
		for (Channel channel : this.channels) {
			if (channel.view().getWidth(0) != color.getWidth(0) || channel.view().getHeight(0) != color.getHeight(0)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The pipeline to bind into {@code pass}: the pack's stand-in when the pass carries G-buffer
	 * attachments and this pipeline has one, and the vanilla pipeline in every other case.
	 */
	public static RenderPipeline substitute(final RenderPass pass, final RenderPipeline pipeline) {
		RenderPipeline shadow = MetalWorldShadow.substitute(pass, pipeline);
		if (shadow != pipeline) {
			return shadow;
		}
		MetalWorldGeometry binding = active();
		if (binding == null || binding.gbufferPass != pass) {
			return pipeline;
		}
		return binding.substitutionFor(pipeline).map(Substitution::pipeline).orElse(pipeline);
	}

	/**
	 * The pack pipeline standing in for a vanilla one, or null when this pack leaves it alone.
	 *
	 * <p>Package-private because the substitution is otherwise only reachable by rendering a frame,
	 * and whether a given vanilla pipeline is routed - and whether its MSL then compiles on the
	 * device - is exactly what a test needs to ask directly.
	 */
	@Nullable RenderPipeline standInFor(final RenderPipeline pipeline) {
		return this.substitutionFor(pipeline).map(Substitution::pipeline).orElse(null);
	}

	/**
	 * Stands in for the depth attachment a world draw would have recorded this frame.
	 *
	 * <p>Package-private for the same reason {@link #standInFor} is. The pack's later passes read a
	 * depth attachment that exists only once Minecraft has drawn a level into it, so a test with no
	 * level to draw still needs a way to run them - and running them is the only way to find out
	 * whether the pack's last pass reads the G-buffer back correctly.
	 */
	void supplyWorldDepthForTesting(final @Nullable MetalTextureView depth) {
		this.worldDepth = depth;
	}

	/** Called once per presented frame, after which the next G-buffer pass clears rather than loads. */
	void beginFrame() {
		this.cleared = false;
		this.gbufferPass = null;
		this.worldDepth = null;
	}

	/** Whether an opaque draw has populated tile attachments not yet consumed by the resolve. */
	boolean hasPendingResolve() {
		return this.cleared;
	}

	/** Starts a fresh memoryless lifetime after the current tile contents have been consumed. */
	void didResolve() {
		this.cleared = false;
		this.gbufferPass = null;
	}

	List<Channel> channels() {
		return this.channels;
	}

	/**
	 * The depth attachment the world draw used this frame, or null when no world was drawn.
	 *
	 * <p>The pack's {@code depth} target is not allocated. It is Minecraft's own depth attachment,
	 * shared with the geometry pass so that substituted and unsubstituted geometry keep one depth
	 * order between them, and taken from whichever pass reached the G-buffer first. Its absence is
	 * also how the engine tells that there was no world this frame.
	 */
	@Nullable MetalTextureView worldDepth() {
		// Minecraft's frame graph owns this view and may have released it by the time the pack
		// composites. A released one means the frame's depth is gone, which is the same answer as
		// never having drawn a world: decline, rather than sample a closed texture.
		MetalTextureView depth = this.worldDepth;
		return depth == null || depth.isClosed() ? null : depth;
	}

	private RenderPass begin(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView color,
		final Optional<Vector4fc> clearColor,
		final @Nullable GpuTextureView depth,
		final OptionalDouble clearDepth
	) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(label)
			.withColorAttachment(color, clearColor);
		for (Channel channel : this.channels) {
			descriptor = descriptor.withColorAttachment(
				channel.view(), this.cleared ? Optional.empty() : Optional.of(channel.clearColor())
			);
		}
		if (depth != null) {
			descriptor = descriptor.withDepthAttachment(depth, clearDepth);
		}
		descriptor = descriptor.withRenderArea(
			new RenderPass.RenderArea(0, 0, color.getWidth(0), color.getHeight(0))
		);
		if (depth instanceof MetalGpuTextureView metalDepth) {
			this.worldDepth = metalDepth.metal();
		}
		RenderPass pass = encoder.createRenderPass(descriptor);
		// Mark the tile contents pending only after the backend has opened this pass. Doing it
		// before createRenderPass would make an unrelated deferred encoder (notably the shadow pass)
		// look like the G-buffer that the backend is about to replace.
		this.cleared = true;
		this.gbufferPass = pass;
		return pass;
	}

	/**
	 * The stand-in for a vanilla pipeline, built on first sight and remembered either way.
	 *
	 * <p>An empty answer is cached too. This runs once per draw on the render thread, and a pipeline
	 * that cannot be substituted is asked about just as often as one that can.
	 */
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
		// Every input a variant reads has to be there. The two world-surface programs always read
		// the lightmap; the entity program reads it unless it is the emissive variant, reads the
		// overlay unless the variant says there is none, and reads the light directions unless the
		// variant does no cardinal lighting at all.
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
			RenderPipeline stand = standIn(pipeline, program, material);
			this.device.registerNativePipeline(stand, new MetalGpuDevice.NativeProgram(
				this.pipelineCache,
				this.programSource(pipeline, program, material, slots, transforms),
				program.entryPoint(this.passId, "vertex"),
				program.entryPoint(this.passId, "fragment")
			));
			return Optional.of(new Substitution(stand, program, material));
		} catch (RuntimeException error) {
			LOGGER.error("Shader pack '{}' could not stand in for pipeline {}", this.packId, pipeline.getLocation(), error);
			return Optional.empty();
		}
	}

	/**
	 * A pipeline identical to the vanilla one except for its programs and its extra colour targets.
	 *
	 * <p>The bind-group layouts are carried across unchanged, which is the whole reason a stand-in
	 * works: the backend resolves a bound name to a Metal slot through the layout of whichever
	 * pipeline is bound, so identical layouts mean {@code Sampler0} and {@code DynamicTransforms}
	 * land where they always did, and the pack's program only has to be told which index that is.
	 */
	private RenderPipeline standIn(final RenderPipeline pipeline, final Program program, final Material material) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.parse("metalcraft:gbuffer/"
				+ program.name().toLowerCase(Locale.ROOT) + "_" + material.name().toLowerCase(Locale.ROOT)
				+ "_" + Integer.toHexString(System.identityHashCode(pipeline))))
			// Never resolved: registerNativePipeline diverts compilation before any shader lookup.
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
				this.channels.get(index).view().texture().getFormat(),
				ColorTargetState.WRITE_ALL
			));
		}
		return builder.build();
	}

	/**
	 * The pack's MSL, prefixed with everything the program cannot know for itself: which Metal slot
	 * each named resource occupies, which colour index each target is, which optional inputs this
	 * pipeline variant actually has, and the alpha-cutout threshold vanilla compiled in.
	 */
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
		// Every variant switch the vanilla program was compiled with, so the pack's program takes
		// the same branches. Declared as zero when absent, so the MSL can test them with #if.
		for (String define : program.implementedDefines()) {
			preamble.append("#define MC_DEFINE_").append(define)
				.append(declaredDefines(pipeline).contains(define) ? " 1\n" : " 0\n");
		}
		// Two defines rather than one, because the C preprocessor cannot compare floats: the flag is
		// what #if tests, and the threshold is what the comparison in the program uses.
		String cutout = pipeline.getShaderDefines().values().get("ALPHA_CUTOUT");
		preamble.append("#define MC_HAS_ALPHA_CUTOUT ").append(cutout == null ? "0\n" : "1\n");
		preamble.append("#define MC_ALPHA_CUTOUT ").append(cutout == null ? "0.0" : cutout).append('\n');
		for (Material value : Material.values()) {
			preamble.append("#define MC_MATERIAL_").append(value.name()).append(' ').append(value.ordinal()).append('\n');
		}
		return preamble + this.source;
	}

	/** Reports, once, every pipeline that was offered to the G-buffer and left alone. */
	void logDeclinedPipelines() {
		if (!this.declined.isEmpty()) {
			LOGGER.info("Shader pack '{}' leaves {} pipeline(s) outside the G-buffer: {}",
				this.packId, this.declined.size(), this.declined);
		}
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.logDeclinedPipelines();
		for (Optional<Substitution> substitution : this.substitutions.values()) {
			substitution.ifPresent(value -> this.device.forgetNativePipeline(value.pipeline()));
		}
		this.substitutions.clear();
		this.gbufferPass = null;
		if (active == this) {
			active = null;
		}
	}

	static @Nullable Program programFor(final RenderPipeline pipeline) {
		String vertexShader = String.valueOf(pipeline.getVertexShader());
		for (Program program : Program.values()) {
			if (program.vertexShader.equals(vertexShader)) {
				return program;
			}
		}
		return null;
	}

	/**
	 * The surface class this pipeline draws.
	 *
	 * <p>Read from the pipeline's own compiled-in defines rather than from a table of pipeline
	 * names, so a render type added by a mod is classified the same way a vanilla one is. Alpha
	 * cutout on world terrain is overwhelmingly foliage - leaves, grass, crops, saplings - and an
	 * entity program compiled without a lightmap is emissive by construction, which is what
	 * {@code EMISSIVE} means in Minecraft's own entity shader.
	 */
	private static Material materialFor(final RenderPipeline pipeline, final Program program) {
		Set<String> flags = pipeline.getShaderDefines().flags();
		if (program == Program.ENTITY) {
			return flags.contains("EMISSIVE") ? Material.EMISSIVE : Material.ENTITY;
		}
		return declaredDefines(pipeline).contains("ALPHA_CUTOUT") ? Material.FOLIAGE : Material.SOLID;
	}

	/** Every define the vanilla pipeline compiles with, whether it carries a value or not. */
	static Set<String> declaredDefines(final RenderPipeline pipeline) {
		Set<String> declared = new LinkedHashSet<>(pipeline.getShaderDefines().flags());
		declared.addAll(pipeline.getShaderDefines().values().keySet());
		return declared;
	}

	static boolean isBlended(final RenderPipeline pipeline) {
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

	/**
	 * Name to Metal argument-table slot, matching how the backend resolves a bound name: the
	 * flattened uniforms first, then the flattened samplers after them.
	 */
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

	/**
	 * The colour a G-buffer channel is cleared to where nothing draws.
	 *
	 * <p>Zero everywhere except an encoded normal, which has to decode to something finite: the
	 * octahedral origin is the centre of the unit square, not its corner. A cleared albedo is left
	 * with a zero alpha, which is one less than the first material class and therefore reads back
	 * as "nothing drew here" rather than as solid.
	 */
	static Vector4fc clearColorFor(final String target) {
		return target.endsWith("normal")
			? new Vector4f(0.5F, 0.5F, 0.0F, 0.0F)
			: new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
	}
}
