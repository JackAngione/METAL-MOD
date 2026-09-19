package dev.metalcraft.client.chunk;

/** Distance/FOV selection for progressively coarser geometric grids, with near protection and hysteresis. */
public final class NativeLodSelection {
    public static final int DEFAULT_REDUCTION = 3;
    public static final int MIN_NATIVE_DISTANCE = 1;
    public static final int MAX_NATIVE_DISTANCE = 256;
    public static final int DEFAULT_NATIVE_DISTANCE = 4;
    private NativeLodSelection() { }

    public static int clampReduction(int reduction) { return Math.clamp(reduction, 0, 5); }
    public static int clampNativeDistance(int chunks) { return Math.clamp(chunks, MIN_NATIVE_DISTANCE, MAX_NATIVE_DISTANCE); }

    public static int select(double distance, double fovDegrees, int previous, boolean enabled) {
        return select(distance, fovDegrees, previous, enabled, DEFAULT_REDUCTION);
    }

    public static int select(double distance, double fovDegrees, int previous, boolean enabled, int reduction) {
        return select(distance, fovDegrees, previous, enabled, reduction, DEFAULT_NATIVE_DISTANCE);
    }

    public static int select(double distance, double fovDegrees, int previous, boolean enabled, int reduction, int nativeDistance) {
        reduction = clampReduction(reduction);
        double nativeRadius = clampNativeDistance(nativeDistance) * 16.0;
        if (!enabled || reduction == 0 || !Double.isFinite(distance) || distance <= nativeRadius || !Double.isFinite(fovDegrees)
                || fovDegrees <= 0 || fovDegrees >= 180) return 1;
        double strength = switch (reduction) {
            case 1 -> 0.5;
            case 2 -> 0.75;
            case 4 -> 1.5;
            case 5 -> 3.0;
            default -> 1.0;
        };
        // Anchor the first coarsening threshold at the chosen radius at 70-degree FOV.
        // Strength controls progression beyond it; zoom can still restore native detail.
        // The hard radius check above overrides hysteresis when moving/expanding inward.
        double projectedDistance = (96.0 * 1.15 + (distance - nativeRadius) * strength)
                * Math.tan(Math.toRadians(fovDegrees) / 2) / Math.tan(Math.toRadians(70) / 2);
        int maximumCell = switch (reduction) { case 1 -> 2; case 2 -> 4; case 3 -> 8; default -> 16; };
        int selected = 1;
        for (int cell = 2; cell <= maximumCell; cell *= 2) {
            double threshold = 48.0 * cell;
            if (projectedDistance >= threshold * (cell <= previous ? 0.85 : 1.15)) selected = cell;
        }
        return selected;
    }

    public static double distance(double x, double y, double z, int sectionX, int sectionY, int sectionZ) {
        double dx = Math.max(0, Math.abs(x - (sectionX * 16.0 + 8)) - 8);
        double dy = Math.max(0, Math.abs(y - (sectionY * 16.0 + 8)) - 8);
        double dz = Math.max(0, Math.abs(z - (sectionZ * 16.0 + 8)) - 8);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
