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
	/**
	 * Marks when the render loop resumed.
	 *
	 * <p>The interval is measured tail-to-tail, so it also contains whatever happened while the
	 * render thread was not in the render loop at all. The worst frames of a traversal capture turn
	 * out to be almost entirely that: 8 ms of loop inside a 279 ms interval. Without this timestamp
	 * that gap is invisible, and a stall outside the loop is indistinguishable from a slow frame.
	 */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void metalcraft$beginFrameMetrics(final boolean advanceGameTime, final CallbackInfo callback) {
		MetalFrameMetrics.recordFrameStart(System.nanoTime());
		var metal = dev.metalcraft.client.metal.MetalGpuDevices.current();
		var settings = dev.metalcraft.client.MetalCraftConfig.beginLodFrame(metal != null);
		if (dev.metalcraft.client.lod.LodCompilerCapture.configure(settings) && settings.enabled()) {
			Minecraft client = (Minecraft)(Object)this;
			if (client.level != null) client.levelExtractor.allChanged();
		}
		dev.metalcraft.client.lod.LodLoadedRenderer.beginFrame(metal, settings);
		dev.metalcraft.client.lod.LodDistantRenderer.beginFrame(metal, settings);
	}

	@Inject(method = "runTick", at = @At("TAIL"))
	private void metalcraft$finishFrameMetrics(final boolean advanceGameTime, final CallbackInfo callback) {
		Minecraft client = (Minecraft)(Object)this;
		MetalFrameMetrics.recordFrame(client.getFrameTimeNs(), System.nanoTime());
	}
}
