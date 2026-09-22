package dev.metalcraft.client.mixin;

import dev.metalcraft.client.horizon.NativeHorizon;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe mutations even when their flags suppress ordinary client notifications. */
@Mixin(LevelChunk.class)
abstract class HorizonBlockChangeMixin {
    @Inject(method="setBlockState",at=@At("RETURN"))
    private void metalcraft$refreshEnvelope(BlockPos pos,BlockState state,int flags,CallbackInfoReturnable<BlockState> cir) {
        var chunk=(LevelChunk)(Object)this;
        if(cir.getReturnValue()!=null && cir.getReturnValue()!=state && chunk.getLevel() instanceof ServerLevel level)
            NativeHorizon.invalidate(level,chunk.getPos().pack());
    }
}
