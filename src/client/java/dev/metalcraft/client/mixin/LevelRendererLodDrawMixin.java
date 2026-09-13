package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import dev.metalcraft.client.lod.LodDrawSource;
import dev.metalcraft.client.lod.LodLoadedRenderer;
import java.util.function.BiConsumer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelRenderer.class)
abstract class LevelRendererLodDrawMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "prepareChunkRenders", at = @At("HEAD"))
    private void metalcraft$prepareLod(Matrix4fc view, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        LodLoadedRenderer.prepare(((LevelRenderer)(Object)this).visibleSections(), levelRenderState.cameraRenderState);
    }

    @Inject(method = "prepareChunkRenders", at = @At("RETURN"), cancellable = true)
    private void metalcraft$distantLod(Matrix4fc view, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        cir.setReturnValue(dev.metalcraft.client.lod.LodDistantRenderer.append(cir.getReturnValue(),levelRenderState.cameraRenderState));
    }

    @WrapOperation(method = "prepareChunkRenders", at = @At(value = "NEW", target = "com/mojang/blaze3d/systems/RenderPass$Draw"))
    private RenderPass.Draw<GpuBufferSlice[]> metalcraft$attachLod(int slot, GpuBuffer vertices,
            GpuBuffer indices, IndexType indexType, int firstIndex, int indexCount, int baseVertex,
            BiConsumer<GpuBufferSlice[], RenderPass.UniformUploader> uploader,
            Operation<RenderPass.Draw<GpuBufferSlice[]>> original, @Local SectionMesh sectionMesh,
            @Local ChunkSectionLayer layer, @Local RenderSection section) {
        var draw = original.call(slot, vertices, indices, indexType, firstIndex, indexCount, baseVertex, uploader);
        if (!LodLoadedRenderer.trackingDraws()) return draw;
        ((LodDrawSource)(Object)draw).metalcraft$terrain(LodLoadedRenderer.distant(section, levelRenderState.cameraRenderState));
        if (layer == ChunkSectionLayer.SOLID && indices == null && firstIndex == 0)
            ((LodDrawSource)(Object)draw).metalcraft$lodDraw(LodLoadedRenderer.selected(section, sectionMesh));
        return draw;
    }
}
