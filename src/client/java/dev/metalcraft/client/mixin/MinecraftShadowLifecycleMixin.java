package dev.metalcraft.client.mixin;

import dev.metalcraft.client.shader.world.WorldTerrainShadows;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Clear the retained scene when the client drops a level, including the saving-screen path. */
@Mixin(Minecraft.class)
abstract class MinecraftShadowLifecycleMixin {
    @Inject(method = {"clearClientLevel", "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V"}, at = @At("HEAD"))
    private void metalcraft$clearShadowScene(CallbackInfo callback) {
        WorldTerrainShadows.clearWorldCache();
    }
}
