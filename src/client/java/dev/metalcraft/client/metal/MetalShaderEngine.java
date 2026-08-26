package dev.metalcraft.client.metal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.logging.LogUtils;
import dev.metalcraft.client.shader.ShaderGraphCompiler;
import dev.metalcraft.client.shader.ShaderPack;
import dev.metalcraft.client.shader.ShaderPackLoader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

/** Loads, compiles, and presents the selected first-party MSL shader pack. */
public final class MetalShaderEngine implements AutoCloseable {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type SETTINGS_TYPE = new TypeToken<SettingsData>() { }.getType();
	private static final String BUILTIN_ID = "metalcraft-standard";
	private static final String BUILTIN_ROOT = "assets/metalcraft/shaderpacks/standard";
	private static volatile MetalShaderEngine active;

	public record PackChoice(String id, String name) {
	}

	private final MetalDevice device;
	private final Path shaderpacksRoot;
	private final Path settingsPath;
	private final MetalPipelineCache pipelineCache;
	private final Map<String, ShaderPackLoader.PackRef> discovered = new LinkedHashMap<>();
	private final Map<String, MetalTexture> targets = new LinkedHashMap<>();
	private final SettingsData settings;
	private ShaderPack pack;
	private ShaderGraphCompiler.CompiledGraph graph;
	private MetalRenderPipeline finalPipeline;
	private MetalBuffer uniforms;
	private MetalSampler sceneSampler;
	private int width = 1;
	private int height = 1;
	private boolean closed;

	public static MetalShaderEngine createDefault(final MetalDevice device) {
		FabricLoader loader = FabricLoader.getInstance();
		return new MetalShaderEngine(
			device,
			loader.getGameDir().resolve("shaderpacks"),
			loader.getConfigDir().resolve("metalcraft-shaders.json")
		);
	}

	public static MetalShaderEngine active() {
		return active;
	}

	public MetalShaderEngine(final MetalDevice device, final Path shaderpacksRoot, final Path settingsPath) {
		this.device = Objects.requireNonNull(device, "device");
		this.shaderpacksRoot = Objects.requireNonNull(shaderpacksRoot, "shaderpacksRoot").toAbsolutePath().normalize();
		this.settingsPath = Objects.requireNonNull(settingsPath, "settingsPath").toAbsolutePath().normalize();
		this.pipelineCache = new MetalPipelineCache(device, this.shaderpacksRoot.resolve(".cache"));
		this.settings = this.loadSettings();
		try {
			Files.createDirectories(this.shaderpacksRoot);
			this.refreshDiscovery();
			String selected = this.settings.selectedPack == null ? BUILTIN_ID : this.settings.selectedPack;
			try {
				this.loadPack(selected);
			} catch (IOException | RuntimeException error) {
				if (BUILTIN_ID.equals(selected)) {
					throw error;
				}
				LOGGER.warn("Could not restore shader pack '{}'; falling back to the built-in pack", selected, error);
				this.loadPack(BUILTIN_ID);
				this.settings.selectedPack = BUILTIN_ID;
				this.saveSettings();
			}
		} catch (IOException | RuntimeException error) {
			this.close();
			throw new IllegalStateException("Could not initialize MetalCraft shader packs", error);
		}
		active = this;
	}

	public synchronized List<PackChoice> availablePacks() {
		this.requireOpen();
		List<PackChoice> choices = new ArrayList<>();
		try {
			ShaderPack builtin = this.loadById(BUILTIN_ID);
			choices.add(new PackChoice(BUILTIN_ID, builtin.manifest().name()));
			this.refreshDiscovery();
			for (ShaderPackLoader.PackRef ref : this.discovered.values()) {
				try {
					ShaderPack candidate = ShaderPackLoader.load(ref);
					ShaderGraphCompiler.CompiledGraph candidateGraph = ShaderGraphCompiler.compile(candidate);
					validatePhaseOneGraph(candidate, candidateGraph);
					Map<String, Object> defaults = new LinkedHashMap<>();
					for (ShaderPack.Option option : candidate.manifest().options()) {
						defaults.put(option.id(), option.defaultValue());
					}
					try (MetalRenderPipeline ignored = this.createFinalPipeline(candidate, candidateGraph, defaults)) {
						// Pipeline creation validates the pack's MSL and entry-point conventions.
					}
					choices.add(new PackChoice(candidate.id(), candidate.manifest().name()));
				} catch (IOException | RuntimeException error) {
					LOGGER.warn("Ignoring invalid shader pack {}", ref.path(), error);
				}
			}
		} catch (IOException error) {
			LOGGER.warn("Could not refresh shader pack discovery", error);
		}
		return List.copyOf(choices);
	}

