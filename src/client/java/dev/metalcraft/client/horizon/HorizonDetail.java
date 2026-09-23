package dev.metalcraft.client.horizon;

/** Detail of detached horizon columns. Higher levels preserve smaller surface features. */
public final class HorizonDetail {
    public static final int DEFAULT = 1;
    private HorizonDetail() { }

    public static int clamp(int level) { return Math.clamp(level, 1, 5); }

    public static int sampleSize(int level) {
        return switch (clamp(level)) { case 4 -> 2; case 5 -> 1; default -> 4; };
    }

    public static int cellSize(int level, int adaptiveCell) {
        return Math.min(adaptiveCell, switch (clamp(level)) {
            case 2 -> 8;
            case 3 -> 4;
            case 4 -> 2;
            case 5 -> 1;
            default -> 16;
        });
    }
}
