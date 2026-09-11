package dev.metalcraft.client.lod;

/** Pure screen-error selector. Visibility and nearest conservative node depth come from the caller. */
public final class LodSelector {
    private LodSelector() { }

    /** Loaded-section adjacency relaxation, applied after transition limits. Fine decisions win. */
    public static int[] balance(int[] selected, int[][] neighbors) {
        int[] available = new int[selected.length];
        java.util.Arrays.fill(available, 31);
        return resolveLoaded(selected, selected, available, neighbors, false);
    }

    /**
     * Final loaded-section ownership decision. Bit zero is the ordinary mesh; bits 1–4
     * describe fully uploaded, current, preflighted replacements. Missing tiers fall
     * back toward detail, never to a coarser mesh exceeding the screen-error decision.
     * Reapply availability after every neighbor refinement, until both contracts hold.
     */
    public static int[] resolveLoaded(int[] candidates, int[] previous, int[] available,
            int[][] neighbors, boolean smoothing) {
        int count = candidates.length;
        if (previous.length != count || available.length != count || neighbors.length != count)
            throw new IllegalArgumentException("Mismatched selection inputs");
        // Make edges reciprocal: a refinement must wake all dependents even if the
        // caller supplied each shared boundary only once.
        int[] degree = new int[count];
        for (int node = 0; node < count; node++) for (int neighbor : neighbors[node]) {
            if (neighbor < 0 || neighbor >= count) throw new IllegalArgumentException("Invalid neighbor index");
            degree[node]++;
            degree[neighbor]++;
        }
        int[][] adjacent = new int[count][];
        for (int node = 0; node < count; node++) adjacent[node] = new int[degree[node]];
        java.util.Arrays.fill(degree, 0);
        for (int node = 0; node < count; node++) for (int neighbor : neighbors[node]) {
            adjacent[node][degree[node]++] = neighbor;
            adjacent[neighbor][degree[neighbor]++] = node;
        }
        int[] result = new int[count];
        boolean[] queued = new boolean[count];
        java.util.ArrayDeque<Integer> changed = new java.util.ArrayDeque<>();
        for (int node = 0; node < count; node++) {
            if ((available[node] & 1) == 0 || (available[node] & ~31) != 0)
                throw new IllegalArgumentException("Loaded sections require ordinary fallback and valid tier bits");
            result[node] = availableAtMost(transition(previous[node], candidates[node], smoothing), available[node]);
            changed.add(node);
            queued[node] = true;
        }
        while (!changed.isEmpty()) {
            int node = changed.removeFirst();
            queued[node] = false;
            if (result[node] < 0) continue;
            for (int neighbor : adjacent[node]) {
                if (result[neighbor] <= result[node] + 1) continue;
                result[neighbor] = availableAtMost(result[node] + 1, available[neighbor]);
                if (!queued[neighbor]) { changed.add(neighbor); queued[neighbor] = true; }
            }
        }
        return result;
    }

    private static int availableAtMost(int tier, int available) {
        while (tier > 0 && (available & (1 << tier)) == 0) tier--;
        return tier;
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
        for (int tier = 1; tier < Math.min(5, worldErrors.length); tier++) {
            double error = worldErrors[tier];
            if (!Double.isFinite(error) || error < 0) continue; // missing tier cannot be selected
            // Previously selected/coarser tiers get 20% retention; new coarsening needs 20% headroom.
            double tolerance = settings.errorPixels() * (tier <= previousTier ? 1.2 : 0.8);
            if (error * scale <= tolerance) selected = tier;
        }
        return selected;
    }
}
