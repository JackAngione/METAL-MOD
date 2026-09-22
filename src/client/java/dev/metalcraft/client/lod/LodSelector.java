package dev.metalcraft.client.lod;

/** Pure screen-error selector. Visibility and nearest conservative node depth come from the caller. */
public final class LodSelector {
    private LodSelector() { }

    /** Loaded-section adjacency relaxation, applied after transition limits. Fine decisions win. */
    public static int[] balance(int[] selected, int[][] neighbors) {
        return balance(selected, adjacency(neighbors));
    }

    static int[] balance(int[] selected, Adjacency neighbors) {
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
        return resolveLoaded(candidates, previous, available, adjacency(neighbors), smoothing);
    }

    /** Immutable reciprocal graph, shared by admission and final resolution within one frame. */
    static final class Adjacency {
        private final int[] offsets;
        private final int[] nodes;

        private Adjacency(int[] offsets, int[] nodes) {
            this.offsets = offsets;
            this.nodes = nodes;
        }
    }

    static Adjacency adjacency(int[][] neighbors) {
        int count = neighbors.length;
        int[] degree = new int[count];
        for (int node = 0; node < count; node++) for (int neighbor : neighbors[node]) {
            if (neighbor < 0 || neighbor >= count) throw new IllegalArgumentException("Invalid neighbor index");
            degree[node]++;
            degree[neighbor]++;
        }
        int[] offsets = new int[count + 1];
        for (int node = 0; node < count; node++) offsets[node + 1] = Math.addExact(offsets[node], degree[node]);
        int[] nodes = new int[offsets[count]];
        java.util.Arrays.fill(degree, 0);
        for (int node = 0; node < count; node++) for (int neighbor : neighbors[node]) {
            nodes[offsets[node] + degree[node]++] = neighbor;
            nodes[offsets[neighbor] + degree[neighbor]++] = node;
        }
        return new Adjacency(offsets, nodes);
    }

    static int[] resolveLoaded(int[] candidates, int[] previous, int[] available,
            Adjacency adjacent, boolean smoothing) {
        int count = candidates.length;
        if (previous.length != count || available.length != count || adjacent.offsets.length != count + 1)
            throw new IllegalArgumentException("Mismatched selection inputs");
        int[] result = new int[count];
        boolean[] queued = new boolean[count];
        // At most one queued entry per node. A fixed ring avoids per-frame boxing
        // and retains FIFO propagation even when a processed node is refined again.
        int[] changed = new int[count];
        int head = 0, tail = 0, pending = count;
        for (int node = 0; node < count; node++) {
            if ((available[node] & 1) == 0 || (available[node] & ~31) != 0)
                throw new IllegalArgumentException("Loaded sections require ordinary fallback and valid tier bits");
            result[node] = availableAtMost(transition(previous[node], candidates[node], smoothing), available[node]);
            changed[node] = node;
            queued[node] = true;
        }
        while (pending > 0) {
            int node = changed[head];
            if (++head == count) head = 0;
            pending--;
            queued[node] = false;
            if (result[node] < 0) continue;
            for (int edge = adjacent.offsets[node]; edge < adjacent.offsets[node + 1]; edge++) {
                int neighbor = adjacent.nodes[edge];
                if (result[neighbor] <= result[node] + 1) continue;
                result[neighbor] = availableAtMost(result[node] + 1, available[neighbor]);
                if (!queued[neighbor]) {
                    changed[tail] = neighbor;
                    if (++tail == count) tail = 0;
                    pending++;
                    queued[neighbor] = true;
                }
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
