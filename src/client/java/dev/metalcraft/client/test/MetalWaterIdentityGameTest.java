package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.Window;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.MetalCraftRenderResolution;
import dev.metalcraft.client.metal.MetalLinearWorldActivation;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.mixin.LevelRendererAccessor;
import dev.metalcraft.client.mixin.WindowFramebufferAccessor;
import org.lwjgl.glfw.GLFW;
import dev.metalcraft.client.shader.SceneColor;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import com.mojang.blaze3d.platform.NativeImage;
import java.nio.file.Files;
import java.nio.file.Path;
import dev.metalcraft.client.shader.world.WaterIdentityDebug;
import dev.metalcraft.client.shader.water.WaterRoutingDebug;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.gamerules.GameRules;

/** Paired real-mesh captures; visual review is required before closing water routing gates. */
final class MetalWaterIdentityGameTest {
	private static final int RESIZED_WIDTH = 1280;
	private static final int RESIZED_HEIGHT = 720;

	private final ClientGameTestContext context;

	MetalWaterIdentityGameTest(final ClientGameTestContext context) {
		this.context = context;
	}

	void run() {
		var builder = this.context.worldBuilder().adjustSettings(settings -> {
			// Fabric's consistent settings select FLAT before this callback.
			var normal = settings.getSettings().worldgenLoadContext()
				.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_PRESET)
				.getOrThrow(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
			settings.setWorldType(new WorldCreationUiState.WorldTypeEntry(normal));
			settings.setSeed("12345");
			settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			settings.setAllowCommands(true);
			settings.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
			settings.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
		});
		boolean originalDebug = WaterIdentityDebug.enabled();
		WaterRoutingDebug.Mode originalRoutingDebug = WaterRoutingDebug.mode();
		boolean originalHalfResolution = MetalCraftConfig.halfResolution();
		boolean originalFabulous = this.context.computeOnClient(client -> client.options.improvedTransparency().get());
		WaterIdentityDebug.setEnabled(false);
		WaterRoutingDebug.setMode(WaterRoutingDebug.Mode.OFF);
		java.util.Map<String, Object> originalOptions = new java.util.LinkedHashMap<>();
		String originalPack = this.context.computeOnClient(client -> ShaderPackRuntime.active().selectedPackId());
		try (var world = builder.create()) {
			this.context.waitFor(client -> client.level != null && client.player != null);
			world.getServer().runCommand("gamemode spectator @a");
			world.getServer().runCommand("tp @a 0 193 18 180 40");
			this.context.waitTicks(80);
			world.getServer().runCommand("fill -14 180 -10 14 180 10 minecraft:white_concrete");
			world.getServer().runCommand("fill -12 181 -8 -3 182 0 minecraft:stone hollow");
			world.getServer().runCommand("fill -11 182 -7 -4 182 -1 minecraft:air");
			world.getServer().runCommand("fill -11 181 -7 -4 181 -1 minecraft:water");
			world.getServer().runCommand("setblock -8 185 -4 minecraft:water");
			world.getServer().runCommand("fill 2 181 -8 6 182 -4 minecraft:glass");
			world.getServer().runCommand("fill 8 181 -8 12 182 -4 minecraft:ice");
			world.getServer().runCommand("fill 2 181 0 6 182 4 minecraft:slime_block");
			world.getServer().runCommand("fill 8 181 0 12 182 4 minecraft:stone hollow");
			world.getServer().runCommand("fill 9 182 1 11 182 3 minecraft:lava");
			world.getServer().runCommand("setblock -5 181 4 minecraft:oak_slab[waterlogged=true]");
			world.getServer().runCommand("time set noon");
			world.getServer().runCommand("weather clear");
			this.context.getInput().lookAt(180, 40);
			this.context.runOnClient(client -> {
				ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID);
				for (String id : new String[]{"exposure", "tonemap", "invert", "debug_view"}) {
					originalOptions.put(id, ShaderPackRuntime.active().optionValue(id));
				}
				ShaderPackRuntime.active().setOption("exposure", 1.0F);
				ShaderPackRuntime.active().setOption("tonemap", "none");
				ShaderPackRuntime.active().setOption("invert", false);
				ShaderPackRuntime.active().setOption("debug_view", "off");
				client.gui.hud.getChat().clearMessages(true);
			});
			this.context.waitTicks(100);
			this.context.runOnClient(client -> {
				if (!MetalLinearWorldActivation.lastLiveUsedHdr()) {
					throw new AssertionError("Live HDR world session was not active before water identity captures");
				}
				if (MetalLinearWorldActivation.lastLiveFabulous()) {
					throw new AssertionError("Ordinary transparency expected before the Fabulous check");
				}
			});
			int[] nativeSnapshot = new int[2];
			Path baseline = this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-identity-baseline", false);
			Path water = this.capture(WaterRoutingDebug.Mode.IDENTITY, "metalcraft-water-identity-water", false);
			Path restored = this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-identity-restored", false);
			assertWaterIdentity(baseline, water, restored);
			Path surface = this.capture(WaterRoutingDebug.Mode.SURFACE_DEPTH, "metalcraft-water-surface-depth", false);
			Path opaque = this.capture(WaterRoutingDebug.Mode.OPAQUE_DEPTH, "metalcraft-water-opaque-depth", false);
			assertDepthViews(surface, opaque, restored);
			this.context.runOnClient(client -> {
				WaterRoutingDebug.setMode(WaterRoutingDebug.Mode.OFF);
				client.gui.hud.getChat().clearMessages(true);
			});
			this.context.waitTicks(12);
			Path gradeBaseline = this.context.takeScreenshot("metalcraft-world-grade-baseline");
			this.context.runOnClient(client -> ShaderPackRuntime.active().setOption("exposure", 0.5F));
			world.getServer().runCommand("title @a times 0 200 0");
			world.getServer().runCommand("title @a title {\"text\":\"HUD WHITE\",\"color\":\"white\"}");
			this.context.waitTicks(10);
			this.context.runOnClient(client -> client.gui.hud.getChat().clearMessages(true));
			this.context.waitTicks(2);
			Path graded = this.context.takeScreenshot("metalcraft-world-grade-half-exposure-hud");
			assertWorldOnlyGrade(gradeBaseline, graded);
			this.context.runOnClient(client -> ShaderPackRuntime.active().setOption("exposure", 1.0F));

			this.setHalfResolution(false);
			this.capture(WaterRoutingDebug.Mode.IDENTITY, "metalcraft-water-identity-native", false);
			this.recordSnapshotSize(nativeSnapshot);
			this.setHalfResolution(true);
			Path half = this.capture(WaterRoutingDebug.Mode.IDENTITY, "metalcraft-water-identity-half", false);
			assertWaterIdentity(baseline, half, restored);
			this.assertSnapshotSmallerThan(nativeSnapshot);
			this.setHalfResolution(false);
			this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-identity-native-restored", false);

			this.context.getInput().resizeWindow(RESIZED_WIDTH, RESIZED_HEIGHT);
			this.context.waitFor(client -> client.getWindow().getWidth() == RESIZED_WIDTH
				&& client.getWindow().getHeight() == RESIZED_HEIGHT);
			this.context.waitTicks(20);
			this.capture(WaterRoutingDebug.Mode.IDENTITY, "metalcraft-water-identity-resized", false);

			world.getServer().runCommand("fill -10 181 2 -8 181 4 minecraft:water");
			this.context.waitTicks(40);
			this.capture(WaterRoutingDebug.Mode.IDENTITY, "metalcraft-water-identity-world-change", false);

			this.setFabulous(true);
			Path fabulous = this.capture(WaterRoutingDebug.Mode.IDENTITY, "metalcraft-water-identity-fabulous", true);
			assertWaterIdentity(baseline, fabulous, restored);
			this.context.runOnClient(client -> {
				if (!MetalLinearWorldActivation.lastLiveFabulous()) {
					throw new AssertionError("Fabulous HDR session was not active");
				}
				if (((LevelRendererAccessor)client.levelRenderer).metalcraft$getTransparencyChain() == null) {
					throw new AssertionError("Fabulous transparency chain was missing");
				}
			});
			this.setFabulous(false);
		} finally {
			WaterIdentityDebug.setEnabled(originalDebug);
			WaterRoutingDebug.setMode(originalRoutingDebug);
			MetalCraftConfig.setHalfResolution(originalHalfResolution);
			this.context.runOnClient(client -> {
				client.options.improvedTransparency().set(originalFabulous);
				MetalCraftRenderResolution.apply(client);
				originalOptions.forEach((id, value) -> ShaderPackRuntime.active().setOption(id, value));
				ShaderPackRuntime.active().selectPack(originalPack);
				client.levelExtractor.allChanged();
			});
		}
	}

	private Path capture(final WaterRoutingDebug.Mode mode, final String name, final boolean fabulous) {
		this.context.runOnClient(client -> WaterRoutingDebug.setMode(mode));
		this.context.waitTicks(12);
		this.context.runOnClient(client -> {
			if (!MetalLinearWorldActivation.lastLiveUsedHdr()) {
				throw new AssertionError("Live HDR world session dropped during " + name);
			}
			if (fabulous != MetalLinearWorldActivation.lastLiveFabulous()) {
				throw new AssertionError("Fabulous state for " + name + " expected=" + fabulous
					+ " actual=" + MetalLinearWorldActivation.lastLiveFabulous());
			}
			var device = MetalGpuDevices.current();
			if (device == null || !device.lastWorldHadOpaqueWaterInputs()) {
				throw new AssertionError("Opaque water inputs were not captured in the live HDR world");
			}
			if (device.lastWorldWaterDraws() == 0) {
				throw new AssertionError("Production water metadata did not reach a forward draw");
			}
			if (device.opaqueWaterInputs().isPresent()) {
				throw new AssertionError("Opaque water inputs escaped the world session");
			}
			var view = client.gameRenderer.mainRenderTarget().getColorTextureView();
			if (device.lastWorldOpaqueWaterWidth() != view.getWidth(0)
				|| device.lastWorldOpaqueWaterHeight() != view.getHeight(0)) {
				throw new AssertionError("Opaque snapshot extent " + device.lastWorldOpaqueWaterWidth()
					+ "x" + device.lastWorldOpaqueWaterHeight() + " did not match world attachment "
					+ view.getWidth(0) + "x" + view.getHeight(0));
			}
			client.gui.hud.getChat().clearMessages(true);
		});
		this.context.waitTicks(2);
		Path path = this.context.takeScreenshot(name);
		System.out.println("Water capture: " + path);
		return path;
	}

	private void recordSnapshotSize(final int[] size) {
		this.context.runOnClient(client -> {
			var device = MetalGpuDevices.current();
			size[0] = device.lastWorldOpaqueWaterWidth();
			size[1] = device.lastWorldOpaqueWaterHeight();
		});
		if (size[0] <= 0 || size[1] <= 0) {
			throw new AssertionError("Native opaque snapshot size was not recorded");
		}
	}

	private void assertSnapshotSmallerThan(final int[] nativeSize) {
		this.context.runOnClient(client -> {
			var device = MetalGpuDevices.current();
			if (device.lastWorldOpaqueWaterWidth() >= nativeSize[0]
				|| device.lastWorldOpaqueWaterHeight() >= nativeSize[1]) {
				throw new AssertionError("Half-resolution snapshot "
					+ device.lastWorldOpaqueWaterWidth() + "x" + device.lastWorldOpaqueWaterHeight()
					+ " was not smaller than native " + nativeSize[0] + "x" + nativeSize[1]);
			}
		});
	}

	private void setHalfResolution(final boolean enabled) {
		MetalCraftConfig.setHalfResolution(enabled);
		this.context.runOnClient(client -> {
			Window window = client.getWindow();
			int[] nativeWidth = new int[1];
			int[] nativeHeight = new int[1];
			GLFW.glfwGetFramebufferSize(window.handle(), nativeWidth, nativeHeight);
			int width = enabled ? Math.max(1, nativeWidth[0] / 2) : Math.max(1, nativeWidth[0]);
			int height = enabled ? Math.max(1, nativeHeight[0] / 2) : Math.max(1, nativeHeight[0]);
			WindowFramebufferAccessor framebuffer = (WindowFramebufferAccessor)(Object)window;
			framebuffer.metalcraft$setFramebufferWidth(width);
			framebuffer.metalcraft$setFramebufferHeight(height);
			client.framebufferSizeChanged();
		});
		this.context.waitTicks(20);
	}

	private void setFabulous(final boolean enabled) {
		this.context.runOnClient(client -> {
			client.getGpuWarnlistManager().dismissWarning();
			client.options.improvedTransparency().set(enabled);
		});
		this.context.waitTicks(40);
	}

	private static void assertDepthViews(final Path surface, final Path opaque, final Path restored) {
		int leftSurface = differentSamples(surface, restored, 0.05, 0.40);
		int rightSurface = differentSamples(surface, restored, 0.60, 0.95);
		int leftOpaque = differentSamples(opaque, restored, 0.05, 0.40);
		int rightOpaque = differentSamples(opaque, restored, 0.60, 0.95);
		if (leftSurface < 30) {
			throw new AssertionError("Surface depth debug did not cover water: " + leftSurface);
		}
		if (leftOpaque < 30) {
			throw new AssertionError("Opaque depth debug did not cover water: " + leftOpaque);
		}
		if (rightSurface > Math.max(10, leftSurface / 4)) {
			throw new AssertionError("Surface depth debug leaked onto glass/lava: " + rightSurface);
		}
		if (rightOpaque > Math.max(10, leftOpaque / 4)) {
			throw new AssertionError("Opaque depth debug leaked onto glass/lava: " + rightOpaque);
		}
	}

	private static int differentSamples(final Path first, final Path second, final double x0, final double x1) {
		try (var a = NativeImage.read(Files.newInputStream(first));
			 var b = NativeImage.read(Files.newInputStream(second))) {
			if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
				throw new AssertionError("Depth debug captures have mismatched extents");
			}
			int count = 0;
			int left = Math.max(0, (int)(a.getWidth() * x0));
			int right = Math.min(a.getWidth(), (int)(a.getWidth() * x1));
			for (int y = 0; y < a.getHeight(); y += 2) {
				for (int x = left; x < right; x += 2) {
					int pa = a.getPixel(x, y);
					int pb = b.getPixel(x, y);
					int mad = 0;
					for (int shift : new int[]{0, 8, 16}) {
						mad += Math.abs(((pa >>> shift) & 255) - ((pb >>> shift) & 255));
					}
					if (mad > 80) count++;
				}
			}
			return count;
		} catch (java.io.IOException error) {
			throw new AssertionError("Could not inspect water depth captures", error);
		}
	}

	private static void assertWaterIdentity(final Path baseline, final Path water, final Path restored) {
		int baselineMagenta = magentaSamples(baseline);
		int waterMagenta = magentaSamples(water);
		int restoredMagenta = magentaSamples(restored);
		if (waterMagenta < 50) {
			throw new AssertionError("Water identity diagnostic did not cover water: " + waterMagenta);
		}
		if (baselineMagenta > 10 || restoredMagenta > 10) {
			throw new AssertionError("Water identity leaked into baseline/restored: "
				+ baselineMagenta + "/" + restoredMagenta);
		}
	}

	private static int magentaSamples(final Path path) {
		try (var image = NativeImage.read(Files.newInputStream(path))) {
			int count = 0;
			for (int y = 0; y < image.getHeight(); y += 2) {
				for (int x = 0; x < image.getWidth(); x += 2) {
					int pixel = image.getPixel(x, y);
					int c0 = pixel & 255;
					int c1 = (pixel >>> 8) & 255;
					int c2 = (pixel >>> 16) & 255;
					int max = Math.max(c0, Math.max(c1, c2));
					int min = Math.min(c0, Math.min(c1, c2));
					int mid = c0 + c1 + c2 - max - min;
					if (max > 180 && mid > 150 && min < 100) count++;
				}
			}
			return count;
		} catch (java.io.IOException error) {
			throw new AssertionError("Could not inspect water identity capture", error);
		}
	}

	private static void assertWorldOnlyGrade(final Path baseline, final Path graded) {
		try (var original = NativeImage.read(Files.newInputStream(baseline));
			 var result = NativeImage.read(Files.newInputStream(graded))) {
			// Linear HDR exposure multiplies decoded scene RGB then encodes once.
			int before = original.getPixel(20, 20), after = result.getPixel(20, 20);
			boolean linear = true;
			for (int shift : new int[]{0, 8, 16}) {
				int start = (before >>> shift) & 255;
				int end = (after >>> shift) & 255;
				if (Math.abs(end - linearHalf(start)) > 3) linear = false;
			}
			if (!linear) {
				throw new AssertionError("World exposure did not apply linear HDR grade once: "
					+ Integer.toHexString(before) + " -> " + Integer.toHexString(after));
			}
			int white = 0;
			for (int y = result.getHeight() / 3; y < result.getHeight() * 2 / 3; y++) {
				for (int x = result.getWidth() / 4; x < result.getWidth() * 3 / 4; x++) {
					int pixel = result.getPixel(x, y);
					if ((pixel & 255) > 240 && ((pixel >>> 8) & 255) > 240 && ((pixel >>> 16) & 255) > 240) white++;
				}
			}
			if (white < 100) throw new AssertionError("HUD white title was graded or missing");
		} catch (java.io.IOException error) {
			throw new AssertionError("Could not inspect world grade captures", error);
		}
	}

	/** Matches Standard shared/color.metal sRGB transfer at 8-bit endpoints. */
	private static int linearHalf(final int encoded) {
		return Math.round(SceneColor.linearToSrgb(SceneColor.srgbToLinear(encoded / 255.0F) * 0.5F) * 255.0F);
	}
}