	public synchronized String selectedPackId() {
		this.requireOpen();
		return this.pack.id();
	}

	public synchronized String selectedPackName() {
		this.requireOpen();
		return this.pack.manifest().name();
	}

	public synchronized List<ShaderPack.Option> options() {
		this.requireOpen();
		return this.pack.manifest().options();
	}

	public synchronized Object optionValue(final String id) {
		this.requireOpen();
		ShaderPack.Option option = this.option(id);
		return this.packSettings().getOrDefault(id, option.defaultValue());
	}

	/** Selecting the active pack again is an explicit hot reload from disk. */
	public synchronized void selectPack(final String id) {
		this.requireOpen();
		try {
			this.refreshDiscovery();
			this.loadPack(id);
			this.settings.selectedPack = id;
			this.saveSettings();
		} catch (IOException | RuntimeException error) {
			if ((this.finalPipeline == null || this.uniforms == null || this.sceneSampler == null) && !BUILTIN_ID.equals(id)) {
				try {
					this.loadPack(BUILTIN_ID);
					this.settings.selectedPack = BUILTIN_ID;
					this.saveSettings();
				} catch (IOException | RuntimeException fallbackFailure) {
					error.addSuppressed(fallbackFailure);
				}
			}
			throw new IllegalStateException("Could not load shader pack '" + id + "'", error);
		}
	}

	public synchronized void setOption(final String id, final Object value) {
		this.requireOpen();
		ShaderPack.Option option = this.option(id);
		Object normalized = normalizeOption(option, value);
		Map<String, Object> values = this.packSettings();
		Object previous = values.put(id, normalized);
		try {
			switch (option.apply()) {
				case UNIFORM -> this.writeUniforms();
				case RECOMPILE -> this.compileFinalPipeline();
				case RELOAD -> this.allocateTargets();
			}
			this.saveSettings();
		} catch (RuntimeException error) {
			if (previous == null) {
				values.remove(id);
			} else {
				values.put(id, previous);
			}
			if (option.apply() == ShaderPack.ApplyMode.UNIFORM) {
				try {
					this.writeUniforms();
				} catch (RuntimeException restoreFailure) {
					error.addSuppressed(restoreFailure);
				}
			}
			throw error;
		}
	}

