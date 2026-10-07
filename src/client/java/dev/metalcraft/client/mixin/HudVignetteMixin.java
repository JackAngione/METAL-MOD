package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps the vanilla HUD corner mask from darkening the completed ACES image. */
@Mixin(Hud.class)
abstract class HudVignetteMixin {
    @ModifyExpressionValue(method = "extractVignette", at = @At(value = "FIELD",
        target = "Lnet/minecraft/client/gui/Hud;vignetteBrightness:F"))
    private float metalcraft$acesVignetteBrightness(final float brightness) {
        ShaderPackRuntime runtime = ShaderPackRuntime.active();
        if (MetalGpuDevices.current() != null && runtime != null && runtime.isActive()
            && ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId())
            && "aces".equals(runtime.optionValue("tonemap"))) {
            // Zero removes ordinary multiplicative darkening. Vanilla still computes
            // its red world-border warning independently from this brightness.
            return 0.0F;
        }
        return brightness;
    }
}
