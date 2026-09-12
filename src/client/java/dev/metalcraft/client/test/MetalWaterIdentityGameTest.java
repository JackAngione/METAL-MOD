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
import java.util.concurrent.CompletableFuture;
import dev.metalcraft.client.shader.world.WaterIdentityDebug;
import dev.metalcraft.client.shader.water.WaterRoutingDebug;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.material.FogType;

/** Paired real-mesh captures; visual review is required before closing water routing gates. */
final class MetalWaterIdentityGameTest {
	private static final int RESIZED_WIDTH = 1280;
	private static final int RESIZED_HEIGHT = 720;
	private static final String[] WATER_OPTIONS = {
		"water_enabled", "water_wave_strength", "water_refraction_strength",
		"water_absorption", "water_foam", "water_underwater_distortion",
		"water_reflection_quality"
	};

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
			if (MetalLodTestScope.HORIZON>16) settings.getGameRules().set(GameRules.SPECTATORS_GENERATE_CHUNKS,true,null);
		});
		boolean originalDebug = WaterIdentityDebug.enabled();
		WaterRoutingDebug.Mode originalRoutingDebug = WaterRoutingDebug.mode();
		boolean originalHalfResolution = MetalCraftConfig.halfResolution();
		boolean originalFabulous = this.context.computeOnClient(client -> client.options.improvedTransparency().get());
		WaterIdentityDebug.setEnabled(false);
		WaterRoutingDebug.setMode(WaterRoutingDebug.Mode.BASELINE);
		java.util.Map<String, Object> originalOptions = new java.util.LinkedHashMap<>();
		String originalPack = this.context.computeOnClient(client -> ShaderPackRuntime.active().selectedPackId());
		try (var world = builder.create()) {
			this.context.waitFor(client -> client.level != null && client.player != null);
			world.getServer().runCommand("gamemode spectator @a");
			MetalLodTestScope.prepareHorizon(this.context,world);
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
				for (String id : new String[]{
					"exposure", "tonemap", "invert", "debug_view", "water_detail", "water_detail_distance",
					"water_enabled", "water_wave_strength", "water_refraction_strength",
					"water_absorption", "water_foam", "water_underwater_distortion",
					"water_reflection_quality"
				}) {
					originalOptions.put(id, ShaderPackRuntime.active().optionValue(id));
				}
				ShaderPackRuntime.active().setOption("exposure", 1.0F);
				ShaderPackRuntime.active().setOption("tonemap", "none");
				ShaderPackRuntime.active().setOption("invert", false);
				ShaderPackRuntime.active().setOption("debug_view", "off");
				client.gui.hud.getChat().clearMessages(true);
			});
			// Earlier W3-W6 comparisons must not inherit a user's saved W7 zero/off settings.
			this.setWaterDefaults("baseline");
			this.context.waitTicks(100);
			this.context.runOnClient(client -> {
				if (!MetalLinearWorldActivation.lastLiveUsedHdr()) {
					throw new AssertionError("Live HDR world session was not active before water identity captures");
				}
				if (MetalLinearWorldActivation.lastLiveFabulous()) {
					throw new AssertionError("Ordinary transparency expected before the Fabulous check");
				}
			});
			if (Boolean.getBoolean("metalcraft.underwaterProbe")) {
				this.context.getInput().resizeWindow(RESIZED_WIDTH, RESIZED_HEIGHT);
				this.context.waitFor(client -> client.getWindow().getWidth() == RESIZED_WIDTH
					&& client.getWindow().getHeight() == RESIZED_HEIGHT);
				this.captureW6Comparisons(world);
				return;
			}
			if (Boolean.getBoolean("metalcraft.waterW5Probe")) {
				this.captureW5Comparisons(world);
				return;
			}
			if (Boolean.getBoolean("metalcraft.waterDetailProbe")) {
				this.context.getInput().resizeWindow(RESIZED_WIDTH, RESIZED_HEIGHT);
				this.context.waitFor(client -> client.getWindow().getWidth() == RESIZED_WIDTH
					&& client.getWindow().getHeight() == RESIZED_HEIGHT);
				this.captureW7Comparisons(world);
				return;
			}
			int[] nativeSnapshot = new int[2];
			Path baseline = this.capture(WaterRoutingDebug.Mode.BASELINE, "metalcraft-water-identity-baseline", false);
			Path water = this.capture(WaterRoutingDebug.Mode.IDENTITY, "metalcraft-water-identity-water", false);
			Path restored = this.capture(WaterRoutingDebug.Mode.BASELINE, "metalcraft-water-identity-restored", false);
			assertWaterIdentity(baseline, water, restored);
			Path surface = this.capture(WaterRoutingDebug.Mode.SURFACE_DEPTH, "metalcraft-water-surface-depth", false);
			Path opaque = this.capture(WaterRoutingDebug.Mode.OPAQUE_DEPTH, "metalcraft-water-opaque-depth", false);
			assertDepthViews(surface, opaque, restored);
			this.context.runOnClient(client -> {
				WaterRoutingDebug.setMode(WaterRoutingDebug.Mode.BASELINE);
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
			this.capture(WaterRoutingDebug.Mode.BASELINE, "metalcraft-water-identity-native-restored", false);

			this.context.getInput().resizeWindow(RESIZED_WIDTH, RESIZED_HEIGHT);
			this.context.waitFor(client -> client.getWindow().getWidth() == RESIZED_WIDTH
				&& client.getWindow().getHeight() == RESIZED_HEIGHT);
			this.context.waitTicks(20);
			this.capture(WaterRoutingDebug.Mode.IDENTITY, "metalcraft-water-identity-resized", false);

			world.getServer().runCommand("fill -10 181 2 -8 181 4 minecraft:water");
			this.context.waitTicks(40);
			this.capture(WaterRoutingDebug.Mode.IDENTITY, "metalcraft-water-identity-world-change", false);

			this.captureW4Comparisons(world);
			this.captureW5Comparisons(world);
			this.captureW6Comparisons(world);
			this.captureW7Comparisons(world);
			this.captureNaturalWater(world);

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
				ShaderPackRuntime.active().selectPack(ShaderPackRuntime.BUILTIN_ID);
				originalOptions.forEach((id, value) -> ShaderPackRuntime.active().setOption(id, value));
				ShaderPackRuntime.active().selectPack(originalPack);
				client.levelExtractor.allChanged();
			});
		}
	}

	/** W7 persisted controls, identity behavior, failure recovery, and SSR visual matrix. */
	private void captureW7Comparisons(final TestSingleplayerContext world) {
		world.getServer().runCommand("fill -34 174 142 34 199 159 minecraft:air");
		world.getServer().runCommand("fill -34 174 160 34 199 177 minecraft:air");
		world.getServer().runCommand("fill -34 174 178 34 199 194 minecraft:air");
		world.getServer().runCommand("fill -28 178 148 28 184 188 minecraft:smooth_stone hollow");
		world.getServer().runCommand("fill -27 184 149 27 184 187 minecraft:air");
		world.getServer().runCommand("fill -27 179 149 27 183 187 minecraft:water");
		// Glass is deliberately absent from the opaque snapshot and must miss SSR. Thin opaque
		// bars, broken targets, and edge-adjacent objects exercise hit rejection and fading.
		world.getServer().runCommand("fill -22 185 158 -22 191 178 minecraft:glass_pane");
		world.getServer().runCommand("fill -10 185 162 -10 190 174 minecraft:iron_bars");
		world.getServer().runCommand("fill 22 185 151 22 192 164 minecraft:white_concrete");
		world.getServer().runCommand("fill 8 185 166 14 188 166 minecraft:red_concrete");
		world.getServer().runCommand("time set 6000");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("tp @a 0 193 193 180 27");
		this.context.getInput().lookAt(180, 27);
		this.context.waitTicks(80);
		world.getServer().runCommand("tick freeze");

		this.setWaterDefaults("baseline");
		Path previousDetail = null;
		for (int detail = 0; detail <= 3; detail++) {
			this.setWaterOption("water_detail", detail);
			Path currentDetail = this.capture(WaterRoutingDebug.Mode.OFF,
				"metalcraft-water-detail-" + detail, false);
			if (previousDetail != null) {
				// Fine normal changes are deliberately subtler than the full-effect RGB>80 check.
				int changed = differentSamples(previousDetail, currentDetail, 0.05, 0.95, 0.45, 0.90, 8);
				int sky = differentSamples(previousDetail, currentDetail, 0.05, 0.95, 0.0, 0.15, 8);
				// Higher tiers may intentionally converge at overview distance after footprint filtering.
				if ((detail == 1 && changed < 100) || sky > 10) throw new AssertionError(
					"Detail tier " + detail + " coverage=" + changed + " sky=" + sky);
				System.out.println("Water detail tier " + detail + ": changed=" + changed + " sky=" + sky);
			}
			previousDetail = currentDetail;
		}
		// Camera eye is about 1.75 blocks above the surface, rather than the overview's 10+.
		world.getServer().runCommand("tp @a 0 184 180 180 40");
		this.context.getInput().lookAt(180, 40);
		this.context.waitTicks(20);
		// Pin the client animation clock, not only server daylight, for identical reruns.
		this.context.runOnClient(client -> client.level.setTimeFromServer(340L));
		Path closePrevious = null;
		Path detailNoneNormals = null;
		for (int detail = 0; detail <= 3; detail++) {
			this.setWaterOption("water_detail", detail);
			Path close = this.capture(WaterRoutingDebug.Mode.OFF,
				"metalcraft-water-micro-close-" + detail, false);
			if (closePrevious != null && differentSamples(closePrevious, close, 0.1, 0.9, 0.25, 0.95, 8) < 100) {
				throw new AssertionError("Near-camera water detail tier " + detail + " was not visible");
			}
			if (detail == 0 || detail == 3) {
				Path normals = this.capture(WaterRoutingDebug.Mode.NORMALS,
					"metalcraft-water-detail-route-" + detail, false);
				if (detail == 0) detailNoneNormals = normals;
				else {
					int normalChanges = differentSamples(detailNoneNormals, normals, 0.1, 0.9, 0.25, 0.95, 8);
					if (normalChanges < 1000) throw new AssertionError("Detail did not reach live water normals: " + normalChanges);
					System.out.println("Water detail route: None/High normal changes=" + normalChanges);
				}
			}
			final int expectedDetail = detail;
			this.context.runOnClient(client -> {
				var runtime = ShaderPackRuntime.active();
				if (!runtime.isActive() || !ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId())
					|| ((Number)runtime.optionValue("water_detail")).intValue() != expectedDetail) {
					throw new AssertionError("Live water detail setting or Standard pack was not active");
				}
			});
			closePrevious = close;
		}
		Path closeNormals = this.capture(WaterRoutingDebug.Mode.NORMALS,
			"metalcraft-water-micro-close-normals-t0", false);
		world.getServer().runCommand("tick step 10");
		this.context.waitTicks(15);
		this.context.runOnClient(client -> client.level.setTimeFromServer(350L));
		Path movingNormals = this.capture(WaterRoutingDebug.Mode.NORMALS,
			"metalcraft-water-micro-close-normals-t1", false);
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-micro-close-moving", false);
		if (differentSamples(closeNormals, movingNormals, 0.1, 0.9, 0.25, 0.95, 8) < 1000) {
			throw new AssertionError("Close micro-ripples did not evolve over time");
		}
		this.context.getInput().lookAt(180, 8);
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-micro-grazing", false);
		this.setHalfResolution(true);
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-micro-grazing-half", false);
		this.setHalfResolution(false);
		world.getServer().runCommand("tp @a 0 193 193 180 27");
		this.context.getInput().lookAt(180, 27);
		this.context.waitTicks(20);
		this.setWaterOption("water_detail", 2);
		if (Boolean.getBoolean("metalcraft.waterDetailProbe")) {
			world.getServer().runCommand("tick unfreeze");
			return;
		}
		Path enabled = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w7-enabled", false);
		this.setWaterOption("water_enabled", false);
		Path disabledFirst = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w7-disabled-first", false);
		Path vanillaBaseline = this.capture(WaterRoutingDebug.Mode.BASELINE,
			"metalcraft-water-w7-vanilla-baseline", false);
		assertVisualDifference(vanillaBaseline, enabled, 80,
			"W7 enabled controls did not change the vanilla-compatible baseline");
		assertImagesNear(disabledFirst, vanillaBaseline, 12, 30,
			"W7 water-off did not restore the vanilla-compatible baseline");
		this.setWaterOption("water_enabled", true);
		Path enabledAgain = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w7-enabled-again", false);
		assertVisualDifference(disabledFirst, enabledAgain, 80,
			"W7 repeated enable did not restore water effects");
		this.setWaterOption("water_enabled", false);
		Path disabledAgain = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w7-disabled-again", false);
		// Vanilla water sprites keep animating from render time while the server tick is frozen, so
		// comparing the two disabled captures across an intervening recompile measures sprite drift.
		// Compare the repeated toggle with an adjacent compatibility-baseline capture instead.
		Path vanillaBaselineAgain = this.capture(WaterRoutingDebug.Mode.BASELINE,
			"metalcraft-water-w7-vanilla-baseline-again", false);
		assertImagesNear(disabledAgain, vanillaBaselineAgain, 12, 30,
			"W7 repeated toggle left stale water output");

		this.setWaterOption("water_enabled", true);
		for (String id : new String[]{"water_wave_strength", "water_refraction_strength",
			"water_absorption", "water_foam", "water_underwater_distortion"}) {
			this.setWaterOption(id, 0.0F);
		}
		this.setWaterOption("water_reflection_quality", "off");
		Path zeroStrengths = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w7-zero-strengths", false);
		Path zeroStrengthsRepeat = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w7-zero-strengths-repeat", false);
		assertImagesNear(zeroStrengths, zeroStrengthsRepeat, 12, 40,
			"W7 zero-strength controls did not produce stable defined output");
		assertVisualDifference(enabled, zeroStrengths, 50,
			"W7 zero-strength controls did not disable the configured effects");

		this.setWaterDefaults("ssr_low");
		this.reloadResourcePacks();
		this.assertWaterOptions(1.0F, "ssr_low");
		Path ssrLow = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w7-ssr-low", false);
		this.setWaterOption("water_reflection_quality", "baseline");
		Path reflectionBaseline = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w7-reflection-baseline", false);
		this.setWaterOption("water_reflection_quality", "ssr_high");
		Path ssrHigh = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w7-ssr-high", false);
		assertVisualDifference(reflectionBaseline, ssrLow, 25,
			"W7 SSR low did not differ from baseline reflection");
		assertVisualDifference(ssrLow, ssrHigh, 10,
			"W7 SSR quality tiers produced indistinguishable output");

		world.getServer().runCommand("tp @a 25 186 190 180 8");
		this.context.getInput().lookAt(180, 8);
		this.context.waitTicks(20);
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-w7-ssr-high-edge-offscreen", false);

		world.getServer().runCommand("tp @a 7 193 193 180 27");
		this.context.getInput().lookAt(180, 27);
		this.context.waitTicks(20);
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-w7-ssr-high-camera-moved", false);
		this.setHalfResolution(true);
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-w7-ssr-high-half-resolution", false);
		this.setHalfResolution(false);

		this.context.runOnClient(client -> {
			ShaderPackRuntime runtime = ShaderPackRuntime.active();
			runtime.selectPack("metalcraft-water-w7-missing-pack");
			if (runtime.isActive() || runtime.lastError().isEmpty()) {
				throw new AssertionError("W7 failed pack configuration did not select vanilla fallback");
			}
			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
			if (!runtime.isActive() || runtime.lastError().isPresent()) {
				throw new AssertionError("W7 shader pack did not recover after failed configuration");
			}
		});
		this.context.waitTicks(20);
		this.assertWaterOptions(1.0F, "ssr_high");
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-w7-failure-recovered", false);

		// The coordinator owns this helper and the Gradle property because GPU tests are serialized.
		if (Boolean.getBoolean("metalcraft.waterQualityBenchmark")) {
			new WaterQualityBenchmark(this.context).run();
		}
		world.getServer().runCommand("tick unfreeze");
	}

	/** Validate actual generated water, avoiding the high-contrast tiled fixture bed. */
	private void captureNaturalWater(final TestSingleplayerContext world) {
		int[] selected = new int[4];
		world.getServer().runOnServer(server -> {
			var level = server.overworld();
			int sea = level.getSeaLevel();
			search: for (int x = -224; x <= 224; x += 16) {
				for (int z = -32; z <= 384; z += 16) {
					boolean openWater = true;
					for (int[] offset : new int[][]{{0,0},{-6,0},{6,0},{0,-6},{0,6}}) {
						var pos = new net.minecraft.core.BlockPos(x+offset[0], sea-1, z+offset[1]);
						if (!level.hasChunkAt(pos) || !level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER)
							|| !level.getFluidState(pos.below(3)).is(net.minecraft.tags.FluidTags.WATER)) {
							openWater = false;
							break;
						}
					}
					if (openWater) {
						selected[0]=x; selected[1]=sea; selected[2]=z; selected[3]=1;
						break search;
					}
				}
			}
		});
		if (selected[3] == 0) throw new AssertionError("No generated deep water found in loaded NORMAL terrain");
		world.getServer().runCommand("tick unfreeze");
		world.getServer().runCommand("tp @a " + selected[0] + " " + (selected[1]+0.15) + " " + selected[2] + " 180 35");
		world.getServer().runCommand("time set noon");
		this.context.getInput().lookAt(180,35);
		this.context.waitTicks(80);
		world.getServer().runCommand("tick freeze");
		this.context.runOnClient(client -> client.level.setTimeFromServer(340L));
		this.setWaterDefaults("baseline");
		this.setWaterOption("water_detail",0);
		Path none = this.capture(WaterRoutingDebug.Mode.OFF,"metalcraft-water-natural-none",false);
		this.setWaterOption("water_detail",3);
		Path high = this.capture(WaterRoutingDebug.Mode.OFF,"metalcraft-water-natural-high",false);
		int changes = differentSamples(none,high,0.1,0.9,0.3,0.9,8);
		if (changes < 1000) throw new AssertionError("Generated-water detail remains too weak: " + changes);
		System.out.println("Natural water detail: camera=" + java.util.Arrays.toString(selected) + " changed=" + changes);
		this.context.getInput().lookAt(180,8);
		this.capture(WaterRoutingDebug.Mode.OFF,"metalcraft-water-natural-grazing",false);
		this.setWaterOption("water_detail_distance",2);
		Path shortRange = this.capture(WaterRoutingDebug.Mode.OFF,"metalcraft-water-distance-2",false);
		this.setWaterOption("water_detail_distance",16);
		Path longRange = this.capture(WaterRoutingDebug.Mode.OFF,"metalcraft-water-distance-16",false);
		if (differentSamples(shortRange,longRange,0.1,0.9,0.42,0.65,8) < 1000) {
			throw new AssertionError("16-chunk water detail did not extend visible distant waves");
		}

		// Time samples preserve the same natural-water camera and settings.
		Path previousMotion = null;
		for (int sample = 0; sample < 4; sample++) {
			if (sample > 0) {
				world.getServer().runCommand("tick step 8");
				this.context.waitTicks(12);
			}
			final long animationTick = 340L + sample * 8L;
			this.context.runOnClient(client -> client.level.setTimeFromServer(animationTick));
			Path motion = this.capture(WaterRoutingDebug.Mode.OFF,"metalcraft-water-crossing-" + sample,false);
			if (previousMotion != null && differentSamples(previousMotion,motion,0.1,0.9,0.5,0.95,8) < 1000) {
				throw new AssertionError("Natural ocean motion samples did not advance");
			}
			previousMotion = motion;
		}
		world.getServer().runCommand("time set 11000");
		this.context.getInput().lookAt(90,8);
		this.context.waitTicks(10);
		this.setWaterOption("water_wave_strength",0.0F);
		this.capture(WaterRoutingDebug.Mode.OFF,"metalcraft-water-glints-flat",false);
		this.setWaterOption("water_wave_strength",1.0F);
		this.capture(WaterRoutingDebug.Mode.OFF,"metalcraft-water-glints-waves",false);

		world.getServer().runCommand("tick unfreeze");
	}

	private void setWaterDefaults(final String reflectionQuality) {
		this.setWaterOption("water_detail_distance", 16);
		this.setWaterOption("water_detail", 2);
		this.setWaterOption("water_enabled", true);
		for (String id : new String[]{"water_wave_strength", "water_refraction_strength",
			"water_absorption", "water_foam", "water_underwater_distortion"}) {
			this.setWaterOption(id, 1.0F);
		}
		this.setWaterOption("water_reflection_quality", reflectionQuality);
	}

	private void setWaterOption(final String id, final Object value) {
		this.context.runOnClient(client -> ShaderPackRuntime.active().setOption(id, value));
		this.context.waitTicks(10);
	}

	private void assertWaterOptions(final float strength, final String reflectionQuality) {
		this.context.runOnClient(client -> {
			ShaderPackRuntime runtime = ShaderPackRuntime.active();
			if (!Boolean.TRUE.equals(runtime.optionValue("water_enabled"))) {
				throw new AssertionError("W7 persisted water_enabled value was lost");
			}
			for (String id : WATER_OPTIONS) {
				if (id.equals("water_enabled") || id.equals("water_reflection_quality")) continue;
				if (!(runtime.optionValue(id) instanceof Number value)
					|| Double.compare(value.doubleValue(), strength) != 0) {
					throw new AssertionError("W7 persisted " + id + " value was lost: " + runtime.optionValue(id));
				}
			}
			if (!reflectionQuality.equals(runtime.optionValue("water_reflection_quality"))) {
				throw new AssertionError("W7 persisted reflection quality was lost: "
					+ runtime.optionValue("water_reflection_quality"));
			}
		});
	}

	private void reloadResourcePacks() {
		CompletableFuture<Void> reload = this.context.computeOnClient(client -> client.reloadResourcePacks());
		this.context.waitFor(ignored -> reload.isDone(), 2400);
		if (reload.isCompletedExceptionally()) {
			throw new AssertionError("W7 resource reload failed", reload.handle((ignored, error) -> error).join());
		}
		// The reload future can complete before the Mojang loading overlay finishes its fade-out.
		// Waiting for the actual GUI state keeps SSR comparisons free of non-world pixels.
		this.context.waitFor(client -> client.gui.overlay() == null);
		this.context.waitTicks(2);
	}

	/** W6 shoreline/contact foam and underwater transition matrix in the NORMAL-world fixture. */
	private void captureW6Comparisons(final TestSingleplayerContext world) {
		// Isolate a shallow pool whose bed rises in steps toward the camera. Stone stairs provide
		// sloping contacts, while the dry posts prove that unrelated silhouettes are not outlined.
		world.getServer().runCommand("fill -28 174 92 28 198 132 minecraft:air");
		world.getServer().runCommand("fill -22 179 96 22 184 124 minecraft:stone hollow");
		world.getServer().runCommand("fill -21 184 97 21 184 123 minecraft:air");
		world.getServer().runCommand("fill -21 180 97 21 180 105 minecraft:smooth_stone");
		world.getServer().runCommand("fill -21 181 106 21 181 113 minecraft:smooth_stone");
		world.getServer().runCommand("fill -21 182 114 21 182 123 minecraft:smooth_stone");
		world.getServer().runCommand("fill -21 181 97 21 183 113 minecraft:water");
		world.getServer().runCommand("fill -21 183 114 21 183 123 minecraft:water");
		world.getServer().runCommand("fill -14 183 112 -8 183 112 minecraft:stone_stairs[facing=south,half=bottom]");
		world.getServer().runCommand("fill 8 183 112 14 183 112 minecraft:stone_stairs[facing=south,half=bottom]");
		// Let source water run over a stair lip; the flowing surface exposes the foam shader's
		// strongest 0.1--0.25-block proximity band instead of only full-block-deep contacts.
		world.getServer().runCommand("fill -6 183 114 6 183 118 minecraft:air");
		world.getServer().runCommand("fill -6 183 117 6 183 118 minecraft:water");
		world.getServer().runCommand("fill -2 184 103 2 190 103 minecraft:oak_fence");
		// A sealed side chamber supplies a cave-water and low-light underwater control.
		world.getServer().runCommand("fill 25 178 98 43 191 122 minecraft:deepslate_tiles hollow");
		world.getServer().runCommand("fill 27 179 100 41 184 120 minecraft:water");
		world.getServer().runCommand("fill 27 185 100 41 189 120 minecraft:air");
		world.getServer().runCommand("setblock 34 185 101 minecraft:soul_lantern");
		world.getServer().runCommand("time set 6000");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("tp @a 0 193 130 180 31");
		this.context.getInput().lookAt(180, 31);
		this.context.waitTicks(100);
		world.getServer().runCommand("tick freeze");

		Path foamOff = this.capture(WaterRoutingDebug.Mode.FOAM_OFF,
			"metalcraft-water-w6-contact-foam-off", false);
		Path foamOn = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w6-contact-foam-on", false);
		assertFoamCoverage(foamOff, foamOn);

		// Enter from just above the surface, stop with the eye at the waterline, then submerge.
		world.getServer().runCommand("tp @a 0 182.4 109 180 4");
		this.context.getInput().lookAt(180, 4);
		this.context.waitTicks(20);
		this.assertCameraFog(false, "entry above water");
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-w6-entry-air", false);
		world.getServer().runCommand("tp @a 0 182.25 109 180 1");
		this.context.getInput().lookAt(180, 1);
		this.context.waitTicks(20);
		this.assertCameraFog(true, "partial submersion");
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-w6-partial-submersion", false);
		world.getServer().runCommand("tp @a 0 181.8 109 180 -10");
		this.context.getInput().lookAt(180, -10);
		this.context.waitTicks(20);
		this.assertCameraFog(true, "submerged distortion comparison");
		Path distortionOff = this.capture(WaterRoutingDebug.Mode.UNDERWATER_DISTORTION_OFF,
			"metalcraft-water-w6-underwater-distortion-off", false);
		Path underwaterEntry = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w6-underwater-entry", false);
		if (differentSamples(distortionOff, underwaterEntry, 0.0, 1.0, 12) < 20) {
			throw new AssertionError("W6 underwater distortion did not change the frozen submerged view");
		}
		// Matched near-bed colors with the vanilla underwater path and the clear-water path.
		this.context.getInput().lookAt(180, 35);
		this.context.waitTicks(20);
		this.context.runOnClient(client -> ShaderPackRuntime.active().setOption("water_enabled", false));
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-wu-vanilla-bed", false);
		this.context.runOnClient(client -> ShaderPackRuntime.active().setOption("water_enabled", true));
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-wu-clear-bed", false);
		this.context.getInput().lookAt(180, -75);
		this.context.waitTicks(20);
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-w6-underwater-looking-up", false);
		world.getServer().runCommand("tp @a 0 182.4 109 180 4");
		this.context.getInput().lookAt(180, 4);
		this.context.waitTicks(20);
		this.assertCameraFog(false, "exit above water");
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-w6-exit-air", false);

		world.getServer().runCommand("gamemode creative @a");
		world.getServer().runCommand("item replace entity @a weapon.mainhand with minecraft:prismarine_shard");
		world.getServer().runCommand("tp @a 34 182 110 180 -8");
		this.context.getInput().lookAt(180, -8);
		this.context.waitTicks(60);
		this.assertCameraFog(true, "underwater cave");
		world.getServer().runCommand("title @a times 0 100 0");
		world.getServer().runCommand("title @a title {\"text\":\"UNDERWATER HUD\",\"color\":\"white\"}");
		this.context.waitTicks(2);
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-w6-underwater-cave-hud", false);

		world.getServer().runCommand("tick unfreeze");
		world.getServer().runCommand("gamemode spectator @a");
		world.getServer().runCommand("tp @a 0 193 130 180 31");
		this.context.getInput().lookAt(180, 31);
		this.context.waitTicks(20);
	}

	private void assertCameraFog(final boolean water, final String scene) {
		this.context.runOnClient(client -> {
			FogType actual = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState.fogType;
			if ((actual == FogType.WATER) != water) {
				throw new AssertionError("W6 " + scene + " camera fog expected water=" + water
					+ " actual=" + actual);
			}
		});
	}

	/** W5 uses a deep and shallow pool in the NORMAL-world fixture, with transparent controls. */
	private void captureW5Comparisons(final TestSingleplayerContext world) {
		world.getServer().runCommand("fill -20 174 55 20 195 85 minecraft:air");
		// Five-block-deep pool on the left, one-block-shallow pool on the right.
		world.getServer().runCommand("fill -18 176 58 -2 182 74 minecraft:stone hollow");
		world.getServer().runCommand("fill -17 182 59 -3 182 73 minecraft:air");
		world.getServer().runCommand("fill -17 177 59 -3 181 73 minecraft:water");
		world.getServer().runCommand("fill 2 180 58 18 182 74 minecraft:stone hollow");
		world.getServer().runCommand("fill 3 182 59 17 182 73 minecraft:air");
		world.getServer().runCommand("fill 3 181 59 17 181 73 minecraft:water");
		world.getServer().runCommand("fill -16 176 61 -4 176 63 minecraft:red_concrete");
		world.getServer().runCommand("fill 4 180 61 16 180 63 minecraft:red_concrete");
		// Transparent geometry both below the deep surface and above the shallow surface.
		world.getServer().runCommand("fill -12 178 65 -8 180 65 minecraft:light_blue_stained_glass");
		world.getServer().runCommand("fill 8 183 65 12 185 65 minecraft:light_blue_stained_glass");
		world.getServer().runCommand("time set 6000");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("tp @a 0 194 84 180 34");
		this.context.getInput().lookAt(180, 34);
		this.context.waitTicks(80);
		world.getServer().runCommand("tick freeze");

		Path refractionOff = this.capture(WaterRoutingDebug.Mode.REFRACTION_OFF,
			"metalcraft-water-w5-refraction-off", false);
		Path refracted = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w5-refraction-absorption", false);
		assertVisualDifference(refractionOff, refracted, 80,
			"W5 refraction/absorption did not change the shallow/deep pools");

		world.getServer().runCommand("tp @a 0 183 82 180 6");
		this.context.getInput().lookAt(180, 6);
		this.context.waitTicks(20);
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-w5-steep-border-sky", false);

		world.getServer().runCommand("tp @a -10 180 68 180 -30");
		this.context.getInput().lookAt(180, -30);
		this.context.waitTicks(20);
		this.assertCameraFog(true, "W5 submerged refraction fallback");
		// LocalPlayer's underwater vision still adapts on client ticks while server ticks
		// are frozen. Wait for its final fog distance before comparing shader modes.
		this.context.waitFor(client -> client.player.getWaterVision() == 1.0F, 1200);
		Path underwaterOff = this.capture(WaterRoutingDebug.Mode.REFRACTION_OFF,
			"metalcraft-water-w5-underwater-refraction-off", false);
		Path underwater = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w5-underwater-fallback", false);
		if (differentSamples(underwaterOff, underwater, 0.0, 1.0, 40) > 20) {
			throw new AssertionError("W5 underwater view did not use the explicit W4 fallback");
		}

		world.getServer().runCommand("tp @a 0 194 84 180 34");
		this.context.getInput().lookAt(180, 34);
		this.context.waitTicks(20);
		this.setFabulous(true);
		Path fabulousOff = this.capture(WaterRoutingDebug.Mode.REFRACTION_OFF,
			"metalcraft-water-w5-fabulous-refraction-off", true);
		Path fabulous = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w5-fabulous-fallback", true);
		if (differentSamples(fabulousOff, fabulous, 0.0, 1.0, 12) > 20) {
			throw new AssertionError("W5 Fabulous mode did not use the explicit W4 fallback");
		}
		this.setFabulous(false);
		world.getServer().runCommand("tick unfreeze");
		// Leave the large W5 pools framed for the caller's final Fabulous identity capture.
	}

	/**
	 * Deterministic W4 visual matrix in the same NORMAL world as the routing checks.
	 * Daylight and animation samples use explicit game times while ADVANCE_TIME remains disabled.
	 */
	private void captureW4Comparisons(final TestSingleplayerContext world) {
		world.getServer().runCommand("fill 0 180 20 32 180 44 minecraft:white_concrete");
		world.getServer().runCommand("fill 9 181 24 23 182 38 minecraft:stone hollow");
		world.getServer().runCommand("fill 10 182 25 22 182 37 minecraft:air");
		// This still-water surface crosses the x=16 chunk boundary.
		world.getServer().runCommand("fill 10 181 25 22 181 37 minecraft:water");
		// A raised source/channel produces flowing top faces and a vertical waterfall together.
		world.getServer().runCommand("fill 25 181 25 30 184 35 minecraft:stone");
		world.getServer().runCommand("fill 25 185 25 25 186 35 minecraft:stone");
		world.getServer().runCommand("fill 30 185 25 30 186 35 minecraft:stone");
		world.getServer().runCommand("fill 26 185 25 29 185 25 minecraft:stone");
		world.getServer().runCommand("fill 26 185 26 29 185 26 minecraft:water");
		// Build and light the sealed cave before freezing simulation for paired captures.
		world.getServer().runCommand("fill 38 180 22 58 190 42 minecraft:stone hollow");
		world.getServer().runCommand("fill 40 181 24 56 181 40 minecraft:water");
		world.getServer().runCommand("fill 40 182 24 56 188 40 minecraft:air");
		world.getServer().runCommand("setblock 48 182 25 minecraft:torch");
		world.getServer().runCommand("time set 6000");
		world.getServer().runCommand("weather clear");
		world.getServer().runCommand("tp @a 16 194 52 180 35");
		this.context.getInput().lookAt(180, 35);
		this.context.waitTicks(80);
		world.getServer().runCommand("tick freeze");

		Path noonBaseline = this.capture(WaterRoutingDebug.Mode.BASELINE,
			"metalcraft-water-w4-noon-baseline", false);
		Path noonT0 = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w4-noon-effect-t0", false);
		Path normalsT0 = this.capture(WaterRoutingDebug.Mode.NORMALS,
			"metalcraft-water-w4-noon-normals-t0", false);
		world.getServer().runCommand("tick step 40");
		this.context.waitTicks(45);
		Path noonT1 = this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w4-noon-effect-t1", false);
		Path normalsT1 = this.capture(WaterRoutingDebug.Mode.NORMALS,
			"metalcraft-water-w4-noon-normals-t1", false);
		assertVisualDifference(noonBaseline, noonT0, 50, "W4 noon effect did not differ from baseline");
		assertVisualDifference(noonT0, noonT1, 25, "W4 water normals did not animate between fixed times");
		// Unit-normal perturbations encode to small color changes; depth debug's >80 RGB
		// threshold cannot measure them. Restrict this check to the central still-water pool.
		if (differentSamples(normalsT0, normalsT1, 0.40, 0.59, 12) < 100) {
			throw new AssertionError("W4 normal debug did not animate on the still-water pool");
		}

		// Keep time fixed while translating the camera so world-anchored waves can be compared to blocks.
		world.getServer().runCommand("tp @a 20 194 52 180 35");
		this.context.getInput().lookAt(180, 35);
		this.context.waitTicks(20);
		this.capture(WaterRoutingDebug.Mode.OFF, "metalcraft-water-w4-camera-translated", false);
		this.capture(WaterRoutingDebug.Mode.NORMALS, "metalcraft-water-w4-camera-translated-normals", false);

		world.getServer().runCommand("tp @a 16 183 48 180 7");
		this.context.getInput().lookAt(180, 7);
		this.context.waitTicks(20);
		this.capture(WaterRoutingDebug.Mode.BASELINE,
			"metalcraft-water-w4-grazing-baseline", false);
		this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w4-grazing-effect", false);

		world.getServer().runCommand("time set 18000");
		world.getServer().runCommand("tp @a 16 194 52 180 35");
		this.context.getInput().lookAt(180, 35);
		this.context.waitTicks(20);
		this.capture(WaterRoutingDebug.Mode.BASELINE,
			"metalcraft-water-w4-night-baseline", false);
		this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w4-night-effect", false);

		// Enclosed pool at noon tests occlusion independently of the night gate.
		world.getServer().runCommand("time set 6000");
		world.getServer().runCommand("tp @a 48 184 39 180 18");
		this.context.getInput().lookAt(180, 18);
		this.context.waitTicks(60);
		this.capture(WaterRoutingDebug.Mode.BASELINE,
			"metalcraft-water-w4-cave-baseline", false);
		this.capture(WaterRoutingDebug.Mode.OFF,
			"metalcraft-water-w4-cave-effect", false);
		world.getServer().runCommand("tick unfreeze");
		world.getServer().runCommand("tp @a 0 193 18 180 40");
		this.context.getInput().lookAt(180, 40);
		this.context.waitTicks(20);
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
			if (client.options.renderDistance().get() != 16 || client.options.simulationDistance().get() != 16) {
				throw new AssertionError("Water validation requires 16 render and simulation distance");
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
		return differentSamples(first, second, x0, x1, 80);
	}

	private static int differentSamples(final Path first, final Path second, final double x0, final double x1,
		final int rgbThreshold) {
		return differentSamples(first, second, x0, x1, 0.0, 1.0, rgbThreshold);
	}

	private static int differentSamples(final Path first, final Path second,
		final double x0, final double x1, final double y0, final double y1, final int rgbThreshold) {
		try (var a = NativeImage.read(Files.newInputStream(first));
			 var b = NativeImage.read(Files.newInputStream(second))) {
			if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
				throw new AssertionError("Depth debug captures have mismatched extents");
			}
			int count = 0;
			int left = Math.max(0, (int)(a.getWidth() * x0));
			int right = Math.min(a.getWidth(), (int)(a.getWidth() * x1));
			int top = Math.max(0, (int)(a.getHeight() * y0));
			int bottom = Math.min(a.getHeight(), (int)(a.getHeight() * y1));
			for (int y = top; y < bottom; y += 2) {
				for (int x = left; x < right; x += 2) {
					int pa = a.getPixel(x, y);
					int pb = b.getPixel(x, y);
					int mad = 0;
					for (int shift : new int[]{0, 8, 16}) {
						mad += Math.abs(((pa >>> shift) & 255) - ((pb >>> shift) & 255));
					}
					if (mad > rgbThreshold) count++;
				}
			}
			return count;
		} catch (java.io.IOException error) {
			throw new AssertionError("Could not inspect water depth captures", error);
		}
	}

	private static void assertFoamCoverage(final Path foamOff, final Path foamOn) {
		int left = differentSamples(foamOff, foamOn, 0.05, 0.35, 0.40, 0.85, 12);
		int center = differentSamples(foamOff, foamOn, 0.35, 0.65, 0.40, 0.85, 12);
		int right = differentSamples(foamOff, foamOn, 0.65, 0.95, 0.40, 0.85, 12);
		if (left < 100 || center < 100 || right < 100 || left + center + right < 750) {
			throw new AssertionError("W6 foam did not cover the broad shallow shelf: left=" + left
				+ " center=" + center + " right=" + right);
		}
		int sky = differentSamples(foamOff, foamOn, 0.05, 0.90, 0.0, 0.17, 12);
		int dryFenceTop = differentSamples(foamOff, foamOn, 0.46, 0.54, 0.18, 0.28, 12);
		if (sky > 10 || dryFenceTop > 10) {
			throw new AssertionError("W6 foam leaked onto dry controls: sky=" + sky
				+ " fence=" + dryFenceTop);
		}
		System.out.println("W6 foam coverage: left=" + left + " center=" + center
			+ " right=" + right + " sky=" + sky + " dryFence=" + dryFenceTop);
	}

	private static void assertVisualDifference(final Path first, final Path second,
		final int minimumSamples, final String message) {
		int changed = differentSamples(first, second, 0.0, 1.0);
		if (changed < minimumSamples) {
			throw new AssertionError(message + ": " + changed + " changed samples");
		}
	}

	private static void assertImagesNear(final Path first, final Path second,
		final int rgbThreshold, final int maximumSamples, final String message) {
		int changed = differentSamples(first, second, 0.0, 1.0, rgbThreshold);
		if (changed > maximumSamples) {
			throw new AssertionError(message + ": " + changed + " changed samples");
		}
	}

	private static void assertWaterIdentity(final Path baseline, final Path water, final Path restored) {
		int baselineMagenta = magentaSamples(baseline);
		int waterMagenta = magentaSamples(water);
		int restoredMagenta = magentaSamples(restored);
		if (waterMagenta < 500) {
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
					// The identity color is composited and lit: half-resolution filtering
					// can remove its few >180 highlights while preserving the full mask.
					// Require magenta chroma over a broad area, independent of those peaks.
					if (c0 > c1 + 70 && c2 > c1 + 70 && Math.min(c0, c2) > 120) count++;
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
