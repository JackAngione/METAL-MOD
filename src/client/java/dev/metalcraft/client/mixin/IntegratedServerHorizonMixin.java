package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Slice;

/** Limit ordinary chunk delivery, leaving simulation distance and bounded horizon tickets independent. */
@Mixin(IntegratedServer.class)
abstract class IntegratedServerHorizonMixin {
    @ModifyExpressionValue(method="tickServer",at=@At(value="INVOKE",target="Ljava/lang/Math;max(II)I",ordinal=0),
            slice=@Slice(from=@At(value="INVOKE",target="Lnet/minecraft/client/Options;renderDistance()Lnet/minecraft/client/OptionInstance;"),
                    to=@At(value="INVOKE",target="Lnet/minecraft/server/players/PlayerList;setViewDistance(I)V")))
    private int metalcraft$nativeDeliveryDistance(int distance) {
        return dev.metalcraft.client.horizon.NativeHorizon.nativeDistance(distance);
    }
}
