package dev.metalcraft.client.mixin;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.SectionUpdateTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionUpdateTracker.class)
public interface SectionUpdateTrackerAccessor {
    @Accessor("storage") RotatingSectionStorage<SectionUpdateTracker.SectionDirtyState> metalcraft$storage();
}
