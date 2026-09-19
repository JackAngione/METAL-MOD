package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import dev.metalcraft.client.MetalCraftRenderResolution;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.metal.MetalSurfaceProbe;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.GraphicsPreset;
import net.minecraft.client.InactivityFpsLimit;
import dev.metalcraft.client.metal.MetalTaskCensus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.slf4j.Logger;

/** End-to-end validation of the direct Metal world and window lifecycle. */
public final class MetalLifecycleGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int RESIZED_WIDTH = 1280;
	private static final int RESIZED_HEIGHT = 720;
	private static final int POST_RELOAD_SETTLE_TICKS = 100;
	private static final int PERFORMANCE_SAMPLE_TICKS = 60;
	private static final int MINIMUM_ACCEPTABLE_FPS = 30;

	/** Frames discarded at the start of every capture while the client resumes free running. */
	private static final int CAPTURE_WARMUP_FRAMES = 30;
	/** Empty tasks submitted during the lifecycle capture to prove the task census records. */
	private static final int CENSUS_PROBE_TASKS = 4;
	private static final int CHUNK_LOAD_TIMEOUT_TICKS = 2400;
	/**
	 * How much terrain must be loaded before the streaming capture starts.
	 *
	 * <p>Late enough that the client is meshing and uploading in bulk rather than waiting on the
	 * generator, and early enough that the settle wait cannot end before the window closes.
	 */
	private static final double STREAMING_CAPTURE_START_FRACTION = 0.25;
	private static final int SITE_SEARCH_RADIUS = 1536;
	private static final int SITE_CANDIDATE_STEP = 128;
	/** Eye height of a standing player, used to place the camera where a player's actually is. */
	private static final double PLAYER_EYE_HEIGHT = 1.62;

	@Override
	public void runTest(final ClientGameTestContext context) {
		try (var lod = new MetalLodTestScope(context)) {
			this.runConfiguredTest(context);
		}
	}

	private void runConfiguredTest(final ClientGameTestContext context) {
		if (Boolean.getBoolean("metalcraft.geometricLodTest")) {
			MetalGeometricLodGameTest.run(context);
			return;
		}
		if (Boolean.getBoolean("metalcraft.nativeTerrainLodTest")) {
			MetalNativeTerrainLodGameTest.run(context);
			return;
		}
		if (Boolean.getBoolean("metalcraft.nativeChunkDistanceTest")) {
			MetalNativeChunkDistanceGameTest.run(context);
			return;
		}
		context.runOnClient(client -> {
			client.options.renderDistance().set(16);
			client.options.simulationDistance().set(16);
		});
		String expectedBackend = System.getProperty("metalcraft.lifecycleExpectedBackend", "Metal");
		boolean benchmark = Boolean.getBoolean("metalcraft.lifecycleBenchmark");
		String backend = context.computeOnClient(ignored -> RenderSystem.getDevice().getDeviceInfo().backendName());
		if (!expectedBackend.equals(backend)) {
			throw new AssertionError("Lifecycle test selected unexpected backend: " + backend + " (expected " + expectedBackend + ")");
		}
		LOGGER.info("Metal lifecycle validation: {} backend selected", backend);
		if (Boolean.getBoolean("metalcraft.lodGenerationTest")) {
			MetalLodGenerationGameTest.run(context);
			return;
		}
		if (Boolean.getBoolean("metalcraft.lodHorizonTest")) {
			MetalLodHorizonGameTest.run(context);
			return;
		}
		if (Boolean.getBoolean("metalcraft.lodRenderTest")) {
			MetalLodRenderGameTest.run(context);
			return;
		}
		if (Boolean.getBoolean("metalcraft.lodCompilerTest")) {
			MetalLodCompilerGameTest.run(context);
			return;
		}
		if (Boolean.getBoolean("metalcraft.lodSettingsTest")) {
			MetalLodSettingsGameTest.run(context);
			return;
		}

		if (Boolean.getBoolean("metalcraft.waterIdentityTest")) {
			new MetalWaterIdentityGameTest(context).run();
			return;
		}

		if (Boolean.getBoolean("metalcraft.shadowVisibilityTest")) {
			new MetalShadowVisibilityGameTest(context).run();
			return;
		}

		if (benchmark) {
			try (MetalBenchmarkEnvironment environment = new MetalBenchmarkEnvironment(context)) {
				new MetalRealWorldBenchmark(context, backend).run();
			}
			return;
		}

		// Runnable on its own because it needs a creative world of its own, and because a crash in the
		// GUI item atlas has nothing to do with the world lifecycle the rest of this test walks.
		if (Boolean.getBoolean("metalcraft.lifecycleCreativeSearch")) {
			new MetalCreativeSearchGameTest(context).run();
			return;
		}

		try (ShaderLifecycleSelection selection = new ShaderLifecycleSelection(context);
			TestSingleplayerContext world = context.worldBuilder().adjustSettings(settings -> {
				var normal = settings.getSettings().worldgenLoadContext()
					.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
					.getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
				settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
			}).create()) {
			context.waitFor(client -> client.level != null && client.player != null);
			context.waitTicks(10);
			context.getInput().lookAt(0.0F, 30.0F);
			context.waitTicks(5);
			LOGGER.info("Metal lifecycle validation: singleplayer world loaded");

			context.getInput().resizeWindow(RESIZED_WIDTH, RESIZED_HEIGHT);
			context.waitFor(client -> client.getWindow().getWidth() == RESIZED_WIDTH
				&& client.getWindow().getHeight() == RESIZED_HEIGHT);
			context.waitTicks(5);
			LOGGER.info("Metal lifecycle validation: resized to {}x{}", RESIZED_WIDTH, RESIZED_HEIGHT);

			boolean initiallyFullscreen = context.computeOnClient(client -> client.getWindow().isFullscreen());
			context.runOnClient(client -> client.getWindow().toggleFullScreen());
			context.waitFor(client -> client.getWindow().isFullscreen() != initiallyFullscreen);
			context.waitTicks(5);
			context.runOnClient(client -> client.getWindow().toggleFullScreen());
			context.waitFor(client -> client.getWindow().isFullscreen() == initiallyFullscreen);
			context.waitTicks(5);
			LOGGER.info("Metal lifecycle validation: fullscreen round trip completed");

			CompletableFuture<Void> reload = context.computeOnClient(client -> client.reloadResourcePacks());
			context.waitFor(ignored -> reload.isDone(), 2400);
			if (reload.isCompletedExceptionally()) {
				throw new AssertionError("Metal lifecycle resource reload failed", reload.handle((ignored, error) -> error).join());
			}
			context.waitFor(client -> client.gui.overlay() == null);
			context.waitTicks(POST_RELOAD_SETTLE_TICKS);
			LOGGER.info("Metal lifecycle validation: resource reload completed");
			selection.assertActive();

			Path screenshot = context.takeScreenshot("metalcraft-world-" + backend.toLowerCase(Locale.ROOT));
			if (!Files.isRegularFile(screenshot) || fileSize(screenshot) == 0L) {
				throw new AssertionError("Metal lifecycle screenshot was not written: " + screenshot);
			}
			assertScreenshotVaries(screenshot);
			LOGGER.info("Metal lifecycle validation: screenshot written to {}", screenshot.toAbsolutePath());
			selection.assertFailureRecovery();

			context.runOnClient(ignored -> MetalFrameMetrics.beginCapture(CAPTURE_WARMUP_FRAMES));
			// Known tasks through the real queue, so the census is proved to be recording rather than
			// merely silent. Benchmark phases routinely drain an empty queue, which reads identically
			// to a census whose injection point has drifted onto a method Minecraft no longer routes
			// tasks through - the failure this instrument is most exposed to, because it hooks an
			// internal event-loop method rather than anything Blaze3D promises.
			//
			// Submitted from the test thread, deliberately. Minecraft.execute runs a task inline when
			// the caller is already the game thread, so a probe submitted from inside runOnClient
			// would exercise doRunTask without ever touching the queue - which is most of what is
			// being claimed. From here the task is scheduled, polled, and then run. The tasks are
			// empty, so they cost the surrounding measurement nothing.
			//
			// The instance is fetched through the harness rather than from Minecraft.getInstance(),
			// which the gametest thread is forbidden to call; execute is what the guard exists to
			// point callers at, and is safe from any thread.
			Minecraft client = context.computeOnClient(instance -> instance);
			for (int submission = 0; submission < CENSUS_PROBE_TASKS; submission++) {
				client.execute(() -> { });
			}
			context.waitTicks(PERFORMANCE_SAMPLE_TICKS);
			MetalFrameMetrics.Phase metrics = context.computeOnClient(ignored -> MetalFrameMetrics.endCapture("lifecycle"));
			LOGGER.info("Metal lifecycle performance: backend={} {}", backend, metrics.toLogLine());
			// The lifecycle capture runs while the world is still streaming, which the benchmark
			// phases deliberately do not - they wait for terrain to settle first. So this is the one
			// place in the suite where the main-thread task queue is reliably busy, and printing its
			// attribution here is what makes a quiet benchmark readable as a quiet queue rather than
			// as a probe that stopped working.
			for (String line : metrics.toAttributionLines()) {
				LOGGER.info("Metal lifecycle stall: {}", line);
			}
			assertCensusRecorded(metrics, "total", "ran no tasks at all");
			if (metrics.frames() < 120) {
				throw new AssertionError("Lifecycle captured too few complete render frames: " + metrics.frames());
			}
			if (metrics.averageFps() < MINIMUM_ACCEPTABLE_FPS) {
				throw new AssertionError("Lifecycle performance is below " + MINIMUM_ACCEPTABLE_FPS
					+ " FPS: backend=" + backend + " " + metrics.toLogLine());
			}
		}

		LOGGER.info("Metal lifecycle validation: world closed; clean client shutdown requested");

		new MetalCreativeSearchGameTest(context).run();
	}

	/** Opt-in pack coverage; always restores the user's original selection. */
	private static final class ShaderLifecycleSelection implements AutoCloseable {
		private final ClientGameTestContext context;
		private final String original;

		ShaderLifecycleSelection(final ClientGameTestContext context) {
			this.context = context;
			this.original = Boolean.getBoolean("metalcraft.lifecycleShaders")
				? context.computeOnClient(client -> {
					ShaderPackRuntime runtime = ShaderPackRuntime.active();
					if (runtime == null) throw new AssertionError("Shader runtime unavailable");
					String previous = runtime.selectedPackId();
					runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
					return previous;
				}) : null;
		}

		void assertActive() {
			if (this.original == null) return;
			this.context.runOnClient(client -> {
				ShaderPackRuntime runtime = ShaderPackRuntime.active();
				if (runtime == null || !runtime.isActive() || runtime.lastError().isPresent()
					|| runtime.worldGeometry() == null || runtime.worldShadows() == null || runtime.target("post_color") == null) {
					throw new AssertionError("Shader pack did not survive world reload/resize");
				}
				if (runtime.worldShadows().renderedFrames() == 0 || runtime.worldShadows().lastDrawCount() == 0) {
					throw new AssertionError("Shader pack did not encode loaded terrain into the sun shadow map");
				}
				LOGGER.info("Metal terrain shadows: {} draws, {} rendered frames",
					runtime.worldShadows().lastDrawCount(), runtime.worldShadows().renderedFrames());
				if (runtime.target("post_color").descriptor().width() != runtime.frameWidth()
					|| runtime.target("post_color").descriptor().height() != runtime.frameHeight()) {
					throw new AssertionError("Shader post target does not match configured surface");
				}
			});
		}

		void assertFailureRecovery() {
			if (this.original == null) return;
			this.context.runOnClient(client -> {
				ShaderPackRuntime runtime = ShaderPackRuntime.active();
				// Deterministic load failure without modifying any installed pack.
				runtime.selectPack("metalcraft-lifecycle-missing-pack");
				if (runtime.isActive() || runtime.lastError().isEmpty() || runtime.worldGeometry() != null || runtime.worldShadows() != null) {
					throw new AssertionError("Failed pack did not fall back to vanilla");
				}
			});
			this.context.waitTicks(10);
			this.context.runOnClient(client -> ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID));
			this.context.waitTicks(10);
			this.assertActive();
			LOGGER.info("Metal shader lifecycle validation: reload, resize, failure fallback and recovery passed");
		}

		@Override
		public void close() {
			if (this.original != null) {
				this.context.runOnClient(client -> ShaderPackRuntime.active().selectPack(this.original));
			}
		}
	}

	/**
	 * Frame-pacing benchmark over a world a player would actually play in.
	 *
	 * <p>The scenario it replaces measured a spectator frozen at Y=150 above a seed-1 coastline,
	 * with structures off, at 21 chunks and a 1920x1080 render target. Roughly three quarters of
	 * that frame was empty sky over flat ocean, no chunk was meshed or uploaded while the capture
	 * ran, and no entity, particle, or HUD work was in the frame. It reported several hundred FPS
	 * against 60-70 FPS in real play, so it could not rank optimizations.
	 *
	 * <p>This scenario instead generates ordinary terrain with structures on, locates inland
	 * high-relief ground from generator noise, stands the camera on that ground at eye height
	 * looking at the horizon, renders at the display's native resolution, and captures three
	 * separate phases: stationary, panning, and traversing. Traversal is what forces continuous
	 * chunk generation, meshing, and buffer upload, which is exactly the work the old capture
	 * excluded.
	 */
	private static final class MetalRealWorldBenchmark {
		private final ClientGameTestContext context;
		private final String backend;
		private final String seed;
		private final int renderDistance;
		private final int simulationDistance;
		private final int phaseTicks;
		private final int repeats;
		private final float pitch;
		private final com.google.gson.JsonObject memorySamples = new com.google.gson.JsonObject();
		private final com.google.gson.JsonObject cameraSamples = new com.google.gson.JsonObject();
		private final com.google.gson.JsonObject presentationSamples = new com.google.gson.JsonObject();
		private MetalBenchmarkEnvironment.Presentation presentation;
		private final double minimumFps;
		private final double minimumOnePercentLow;
		/** The presented drawable size, recorded so the report states it rather than the request. */
		private int drawableWidth;
		private int drawableHeight;
		private boolean chunkLoadAndRenderSettlePassed;
		private final com.google.gson.JsonObject readiness = new com.google.gson.JsonObject();

		private MetalRealWorldBenchmark(final ClientGameTestContext context, final String backend) {
			this.context = context;
			this.backend = backend;
			this.seed = System.getProperty("metalcraft.benchmarkSeed", "metalcraft");
			this.renderDistance = intProperty("metalcraft.benchmarkRenderDistance", 16);
			// Matches the reported real session rather than the vanilla default of 12.
			this.simulationDistance = intProperty("metalcraft.benchmarkSimulationDistance", 16);
			this.phaseTicks = intProperty("metalcraft.benchmarkPhaseTicks", 400);
			// One pass of each phase is not a measurement. Two runs of this scenario an hour apart
			// differed by 1.5x with identical per-frame CPU time, so a single pass cannot rank a
			// change against the machine's own drift.
			this.repeats = Math.max(1, intProperty("metalcraft.benchmarkRepeats", 3));
			double requestedPitch = doubleProperty("metalcraft.benchmarkPitch", 0);
			if (!Double.isFinite(requestedPitch) || requestedPitch < -80 || requestedPitch > 80) throw new IllegalArgumentException("Benchmark pitch must be -80–80 degrees");
			this.pitch = (float)requestedPitch;
			this.minimumFps = doubleProperty("metalcraft.benchmarkMinimumFps", 20.0);
			// A low floor on purpose. These gates exist to catch a broken scene, not to abort a run over
			// a real frame-time stall: the traversal phase's streaming stalls are a defect the
			// benchmark is meant to report on, and failing the run over them destroys the report.
			this.minimumOnePercentLow = doubleProperty("metalcraft.benchmarkMinimumOnePercentLow", 5.0);
		}

		private void run() {
			var worldBuilder = this.context.worldBuilder()
				.setUseConsistentSettings(false)
				.adjustSettings(settings -> {
					settings.setSeed(this.seed);
					var normal = settings.getSettings().worldgenLoadContext()
						.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
						.getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
					settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
					settings.setGenerateStructures(true);
					settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
					settings.setDifficulty(Difficulty.NORMAL);
					settings.setAllowCommands(true);
					// Mob spawning stays at its default so entity rendering is in the frame. Time and
					// weather are frozen because they change the scene, not the renderer's workload,
					// and would make repeated runs incomparable.
					settings.getGameRules().set(GameRules.ADVANCE_TIME, Boolean.FALSE, null);
					settings.getGameRules().set(GameRules.ADVANCE_WEATHER, Boolean.FALSE, null);
				});

			try (TestSingleplayerContext world = worldBuilder.create()) {
				this.context.waitFor(client -> client.level != null && client.player != null);
				this.context.waitTicks(10);

				MetalBenchmarkScene.Site site = world.getServer().computeOnServer(server ->
					MetalBenchmarkScene.choose(server.overworld(), 0, 0, SITE_SEARCH_RADIUS, SITE_CANDIDATE_STEP));
				LOGGER.info("Metal benchmark scene: x={} y={} z={} yaw={} roughness={} minimumGroundY={}",
					site.x(), site.groundY(), site.z(), format(site.yaw()), format(site.roughness()), site.minimumGroundY());

				// The camera moves before the render distance is raised. Raising it first would make
				// the integrated server generate a full 32-chunk radius around spawn that the
				// benchmark then teleports away from, doubling an already long generation pass.
				this.moveCameraTo(world, site);
				int[] resolution = this.applyDisplaySettings();
				this.reloadResourcePacks();
				double loadedFraction = this.awaitLoadedTerrain(world);
				// Receiving chunks and draining the current queue does not warm terrain behind
				// the camera. Prime the entire pan route before any steady-state comparison.
				for (int tick = 0; tick < 120; tick++) {
					this.context.getInput().lookAt(site.yaw() + tick * 3.0F, this.pitch);
					this.tick(world);
				}
				this.context.getInput().lookAt(site.yaw(), this.pitch);
				this.context.waitTicks(100);
				world.getConnection().waitForChunksRender(false, 2400);
				this.readiness.addProperty("panWarmupTicks", 120);
				this.cameraSamples.add("initial", this.context.computeOnClient(ignored -> MetalBenchmarkEnvironment.camera()));
				if (Math.abs(this.cameraSamples.getAsJsonObject("initial").get("pitch").getAsFloat() - this.pitch) > 0.01F) {
					throw new AssertionError("Benchmark camera did not adopt requested pitch");
				}

				Path screenshot = this.context.takeScreenshot("metalcraft-world-"
					+ this.backend.toLowerCase(Locale.ROOT) + "-benchmark");
				if (!Files.isRegularFile(screenshot) || fileSize(screenshot) == 0L) {
					throw new AssertionError("Metal benchmark screenshot was not written: " + screenshot);
				}
				assertNoColorInversion(screenshot);
				double flatFraction = featurelessFrameFraction(screenshot);
				int visibleSections = this.assertSceneDrawsTerrain();

				// Repeats interleave the phases rather than running each phase's repeats together, so a
				// drift in machine state spreads across all three phases instead of penalising
				// whichever one happened to run last.
				List<MetalFrameMetrics.Phase> phases = new ArrayList<>();
				for (int repeat = 1; repeat <= this.repeats; repeat++) {
					this.moveCameraTo(world, site);
					this.context.waitTicks(100);
					this.cameraSamples.add("repeat#" + repeat + ":start", this.context.computeOnClient(ignored -> MetalBenchmarkEnvironment.camera()));
					phases.add(this.captureStationary(world, repeat));
					phases.add(this.capturePan(world, repeat));
					phases.add(this.captureTraversal(world, repeat));
				}

				this.report(resolution, site, flatFraction, loadedFraction, visibleSections, phases);
				this.warnIfDisplayPaced(phases);
				this.assertThresholds(phases);
			}

			LOGGER.info("Metal benchmark: world closed; clean client shutdown requested");
		}

		/** @return the effective render resolution as {width, height} */
		private int[] applyDisplaySettings() {
			int[] requested = requestedResolution(this.context);
			boolean fullscreen = Boolean.getBoolean("metalcraft.benchmarkFullscreen");
			if (fullscreen) {
				// Fabric resizeWindow forces windowed mode. Native fullscreen must retain
				// the monitor's drawable and only apply the separate scene render scale.
				this.context.runOnClient(client -> {
					if (!client.getWindow().isFullscreen()) client.getWindow().toggleFullScreen();
				});
				this.context.waitFor(client -> GLFW.glfwGetWindowMonitor(client.getWindow().handle()) != 0L, 200);
				this.context.runOnClient(MetalCraftRenderResolution::apply);
			} else {
				this.resizeToPixels(requested);
			}
			this.context.runOnClient(client -> {
				// The preset rewrites the individual graphics options, so it has to be applied first.
				client.options.graphicsPreset().set(GraphicsPreset.FANCY);
				client.options.renderDistance().set(this.renderDistance);
				client.options.simulationDistance().set(this.simulationDistance);
				client.options.cloudRange().set(128);
				client.options.mipmapLevels().set(4);
				client.options.maxAnisotropyBit().set(2);
				client.options.textureFiltering().set(TextureFilteringMethod.ANISOTROPIC);
				client.options.enableVsync().set(false);
				client.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
				// Minecraft throttles to 30 FPS after 60 seconds without input under the default AFK
				// setting, and a benchmark whose camera holds still is guaranteed to trip it: the
				// stationary and panning phases both measured exactly 33.333 ms per frame while the
				// CPU spent 10 ms on it, and only the phase that held a movement key ran free.
				client.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
				// ChunkMap clamps what it sends to the view distance the client last announced in its
				// ClientInformation packet, which setting the option does not re-send. Without this
				// broadcast the server keeps feeding the distance the client joined with - the
				// previous harness asked for 21 chunks and was served 5, so its frame was mostly fog.
				client.options.broadcastOptions();
				client.invalidateSurfaceConfiguration();
			});
			int[] achieved = this.awaitConfiguredDrawable(requested);
			this.context.runOnClient(client -> {
				if (client.getWindow().isFullscreen() != fullscreen
						|| (GLFW.glfwGetWindowMonitor(client.getWindow().handle()) != 0L) != fullscreen)
					throw new AssertionError("Benchmark window mode did not match its requested presentation mode");
			});
			LOGGER.info("Metal benchmark: rendering at {}x{}, {} chunks render distance, {} chunks simulation distance",
				achieved[0], achieved[1], this.renderDistance, this.simulationDistance);
			return achieved;
		}

		/**
		 * Sizes the window so the pixels Minecraft draws are the pixels the surface presents.
		 *
		 * <p>The gametest harness's {@code resizeWindow} writes one number into both the window size
		 * and the framebuffer size. GLFW measures windows in screen coordinates and framebuffers in
		 * pixels, so on a Retina display those are not the same number, and asking for 3840x2160
		 * produced a 3840x2160 render target presented on a 7680x2104 drawable - a final blit that
		 * upscaled two-to-one and squashed the aspect ratio, on top of a resolution figure that
		 * described neither buffer. The request is therefore converted to screen coordinates, and the
		 * framebuffer Minecraft believes in is then set from the drawable the window manager actually
		 * gave, which is the only size both halves can agree on.
		 */
		private void resizeToPixels(final int[] requestedPixels) {
			float scale = this.context.computeOnClient(MetalLifecycleGameTest::contentScale);
			int points = Math.max(1, Math.round(requestedPixels[0] / scale));
			int pointsHigh = Math.max(1, Math.round(requestedPixels[1] / scale));
			this.context.getInput().resizeWindow(points, pointsHigh);
			// The window manager may clamp the request - macOS keeps a windowed frame under the menu
			// bar - so the achieved size is read back rather than assumed.
			this.context.waitFor(client -> framebufferSize(client)[0] > 0);
			this.context.runOnClient(MetalCraftRenderResolution::apply);
			this.context.waitTicks(5);
		}

		/**
		 * Waits for the surface to be configured after the resize and checks it against the render
		 * target, so a run cannot silently present at a size it never drew.
		 *
		 * @return the render resolution actually in use as {width, height}
		 */
		private int[] awaitConfiguredDrawable(final int[] requestedPixels) {
			// Only the Metal surface publishes what it was configured with. Another backend under
			// comparison still has to report a drawable size, so it is read from the window instead.
			boolean metal = "Metal".equals(this.backend);
			if (metal) {
				long before = MetalSurfaceProbe.generation();
				this.context.waitFor(client -> MetalSurfaceProbe.generation() != before);
			}
			int[] rendered = this.context.computeOnClient(client ->
				new int[] {client.getWindow().getWidth(), client.getWindow().getHeight()});
			int[] drawable = metal
				? MetalSurfaceProbe.drawableSize()
				: this.context.computeOnClient(MetalLifecycleGameTest::framebufferSize);
			if (drawable[0] <= 0 || drawable[1] <= 0) {
				throw new AssertionError("The " + this.backend + " surface reported no drawable size, so "
					+ "the benchmark cannot state the resolution it presented at.");
			}
			int expectedWidth = MetalCraftRenderResolution.scaleDimension(drawable[0]);
			int expectedHeight = MetalCraftRenderResolution.scaleDimension(drawable[1]);
			if (rendered[0] != expectedWidth || rendered[1] != expectedHeight) {
				throw new AssertionError(this.backend + " benchmark renders at " + rendered[0] + "x" + rendered[1]
					+ " but presents on a " + drawable[0] + "x" + drawable[1] + " drawable, so every frame "
					+ "ends in a rescaling blit and no resolution figure describes the run.");
			}
			if (rendered[0] != requestedPixels[0] || rendered[1] != requestedPixels[1]) {
				// Not fatal: a windowed frame cannot always reach the display's full pixel count. It is
				// loud because the reported number is now the achieved one, not the requested one.
				LOGGER.warn("Metal benchmark: requested {}x{} but the window manager gave {}x{}; results "
					+ "are reported at the achieved size and are not comparable with runs at another size.",
					requestedPixels[0], requestedPixels[1], rendered[0], rendered[1]);
			}
			this.drawableWidth = drawable[0];
			this.drawableHeight = drawable[1];
			return rendered;
		}

		private void reloadResourcePacks() {
			CompletableFuture<Void> reload = this.context.computeOnClient(client -> client.reloadResourcePacks());
			this.context.waitFor(ignored -> reload.isDone(), 2400);
			if (reload.isCompletedExceptionally()) {
				throw new AssertionError("Metal benchmark resource reload failed",
					reload.handle((ignored, error) -> error).join());
			}
			this.context.waitFor(client -> client.gui.overlay() == null);
		}

		private void moveCameraTo(final TestSingleplayerContext world, final MetalBenchmarkScene.Site site) {
			// Creative flight keeps the HUD and held item in the frame, unlike the spectator mode the
			// previous scenario used, and lets the traversal phase move at a steady speed.
			world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().forEach(player -> {
				player.getAbilities().mayfly = true;
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			}));
			world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %d %.2f %d %.1f %.1f",
				site.x(), site.groundY() + PLAYER_EYE_HEIGHT, site.z(), site.yaw(), this.pitch));
			this.context.getInput().lookAt(site.yaw(), this.pitch);
		}

		/**
		 * Settles the world before the capture, without requiring it to settle completely. Generating
		 * and streaming a 32-chunk radius of fresh terrain can outlast any reasonable deadline, and a
		 * player who has just flown somewhere is looking at a partly loaded world too. The measured
		 * fraction is reported so a badly under-loaded run is visible rather than silent.
		 *
		 * @return the fraction of the chunks inside the render distance that the client holds
		 */
		/**
		 * Captures one window of frames while terrain is still streaming, for attribution only.
		 *
		 * <p>The three measured phases all run after the settle wait, and the main-thread task queue
		 * is empty by then: three runs of the census counted zero tasks across roughly 2350 drains.
		 * The 170 ms spike this renderer's largest open item is about lives on the other side of that
		 * wait, in the window the harness exists to skip past. So this one is captured inside it.
		 *
		 * <p>It is deliberately not one of the phases. Its duration depends on how fast the generator
		 * runs, its frame rate is dominated by work that is not the renderer's, and it would be
		 * incomparable between runs - so it is logged and never fed to the report, the display-pacing
		 * check, or the thresholds.
		 */
		private void finishStreamingCapture() {
			MetalFrameMetrics.Phase phase =
				this.context.computeOnClient(ignored -> MetalFrameMetrics.endCapture("streaming"));
			LOGGER.info("Metal benchmark: {}", phase.toLogLine());
			for (String line : phase.toAttributionLines()) {
				LOGGER.info("Metal benchmark stall: {}", line);
			}
		}

		private double awaitLoadedTerrain(final TestSingleplayerContext world) {
			int served = world.getServer().computeOnServer(server ->
				server.getPlayerList().getPlayers().stream().mapToInt(player -> player.requestedViewDistance()).max().orElse(0));
			if (served < this.renderDistance) {
				throw new AssertionError("The server is serving " + served + " chunks while the client is set "
					+ "to " + this.renderDistance + "; the announced view distance did not reach the server "
					+ "and the benchmark would measure a world far smaller than it claims");
			}

			// The server's rounded tracking footprint is the authoritative receive contract.
			// Fabric requireLoaded=true instead checks square corners the server need not send.
			List<net.minecraft.world.level.ChunkPos> tracked = world.getServer().computeOnServer(server -> {
				var positions = new ArrayList<net.minecraft.world.level.ChunkPos>();
				server.getPlayerList().getPlayers().getFirst().getChunkTrackingView().forEach(positions::add);
				return List.copyOf(positions);
			});
			if (tracked.isEmpty()) throw new AssertionError("Server tracking footprint is empty");
			int missing = tracked.size();
			int captureStartedTick = -1;
			boolean captureTaken = false;
			for (int tick = 0; tick < CHUNK_LOAD_TIMEOUT_TICKS; tick++) {
				this.tick(world);
				if (captureStartedTick >= 0 && tick - captureStartedTick >= this.phaseTicks) {
					this.finishStreamingCapture(); captureStartedTick = -1; captureTaken = true;
				}
				if (tick % 20 != 0) continue;
				missing = this.context.computeOnClient(client -> (int)tracked.stream()
					.filter(pos -> !client.level.hasChunk(pos.x(), pos.z())).count());
				if (missing == 0) break;
				if (!captureTaken && captureStartedTick < 0 && 1.0 - (double)missing / tracked.size() >= STREAMING_CAPTURE_START_FRACTION) {
					this.context.runOnClient(ignored -> MetalFrameMetrics.beginCapture(CAPTURE_WARMUP_FRAMES));
					captureStartedTick = tick;
				}
				if (tick % 200 == 0) LOGGER.info("Metal benchmark: {} / {} server-tracked chunks missing", missing, tracked.size());
			}
			if (captureStartedTick >= 0) this.finishStreamingCapture();
			this.readiness.addProperty("trackedChunks", tracked.size());
			this.readiness.addProperty("missingTrackedChunks", missing);
			this.readiness.addProperty("contract", "Every chunk in the server tracking footprint received; Fabric light queue and renderer compile queue drained");
			if (missing != 0) throw new AssertionError("Benchmark has " + missing + " missing server-tracked chunks; refusing unqualified timings");
			world.getConnection().waitForChunksRender(false, 2400);
			this.chunkLoadAndRenderSettlePassed = true;
			this.readiness.addProperty("lightAndRenderQueuesDrained", true);
			LOGGER.info("Metal benchmark: all {} server-tracked chunks received, light/render queues drained", tracked.size());
			this.context.waitTicks(100);

			return 1.0;
		}

		/**
		 * Advances one tick with the server's chunk send rate pinned to its maximum.
		 *
		 * <p>The server paces chunk delivery from a rate the client measures in wall-clock time per
		 * chunk. Under the gametest harness the client thread is parked between ticks, so that
		 * measurement reads as enormously slow and the rate collapses to its 0.01 chunks-per-tick
		 * floor: an earlier run of this benchmark held 157 of 4225 chunks after twenty minutes and
		 * would have measured an almost empty world. Pinning the rate restores the delivery a local
		 * integrated server gives a real client, and it is held for the captures too, so the
		 * traversal phase streams terrain instead of starving.
		 */
		private void tick(final TestSingleplayerContext world) {
			world.getServer().runOnServer(server -> server.getPlayerList().getPlayers().forEach(player ->
				player.connection.chunkSender.onChunkBatchReceivedByClient(PlayerChunkSender.MAX_CHUNKS_PER_TICK)));
			this.context.waitTick();
			if (this.presentation != null) this.context.runOnClient(c -> this.presentation.check());
		}

		private void beginPhase() {
			MetalBenchmarkEnvironment.focus(this.context);
			this.context.runOnClient(c -> {
				this.presentation = new MetalBenchmarkEnvironment.Presentation();
				MetalFrameMetrics.beginCapture(CAPTURE_WARMUP_FRAMES);
			});
		}

		private MetalFrameMetrics.Phase captureStationary(final TestSingleplayerContext world, final int repeat) {
			this.beginPhase();
			for (int tick = 0; tick < this.phaseTicks; tick++) {
				this.tick(world);
			}
			return this.finishPhase("stationary", repeat);
		}

		/** A full rotation, which is the worst case for frustum culling and section visibility. */
		private MetalFrameMetrics.Phase capturePan(final TestSingleplayerContext world, final int repeat) {
			float startYaw = this.context.computeOnClient(client -> client.player.getYRot());
			float step = 360.0F / this.phaseTicks;
			this.beginPhase();
			for (int tick = 0; tick < this.phaseTicks; tick++) {
				this.context.getInput().lookAt(startYaw + step * tick, this.pitch);
				this.tick(world);
			}
			return this.finishPhase("pan", repeat);
		}

		/** Continuous flight, which is the only phase that forces chunk generation, meshing, and upload. */
		private MetalFrameMetrics.Phase captureTraversal(final TestSingleplayerContext world, final int repeat) {
			float startYaw = this.context.computeOnClient(client -> client.player.getYRot());
			this.context.getInput().lookAt(startYaw, this.pitch);
			this.context.getInput().holdKey(options -> options.keyUp);
			try {
				this.beginPhase();
				for (int tick = 0; tick < this.phaseTicks; tick++) {
					// A slow drift keeps newly generated terrain entering the frustum from the side
					// rather than only from straight ahead.
					this.context.getInput().lookAt(startYaw + 20.0F * (float)Math.sin(tick / 60.0), this.pitch);
					this.tick(world);
				}
				return this.finishPhase("traversal", repeat);
			} finally {
				this.context.getInput().releaseKey(options -> options.keyUp);
			}
		}

		private MetalFrameMetrics.Phase finishPhase(final String baseName, final int repeat) {
			String name = this.repeats == 1 ? baseName : baseName + "#" + repeat;
			MetalFrameMetrics.Phase phase = this.context.computeOnClient(ignored -> MetalFrameMetrics.endCapture(name));
			this.presentationSamples.add(name, this.context.computeOnClient(c -> this.presentation.describe()));
			this.presentation = null;
			this.memorySamples.add(name, this.context.computeOnClient(ignored -> MetalBenchmarkEnvironment.sample()));
			this.cameraSamples.add(name + ":end", this.context.computeOnClient(ignored -> MetalBenchmarkEnvironment.camera()));
			LOGGER.info("Metal benchmark: {}", phase.toLogLine());
			for (String line : phase.toAttributionLines()) {
				LOGGER.info("Metal benchmark stall: {}", line);
			}
			if (phase.frames() < 120) {
				throw new AssertionError("Metal benchmark phase " + name
					+ " captured too few complete render frames: " + phase.frames());
			}
			return phase;
		}

		/**
		 * Fails when the camera is not actually drawing a render distance worth of world.
		 *
		 * <p>The count of sections the renderer submits is the quantity the benchmark exists to
		 * stress, and unlike an image statistic it does not care whether the terrain is textured
		 * grass or flat-shaded snow. An earlier run stood two metres from a hillside: the frame was
		 * full of blocks and drew almost none of the world.
		 *
		 * @return the number of chunk sections the renderer drew
		 */
		private int assertSceneDrawsTerrain() {
			int visibleSections = this.context.computeOnClient(client -> client.levelRenderer.visibleSections().size());
			int minimum = intProperty("metalcraft.benchmarkMinimumVisibleSections", 600);
			LOGGER.info("Metal benchmark scene: renderer is drawing {} chunk sections", visibleSections);
			if (visibleSections < minimum) {
				throw new AssertionError("The camera is drawing only " + visibleSections + " chunk sections, "
					+ "below the " + minimum + " minimum. The view is blocked, so the render distance is "
					+ "doing no work and the measurement would not describe real play.");
			}
			return visibleSections;
		}

		private void report(final int[] resolution, final MetalBenchmarkScene.Site site,
				final double flatFraction, final double loadedFraction, final int visibleSections,
				final List<MetalFrameMetrics.Phase> phases) {
			String architecture = System.getProperty("os.arch", "unknown");
			// The tail of this benchmark is sensitive to heap pressure, so a result that does not
			// state the heap it ran on is not comparable with another result.
			long maxHeapMiB = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
			String collectors = ManagementFactory.getGarbageCollectorMXBeans().stream()
				.map(GarbageCollectorMXBean::getName)
				.collect(Collectors.joining("+"));
			String header = String.format(Locale.ROOT,
				"backend=%s arch=%s maxHeapMiB=%d gc=%s resolution=%dx%d drawable=%dx%d renderDistance=%d "
					+ "simulationDistance=%d seed=%s "
					+ "site=%d,%d,%d roughness=%s flatFrameFraction=%s loadedChunkFraction=%s visibleSections=%d",
				this.backend, architecture, maxHeapMiB, collectors, resolution[0], resolution[1],
				this.drawableWidth, this.drawableHeight, this.renderDistance,
				this.simulationDistance, this.seed, site.x(), site.groundY(), site.z(),
				format(site.roughness()), format(flatFraction), format(loadedFraction), visibleSections);
			LOGGER.info("Metal benchmark result: {}", header);
			for (MetalFrameMetrics.Phase phase : phases) {
				LOGGER.info("Metal benchmark result: {}", phase.toLogLine());
			}
			for (String name : List.of("stationary", "pan", "traversal")) {
				List<MetalFrameMetrics.Phase> group = phases.stream()
					.filter(phase -> phase.name().equals(name) || phase.name().startsWith(name + "#"))
					.toList();
				if (group.size() > 1) {
					LOGGER.info("Metal benchmark summary: {}", summarise(name, group));
				}
			}

			String json = String.format(Locale.ROOT,
				"{\"backend\":\"%s\",\"arch\":\"%s\",\"maxHeapMiB\":%d,\"gc\":\"%s\",\"width\":%d,\"height\":%d,"
					+ "\"drawableWidth\":%d,\"drawableHeight\":%d,"
					+ "\"renderDistance\":%d,\"simulationDistance\":%d,\"seed\":\"%s\","
					+ "\"siteX\":%d,\"siteY\":%d,\"siteZ\":%d,\"siteRoughness\":%.3f,\"flatFrameFraction\":%.4f,"
					+ "\"loadedChunkFraction\":%.4f,\"visibleSections\":%d,"
					+ "\"phases\":[%s]}%n",
				this.backend, architecture, maxHeapMiB, collectors, resolution[0], resolution[1],
				this.drawableWidth, this.drawableHeight, this.renderDistance,
				this.simulationDistance, this.seed, site.x(), site.groundY(), site.z(), site.roughness(),
				flatFraction, loadedFraction, visibleSections, phases.stream().map(MetalFrameMetrics.Phase::toJson).collect(Collectors.joining(",")));
			Path output = Path.of("benchmarks", "metalcraft-" + this.backend.toLowerCase(Locale.ROOT) + ".json");
			var report = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
			report.add("environment", this.context.computeOnClient(ignored -> MetalBenchmarkEnvironment.describe()));
			report.addProperty("routeVersion", 3);
			report.add("readiness", this.readiness);
			report.addProperty("gpuFrameContract", "First GPU start to last GPU end of buffers committed on the render thread inside each frame; includes gaps, never sums overlapping spans; pending/invalid/empty/overflow frames excluded and counted");
			report.addProperty("worldPreset", "minecraft:normal");
			report.addProperty("chunkLoadAndRenderSettlePassed", this.chunkLoadAndRenderSettlePassed);
			report.addProperty("requestedPitchDegrees", this.pitch);
			report.add("cameraSamples", this.cameraSamples);
			report.add("memoryAfterPhase", this.memorySamples);
			report.add("presentationAfterPhase", this.presentationSamples);
			json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report);
			try {
				Files.createDirectories(output.getParent());
				Files.writeString(output, json, StandardCharsets.UTF_8);
				LOGGER.info("Metal benchmark result written to {}", output.toAbsolutePath());
			} catch (Exception error) {
				throw new AssertionError("Could not write Metal benchmark result to " + output, error);
			}
		}

		/**
		 * Reports the spread across repeats. The median is the number worth quoting; the range is
		 * what says whether it can be trusted, because the machine's own drift has been larger than
		 * many of the changes this benchmark exists to evaluate.
		 */
		private static String summarise(final String name, final List<MetalFrameMetrics.Phase> group) {
			double[] averages = group.stream().mapToDouble(MetalFrameMetrics.Phase::averageFps).sorted().toArray();
			double[] lows = group.stream().mapToDouble(MetalFrameMetrics.Phase::onePercentLowFps).sorted().toArray();
			double worst = group.stream().mapToDouble(MetalFrameMetrics.Phase::worstIntervalMs).max().orElse(0.0);
			double medianFps = median(averages);
			double spread = averages[averages.length - 1] - averages[0];
			return String.format(Locale.ROOT,
				"phase=%s repeats=%d medianFps=%.1f minFps=%.1f maxFps=%.1f spreadFps=%.1f "
					+ "spreadPercent=%.1f medianOnePercentLow=%.1f worstIntervalMs=%.3f",
				name, group.size(), medianFps, averages[0], averages[averages.length - 1], spread,
				medianFps == 0.0 ? 0.0 : 100.0 * spread / medianFps, median(lows), worst);
		}

		/** A true median: with an even count the midpoint of the two central values, not the upper one. */
		private static double median(final double[] sorted) {
			int middle = sorted.length / 2;
			return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0;
		}

		/**
		 * Only a floor is enforced. The scenario's job is to produce a number that tracks real play,
		 * and a target for that number has to come from measurement rather than from the harness.
		 */
		/**
		 * Warns when a phase's frames arrive at the display's refresh interval.
		 *
		 * <p>Presentation is requested in immediate mode and the frame limiter is off, so this should
		 * not happen - but it does, on the same machine that free-runs at 300 FPS an hour earlier,
		 * and a paced run absorbs exactly the stalls the capture exists to find. A run pinned to the
		 * refresh rate reports a frame rate that describes the display rather than the renderer, and
		 * comparing it against a free-running run is how a change gets credited or blamed for
		 * something it did not do.
		 *
		 * <p>A warning rather than a failure: the phase's CPU time, GPU time, and stall attribution
		 * are all still meaningful, and those are the numbers a diagnostic run is usually after.
		 */
		private void warnIfDisplayPaced(final List<MetalFrameMetrics.Phase> phases) {
			double refreshMs = this.context.computeOnClient(MetalLifecycleGameTest::refreshIntervalMs);
			if (refreshMs <= 0.0) {
				return;
			}
			for (MetalFrameMetrics.Phase phase : phases) {
				// Pinned means the median sits on the refresh interval and the spread to p99 is small;
				// a free-running phase that merely averages near it will have a much wider tail.
				boolean atRefresh = Math.abs(phase.p50IntervalMs() - refreshMs) / refreshMs < 0.05;
				boolean tight = phase.p99IntervalMs() < refreshMs * 1.25;
				if (atRefresh && tight) {
					LOGGER.warn("Metal benchmark: phase {} ran at the display's {} Hz refresh despite "
						+ "immediate presentation and no frame limit, so its frame rate describes the "
						+ "display, not the renderer. Do not compare it against a free-running run. {}",
						phase.name(), Math.round(1000.0 / refreshMs), phase.toLogLine());
				}
			}
		}

		private void assertThresholds(final List<MetalFrameMetrics.Phase> phases) {
			for (MetalFrameMetrics.Phase phase : phases) {
				if (phase.averageFps() < this.minimumFps) {
					throw new AssertionError("Metal benchmark phase is below " + this.minimumFps
						+ " FPS: backend=" + this.backend + " " + phase.toLogLine());
				}
				if (phase.onePercentLowFps() < this.minimumOnePercentLow) {
					throw new AssertionError("Metal benchmark 1% low is below " + this.minimumOnePercentLow
						+ " FPS: backend=" + this.backend + " " + phase.toLogLine());
				}
			}
		}
	}

	/**
	 * The window's own pixels-per-point, read from the window rather than the monitor.
	 *
	 * <p>The monitor's content scale describes the display; this describes the surface the drawable
	 * is actually made from, and the two disagree whenever the window sits on a second display.
	 */
	private static float contentScale(final Minecraft client) {
		int[] framebuffer = framebufferSize(client);
		int[] windowWidth = new int[1];
		int[] windowHeight = new int[1];
		GLFW.glfwGetWindowSize(client.getWindow().handle(), windowWidth, windowHeight);
		if (framebuffer[0] <= 0 || windowWidth[0] <= 0) {
			throw new AssertionError("The benchmark window reported no size, which happens while the "
				+ "display is asleep or locked. Pass -PmetalBenchmarkResolution=WIDTHxHEIGHT.");
		}
		return (float)framebuffer[0] / windowWidth[0];
	}

	/** @return the primary display's refresh interval in milliseconds, or {@code 0} if unknown */
	private static double refreshIntervalMs(final Minecraft client) {
		long monitor = GLFW.glfwGetPrimaryMonitor();
		GLFWVidMode mode = monitor == 0L ? null : GLFW.glfwGetVideoMode(monitor);
		return mode == null || mode.refreshRate() <= 0 ? 0.0 : 1000.0 / mode.refreshRate();
	}

	private static int[] framebufferSize(final Minecraft client) {
		int[] width = new int[1];
		int[] height = new int[1];
		GLFW.glfwGetFramebufferSize(client.getWindow().handle(), width, height);
		return new int[] {width[0], height[0]};
	}

	/**
	 * Resolves the requested render resolution in pixels, defaulting to the primary display's native
	 * pixel dimensions. A benchmark that renders a quarter of the pixels the player's session renders
	 * cannot predict the player's frame rate, and on a Retina display a 1920x1080 target does exactly
	 * that.
	 *
	 * <p>This is a request, not a result. A windowed frame cannot always reach the display's full
	 * pixel count - macOS keeps one below the menu bar - so what the run reports is the size the
	 * window manager granted, resolved in {@code awaitConfiguredDrawable}.
	 */
	private static int[] requestedResolution(final ClientGameTestContext context) {
		String override = System.getProperty("metalcraft.benchmarkResolution", "native");
		if (!"native".equalsIgnoreCase(override)) {
			String[] parts = override.toLowerCase(Locale.ROOT).split("x", 2);
			if (parts.length != 2) {
				throw new AssertionError("metalcraft.benchmarkResolution must be WIDTHxHEIGHT or 'native': " + override);
			}
			return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
		}
		return context.computeOnClient(MetalLifecycleGameTest::nativeResolution);
	}

	/**
	 * Detection failing is a hard error rather than a fallback. GLFW returns no monitor when the
	 * display is asleep or locked, and an earlier run silently benchmarked at the gametest's default
	 * 854x480 window because of it. A benchmark that picks a different resolution per run cannot be
	 * compared against its own previous results, so an undetectable display has to be named.
	 */
	private static int[] nativeResolution(final Minecraft client) {
		long monitor = GLFW.glfwGetPrimaryMonitor();
		GLFWVidMode mode = monitor == 0L ? null : GLFW.glfwGetVideoMode(monitor);
		if (mode == null) {
			throw new AssertionError("No primary display is available to size the benchmark, which "
				+ "happens while the screen is asleep or locked. Pass an explicit resolution with "
				+ "-PmetalBenchmarkResolution=WIDTHxHEIGHT.");
		}
		float[] scaleX = new float[1];
		float[] scaleY = new float[1];
		GLFW.glfwGetMonitorContentScale(monitor, scaleX, scaleY);
		// GLFW reports the video mode in screen coordinates; the drawable is content-scaled pixels.
		int width = Math.round(mode.width() * Math.max(1.0F, scaleX[0]));
		int height = Math.round(mode.height() * Math.max(1.0F, scaleY[0]));
		if (width < 1920 || height < 1080) {
			throw new AssertionError("The detected display is " + width + "x" + height + ", which is "
				+ "smaller than any session worth benchmarking and is usually a sign the display was "
				+ "not readable. Pass -PmetalBenchmarkResolution=WIDTHxHEIGHT.");
		}
		return new int[] {width, height};
	}

	private static int intProperty(final String key, final int fallback) {
		String value = System.getProperty(key);
		return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
	}

	private static double doubleProperty(final String key, final double fallback) {
		String value = System.getProperty(key);
		return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
	}

	private static long fileSize(final Path path) {
		try {
			return Files.size(path);
		} catch (Exception error) {
			throw new AssertionError("Could not inspect Metal lifecycle screenshot " + path, error);
		}
	}

	private static String format(final double value) {
		return String.format(Locale.ROOT, "%.3f", value);
	}

	/**
	 * Fails unless the census counted at least the tasks this test deliberately put through it.
	 *
	 * <p>The probe tasks are submitted from the test thread, so a count here covers the whole path:
	 * scheduled onto the queue, taken off it, then timed and named. A drift in the injection point
	 * silences the census, and that silence would otherwise be indistinguishable from the quiet queue
	 * the benchmark phases genuinely see.
	 */
	private static void assertCensusRecorded(final MetalFrameMetrics.Phase metrics, final String counter,
			final String failure) {
		long recorded = metrics.taskKinds().stream()
			.filter(kind -> counter.equals(kind.name()))
			.mapToLong(MetalTaskCensus.TaskKind::count)
			.findFirst()
			.orElse(0L);
		if (recorded < CENSUS_PROBE_TASKS) {
			throw new AssertionError("The main-thread task census " + failure + " (" + counter + "="
				+ recorded + ") while this test submitted " + CENSUS_PROBE_TASKS
				+ "; its injection point has probably moved");
		}
	}

	private static void assertScreenshotVaries(final Path path) {
		try (NativeImage image = NativeImage.read(Files.newInputStream(path))) {
			int first = image.getPixel(0, 0);
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					if (image.getPixel(x, y) != first) {
						return;
					}
				}
			}
			throw new AssertionError("Metal lifecycle screenshot contains only one color: " + path);
		} catch (AssertionError error) {
			throw error;
		} catch (Exception error) {
			throw new AssertionError("Could not validate Metal lifecycle screenshot " + path, error);
		}
	}

	/** Catches the magenta cast that a swapped colour channel produces on distant terrain. */
	private static void assertNoColorInversion(final Path path) {
		try (NativeImage image = NativeImage.read(Files.newInputStream(path))) {
			long inspected = 0L;
			long magenta = 0L;
			int startY = image.getHeight() / 5;
			int endY = image.getHeight() * 9 / 10;
			for (int y = startY; y < endY; y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					int pixel = image.getPixel(x, y);
					int red = pixel >>> 16 & 0xFF;
					int green = pixel >>> 8 & 0xFF;
					int blue = pixel & 0xFF;
					if (red > 51 && blue > 51 && red * 2 > green * 3 && blue * 5 > green * 6) {
						magenta++;
					}
					inspected++;
				}
			}
			double ratio = (double)magenta / inspected;
			if (ratio > 0.01) {
				throw new AssertionError("Distant terrain contains Metal color-inversion artifacts: magentaRatio=" + ratio);
			}
		} catch (AssertionError error) {
			throw error;
		} catch (Exception error) {
			throw new AssertionError("Could not validate distant terrain colors in " + path, error);
		}
	}

	/**
	 * Measures how much of the frame is flat colour: sky, open water, and untextured blocks such as
	 * snow all render as large low-variance areas. This is reported rather than asserted on, because
	 * it cannot tell a sky-filled frame apart from a legitimately snowy one; the section count in
	 * {@code assertSceneDrawsTerrain} is the gate.
	 *
	 * @return the fraction of the frame occupied by featureless tiles
	 */
	private static double featurelessFrameFraction(final Path path) {
		int tileSize = 8;
		double varianceThreshold = 12.0;
		try (NativeImage image = NativeImage.read(Files.newInputStream(path))) {
			long tiles = 0L;
			long flatTiles = 0L;
			for (int tileY = 0; tileY + tileSize <= image.getHeight(); tileY += tileSize) {
				for (int tileX = 0; tileX + tileSize <= image.getWidth(); tileX += tileSize) {
					double total = 0.0;
					double totalSquares = 0.0;
					for (int y = tileY; y < tileY + tileSize; y++) {
						for (int x = tileX; x < tileX + tileSize; x++) {
							int pixel = image.getPixel(x, y);
							double luminance = 0.2126 * (pixel >>> 16 & 0xFF)
								+ 0.7152 * (pixel >>> 8 & 0xFF)
								+ 0.0722 * (pixel & 0xFF);
							total += luminance;
							totalSquares += luminance * luminance;
						}
					}
					int pixels = tileSize * tileSize;
					double mean = total / pixels;
					if (totalSquares / pixels - mean * mean < varianceThreshold) {
						flatTiles++;
					}
					tiles++;
				}
			}
			double flatFraction = (double)flatTiles / tiles;
			LOGGER.info("Metal benchmark scene: featureless frame fraction {}", format(flatFraction));
			return flatFraction;
		} catch (Exception error) {
			throw new AssertionError("Could not measure the benchmark scene in " + path, error);
		}
	}
}
