package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.metalcraft.client.chunk.NativeChunkDistance;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Apply the native adaptive batch quota to extended local views too. */
@Mixin(PlayerChunkSender.class)
abstract class PlayerChunkSenderNativeDistanceMixin {
    @ModifyExpressionValue(method = "collectChunksToSend", at = @At(value = "FIELD",
            target = "Lnet/minecraft/server/network/PlayerChunkSender;memoryConnection:Z"))
    private boolean metalcraft$boundExtendedLocalBatches(boolean local, ChunkMap chunks, ChunkPos player) {
        return local && ((ChunkMapDistanceAccessor)chunks).metalcraft$serverViewDistance() <= NativeChunkDistance.VANILLA_MAX;
    }
}
