package dev.metalcraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.metalcraft.client.lod.LodSettings;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/** Persistent client-side settings owned by MetalCraft. */
public final class MetalCraftConfig {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("metalcraft.json");
	private static Data data = load();

	private MetalCraftConfig() {
	}

	/** Re-read renderer preferences; resources adopt changes at their own frame boundary. */
	public static synchronized void reload() {
		data = load();
	}

	public static synchronized boolean halfResolution() {
		return data.halfResolution;
	}

	public static synchronized void setHalfResolution(final boolean enabled) {
		if (data.halfResolution == enabled) {
			return;
		}
		data.halfResolution = enabled;
		save();
	}

	/** Whether MetalCraft removes Minecraft's frame limit and forces the layer to present unsynced. */
	public static synchronized boolean unlockedFrameRate() {
		return data.unlockedFrameRate;
	}

	public static synchronized boolean clearDistanceFog() {
		return data.clearDistanceFog;
	}

	/** Distant terrain between the native distance and Minecraft's render distance. */
	public static synchronized boolean lodEnabled() { return data.lodEnabled; }

	public static synchronized void setLodEnabled(final boolean enabled) {
		if (data.lodEnabled == enabled) return;
		data.lodEnabled = enabled;
		save();
	}

	/** Radius of ordinary full-quality chunks while distant terrain is enabled. */
	public static synchronized int lodNativeDistance() { return data.lodNativeDistance; }

	public static synchronized void setLodNativeDistance(final int chunks) {
		int clamped = LodSettings.clampNativeDistance(chunks);
		if (data.lodNativeDistance == clamped) return;
		data.lodNativeDistance = clamped;
		save();
	}

	/** How slowly distant terrain loses detail, from 1 (fastest falloff) to 8. */
	public static synchronized int lodDetail() { return data.lodDetail; }

	public static synchronized void setLodDetail(final int detail) {
		int clamped = LodSettings.clampDetail(detail);
		if (data.lodDetail == clamped) return;
		data.lodDetail = clamped;
		save();
	}

	public static synchronized void setClearDistanceFog(final boolean enabled) {
		if (data.clearDistanceFog == enabled) return;
		data.clearDistanceFog = enabled;
		save();
	}

	public static synchronized void setUnlockedFrameRate(final boolean enabled) {
		if (data.unlockedFrameRate == enabled) {
			return;
		}
		data.unlockedFrameRate = enabled;
		save();
	}

	private static Data load() {
		if (!Files.isRegularFile(PATH)) {
			return new Data();
		}

		try (Reader reader = Files.newBufferedReader(PATH)) {
			JsonObject json = GSON.fromJson(reader, JsonObject.class);
			if (json == null) return new Data();
			Data loaded = new Data();
			loaded.halfResolution = readBoolean(json, "halfResolution");
			loaded.unlockedFrameRate = readBoolean(json, "unlockedFrameRate");
			loaded.clearDistanceFog = readBoolean(json, "clearDistanceFog");
			// The prototype's on/off switch is not carried over; its native radius is.
			loaded.lodEnabled = readBoolean(json, "lodEnabled", LodSettings.DEFAULT_ENABLED);
			loaded.lodNativeDistance = LodSettings.clampNativeDistance(readInteger(json.has("lodNativeDistance")
				? json.get("lodNativeDistance") : json.get("nativeQualityDistance"), LodSettings.DEFAULT_NATIVE_DISTANCE));
			loaded.lodDetail = LodSettings.clampDetail(readInteger(json.get("lodDetail"), LodSettings.DEFAULT_DETAIL));
			return loaded;
		} catch (IOException | RuntimeException error) {
			LOGGER.warn("Could not read MetalCraft settings from {}", PATH, error);
			return new Data();
		}
	}

	private static boolean readBoolean(final JsonObject json, final String key) {
		return readBoolean(json, key, false);
	}

	private static boolean readBoolean(final JsonObject json, final String key, final boolean fallback) {
		var value = json.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : fallback;
	}

	/** Whole numbers only; missing or malformed values use the fallback. */
	private static int readInteger(final JsonElement value, final int fallback) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return fallback;
		double number = value.getAsDouble();
		return Double.isFinite(number) && number == Math.rint(number) ? (int)Math.clamp(number, Integer.MIN_VALUE, Integer.MAX_VALUE) : fallback;
	}

	private static void save() {
		Path temporary = null;
		try {
			Files.createDirectories(PATH.getParent());
			temporary = Files.createTempFile(PATH.getParent(), "metalcraft-", ".json.tmp");
			try (Writer writer = Files.newBufferedWriter(temporary)) {
				GSON.toJson(data, writer);
			}
			try {
				Files.move(temporary, PATH, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			} catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
				Files.move(temporary, PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException error) {
			LOGGER.warn("Could not save MetalCraft settings to {}", PATH, error);
		} finally {
			if (temporary != null) {
				try { Files.deleteIfExists(temporary); }
				catch (IOException error) { LOGGER.warn("Could not remove temporary settings file", error); }
			}
		}
	}

	private static final class Data {
		private boolean halfResolution;
		private boolean unlockedFrameRate;
		private boolean clearDistanceFog;
		private boolean lodEnabled = LodSettings.DEFAULT_ENABLED;
		private int lodNativeDistance = LodSettings.DEFAULT_NATIVE_DISTANCE;
		private int lodDetail = LodSettings.DEFAULT_DETAIL;
	}
}
