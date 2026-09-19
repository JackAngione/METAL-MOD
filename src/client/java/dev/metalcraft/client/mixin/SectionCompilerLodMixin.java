package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.metalcraft.client.lod.LodCompilerCapture;
import dev.metalcraft.client.lod.LodCaptureOwner;
import dev.metalcraft.client.lod.LodRegionSource;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;

/** Reduces distant native solid output on its compiler worker, before ordinary upload/release. */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerLodMixin {
    @WrapMethod(method = "compile")
    private SectionCompiler.Results metalcraft$captureLod(SectionPos section, RenderSectionRegion region,
            VertexSorting sorting, SectionBufferBuilderPack builders, Operation<SectionCompiler.Results> original) {
        SectionCompiler.Results results = original.call(section, region, sorting, builders);
        try {
            dev.metalcraft.client.chunk.NativeTerrainLod.compile(results, builders,
                    ((dev.metalcraft.client.chunk.NativeLodState)region).metalcraft$cellSize());
        } catch (RuntimeException | Error error) {
            results.release();
            throw error;
        }
        if (LodCompilerCapture.capturing()) {
            try {
                dev.metalcraft.client.lod.LodDistantRenderer.capture(section, results,
                        ((LodRegionSource)region).metalcraft$lodTicket());
                var candidate = LodCompilerCapture.capture(section.asLong(), results.renderedLayers.get(ChunkSectionLayer.SOLID),
                        results.renderedLayers.keySet().stream().allMatch(layer -> layer == ChunkSectionLayer.SOLID),
                        ((LodRegionSource)region).metalcraft$lodTicket());
                ((LodCaptureOwner)(Object)results).metalcraft$lodCandidate(candidate);
            } catch (RuntimeException | Error error) {
                results.release();
                throw error;
            }
        }
        return results;
    }
}
