package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import dev.metalcraft.client.shader.water.WaterDrawSource;
import dev.metalcraft.client.shader.water.WaterMeshSource;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The metadata follows each original draw through vanilla's list reversal and index resorting. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererWaterDrawMixin {
	@WrapOperation(method = "prepareChunkRenders", at = @At(value = "NEW", target = "com/mojang/blaze3d/systems/RenderPass$Draw"))
	private RenderPass.Draw<GpuBufferSlice[]> metalcraft$attachWater(final int slot, final GpuBuffer vertices,
		final GpuBuffer indices, final IndexType indexType, final int firstIndex, final int indexCount,
		final int baseVertex, final BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> uploader,
		final Operation<RenderPass.Draw<GpuBufferSlice[]>> original,
		@Local final SectionMesh sectionMesh, @Local final ChunkSectionLayer layer) {
		RenderPass.Draw<GpuBufferSlice[]> draw = original.call(slot, vertices, indices, indexType, firstIndex, indexCount, baseVertex, uploader);
		if (layer == ChunkSectionLayer.TRANSLUCENT && sectionMesh instanceof WaterMeshSource source) {
			((WaterDrawSource)(Object)draw).metalcraft$waterMesh(source.metalcraft$waterMesh(layer));
		}
		return draw;
	}
}
