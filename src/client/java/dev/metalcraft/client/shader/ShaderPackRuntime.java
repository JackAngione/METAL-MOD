package dev.metalcraft.client.shader;

import com.mojang.logging.LogUtils;
import dev.metalcraft.api.MetalCraftShaderPackInfo;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.shader.world.ShadowCascades;
import dev.metalcraft.client.shader.world.WorldTerrainShadows;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Selects, compiles, and allocates a Metal shader pack. Does not encode. */
public final class ShaderPackRuntime implements AutoCloseable {
	public static final String BUILTIN_ID = "metalcraft-standard";
	public static final String NONE_ID = "none";
	private static final String BUILTIN_ROOT = "assets/metalcraft/shaderpacks/standard";
	private static final Logger LOGGER = LogUtils.getLogger();
	private static volatile @Nullable ShaderPackRuntime active;

	private final MetalDevice device;
	private final @Nullable MetalGpuDevice gpuDevice;
	private final Path shaderpacksRoot;
	private final Path settingsPath;
	private final ShaderPackSettings settings;
	private final ShaderTargetAllocator allocator;
	private final Map<String, ShaderPackLoader.PackRef> discovered = new LinkedHashMap<>();
	private @Nullable ShaderPack pack;
	private ShaderGraphCompiler.@Nullable CompiledGraph graph;
	private @Nullable MetalShaderFrameExecutor executor;
	private @Nullable WorldGeometryAdapter worldGeometry;
	private @Nullable WorldTerrainShadows worldShadows;
	private @Nullable String lastError;
	private int width;
	private int height;
	private boolean closed;

	public static @Nullable ShaderPackRuntime active() {
		return active;
	}

	/** @return a runtime, or null when {@code metalcraft.shaders.disable} is true */
	public static @Nullable ShaderPackRuntime createDefault(final MetalDevice device) {
		return createDefault(device, null);
	}

	public static @Nullable ShaderPackRuntime createDefault(final MetalGpuDevice gpuDevice) {
		return createDefault(gpuDevice.metal(), gpuDevice);
	}

	private static @Nullable ShaderPackRuntime createDefault(
		final MetalDevice device,
		final @Nullable MetalGpuDevice gpuDevice
	) {
		if (Boolean.getBoolean("metalcraft.shaders.disable")) {
			return null;
		}
		Path shaderpacksRoot;
		Path settingsPath;
		try {
			FabricLoader loader = FabricLoader.getInstance();
			shaderpacksRoot = loader.getGameDir().resolve("shaderpacks");
			settingsPath = loader.getConfigDir().resolve("metalcraft-shaders.json");
		} catch (RuntimeException error) {
			Path root = Path.of("build", "shader-runtime-smoke");
			shaderpacksRoot = root.resolve("shaderpacks");
			settingsPath = root.resolve("metalcraft-shaders.json");
		}
		return new ShaderPackRuntime(device, gpuDevice, shaderpacksRoot, settingsPath);
	}

	public ShaderPackRuntime(final MetalDevice device, final Path shaderpacksRoot, final Path settingsPath) {
		this(device, null, shaderpacksRoot, settingsPath);
	}

	public ShaderPackRuntime(
		final MetalDevice device,
		final @Nullable MetalGpuDevice gpuDevice,
		final Path shaderpacksRoot,
		final Path settingsPath
	) {
		this.device = Objects.requireNonNull(device, "device");
		this.gpuDevice = gpuDevice;
		this.shaderpacksRoot = Objects.requireNonNull(shaderpacksRoot, "shaderpacksRoot").toAbsolutePath().normalize();
		this.settingsPath = Objects.requireNonNull(settingsPath, "settingsPath").toAbsolutePath().normalize();
		this.settings = ShaderPackSettings.load(this.settingsPath);
		this.allocator = new ShaderTargetAllocator(device, gpuDevice);
		try {
			Files.createDirectories(this.shaderpacksRoot);
		} catch (IOException error) {
			LOGGER.warn("Could not create shaderpacks directory {}", this.shaderpacksRoot, error);
		}
		this.refreshDiscovery();
		String selected = this.settings.selectedPack();
		if (!NONE_ID.equals(selected)) {
			this.loadSelected(selected, false);
		}
		active = this;
	}

