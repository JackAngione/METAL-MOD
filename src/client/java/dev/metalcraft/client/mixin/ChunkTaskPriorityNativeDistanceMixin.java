package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.metalcraft.client.chunk.NativeChunkDistance;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkTaskPriorityQueue.class)
abstract class ChunkTaskPriorityNativeDistanceMixin {
    // Includes the no-player sentinel used while a pending task is being removed.
    @ModifyExpressionValue(method = "<clinit>", at = @At(value = "FIELD",
            target = "Lnet/minecraft/server/level/ChunkLevel;MAX_LEVEL:I"))
    private static int metalcraft$wideTicketPriorities(int original) {
        return Math.max(original, NativeChunkDistance.NO_LEVEL - 1);
    }
}
