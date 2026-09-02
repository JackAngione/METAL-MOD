package dev.metalcraft.client.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.logging.LogUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.ShaderDefines;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Replays the prepared world once into a texel-stabilised four-layer cascaded shadow map. */
public final class MetalWorldShadow implements AutoCloseable {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int CASCADES = 4;
	private static final int UNIFORM_BYTES = 448;
	private static final int RASTER_PROJECTION_OFFSET = 320;
	private static final int INVERSE_RASTER_PROJECTION_OFFSET = 384;
	private static final Matrix4f capturedRasterProjection = new Matrix4f();
	private static boolean hasCapturedRasterProjection;
	private static final int LOCAL_SHADOW_LAYERS = 24;
	private static final int LOCAL_UNIFORM_BYTES = LOCAL_SHADOW_LAYERS * 64 + 16;
	private static final String MATRICES_UNIFORM = "MetalCraftShadow";
	private static final String LOCAL_MATRICES_UNIFORM = "MetalCraftLocalShadow";
	private static volatile @Nullable MetalWorldShadow active;

	private record Substitution(RenderPipeline pipeline) {
	}

	/** Blaze3D's builder inserts a default color target when none are declared; shadows need zero. */
	private static final class DepthOnlyPipeline extends RenderPipeline {
		DepthOnlyPipeline(
			final Identifier location,
			final RenderPipeline original,
			final List<BindGroupLayout> layouts,
			final DepthStencilState depth
		) {
			super(
				location, original.getVertexShader(), original.getFragmentShader(), ShaderDefines.builder().build(),
				layouts, new ColorTargetState[0], depth, original.getPolygonMode(), false,
				original.getVertexFormatBindings(), original.getPrimitiveTopology(), original.getSortKey()
			);
		}
	}

	private final MetalGpuDevice device;
	private final MetalPipelineCache pipelineCache;
	private final String packId;
	private final String passId;
	private final String source;
	private final MetalGpuTextureView shadowView;
	private final MetalGpuTexture localShadowTexture;
	private final MetalGpuTextureView localShadowView;
	private final DoubleSupplier distance;
	private final DoubleSupplier normalOffset;
	private final IntSupplier shadowCount;
	private final MetalWorldLighting lighting;
	private GpuBufferSlice matrices;
	private GpuBufferSlice localMatrices;
	private final Map<RenderPipeline, Optional<Substitution>> substitutions = new IdentityHashMap<>();
	private final Map<RenderPipeline, Optional<Substitution>> localSubstitutions = new IdentityHashMap<>();
	private final FrustumIntersection[] cascadeFrusta = new FrustumIntersection[CASCADES];
	private final FrustumIntersection[] localFrusta = new FrustumIntersection[LOCAL_SHADOW_LAYERS];
	private final Matrix4f[] cascadeMatrices = new Matrix4f[CASCADES];
	private final Matrix4f cameraViewForTesting = new Matrix4f();
	private final Set<String> declined = new LinkedHashSet<>();
	private @Nullable ChunkSectionsToRender preparedTerrain;
	private FeatureRenderDispatcher.@Nullable PreparedFrame preparedFeatures;
	private @Nullable RenderPass shadowPass;
	private double cameraX;
	private double cameraY;
	private double cameraZ;
	private boolean rendering;
	private boolean localRendering;
	private int localShadowCount;
	private boolean celestialActive;
	private boolean cleared;
	private boolean closed;

	MetalWorldShadow(
		final MetalGpuDevice device,
		final MetalPipelineCache pipelineCache,
		final String packId,
		final String passId,
		final String source,
		final MetalGpuTextureView shadowView,
		final DoubleSupplier distance,
		final DoubleSupplier normalOffset,
		final IntSupplier shadowCount,
		final IntSupplier localResolution,
		final MetalWorldLighting lighting
	) {
		this.device = device;
		this.pipelineCache = pipelineCache;
		this.packId = packId;
		this.passId = passId;
		this.source = source;
		this.shadowView = shadowView;
		this.distance = distance;
		this.normalOffset = normalOffset;
		this.shadowCount = shadowCount;
		this.lighting = lighting;
		int resolution = localResolution.getAsInt();
		MetalTexture localMetal = device.metal().createTexture(MetalTexture.Descriptor.array(
			MetalTexture.Format.DEPTH32_FLOAT, resolution, resolution, LOCAL_SHADOW_LAYERS, MetalTexture.USAGE_ALL
		));
		this.localShadowTexture = new MetalGpuTexture(
			GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
			"metalcraft:local_shadow", Blaze3DMetalMappings.gpuFormat(MetalTexture.Format.DEPTH32_FLOAT),
			resolution, resolution, LOCAL_SHADOW_LAYERS, 1, localMetal
		);
		this.localShadowView = new MetalGpuTextureView(this.localShadowTexture, 0, 1, localMetal.createView());
		this.matrices = this.zeroedUniform(UNIFORM_BYTES);
		this.localMatrices = this.zeroedUniform(LOCAL_UNIFORM_BYTES);
	}

