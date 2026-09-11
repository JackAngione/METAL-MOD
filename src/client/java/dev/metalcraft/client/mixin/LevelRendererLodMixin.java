package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodCompilerCapture;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererLodMixin {
    @Inject(method = "invalidateCompiledGeometry", at = @At("HEAD"))
    private void metalcraft$invalidateLod(CallbackInfo ci) {
        if (LodCompilerCapture.ENABLED) LodCompilerCapture.REVISIONS.resources();
    }
}
