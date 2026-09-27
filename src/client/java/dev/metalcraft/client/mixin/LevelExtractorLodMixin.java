package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodSystem;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Level lifecycle and block edits for distant terrain. */
@Mixin(LevelExtractor.class)
abstract class LevelExtractorLodMixin {
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void metalcraft$levelChanged(@Nullable ClientLevel level, CallbackInfo ci) {
        LodSystem.levelChanged();
    }

    @Inject(method = "allChanged", at = @At("HEAD"))
    private void metalcraft$nativeReset(CallbackInfo ci) {
        LodSystem.nativeReset();
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void metalcraft$sectionDirty(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
        LodSystem.sectionDirty(x, z);
    }
}
