package dev.metalcraft.client.lod;

/**
 * Limits and derived policy for distant terrain.
 *
 * <p>Total distance is Minecraft's render distance. Native distance is the radius of ordinary
 * chunks. Detail sets the distance at which each LOD level begins: a level-{@code L} cell is
 * {@code 2^L} blocks wide and is used from {@code levelDistance(detail) * 2^L} blocks, which
 * keeps a cell under roughly {@link #pixelError} pixels at a 1080p, 70-degree reference view.
 * Detail also sets how far block-sized cells keep their real block textures instead of one
 * averaged colour per cell.
 */
public final class LodSettings {
    public static final boolean DEFAULT_ENABLED = true;
    public static final int MIN_NATIVE_DISTANCE = 2;
    public static final int MAX_NATIVE_DISTANCE = 256;
    public static final int DEFAULT_NATIVE_DISTANCE = 12;
    public static final int MIN_DETAIL = 1;
    public static final int MAX_DETAIL = 8;
    public static final int DEFAULT_DETAIL = 5;
    /** Blocks per level; the maximum step preserves block-sized terrain for high-resolution views. */
    private static final int[] LEVEL_DISTANCE = {32, 48, 64, 96, 128, 192, 256, 768};
    /**
     * Blocks within which cells are textured. A textured node costs about seven times a flat one
     * (a quad per block face), and past about 400 blocks a block is under two pixels at 1080p, where
     * its mipmapped texture is its average colour anyway. The maximum step extends textures for
     * high-resolution views, where blocks remain visible farther away.
     */
    private static final int[] TEXTURE_DISTANCE = {0, 0, 64, 128, 256, 320, 384, 1024};
    /** Pixels per radian of a 1080-pixel-tall, 70-degree view. */
    private static final double REFERENCE_PIXELS_PER_RADIAN = 1080 / (2 * Math.tan(Math.toRadians(35)));

    private LodSettings() { }

    public static int clampNativeDistance(int chunks) { return Math.clamp(chunks, MIN_NATIVE_DISTANCE, MAX_NATIVE_DISTANCE); }

    public static int clampDetail(int detail) { return Math.clamp(detail, MIN_DETAIL, MAX_DETAIL); }

    public static int levelDistance(int detail) { return LEVEL_DISTANCE[clampDetail(detail) - 1]; }

    /** Distance in blocks within which block-sized cells use real block textures; zero for none. */
    public static int textureDistance(int detail) {
        int detailIndex = clampDetail(detail) - 1;
        // Only block-sized cells are textured, and those end at twice the level distance.
        return Math.min(TEXTURE_DISTANCE[detailIndex], 2 * LEVEL_DISTANCE[detailIndex]);
    }

    /** Approximate on-screen size of one cell at the start of its level, for the settings text. */
    public static double pixelError(int detail) { return REFERENCE_PIXELS_PER_RADIAN / levelDistance(detail); }
}
