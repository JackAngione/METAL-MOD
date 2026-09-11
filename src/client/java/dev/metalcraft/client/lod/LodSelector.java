package dev.metalcraft.client.lod;

/** Pure screen-error selector. Visibility and nearest conservative node depth come from the caller. */
public final class LodSelector {
    private LodSelector() { }

    public static int select(boolean visible, double nearestDepth, double distanceToBounds,
            double nearPlane, int sceneHeight, double verticalFovRadians, LodSettings settings,
            double[] worldErrors, int previousTier) {
        if (!visible) return -1;
        if (!settings.enabled() || !Double.isFinite(nearestDepth) || !Double.isFinite(distanceToBounds)
                || !Double.isFinite(nearPlane) || nearPlane <= 0 || nearestDepth <= nearPlane
                || distanceToBounds <= settings.fullDetailChunks() * 16.0 || sceneHeight <= 0
                || !Double.isFinite(verticalFovRadians) || verticalFovRadians <= 0 || verticalFovRadians >= Math.PI) return 0;
        double scale = sceneHeight / (2 * Math.tan(verticalFovRadians / 2) * nearestDepth);
        int selected = 0;
        for (int tier = 1; tier < worldErrors.length; tier++) {
            double error = worldErrors[tier];
            if (!Double.isFinite(error) || error < 0) continue; // missing tier cannot be selected
            // Previously selected/coarser tiers get 20% retention; new coarsening needs 20% headroom.
            double tolerance = settings.errorPixels() * (tier <= previousTier ? 1.2 : 0.8);
            if (error * scale <= tolerance) selected = tier;
        }
        return selected;
    }
}