	public synchronized List<MetalCraftShaderPackInfo> availablePacks() {
		this.requireOpen();
		List<MetalCraftShaderPackInfo> packs = new ArrayList<>();
		try {
			ShaderPack builtin = this.loadById(BUILTIN_ID);
			packs.add(new MetalCraftShaderPackInfo(BUILTIN_ID, builtin.manifest().name()));
		} catch (IOException | RuntimeException error) {
			LOGGER.warn("Could not load the built-in MetalCraft shader pack", error);
		}
		this.refreshDiscovery();
		for (ShaderPackLoader.PackRef ref : this.discovered.values()) {
			if (BUILTIN_ID.equals(ref.id()) || NONE_ID.equals(ref.id())) {
				continue;
			}
			try {
				ShaderPack candidate = ShaderPackLoader.load(ref);
				ShaderGraphCompiler.compile(candidate);
				packs.add(new MetalCraftShaderPackInfo(candidate.id(), candidate.manifest().name()));
			} catch (IOException | RuntimeException error) {
				LOGGER.warn("Ignoring invalid shader pack {}", ref.path(), error);
			}
		}
		return List.copyOf(packs);
	}

	public synchronized String selectedPackId() {
		this.requireOpen();
		return this.settings.selectedPack();
	}

	public synchronized String selectedPackName() {
		this.requireOpen();
		if (NONE_ID.equals(this.settings.selectedPack())) {
			return "None";
		}
		return this.pack == null ? this.settings.selectedPack() : this.pack.manifest().name();
	}

	public synchronized Optional<String> lastError() {
		this.requireOpen();
		return Optional.ofNullable(this.lastError);
	}

	public synchronized boolean isActive() {
		this.requireOpen();
		return this.executor != null && !NONE_ID.equals(this.settings.selectedPack());
	}

	public synchronized void selectPack(final String id) {
		this.requireOpen();
		Objects.requireNonNull(id, "id");
		this.settings.setSelectedPack(id);
		this.settings.save(this.settingsPath);
		this.loadSelected(id, true);
	}

	public synchronized void setOption(final String id, final Object value) {
		this.requireOpen();
		if (this.pack == null) {
			throw new IllegalStateException("No shader pack is selected");
		}
		ShaderPack.Option option = this.option(id);
		Object normalized = normalizeOption(option, value);
		Map<String, Object> values = this.settings.packValues(this.pack.id());
		Object previous = values.put(id, normalized);
		try {
			switch (option.apply()) {
				case UNIFORM -> {
					if (this.executor != null) {
						this.executor.writeUniforms();
					}
				}
				case RECOMPILE -> {
					this.rebuildExecutor();
					this.rebuildWorldGeometry();
				}
				case RELOAD -> this.allocateIfSized();
			}
			this.settings.save(this.settingsPath);
		} catch (IOException | RuntimeException error) {
			if (previous == null) {
				values.remove(id);
			} else {
				values.put(id, previous);
			}
			this.markFailed("Could not apply shader option '" + id + "': " + error.getMessage(), error);
			if (error instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException(error);
		}
	}

	public synchronized Object optionValue(final String id) {
		this.requireOpen();
		ShaderPack.Option option = this.option(id);
		return this.settings.packValues(this.pack.id()).getOrDefault(id, option.defaultValue());
	}

	public synchronized List<ShaderPack.Option> options() {
		this.requireOpen();
		return this.pack == null ? List.of() : this.pack.manifest().options();
	}

	public synchronized void resize(final int width, final int height) {
		this.requireOpen();
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Shader graph dimensions must be positive");
		}
		this.width = width;
		this.height = height;
		if (this.isActive()) {
			this.allocator.resize(this.graph, width, height);
			if (this.worldGeometry != null) {
				this.worldGeometry.refreshChannels();
			} else {
				this.rebuildWorldGeometry();
			}
		}
	}

	public synchronized void reload() {
		this.requireOpen();
		this.refreshDiscovery();
		this.loadSelected(this.settings.selectedPack(), true);
	}

	public synchronized Optional<ShaderFrameExecutor> executor() {
		this.requireOpen();
		return Optional.ofNullable(this.executor);
	}

	public synchronized @Nullable MetalTexture target(final String id) {
		this.requireOpen();
		return this.allocator.target(id);
	}

