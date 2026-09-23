package dev.metalcraft.client.mixin;

import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CloudRenderer.class)
abstract class CloudRendererSkyMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void metalcraft$replaceCloudGeometry(CallbackInfo ci) {
        var gpu = MetalGpuDevices.current();
        var runtime = ShaderPackRuntime.active();
        if (gpu != null && runtime != null && runtime.worldSky() != null
            && runtime.worldSky().replacesClouds(gpu.linearWorldSession())) ci.cancel();
    }
}
