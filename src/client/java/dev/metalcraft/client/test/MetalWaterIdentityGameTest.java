package dev.metalcraft.client.test;

import dev.metalcraft.client.metal.MetalLinearWorldActivation;
import dev.metalcraft.client.metal.MetalGpuDevices;
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

/** Paired real-mesh captures; visual review is required before closing the W1 gate. */
final class MetalWaterIdentityGameTest {
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
		boolean originalRoutingDebug = WaterRoutingDebug.enabled();
		WaterIdentityDebug.setEnabled(false);
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
			});
			Path baseline = this.capture(false, "baseline");
			Path water = this.capture(true, "water");
			Path restored = this.capture(false, "restored");
			assertWaterIdentity(baseline, water, restored);
			this.context.runOnClient(client -> ShaderPackRuntime.active().setOption("exposure", 0.5F));
			world.getServer().runCommand("title @a times 0 200 0");
			world.getServer().runCommand("title @a title {\"text\":\"HUD WHITE\",\"color\":\"white\"}");
			this.context.waitTicks(10);
			Path graded = this.context.takeScreenshot("metalcraft-world-grade-half-exposure-hud");
			assertWorldOnlyGrade(restored, graded);
		} finally {
			WaterIdentityDebug.setEnabled(originalDebug);
			WaterRoutingDebug.setEnabled(originalRoutingDebug);
			this.context.runOnClient(client -> {
				originalOptions.forEach((id, value) -> ShaderPackRuntime.active().setOption(id, value));
				ShaderPackRuntime.active().selectPack(originalPack);
				client.levelExtractor.allChanged();
			});
		}
	}

	private Path capture(final boolean enabled, final String name) {
		boolean[] rebuilt = {false};
		this.context.runOnClient(client -> {
			if (WaterRoutingDebug.enabled() != enabled) {
				WaterRoutingDebug.setEnabled(enabled);
				client.levelExtractor.allChanged();
				rebuilt[0] = true;
			}
		});
		this.context.waitTicks(rebuilt[0] ? 100 : 40);
		this.context.runOnClient(client -> {
			if (!MetalLinearWorldActivation.lastLiveUsedHdr()) {
				throw new AssertionError("Live HDR world session dropped after terrain rebuild");
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
			client.gui.hud.getChat().clearMessages(true);
		});
		this.context.waitTicks(2);
		Path path = this.context.takeScreenshot("metalcraft-water-identity-" + name);
		System.out.println("Water identity capture: " + path);
		return path;
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
