package dev.metalcraft.client.test;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.TextureFilteringMethod;
import org.slf4j.Logger;

/** End-to-end validation of the direct Metal world and window lifecycle. */
public final class MetalLifecycleGameTest implements FabricClientGameTest {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int RESIZED_WIDTH = 1280;
	private static final int RESIZED_HEIGHT = 720;
	private static final int BENCHMARK_WIDTH = 1920;
	private static final int BENCHMARK_HEIGHT = 1080;
	private static final int POST_RELOAD_SETTLE_TICKS = 100;
	private static final int PERFORMANCE_SAMPLE_TICKS = 60;
	private static final int MINIMUM_ACCEPTABLE_FPS = 30;
	private static final int MINIMUM_BENCHMARK_FPS = 100;
	private static final int MINIMUM_BENCHMARK_ONE_PERCENT_LOW = 60;
	private static final double MAXIMUM_BENCHMARK_P99_CPU_FRAME_TIME_MS = 5.0;

	@Override
	public void runTest(final ClientGameTestContext context) {
		String expectedBackend = System.getProperty("metalcraft.lifecycleExpectedBackend", "Metal");
		boolean benchmark = Boolean.getBoolean("metalcraft.lifecycleBenchmark");
		String backend = context.computeOnClient(ignored -> RenderSystem.getDevice().getDeviceInfo().backendName());
		if (!expectedBackend.equals(backend)) {
			throw new AssertionError("Lifecycle test selected unexpected backend: " + backend + " (expected " + expectedBackend + ")");
		}
		LOGGER.info("Metal lifecycle validation: {} backend selected", backend);

		var worldBuilder = context.worldBuilder();
		if (benchmark) {
			worldBuilder.setUseConsistentSettings(false).adjustSettings(settings -> {
				settings.setSeed("1");
				settings.setGenerateStructures(false);
			});
		}
		try (TestSingleplayerContext world = worldBuilder.create()) {
			context.waitFor(client -> client.level != null && client.player != null);
			context.waitTicks(10);
			context.getInput().lookAt(0.0F, 30.0F);
			context.waitTicks(5);
			LOGGER.info("Metal lifecycle validation: singleplayer world loaded");

			if (benchmark) {
				context.getInput().resizeWindow(BENCHMARK_WIDTH, BENCHMARK_HEIGHT);
				context.waitFor(client -> client.getWindow().getWidth() == BENCHMARK_WIDTH
					&& client.getWindow().getHeight() == BENCHMARK_HEIGHT);
				context.runOnClient(client -> {
					client.options.renderDistance().set(21);
					client.options.simulationDistance().set(12);
					client.options.cloudRange().set(128);
					client.options.mipmapLevels().set(4);
					client.options.maxAnisotropyBit().set(2);
					client.options.textureFiltering().set(TextureFilteringMethod.ANISOTROPIC);
					client.options.enableVsync().set(false);
					client.options.framerateLimit().set(net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF);
					client.invalidateSurfaceConfiguration();
				});
				CompletableFuture<Void> benchmarkReload = context.computeOnClient(client -> client.reloadResourcePacks());
				context.waitFor(ignored -> benchmarkReload.isDone(), 2400);
				if (benchmarkReload.isCompletedExceptionally()) {
					throw new AssertionError("Metal benchmark resource reload failed",
						benchmarkReload.handle((ignored, error) -> error).join());
				}
				context.waitFor(client -> client.gui.overlay() == null);
				world.getServer().runCommand("gamemode spectator @a");
				world.getServer().runCommand("tp @a 170 150 170 0 30");
				context.waitTicks(300);
				LOGGER.info("Metal lifecycle benchmark: elevated normal seed-1 world settled at 21-chunk render distance and {}x{} window",
					BENCHMARK_WIDTH, BENCHMARK_HEIGHT);
			} else {
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
			}

			String screenshotName = "metalcraft-world-" + backend.toLowerCase(java.util.Locale.ROOT)
				+ (benchmark ? "-benchmark" : "");
			Path screenshot = context.takeScreenshot(screenshotName);
			if (!Files.isRegularFile(screenshot) || fileSize(screenshot) == 0L) {
				throw new AssertionError("Metal lifecycle screenshot was not written: " + screenshot);
			}
			assertScreenshotVaries(screenshot);
			if (benchmark) assertNoDistantColorInversion(screenshot);
			LOGGER.info("Metal lifecycle validation: screenshot written to {}", screenshot.toAbsolutePath());

			context.runOnClient(ignored -> MetalFrameMetrics.beginCapture());
			context.waitTicks(PERFORMANCE_SAMPLE_TICKS);
			MetalFrameMetrics.Snapshot metrics = context.computeOnClient(ignored -> MetalFrameMetrics.endCapture());
			long[] cpuTimes = metrics.cpuFrameTimesNs();
			long[] intervals = metrics.frameIntervalsNs();
			if (cpuTimes.length < 120 || cpuTimes.length != intervals.length) {
				throw new AssertionError("Lifecycle benchmark captured too few complete render frames: " + cpuTimes.length);
			}
			Arrays.sort(cpuTimes);
			Arrays.sort(intervals);
			long totalFrameTimeNs = 0L;
			for (long interval : intervals) totalFrameTimeNs = Math.addExact(totalFrameTimeNs, interval);
			long averageFps = Math.round(cpuTimes.length * 1_000_000_000.0 / totalFrameTimeNs);
			long onePercentLow = Math.round(1_000_000_000.0 / percentile(intervals, 0.99));
			double p50CpuMs = percentile(cpuTimes, 0.50) / 1_000_000.0;
			double p95CpuMs = percentile(cpuTimes, 0.95) / 1_000_000.0;
			double p99CpuMs = percentile(cpuTimes, 0.99) / 1_000_000.0;
			double p99IntervalMs = percentile(intervals, 0.99) / 1_000_000.0;
			LOGGER.info(
				"Metal lifecycle performance: backend={} frames={} averageFps={} onePercentLow={} "
					+ "p50CpuMs={} p95CpuMs={} p99CpuMs={} p99FrameIntervalMs={}",
				backend, cpuTimes.length, averageFps, onePercentLow, format(p50CpuMs), format(p95CpuMs),
				format(p99CpuMs), format(p99IntervalMs)
			);
			int minimumFps = benchmark ? MINIMUM_BENCHMARK_FPS : MINIMUM_ACCEPTABLE_FPS;
			if (averageFps < minimumFps) {
				throw new AssertionError("Lifecycle performance is below " + minimumFps
					+ " FPS: backend=" + backend + " averageFps=" + averageFps
					+ " onePercentLow=" + onePercentLow + " p99CpuMs=" + p99CpuMs);
			}
			if (benchmark && onePercentLow < MINIMUM_BENCHMARK_ONE_PERCENT_LOW) {
				throw new AssertionError("Metal 1% low is below " + MINIMUM_BENCHMARK_ONE_PERCENT_LOW
					+ " FPS: averageFps=" + averageFps + " onePercentLow=" + onePercentLow);
			}
			if (benchmark && p99CpuMs > MAXIMUM_BENCHMARK_P99_CPU_FRAME_TIME_MS) {
				throw new AssertionError("Metal p99 CPU frame time exceeds " + MAXIMUM_BENCHMARK_P99_CPU_FRAME_TIME_MS
					+ " ms: averageFps=" + averageFps + " p99CpuMs=" + p99CpuMs);
			}
		}

		LOGGER.info("Metal lifecycle validation: world closed; clean client shutdown requested");
	}

