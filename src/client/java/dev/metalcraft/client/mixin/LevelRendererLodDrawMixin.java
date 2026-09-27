package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import dev.metalcraft.client.lod.LodDrawSource;
import dev.metalcraft.client.lod.LodSystem;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.core.SectionPos;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds distant terrain to the chunk draw lists. Native sections are never replaced: distant nodes leave out chunks native rendering covers. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererLodDrawMixin {
    @Shadow @Final private LevelRenderState levelRenderState;

    @Inject(method = "prepareChunkRenders", at = @At("HEAD"))
    private void metalcraft$prepareLod(Matrix4fc view, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        LodSystem.prepare(levelRenderState.cameraRenderState);
    }

    @Inject(method = "prepareChunkRenders", at = @At("RETURN"), cancellable = true)
    private void metalcraft$appendLod(Matrix4fc view, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        cir.setReturnValue(LodSystem.append(cir.getReturnValue(), levelRenderState.cameraRenderState));
    }

    // Distant water is merged into one distance-sorted translucent list with native water.
    // Annotate the constructed draw directly rather than wrapping its eight constructor arguments.
    @ModifyExpressionValue(method = "prepareChunkRenders", at = @At(value = "NEW", target = "com/mojang/blaze3d/systems/RenderPass$Draw"))
    private RenderPass.Draw<GpuBufferSlice[]> metalcraft$sortDistance(RenderPass.Draw<GpuBufferSlice[]> draw,
            @Local ChunkSectionLayer layer, @Local RenderSection section) {
        if (layer == ChunkSectionLayer.TRANSLUCENT && LodSystem.active()) {
            var pos = levelRenderState.cameraRenderState.pos;
            long node = section.getSectionNode();
            double dx = SectionPos.x(node) * 16.0 + 8 - pos.x, dy = SectionPos.y(node) * 16.0 + 8 - pos.y, dz = SectionPos.z(node) * 16.0 + 8 - pos.z;
            ((LodDrawSource)(Object)draw).metalcraft$sortDistance(dx * dx + dy * dy + dz * dz);
        }
        return draw;
    }
}
