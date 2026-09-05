package dev.metalcraft.client.test;

import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.world.WaterIdentityDebug;
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
			settings.setSeed("12345");
			settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
			settings.setAllowCommands(true);
			settings.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
			settings.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
		});
		boolean originalDebug = WaterIdentityDebug.enabled();
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
				client.gui.hud.getChat().clearMessages(true);
			});
			this.context.waitTicks(100);
			this.capture(false, "baseline");
			this.capture(true, "water");
			this.capture(false, "restored");
		} finally {
			WaterIdentityDebug.setEnabled(originalDebug);
			this.context.runOnClient(client -> {
				ShaderPackRuntime.active().selectPack(originalPack);
				client.levelExtractor.allChanged();
			});
		}
	}

	private void capture(final boolean enabled, final String name) {
		this.context.runOnClient(client -> {
			WaterIdentityDebug.setEnabled(enabled);
			client.levelExtractor.allChanged();
		});
		this.context.waitTicks(100);
		this.context.runOnClient(client -> client.gui.hud.getChat().clearMessages(true));
		this.context.waitTicks(2);
		System.out.println("Water identity capture: " + this.context.takeScreenshot("metalcraft-water-identity-" + name));
	}
}
