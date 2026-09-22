package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import dev.metalcraft.client.lod.LodDrawSource;
import dev.metalcraft.client.lod.LodLoadedRenderer;
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
        dev.metalcraft.client.horizon.HorizonRenderer.prepare(levelRenderState.cameraRenderState);
        LodLoadedRenderer.prepare(((LevelRenderer)(Object)this).visibleSections(), levelRenderState.cameraRenderState);
    }

    @Inject(method = "prepareChunkRenders", at = @At("RETURN"), cancellable = true)
    private void metalcraft$distantLod(Matrix4fc view, CallbackInfoReturnable<ChunkSectionsToRender> cir) {
        var result=dev.metalcraft.client.lod.LodDistantRenderer.append(cir.getReturnValue(),levelRenderState.cameraRenderState);
        cir.setReturnValue(dev.metalcraft.client.horizon.HorizonRenderer.append(result,levelRenderState.cameraRenderState));
    }

    @ModifyExpressionValue(method="prepareChunkRenders",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/chunk/SectionMesh;getSectionDraw(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lnet/minecraft/client/renderer/chunk/SectionMesh$SectionDraw;"))
    private SectionMesh.SectionDraw metalcraft$singleTerrainOwner(SectionMesh.SectionDraw draw,@Local RenderSection section) {
        return dev.metalcraft.client.horizon.HorizonRenderer.covers(section.getSectionNode())?null:draw;
    }

    // Annotate the constructed draw directly. Wrapping its eight constructor arguments
    // created varargs arrays/boxed integers for every section/layer on every frame.
    @ModifyExpressionValue(method = "prepareChunkRenders", at = @At(value = "NEW", target = "com/mojang/blaze3d/systems/RenderPass$Draw"))
    private RenderPass.Draw<GpuBufferSlice[]> metalcraft$attachLod(RenderPass.Draw<GpuBufferSlice[]> draw,
            @Local SectionMesh sectionMesh,
            @Local ChunkSectionLayer layer, @Local RenderSection section) {
        if(layer==ChunkSectionLayer.TRANSLUCENT && dev.metalcraft.client.horizon.NativeHorizon.enabled()) {
            var pos=levelRenderState.cameraRenderState.pos;long node=section.getSectionNode();
            double dx=net.minecraft.core.SectionPos.x(node)*16.0+8-pos.x,dy=net.minecraft.core.SectionPos.y(node)*16.0+8-pos.y,
                    dz=net.minecraft.core.SectionPos.z(node)*16.0+8-pos.z;
            ((LodDrawSource)(Object)draw).metalcraft$sortDistance(dx*dx+dy*dy+dz*dz);
        }
        if (layer == ChunkSectionLayer.SOLID && sectionMesh instanceof dev.metalcraft.client.chunk.NativeLodState state)
            ((LodDrawSource)(Object)draw).metalcraft$textureMip(dev.metalcraft.client.chunk.NativeTerrainLod.textureMip(
                    section.getSectionNode(),state.metalcraft$cellSize()));
        if (!LodLoadedRenderer.trackingDraws()) return draw;
        ((LodDrawSource)(Object)draw).metalcraft$terrain(LodLoadedRenderer.distant(section, levelRenderState.cameraRenderState));
        if (layer == ChunkSectionLayer.SOLID && draw.indexBuffer() == null && draw.firstIndex() == 0)
            ((LodDrawSource)(Object)draw).metalcraft$lodDraw(LodLoadedRenderer.selected(section, sectionMesh));
        return draw;
    }
}
