package dev.metalcraft.client.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
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
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.ShaderDefines;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Replays the prepared world once into a texel-stabilised four-layer cascaded shadow map. */
public final class MetalWorldShadow implements AutoCloseable {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int CASCADES = 4;
	private static final int UNIFORM_BYTES = 320;
	private static final String MATRICES_UNIFORM = "MetalCraftShadow";
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
	private final DoubleSupplier distance;
	private final DoubleSupplier normalOffset;
	private final MetalGpuBuffer matrices;
	private final Map<RenderPipeline, Optional<Substitution>> substitutions = new IdentityHashMap<>();
	private final FrustumIntersection[] cascadeFrusta = new FrustumIntersection[CASCADES];
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
		final DoubleSupplier normalOffset
	) {
		this.device = device;
		this.pipelineCache = pipelineCache;
		this.packId = packId;
		this.passId = passId;
		this.source = source;
		this.shadowView = shadowView;
		this.distance = distance;
		this.normalOffset = normalOffset;
		this.matrices = device.createBuffer(
			() -> "MetalCraft shadow cascades", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, UNIFORM_BYTES
		);
	}

	static void setActive(final @Nullable MetalWorldShadow binding) {
		active = binding;
	}

	private static @Nullable MetalWorldShadow active() {
		MetalWorldShadow current = active;
		return current == null || current.closed ? null : current;
	}

	public static boolean isAvailable() {
		return active() != null;
	}

	boolean owns(final GpuTexture texture) {
		return this.shadowView.texture() == texture;
	}

	@Nullable RenderPipeline standInForTesting(final RenderPipeline pipeline) {
		return this.substitutionFor(pipeline).map(Substitution::pipeline).orElse(null);
	}

	MetalBuffer matricesForTesting() {
		return this.matrices.metal();
	}

	void useIdentityCascadesForTesting() {
		this.writeTestingCascades(false);
	}

	void useResolveCascadesForTesting() {
		this.writeTestingCascades(true);
	}

	private void writeTestingCascades(final boolean flipViewDepth) {
		try (var mapping = this.matrices.map(0L, UNIFORM_BYTES, false, true)) {
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
			bytes.putFloat(300, 1.0F);
		}
	}

	void prepareForTesting(final CameraRenderState camera, final float sunAngle) {
		this.prepare(camera, sunAngle);
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

	/** True only while the prepared geometry is being replayed into the shadow map. */
	public static boolean isRendering() {
		MetalWorldShadow current = active();
		return current != null && current.rendering;
	}

	/** The backend uses four instances so the vertex stage can select one array layer per cascade. */
	static boolean isShadowPipeline(final RenderPipeline pipeline) {
		return pipeline != null && String.valueOf(pipeline.getLocation()).startsWith("metalcraft:shadow/");
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
	public static void prepareCascades(final CameraRenderState camera, final float sunAngle) {
		MetalWorldShadow current = active();
		if (current != null) {
			current.prepare(camera, sunAngle);
		}
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
		return false;
	}

	/** Runs once immediately before the main opaque terrain group. */
	public static void renderBeforeMain(final ChunkSectionLayerGroup group, final GpuSampler sampler) {
		MetalWorldShadow current = active();
		if (current == null || current.rendering || group != ChunkSectionLayerGroup.OPAQUE
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
		current.rendering = true;
		current.cleared = false;
		current.shadowPass = null;
		try {
			terrain.renderGroup(ChunkSectionLayerGroup.OPAQUE, sampler);
			if (features != null) {
				features.executeSolid();
			}
		} finally {
			current.shadowPass = null;
			current.rendering = false;
		}
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
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "Shadow " + label.get())
			.withDepthAttachment(current.shadowView, current.cleared ? OptionalDouble.empty() : OptionalDouble.of(1.0))
			.withRenderArea(new RenderPass.RenderArea(
				0, 0, current.shadowView.getWidth(0), current.shadowView.getHeight(0)
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
		pass.setUniform(MATRICES_UNIFORM, current.matrices);
		return replacement.pipeline();
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
		MetalWorldGeometry.Program program = MetalWorldGeometry.programFor(pipeline);
		if (program == null || !MetalWorldGeometry.hasVertexElements(pipeline, program)
			|| MetalWorldGeometry.isBlended(pipeline)) {
			return Optional.empty();
		}
		Set<String> defines = MetalWorldGeometry.declaredDefines(pipeline);
		if (!program.implementedDefines().containsAll(defines)) {
			return Optional.empty();
		}
		Map<String, Integer> slots = shadowResourceSlots(pipeline);
		String transforms = program == MetalWorldGeometry.Program.TERRAIN ? "ChunkSection" : "DynamicTransforms";
		if (!slots.containsKey(transforms) || !slots.containsKey("Sampler0")
			|| program == MetalWorldGeometry.Program.TERRAIN && !slots.containsKey("Globals")) {
			return Optional.empty();
		}
		try {
			RenderPipeline standIn = this.standIn(pipeline, program);
			this.device.registerNativePipeline(standIn, new MetalGpuDevice.NativeProgram(
				this.pipelineCache,
				this.programSource(pipeline, program, slots, transforms),
				program.entryPoint(this.passId, "vertex"),
				program.entryPoint(this.passId, "fragment")
			));
			return Optional.of(new Substitution(standIn));
		} catch (RuntimeException error) {
			LOGGER.error("Shader pack '{}' could not build shadow pipeline for {}", this.packId, pipeline.getLocation(), error);
			return Optional.empty();
		}
	}

	private RenderPipeline standIn(final RenderPipeline pipeline, final MetalWorldGeometry.Program program) {
		List<BindGroupLayout> layouts = new ArrayList<>(pipeline.getBindGroupLayouts());
		layouts.add(BindGroupLayout.builder().withUniform(MATRICES_UNIFORM, UniformType.UNIFORM_BUFFER).build());
		return new DepthOnlyPipeline(
			Identifier.parse("metalcraft:shadow/"
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
		final String transforms
	) {
		StringBuilder preamble = new StringBuilder();
		preamble.append("#define MC_PASS_").append(this.passId.toUpperCase(Locale.ROOT)).append(" 1\n");
		preamble.append("#define MC_PROGRAM_").append(program.name()).append(" 1\n");
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

	private static Map<String, Integer> shadowResourceSlots(final RenderPipeline pipeline) {
		Map<String, Integer> slots = new LinkedHashMap<>();
		for (BindGroupLayout.UniformDescription uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
			slots.put(uniform.name(), slots.size());
		}
		slots.put(MATRICES_UNIFORM, slots.size());
		for (String sampler : BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts())) {
			slots.put(sampler, slots.size());
		}
		return slots;
	}

	private void prepare(final CameraRenderState camera, final float sunAngle) {
		this.cameraX = camera.pos.x;
		this.cameraY = camera.pos.y;
		this.cameraZ = camera.pos.z;
		float near = 0.05F;
		float far = Math.max(16.0F, (float)this.distance.getAsDouble());
		float tanHalfX = 1.0F / Math.abs(camera.projectionMatrix.m00());
		float tanHalfY = 1.0F / Math.abs(camera.projectionMatrix.m11());
		float[] splits = new float[CASCADES];
		for (int index = 0; index < CASCADES; index++) {
			float fraction = (index + 1.0F) / CASCADES;
			float logarithmic = near * (float)Math.pow(far / near, fraction);
			float linear = near + (far - near) * fraction;
			splits[index] = logarithmic * 0.65F + linear * 0.35F;
		}

		float solarElevation = (float)Math.cos(sunAngle);
		Vector3f lightWorld = new Vector3f(0.0F, solarElevation, (float)Math.sin(sunAngle)).normalize();
		// Below the horizon the moon supplies the opposing directional light and shadow view.
		if (solarElevation < 0.0F) {
			lightWorld.negate();
		}
		Vector3f lightViewDirection = camera.viewRotationMatrix.transformDirection(lightWorld, new Vector3f()).normalize();
		Matrix4f cameraView = new Matrix4f(camera.viewRotationMatrix);
		this.cameraViewForTesting.set(cameraView);
		Matrix4f inverseCameraView = cameraView.invert(new Matrix4f());
		Matrix4f viewToWorld = new Matrix4f().translation(
			(float)this.cameraX, (float)this.cameraY, (float)this.cameraZ
		).mul(inverseCameraView);
		Matrix4f[] matricesToWrite = new Matrix4f[CASCADES];
		float cascadeNear = near;
		for (int index = 0; index < CASCADES; index++) {
			float cascadeFar = splits[index];
			List<Vector3f> corners = frustumCorners(cascadeNear, cascadeFar, tanHalfX, tanHalfY);
			for (Vector3f corner : corners) viewToWorld.transformPosition(corner);
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
			float rightCoordinate = center.dot(right);
			float upCoordinate = center.dot(lightUp);
			center.fma(Math.round(rightCoordinate / texel) * texel - rightCoordinate, right);
			center.fma(Math.round(upCoordinate / texel) * texel - upCoordinate, lightUp);

			Vector3f eye = new Vector3f(center).fma(2.0F * radius, lightWorld);
			Matrix4f lightView = new Matrix4f().setLookAt(eye, center, lightUp);
			Matrix4f lightProjection = new Matrix4f().setOrtho(
				-radius, radius, -radius, radius, 0.0F, 4.0F * radius, true
			);
			Matrix4f shadowFromWorld = lightProjection.mul(lightView, new Matrix4f());
			Matrix4f shadowFromView = shadowFromWorld.mul(viewToWorld, new Matrix4f());
			matricesToWrite[index] = shadowFromView;
			this.cascadeMatrices[index] = new Matrix4f(shadowFromView);
			this.cascadeFrusta[index] = new FrustumIntersection(
				shadowFromView.mul(cameraView, new Matrix4f()), true
			);
			cascadeNear = cascadeFar;
		}

		try (var mapping = this.matrices.map(0L, UNIFORM_BYTES, false, true)) {
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
			bytes.putFloat(300, solarElevation);
		}
	}

	private static List<Vector3f> frustumCorners(
		final float near, final float far, final float tanHalfX, final float tanHalfY
	) {
		List<Vector3f> corners = new ArrayList<>(8);
		for (float distance : new float[]{near, far}) {
			float x = distance * tanHalfX;
			float y = distance * tanHalfY;
			for (int sx : new int[]{-1, 1}) {
				for (int sy : new int[]{-1, 1}) {
					corners.add(new Vector3f(sx * x, sy * y, -distance));
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
		this.substitutions.clear();
		this.matrices.close();
		if (!this.declined.isEmpty()) {
			LOGGER.info("Shader pack '{}' leaves {} pipeline(s) outside the shadow pass: {}",
				this.packId, this.declined.size(), this.declined);
		}
		if (active == this) active = null;
	}
}
