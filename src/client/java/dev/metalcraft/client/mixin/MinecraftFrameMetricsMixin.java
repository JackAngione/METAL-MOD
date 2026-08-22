package dev.metalcraft.client.mixin;

import dev.metalcraft.client.test.MetalFrameMetrics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures complete render-loop intervals while the lifecycle benchmark is recording. */
@Mixin(Minecraft.class)
abstract class MinecraftFrameMetricsMixin {
	@Inject(method = "runTick", at = @At("TAIL"))
	private void metalcraft$finishFrameMetrics(final boolean advanceGameTime, final CallbackInfo callback) {
		Minecraft client = (Minecraft)(Object)this;
		MetalFrameMetrics.recordFrame(client.getFrameTimeNs(), System.nanoTime());
	}
}
