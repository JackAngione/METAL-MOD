package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.metalcraft.client.lod.LodSystem;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Extends only the camera clip volume to the distant-terrain horizon; loading and simulation are untouched. */
@Mixin(Camera.class)
abstract class CameraLodHorizonMixin {
    @ModifyExpressionValue(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I"))
    private int metalcraft$horizonClip(int original) { return Math.max(original, LodSystem.horizon()); }
}
