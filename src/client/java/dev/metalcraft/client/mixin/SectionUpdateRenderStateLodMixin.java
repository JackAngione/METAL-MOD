package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodCompilerCapture;
import dev.metalcraft.client.lod.LodRegionSource;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SectionUpdateRenderState.class)
abstract class SectionUpdateRenderStateLodMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void metalcraft$stampLod(long section, boolean playerChanged, RenderSectionRegion region, CallbackInfo ci) {
        if (LodCompilerCapture.ENABLED && region != null) {
            ((LodRegionSource)region).metalcraft$lodTicket(LodCompilerCapture.REVISIONS.capture(
                    SectionPos.x(section), SectionPos.y(section), SectionPos.z(section)));
        }
    }
}
