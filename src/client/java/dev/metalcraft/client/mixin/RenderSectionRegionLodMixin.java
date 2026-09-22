package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodRegionSource;
import dev.metalcraft.client.lod.LodRevisionTracker;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderSectionRegion.class)
abstract class RenderSectionRegionLodMixin implements LodRegionSource {
    @Unique private LodRevisionTracker.@Nullable Ticket metalcraft$lodTicket;
    @Override public LodRevisionTracker.@Nullable Ticket metalcraft$lodTicket() { return this.metalcraft$lodTicket; }
    @Override public void metalcraft$lodTicket(LodRevisionTracker.Ticket ticket) { this.metalcraft$lodTicket = ticket; }
}
