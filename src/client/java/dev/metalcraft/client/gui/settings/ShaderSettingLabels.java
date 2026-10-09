package dev.metalcraft.client.gui.settings;

import dev.metalcraft.client.shader.ShaderPack;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.util.Locale;
import net.minecraft.network.chat.Component;

final class ShaderSettingLabels {
    private ShaderSettingLabels() {}
	static Component categoryLabel(final String category) {
		return switch (category) {
			case "tonemap" -> Component.translatable(isStandardPack(ShaderPackRuntime.active())
				? "metalcraft.options.shader_category.color_grading" : "metalcraft.options.shader_category.tonemap");
			case "shadows" -> Component.translatable("metalcraft.options.shader_category.shadows");
			case "water" -> Component.translatable("metalcraft.options.shader_category.water");
			case "wind" -> Component.translatable("metalcraft.options.shader_category.wind");
			case "debug" -> Component.translatable("metalcraft.options.shader_category.debug");
			default -> {
				yield Component.literal(humanize(category));
			}
		};
	}

	static boolean isStandardWindOption(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		return ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId()) && option.category().equals("wind");
	}

	static Component optionMessage(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		if (isStandardGradingOption(runtime, option)) {
			return Component.translatable("metalcraft.grading." + option.id(), gradingValue(option.id(), runtime.optionValue(option.id())));
		}
		if (isStandardWindOption(runtime, option)) {
			Object value = runtime.optionValue(option.id());
			Component label = value instanceof Number number
				? Component.literal(Math.round(number.doubleValue() * 100) + "%")
				: Component.translatable("metalcraft.options.value." + value);
			return Component.translatable("metalcraft.wind." + option.id(), label);
		}
		if (isStandardWaterOption(runtime, option)) {
			Object value = runtime.optionValue(option.id());
			Component label = option.id().equals("water_detail")
				? Component.translatable("metalcraft.water.detail." + ((Number)value).intValue())
				: option.id().equals("water_detail_distance")
				? Component.literal(Integer.toString(((Number)value).intValue()))
				: value instanceof Number number
				? Component.literal(Math.round(number.doubleValue() * 100) + "%")
				: Component.translatable("metalcraft.water.value." + value);
			return Component.translatable("metalcraft.water." + option.id(), label);
		}
		Object value = runtime.optionValue(option.id());
		if (isShadowDistanceOption(runtime, option)) {
			return Component.translatable("metalcraft.shadows." + option.id(), ((Number)value).intValue());
		}
		Component valueLabel = value instanceof Boolean bool
			? Component.translatable("metalcraft.options.value." + bool)
			: value instanceof String string ? Component.literal(humanize(string)) : Component.literal(value.toString());
		return Component.literal(humanize(option.id()) + ": ").append(valueLabel);
	}

	static String humanize(final String identifier) {
		String label = identifier.replace('_', ' ').replace('-', ' ');
		return label.isEmpty() ? identifier : Character.toUpperCase(label.charAt(0)) + label.substring(1);
	}

	static boolean isStandardPack(final ShaderPackRuntime runtime) {
		return runtime != null && ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId());
	}

	static boolean isStandardGradingOption(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		return isStandardPack(runtime) && "tonemap".equals(option.category()) && switch (option.id()) {
			case "exposure", "tonemap", "temperature", "tint", "contrast", "saturation", "vibrance", "gamma", "highlights", "shadows",
				"film_grain", "bloom", "depth_of_field" -> true;
			default -> false;
		};
	}

	static String gradingGroup(final String id) {
		return switch (id) {
			case "exposure", "tonemap" -> "tone";
			case "temperature", "tint" -> "balance";
			case "contrast", "saturation", "vibrance" -> "color";
			case "film_grain", "bloom", "depth_of_field" -> "effects";
			default -> "light";
		};
	}

	static Component gradingValue(final String id, final Object value) {
		if (id.equals("tonemap")) return Component.translatable("metalcraft.grading.tonemap." + value);
		if (id.equals("film_grain") || id.equals("bloom") || id.equals("depth_of_field")) {
			return Component.translatable("metalcraft.grading.strength." + ((Number)value).intValue());
		}
		double numeric = ((Number)value).doubleValue();
		if (id.equals("exposure") || id.equals("gamma")) {
			return Component.literal(String.format(Locale.ROOT, "%.2f", numeric) + (id.equals("exposure") ? "×" : ""));
		}
		long percent = Math.round(numeric * 100);
		if (id.equals("temperature") || id.equals("tint")) {
			if (percent == 0) return Component.translatable("metalcraft.grading.neutral");
			String direction = id.equals("temperature") ? (percent < 0 ? "cool" : "warm") : (percent < 0 ? "green" : "magenta");
			return Component.translatable("metalcraft.grading.direction." + direction, Math.abs(percent));
		}
		boolean signed = id.equals("vibrance") || id.equals("highlights") || id.equals("shadows");
		return Component.literal((signed && percent > 0 ? "+" : "") + percent + "%");
	}

	static boolean isStandardWaterOption(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		return ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId()) && option.id().startsWith("water_");
	}

	static boolean isShadowDistanceOption(final ShaderPackRuntime runtime, final ShaderPack.Option option) {
		return ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId())
			&& (option.id().equals("shadow_distance") || option.id().equals("distant_shadow_distance"));
	}

    static Component description(ShaderPackRuntime runtime, ShaderPack.Option option) {
        String prefix = isStandardGradingOption(runtime, option) ? "metalcraft.grading."
            : isStandardWindOption(runtime, option) ? "metalcraft.wind."
            : isStandardWaterOption(runtime, option) ? "metalcraft.water."
            : isShadowDistanceOption(runtime, option) ? "metalcraft.shadows." : null;
        return prefix == null ? Component.translatable("metalcraft.options.shader_option.tooltip",
            option.category(), option.apply().name().toLowerCase(Locale.ROOT))
            : Component.translatable(prefix + option.id() + ".tooltip");
    }
}
