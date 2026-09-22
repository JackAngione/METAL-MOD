package dev.metalcraft.client.lod;

/** Availability is deliberately separate from saved preferences and shader-pack selection. */
public record LodCapabilities(boolean metal, boolean geometry, boolean multiresolution, boolean extendedHorizon) {
    // Native full-detail distance is the active architecture. Resolution LOD is deferred.
    public static final boolean HORIZON_AVAILABLE = false;
    public static final boolean GEOMETRY_AVAILABLE = false;
    private static final LodCapabilities METAL = new LodCapabilities(true, GEOMETRY_AVAILABLE, false, HORIZON_AVAILABLE);
    private static final LodCapabilities METAL_UNSUPPORTED = new LodCapabilities(true, false, false, false);
    private static final LodCapabilities UNAVAILABLE = new LodCapabilities(false, false, false, false);
    public static LodCapabilities current(boolean metal) {
        // Standard and no-pack composition are qualified; other packs retain their ordinary path.
        if (!metal) return UNAVAILABLE;
        if (GEOMETRY_AVAILABLE) {
            var runtime = dev.metalcraft.client.shader.ShaderPackRuntime.active();
            if (runtime != null && !dev.metalcraft.client.shader.ShaderPackRuntime.NONE_ID.equals(runtime.selectedPackId())
                    && (!dev.metalcraft.client.shader.ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId()) || !runtime.isActive()))
                return METAL_UNSUPPORTED;
        }
        return METAL;
    }

    public LodSettings effective(LodSettings desired) {
        return new LodSettings(desired.enabled() && metal && geometry, desired.preset(),
                desired.fullDetailChunks(), desired.errorPixels(),
                metal && multiresolution ? desired.shading() : LodSettings.Shading.FULL,
                metal && extendedHorizon ? desired.horizonChunks() : 16, desired.smoothTransitions(),
                desired.meshBudgetMiB(), desired.backgroundWork(), desired.diskCache(),
                desired.diskBudgetMiB(), desired.diagnostics() && metal && geometry, desired.generateTerrain());
    }
}