	static void setActive(final @Nullable MetalWorldShadow binding) {
		active = binding;
	}

	/** The projection Minecraft last uploaded for rasterization, including view-bob and reversed-Z. */
	public static void captureRasterProjection(final Matrix4f projection) {
		capturedRasterProjection.set(projection);
		hasCapturedRasterProjection = true;
	}

	static void clearRasterProjectionForTesting() {
		hasCapturedRasterProjection = false;
		capturedRasterProjection.identity();
	}

	private static @Nullable MetalWorldShadow active() {
		MetalWorldShadow current = active;
		return current == null || current.closed ? null : current;
	}

	public static boolean isAvailable() {
		return active() != null;
	}

	boolean owns(final GpuTexture texture) {
		return this.shadowView.texture() == texture || this.localShadowView.texture() == texture;
	}

	MetalTextureView localShadowViewForTesting() {
		return this.localShadowView.metal();
	}

	MetalBuffer localMatricesForTesting() {
		return metal(this.localMatrices);
	}

	@Nullable RenderPipeline standInForTesting(final RenderPipeline pipeline) {
		return this.substitutionFor(pipeline).map(Substitution::pipeline).orElse(null);
	}

	@Nullable RenderPipeline localStandInForTesting(final RenderPipeline pipeline) {
		this.localRendering = true;
		try {
			return this.substitutionFor(pipeline).map(Substitution::pipeline).orElse(null);
		} finally {
			this.localRendering = false;
		}
	}

	MetalBuffer matricesForTesting() {
		return metal(this.matrices);
	}

	GpuBufferSlice matricesSliceForTesting() {
		return this.matrices;
	}

	GpuBufferSlice localMatricesSliceForTesting() {
		return this.localMatrices;
	}

	long matricesOffsetForTesting() {
		return this.matrices.offset();
	}

	long localMatricesOffsetForTesting() {
		return this.localMatrices.offset();
	}

	void useIdentityCascadesForTesting() {
		this.writeTestingCascades(false);
	}

	void useResolveCascadesForTesting() {
		this.writeTestingCascades(true);
	}

	void useIdentityLocalShadowsForTesting(final int count) {
		this.localShadowCount = Math.max(0, Math.min(MetalWorldLighting.MAX_SHADOW_LIGHTS, count));
		try (var mapping = this.allocateUniform(LOCAL_UNIFORM_BYTES)) {
			this.localMatrices = mapping.slice();
			var bytes = mapping.data().order(ByteOrder.nativeOrder());
			for (int index = 0; index < LOCAL_SHADOW_LAYERS; index++) new Matrix4f().get(index * 64, bytes);
			bytes.putInt(LOCAL_SHADOW_LAYERS * 64, this.localShadowCount);
			bytes.putInt(LOCAL_SHADOW_LAYERS * 64 + 4, this.localShadowView.getWidth(0));
			bytes.putInt(LOCAL_SHADOW_LAYERS * 64 + 8, 0);
			bytes.putInt(LOCAL_SHADOW_LAYERS * 64 + 12, 0);
		}
	}

	private void writeTestingCascades(final boolean flipViewDepth) {
		try (var mapping = this.allocateUniform(UNIFORM_BYTES)) {
			this.matrices = mapping.slice();
			var bytes = mapping.data().order(ByteOrder.nativeOrder());
			for (int index = 0; index < CASCADES; index++) {
				Matrix4f cascade = new Matrix4f();
				if (flipViewDepth) {
					cascade.m22(-1.0F);
				}
				cascade.get(index * 64, bytes);
			}
			bytes.putFloat(256, 16.0F);
			bytes.putFloat(260, 32.0F);
			bytes.putFloat(264, 64.0F);
			bytes.putFloat(268, 128.0F);
			bytes.putFloat(272, 0.0F);
			bytes.putFloat(276, flipViewDepth ? 0.0F : 1.0F);
			bytes.putFloat(280, flipViewDepth ? 1.0F : 0.0F);
			bytes.putFloat(284, 0.0F);
			bytes.putFloat(288, this.shadowView.getWidth(0));
			bytes.putFloat(292, 1.0F);
			bytes.putFloat(296, 1.0F);
			bytes.putFloat(300, 0.0F);
			bytes.putFloat(304, 1.0F);
			bytes.putFloat(308, 1.0F);
			bytes.putFloat(312, MetalCelestialLighting.Source.SUN.shaderValue());
			bytes.putFloat(316, 0.0F);
			writeRasterProjection(bytes, new Matrix4f().setPerspective(
				(float)Math.toRadians(90.0), 1.0F, 0.05F, 512.0F, true
			));
		}
	}

