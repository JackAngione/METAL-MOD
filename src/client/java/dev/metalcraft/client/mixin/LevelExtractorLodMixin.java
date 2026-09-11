package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodCompilerCapture;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class LevelExtractorLodMixin {
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void metalcraft$worldLod(@Nullable ClientLevel level, CallbackInfo ci) {
        if (LodCompilerCapture.ENABLED)
            LodCompilerCapture.REVISIONS.world(level == null ? "disconnected" : level.dimension().identifier().toString());
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void metalcraft$resetLod(CallbackInfo ci) {
        if (LodCompilerCapture.ENABLED) LodCompilerCapture.REVISIONS.resources();
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void metalcraft$dirtyLod(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
        if (LodCompilerCapture.capturing()) LodCompilerCapture.REVISIONS.dirty(x, y, z);
    }
}
