package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
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
	private static final int CHUNK_LOAD_TIMEOUT_TICKS = 24000;
	private static final double MINIMUM_LOADED_CHUNK_FRACTION = 0.75;
	/**
	 * The wait ends when the loaded fraction stops climbing rather than when it reaches a target.
	 * The server tracks a slightly smaller region than the (2r+1)^2 square the fraction is measured
	 * against, so the fraction plateaus below 1.0 at a value that depends on the version's chunk
	 * tracking, and any fixed target would either stop early or spin until the deadline.
	 */
	private static final double SETTLE_PROGRESS_EPSILON = 0.005;
	private static final int SETTLE_STALL_CHECKS = 8;
	private static final int SITE_SEARCH_RADIUS = 1536;
	private static final int SITE_CANDIDATE_STEP = 128;
	/** Eye height of a standing player, used to place the camera where a player's actually is. */
	private static final double PLAYER_EYE_HEIGHT = 1.62;

	@Override
	public void runTest(final ClientGameTestContext context) {
		String expectedBackend = System.getProperty("metalcraft.lifecycleExpectedBackend", "Metal");
		boolean benchmark = Boolean.getBoolean("metalcraft.lifecycleBenchmark");
		String backend = context.computeOnClient(ignored -> RenderSystem.getDevice().getDeviceInfo().backendName());
		if (!expectedBackend.equals(backend)) {
			throw new AssertionError("Lifecycle test selected unexpected backend: " + backend + " (expected " + expectedBackend + ")");
		}
		LOGGER.info("Metal lifecycle validation: {} backend selected", backend);

		if (benchmark) {
			new MetalRealWorldBenchmark(context, backend).run();
			return;
		}

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
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

			Path screenshot = context.takeScreenshot("metalcraft-world-" + backend.toLowerCase(Locale.ROOT));
			if (!Files.isRegularFile(screenshot) || fileSize(screenshot) == 0L) {
				throw new AssertionError("Metal lifecycle screenshot was not written: " + screenshot);
			}
			assertScreenshotVaries(screenshot);
			LOGGER.info("Metal lifecycle validation: screenshot written to {}", screenshot.toAbsolutePath());

			context.runOnClient(ignored -> MetalFrameMetrics.beginCapture(CAPTURE_WARMUP_FRAMES));
			context.waitTicks(PERFORMANCE_SAMPLE_TICKS);
			MetalFrameMetrics.Phase metrics = context.computeOnClient(ignored -> MetalFrameMetrics.endCapture("lifecycle"));
			LOGGER.info("Metal lifecycle performance: backend={} {}", backend, metrics.toLogLine());
			if (metrics.frames() < 120) {
				throw new AssertionError("Lifecycle captured too few complete render frames: " + metrics.frames());
			}
			if (metrics.averageFps() < MINIMUM_ACCEPTABLE_FPS) {
				throw new AssertionError("Lifecycle performance is below " + MINIMUM_ACCEPTABLE_FPS
					+ " FPS: backend=" + backend + " " + metrics.toLogLine());
			}
		}

		LOGGER.info("Metal lifecycle validation: world closed; clean client shutdown requested");
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
		private final double minimumFps;
		private final double minimumOnePercentLow;

		private MetalRealWorldBenchmark(final ClientGameTestContext context, final String backend) {
			this.context = context;
			this.backend = backend;
			this.seed = System.getProperty("metalcraft.benchmarkSeed", "metalcraft");
			this.renderDistance = intProperty("metalcraft.benchmarkRenderDistance", 32);
			// Matches the reported real session rather than the vanilla default of 12.
			this.simulationDistance = intProperty("metalcraft.benchmarkSimulationDistance", 16);
			this.phaseTicks = intProperty("metalcraft.benchmarkPhaseTicks", 400);
			// One pass of each phase is not a measurement. Two runs of this scenario an hour apart
			// differed by 1.5x with identical per-frame CPU time, so a single pass cannot rank a
			// change against the machine's own drift.
			this.repeats = Math.max(1, intProperty("metalcraft.benchmarkRepeats", 3));
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
					phases.add(this.captureStationary(world, repeat));
					phases.add(this.capturePan(world, repeat));
					phases.add(this.captureTraversal(world, repeat));
				}

				this.report(resolution, site, flatFraction, loadedFraction, visibleSections, phases);
				this.assertThresholds(phases);
			}

			LOGGER.info("Metal benchmark: world closed; clean client shutdown requested");
		}

		/** @return the effective render resolution as {width, height} */
		private int[] applyDisplaySettings() {
			int[] requested = requestedResolution(this.context);
			this.context.getInput().resizeWindow(requested[0], requested[1]);
			this.context.waitFor(client -> client.getWindow().getWidth() == requested[0]
				&& client.getWindow().getHeight() == requested[1]);
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
			LOGGER.info("Metal benchmark: rendering at {}x{}, {} chunks render distance, {} chunks simulation distance",
				requested[0], requested[1], this.renderDistance, this.simulationDistance);
			return requested;
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
			world.getServer().runCommand(String.format(Locale.ROOT, "tp @a %d %.2f %d %.1f 0",
				site.x(), site.groundY() + PLAYER_EYE_HEIGHT, site.z(), site.yaw()));
			this.context.getInput().lookAt(site.yaw(), 0.0F);
		}

		/**
		 * Settles the world before the capture, without requiring it to settle completely. Generating
		 * and streaming a 32-chunk radius of fresh terrain can outlast any reasonable deadline, and a
		 * player who has just flown somewhere is looking at a partly loaded world too. The measured
		 * fraction is reported so a badly under-loaded run is visible rather than silent.
		 *
		 * @return the fraction of the chunks inside the render distance that the client holds
		 */
		private double awaitLoadedTerrain(final TestSingleplayerContext world) {
			int served = world.getServer().computeOnServer(server ->
				server.getPlayerList().getPlayers().stream().mapToInt(player -> player.requestedViewDistance()).max().orElse(0));
			if (served < this.renderDistance) {
				throw new AssertionError("The server is serving " + served + " chunks while the client is set "
					+ "to " + this.renderDistance + "; the announced view distance did not reach the server "
					+ "and the benchmark would measure a world far smaller than it claims");
			}

			int expected = (2 * this.renderDistance + 1) * (2 * this.renderDistance + 1);
			double fraction = 0.0;
			double best = 0.0;
			int stalledChecks = 0;
			for (int tick = 0; tick < CHUNK_LOAD_TIMEOUT_TICKS; tick++) {
				this.tick(world);
				if (tick % 100 != 0) {
					continue;
				}
				fraction = this.loadedChunkFraction(expected);
				if (tick % 1000 == 0) {
					LOGGER.info("Metal benchmark: streaming terrain, {} of the render distance loaded",
						format(fraction));
				}
				if (fraction > best + SETTLE_PROGRESS_EPSILON) {
					best = fraction;
					stalledChecks = 0;
				} else if (++stalledChecks >= SETTLE_STALL_CHECKS && fraction >= MINIMUM_LOADED_CHUNK_FRACTION) {
					LOGGER.info("Metal benchmark: terrain settled at {} of the render distance", format(fraction));
					break;
				}
			}

			try {
				// waitFor signals a deadline with AssertionError, which is an Error rather than an
				// exception, so this has to catch the error branch explicitly.
				world.getConnection().waitForChunksRender(true, 2400);
			} catch (AssertionError error) {
				LOGGER.warn("Metal benchmark: chunk meshing did not settle; "
					+ "the capture starts with meshing still in flight");
			}
			this.context.waitTicks(100);

			fraction = this.loadedChunkFraction(expected);
			LOGGER.info("Metal benchmark: {} of the {} chunks inside the render distance are loaded",
				format(fraction), expected);
			if (fraction < MINIMUM_LOADED_CHUNK_FRACTION) {
				throw new AssertionError("Only " + format(fraction) + " of the render distance is loaded, "
					+ "below the " + MINIMUM_LOADED_CHUNK_FRACTION + " minimum; the frame would be mostly "
					+ "empty world and the measurement would not describe real play");
			}
			return fraction;
		}

		private double loadedChunkFraction(final int expected) {
			int loaded = this.context.computeOnClient(client -> client.level.getChunkSource().getLoadedChunksCount());
			return Math.min(1.0, (double)loaded / expected);
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
		}

		private MetalFrameMetrics.Phase captureStationary(final TestSingleplayerContext world, final int repeat) {
			this.context.runOnClient(ignored -> MetalFrameMetrics.beginCapture(CAPTURE_WARMUP_FRAMES));
			for (int tick = 0; tick < this.phaseTicks; tick++) {
				this.tick(world);
			}
			return this.finishPhase("stationary", repeat);
		}

		/** A full rotation, which is the worst case for frustum culling and section visibility. */
		private MetalFrameMetrics.Phase capturePan(final TestSingleplayerContext world, final int repeat) {
			float startYaw = this.context.computeOnClient(client -> client.player.getYRot());
			float step = 360.0F / this.phaseTicks;
			this.context.runOnClient(ignored -> MetalFrameMetrics.beginCapture(CAPTURE_WARMUP_FRAMES));
			for (int tick = 0; tick < this.phaseTicks; tick++) {
				this.context.getInput().lookAt(startYaw + step * tick, 0.0F);
				this.tick(world);
			}
			return this.finishPhase("pan", repeat);
		}

		/** Continuous flight, which is the only phase that forces chunk generation, meshing, and upload. */
		private MetalFrameMetrics.Phase captureTraversal(final TestSingleplayerContext world, final int repeat) {
			float startYaw = this.context.computeOnClient(client -> client.player.getYRot());
			this.context.getInput().lookAt(startYaw, 0.0F);
			this.context.getInput().holdKey(options -> options.keyUp);
			try {
				this.context.runOnClient(ignored -> MetalFrameMetrics.beginCapture(CAPTURE_WARMUP_FRAMES));
				for (int tick = 0; tick < this.phaseTicks; tick++) {
					// A slow drift keeps newly generated terrain entering the frustum from the side
					// rather than only from straight ahead.
					this.context.getInput().lookAt(startYaw + 20.0F * (float)Math.sin(tick / 60.0), 0.0F);
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
				"backend=%s arch=%s maxHeapMiB=%d gc=%s resolution=%dx%d renderDistance=%d simulationDistance=%d seed=%s "
					+ "site=%d,%d,%d roughness=%s flatFrameFraction=%s loadedChunkFraction=%s visibleSections=%d",
				this.backend, architecture, maxHeapMiB, collectors, resolution[0], resolution[1], this.renderDistance,
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
					+ "\"renderDistance\":%d,\"simulationDistance\":%d,\"seed\":\"%s\","
					+ "\"siteX\":%d,\"siteY\":%d,\"siteZ\":%d,\"siteRoughness\":%.3f,\"flatFrameFraction\":%.4f,"
					+ "\"loadedChunkFraction\":%.4f,\"visibleSections\":%d,"
					+ "\"phases\":[%s]}%n",
				this.backend, architecture, maxHeapMiB, collectors, resolution[0], resolution[1], this.renderDistance,
				this.simulationDistance, this.seed, site.x(), site.groundY(), site.z(), site.roughness(),
				flatFraction, loadedFraction, visibleSections, phases.stream().map(MetalFrameMetrics.Phase::toJson).collect(Collectors.joining(",")));
			Path output = Path.of("benchmarks", "metalcraft-" + this.backend.toLowerCase(Locale.ROOT) + ".json");
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
	 * Resolves the render resolution, defaulting to the primary display's native pixel dimensions.
	 * A benchmark that renders a quarter of the pixels the player's session renders cannot predict
	 * the player's frame rate, and on a Retina display a 1920x1080 target does exactly that.
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
