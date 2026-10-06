package dev.metalcraft.client.metal;

import dev.metalcraft.client.shader.ShaderPack;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Existing installations keep their look; every grading control survives a fresh runtime. */
final class ColorGradingPersistenceSmoke {

	static void run() {
		try {
			runPersistence();
		} catch (IOException error) {
			throw new AssertionError("Color grading settings fixture failed", error);
		}
	}

	private static void runPersistence() throws IOException {
		var root = Files.createTempDirectory("metalcraft-color-grading-");
		var settings = root.resolve("settings.json");
		Map<String, Object> expected = new LinkedHashMap<>();
		expected.put("exposure", 1.35);
		expected.put("tonemap", "reinhard");
		expected.put("temperature", 0.35);
		expected.put("tint", -0.2);
		expected.put("contrast", 1.15);
		expected.put("saturation", 0.8);
		expected.put("vibrance", 0.4);
		expected.put("gamma", 1.2);
		expected.put("highlights", -0.25);
		expected.put("shadows", 0.3);
		expected.put("film_grain", 1);
		expected.put("bloom", 2);
		expected.put("depth_of_field", 3);
		try (var device = MetalNative.openDefaultDevice().orElseThrow()) {
			// A settings file written before the grading controls existed.
			Files.writeString(settings, """
				{"selectedPack":"metalcraft-standard","packs":{"metalcraft-standard":{
				"exposure":1.25,"tonemap":"aces","shadow_strength":1.5}}}
				""");
			try (var runtime = new ShaderPackRuntime(device, root.resolve("packs"), settings)) {
				check(runtime.isActive(), "Legacy settings must load Standard");
				equal(1.25, runtime.optionValue("exposure"), "legacy exposure");
				equal("aces", runtime.optionValue("tonemap"), "legacy tone mapping");
				for (var option : runtime.options()) {
					if (!expected.containsKey(option.id())) continue;
					check(option.apply() == ShaderPack.ApplyMode.UNIFORM, option.id() + " must update live");
					check("tonemap".equals(option.category()), option.id() + " must be in Color Grading");
					if (!option.id().equals("exposure") && !option.id().equals("tonemap")) {
						equal(option.defaultValue(), runtime.optionValue(option.id()), option.id() + " migration");
					}
				}
				expected.forEach(runtime::setOption);
			}
			try (var restarted = new ShaderPackRuntime(device, root.resolve("packs"), settings)) {
				check(restarted.isActive(), "Saved Standard pack must restart");
				expected.forEach((id, value) -> equal(value, restarted.optionValue(id), id + " persistence"));
				for (String id : new String[] {"film_grain", "bloom", "depth_of_field"}) {
					for (Object invalid : new Object[] {-1, 4, 1.5, Double.NaN, "high"}) {
						try {
							restarted.setOption(id, invalid);
							throw new AssertionError("Accepted invalid " + id + ": " + invalid);
						} catch (IllegalArgumentException expectedError) {
							equal(expected.get(id), restarted.optionValue(id), "rejected tier preserves " + id);
						}
					}
				}
				for (Object invalid : new Object[] {Double.NaN, Double.POSITIVE_INFINITY, -1.01, 1.01, "warm"}) {
					try {
						restarted.setOption("temperature", invalid);
						throw new AssertionError("Accepted invalid temperature " + invalid);
					} catch (IllegalArgumentException expectedError) {
						equal(0.35, restarted.optionValue("temperature"), "rejected temperature preserves value");
						check(restarted.isActive(), "Rejected value must preserve active rendering");
					}
				}
				for (var option : restarted.options()) {
					if ("tonemap".equals(option.category())) restarted.setOption(option.id(), option.defaultValue());
				}
				equal(1.5, restarted.optionValue("shadow_strength"), "grading reset preserves lighting");
			}
			try (var reset = new ShaderPackRuntime(device, root.resolve("packs"), settings)) {
				for (var option : reset.options()) {
					if ("tonemap".equals(option.category())) {
						equal(option.defaultValue(), reset.optionValue(option.id()), option.id() + " reset persistence");
					}
				}
				equal(1.5, reset.optionValue("shadow_strength"), "reset persists unrelated lighting");
			}
		} finally {
			try (var files = Files.walk(root)) {
				for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
			}
		}
		System.out.println("Color grading persistence: legacy migration, fresh-runtime restore, invalid values and scoped reset passed");
	}

	private static void equal(Object expected, Object actual, String label) {
		boolean same = expected instanceof Number a && actual instanceof Number b
			? Math.abs(a.doubleValue() - b.doubleValue()) < 1.0e-6 : expected.equals(actual);
		check(same, label + ": expected " + expected + ", got " + actual);
	}

	private static void check(boolean condition, String label) {
		if (!condition) throw new AssertionError(label);
	}
}