	public synchronized void markFailed(final String message, final @Nullable Throwable error) {
		this.lastError = message;
		this.closeExecutor();
		this.closeWorldGeometry();
		this.allocator.release();
		if (error == null) {
			LOGGER.error("MetalCraft shader pack '{}': {}", this.settings.selectedPack(), message);
		} else {
			LOGGER.error("MetalCraft shader pack '{}': {}", this.settings.selectedPack(), message, error);
		}
	}

	public int frameWidth() {
		return this.width;
	}

	public int frameHeight() {
		return this.height;
	}

	private void loadSelected(final String id, final boolean persistFailure) {
		if (NONE_ID.equals(id)) {
			this.lastError = null;
			this.pack = null;
			this.graph = null;
			this.closeExecutor();
			this.closeWorldGeometry();
			this.allocator.release();
			return;
		}
		try {
			this.refreshDiscovery();
			ShaderPack loaded = this.loadById(id);
			ShaderGraphCompiler.CompiledGraph compiled = ShaderGraphCompiler.compile(loaded);
			this.closeExecutor();
			this.pack = loaded;
			this.graph = compiled;
			this.lastError = null;
			Map<String, Object> values = this.settings.packValues(id);
			for (ShaderPack.Option option : loaded.manifest().options()) {
				Object persisted = values.get(option.id());
				try {
					values.put(option.id(), persisted == null ? option.defaultValue() : normalizeOption(option, persisted));
				} catch (IllegalArgumentException error) {
					LOGGER.warn("Resetting invalid persisted value for shader option '{}.{}'", id, option.id());
					values.put(option.id(), option.defaultValue());
				}
			}
			this.rebuildExecutor();
			this.allocateIfSized();
			this.rebuildWorldGeometry();
			long targetBytes = 0L;
			MetalTexture post = this.allocator.target("post_color");
			if (post != null) {
				targetBytes = post.descriptor().byteSize();
			}
			LOGGER.info(
				"Loaded MetalCraft shader pack '{}': {} pass(es), {} target(s), {} option(s), {} bytes",
				loaded.manifest().name(),
				compiled.passes().size(),
				compiled.targets().size(),
				loaded.manifest().options().size(),
				targetBytes
			);
		} catch (IOException | RuntimeException error) {
			this.markFailed(error.getMessage() == null ? error.toString() : error.getMessage(), error);
			if (persistFailure) {
				this.settings.save(this.settingsPath);
			}
		}
	}

	private void rebuildExecutor() throws ShaderPackLoader.LoadException {
		if (this.pack == null || this.graph == null) {
			this.closeExecutor();
			return;
		}
		MetalShaderFrameExecutor replacement = new MetalShaderFrameExecutor(
			this.device, this.pack, this.graph, this.allocator, this::optionValueUnchecked
		);
		this.closeExecutor();
		this.executor = replacement;
		this.lastError = null;
	}

	private Object optionValueUnchecked(final String id) {
		ShaderPack.Option option = this.option(id);
		return this.settings.packValues(this.pack.id()).getOrDefault(id, option.defaultValue());
	}

	private void allocateIfSized() {
		if (this.executor != null && this.width > 0 && this.height > 0) {
			this.allocator.resize(this.graph, this.width, this.height);
		}
	}

