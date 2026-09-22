package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import dev.metalcraft.client.shader.water.WaterDrawSource;
import dev.metalcraft.client.shader.water.WaterMeshSource;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The metadata follows each original draw through vanilla's list reversal and index resorting. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererWaterDrawMixin {
	@ModifyExpressionValue(method = "prepareChunkRenders", at = @At(value = "NEW", target = "com/mojang/blaze3d/systems/RenderPass$Draw"))
	private RenderPass.Draw<GpuBufferSlice[]> metalcraft$attachWater(final RenderPass.Draw<GpuBufferSlice[]> draw,
		@Local final SectionMesh sectionMesh, @Local final ChunkSectionLayer layer) {
		if (layer == ChunkSectionLayer.TRANSLUCENT && sectionMesh instanceof WaterMeshSource source) {
			((WaterDrawSource)(Object)draw).metalcraft$waterMesh(source.metalcraft$waterMesh(layer));
		}
		return draw;
	}
}
