package dev.metalcraft.client.lod;

import org.jspecify.annotations.Nullable;

/** Extraction writes once; compiler workers never recover identity from mutable world state. */
public interface LodRegionSource {
    LodRevisionTracker.@Nullable Ticket metalcraft$lodTicket();
    void metalcraft$lodTicket(LodRevisionTracker.Ticket ticket);
}
