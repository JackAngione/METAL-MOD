package dev.metalcraft.client.lod;

/**
 * What a textured column shows, packed into one long beside its {@link LodCell}.
 *
 * <p>Bits 0-20 hold the block state whose top face is drawn, 21-41 the state whose side faces
 * are drawn (the top solid block; the two differ under a covering such as a snow layer or
 * carpet), and 42-63 a {@link LodBiomes} id for biome tints. State ids come from
 * {@code Block.BLOCK_STATE_REGISTRY}; {@link LodBlockColors#textured()} is false when they
 * do not fit.
 */
final class LodSurface {
    static final int STATE_BITS = 21;
    static final int MAX_STATES = 1 << STATE_BITS;
    private static final long STATE_MASK = MAX_STATES - 1;

    private LodSurface() { }

    static long pack(int topState, int sideState, int biome) {
        return (topState & STATE_MASK) | (sideState & STATE_MASK) << STATE_BITS | (long)biome << 2 * STATE_BITS;
    }

    static int topState(long surface) { return (int)(surface & STATE_MASK); }

    static int sideState(long surface) { return (int)(surface >>> STATE_BITS & STATE_MASK); }

    static int biome(long surface) { return (int)(surface >>> 2 * STATE_BITS); }
}
