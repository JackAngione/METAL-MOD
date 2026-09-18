package dev.metalcraft.client.chunk;

/** Shared limits; rendering/loading distance is independent of simulation distance. */
public final class NativeChunkDistance {
    public static final int MAX = 256;
    public static final int VANILLA_MAX = 32;
    public static final int NO_LEVEL = MAX + 2;
    private NativeChunkDistance() { }

    /** The vanilla settings packet uses a signed byte. Keep remote servers compatible. */
    public static int wireDistance(int distance) {
        return Math.clamp(distance, 2, VANILLA_MAX);
    }
}
