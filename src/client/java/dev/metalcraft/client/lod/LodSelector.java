package dev.metalcraft.client.lod;

/** Pure screen-error selector. Visibility and nearest conservative node depth come from the caller. */
public final class LodSelector {
    private LodSelector() { }

    /** Loaded-section adjacency relaxation, applied after transition limits. Fine decisions win. */
    public static int[] balance(int[] selected, int[][] neighbors) {
        if(selected.length!=neighbors.length) throw new IllegalArgumentException("Mismatched adjacency");
        int[] result=selected.clone();
        java.util.ArrayDeque<Integer> changed=new java.util.ArrayDeque<>();
        for(int i=0;i<result.length;i++) {
            if(result[i]<-1||result[i]>4) throw new IllegalArgumentException("Invalid tier");
            for(int neighbor:neighbors[i]) if(neighbor<0||neighbor>=result.length) throw new IllegalArgumentException("Invalid neighbor index");
            changed.add(i);
        }
        while(!changed.isEmpty()) {
            int node=changed.removeFirst();
            if(result[node]<0) continue;
            for(int neighbor:neighbors[node]) {
                if(result[neighbor]<0) continue;
                if(result[neighbor]>result[node]+1) {
                    result[neighbor]=result[node]+1;
                    changed.add(neighbor);
                } else if(result[node]>result[neighbor]+1) {
                    result[node]=result[neighbor]+1;
                    changed.add(node);
                }
            }
        }
        return result;
    }

    /** Detail restoration is immediate; optional smoothing bounds coarsening to one tier per frame. */
    public static int transition(int previousTier,int selectedTier,boolean smoothing) {
        if(previousTier < -1 || previousTier > 4 || selectedTier < -1 || selectedTier > 4) throw new IllegalArgumentException("Invalid tier");
        if(previousTier<0 || selectedTier<0 || !smoothing) return selectedTier;
        return Math.min(selectedTier,previousTier+1);
    }

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
