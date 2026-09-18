package dev.metalcraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.metalcraft.client.lod.LodSettings;
import dev.metalcraft.client.lod.LodSettingsCodec;
import dev.metalcraft.client.lod.LodFrameSettings;
import dev.metalcraft.client.lod.LodCapabilities;
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
	private static final LodFrameSettings LOD_FRAMES = new LodFrameSettings();
	static { LOD_FRAMES.request(data.lod); }

	private MetalCraftConfig() {
	}

	public static synchronized LodSettings lod() {
		return data.lod;
	}

	/** Re-read renderer preferences; resources adopt changes at their own frame boundary. */
	public static synchronized void reload() {
		data = load();
		LOD_FRAMES.request(data.lod);
	}

	public static LodSettings beginLodFrame(final boolean metal) {
		return LOD_FRAMES.beginFrame(LodCapabilities.current(metal));
	}

	public static synchronized void setLod(final LodSettings settings) {
		if (data.lod.equals(settings)) return;
		data.lod = java.util.Objects.requireNonNull(settings);
		LOD_FRAMES.request(settings);
		save();
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
			loaded.lod = LodSettingsCodec.read(json.get("lod"));
			return loaded;
		} catch (IOException | RuntimeException error) {
			LOGGER.warn("Could not read MetalCraft settings from {}", PATH, error);
			return new Data();
		}
	}

	private static boolean readBoolean(final JsonObject json, final String key) {
		var value = json.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
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
		private LodSettings lod = LodSettings.defaults();
	}
}
