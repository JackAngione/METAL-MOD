package dev.metalcraft.client.test;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.metal.MetalLinearWorldActivation;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Interleaved warmed full-frame measurements of the water fixture's current camera. */
final class WaterQualityBenchmark {
	private final ClientGameTestContext context;

	WaterQualityBenchmark(final ClientGameTestContext context) {
		this.context = context;
	}

	void run() {
		boolean unlocked = MetalCraftConfig.unlockedFrameRate();
		Object enabled = this.context.computeOnClient(client -> ShaderPackRuntime.active().optionValue("water_enabled"));
		Object reflection = this.context.computeOnClient(client -> ShaderPackRuntime.active().optionValue("water_reflection_quality"));
		List<MetalFrameMetrics.Phase> phases = new ArrayList<>();
		Map<String, Object> report = new LinkedHashMap<>();
		boolean capturing = false;
		try {
			this.context.runOnClient(client -> {
				MetalCraftConfig.setUnlockedFrameRate(true);
				report.put("width", client.getWindow().getWidth());
				report.put("height", client.getWindow().getHeight());
				var device = MetalGpuDevices.current();
				if (device == null || !device.lastWorldHadOpaqueWaterInputs()) {
					throw new AssertionError("Water benchmark requires valid Metal opaque snapshots");
				}
				report.put("worldWidth", device.lastWorldOpaqueWaterWidth());
				report.put("worldHeight", device.lastWorldOpaqueWaterHeight());
				report.put("device", device.metal().name());
				report.put("halfResolution", MetalCraftConfig.halfResolution());
				report.put("opaqueSnapshotBytes", (long)device.lastWorldOpaqueWaterWidth() * device.lastWorldOpaqueWaterHeight() * 12);
				report.put("ssrAdditionalTextureBytes", 0);
				report.put("memoryNote", "Existing RGBA16_FLOAT+D32 snapshots remain allocated for every tier including water_off; SSR allocates no texture/history.");
				report.put("renderDistance", client.options.renderDistance().get());
				report.put("camera", client.player.position().toString());
				report.put("yaw", client.player.getYRot());
				report.put("pitch", client.player.getXRot());
			});
			String[] tiers = {"water_off", "off", "baseline", "ssr_low", "ssr_high"};
			for (int repeat = 0; repeat < 3; repeat++) {
				// Rotate order to distribute drift across tiers instead of always measuring high last.
				for (int index = 0; index < tiers.length; index++) {
					String tier = tiers[(index + repeat) % tiers.length];
					this.context.runOnClient(client -> {
						ShaderPackRuntime.active().setOption("water_enabled", !tier.equals("water_off"));
						ShaderPackRuntime.active().setOption("water_reflection_quality",
							tier.equals("water_off") ? "baseline" : tier);
					});
					this.context.waitTicks(40);
					this.context.runOnClient(client -> {
						if (!MetalLinearWorldActivation.lastLiveUsedHdr()) {
							throw new AssertionError("Water benchmark lost the HDR route");
						}
						MetalFrameMetrics.beginCapture(8);
					});
					capturing = true;
					this.context.waitTicks(100);
					String name = tier + "#" + repeat;
					var phase = this.context.computeOnClient(client -> MetalFrameMetrics.endCapture(name));
					capturing = false;
					if (phase.frames() < 120) throw new AssertionError("Too few water benchmark frames: " + phase.frames());
					phases.add(phase);
					LogUtils.getLogger().info("Water quality benchmark: {}", phase.toLogLine());
					for (String line : phase.toAttributionLines()) {
						LogUtils.getLogger().info("Water quality attribution: {}", line);
					}
				}
			}
			report.put("phases", phases);
			report.put("method", "NORMAL seed 12345 fixture; three interleaved repeats; 40 warmup ticks, 100 capture ticks; unlocked presentation. Pass spans must not be summed as GPU frame time.");
			Files.writeString(Path.of("water-w7-quality-results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
		} catch (java.io.IOException error) {
			throw new AssertionError("Could not save water benchmark results", error);
		} finally {
			if (capturing) this.context.runOnClient(client -> MetalFrameMetrics.endCapture("cleanup"));
			this.context.runOnClient(client -> {
				try {
					ShaderPackRuntime.active().setOption("water_enabled", enabled);
					ShaderPackRuntime.active().setOption("water_reflection_quality", reflection);
				} finally {
					MetalCraftConfig.setUnlockedFrameRate(unlocked);
				}
			});
		}
	}
}
