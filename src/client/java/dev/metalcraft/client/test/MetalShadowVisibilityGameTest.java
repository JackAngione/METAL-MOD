package dev.metalcraft.client.test;

import com.mojang.blaze3d.platform.NativeImage;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.gamerules.GameRules;

/** Visible-shadow regression through real chunk meshes, raster uniforms and world resolve. */
final class MetalShadowVisibilityGameTest {
	private final ClientGameTestContext context;

	MetalShadowVisibilityGameTest(ClientGameTestContext context) {
		this.context = context;
	}

	void run() {
		var builder = this.context.worldBuilder().adjustSettings(settings -> {
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
		String original = this.context.computeOnClient(client -> ShaderPackRuntime.active().selectedPackId());
		Object originalDebug = this.context.computeOnClient(client -> ShaderPackRuntime.active().optionValue("debug_view"));
		try (var world = builder.create()) {
			this.context.waitFor(client -> client.level != null && client.player != null);
			world.getServer().runCommand("gamemode spectator @a");
			world.getServer().runCommand("tp @a 12 192 16 143 40");
			this.context.waitTicks(80);
			world.getServer().runCommand("fill -20 180 -20 20 180 20 minecraft:white_concrete");
			world.getServer().runCommand("fill -1 181 -1 1 188 1 minecraft:stone");
			world.getServer().runCommand("time set 2000");
			world.getServer().runCommand("weather clear");
			this.context.getInput().lookAt(143, 40);
			this.context.runOnClient(client -> {
				ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID);
			});
			this.context.waitTicks(100);
			this.context.runOnClient(client -> client.gui.hud.getChat().clearMessages(true));
			Path vanilla = this.capture("vanilla");
			Path lit = this.capture("off");
			this.capture("visibility");
			this.capture("cascade");
			this.capture("receiver");
			try (NativeImage a = NativeImage.read(Files.newInputStream(vanilla));
				 NativeImage b = NativeImage.read(Files.newInputStream(lit))) {
				long darkened = 0;
				long cleanGroundDarkened = 0;
				for (int y = a.getHeight() / 5; y < a.getHeight() * 4 / 5; y++) {
					for (int x = a.getWidth() / 5; x < a.getWidth() * 4 / 5; x++) {
						int before = a.getPixel(x, y), after = b.getPixel(x, y);
						if ((before & 255) - (after & 255) > 20
							&& ((before >>> 8) & 255) - ((after >>> 8) & 255) > 20
							&& ((before >>> 16) & 255) - ((after >>> 16) & 255) > 20) {
							darkened++;
							if (x > a.getWidth() * 0.60 && x < a.getWidth() * 0.80
								&& y > a.getHeight() * 0.30 && y < a.getHeight() * 0.45) cleanGroundDarkened++;
						}
					}
				}
				System.out.println("Visible terrain shadow pixels: " + darkened);
				if (cleanGroundDarkened > 20) throw new AssertionError("Unoccluded flat ground self-shadows: " + cleanGroundDarkened);
				if (darkened < 100) throw new AssertionError("No visible terrain shadow: " + darkened + " darkened pixels");
			} catch (java.io.IOException error) {
				throw new AssertionError(error);
			}
		} finally {
			this.context.runOnClient(client -> {
				ShaderPackRuntime.active().setOption("debug_view", originalDebug);
				ShaderPackRuntime.active().selectPack(original);
			});
		}
	}

	private Path capture(String debug) {
		this.context.runOnClient(client -> ShaderPackRuntime.active().setOption("debug_view", debug));
		this.context.waitTicks(10);
		return this.context.takeScreenshot("metalcraft-shadow-" + debug);
	}
}
