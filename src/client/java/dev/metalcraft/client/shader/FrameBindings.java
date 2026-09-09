package dev.metalcraft.client.shader;

import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * Host-supplied scene and world-depth attachments plus the size last passed to
 * {@link ShaderPackRuntime#resize}.
 *
 * <p>{@code worldDepth} is the world-composition snapshot, not the live main depth attachment.
 * Hand and GUI writes to that attachment must not be supplied here.
 */
public record FrameBindings(
	MetalTexture scene,
	MetalTextureView sceneView,
	int width,
	int height,
	@Nullable MetalTexture worldDepth,
	@Nullable MetalTextureView worldDepthView,
	@Nullable Matrix4fc worldProjection,
	int worldDepthWidth,
	int worldDepthHeight,
	WorldComposition.Stage stage,
	ColorEncoding colorEncoding,
	dev.metalcraft.client.shader.water.UnderwaterFrameInputs underwater
) {
	/** Encoding is a producer contract, never inferred from a floating-point texture format. */
	public enum ColorEncoding { LEGACY_ENCODED, LINEAR_SRGB }

	public FrameBindings(final MetalTexture scene, final MetalTextureView sceneView, final int width, final int height,
		final @Nullable MetalTexture worldDepth, final @Nullable MetalTextureView worldDepthView,
		final @Nullable Matrix4fc worldProjection, final int worldDepthWidth, final int worldDepthHeight,
		final WorldComposition.Stage stage, final ColorEncoding encoding) {
		this(scene, sceneView, width, height, worldDepth, worldDepthView, worldProjection,
			worldDepthWidth, worldDepthHeight, stage, encoding,
			dev.metalcraft.client.shader.water.UnderwaterFrameInputs.NONE);
	}

	public FrameBindings withUnderwater(final dev.metalcraft.client.shader.water.UnderwaterFrameInputs inputs) {
		return new FrameBindings(this.scene, this.sceneView, this.width, this.height, this.worldDepth,
			this.worldDepthView, this.worldProjection, this.worldDepthWidth, this.worldDepthHeight,
			this.stage, this.colorEncoding, inputs);
	}

	public FrameBindings(final MetalTexture scene, final MetalTextureView sceneView, final int width, final int height,
		final @Nullable MetalTexture worldDepth, final @Nullable MetalTextureView worldDepthView,
		final @Nullable Matrix4fc worldProjection, final int worldDepthWidth, final int worldDepthHeight,
		final WorldComposition.Stage stage) {
		this(scene, sceneView, width, height, worldDepth, worldDepthView, worldProjection,
			worldDepthWidth, worldDepthHeight, stage, ColorEncoding.LEGACY_ENCODED);
	}

	public FrameBindings(
		final MetalTexture scene,
		final MetalTextureView sceneView,
		final int width,
		final int height
	) {
		this(scene, sceneView, width, height, null, null, null, 0, 0, WorldComposition.Stage.PRESENT);
	}

	public FrameBindings {
		if (underwater == null) throw new NullPointerException("underwater");
		if (scene == null) {
			throw new NullPointerException("scene");
		}
		if (sceneView == null) {
			throw new NullPointerException("sceneView");
		}
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Frame bindings require positive dimensions");
		}
		if (colorEncoding == null) throw new NullPointerException("colorEncoding");
		if (colorEncoding == ColorEncoding.LINEAR_SRGB && (stage != WorldComposition.PACK_POST
			|| scene.descriptor().format() != MetalTexture.Format.RGBA16_FLOAT)) {
			throw new IllegalArgumentException("Linear world color requires RGBA16_FLOAT at the world grade seam");
		}
		if (stage == null) {
			throw new NullPointerException("stage");
		}
		if (worldDepth == null) {
			if (worldDepthView != null) {
				throw new IllegalArgumentException("World depth view requires a world depth texture");
			}
			worldDepthWidth = 0;
			worldDepthHeight = 0;
			worldProjection = null;
		} else {
			if (worldDepthView == null) {
				throw new NullPointerException("worldDepthView");
			}
			if (worldDepthWidth <= 0) {
				worldDepthWidth = worldDepth.descriptor().width();
			}
			if (worldDepthHeight <= 0) {
				worldDepthHeight = worldDepth.descriptor().height();
			}
			if (worldProjection != null) {
				worldProjection = new Matrix4f(worldProjection);
			}
		}
	}
}