	private void rebuildWorldGeometry() {
		this.closeWorldGeometry();
		if (this.gpuDevice == null || this.pack == null || this.graph == null
			|| this.width <= 0 || this.height <= 0) {
			return;
		}
		ShaderGraphCompiler.CompiledPass geometry = null;
		ShaderGraphCompiler.CompiledPass resolve = null;
		for (ShaderGraphCompiler.CompiledPass pass : this.graph.passes()) {
			if (pass.declaration().kind() == ShaderPack.PassKind.GEOMETRY && geometry == null) {
				geometry = pass;
			}
			if (pass.declaration().kind() == ShaderPack.PassKind.FULLSCREEN && pass.declaration().mergeWith() != null) {
				resolve = pass;
			}
		}
		if (geometry == null || resolve == null) {
			return;
		}
		try {
			Map<String, Object> values = this.settings.packValues(this.pack.id());
			String source = ShaderPassCompiler.source(this.pack, geometry.declaration(), values);
			String resolveSource = ShaderPassCompiler.source(this.pack, resolve.declaration(), values);
			StringBuilder targetDefines = new StringBuilder();
			int colorIndex = 0;
			List<String> channelIds = new ArrayList<>();
			for (String write : geometry.declaration().writes()) {
				if ("depth".equals(write)) {
					continue;
				}
				targetDefines.append("#define MC_TARGET_").append(ShaderPassCompiler.symbol(write))
					.append(' ').append(colorIndex++).append('\n');
				if (!"scene".equals(write)) {
					channelIds.add(write);
				}
			}
			this.worldGeometry = new WorldGeometryAdapter(
				this.gpuDevice,
				this.allocator,
				this.pack.id(),
				geometry.declaration().id(),
				source,
				targetDefines.toString(),
				channelIds,
				resolve.declaration(),
				targetDefines + resolveSource,
				this.pack.manifest().options().stream()
					.filter(option -> option.apply() == ShaderPack.ApplyMode.UNIFORM)
					.toList(),
				this::optionValueUnchecked
			);
			if (BUILTIN_ID.equals(this.pack.id())) {
				this.worldShadows = new WorldTerrainShadows(this.gpuDevice,
					new ShadowCascades.Settings(
						((Number)this.optionValueUnchecked("shadow_cascades")).intValue(),
						((Number)this.optionValueUnchecked("shadow_resolution")).intValue(), 0.05F,
						((Number)this.optionValueUnchecked("shadow_distance")).floatValue(), 0.6F,
						((Number)this.optionValueUnchecked("shadow_caster_distance")).floatValue()),
					this.pack.metalSources().get("shared/shadows.metal"), this.pack.metalSources().get("shadow.metal"));
			}
		} catch (IOException | RuntimeException error) {
			throw new IllegalStateException("Could not build world geometry adapter: " + error.getMessage(), error);
		}
	}

	public @Nullable WorldTerrainShadows worldShadows() {
		return this.worldShadows;
	}

	public @Nullable WorldGeometryAdapter worldGeometry() {
		return this.worldGeometry;
	}

	private void closeWorldGeometry() {
		if (this.worldShadows != null) {
			this.worldShadows.close();
			this.worldShadows = null;
		}
		if (this.worldGeometry != null) {
			this.worldGeometry.close();
			this.worldGeometry = null;
		}
	}

	private ShaderPack loadById(final String id) throws IOException {
		if (BUILTIN_ID.equals(id)) {
			return ShaderPackLoader.loadBundled(ShaderPackRuntime.class.getClassLoader(), BUILTIN_ID, BUILTIN_ROOT);
		}
		ShaderPackLoader.PackRef ref = this.discovered.get(id);
		if (ref == null) {
			throw new ShaderPackLoader.LoadException("Unknown shader pack '" + id + "'");
		}
		return ShaderPackLoader.load(ref);
	}

	private ShaderPack.Option option(final String id) {
		if (this.pack == null) {
			throw new IllegalStateException("No shader pack is selected");
		}
		return this.pack.manifest().options().stream()
			.filter(option -> option.id().equals(id))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unknown shader option '" + id + "'"));
	}

	private void refreshDiscovery() {
		this.discovered.clear();
		try {
			for (ShaderPackLoader.PackRef ref : ShaderPackLoader.discover(this.shaderpacksRoot)) {
				this.discovered.put(ref.id(), ref);
			}
		} catch (IOException error) {
			LOGGER.warn("Could not refresh shader pack discovery from {}", this.shaderpacksRoot, error);
		}
	}

	private void closeExecutor() {
		if (this.executor != null) {
			this.executor.close();
			this.executor = null;
		}
	}

	private void requireOpen() {
		if (this.closed) {
			throw new IllegalStateException("Shader pack runtime is closed");
		}
	}

	static Object normalizeOption(final ShaderPack.Option option, final Object value) {
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
			case ENUM -> {
				for (Object candidate : option.values()) {
					if (candidate.equals(value)
						|| candidate instanceof Number left && value instanceof Number right
						&& Double.compare(left.doubleValue(), right.doubleValue()) == 0) {
						yield candidate;
					}
				}
				yield null;
			}
		};
		if (normalized == null) {
			throw new IllegalArgumentException("Invalid value '" + value + "' for shader option '" + option.id() + "'");
		}
		return normalized;
	}

	@Override
	public synchronized void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		this.closeExecutor();
		this.closeWorldGeometry();
		this.allocator.close();
		if (active == this) {
			active = null;
		}
	}
}
