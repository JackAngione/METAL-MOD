package dev.metalcraft.client.chunk;

import org.jspecify.annotations.Nullable;

public interface NativePlayerDistanceAccess {
    @Nullable WidePlayerDistanceGraph metalcraft$distanceGraph();

    /** Only the loading tracker uses wider levels; natural spawning stays vanilla. */
    interface LoadingTracker { }
}