	private static long fileSize(final Path path) {
		try {
			return Files.size(path);
		} catch (Exception error) {
			throw new AssertionError("Could not inspect Metal lifecycle screenshot " + path, error);
		}
	}

	private static long percentile(final long[] sortedValues, final double quantile) {
		int index = Math.max(0, (int)Math.ceil(quantile * sortedValues.length) - 1);
		return sortedValues[index];
	}

	private static String format(final double value) {
		return String.format(java.util.Locale.ROOT, "%.3f", value);
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

	private static void assertNoDistantColorInversion(final Path path) {
		try (NativeImage image = NativeImage.read(Files.newInputStream(path))) {
			long inspected = 0L;
			long magenta = 0L;
			long coastalBlue = 0L;
			long coastalPixels = 0L;
			int startY = image.getHeight() / 5;
			int endY = image.getHeight() * 9 / 10;
			for (int y = startY; y < endY; y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					int pixel = image.getPixel(x, y);
					int red = pixel >>> 16 & 0xFF;
					int green = pixel >>> 8 & 0xFF;
					int blue = pixel & 0xFF;
					if (red > 51 && blue > 51 && red * 2 > green * 3 && blue * 5 > green * 6) magenta++;
					if (x >= image.getWidth() * 44 / 100 && x < image.getWidth() * 57 / 100
						&& y >= image.getHeight() * 69 / 100 && y < image.getHeight() * 93 / 100) {
						coastalBlue += blue;
						coastalPixels++;
					}
					inspected++;
				}
			}
			double ratio = (double)magenta / inspected;
			double coastalBlueMean = (double)coastalBlue / coastalPixels / 255.0;
			if (ratio > 0.01 || coastalBlueMean < 0.4) {
				throw new AssertionError("Distant terrain contains Metal color-inversion artifacts: magentaRatio=" + ratio
					+ " coastalBlueMean=" + coastalBlueMean);
			}
		} catch (AssertionError error) {
			throw error;
		} catch (Exception error) {
			throw new AssertionError("Could not validate distant terrain colors in " + path, error);
		}
	}
}
