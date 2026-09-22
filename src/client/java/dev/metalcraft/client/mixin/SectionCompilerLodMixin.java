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
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;

/** Reduces distant native solid output on its compiler worker, before ordinary upload/release. */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerLodMixin {
    @Shadow @Final private net.minecraft.client.renderer.block.BlockStateModelSet blockModelSet;
    @Shadow @Final private net.minecraft.client.color.block.BlockColors blockColors;
    @Shadow @Final private net.minecraft.client.renderer.block.FluidStateModelSet fluidModelSet;

    @WrapMethod(method = "compile")
    private SectionCompiler.Results metalcraft$captureLod(SectionPos section, RenderSectionRegion region,
            VertexSorting sorting, SectionBufferBuilderPack builders, Operation<SectionCompiler.Results> original) {
        int tier = ((dev.metalcraft.client.chunk.NativeLodState)region).metalcraft$cellSize();
        SectionCompiler.Results results;
        try {
            results = tier > 1 ? dev.metalcraft.client.chunk.NativeShellCompiler.compile(section, region,
                    builders, blockModelSet, fluidModelSet, blockColors, sorting, tier)
                    : original.call(section, region, sorting, builders);
        } catch (RuntimeException | Error error) {
            // Vanilla treats any NPE as an exhausted buffer pool and retries while
            // retaining the acquired pack. Surface real compiler failures instead.
            if (tier > 1 && error instanceof NullPointerException)
                throw new IllegalStateException("Building terrain shell at " + section, error);
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
