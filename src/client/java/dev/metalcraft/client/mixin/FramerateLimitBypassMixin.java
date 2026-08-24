package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.metalcraft.client.MetalCraftConfig;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Removes Minecraft's own frame pacing while the unlocked frame rate option is on.
 *
 * <p>Minecraft's default Max Framerate is 120, which on a 120 Hz display produces frame intervals
 * indistinguishable from display synchronization, and its AFK throttle drops a world that has not
 * received input for a minute to 30. Both limits apply regardless of the V-Sync option and of the
 * present mode the Metal surface was configured with.
 *
 * <p>The throttles that exist to stop the renderer burning power when nobody is watching - an
 * iconified window, ten minutes idle, and sitting in a menu outside a world - are left in place.
 */
@Mixin(FramerateLimitTracker.class)
abstract class FramerateLimitBypassMixin {
	@Shadow
	public abstract FramerateLimitTracker.FramerateThrottleReason getThrottleReason();

	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void metalcraft$unlockFramerate(final CallbackInfoReturnable<Integer> info) {
		if (!MetalCraftConfig.unlockedFrameRate()) {
			return;
		}
		FramerateLimitTracker.FramerateThrottleReason reason = this.getThrottleReason();
		if (reason == FramerateLimitTracker.FramerateThrottleReason.NONE
			|| reason == FramerateLimitTracker.FramerateThrottleReason.SHORT_AFK) {
			info.setReturnValue(Options.UNLIMITED_FRAMERATE_CUTOFF);
		}
	}
}
