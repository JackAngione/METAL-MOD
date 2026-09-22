package dev.metalcraft.client;

import com.google.gson.JsonElement;
import dev.metalcraft.client.chunk.NativeLodSelection;

/** Strict integer preferences with defaults for missing or malformed saved values. */
public final class NativeLodSettingsCodec {
    private NativeLodSettingsCodec() { }

    public static int readReduction(JsonElement value) {
        return readInteger(value, NativeLodSelection.DEFAULT_REDUCTION, 0, 5);
    }

    public static int readNativeDistance(JsonElement value) {
        return readInteger(value, NativeLodSelection.DEFAULT_NATIVE_DISTANCE,
                NativeLodSelection.MIN_NATIVE_DISTANCE, NativeLodSelection.MAX_NATIVE_DISTANCE);
    }

    private static int readInteger(JsonElement value, int fallback, int minimum, int maximum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            return fallback;
        try {
            double number = value.getAsDouble();
            if (!Double.isFinite(number) || number != Math.rint(number)) return fallback;
            return (int)Math.clamp(number, minimum, maximum);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }
}
