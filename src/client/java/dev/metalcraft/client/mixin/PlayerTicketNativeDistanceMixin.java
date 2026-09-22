package dev.metalcraft.client.mixin;

import dev.metalcraft.client.chunk.NativeChunkDistance;
import dev.metalcraft.client.chunk.NativePlayerDistanceAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.DistanceManager$PlayerTicketTracker")
abstract class PlayerTicketNativeDistanceMixin implements NativePlayerDistanceAccess.LoadingTracker {
    @Shadow private int viewDistance;
    @Shadow private boolean haveTicketFor(int level) { throw new AssertionError(); }
    @Shadow private void onLevelChange(long key, int level, boolean saw, boolean sees) { throw new AssertionError(); }

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/Long2IntMap;defaultReturnValue(I)V"), index = 0)
    private int metalcraft$absentQueueLevel(int original) { return NativeChunkDistance.NO_LEVEL; }

    @Inject(method = "updateViewDistance", at = @At("HEAD"), cancellable = true)
    private void metalcraft$updateWideView(int distance, CallbackInfo ci) {
        var graph = ((NativePlayerDistanceAccess)this).metalcraft$distanceGraph();
        if (graph == null) throw new IllegalStateException("Missing native player distance graph");
        graph.forEach((node, level) -> onLevelChange(node, level, haveTicketFor(level), level <= distance));
        viewDistance = distance;
        graph.setViewDistance(distance);
        ci.cancel();
    }
}
