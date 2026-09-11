package dev.metalcraft.client.lod;

/** Renderer-owned immutable preferences. Capabilities determine what may actually run. */
public record LodSettings(boolean enabled, Preset preset, int fullDetailChunks, double errorPixels,
        Shading shading, int horizonChunks, boolean smoothTransitions, int meshBudgetMiB,
        Work backgroundWork, boolean diskCache, int diskBudgetMiB, boolean diagnostics) {
    public enum Preset { QUALITY, BALANCED, PERFORMANCE, CUSTOM }
    public enum Shading { FULL, HALF, QUARTER, AUTO }
    public enum Work { LOW, BALANCED, HIGH }

    public LodSettings {
        preset = preset == null ? Preset.BALANCED : preset;
        fullDetailChunks = Math.clamp(fullDetailChunks, 2, 12);
        errorPixels = Double.isFinite(errorPixels) ? Math.clamp(errorPixels, 0.5, 8.0) : 2.0;
        shading = shading == null ? Shading.FULL : shading;
        horizonChunks = switch (horizonChunks) { case 32, 64, 128, 256 -> horizonChunks; default -> 16; };
        meshBudgetMiB = meshBudgetMiB == 0 ? 0 : Math.clamp(meshBudgetMiB, 128, 2048);
        backgroundWork = backgroundWork == null ? Work.BALANCED : backgroundWork;
        diskBudgetMiB = Math.clamp(diskBudgetMiB, 512, 8192);
        if (preset != Preset.CUSTOM && (fullDetailChunks != radius(preset) || errorPixels != error(preset))) {
            preset = Preset.CUSTOM;
        }
    }

    public static LodSettings defaults() {
        return new LodSettings(false, Preset.BALANCED, 4, 2, Shading.FULL, 16, true, 0,
                Work.BALANCED, true, 2048, false);
    }

    private static int radius(Preset preset) { return switch (preset) { case QUALITY -> 6; case PERFORMANCE -> 2; default -> 4; }; }
    private static double error(Preset preset) { return switch (preset) { case QUALITY -> 1; case PERFORMANCE -> 4; default -> 2; }; }

    public LodSettings withPreset(Preset value) {
        if (value == null || value == Preset.CUSTOM) return withGeometry(fullDetailChunks, errorPixels);
        return new LodSettings(enabled, value, radius(value), error(value), shading, horizonChunks,
                smoothTransitions, meshBudgetMiB, backgroundWork, diskCache, diskBudgetMiB, diagnostics);
    }

    public LodSettings withGeometry(int radius, double error) {
        return new LodSettings(enabled, Preset.CUSTOM, radius, error, shading, horizonChunks,
                smoothTransitions, meshBudgetMiB, backgroundWork, diskCache, diskBudgetMiB, diagnostics);
    }

    public LodSettings withEnabled(boolean value) {
        return new LodSettings(value, preset, fullDetailChunks, errorPixels, shading, horizonChunks,
                smoothTransitions, meshBudgetMiB, backgroundWork, diskCache, diskBudgetMiB, diagnostics);
    }
}
