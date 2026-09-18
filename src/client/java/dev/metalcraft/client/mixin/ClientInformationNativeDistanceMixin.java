package dev.metalcraft.client.mixin;

import dev.metalcraft.client.chunk.NativeChunkDistance;
import net.minecraft.server.level.ClientInformation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Preserve the vanilla byte packet. ChunkMap resolves the local player's full distance server-side. */
@Mixin(ClientInformation.class)
abstract class ClientInformationNativeDistanceMixin {
    @ModifyArg(method = "write", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/FriendlyByteBuf;writeByte(I)Lnet/minecraft/network/FriendlyByteBuf;", ordinal = 0), index = 0)
    private int metalcraft$compatibleWireDistance(int distance) { return NativeChunkDistance.wireDistance(distance); }
}
