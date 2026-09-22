package dev.metalcraft.client.mixin;

import dev.metalcraft.client.chunk.NativePlayerDistanceAccess;
import dev.metalcraft.client.chunk.WidePlayerDistanceGraph;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.server.level.DistanceManager$FixedPlayerDistanceChunkTracker")
abstract class FixedPlayerDistanceNativeMixin implements NativePlayerDistanceAccess {
    @Shadow protected abstract int getLevelFromSource(long node);
    @Shadow protected abstract void onLevelChange(long node, int oldLevel, int level);
    @Unique private @Nullable WidePlayerDistanceGraph metalcraft$graph;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void metalcraft$wideLoadingGraph(CallbackInfo ci) {
        if (this instanceof NativePlayerDistanceAccess.LoadingTracker)
            metalcraft$graph = new WidePlayerDistanceGraph(this::getLevelFromSource, this::onLevelChange);
    }

    @Override public @Nullable WidePlayerDistanceGraph metalcraft$distanceGraph() { return metalcraft$graph; }

    @Inject(method = "getLevel", at = @At("HEAD"), cancellable = true)
    private void metalcraft$wideLevel(long node, CallbackInfoReturnable<Integer> cir) {
        if (metalcraft$graph != null) cir.setReturnValue(metalcraft$graph.level(node));
    }

    @Inject(method = "runAllUpdates", at = @At("HEAD"), cancellable = true)
    private void metalcraft$propagateLoadingDistance(CallbackInfo ci) {
        if (metalcraft$graph != null) { metalcraft$graph.runUpdates(); ci.cancel(); }
    }
}