	boolean prepareForTesting(final CameraRenderState camera, final SkyRenderState sky) {
		return this.prepare(camera, sky);
	}

	Vector3f projectWorldPointForTesting(final int cascade, final Vector3f worldPoint) {
		Vector3f relative = new Vector3f(
			(float)(worldPoint.x - this.cameraX),
			(float)(worldPoint.y - this.cameraY),
			(float)(worldPoint.z - this.cameraZ)
		);
		this.cameraViewForTesting.transformPosition(relative);
		return this.cascadeMatrices[cascade].transformProject(relative);
	}

	Vector3f projectCameraRelativePointForTesting(final int cascade, final Vector3f cameraRelativePoint) {
		Vector3f viewPoint = this.cameraViewForTesting.transformPosition(cameraRelativePoint, new Vector3f());
		return this.cascadeMatrices[cascade].transformProject(viewPoint);
	}

	/** True only while the prepared geometry is being replayed into the shadow map. */
	public static boolean isRendering() {
		MetalWorldShadow current = active();
		return current != null && current.rendering;
	}

	/** The backend uses four instances so the vertex stage can select one array layer per cascade. */
	static boolean isShadowPipeline(final RenderPipeline pipeline) {
		if (pipeline == null) return false;
		String id = String.valueOf(pipeline.getLocation());
		return id.startsWith("metalcraft:shadow/") || id.startsWith("metalcraft:local_shadow/");
	}

	static int shadowInstanceMultiplier(final RenderPipeline pipeline) {
		if (pipeline == null) return 1;
		String id = String.valueOf(pipeline.getLocation());
		if (id.startsWith("metalcraft:local_shadow/")) {
			MetalWorldShadow current = active();
			return current == null ? 0 : current.localShadowCount * 6;
		}
		return id.startsWith("metalcraft:shadow/") ? CASCADES : 1;
	}

	/** Keeps unsupported solid features out of the depth-only replay instead of drawing them to color. */
	public static boolean shouldSkip(final RenderPipeline pipeline) {
		MetalWorldShadow current = active();
		return current != null && current.rendering && current.substitutionFor(pipeline).isEmpty();
	}

	/** Captures the prepared feature buffers; they remain owned and closed by LevelRenderer. */
	public static FeatureRenderDispatcher.PreparedFrame captureFeatures(
		final FeatureRenderDispatcher.PreparedFrame frame
	) {
		MetalWorldShadow current = active();
		if (current != null) {
			current.preparedFeatures = frame;
		}
		return frame;
	}

	/** Supplies terrain prepared against the shadow cascades' own union frustum. */
	public static void setPreparedTerrain(final ChunkSectionsToRender terrain) {
		MetalWorldShadow current = active();
		if (current != null) {
			current.preparedTerrain = terrain;
		}
	}

	/**
	 * Builds four practical-split cascades in camera-view space and snaps their light-space centres
	 * to shadow texels. The latter keeps a stationary surface on the same texels during sub-texel
	 * camera motion, which removes the characteristic crawling edge.
	 */
	public static boolean prepareCascades(final CameraRenderState camera, final SkyRenderState sky) {
		MetalWorldShadow current = active();
		if (current != null) {
			return current.prepare(camera, sky);
		}
		return false;
	}