	public synchronized void resize(final int width, final int height) {
		this.requireOpen();
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Shader graph dimensions must be positive");
		}
		if (this.width != width || this.height != height) {
			this.width = width;
			this.height = height;
			this.allocateTargets();
		}
	}

	/** Re-reads the selected pack, used by Minecraft's resource-reload pipeline. */
	public synchronized void reload() {
		this.selectPack(this.pack.id());
	}

	void encode(final MetalCommandBuffer commands, final MetalTexture scene, final MetalDrawable drawable) {
		this.encodeTarget(commands, scene, drawable);
	}

	void encodeForTesting(final MetalCommandBuffer commands, final MetalTexture scene, final MetalTexture output) {
		this.encodeTarget(commands, scene, output);
	}

	private void encodeTarget(
		final MetalCommandBuffer commands,
		final MetalTexture scene,
		final MetalRenderPass.ColorTarget output
	) {
		synchronized (this) {
			this.requireOpen();
			ShaderGraphCompiler.WriteDecision drawable = this.graph.passes().getFirst().writes().stream()
				.filter(write -> write.target().equals("drawable"))
				.findFirst()
				.orElseThrow();
			try (MetalTextureView sceneView = scene.createView();
				 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					 new MetalRenderPass.ColorAttachment(
						 output, loadAction(drawable.loadAction()), storeAction(drawable.storeAction()),
						 0.0, 0.0, 0.0, 1.0)))) {
				pass.setPipeline(this.finalPipeline);
				pass.setTexture(0, sceneView, MetalRenderPass.STAGE_FRAGMENT);
				pass.setSampler(0, this.sceneSampler, MetalRenderPass.STAGE_FRAGMENT);
				pass.setUniformBuffer(0, this.uniforms, 0L, MetalRenderPass.STAGE_FRAGMENT);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
			}
		}
	}

	@Override
	public synchronized void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.closeResources();
		if (active == this) {
			active = null;
		}
	}

	private void loadPack(final String id) throws IOException {
		ShaderPack loaded = this.loadById(id);
		ShaderGraphCompiler.CompiledGraph compiled = ShaderGraphCompiler.compile(loaded);
		validatePhaseOneGraph(loaded, compiled);
		this.closeResources();
		this.pack = loaded;
		this.graph = compiled;
		this.settings.packs.computeIfAbsent(id, ignored -> new LinkedHashMap<>());
		Map<String, Object> values = this.settings.packs.get(id);
		for (ShaderPack.Option option : loaded.manifest().options()) {
			Object persisted = values.get(option.id());
			try {
				values.put(option.id(), persisted == null ? option.defaultValue() : normalizeOption(option, persisted));
			} catch (IllegalArgumentException error) {
				LOGGER.warn("Resetting invalid persisted value for shader option '{}.{}'", id, option.id());
				values.put(option.id(), option.defaultValue());
			}
		}
		int uniformCount = (int)loaded.manifest().options().stream()
			.filter(option -> option.apply() == ShaderPack.ApplyMode.UNIFORM)
			.count();
		this.uniforms = this.device.createBuffer(Math.max(16L, uniformCount * 4L), MetalBuffer.StorageMode.SHARED);
		this.compileFinalPipeline();
		this.allocateTargets();
		this.writeUniforms();
		LOGGER.info("Loaded MetalCraft shader pack '{}' with {} pass and {} option(s)",
			loaded.manifest().name(), compiled.passes().size(), loaded.manifest().options().size());
	}

	private ShaderPack loadById(final String id) throws IOException {
		if (BUILTIN_ID.equals(id)) {
			return ShaderPackLoader.loadBundled(MetalShaderEngine.class.getClassLoader(), BUILTIN_ID, BUILTIN_ROOT);
		}
		ShaderPackLoader.PackRef ref = this.discovered.get(id);
		if (ref == null) {
			throw new ShaderPackLoader.LoadException("Unknown shader pack '" + id + "'");
		}
		return ShaderPackLoader.load(ref);
	}

	private void compileFinalPipeline() {
		MetalRenderPipeline replacement = this.createFinalPipeline(this.pack, this.graph, this.packSettings());
		MetalRenderPipeline previous = this.finalPipeline;
		this.finalPipeline = replacement;
		if (previous != null) {
			previous.close();
		}
	}

	private MetalRenderPipeline createFinalPipeline(
		final ShaderPack selectedPack,
		final ShaderGraphCompiler.CompiledGraph selectedGraph,
		final Map<String, Object> values
	) {
		String source = combinedSource(selectedPack);
		StringBuilder preamble = new StringBuilder();
		for (ShaderPack.Option option : selectedPack.manifest().options()) {
			if (option.apply() == ShaderPack.ApplyMode.RECOMPILE) {
				preamble.append("#define MC_OPTION_")
					.append(option.id().toUpperCase(java.util.Locale.ROOT).replace('.', '_').replace('-', '_'))
					.append(' ').append(compileConstant(option, values.getOrDefault(option.id(), option.defaultValue()))).append('\n');
			}
		}
		String configuredSource = preamble + source;
		ShaderPack.Pass pass = selectedGraph.passes().getFirst().declaration();
		return this.pipelineCache.createRenderPipeline(new MetalRenderPipeline.Descriptor(
			configuredSource, pass.id() + "_vertex", configuredSource, pass.id() + "_fragment",
			List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.BGRA8_UNORM)), null,
			MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
			MetalRenderPipeline.RasterState.DEFAULT
		));
	}

	private void allocateTargets() {
		Map<String, MetalTexture> replacements = new LinkedHashMap<>();
		MetalSampler replacementSampler = null;
		try {
			for (Map.Entry<String, ShaderGraphCompiler.TargetInfo> entry : this.graph.targets().entrySet()) {
				if (entry.getValue().producer().isEmpty()) {
					continue;
				}
				ShaderPack.Target target = entry.getValue().declaration();
				int targetWidth;
				int targetHeight;
				if (target.extent() instanceof ShaderPack.Scale scale) {
					targetWidth = Math.max(1, (int)Math.round(this.width * scale.value()));
					targetHeight = Math.max(1, (int)Math.round(this.height * scale.value()));
				} else {
					int size = ((ShaderPack.FixedSize)target.extent()).value();
					targetWidth = size;
					targetHeight = size;
				}
				MetalTexture.Format format = MetalTexture.Format.valueOf(target.format().name());
				MetalTexture.Descriptor descriptor = entry.getValue().memoryless()
					? MetalTexture.Descriptor.memoryless(format, targetWidth, targetHeight)
					: target.layers() > 1
						? MetalTexture.Descriptor.array(format, targetWidth, targetHeight, target.layers(), MetalTexture.USAGE_ALL)
						: new MetalTexture.Descriptor(format, targetWidth, targetHeight, 1, MetalTexture.USAGE_ALL);
				replacements.put(entry.getKey(), this.device.createTexture(descriptor));
			}
			MetalSampler.Filter filter = this.pack.manifest().options().stream()
				.filter(option -> option.apply() == ShaderPack.ApplyMode.RELOAD && option.id().equals("upscale_filter"))
				.findFirst()
				.map(option -> "nearest".equals(this.optionValue(option.id())) ? MetalSampler.Filter.NEAREST : MetalSampler.Filter.LINEAR)
				.orElse(MetalSampler.Filter.LINEAR);
			replacementSampler = this.device.createSampler(new MetalSampler.Descriptor(
				filter, filter, MetalSampler.AddressMode.CLAMP_TO_EDGE));
		} catch (RuntimeException error) {
			replacements.values().forEach(MetalTexture::close);
			if (replacementSampler != null) {
				replacementSampler.close();
			}
			throw error;
		}
		this.targets.values().forEach(MetalTexture::close);
		this.targets.clear();
		this.targets.putAll(replacements);
		if (this.sceneSampler != null) {
			this.sceneSampler.close();
		}
		this.sceneSampler = replacementSampler;
	}

	private void writeUniforms() {
		try (MetalBuffer.Mapping mapping = this.uniforms.map()) {
			var bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			for (ShaderPack.Option option : this.pack.manifest().options()) {
				if (option.apply() != ShaderPack.ApplyMode.UNIFORM) {
					continue;
				}
				Object value = this.optionValue(option.id());
				switch (option.type()) {
					case BOOL -> bytes.putInt(Boolean.TRUE.equals(value) ? 1 : 0);
					case INT -> bytes.putInt(((Number)value).intValue());
					case FLOAT -> bytes.putFloat(((Number)value).floatValue());
					case ENUM -> bytes.putInt(option.values().indexOf(value));
				}
			}
		}
	}

	private ShaderPack.Option option(final String id) {
		return this.pack.manifest().options().stream()
			.filter(option -> option.id().equals(id))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown shader option '" + id + "'"));
	}

	private Map<String, Object> packSettings() {
		return this.settings.packs.computeIfAbsent(this.pack.id(), ignored -> new LinkedHashMap<>());
	}

	private void refreshDiscovery() throws IOException {
		this.discovered.clear();
		for (ShaderPackLoader.PackRef ref : ShaderPackLoader.discover(this.shaderpacksRoot)) {
			this.discovered.put(ref.id(), ref);
		}
	}

	private SettingsData loadSettings() {
		if (!Files.isRegularFile(this.settingsPath)) {
			return new SettingsData();
		}
		try (Reader reader = Files.newBufferedReader(this.settingsPath)) {
			SettingsData loaded = GSON.fromJson(reader, SETTINGS_TYPE);
			return loaded == null ? new SettingsData() : loaded.normalized();
		} catch (IOException | RuntimeException error) {
			LOGGER.warn("Could not read MetalCraft shader settings from {}", this.settingsPath, error);
			return new SettingsData();
		}
	}

	private void saveSettings() {
		try {
			Files.createDirectories(this.settingsPath.getParent());
			try (Writer writer = Files.newBufferedWriter(this.settingsPath)) {
				GSON.toJson(this.settings, SETTINGS_TYPE, writer);
			}
		} catch (IOException error) {
			LOGGER.warn("Could not save MetalCraft shader settings to {}", this.settingsPath, error);
		}
	}

	private void closeResources() {
		this.targets.values().forEach(MetalTexture::close);
		this.targets.clear();
		if (this.uniforms != null) {
			this.uniforms.close();
			this.uniforms = null;
		}
		if (this.finalPipeline != null) {
			this.finalPipeline.close();
			this.finalPipeline = null;
		}
		if (this.sceneSampler != null) {
			this.sceneSampler.close();
			this.sceneSampler = null;
		}
	}

	private void requireOpen() {
		if (this.closed) {
			throw new IllegalStateException("Metal shader engine is closed");
		}
	}

	private static String combinedSource(final ShaderPack pack) {
		if (pack.metalSources().isEmpty()) {
			throw new IllegalArgumentException("Shader pack '" + pack.id() + "' contains no .metal sources");
		}
		return String.join("\n", pack.metalSources().values());
	}

	private static String compileConstant(final ShaderPack.Option option, final Object value) {
		return switch (option.type()) {
			case BOOL -> Boolean.TRUE.equals(value) ? "1" : "0";
			case INT -> Integer.toString(((Number)value).intValue());
			case FLOAT -> Double.toString(((Number)value).doubleValue());
			case ENUM -> Integer.toString(option.values().indexOf(value));
		};
	}

	private static MetalRenderPass.LoadAction loadAction(final ShaderGraphCompiler.LoadAction action) {
		return action == ShaderGraphCompiler.LoadAction.LOAD
			? MetalRenderPass.LoadAction.LOAD
			: MetalRenderPass.LoadAction.DONT_CARE;
	}

	private static MetalRenderPass.StoreAction storeAction(final ShaderGraphCompiler.StoreAction action) {
		return action == ShaderGraphCompiler.StoreAction.STORE
			? MetalRenderPass.StoreAction.STORE
			: MetalRenderPass.StoreAction.DONT_CARE;
	}

	private static void validatePhaseOneGraph(
		final ShaderPack pack,
		final ShaderGraphCompiler.CompiledGraph graph
	) {
		if (graph.passes().size() != 1) {
			throw new IllegalArgumentException("Phase 1 pack '" + pack.id() + "' must contain exactly one pass");
		}
		ShaderPack.Pass pass = graph.passes().getFirst().declaration();
		if (pass.kind() != ShaderPack.PassKind.FULLSCREEN
			|| !pass.reads().equals(List.of("scene"))
			|| !pass.writes().equals(List.of("drawable"))) {
			throw new IllegalArgumentException(
				"Phase 1 pack '" + pack.id() + "' must be one fullscreen scene-to-drawable pass"
			);
		}
	}

	private static Object normalizeOption(final ShaderPack.Option option, final Object value) {
		Object normalized = switch (option.type()) {
			case BOOL -> value instanceof Boolean ? value : null;
			case INT -> {
				if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
					|| number.doubleValue() != Math.rint(number.doubleValue())) {
					yield null;
				}
				double numeric = number.doubleValue();
				yield numeric < option.min().orElseThrow() || numeric > option.max().orElseThrow()
					? null : number.intValue();
			}
			case FLOAT -> {
				if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
					yield null;
				}
				double numeric = number.doubleValue();
				yield numeric < option.min().orElseThrow() || numeric > option.max().orElseThrow()
					? null : numeric;
			}
			case ENUM -> canonicalEnumValue(option, value);
		};
		if (normalized == null) {
			throw new IllegalArgumentException("Invalid value '" + value + "' for shader option '" + option.id() + "'");
		}
		return normalized;
	}

	private static Object canonicalEnumValue(final ShaderPack.Option option, final Object value) {
		for (Object candidate : option.values()) {
			if (candidate.equals(value)
				|| candidate instanceof Number left && value instanceof Number right
					&& Double.compare(left.doubleValue(), right.doubleValue()) == 0) {
				return candidate;
			}
		}
		return null;
	}

	private static final class SettingsData {
		private String selectedPack = BUILTIN_ID;
		private Map<String, Map<String, Object>> packs = new LinkedHashMap<>();

		SettingsData normalized() {
			if (this.packs == null) {
				this.packs = new LinkedHashMap<>();
			}
			return this;
		}
	}
}
