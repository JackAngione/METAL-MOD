package dev.metalcraft.client.mixin;

import dev.metalcraft.client.shader.world.WorldLocalLighting;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelExtractor.class)
abstract class LevelExtractorLocalLightingMixin {
    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
    private void metalcraft$dirtyLocalLight(int x, int y, int z, boolean playerChanged, CallbackInfo ci) {
        WorldLocalLighting.dirty(x, y, z);
    }

    @Inject(method = {"setLevel", "allChanged"}, at = @At("HEAD"))
    private void metalcraft$resetLocalLight(CallbackInfo ci) {
        WorldLocalLighting.reset();
        dev.metalcraft.client.shader.world.WorldTerrainShadows.clearWorldCache();
    }
}
