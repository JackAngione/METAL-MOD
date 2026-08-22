package dev.metalcraft.client.mixin;

import dev.metalcraft.client.metal.MetalStallProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the parts of {@code runTick} that Minecraft's own frame timer does not cover.
 *
 * <p>The worst traversal frames are almost entirely unexplained, and the explanation previously
 * offered - garbage collection - does not survive being tested. Running the same capture under ZGC
 * removed collection from the tail completely (0.000 ms attributed across the worst 1% of frames,
 * against 154 ms under G1) and the worst frame still took 158 ms, of which 158.3 ms was
 * unattributed. So collection was a correlate of the spike, not its cause.
 *
 * <p>What the probe could not see is that {@code runTick} is not just rendering.
 * {@code getFrameTimeNs()} covers the render section only, so a frame reporting 3.7 ms of CPU inside
 * a 158 ms interval leaves everything before {@code renderFrame} invisible: queued packet
 * processing, the main-thread task queue that chunk mesh uploads are scheduled onto, and up to ten
 * client ticks. These three sources make that stretch attributable.
 */
@Mixin(Minecraft.class)
abstract class MinecraftTickPhaseMixin {
	private long metalcraft$packetsStartedNs;
	private long metalcraft$tasksStartedNs;
	private long metalcraft$clientTickStartedNs;
	private long metalcraft$levelEndFrameStartedNs;
	private long metalcraft$renderFrameStartedNs;

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V")
	)
	private void metalcraft$beginPackets(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$packetsStartedNs = MetalStallProbe.begin();
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V", shift = At.Shift.AFTER)
	)
	private void metalcraft$endPackets(final boolean advanceGameTime, final CallbackInfo callback) {
		MetalStallProbe.end(MetalStallProbe.Source.CLIENT_PACKETS, this.metalcraft$packetsStartedNs);
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V")
	)
	private void metalcraft$beginTasks(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$tasksStartedNs = MetalStallProbe.begin();
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER)
	)
	private void metalcraft$endTasks(final boolean advanceGameTime, final CallbackInfo callback) {
		MetalStallProbe.end(MetalStallProbe.Source.CLIENT_TASKS, this.metalcraft$tasksStartedNs);
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V")
	)
	private void metalcraft$beginRenderFrame(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$renderFrameStartedNs = MetalStallProbe.begin();
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V", shift = At.Shift.AFTER)
	)
	private void metalcraft$endRenderFrame(final boolean advanceGameTime, final CallbackInfo callback) {
		MetalStallProbe.end(MetalStallProbe.Source.RENDER_FRAME, this.metalcraft$renderFrameStartedNs);
	}

	// After Minecraft stops its own frame timer, so nothing it reports can see this.
	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;endFrame()V")
	)
	private void metalcraft$beginLevelEndFrame(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$levelEndFrameStartedNs = MetalStallProbe.begin();
	}

	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;endFrame()V", shift = At.Shift.AFTER)
	)
	private void metalcraft$endLevelEndFrame(final boolean advanceGameTime, final CallbackInfo callback) {
		MetalStallProbe.end(MetalStallProbe.Source.LEVEL_END_FRAME, this.metalcraft$levelEndFrameStartedNs);
	}

	// Inside the tick loop, so the recorded count is the number of ticks the frame had to catch up on.
	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V")
	)
	private void metalcraft$beginClientTick(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$clientTickStartedNs = MetalStallProbe.begin();
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V", shift = At.Shift.AFTER)
	)
	private void metalcraft$endClientTick(final boolean advanceGameTime, final CallbackInfo callback) {
		MetalStallProbe.end(MetalStallProbe.Source.CLIENT_TICK, this.metalcraft$clientTickStartedNs);
	}
}
