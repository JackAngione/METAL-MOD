package dev.metalcraft.client.lod;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Missing and malformed fields recover independently, without losing unrelated preferences. */
public final class LodSettingsCodec {
    private LodSettingsCodec() { }

    public static LodSettings read(JsonElement json) {
        if (json == null || !json.isJsonObject()) return LodSettings.defaults();
        JsonObject value = json.getAsJsonObject();
        LodSettings d = LodSettings.defaults();
        return new LodSettings(bool(value, "enabled", d.enabled()),
                enumeration(value, "preset", d.preset()), integer(value, "fullDetailChunks", d.fullDetailChunks()),
                number(value, "errorPixels", d.errorPixels()), enumeration(value, "shading", d.shading()),
                integer(value, "horizonChunks", d.horizonChunks()), bool(value, "smoothTransitions", d.smoothTransitions()),
                integer(value, "meshBudgetMiB", d.meshBudgetMiB()), enumeration(value, "backgroundWork", d.backgroundWork()),
                bool(value, "diskCache", d.diskCache()), integer(value, "diskBudgetMiB", d.diskBudgetMiB()),
                bool(value, "diagnostics", d.diagnostics()));
    }

    private static boolean bool(JsonObject v, String k, boolean fallback) {
        JsonElement e = v.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : fallback;
    }

    private static double number(JsonObject v, String k, double fallback) {
        try {
            JsonElement e = v.get(k);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return fallback;
            double n = e.getAsDouble();
            return Double.isFinite(n) ? n : fallback;
        } catch (RuntimeException ignored) { return fallback; }
    }

    private static int integer(JsonObject v, String k, int fallback) {
        double n = number(v, k, fallback);
        return n == Math.rint(n) && n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE ? (int)n : fallback;
    }

    private static <E extends Enum<E>> E enumeration(JsonObject v, String k, E fallback) {
        try { return Enum.valueOf(fallback.getDeclaringClass(), v.get(k).getAsString()); }
        catch (RuntimeException ignored) { return fallback; }
    }
}
