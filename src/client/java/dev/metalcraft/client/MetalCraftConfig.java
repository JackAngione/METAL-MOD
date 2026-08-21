package dev.metalcraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

	private static Data load() {
		if (!Files.isRegularFile(PATH)) {
			return new Data();
		}

		try (Reader reader = Files.newBufferedReader(PATH)) {
			Data loaded = GSON.fromJson(reader, Data.class);
			return loaded != null ? loaded : new Data();
		} catch (IOException | RuntimeException error) {
			LOGGER.warn("Could not read MetalCraft settings from {}", PATH, error);
			return new Data();
		}
	}

	private static void save() {
		try {
			Files.createDirectories(PATH.getParent());
			try (Writer writer = Files.newBufferedWriter(PATH)) {
				GSON.toJson(data, writer);
			}
		} catch (IOException error) {
			LOGGER.warn("Could not save MetalCraft settings to {}", PATH, error);
		}
	}

	private static final class Data {
		private boolean halfResolution;
	}
}
