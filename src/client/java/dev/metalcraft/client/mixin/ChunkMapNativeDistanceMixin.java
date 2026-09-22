package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.metalcraft.client.chunk.NativeChunkDistance;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

@Mixin(ChunkMap.class)
abstract class ChunkMapNativeDistanceMixin {
    @Shadow private int serverViewDistance;

    @ModifyConstant(method = "setServerViewDistance", constant = @Constant(intValue = 32))
    private int metalcraft$loadingDistanceMaximum(int original) { return NativeChunkDistance.MAX; }

    // 26.2 serializes even local ClientInformation. The integrated server already
    // follows Options.renderDistance; use that authoritative int for its local player.
    @ModifyExpressionValue(method = "getPlayerViewDistance", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;requestedViewDistance()I"))
    private int metalcraft$localPlayerDistance(int requested, ServerPlayer player) {
        return ((ServerConnectionAccessor)player.connection).metalcraft$connection().isMemoryConnection()
                ? serverViewDistance : requested;
    }
}
