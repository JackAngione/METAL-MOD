package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodCompilerCapture;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache$Storage")
abstract class ClientChunkStorageLodMixin {
    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void metalcraft$unloadLod(LevelChunk chunk, CallbackInfo ci) {
        if (LodCompilerCapture.ENABLED) LodCompilerCapture.REVISIONS.unload(chunk.getPos().x(), chunk.getPos().z());
    }
}
