package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.metalcraft.client.lod.LodSystem;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/** With distant terrain on, the integrated server loads and sends only the native radius. Simulation distance is unchanged. */
@Mixin(IntegratedServer.class)
abstract class IntegratedServerHorizonMixin {
    @ModifyExpressionValue(method="tickServer",at=@At(value="INVOKE",target="Ljava/lang/Math;max(II)I",ordinal=0),
            slice=@Slice(from=@At(value="INVOKE",target="Lnet/minecraft/client/Options;renderDistance()Lnet/minecraft/client/OptionInstance;"),
                    to=@At(value="INVOKE",target="Lnet/minecraft/server/players/PlayerList;setViewDistance(I)V")))
    private int metalcraft$nativeDeliveryDistance(int distance) {
        return LodSystem.nativeDistance(distance);
    }
}
