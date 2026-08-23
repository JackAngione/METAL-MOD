package dev.metalcraft.client.mixin;

import dev.metalcraft.client.metal.MetalStallProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attributes the time the client gametest harness parks the render thread.
 *
 * <p>Every measurement this project has taken runs under that harness, and the harness hands each
 * frame to the test thread by blocking the render thread on a {@code Phaser} inside
 * {@code ThreadingImpl.enterPhase}. It does that from {@code postRunTasksHook}, injected at the same
 * {@code INVOKE runAllTasks} that {@code CLIENT_TASKS} closes on - so the park landed inside a phase
 * named for Minecraft's task queue and was read, for months, as Minecraft's task queue. It is not:
 * on the 180 ms frame that finally settled it, the queue's own drain measured 0.000 ms.
 *
 * <p>Which phase absorbs the park is not even stable. It depends on the order Mixin applies two
 * injections at one instruction, and it moved from {@code CLIENT_TASKS} to {@code CLIENT_GIZMOS}
 * when an unrelated mixin was added to this mod. Timing it here instead pins it to the harness
 * whatever the ordering does, and the remainder of the phase it lands in becomes readable again.
 *
 * <p>{@code @Pseudo} because the target belongs to Fabric's client gametest module rather than to
 * Minecraft: if it is absent the mixin is skipped rather than failing the whole configuration. When
 * it is present and has moved, the injector's {@code require} still fails loudly, which is the
 * behaviour worth having - a park that silently stops being attributed puts the numbers back where
 * they started.
 */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.gametest.threading.ThreadingImpl", remap = false)
abstract class GametestHandoffMixin {
	@Inject(method = "enterPhase", at = @At("HEAD"))
	private static void metalcraft$beginHandoff(final int phase, final CallbackInfo callback) {
		MetalStallProbe.beginHandoff();
	}

	@Inject(method = "enterPhase", at = @At("RETURN"))
	private static void metalcraft$endHandoff(final int phase, final CallbackInfo callback) {
		MetalStallProbe.endHandoff();
	}
}
