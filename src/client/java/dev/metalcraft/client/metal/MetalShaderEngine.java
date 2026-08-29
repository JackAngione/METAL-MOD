package dev.metalcraft.client.metal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.blaze3d.textures.GpuTexture;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/** Loads, compiles, and executes the selected first-party MSL shader pack. */
public final class MetalShaderEngine implements AutoCloseable {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Type SETTINGS_TYPE = new TypeToken<SettingsData>() { }.getType();
	private static final String BUILTIN_ID = "metalcraft-standard";
	private static final String BUILTIN_ROOT = "assets/metalcraft/shaderpacks/standard";
	/** Minecraft's own colour attachment, written by a geometry pass and read by the passes after it. */
	private static final String TARGET_SCENE = "scene";
	/** Minecraft's own depth attachment, shared with the world draw so depth ordering stays global. */
	private static final String TARGET_DEPTH = "depth";
	/** The surface being presented; only the last pass can write it. */
	private static final String TARGET_DRAWABLE = "drawable";
	private static volatile MetalShaderEngine active;

	public record PackChoice(String id, String name) {
	}

	/** One allocated pack target: the Metal texture, plus the Blaze3D view the world draw binds. */
	private record Attachment(MetalGpuTexture texture, MetalGpuTextureView view) implements AutoCloseable {
		MetalTexture metal() {
			return this.texture.metal();
		}

		@Override
		public void close() {
			this.view.close();
			this.texture.close();
		}
	}

	/** A fullscreen pass and the pipeline compiled for it, in execution order. */
	private record PostPass(ShaderGraphCompiler.CompiledPass compiled, MetalRenderPipeline pipeline) implements AutoCloseable {
		@Override
		public void close() {
			this.pipeline.close();
		}
	}

	private final MetalDevice device;
	private final Path shaderpacksRoot;
	private final Path settingsPath;
	private final MetalPipelineCache pipelineCache;
	private final Map<String, ShaderPackLoader.PackRef> discovered = new LinkedHashMap<>();
	private final Map<String, Attachment> targets = new LinkedHashMap<>();
	private final List<PostPass> postPasses = new ArrayList<>();
	/** The fullscreen draw appended to the geometry encoder before forward translucency begins. */
	private @Nullable PostPass deferredResolve;
	private final SettingsData settings;
	private @Nullable MetalGpuDevice gpuDevice;
	private ShaderPack pack;
	private ShaderGraphCompiler.CompiledGraph graph;
	private ShaderGraphCompiler.@Nullable CompiledPass geometryPass;
	private ShaderGraphCompiler.@Nullable CompiledPass shadowPass;
	private @Nullable MetalWorldGeometry worldGeometry;
	private @Nullable MetalWorldShadow worldShadow;
	private MetalBuffer uniforms;
	private MetalSampler filteredSampler;
	private MetalSampler unfilteredSampler;
	/**
	 * A view of Minecraft's colour attachment, remade only when that attachment changes.
	 *
	 * <p>The engine is handed the texture rather than a view, and a Metal texture view is an owned
	 * object. Creating one per frame was affordable while the graph was a single blit; a pack that
	 * samples the scene from several passes would create several per frame for no reason.
	 */
	private @Nullable MetalTexture sceneTexture;
	private @Nullable MetalTextureView sceneView;
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

	/** Used by the CommandEncoder mixin to admit the engine's one layered render attachment. */
	public static boolean isLayeredShadowAttachment(final GpuTexture texture) {
		MetalShaderEngine engine = active;
		return engine != null && engine.worldShadow != null && engine.worldShadow.owns(texture);
	}

