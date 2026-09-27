package dev.metalcraft.client.lod;

/**
 * One packed terrain column sample.
 *
 * <p>Bits 0-11 hold the terrain top (exclusive), 12-23 the terrain bottom, 24-35 the fluid top,
 * 36-59 the top block's RGB and 60-63 its block light. Heights are stored as {@code y + 2048},
 * which covers every height a dimension may declare. A column is empty when its top equals its
 * bottom and has fluid only when the fluid top is above the terrain top. Fluid colour lives in a
 * separate array because only fluid columns need it.
 */
public final class LodCell {
    public static final int HEIGHT_OFFSET = 2048;
    public static final long EMPTY = 0L;
    private static final int MASK = 0xFFF;

    private LodCell() { }

    public static long pack(int top, int bottom, int fluidTop, int rgb, int blockLight) {
        return (long)encode(top) | (long)encode(bottom) << 12 | (long)encode(fluidTop) << 24
            | (long)(rgb & 0xFFFFFF) << 36 | (long)(blockLight & 15) << 60;
    }

    private static int encode(int y) { return Math.clamp(y + HEIGHT_OFFSET, 0, MASK); }

    public static int top(long cell) { return (int)(cell & MASK) - HEIGHT_OFFSET; }

    public static int bottom(long cell) { return (int)(cell >>> 12 & MASK) - HEIGHT_OFFSET; }

    public static int fluidTop(long cell) { return (int)(cell >>> 24 & MASK) - HEIGHT_OFFSET; }

    public static int rgb(long cell) { return (int)(cell >>> 36 & 0xFFFFFF); }

    public static int blockLight(long cell) { return (int)(cell >>> 60 & 15); }

    public static boolean hasTerrain(long cell) { return (cell & MASK) != (cell >>> 12 & MASK); }

    public static boolean hasFluid(long cell) { return (cell >>> 24 & MASK) > (cell & MASK); }
}
