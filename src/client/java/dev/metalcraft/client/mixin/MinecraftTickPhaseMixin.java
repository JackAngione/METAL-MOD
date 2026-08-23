package dev.metalcraft.client.mixin;

import dev.metalcraft.client.metal.MetalStallProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Partitions {@code runTick} into phases, so that no part of a frame is unattributed.
 *
 * <p>The worst traversal frames were almost entirely unexplained, and the explanation previously
 * offered - garbage collection - does not survive being tested. Running the same capture under ZGC
 * removed collection from the tail completely (0.000 ms attributed across the worst 1% of frames,
 * against 154 ms under G1) and the worst frame still took 158 ms, of which 158.3 ms was
 * unattributed. So collection was a correlate of the spike, not its cause.
 *
 * <p>What the probe could not see is that {@code runTick} is not just rendering.
 * {@code getFrameTimeNs()} covers the render section only, so a frame reporting 3.7 ms of CPU inside
 * a 158 ms interval leaves everything before {@code renderFrame} invisible. Bracketing the packet
 * queue, the main-thread task queue, and the client ticks found the traversal stall in the second of
 * those - but it left the stretches <em>between</em> those brackets uncounted, and a 50 ms pan frame
 * then attributed 45.8 ms to that remainder while claiming nothing anywhere else.
 *
 * <p>So the boundaries here are a cursor rather than a set of begin/end pairs. Each injection closes
 * the stretch that ended and opens the one that begins, which has two properties that pairs do not:
 * the gaps between the interesting calls are attributed rather than discarded, and a branch not
 * taken - {@code runTick} skips packets, tasks and ticks entirely when {@code advanceGameTime} is
 * false - cannot leave a stretch open and mis-attribute the rest of the frame to it. What survives
 * as {@code unphasedMs} is then only the handful of microseconds outside {@code runTick} itself.
 */
@Mixin(Minecraft.class)
abstract class MinecraftTickPhaseMixin {
	/** Nanoseconds at the last phase boundary, or {@code 0} while the probe is disabled. */
	private long metalcraft$cursorNs;
	private long metalcraft$levelEndFrameStartedNs;

	@Inject(method = "runTick", at = @At("HEAD"))
	private void metalcraft$beginTick(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$cursorNs = MetalStallProbe.begin();
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V")
	)
	private void metalcraft$endPreRender(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$cursorNs =
			MetalStallProbe.split(MetalStallProbe.Source.CLIENT_PRE_RENDER, this.metalcraft$cursorNs);
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V", shift = At.Shift.AFTER)
	)
	private void metalcraft$endPackets(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$cursorNs =
			MetalStallProbe.split(MetalStallProbe.Source.CLIENT_PACKETS, this.metalcraft$cursorNs);
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER)
	)
	private void metalcraft$endTasks(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$cursorNs =
			MetalStallProbe.split(MetalStallProbe.Source.CLIENT_TASKS, this.metalcraft$cursorNs);
	}

	// Inside the tick loop, so both boundaries are crossed once per tick the frame catches up on.
	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V")
	)
	private void metalcraft$endGizmos(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$cursorNs =
			MetalStallProbe.split(MetalStallProbe.Source.CLIENT_GIZMOS, this.metalcraft$cursorNs);
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V", shift = At.Shift.AFTER)
	)
	private void metalcraft$endClientTick(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$cursorNs =
			MetalStallProbe.split(MetalStallProbe.Source.CLIENT_TICK, this.metalcraft$cursorNs);
	}

	// Closes the tick section: the gizmo drain and the loop exit land in CLIENT_GIZMOS, and the
	// collection opened here belongs to the pre-frame stretch that it is part of.
	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;collectPerFrameMainThreadGizmos()Lnet/minecraft/gizmos/Gizmos$TemporaryCollection;")
	)
	private void metalcraft$endTickSection(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$cursorNs =
			MetalStallProbe.split(MetalStallProbe.Source.CLIENT_GIZMOS, this.metalcraft$cursorNs);
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V")
	)
	private void metalcraft$endPreFrame(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$cursorNs =
			MetalStallProbe.split(MetalStallProbe.Source.CLIENT_PRE_FRAME, this.metalcraft$cursorNs);
	}

	@Inject(
		method = "runTick",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V", shift = At.Shift.AFTER)
	)
	private void metalcraft$endRenderFrame(final boolean advanceGameTime, final CallbackInfo callback) {
		this.metalcraft$cursorNs =
			MetalStallProbe.split(MetalStallProbe.Source.RENDER_FRAME, this.metalcraft$cursorNs);
	}

	@Inject(method = "runTick", at = @At("TAIL"))
	private void metalcraft$endPostRender(final boolean advanceGameTime, final CallbackInfo callback) {
		MetalStallProbe.split(MetalStallProbe.Source.CLIENT_POST_RENDER, this.metalcraft$cursorNs);
		this.metalcraft$cursorNs = 0L;
	}

	// A detail rather than a phase: it sits inside renderFrame, after Minecraft stops its own frame
	// timer, so nothing Minecraft reports can see it.
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
}
