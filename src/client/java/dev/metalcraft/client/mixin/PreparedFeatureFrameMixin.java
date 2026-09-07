package dev.metalcraft.client.mixin;

import dev.metalcraft.client.shader.WorldGeometryAdapter;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalGpuDevices;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Places the deferred resolve between opaque features and Minecraft's forward translucency. */
@Mixin(FeatureRenderDispatcher.PreparedFrame.class)
abstract class PreparedFeatureFrameMixin {
	@Inject(method = "executeTranslucent", at = @At("HEAD"))
	private void metalcraft$resolveOpaqueBeforeTranslucency(final CallbackInfo callback) {
		WorldGeometryAdapter.resolveOpaque();
		MetalGpuDevice device = MetalGpuDevices.current();
		if (device != null) device.captureOpaqueWaterInputs();
	}
}
