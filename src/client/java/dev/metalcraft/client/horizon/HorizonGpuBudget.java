package dev.metalcraft.client.horizon;

/** A bounded share of Metal's recommended working set, including in-flight retired meshes. */
public final class HorizonGpuBudget {
    private HorizonGpuBudget() { }
    public static long bytes(long recommendedWorkingSet) {
        if(recommendedWorkingSet<=0) return 256L<<20;
        return Math.clamp(recommendedWorkingSet/16,128L<<20,1024L<<20);
    }
}
