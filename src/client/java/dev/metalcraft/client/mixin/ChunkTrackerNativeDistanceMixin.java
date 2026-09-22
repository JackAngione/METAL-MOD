package dev.metalcraft.client.mixin;

import dev.metalcraft.client.chunk.NativePlayerDistanceAccess;
import net.minecraft.server.level.ChunkTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkTracker.class)
abstract class ChunkTrackerNativeDistanceMixin {
    @Inject(method = "update", at = @At("HEAD"), cancellable = true)
    private void metalcraft$updateLoadingSource(long node, int level, boolean decreased, CallbackInfo ci) {
        if ((Object)this instanceof NativePlayerDistanceAccess access && access.metalcraft$distanceGraph() != null) {
            access.metalcraft$distanceGraph().updateSource(node, level, decreased);
            ci.cancel();
        }
    }
}
