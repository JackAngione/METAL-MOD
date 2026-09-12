package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodCompilerCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class LevelExtractorLodMixin {
    @Shadow private ClientLevel level;
    @Shadow private net.minecraft.client.SectionUpdateTracker sectionUpdateTracker;
    @Shadow @Final private net.minecraft.client.renderer.state.level.LevelRenderState levelRenderState;

    @Inject(method="extract",at=@At("TAIL"))
    private void metalcraft$recaptureDistant(net.minecraft.client.DeltaTracker delta,net.minecraft.client.Camera camera,
                                           float partialTick,CallbackInfo ci) {
        if (level!=null && sectionUpdateTracker!=null)
            dev.metalcraft.client.lod.LodDistantRecapture.extract(level,sectionUpdateTracker,levelRenderState);
    }
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void metalcraft$worldLod(@Nullable ClientLevel level, CallbackInfo ci) {
        dev.metalcraft.client.lod.LodDistantRenderer.worldChanged();
        if (LodCompilerCapture.ENABLED)
            LodCompilerCapture.REVISIONS.world(level == null ? "disconnected" : level.dimension().identifier().toString());
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void metalcraft$resetLod(CallbackInfo ci) {
        if (LodCompilerCapture.ENABLED) LodCompilerCapture.REVISIONS.resources();
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void metalcraft$dirtyLod(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
        dev.metalcraft.client.lod.LodDistantRenderer.dirty(x, y, z);
        if (LodCompilerCapture.capturing()) LodCompilerCapture.REVISIONS.dirty(x, y, z);
    }
}
