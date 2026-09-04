package dev.metalcraft.client.shader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;

/** Persistent pack selection and per-pack option values at {@code config/metalcraft-shaders.json}. */
final class ShaderPackSettings {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private String selectedPack = ShaderPackRuntime.NONE_ID;
	private Map<String, Map<String, Object>> packs = new LinkedHashMap<>();

	String selectedPack() {
		return this.selectedPack == null || this.selectedPack.isBlank()
			? ShaderPackRuntime.NONE_ID
			: this.selectedPack;
	}

	void setSelectedPack(final String id) {
		this.selectedPack = id;
	}

	Map<String, Object> packValues(final String id) {
		return this.packs.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
	}

	static ShaderPackSettings load(final Path path) {
		if (!Files.isRegularFile(path)) {
			return new ShaderPackSettings();
		}
		try (Reader reader = Files.newBufferedReader(path)) {
			ShaderPackSettings loaded = GSON.fromJson(reader, ShaderPackSettings.class);
			return loaded == null ? new ShaderPackSettings() : loaded.normalized();
		} catch (IOException | RuntimeException error) {
			LOGGER.warn("Could not read MetalCraft shader settings from {}", path, error);
			return new ShaderPackSettings();
		}
	}

	void save(final Path path) {
		try {
			Files.createDirectories(path.getParent());
			try (Writer writer = Files.newBufferedWriter(path)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException error) {
			LOGGER.warn("Could not save MetalCraft shader settings to {}", path, error);
		}
	}

	private ShaderPackSettings normalized() {
		if (this.packs == null) {
			this.packs = new LinkedHashMap<>();
		}
		if (this.selectedPack == null || this.selectedPack.isBlank()) {
			this.selectedPack = ShaderPackRuntime.NONE_ID;
		}
		return this;
	}
}
