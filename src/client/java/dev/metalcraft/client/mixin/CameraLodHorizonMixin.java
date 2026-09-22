package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.metalcraft.client.lod.LodDistantRenderer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Extend only the camera clip volume; game loading and simulation options stay untouched. */
@Mixin(Camera.class)
abstract class CameraLodHorizonMixin {
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
    private int metalcraft$horizonClip(int original) { return Math.max(original,Math.max(LodDistantRenderer.horizon(),dev.metalcraft.client.horizon.NativeHorizon.horizon())); }
}