	/** Completes any opaque G-buffer still resident in tile memory. */
	public static void resolveOpaque() {
		MetalShaderEngine engine = active;
		if (engine != null && engine.gpuDevice != null) {
			engine.gpuDevice.resolveShaderPackOpaque(engine);
		}
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

	/**
	 * Hands the engine the Blaze3D device, once that device exists.
	 *
	 * <p>The device constructs the engine, so the engine cannot be given the device at construction.
	 * Nothing before this point needs it: a geometry pass only reaches a pipeline when the world is
	 * drawn, which is long after both objects exist.
	 */
	synchronized void attachDevice(final MetalGpuDevice attached) {
		this.requireOpen();
		this.gpuDevice = Objects.requireNonNull(attached, "device");
		this.rebuildWorldGeometry();
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
					validateSupportedGraph(candidate, candidateGraph);
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
			if (this.postPasses.isEmpty() && !BUILTIN_ID.equals(id)) {
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
				case RECOMPILE -> {
					this.compilePostPasses();
					this.rebuildWorldGeometry();
				}
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

	/**
	 * Resizes the graph to the size the world is actually being drawn at.
	 *
	 * <p>{@link #resize} is driven by the surface, which is the right size for the drawable but not
	 * necessarily for Minecraft's world attachments. The G-buffer has to match those, because a
	 * render pass admits only one attachment size, so the world's size wins and the last pass
	 * upscales to the drawable through the pack's own filter option.
	 */
	synchronized void resizeToWorld(final int width, final int height) {
		this.resize(width, height);
	}

	/** Re-reads the selected pack, used by Minecraft's resource-reload pipeline. */
	public synchronized void reload() {
		this.selectPack(this.pack.id());
	}

	/**
	 * Runs the passes that follow the world draw and writes the drawable.
	 *
	 * @return whether the pack ran; false asks the caller to present the scene unchanged, which is
	 *     what happens on a screen with no world behind it when the pack needs one
	 */
	boolean encode(final MetalCommandBuffer commands, final MetalTexture scene, final MetalDrawable drawable) {
		return this.encodeTarget(commands, scene, drawable);
	}

	boolean encodeForTesting(final MetalCommandBuffer commands, final MetalTexture scene, final MetalTexture output) {
		return this.encodeTarget(commands, scene, output);
	}

	private synchronized boolean encodeTarget(
		final MetalCommandBuffer commands,
		final MetalTexture scene,
		final MetalRenderPass.ColorTarget output
	) {
		this.requireOpen();
		try {
			if (this.geometryPass != null && this.worldDepth() == null) {
				// A pack whose graph starts in the world has nothing to composite when no world was
				// drawn - a title screen, a loading screen, a menu over no level.
				return false;
			}
			this.bindSceneTexture(scene);
			for (PostPass pass : this.postPasses) {
				this.encodePass(commands, pass, output);
			}
			return true;
		} finally {
			if (this.worldGeometry != null) {
				this.worldGeometry.beginFrame();
			}
		}
	}

	private void encodePass(
		final MetalCommandBuffer commands,
		final PostPass pass,
		final MetalRenderPass.ColorTarget output
	) {
		List<MetalRenderPass.@Nullable ColorAttachment> colors = new ArrayList<>();
		MetalRenderPass.DepthAttachment depth = null;
		for (ShaderGraphCompiler.WriteDecision write : pass.compiled().writes()) {
			if (TARGET_DEPTH.equals(write.target())) {
				depth = new MetalRenderPass.DepthAttachment(
					this.requireWorldDepth().texture(), loadAction(write.loadAction()), storeAction(write.storeAction()), 1.0
				);
				continue;
			}
			colors.add(new MetalRenderPass.ColorAttachment(
				this.colorTarget(write.target(), output), loadAction(write.loadAction()), storeAction(write.storeAction()),
				0.0, 0.0, 0.0, 1.0
			));
		}
		try (MetalRenderPass render = commands.beginRenderPass(new MetalRenderPass.Descriptor(colors, depth))) {
			render.setPipeline(pass.pipeline());
			int slot = 0;
			for (String read : pass.compiled().declaration().reads()) {
				MetalTextureView view = this.readView(read);
				render.setTexture(slot, view, MetalRenderPass.STAGE_FRAGMENT);
				render.setSampler(
					slot,
					view.texture().descriptor().format().hasDepthAspect() ? this.unfilteredSampler : this.filteredSampler,
					MetalRenderPass.STAGE_FRAGMENT
				);
				slot++;
			}
			render.setUniformBuffer(0, this.uniforms, 0L, MetalRenderPass.STAGE_FRAGMENT);
			render.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
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
		validateSupportedGraph(loaded, compiled);
		this.closeResources();
		this.pack = loaded;
		this.graph = compiled;
		this.geometryPass = compiled.passes().stream()
			.filter(pass -> pass.declaration().kind() == ShaderPack.PassKind.GEOMETRY)
			.findFirst()
			.orElse(null);
		this.shadowPass = compiled.passes().stream()
			.filter(pass -> pass.declaration().kind() == ShaderPack.PassKind.SHADOW)
			.findFirst()
			.orElse(null);
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
		this.allocateTargets();
		this.compilePostPasses();
		this.rebuildWorldGeometry();
		this.writeUniforms();
		LOGGER.info("Loaded MetalCraft shader pack '{}': {} pass(es), {} target(s), {} option(s), geometry pass {}, shadow pass {}",
			loaded.manifest().name(), compiled.passes().size(), compiled.targets().size(),
			loaded.manifest().options().size(),
			this.geometryPass == null ? "absent" : this.geometryPass.declaration().id(),
			this.shadowPass == null ? "absent" : this.shadowPass.declaration().id());
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

	/**
	 * Compiles one pipeline per fullscreen pass, replacing whatever was compiled before.
	 *
	 * <p>Built as a whole and swapped in as a whole: a pack whose third pass fails to compile must
	 * leave the previous pack running rather than half of two.
	 */
	private void compilePostPasses() {
		List<PostPass> replacements = new ArrayList<>();
		PostPass replacementResolve = null;
		try {
			for (ShaderGraphCompiler.CompiledPass pass : this.graph.passes()) {
				if (pass.declaration().kind() == ShaderPack.PassKind.GEOMETRY
					|| pass.declaration().kind() == ShaderPack.PassKind.SHADOW) {
					continue;
				}
				if (this.geometryPass != null && pass.groupIndex() == this.geometryPass.groupIndex()) {
					if (replacementResolve != null) {
						throw new IllegalArgumentException("A geometry group can contain only one deferred resolve");
					}
					replacementResolve = new PostPass(pass, this.createMergedPassPipeline(pass));
				} else {
					replacements.add(new PostPass(pass, this.createPassPipeline(pass)));
				}
			}
		} catch (RuntimeException error) {
			replacements.forEach(PostPass::close);
			if (replacementResolve != null) {
				replacementResolve.close();
			}
			throw error;
		}
		this.postPasses.forEach(PostPass::close);
		this.postPasses.clear();
		this.postPasses.addAll(replacements);
		if (this.deferredResolve != null) {
			this.deferredResolve.close();
		}
		this.deferredResolve = replacementResolve;
	}

	/** Builds a fullscreen pipeline against every attachment kept open by its merged pass group. */
	private MetalRenderPipeline createMergedPassPipeline(final ShaderGraphCompiler.CompiledPass pass) {
		List<String> attachments = this.groupColorTargets(pass.groupIndex());
		List<MetalRenderPipeline.ColorTarget> colorTargets = new ArrayList<>(attachments.size());
		for (String target : attachments) {
			int writeMask = pass.declaration().writes().contains(target) ? MetalRenderPipeline.WRITE_ALL : 0;
			colorTargets.add(new MetalRenderPipeline.ColorTarget(this.writeFormat(target), writeMask, null));
		}
		String passId = pass.declaration().id();
		String source = this.passDefines(pass) + this.configuredSource();
		return this.pipelineCache.createRenderPipeline(new MetalRenderPipeline.Descriptor(
			source, passId + "_vertex", source, passId + "_fragment",
			colorTargets, MetalTexture.Format.DEPTH32_FLOAT, MetalRenderPipeline.VertexDescriptor.EMPTY,
			MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT
		));
	}

	private MetalRenderPipeline createPassPipeline(final ShaderGraphCompiler.CompiledPass pass) {
		List<MetalRenderPipeline.ColorTarget> colorTargets = new ArrayList<>();
		MetalTexture.Format depthFormat = null;
		for (ShaderGraphCompiler.WriteDecision write : pass.writes()) {
			if (TARGET_DEPTH.equals(write.target())) {
				depthFormat = MetalTexture.Format.DEPTH32_FLOAT;
				continue;
			}
			colorTargets.add(MetalRenderPipeline.ColorTarget.opaque(this.writeFormat(write.target())));
		}
		String passId = pass.declaration().id();
		String source = this.passDefines(pass) + this.configuredSource();
		return this.pipelineCache.createRenderPipeline(new MetalRenderPipeline.Descriptor(
			source, passId + "_vertex", source, passId + "_fragment",
			colorTargets, depthFormat, MetalRenderPipeline.VertexDescriptor.EMPTY,
			MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT
		));
	}

	/**
	 * What a fullscreen pass cannot know about itself: which pass is being compiled, and which
	 * texture and colour index each of its declared reads and writes occupies.
	 *
	 * <p>Every pass's programs live in the same source, because a pack is one translation unit per
	 * compile - so {@code MC_PASS_<ID>} is also what keeps one pass's programs out of another's
	 * compilation, where its indices would not be defined.
	 */
	private String passDefines(final ShaderGraphCompiler.CompiledPass pass) {
		StringBuilder defines = new StringBuilder();
		defines.append("#define MC_PASS_").append(symbol(pass.declaration().id())).append(" 1\n");
		int read = 0;
		for (String target : pass.declaration().reads()) {
			defines.append("#define MC_TEX_").append(symbol(target)).append(' ').append(read++).append('\n');
		}
		int color = 0;
		for (String target : this.groupColorTargets(pass.groupIndex())) {
			defines.append("#define MC_TARGET_").append(symbol(target)).append(' ').append(color++).append('\n');
		}
		return defines.toString();
	}

	/** Attachment order is the first declaration of each target in the merged group. */
	private List<String> groupColorTargets(final int groupIndex) {
		List<String> targets = new ArrayList<>();
		for (ShaderGraphCompiler.CompiledPass member : this.graph.passes()) {
			if (member.groupIndex() != groupIndex || member.declaration().kind() == ShaderPack.PassKind.COMPUTE) {
				continue;
			}
			for (String target : member.declaration().writes()) {
				if (!TARGET_DEPTH.equals(target) && !targets.contains(target)) {
					targets.add(target);
				}
			}
		}
		return List.copyOf(targets);
	}

	/** Draws the merged resolve into the encoder that still owns the memoryless G-buffer. */
	boolean encodeDeferredResolve(final MetalRenderPass render) {
		PostPass resolve = this.deferredResolve;
		MetalWorldGeometry geometry = this.worldGeometry;
		if (resolve == null || geometry == null || !geometry.hasPendingResolve()) {
			return false;
		}
		render.setPipeline(resolve.pipeline());
		int textureSlot = 0;
		for (String read : resolve.compiled().declaration().reads()) {
			MetalTextureView view = this.readView(read);
			render.setTexture(textureSlot, view, MetalRenderPass.STAGE_FRAGMENT);
			render.setSampler(
				textureSlot,
				view.texture().descriptor().format().hasDepthAspect() ? this.unfilteredSampler : this.filteredSampler,
				MetalRenderPass.STAGE_FRAGMENT
			);
			textureSlot++;
		}
		render.setUniformBuffer(0, this.uniforms, 0L, MetalRenderPass.STAGE_FRAGMENT);
		if (this.worldShadow != null) {
			render.setUniformBuffer(1, this.worldShadow.matricesForTesting(), 0L, MetalRenderPass.STAGE_FRAGMENT);
		}
		render.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
		geometry.didResolve();
		return true;
	}

	private static String symbol(final String id) {
		return id.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
	}

	/**
	 * Builds the world binding for the loaded pack, or clears it when the pack has no geometry pass.
	 *
	 * <p>Also called when a recompile-mode option changes, because the option defines are compiled
	 * into the geometry programs the same way they are into the fullscreen ones.
	 */
	private void rebuildWorldGeometry() {
		if (this.worldGeometry != null) {
			this.worldGeometry.close();
			this.worldGeometry = null;
		}
		if (this.worldShadow != null) {
			this.worldShadow.close();
			this.worldShadow = null;
		}
		MetalWorldGeometry.setActive(null);
		MetalWorldShadow.setActive(null);
		if (this.gpuDevice == null) {
			return;
		}
		if (this.geometryPass != null) {
			List<String> writes = this.geometryPass.declaration().writes();
			List<MetalWorldGeometry.Channel> channels = new ArrayList<>();
			for (String target : writes) {
				if (TARGET_SCENE.equals(target) || TARGET_DEPTH.equals(target)) {
					continue;
				}
				Attachment attachment = this.targets.get(target);
				if (attachment == null) {
					throw new IllegalStateException("Geometry pass writes unallocated target '" + target + "'");
				}
				channels.add(new MetalWorldGeometry.Channel(target, attachment.view(), MetalWorldGeometry.clearColorFor(target)));
			}
			MetalWorldGeometry binding = new MetalWorldGeometry(
				this.gpuDevice,
				this,
				this.pipelineCache,
				this.pack.id(),
				this.geometryPass.declaration().id(),
				this.configuredSource(),
				this.targetDefines(),
				channels
			);
			this.worldGeometry = binding;
			MetalWorldGeometry.setActive(binding);
		}
		if (this.shadowPass != null) {
			String target = this.shadowPass.declaration().writes().getFirst();
			Attachment attachment = this.requireAttachment(target);
			this.worldShadow = new MetalWorldShadow(
				this.gpuDevice, this.pipelineCache, this.pack.id(), this.shadowPass.declaration().id(),
				this.configuredSource(), attachment.view(),
				() -> this.numericOption("shadow_distance", 192.0F),
				() -> this.numericOption("shadow_normal_offset", 0.08F)
			);
			MetalWorldShadow.setActive(this.worldShadow);
		}
	}

	/**
	 * The colour index each of the geometry pass's targets occupies, as preprocessor defines.
	 *
	 * <p>A pack writes {@code [[color(MC_TARGET_GBUFFER_ALBEDO)]]} rather than a literal, so the
	 * order of the pass's declared writes is the single place the layout is decided, and adding a
	 * channel does not mean renumbering the programs.
	 */
	private String targetDefines() {
		if (this.geometryPass == null) {
			return "";
		}
		StringBuilder defines = new StringBuilder();
		int index = 0;
		for (String target : this.geometryPass.declaration().writes()) {
			if (TARGET_DEPTH.equals(target)) {
				continue;
			}
			defines.append("#define MC_TARGET_").append(symbol(target)).append(' ').append(index++).append('\n');
		}
		return defines.toString();
	}

	/**
	 * Allocates every target the pack produces, at the size its declaration asks for.
	 *
	 * <p>The reserved targets are not among them: {@code scene} and {@code depth} are Minecraft's
	 * attachments and {@code drawable} is the surface, so the pack names them but the host supplies
	 * them. Everything else is allocated here and reallocated on resize and on a reload-mode option.
	 */
	private void allocateTargets() {
		Map<String, Attachment> replacements = new LinkedHashMap<>();
		MetalSampler filtered = null;
		MetalSampler unfiltered = null;
		try {
			for (Map.Entry<String, ShaderGraphCompiler.TargetInfo> entry : this.graph.targets().entrySet()) {
				if (entry.getValue().producer().isEmpty()) {
					continue;
				}
				replacements.put(entry.getKey(), this.allocate(entry.getKey(), entry.getValue()));
			}
			MetalSampler.Filter filter = "nearest".equals(this.reloadOption("upscale_filter"))
				? MetalSampler.Filter.NEAREST
				: MetalSampler.Filter.LINEAR;
			filtered = this.device.createSampler(new MetalSampler.Descriptor(
				filter, filter, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			// Depth formats are not filterable on this hardware, so a sampled depth read needs its
			// own sampler rather than whichever filter the pack's option happens to select.
			unfiltered = this.device.createSampler(new MetalSampler.Descriptor(
				MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
		} catch (RuntimeException error) {
			replacements.values().forEach(Attachment::close);
			if (filtered != null) {
				filtered.close();
			}
			if (unfiltered != null) {
				unfiltered.close();
			}
			throw error;
		}
		this.targets.values().forEach(Attachment::close);
		this.targets.clear();
		this.targets.putAll(replacements);
		if (this.filteredSampler != null) {
			this.filteredSampler.close();
		}
		if (this.unfilteredSampler != null) {
			this.unfilteredSampler.close();
		}
		this.filteredSampler = filtered;
		this.unfilteredSampler = unfiltered;
		// The world binding holds views of the textures just replaced, so it has to be rebuilt with
		// them rather than left pointing at closed ones.
		if (this.worldGeometry != null) {
			this.rebuildWorldGeometry();
		}
	}

	private Attachment allocate(final String id, final ShaderGraphCompiler.TargetInfo info) {
		ShaderPack.Target target = info.declaration();
		int targetWidth;
		int targetHeight;
		if (target.extent() instanceof ShaderPack.Scale scale) {
			targetWidth = Math.max(1, (int)Math.round(this.width * scale.value()));
			targetHeight = Math.max(1, (int)Math.round(this.height * scale.value()));
		} else {
			int size = ((ShaderPack.FixedSize)target.extent()).value();
			if (info.producer().isPresent() && this.shadowPass != null
				&& info.producer().get().equals(this.shadowPass.declaration().id())) {
				Object configured = this.reloadOption("shadow_resolution");
				if (configured instanceof Number number) {
					size = number.intValue();
				}
			}
			targetWidth = size;
			targetHeight = size;
		}
		MetalTexture.Format format = MetalTexture.Format.valueOf(target.format().name());
		MetalTexture.Descriptor descriptor = info.memoryless()
			? MetalTexture.Descriptor.memoryless(format, targetWidth, targetHeight)
			: target.layers() > 1
				? MetalTexture.Descriptor.array(format, targetWidth, targetHeight, target.layers(), MetalTexture.USAGE_ALL)
				: new MetalTexture.Descriptor(format, targetWidth, targetHeight, 1, MetalTexture.USAGE_ALL);
		MetalTexture metal = this.device.createTexture(descriptor);
		try {
			int usage = GpuTexture.USAGE_RENDER_ATTACHMENT
				| (info.memoryless() ? 0 : GpuTexture.USAGE_TEXTURE_BINDING);
			MetalGpuTexture texture = new MetalGpuTexture(
				usage,
				"metalcraft:" + id,
				Blaze3DMetalMappings.gpuFormat(format),
				targetWidth, targetHeight, target.layers(), 1, metal
			);
			MetalGpuTextureView view = info.memoryless()
				? MetalGpuTextureView.attachmentOnly(texture)
				: new MetalGpuTextureView(texture, 0, 1, metal.createView());
			return new Attachment(texture, view);
		} catch (RuntimeException error) {
			metal.close();
			throw error;
		}
	}

	private void bindSceneTexture(final MetalTexture scene) {
		if (this.sceneTexture == scene && this.sceneView != null && !this.sceneView.isClosed()) {
			return;
		}
		if (this.sceneView != null) {
			this.sceneView.close();
		}
		this.sceneTexture = scene;
		this.sceneView = scene.createView();
	}

	private MetalRenderPass.ColorTarget colorTarget(final String id, final MetalRenderPass.ColorTarget output) {
		return switch (id) {
			case TARGET_DRAWABLE -> output;
			case TARGET_SCENE -> Objects.requireNonNull(this.sceneTexture, "scene texture");
			default -> this.requireAttachment(id).metal();
		};
	}

	private MetalTextureView readView(final String id) {
		return switch (id) {
			case TARGET_SCENE -> Objects.requireNonNull(this.sceneView, "scene view");
			case TARGET_DEPTH -> this.requireWorldDepth();
			case TARGET_DRAWABLE -> throw new IllegalStateException("The drawable cannot be sampled");
			default -> this.requireAttachment(id).view().metal();
		};
	}

	/**
	 * The Metal texture behind an allocated pack target, so a test can seed a G-buffer by hand.
	 *
	 * <p>What a geometry pass writes and what the passes after it read are two halves of the same
	 * contract, and only the writing half can be checked by drawing. Handing a test the texture lets
	 * the reading half be checked against values it chose rather than against whatever a draw
	 * happened to produce.
	 */
	@Nullable MetalTexture targetTextureForTesting(final String id) {
		Attachment attachment = this.targets.get(id);
		return attachment == null ? null : attachment.metal();
	}

	@Nullable MetalWorldShadow shadowForTesting() {
		return this.worldShadow;
	}

	@Nullable MetalRenderPipeline deferredResolvePipelineForTesting() {
		return this.deferredResolve == null ? null : this.deferredResolve.pipeline();
	}

	MetalBuffer optionUniformsForTesting() {
		return this.uniforms;
	}

	/** See {@link MetalWorldGeometry#supplyWorldDepthForTesting}. */
	void supplyWorldDepthForTesting(final @Nullable MetalTextureView depth) {
		if (this.worldGeometry != null) {
			this.worldGeometry.supplyWorldDepthForTesting(depth);
		}
	}

	private @Nullable MetalTextureView worldDepth() {
		return this.worldGeometry == null ? null : this.worldGeometry.worldDepth();
	}

	private MetalTextureView requireWorldDepth() {
		MetalTextureView depth = this.worldDepth();
		if (depth == null) {
			throw new IllegalStateException("Shader pack '" + this.pack.id() + "' reads depth but no world was drawn");
		}
		return depth;
	}

	private Attachment requireAttachment(final String id) {
		Attachment attachment = this.targets.get(id);
		if (attachment == null) {
			throw new IllegalStateException("Shader target '" + id + "' is not allocated");
		}
		return attachment;
	}

	/** The colour format a pass's pipeline must declare for the target it writes. */
	private MetalTexture.Format writeFormat(final String id) {
		if (TARGET_DRAWABLE.equals(id)) {
			return MetalTexture.Format.BGRA8_UNORM;
		}
		if (TARGET_SCENE.equals(id)) {
			return this.sceneTexture == null ? MetalTexture.Format.RGBA8_UNORM : this.sceneTexture.descriptor().format();
		}
		return MetalTexture.Format.valueOf(this.graph.targets().get(id).declaration().format().name());
	}

	/** The pack MSL with the recompile-mode options compiled in, shared by every program. */
	private String configuredSource() {
		StringBuilder preamble = new StringBuilder();
		Map<String, Object> values = this.packSettings();
		for (ShaderPack.Option option : this.pack.manifest().options()) {
			if (option.apply() == ShaderPack.ApplyMode.RECOMPILE) {
				preamble.append("#define MC_OPTION_").append(symbol(option.id()))
					.append(' ').append(compileConstant(option, values.getOrDefault(option.id(), option.defaultValue())))
					.append('\n');
			}
		}
		return preamble + combinedSource(this.pack);
	}

	private void writeUniforms() {
		try (MetalBuffer.Mapping mapping = this.uniforms.map()) {
			var bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			// Zeroed first, because the buffer is rounded up to a minimum size and a pack with fewer
			// uniform options than that would otherwise read whatever the allocation came with.
			while (bytes.hasRemaining()) {
				bytes.put((byte)0);
			}
			bytes.rewind();
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

	private @Nullable Object reloadOption(final String id) {
		return this.pack.manifest().options().stream()
			.filter(option -> option.apply() == ShaderPack.ApplyMode.RELOAD && option.id().equals(id))
			.findFirst()
			.map(option -> this.packSettings().getOrDefault(option.id(), option.defaultValue()))
			.orElse(null);
	}

	private float numericOption(final String id, final float fallback) {
		return this.pack.manifest().options().stream()
			.filter(option -> option.id().equals(id))
			.findFirst()
			.map(option -> this.packSettings().getOrDefault(id, option.defaultValue()))
			.filter(Number.class::isInstance)
			.map(Number.class::cast)
			.map(Number::floatValue)
			.orElse(fallback);
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
		if (this.worldGeometry != null) {
			this.worldGeometry.close();
			this.worldGeometry = null;
		}
		if (this.worldShadow != null) {
			this.worldShadow.close();
			this.worldShadow = null;
		}
		MetalWorldGeometry.setActive(null);
		MetalWorldShadow.setActive(null);
		this.postPasses.forEach(PostPass::close);
		this.postPasses.clear();
		if (this.deferredResolve != null) {
			this.deferredResolve.close();
			this.deferredResolve = null;
		}
		this.targets.values().forEach(Attachment::close);
		this.targets.clear();
		this.geometryPass = null;
		this.shadowPass = null;
		if (this.sceneView != null) {
			this.sceneView.close();
			this.sceneView = null;
		}
		this.sceneTexture = null;
		if (this.uniforms != null) {
			this.uniforms.close();
			this.uniforms = null;
		}
		if (this.filteredSampler != null) {
			this.filteredSampler.close();
			this.filteredSampler = null;
		}
		if (this.unfilteredSampler != null) {
			this.unfilteredSampler.close();
			this.unfilteredSampler = null;
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

	/**
	 * Rejects the parts of the declared format the runtime does not execute yet.
	 *
	 * <p>The graph compiler validates more than the runtime can run. The deferred geometry/resolve
	 * pair is executable; arbitrary merged groups and compute passes remain future graph shapes, so
	 * the refusal is here and names the pass rather than leaving a pack to render half its graph.
	 */
	private static void validateSupportedGraph(
		final ShaderPack pack,
		final ShaderGraphCompiler.CompiledGraph graph
	) {
		long geometryPasses = graph.passes().stream()
			.filter(pass -> pass.declaration().kind() == ShaderPack.PassKind.GEOMETRY)
			.count();
		if (geometryPasses > 1) {
			throw new IllegalArgumentException(
				"Shader pack '" + pack.id() + "' declares " + geometryPasses + " geometry passes; the engine runs at most one"
			);
		}
		long shadowPasses = graph.passes().stream()
			.filter(pass -> pass.declaration().kind() == ShaderPack.PassKind.SHADOW)
			.count();
		if (shadowPasses > 1) {
			throw new IllegalArgumentException(
				"Shader pack '" + pack.id() + "' declares " + shadowPasses + " shadow passes; the engine runs at most one"
			);
		}
		for (ShaderGraphCompiler.PassGroup group : graph.groups()) {
			if (group.passes().size() <= 1) {
				continue;
			}
			List<ShaderGraphCompiler.CompiledPass> members = graph.passes().stream()
				.filter(pass -> pass.groupIndex() == group.index()).toList();
			long geometryMembers = members.stream()
				.filter(pass -> pass.declaration().kind() == ShaderPack.PassKind.GEOMETRY).count();
			long fullscreenMembers = members.stream()
				.filter(pass -> pass.declaration().kind() == ShaderPack.PassKind.FULLSCREEN).count();
			if (members.size() != 2 || geometryMembers != 1 || fullscreenMembers != 1) {
				throw new IllegalArgumentException(
					"Shader pack '" + pack.id() + "' merges unsupported pass group " + group.passes()
						+ "; the runtime accepts one geometry pass followed by one fullscreen resolve"
				);
			}
		}
		for (ShaderGraphCompiler.CompiledPass pass : graph.passes()) {
			if (pass.declaration().kind() == ShaderPack.PassKind.COMPUTE) {
				throw new IllegalArgumentException(
					"Shader pack '" + pack.id() + "' declares compute pass '" + pass.declaration().id()
						+ "'; compute passes are not executed until the effects phase exists"
				);
			}
			if (pass.declaration().kind() == ShaderPack.PassKind.SHADOW) {
				List<String> writes = pass.declaration().writes();
				if (writes.size() != 1) {
					throw new IllegalArgumentException("Shadow pass '" + pass.declaration().id() + "' must write exactly one target");
				}
				ShaderPack.Target target = pack.manifest().targets().get(writes.getFirst());
				if (target == null || target.format() != ShaderPack.PixelFormat.DEPTH32_FLOAT || target.layers() != 4) {
					throw new IllegalArgumentException(
						"Shadow pass '" + pass.declaration().id() + "' requires one depth32_float target with four layers"
					);
				}
				continue;
			}
			if (pass.declaration().kind() != ShaderPack.PassKind.GEOMETRY) {
				boolean mergedResolve = graph.passes().stream().anyMatch(candidate ->
					candidate.groupIndex() == pass.groupIndex()
						&& candidate.declaration().kind() == ShaderPack.PassKind.GEOMETRY);
				if (pass.declaration().writes().contains(TARGET_SCENE) && !mergedResolve) {
					throw new IllegalArgumentException(
						"Pass '" + pass.declaration().id() + "' writes 'scene', which only a geometry pass may do:"
							+ " Minecraft's colour attachment has no declared format for a pipeline to be built against"
					);
				}
				continue;
			}
			List<String> writes = pass.declaration().writes();
			if (writes.isEmpty() || !TARGET_SCENE.equals(writes.getFirst())) {
				throw new IllegalArgumentException(
					"Geometry pass '" + pass.declaration().id() + "' must write 'scene' first: Minecraft's own colour"
						+ " attachment stays at colour index zero so unsubstituted geometry still lands in it"
				);
			}
			if (!writes.contains(TARGET_DEPTH)) {
				throw new IllegalArgumentException(
					"Geometry pass '" + pass.declaration().id() + "' must write 'depth': the world draw shares"
						+ " Minecraft's depth attachment so one depth order covers substituted and unsubstituted geometry"
				);
			}
		}
		if (graph.passes().getLast().declaration().writes().stream().noneMatch(TARGET_DRAWABLE::equals)) {
			throw new IllegalArgumentException("Shader pack '" + pack.id() + "' never writes the drawable");
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
