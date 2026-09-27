package dev.metalcraft.client.shader;

import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import java.util.List;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

/**
 * World-only pack insertion relative to Minecraft 26.2's frame graph.
 *
 * <p>Live grading runs before the hand-depth clear in {@code GameRenderer.renderLevel}.
 * A linear world session wraps {@code LevelRenderer.render} and grades with
 * {@code gradeLinearWorld} into the encoded main color at that same seam; otherwise the
 * encoded {@code gradeWorld} path remains. Presentation copies the completed scene without
 * running the pack again. Packs whose executable passes read depth receive a stored snapshot
 * taken before hand and HUD can overwrite main depth; other packs omit the snapshot.
 */
public final class WorldComposition {
	/**
	 * After the world graph (opaque exports, forward/translucent composition, Fabulous, particles,
	 * clouds, weather, outline generation/filtering) and before hand, underwater overlay,
	 * final outline composition, spectator chains, and HUD. The final outline blit remains
	 * an encoded overlay after grading; its intermediate is not a linear world producer.
	 */
	public static final Stage PACK_POST = Stage.WORLD_GRADE_AA;

	/**
	 * 26.2 sites that finish before {@link #PACK_POST}. Audited against mapped
	 * {@code LevelRenderer} / {@code FeatureRenderDispatcher.PreparedFrame}.
	 */
	public static final List<String> WORLD_STAGE_SITES = List.of(
		"LevelRenderer.addSkyPass",
		"LevelRenderer.addMainPass / FeatureRenderDispatcher.PreparedFrame.executeSolid",
		"PreparedFeatureFrameMixin → WorldGeometryAdapter.resolveOpaque at executeTranslucent HEAD",
		"FeatureRenderDispatcher.PreparedFrame.executeTranslucent / executeTranslucentAfterTerrain",
		"LevelRenderer.getTransparencyChain (Fabulous TRANSPARENCY_POST_CHAIN_ID: translucent, itemEntity, particles, weather, clouds targets)",
		"LevelRenderer.addCloudsPass",
		"LevelRenderer.addWeatherPass",
		"LevelRenderer.addAlwaysOnTopPass",
		"LevelRenderer.render / ENTITY_OUTLINE_POST_CHAIN_ID (outline generation/filtering only)"
	);

	/**
	 * 26.2 sites that run after world composition. Hand and GUI write the main depth attachment;
	 * they must not be sampled as world depth.
	 */
	public static final List<String> AFTER_WORLD_SITES = List.of(
		"GameRenderer.renderItemInHand",
		"ScreenEffectRenderer.submitWater / submitFire (underwater and fire overlays)",
		"GameRenderer.render → LevelRenderer.doEntityOutline (encoded final outline blit)",
		"GameRenderer.checkEntityPostEffect / postEffectId (spectator)",
		"GuiRenderer (HUD / 2D GUI)"
	);

	public enum Stage {
		OPAQUE_EXPORTS,
		FORWARD_TRANSLUCENT,
		WORLD_GRADE_AA,
		HUD,
		PRESENT
	}

	private WorldComposition() {
	}

	public static boolean excludesHud(final Stage stage) {
		return stage == PACK_POST;
	}

	public static FrameBindings present(
		final MetalTexture scene,
		final MetalTextureView sceneView,
		final int width,
		final int height
	) {
		return new FrameBindings(scene, sceneView, width, height);
	}

	public static FrameBindings world(
		final MetalTexture scene,
		final MetalTextureView sceneView,
		final int width,
		final int height,
		final @Nullable MetalTexture worldDepth,
		final @Nullable MetalTextureView worldDepthView,
		final @Nullable Matrix4fc worldProjection
	) {
		return world(scene, sceneView, width, height, worldDepth, worldDepthView, worldProjection,
			FrameBindings.ColorEncoding.LEGACY_ENCODED);
	}

	public static FrameBindings world(final MetalTexture scene, final MetalTextureView sceneView,
		final int width, final int height, final @Nullable MetalTexture worldDepth, final @Nullable MetalTextureView worldDepthView,
		final @Nullable Matrix4fc worldProjection, final FrameBindings.ColorEncoding colorEncoding) {
		return new FrameBindings(
			scene,
			sceneView,
			width,
			height,
			worldDepth,
			worldDepthView,
			worldProjection,
			worldDepth == null ? 0 : worldDepth.descriptor().width(),
			worldDepth == null ? 0 : worldDepth.descriptor().height(),
			PACK_POST,
			colorEncoding
		);
	}
}
