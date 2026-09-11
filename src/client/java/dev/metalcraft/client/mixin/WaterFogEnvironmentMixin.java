package dev.metalcraft.client.mixin;

import dev.metalcraft.client.shader.water.UnderwaterAppearance;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WaterFogEnvironment.class)
abstract class WaterFogEnvironmentMixin {
	@Inject(method = "setupFog", at = @At("TAIL"))
	private void metalcraft$clearWater(final FogData fog, final Camera camera, final ClientLevel level,
		final float renderDistance, final DeltaTracker deltaTracker, final CallbackInfo callback) {
		if (UnderwaterAppearance.enabled()) UnderwaterAppearance.apply(fog);
	}
}
