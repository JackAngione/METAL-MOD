package dev.metalcraft.client.mixin;

import dev.metalcraft.client.test.MetalFrameMetrics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures complete render-loop intervals while the lifecycle benchmark is recording. */
@Mixin(Minecraft.class)
abstract class MinecraftFrameMetricsMixin {
	@Unique
	private long metalcraft$frameStartedNs;

	@Inject(method = "runTick", at = @At("HEAD"))
	private void metalcraft$startFrameMetrics(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$frameStartedNs = System.nanoTime();
	}

	@Inject(method = "runTick", at = @At("TAIL"))
	private void metalcraft$finishFrameMetrics(final boolean advanceGameTime, final CallbackInfo callback) {
		Minecraft client = (Minecraft)(Object)this;
		MetalFrameMetrics.recordFrame(client.getFrameTimeNs(), System.nanoTime() - this.metalcraft$frameStartedNs);
	}
}
