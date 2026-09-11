package dev.metalcraft.client.metal;

import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fresh runtime instances must read the same water settings from disk after shutdown. */
final class WaterOptionsPersistenceSmoke {
	private WaterOptionsPersistenceSmoke() { }

	static void run() {
		try {
			runPersistence();
		} catch (java.io.IOException error) {
			throw new AssertionError("Water settings fixture failed", error);
		}
	}

	private static void runPersistence() throws java.io.IOException {
		var root = Files.createTempDirectory("metalcraft-water-options-");
		var settings = root.resolve("settings.json");
		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("water_enabled", false);
		expected.put("water_detail", 3);
		expected.put("water_detail_distance", 24);
		expected.put("water_wave_strength", 0.2);
		expected.put("water_refraction_strength", 0.3);
		expected.put("water_absorption", 0.4);
		expected.put("water_foam", 0.5);
		expected.put("water_underwater_distortion", 0.6);
		expected.put("water_reflection_quality", "ssr_low");
		try (var device = MetalNative.openDefaultDevice().orElseThrow()) {
			try (var runtime = new ShaderPackRuntime(device, root.resolve("packs"), settings)) {
				runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
				if (((Number)runtime.optionValue("water_detail_distance")).intValue() != 16) {
					throw new AssertionError("Water detail distance must default to 16 chunks");
				}
				expected.forEach(runtime::setOption);
			}
			try (var restarted = new ShaderPackRuntime(device, root.resolve("packs"), settings)) {
				if (!restarted.isActive()) throw new AssertionError("Saved Standard pack did not restart");
				for (var entry : expected.entrySet()) {
					Object actual = restarted.optionValue(entry.getKey());
					boolean equal = actual instanceof Number a && entry.getValue() instanceof Number b
						? Math.abs(a.doubleValue() - b.doubleValue()) < 1.0e-6 : entry.getValue().equals(actual);
					if (!equal) throw new AssertionError("Restart lost " + entry.getKey() + ": " + actual);
				}
				try {
					restarted.setOption("water_reflection_quality", "invalid-tier");
					throw new AssertionError("Invalid reflection tier was accepted");
				} catch (IllegalArgumentException expectedError) {
					if (!restarted.isActive() || !"ssr_low".equals(restarted.optionValue("water_reflection_quality"))) {
						throw new AssertionError("Rejected setting damaged the active pack");
					}
				}
			}
			Files.writeString(settings, "{\"selectedPack\":\"metalcraft-standard\",\"packs\":{\"metalcraft-standard\":{\"water_reflection_quality\":\"invalid-tier\",\"water_enabled\":true}}}");
			try (var recovered = new ShaderPackRuntime(device, root.resolve("packs"), settings)) {
				if (!recovered.isActive() || !"baseline".equals(recovered.optionValue("water_reflection_quality"))) {
					throw new AssertionError("Invalid persisted tier did not recover to baseline");
				}
				recovered.selectPack("missing-water-test-pack");
				if (recovered.isActive() || recovered.lastError().isEmpty()) {
					throw new AssertionError("Missing pack did not enter defined fallback");
				}
				recovered.selectPack(ShaderPackRuntime.BUILTIN_ID);
				if (!recovered.isActive() || recovered.lastError().isPresent()) {
					throw new AssertionError("Standard did not recover after failed pack selection");
				}
			}
		}
		System.out.println("Water options: fresh-runtime disk restore, rejected option, invalid persisted tier and failed-pack recovery passed");
	}
}
