package dev.metalcraft.client.horizon;

/** Keep vanilla generation busy without letting pending world data grow with view distance. */
public final class HorizonGenerationBudget {
    public static final int MAX_JOBS=64, MAX_SNAPSHOTS=64, STARTS_PER_TICK=16;
    private HorizonGenerationBudget() { }

    public static int jobLimit(int processors,long averageTickNanos,int pendingSnapshots) {
        // Leave headroom in the 50 ms server tick and room for the client to catch up.
        // Already admitted jobs still drain; pressure only stops new admissions.
        if(averageTickNanos>=45_000_000L || pendingSnapshots>=48) return 0;
        int capacity=Math.clamp((long)processors*4,16,MAX_JOBS);
        if(averageTickNanos>=35_000_000L) capacity=Math.min(capacity,16);
        return Math.min(capacity,MAX_SNAPSHOTS-Math.max(0,pendingSnapshots));
    }
}