	/** Tests a chunk against the union of the four light frusta, independent of camera visibility. */
	public static boolean isSectionVisible(final SectionRenderDispatcher.RenderSection section) {
		MetalWorldShadow current = active();
		if (current == null) {
			return false;
		}
		var bounds = section.getBoundingBox();
		float minX = (float)(bounds.minX - current.cameraX);
		float minY = (float)(bounds.minY - current.cameraY);
		float minZ = (float)(bounds.minZ - current.cameraZ);
		float maxX = (float)(bounds.maxX - current.cameraX);
		float maxY = (float)(bounds.maxY - current.cameraY);
		float maxZ = (float)(bounds.maxZ - current.cameraZ);
		for (FrustumIntersection frustum : current.cascadeFrusta) {
			if (frustum != null && frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ)) {
				return true;
			}
		}
		for (FrustumIntersection frustum : current.localFrusta) {
			if (frustum != null && frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ)) return true;
		}
		return false;
	}

	/** Runs once immediately before the main opaque terrain group. */
	public static void renderBeforeMain(final ChunkSectionLayerGroup group, final GpuSampler sampler) {
		MetalWorldShadow current = active();
		if (current == null || !current.celestialActive && current.localShadowCount == 0
			|| current.rendering || group != ChunkSectionLayerGroup.OPAQUE
			|| SharedConstants.DEBUG_HOTKEYS && Minecraft.getInstance().wireframe) {
			return;
		}
		ChunkSectionsToRender terrain = current.preparedTerrain;
		FeatureRenderDispatcher.PreparedFrame features = current.preparedFeatures;
		current.preparedTerrain = null;
		current.preparedFeatures = null;
		if (terrain == null) {
			return;
		}
		try {
			if (current.celestialActive) current.replay(terrain, features, sampler, false);
			if (current.localShadowCount > 0) current.replay(terrain, features, sampler, true);
		} finally {
			current.shadowPass = null;
			current.rendering = false;
			current.localRendering = false;
		}
	}

	private void replay(
		final ChunkSectionsToRender terrain,
		final FeatureRenderDispatcher.@Nullable PreparedFrame features,
		final GpuSampler sampler,
		final boolean local
	) {
		this.rendering = true;
		this.localRendering = local;
		this.cleared = false;
		this.shadowPass = null;
		terrain.renderGroup(ChunkSectionLayerGroup.OPAQUE, sampler);
		if (features != null) features.executeSolid();
		this.shadowPass = null;
		this.rendering = false;
	}

	/** Returns a depth-only layered pass while a supported shadow replay is active. */
	static @Nullable RenderPass beginPass(
		final CommandEncoder encoder,
		final java.util.function.Supplier<String> label,
		final List<RenderPipeline> pipelines
	) {
		MetalWorldShadow current = active();
		if (current == null || !current.rendering || pipelines.isEmpty()) {
			return null;
		}
		for (RenderPipeline pipeline : pipelines) {
			if (current.substitutionFor(pipeline).isEmpty()) {
				throw new IllegalStateException("Unsupported pipeline reached the shadow replay: " + pipeline.getLocation());
			}
		}
		MetalGpuTextureView target = current.localRendering ? current.localShadowView : current.shadowView;
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Shadow " + label.get())
			.withDepthAttachment(target, current.cleared ? OptionalDouble.empty() : OptionalDouble.of(1.0))
			.withRenderArea(new RenderPass.RenderArea(
				0, 0, target.getWidth(0), target.getHeight(0)
			));
		current.cleared = true;
		RenderPass pass = encoder.createRenderPass(descriptor);
		current.shadowPass = pass;
		return pass;
	}

	/** Binds the cascade block and substitutes a depth-only pack program. */
	static RenderPipeline substitute(final RenderPass pass, final RenderPipeline pipeline) {
		MetalWorldShadow current = active();
		if (current == null || !current.rendering || current.shadowPass != pass) {
			return pipeline;
		}
		Substitution replacement = current.substitutionFor(pipeline).orElseThrow();
		pass.setUniform(
			current.localRendering ? LOCAL_MATRICES_UNIFORM : MATRICES_UNIFORM,
			current.localRendering ? current.localMatrices : current.matrices
		);
		return replacement.pipeline();
	}

	private Optional<Substitution> substitutionFor(final RenderPipeline pipeline) {
		Map<RenderPipeline, Optional<Substitution>> cache = this.localRendering
			? this.localSubstitutions : this.substitutions;
		Optional<Substitution> known = cache.get(pipeline);
		if (known != null) {
			return known;
		}
		Optional<Substitution> built = this.build(pipeline, this.localRendering);
		cache.put(pipeline, built);
		if (built.isEmpty()) {
			this.declined.add(String.valueOf(pipeline.getLocation()));
		}
		return built;
	}

	private Optional<Substitution> build(final RenderPipeline pipeline, final boolean local) {
		MetalWorldGeometry.Program program = MetalWorldGeometry.programFor(pipeline);
		if (program == null || !MetalWorldGeometry.hasVertexElements(pipeline, program)
			|| MetalWorldGeometry.isBlended(pipeline)) {
			return Optional.empty();
		}
		Set<String> defines = MetalWorldGeometry.declaredDefines(pipeline);
		if (!program.implementedDefines().containsAll(defines)) {
			return Optional.empty();
		}
		String matricesUniform = local ? LOCAL_MATRICES_UNIFORM : MATRICES_UNIFORM;
		Map<String, Integer> slots = shadowResourceSlots(pipeline, matricesUniform);
		String transforms = program == MetalWorldGeometry.Program.TERRAIN ? "ChunkSection" : "DynamicTransforms";
		if (!slots.containsKey(transforms) || !slots.containsKey("Sampler0")
			|| program == MetalWorldGeometry.Program.TERRAIN && !slots.containsKey("Globals")) {
			return Optional.empty();
		}
		try {
			RenderPipeline standIn = this.standIn(pipeline, program, local, matricesUniform);
			this.device.registerNativePipeline(standIn, new MetalGpuDevice.NativeProgram(
				this.pipelineCache,
				this.programSource(pipeline, program, slots, transforms, local),
				program.entryPoint(this.passId, "vertex"),
				program.entryPoint(this.passId, "fragment")
			));
			return Optional.of(new Substitution(standIn));
		} catch (RuntimeException error) {
			LOGGER.error("Shader pack '{}' could not build shadow pipeline for {}", this.packId, pipeline.getLocation(), error);
			return Optional.empty();
		}
	}

	private RenderPipeline standIn(
		final RenderPipeline pipeline,
		final MetalWorldGeometry.Program program,
		final boolean local,
		final String matricesUniform
	) {
		List<BindGroupLayout> layouts = new ArrayList<>(pipeline.getBindGroupLayouts());
		layouts.add(BindGroupLayout.builder().withUniform(matricesUniform, UniformType.UNIFORM_BUFFER).build());
		return new DepthOnlyPipeline(
			Identifier.parse(local ? "metalcraft:local_shadow/" : "metalcraft:shadow/"
				+ program.name().toLowerCase(Locale.ROOT) + "_" + Integer.toHexString(System.identityHashCode(pipeline))),
			pipeline,
			List.copyOf(layouts),
			new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true, 1.75F, 1.0F)
		);
	}

	private String programSource(
		final RenderPipeline pipeline,
		final MetalWorldGeometry.Program program,
		final Map<String, Integer> slots,
		final String transforms,
		final boolean local
	) {
		StringBuilder preamble = new StringBuilder();
		preamble.append("#define MC_PASS_").append(this.passId.toUpperCase(Locale.ROOT)).append(" 1\n");
		preamble.append("#define MC_PROGRAM_").append(program.name()).append(" 1\n");
		preamble.append("#define MC_LOCAL_SHADOW ").append(local ? "1\n" : "0\n");
		preamble.append("#define MC_SLOT_TRANSFORMS ").append(slots.get(transforms)).append('\n');
		for (Map.Entry<String, Integer> slot : slots.entrySet()) {
			preamble.append("#define MC_SLOT_").append(slot.getKey().toUpperCase(Locale.ROOT))
				.append(' ').append(slot.getValue()).append('\n');
		}
		Set<String> declared = MetalWorldGeometry.declaredDefines(pipeline);
		for (String define : program.implementedDefines()) {
			preamble.append("#define MC_DEFINE_").append(define).append(declared.contains(define) ? " 1\n" : " 0\n");
		}
		String cutout = pipeline.getShaderDefines().values().get("ALPHA_CUTOUT");
		preamble.append("#define MC_HAS_ALPHA_CUTOUT ").append(cutout == null ? "0\n" : "1\n");
		preamble.append("#define MC_ALPHA_CUTOUT ").append(cutout == null ? "0.0" : cutout).append('\n');
		return preamble + this.source;
	}

	private static Map<String, Integer> shadowResourceSlots(
		final RenderPipeline pipeline, final String matricesUniform
	) {
		Map<String, Integer> slots = new LinkedHashMap<>();
		for (BindGroupLayout.UniformDescription uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
			slots.put(uniform.name(), slots.size());
		}
		slots.put(matricesUniform, slots.size());
		for (String sampler : BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts())) {
			slots.put(sampler, slots.size());
		}
		return slots;
	}

	private boolean prepare(final CameraRenderState camera, final SkyRenderState sky) {
		this.cameraX = camera.pos.x;
		this.cameraY = camera.pos.y;
		this.cameraZ = camera.pos.z;
		MetalCelestialLighting.State celestial = MetalCelestialLighting.derive(sky, camera);
		this.celestialActive = celestial.active();
		this.preparedTerrain = null;
		for (int index = 0; index < CASCADES; index++) {
			this.cascadeFrusta[index] = null;
		}
		float near = 0.05F;
		float far = Math.max(16.0F, (float)this.distance.getAsDouble());
		// Cascades stay on the un-bobbed camera frustum so walk-bob cannot crawl the shadow map.
		// Reconstruction still uses the GPU projection Minecraft rasterized, which does include bob.
		Matrix4f cascadeProjection = new Matrix4f(camera.projectionMatrix);
		Matrix4f rasterProjection = hasCapturedRasterProjection
			? new Matrix4f(capturedRasterProjection) : new Matrix4f(cascadeProjection);
		float tanHalfX = 1.0F / Math.max(1.0E-6F, Math.abs(cascadeProjection.m00()));
		float tanHalfY = 1.0F / Math.max(1.0E-6F, Math.abs(cascadeProjection.m11()));
		float[] splits = new float[CASCADES];
		for (int index = 0; index < CASCADES; index++) {
			float fraction = (index + 1.0F) / CASCADES;
			float logarithmic = near * (float)Math.pow(far / near, fraction);
			float linear = near + (far - near) * fraction;
			splits[index] = logarithmic * 0.65F + linear * 0.35F;
		}

		Vector3f lightWorld = celestial.worldDirection();
		Vector3f lightViewDirection = celestial.viewDirection();
		Matrix4f cameraView = new Matrix4f(camera.viewRotationMatrix);
		this.cameraViewForTesting.set(cameraView);
		Matrix4f inverseCameraView = cameraView.invert(new Matrix4f());
		Matrix4f[] matricesToWrite = new Matrix4f[CASCADES];
		float cascadeNear = near;
		for (int index = 0; index < CASCADES && this.celestialActive; index++) {
			float cascadeFar = splits[index];
			List<Vector3f> corners = frustumCorners(cascadeProjection, cascadeNear, cascadeFar, tanHalfX, tanHalfY);
			// Keep the light view camera-relative. Minecraft feeds world geometry to the GPU in this
			// space already, and converting through an absolute float position loses player motion far
			// from the origin before the translation can be cancelled back out.
			for (Vector3f corner : corners) inverseCameraView.transformPosition(corner);
			Vector3f center = new Vector3f();
			for (Vector3f corner : corners) center.add(corner);
			center.div(corners.size());
			float radius = 0.0F;
			for (Vector3f corner : corners) radius = Math.max(radius, corner.distance(center));
			radius = (float)Math.ceil(radius * 16.0F) / 16.0F;

			Vector3f up = Math.abs(lightWorld.y) > 0.95F
				? new Vector3f(0.0F, 0.0F, 1.0F) : new Vector3f(0.0F, 1.0F, 0.0F);
			Vector3f right = new Vector3f(lightWorld).cross(up).normalize();
			Vector3f lightUp = new Vector3f(right).cross(lightWorld).normalize();
			float texel = 2.0F * radius / this.shadowView.getWidth(0);
			// The grid itself is world-anchored, so include the absolute camera position only in these
			// scalar dot products. Double precision preserves sub-texel motion near the world border.
			double rightCoordinate = this.cameraX * right.x + this.cameraY * right.y + this.cameraZ * right.z
				+ center.dot(right);
			double upCoordinate = this.cameraX * lightUp.x + this.cameraY * lightUp.y + this.cameraZ * lightUp.z
				+ center.dot(lightUp);
			center.fma((float)(Math.rint(rightCoordinate / texel) * texel - rightCoordinate), right);
			center.fma((float)(Math.rint(upCoordinate / texel) * texel - upCoordinate), lightUp);

			Vector3f eye = new Vector3f(center).fma(2.0F * radius, lightWorld);
			Matrix4f lightView = new Matrix4f().setLookAt(eye, center, lightUp);
			Matrix4f lightProjection = new Matrix4f().setOrtho(
				-radius, radius, -radius, radius, 0.0F, 4.0F * radius, true
			);
			Matrix4f shadowFromRelativeWorld = lightProjection.mul(lightView, new Matrix4f());
			Matrix4f shadowFromView = shadowFromRelativeWorld.mul(inverseCameraView, new Matrix4f());
			matricesToWrite[index] = shadowFromView;
			this.cascadeMatrices[index] = new Matrix4f(shadowFromView);
			this.cascadeFrusta[index] = new FrustumIntersection(shadowFromRelativeWorld, true);
			cascadeNear = cascadeFar;
		}
		if (!this.celestialActive) {
			for (int index = 0; index < CASCADES; index++) {
				matricesToWrite[index] = new Matrix4f();
				splits[index] = 0.0F;
			}
		}

		try (var mapping = this.allocateUniform(UNIFORM_BYTES)) {
			this.matrices = mapping.slice();
			var bytes = mapping.data().order(ByteOrder.nativeOrder());
			for (int index = 0; index < CASCADES; index++) {
				matricesToWrite[index].get(index * 64, bytes);
			}
			bytes.putFloat(256, splits[0]);
			bytes.putFloat(260, splits[1]);
			bytes.putFloat(264, splits[2]);
			bytes.putFloat(268, splits[3]);
			bytes.putFloat(272, lightViewDirection.x);
			bytes.putFloat(276, lightViewDirection.y);
			bytes.putFloat(280, lightViewDirection.z);
			bytes.putFloat(284, (float)this.normalOffset.getAsDouble());
			bytes.putFloat(288, this.shadowView.getWidth(0));
			bytes.putFloat(292, tanHalfX);
			bytes.putFloat(296, tanHalfY);
			bytes.putFloat(300, 0.0F);
			bytes.putFloat(304, celestial.elevation());
			bytes.putFloat(308, celestial.intensity());
			bytes.putFloat(312, celestial.source().shaderValue());
			bytes.putFloat(316, 0.0F);
			writeRasterProjection(bytes, rasterProjection);
		}
		this.prepareLocalShadows(cameraView);
		return this.celestialActive || this.localShadowCount > 0;
	}

	private void prepareLocalShadows(final Matrix4f cameraView) {
		for (int index = 0; index < LOCAL_SHADOW_LAYERS; index++) this.localFrusta[index] = null;
		int wanted = Math.max(0, Math.min(MetalWorldLighting.MAX_SHADOW_LIGHTS, this.shadowCount.getAsInt()));
		Matrix4f[] matricesToWrite = new Matrix4f[LOCAL_SHADOW_LAYERS];
		for (int index = 0; index < LOCAL_SHADOW_LAYERS; index++) matricesToWrite[index] = new Matrix4f();
		this.localShadowCount = 0;
		for (MetalWorldLighting.FrameLight light : this.lighting.snapshot().lights()) {
			int slot = light.shadowSlot();
			if (slot < 0 || slot >= wanted) continue;
			this.localShadowCount = Math.max(this.localShadowCount, slot + 1);
			Vector3f origin = new Vector3f(light.viewX(), light.viewY(), light.viewZ());
			Matrix4f projection = new Matrix4f().setPerspective(
				(float)(Math.PI / 2.0), 1.0F, 0.05F, Math.max(light.radius(), 0.1F), true
			);
			for (int face = 0; face < 6; face++) {
				Vector3f direction = localFaceDirection(face);
				Vector3f up = localFaceUp(face);
				Matrix4f faceView = new Matrix4f().setLookAt(origin, new Vector3f(origin).add(direction), up);
				Matrix4f shadowFromView = projection.mul(faceView, new Matrix4f());
				int layer = slot * 6 + face;
				matricesToWrite[layer] = shadowFromView;
				this.localFrusta[layer] = new FrustumIntersection(
					shadowFromView.mul(cameraView, new Matrix4f()), true
				);
			}
		}
		try (var mapping = this.allocateUniform(LOCAL_UNIFORM_BYTES)) {
			this.localMatrices = mapping.slice();
			var bytes = mapping.data().order(ByteOrder.nativeOrder());
			for (int index = 0; index < LOCAL_SHADOW_LAYERS; index++) {
				matricesToWrite[index].get(index * 64, bytes);
			}
			bytes.putInt(LOCAL_SHADOW_LAYERS * 64, this.localShadowCount);
			bytes.putInt(LOCAL_SHADOW_LAYERS * 64 + 4, this.localShadowView.getWidth(0));
			bytes.putInt(LOCAL_SHADOW_LAYERS * 64 + 8, 0);
			bytes.putInt(LOCAL_SHADOW_LAYERS * 64 + 12, 0);
		}
	}

	private static Vector3f localFaceDirection(final int face) {
		return switch (face) {
			case 0 -> new Vector3f(1.0F, 0.0F, 0.0F);
			case 1 -> new Vector3f(-1.0F, 0.0F, 0.0F);
			case 2 -> new Vector3f(0.0F, 1.0F, 0.0F);
			case 3 -> new Vector3f(0.0F, -1.0F, 0.0F);
			case 4 -> new Vector3f(0.0F, 0.0F, 1.0F);
			default -> new Vector3f(0.0F, 0.0F, -1.0F);
		};
	}

	private GpuBufferSlice.MappedView allocateUniform(final int size) {
		return this.device.transientMemory().allocateGpuMapped(
			size,
			this.device.getDeviceInfo().limits().minUniformOffsetAlignment(),
			GpuBuffer.USAGE_UNIFORM
		);
	}

	private GpuBufferSlice zeroedUniform(final int size) {
		try (GpuBufferSlice.MappedView mapping = this.allocateUniform(size)) {
			ByteBuffer bytes = mapping.data();
			while (bytes.hasRemaining()) bytes.put((byte)0);
			return mapping.slice();
		}
	}

	private static MetalBuffer metal(final GpuBufferSlice slice) {
		return ((MetalGpuBuffer)slice.buffer()).metal();
	}

	private static Vector3f localFaceUp(final int face) {
		return switch (face) {
			case 2 -> new Vector3f(0.0F, 0.0F, 1.0F);
			case 3 -> new Vector3f(0.0F, 0.0F, -1.0F);
			default -> new Vector3f(0.0F, -1.0F, 0.0F);
		};
	}

	private static void writeRasterProjection(final ByteBuffer bytes, final Matrix4f raster) {
		raster.get(RASTER_PROJECTION_OFFSET, bytes);
		Matrix4f inverse = raster.invert(new Matrix4f());
		if (!Float.isFinite(inverse.determinant())) {
			inverse.identity();
		}
		inverse.get(INVERSE_RASTER_PROJECTION_OFFSET, bytes);
	}

	/**
	 * Recovers view-space XY from OpenGL NDC and a known view Z through the raster projection.
	 * This matches {@code mc_view_position} after the resolve's Metal Y conversion.
	 */
	static Vector3f viewFromNdc(
		final Matrix4f rasterProjection, final float ndcX, final float ndcY, final float viewZ
	) {
		Vector4f col0 = rasterProjection.getColumn(0, new Vector4f());
		Vector4f col1 = rasterProjection.getColumn(1, new Vector4f());
		Vector4f known = rasterProjection.getColumn(2, new Vector4f()).mul(viewZ)
			.add(rasterProjection.getColumn(3, new Vector4f()));
		float a11 = col0.x - ndcX * col0.w;
		float a12 = col1.x - ndcX * col1.w;
		float a21 = col0.y - ndcY * col0.w;
		float a22 = col1.y - ndcY * col1.w;
		float det = a11 * a22 - a12 * a21;
		if (Math.abs(det) < 1.0E-8F) {
			return new Vector3f(0.0F, 0.0F, viewZ);
		}
		float b1 = ndcX * known.w - known.x;
		float b2 = ndcY * known.w - known.y;
		return new Vector3f((b1 * a22 - a12 * b2) / det, (a11 * b2 - b1 * a21) / det, viewZ);
	}

	private static List<Vector3f> frustumCorners(
		final Matrix4f rasterProjection,
		final float near,
		final float far,
		final float tanHalfX,
		final float tanHalfY
	) {
		List<Vector3f> corners = new ArrayList<>(8);
		for (float distance : new float[]{near, far}) {
			float viewZ = -distance;
			for (int sx : new int[]{-1, 1}) {
				for (int sy : new int[]{-1, 1}) {
					Vector3f corner = viewFromNdc(rasterProjection, sx, sy, viewZ);
					if (corner.x == 0.0F && corner.y == 0.0F && Math.abs(viewZ) > 1.0E-4F) {
						corner.set(sx * distance * tanHalfX, sy * distance * tanHalfY, viewZ);
					}
					corners.add(corner);
				}
			}
		}
		return corners;
	}

	@Override
	public void close() {
		if (this.closed) return;
		this.closed = true;
		for (Optional<Substitution> substitution : this.substitutions.values()) {
			substitution.ifPresent(value -> this.device.forgetNativePipeline(value.pipeline()));
		}
		for (Optional<Substitution> substitution : this.localSubstitutions.values()) {
			substitution.ifPresent(value -> this.device.forgetNativePipeline(value.pipeline()));
		}
		this.substitutions.clear();
		this.localSubstitutions.clear();
		this.localShadowView.close();
		this.localShadowTexture.close();
		if (!this.declined.isEmpty()) {
			LOGGER.info("Shader pack '{}' leaves {} pipeline(s) outside the shadow pass: {}",
				this.packId, this.declined.size(), this.declined);
		}
		if (active == this) active = null;
	}
}
