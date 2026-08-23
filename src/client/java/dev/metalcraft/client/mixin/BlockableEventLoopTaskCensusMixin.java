package dev.metalcraft.client.mixin;

import dev.metalcraft.client.metal.MetalTaskCensus;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records what the main-thread task queue actually runs, for {@link MetalTaskCensus}.
 *
 * <p>{@code doRunTask} is the one place every queued task passes through, on the client and on the
 * integrated server alike. The census guards itself on the render thread and on a capture being
 * active, so the server thread's tasks cost a volatile read and a thread comparison and are then
 * dropped.
 */
@Mixin(BlockableEventLoop.class)
abstract class BlockableEventLoopTaskCensusMixin {
	@Inject(method = "doRunTask", at = @At("HEAD"))
	private void metalcraft$beginTask(final Runnable task, final CallbackInfo callback) {
		MetalTaskCensus.begin();
	}

	@Inject(method = "doRunTask", at = @At("RETURN"))
	private void metalcraft$endTask(final Runnable task, final CallbackInfo callback) {
		MetalTaskCensus.end(task.getClass());
	}
}
