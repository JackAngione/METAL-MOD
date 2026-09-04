package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.textures.GpuSampler;
import dev.metalcraft.client.shader.world.WorldTerrainShadows;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Runs terrain shadows in the main graph pass, immediately before its opaque terrain draws. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererShadowMixin {
	@Shadow @Final private LevelRenderState levelRenderState;
	@Shadow private ViewArea viewArea;

	@Redirect(method = "lambda$addMainPass$0", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup("
			+ "Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V"))
	private void metalcraft$renderShadowTerrain(final ChunkSectionsToRender terrain,
		final ChunkSectionLayerGroup group, final GpuSampler sampler) {
		if (group == ChunkSectionLayerGroup.OPAQUE && this.viewArea != null) {
			WorldTerrainShadows.render((LevelRenderer)(Object)this, this.levelRenderState.cameraRenderState,
				this.levelRenderState.skyRenderState, ((ViewAreaAccessor)this.viewArea).metalcraft$sections(), sampler);
		}
		terrain.renderGroup(group, sampler);
	}
}
