package dev.metalcraft.client.lod;

/** Availability is deliberately separate from saved preferences and shader-pack selection. */
public record LodCapabilities(boolean metal, boolean geometry, boolean multiresolution, boolean extendedHorizon) {
    private static final LodCapabilities METAL = new LodCapabilities(true, false, false, false);
    private static final LodCapabilities UNAVAILABLE = new LodCapabilities(false, false, false, false);
    public static LodCapabilities current(boolean metal) {
        // Only promote these gates after the corresponding plan acceptance evidence is recorded.
        return metal ? METAL : UNAVAILABLE;
    }

    public LodSettings effective(LodSettings desired) {
        return new LodSettings(desired.enabled() && metal && geometry, desired.preset(),
                desired.fullDetailChunks(), desired.errorPixels(),
                metal && multiresolution ? desired.shading() : LodSettings.Shading.FULL,
                metal && extendedHorizon ? desired.horizonChunks() : 16, desired.smoothTransitions(),
                desired.meshBudgetMiB(), desired.backgroundWork(), desired.diskCache(),
                desired.diskBudgetMiB(), desired.diagnostics() && metal && geometry);
    }
}
