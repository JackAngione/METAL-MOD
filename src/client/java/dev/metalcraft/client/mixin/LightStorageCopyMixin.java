package dev.metalcraft.client.mixin;

import dev.metalcraft.client.chunk.LightSnapshotAccess;
import net.minecraft.world.level.lighting.DataLayerStorageMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public final class LightStorageCopyMixin {
    private LightStorageCopyMixin() { }

    @Mixin(targets = "net.minecraft.world.level.lighting.BlockLightSectionStorage$BlockDataLayerStorageMap")
    abstract static class Block implements LightSnapshotAccess.Enabled {
        @Inject(method = "copy()Lnet/minecraft/world/level/lighting/BlockLightSectionStorage$BlockDataLayerStorageMap;", at = @At("RETURN"))
        private void metalcraft$copy(CallbackInfoReturnable<DataLayerStorageMap<?>> cir) {
            LightSnapshotAccess.copy(this, cir.getReturnValue());
        }
    }

    @Mixin(targets = "net.minecraft.world.level.lighting.SkyLightSectionStorage$SkyDataLayerStorageMap")
    abstract static class Sky implements LightSnapshotAccess.Enabled {
        @Inject(method = "copy()Lnet/minecraft/world/level/lighting/SkyLightSectionStorage$SkyDataLayerStorageMap;", at = @At("RETURN"))
        private void metalcraft$copy(CallbackInfoReturnable<DataLayerStorageMap<?>> cir) {
            LightSnapshotAccess.copy(this, cir.getReturnValue());
        }
    }
}
