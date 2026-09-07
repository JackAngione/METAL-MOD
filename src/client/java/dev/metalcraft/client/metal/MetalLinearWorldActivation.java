package dev.metalcraft.client.metal;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.WorldGeometryAdapter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Live GameRenderer wrap for one HDR world frame. Mixins stay thin.
 *
 * <p>Fog clear RGB is still written as vanilla encoded fog color. {@code shared/color.metal}
 * has no host decoder, so linearizing that clear is remaining work rather than a second transfer.
 */
public final class MetalLinearWorldActivation {
	private static final Logger LOGGER = LogUtils.getLogger();
	/** Subset LinearWorldShaders can verify; other static pipelines fail preflight. */
	private static final Set<String> LINEAR_WORLD_SHADER_PATHS = Set.of(
		"core/terrain", "core/block", "core/entity", "core/particle", "core/rendertype_clouds",
		"core/glint", "core/position", "core/sky", "core/stars", "core/position_color",
		"core/position_tex_color", "core/position_tex", "core/rendertype_lightning",
		"core/rendertype_world_border", "core/rendertype_beacon_beam", "core/rendertype_crumbling",
		"core/rendertype_entity_shadow", "core/rendertype_lines", "core/rendertype_leash",
		"core/rendertype_end_portal", "core/text", "core/text_background", "core/debug_point",
		"core/item"
	);

	private static @Nullable List<RenderPipeline> cachedKnown;
	private static long cachedGeneration = Long.MIN_VALUE;

	private MetalLinearWorldActivation() {
	}

	/** Encoded grade path. Pass the live device so {@link Frame#grade()} still runs {@code gradeWorld}. */
	public static Frame encoded(
		final @Nullable MetalGpuDevice gpu,
		final GpuTextureView mainColor,
		final GpuTextureView mainDepth
	) {
		return new Frame(gpu, mainColor, mainDepth, null, false, null);
	}

	/**
	 * Live GameRenderer entry. HDR session begin remains gated: LINEAR_SRGB identity routing
	 * currently drops rebuilt terrain from the standard-world water identity fixture.
	 * GPU smoke calls {@link #begin} directly.
	 */
	public static Frame beginLive(
		final @Nullable MetalGpuDevice gpu,
		final GpuTextureView mainColor,
		final GpuTextureView mainDepth,
		final boolean fabulous
	) {
		return encoded(gpu, mainColor, mainDepth);
	}

	/**
	 * Begins a fail-closed HDR session and selects linear geometry when the token is non-null.
	 * {@code null} from {@link MetalGpuDevice#beginLinearWorld} keeps the encoded grade path.
	 */
	public static Frame begin(
		final MetalGpuDevice gpu,
		final GpuTextureView mainColor,
		final GpuTextureView mainDepth,
		final boolean fabulous,
		final Collection<RenderPipeline> knownPipelines
	) {
		if (gpu == null || mainColor == null || mainDepth == null || knownPipelines == null) {
			throw new NullPointerException("Linear world activation requires device, attachments and pipelines");
		}
		MetalLinearWorldSession session = null;
		if (!knownPipelines.isEmpty()) {
			try {
				session = gpu.beginLinearWorld(mainColor, mainDepth, fabulous, knownPipelines, List.of(), null);
			} catch (IllegalArgumentException error) {
				LOGGER.error("Linear world attachments rejected; keeping the encoded world path", error);
			}
		}
		boolean selectedLinear = false;
		WorldGeometryAdapter geometry = null;
		if (session != null) {
			try {
				ShaderPackRuntime runtime = gpu.shaderPackRuntime();
				if (runtime != null && runtime.isActive()) {
					runtime.resizeToScene(mainColor.getWidth(0), mainColor.getHeight(0));
					geometry = runtime.worldGeometry();
					if (geometry != null) {
						geometry.beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);
						selectedLinear = true;
					}
				}
			} catch (RuntimeException error) {
				session.close();
				session = null;
				geometry = null;
				selectedLinear = false;
				LOGGER.error("Linear geometry selection failed; keeping the encoded world path", error);
			}
		}
		return new Frame(gpu, mainColor, mainDepth, session, selectedLinear, geometry);
	}

	public static List<RenderPipeline> knownWorldPipelines(final MetalGpuDevice gpu) {
		long generation = gpu.shaderGeneration();
		if (cachedKnown != null && cachedGeneration == generation) {
			return cachedKnown;
		}
		List<RenderPipeline> known = new ArrayList<>();
		for (RenderPipeline pipeline : RenderPipelines.getStaticPipelines()) {
			if (!LINEAR_WORLD_SHADER_PATHS.contains(pipeline.getVertexShader().getPath())
				|| !LINEAR_WORLD_SHADER_PATHS.contains(pipeline.getFragmentShader().getPath())) {
				continue;
			}
			try {
				gpu.precompileLinearWorldPipeline(pipeline, null);
				known.add(pipeline);
			} catch (RuntimeException ignored) {
			}
		}
		cachedKnown = List.copyOf(known);
		cachedGeneration = generation;
		return cachedKnown;
	}

	/**
	 * One world-graph wrap. Close after {@code LevelRenderer.render} and before hand/HUD so
	 * identity routing cannot capture the encoded grade blit. {@link #grade()} then runs at the
	 * existing hand-depth-clear seam.
	 */
	public static final class Frame implements AutoCloseable {
		private final @Nullable MetalGpuDevice device;
		private final GpuTextureView encodedColor;
		private final GpuTextureView encodedDepth;
		private final @Nullable MetalLinearWorldSession session;
		private final boolean linear;
		private final boolean selectedLinearGeometry;
		private final @Nullable WorldGeometryAdapter geometry;
		private boolean closed;
		private boolean graded;

		Frame(
			final @Nullable MetalGpuDevice device,
			final GpuTextureView encodedColor,
			final GpuTextureView encodedDepth,
			final @Nullable MetalLinearWorldSession session,
			final boolean selectedLinearGeometry,
			final @Nullable WorldGeometryAdapter geometry
		) {
			this.device = device;
			this.encodedColor = encodedColor;
			this.encodedDepth = encodedDepth;
			this.session = session;
			this.linear = session != null;
			this.selectedLinearGeometry = selectedLinearGeometry;
			this.geometry = geometry;
		}

		public boolean sessionActive() {
			return this.session != null;
		}

		boolean selectedLinearGeometry() {
			return this.selectedLinearGeometry;
		}

		@Nullable MetalLinearWorldSession session() {
			return this.session;
		}

		/** Grades once into the encoded main color. Closes the session first if it is still open. */
		public void grade() {
			this.close();
			if (this.graded) return;
			this.graded = true;
			if (this.device == null) return;
			if (this.linear) {
				try {
					this.device.gradeLinearWorld(this.encodedColor);
				} catch (RuntimeException error) {
					ShaderPackRuntime runtime = this.device.shaderPackRuntime();
					if (runtime != null) {
						runtime.markFailed("World grading failed: " + error.getMessage(), error);
					}
					LOGGER.error("Linear world grading failed; preserving the world scene", error);
				}
				return;
			}
			this.device.gradeWorld(this.encodedColor, this.encodedDepth);
		}

		@Override
		public void close() {
			if (this.closed) return;
			this.closed = true;
			if (this.session != null) this.session.close();
			if (this.selectedLinearGeometry && this.geometry != null) {
				try {
					this.geometry.beginFrame(FrameBindings.ColorEncoding.LEGACY_ENCODED);
				} catch (RuntimeException error) {
					LOGGER.error("Could not restore encoded world geometry after a linear session", error);
				}
			}
		}
	}
}
