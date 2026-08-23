package dev.metalcraft.client.mixin;

import dev.metalcraft.client.metal.MetalStallProbe;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Times the task queue's drain from inside it, which is the only place it can be timed honestly.
 *
 * <p>The {@code CLIENT_TASKS} phase brackets the drain from {@code runTick}, at
 * {@code INVOKE runAllTasks} with {@code shift = AFTER}. So does Fabric's client gametest harness,
 * whose {@code postRunTasksHook} parks the render thread there to hand the frame to the test thread -
 * and it is applied closer to the call, so its park falls inside the bracket. Every number this
 * project has attributed to Minecraft's task queue was measured through that.
 *
 * <p>Timing the drain from inside {@code runAllTasks} cannot pick the park up, because the park is
 * not in it. The difference between the two is the harness, and having both makes it legible instead
 * of having to be known.
 */
@Mixin(BlockableEventLoop.class)
abstract class BlockableEventLoopDrainMixin {
	private long metalcraft$drainStartedNs;

	@Inject(method = "runAllTasks", at = @At("HEAD"))
	private void metalcraft$beginDrain(final CallbackInfo callback) {
		this.metalcraft$drainStartedNs = MetalStallProbe.begin();
	}

	@Inject(method = "runAllTasks", at = @At("RETURN"))
	private void metalcraft$endDrain(final CallbackInfo callback) {
		MetalStallProbe.end(MetalStallProbe.Source.TASK_DRAIN, this.metalcraft$drainStartedNs);
	}
}
