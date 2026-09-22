package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.metalcraft.client.chunk.LazySectionStorage;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Limit the altered iteration contract to the two audited native bookkeeping owners. */
@Mixin({ViewArea.class, SectionUpdateTracker.class})
abstract class LazySectionStorageMixin {
    @WrapOperation(method = "<init>", at = @At(value = "NEW", target = "net/minecraft/client/RotatingSectionStorage"))
    private <T extends RotatingSectionStorage.Value> RotatingSectionStorage<T> metalcraft$lazySections(
            int radius, int minY, int maxY, RotatingSectionStorage.ValueCreator<T> factory,
            Operation<RotatingSectionStorage<T>> original) {
        return radius > 32 ? new LazySectionStorage<>(radius,minY,maxY,factory) : original.call(radius,minY,maxY,factory);
    }
}
