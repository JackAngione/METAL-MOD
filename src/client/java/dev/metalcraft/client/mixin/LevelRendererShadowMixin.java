package dev.metalcraft.client.mixin;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import dev.metalcraft.client.metal.MetalWorldShadow;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prepares a second terrain list from the cascades' union frustum and retains feature buffers. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererShadowMixin {
	@Shadow @Final private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;
	@Shadow @Final private LevelRenderState levelRenderState;
	@Shadow private @Nullable ViewArea viewArea;

	@Redirect(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame("
				+ "Lnet/minecraft/client/renderer/SubmitNodeStorage;)"
				+ "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"
		)
	)
	private FeatureRenderDispatcher.PreparedFrame metalcraft$captureShadowFeatures(
		final FeatureRenderDispatcher dispatcher,
		final SubmitNodeStorage storage
	) {
		return MetalWorldShadow.captureFeatures(dispatcher.prepareFrame(storage));
	}

	@Redirect(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRenders("
				+ "Lorg/joml/Matrix4fc;)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
		)
	)
	private ChunkSectionsToRender metalcraft$prepareShadowTerrain(
		final LevelRenderer renderer,
		final Matrix4fc modelView
	) {
		if (!MetalWorldShadow.isAvailable() || this.viewArea == null) {
			return renderer.prepareChunkRenders(modelView);
		}
		if (!MetalWorldShadow.prepareCascades(
			this.levelRenderState.cameraRenderState, this.levelRenderState.skyRenderState
		)) {
			return renderer.prepareChunkRenders(modelView);
		}
		List<SectionRenderDispatcher.RenderSection> cameraVisible = new ArrayList<>(this.visibleSections);
		this.visibleSections.clear();
		((ViewAreaAccessor)this.viewArea).metalcraft$sections().forEach(section -> {
			if (MetalWorldShadow.isSectionVisible(section)) {
				this.visibleSections.add(section);
			}
		});
		ChunkSectionsToRender shadowTerrain;
		try {
			shadowTerrain = renderer.prepareChunkRenders(modelView);
		} finally {
			this.visibleSections.clear();
			this.visibleSections.addAll(cameraVisible);
		}
		ChunkSectionsToRender mainTerrain = renderer.prepareChunkRenders(modelView);
		MetalWorldShadow.setPreparedTerrain(shadowTerrain);
		return mainTerrain;
	}
}
