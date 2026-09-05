package dev.metalcraft.client.shader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.Reader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/** Strict format-1/2 parsing kept behind {@link ShaderPackLoader}. */
final class ShaderManifestParser {
	private static final Set<String> ROOT_FIELDS_V1 = Set.of("format", "name", "targets", "passes", "options");
	private static final Set<String> ROOT_FIELDS_V2 = Set.of(
		"format", "name", "targets", "passes", "options", "includes", "presets"
	);
	private static final Set<String> PASS_FIELDS_V1 = Set.of(
		"id", "kind", "geometry", "reads", "writes", "tile_reads", "merge_with", "enabled_by", "buffers"
	);
	private static final Set<String> PASS_FIELDS_V2 = Set.of(
		"id", "kind", "source", "geometry", "reads", "writes", "tile_reads", "merge_with", "enabled_by", "buffers"
	);

	private ShaderManifestParser() {
	}

	static ShaderPack.Manifest parse(final String packId, final Reader reader) throws ShaderPackLoader.LoadException {
		try {
			JsonElement document = JsonParser.parseReader(reader);
			JsonObject root = object(document, "pack.json");
			int format = integer(required(root, "format", "pack.json"), "pack.json.format");
			if (format != 1 && format != 2) {
				throw failure(packId, "Unsupported format " + format + "; expected format 1 or 2");
			}
			requireFields(root, "pack.json", format == 2 ? ROOT_FIELDS_V2 : ROOT_FIELDS_V1);
			String name = string(required(root, "name", "pack.json"), "pack.json.name");
			List<String> includes = root.has("includes")
				? stringArray(root.get("includes"), "pack.json.includes")
				: List.of();
			Map<String, ShaderPack.Target> targets = parseTargets(
				object(required(root, "targets", "pack.json"), "pack.json.targets")
			);
			List<ShaderPack.Pass> passes = parsePasses(
				array(required(root, "passes", "pack.json"), "pack.json.passes"),
				format
			);
			List<ShaderPack.Option> options = root.has("options")
				? parseOptions(array(root.get("options"), "pack.json.options"))
				: List.of();
			Map<String, Map<String, Object>> presets = root.has("presets")
				? parsePresets(object(root.get("presets"), "pack.json.presets"))
				: Map.of();
			if (passes.isEmpty()) {
				throw failure(packId, "pack.json.passes must contain at least one pass");
			}
			return new ShaderPack.Manifest(format, name, includes, targets, passes, options, presets);
		} catch (ShaderPackLoader.LoadException error) {
			throw error;
		} catch (JsonParseException | IllegalArgumentException | NullPointerException error) {
			throw failure(packId, error.getMessage() == null ? "Malformed pack.json" : error.getMessage(), error);
		}
	}

	private static Map<String, ShaderPack.Target> parseTargets(final JsonObject object) {
		Map<String, ShaderPack.Target> targets = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> field : object.entrySet()) {
			String id = field.getKey();
			ShaderPack.requireId(id, "target");
			JsonObject target = object(field.getValue(), "target '" + id + "'");
			requireFields(target, "target '" + id + "'", Set.of("format", "scale", "size", "lifetime", "layers"));
			boolean hasScale = target.has("scale");
			boolean hasSize = target.has("size");
			if (hasScale == hasSize) {
				throw new IllegalArgumentException("Target '" + id + "' requires exactly one of scale or size");
			}

			String formatName = string(required(target, "format", "target '" + id + "'"), "target '" + id + "'.format");
			ShaderPack.PixelFormat format;
			try {
				format = ShaderPack.PixelFormat.valueOf(formatName.toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException error) {
				throw new IllegalArgumentException("Target '" + id + "' has unsupported format '" + formatName + "'");
			}
			ShaderPack.Extent extent = hasScale
				? new ShaderPack.Scale(number(target.get("scale"), "target '" + id + "'.scale"))
				: new ShaderPack.FixedSize(integer(target.get("size"), "target '" + id + "'.size"));
			ShaderPack.Lifetime lifetime = target.has("lifetime")
				? enumValue(target.get("lifetime"), ShaderPack.Lifetime.class, "target '" + id + "'.lifetime")
				: ShaderPack.Lifetime.FRAME;
			int layers = target.has("layers") ? integer(target.get("layers"), "target '" + id + "'.layers") : 1;
			targets.put(id, new ShaderPack.Target(format, extent, lifetime, layers));
		}
		return targets;
	}

	private static List<ShaderPack.Pass> parsePasses(final JsonArray array, final int format) {
		List<ShaderPack.Pass> passes = new ArrayList<>(array.size());
		for (int index = 0; index < array.size(); index++) {
			String path = "pass[" + index + "]";
			JsonObject pass = object(array.get(index), path);
			requireFields(pass, path, format == 2 ? PASS_FIELDS_V2 : PASS_FIELDS_V1);
			String id = string(required(pass, "id", path), path + ".id");
			ShaderPack.PassKind kind = enumValue(required(pass, "kind", path), ShaderPack.PassKind.class, path + ".kind");
			String source = pass.has("source") ? string(pass.get("source"), path + ".source") : null;
			List<String> geometry = pass.has("geometry") ? geometry(pass.get("geometry"), path + ".geometry") : List.of();
			List<String> reads = pass.has("reads") ? stringArray(pass.get("reads"), path + ".reads") : List.of();
			List<String> writes = pass.has("writes") ? stringArray(pass.get("writes"), path + ".writes") : List.of();
			List<String> tileReads = pass.has("tile_reads")
				? stringArray(pass.get("tile_reads"), path + ".tile_reads")
				: List.of();
			String mergeWith = pass.has("merge_with") ? string(pass.get("merge_with"), path + ".merge_with") : null;
			String enabledBy = pass.has("enabled_by") ? string(pass.get("enabled_by"), path + ".enabled_by") : null;
			List<String> buffers = pass.has("buffers") ? stringArray(pass.get("buffers"), path + ".buffers") : List.of();
			if (writes.isEmpty()) {
				throw new IllegalArgumentException("Pass '" + id + "' must write at least one target");
			}
			if (kind != ShaderPack.PassKind.GEOMETRY && kind != ShaderPack.PassKind.SHADOW && !geometry.isEmpty()) {
				throw new IllegalArgumentException("Only a geometry or shadow pass may declare geometry: pass '" + id + "'");
			}
			if (kind == ShaderPack.PassKind.COMPUTE && (!tileReads.isEmpty() || mergeWith != null)) {
				throw new IllegalArgumentException("Compute pass '" + id + "' cannot tile-read or merge");
			}
			passes.add(new ShaderPack.Pass(id, kind, source, geometry, reads, writes, tileReads, mergeWith, enabledBy, buffers));
		}
		return passes;
	}

	private static Map<String, Map<String, Object>> parsePresets(final JsonObject object) {
		Map<String, Map<String, Object>> presets = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> field : object.entrySet()) {
			JsonObject values = object(field.getValue(), "preset '" + field.getKey() + "'");
			Map<String, Object> optionValues = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> option : values.entrySet()) {
				optionValues.put(option.getKey(), scalar(option.getValue(), "preset '" + field.getKey() + "'." + option.getKey()));
			}
			presets.put(field.getKey(), optionValues);
		}
		return presets;
	}

	private static List<ShaderPack.Option> parseOptions(final JsonArray array) {
		List<ShaderPack.Option> options = new ArrayList<>(array.size());
		for (int index = 0; index < array.size(); index++) {
			String path = "option[" + index + "]";
			JsonObject option = object(array.get(index), path);
			requireFields(option, path, Set.of("id", "category", "type", "default", "min", "max", "step", "values", "apply"));
			String id = string(required(option, "id", path), path + ".id");
			String category = string(required(option, "category", path), path + ".category");
			ShaderPack.OptionType type = enumValue(required(option, "type", path), ShaderPack.OptionType.class, path + ".type");
			ShaderPack.ApplyMode apply = enumValue(required(option, "apply", path), ShaderPack.ApplyMode.class, path + ".apply");
			JsonElement defaultElement = required(option, "default", path);
			Object defaultValue = switch (type) {
				case BOOL -> bool(defaultElement, path + ".default");
				case INT -> integer(defaultElement, path + ".default");
				case FLOAT -> number(defaultElement, path + ".default");
				case ENUM -> scalar(defaultElement, path + ".default");
			};
			OptionalDouble min = option.has("min")
				? OptionalDouble.of(numericBound(type, option.get("min"), path + ".min"))
				: OptionalDouble.empty();
			OptionalDouble max = option.has("max")
				? OptionalDouble.of(numericBound(type, option.get("max"), path + ".max"))
				: OptionalDouble.empty();
			OptionalDouble step = option.has("step")
				? OptionalDouble.of(numericBound(type, option.get("step"), path + ".step"))
				: OptionalDouble.empty();
			List<Object> values = option.has("values") ? scalarArray(option.get("values"), path + ".values") : List.of();
			options.add(new ShaderPack.Option(id, category, type, defaultValue, min, max, step, values, apply));
		}
		return options;
	}

	private static double numericBound(final ShaderPack.OptionType type, final JsonElement value, final String path) {
		return switch (type) {
			case INT -> integer(value, path);
			case FLOAT -> number(value, path);
			default -> throw new IllegalArgumentException("Only numeric options may declare " + path.substring(path.lastIndexOf('.') + 1));
		};
	}

	private static List<String> geometry(final JsonElement element, final String path) {
		String declaration = string(element, path);
		if (declaration.isBlank()) {
			throw new IllegalArgumentException(path + " cannot be blank");
		}
		List<String> values = new ArrayList<>();
		for (String value : declaration.split(",", -1)) {
			String trimmed = value.trim();
			if (trimmed.isEmpty()) {
				throw new IllegalArgumentException(path + " contains an empty selector");
			}
			values.add(trimmed);
		}
		return values;
	}

	private static List<String> stringArray(final JsonElement element, final String path) {
		JsonArray array = array(element, path);
		List<String> values = new ArrayList<>(array.size());
		for (int index = 0; index < array.size(); index++) {
			values.add(string(array.get(index), path + "[" + index + "]"));
		}
		return values;
	}

	private static List<Object> scalarArray(final JsonElement element, final String path) {
		JsonArray array = array(element, path);
		List<Object> values = new ArrayList<>(array.size());
		for (int index = 0; index < array.size(); index++) {
			values.add(scalar(array.get(index), path + "[" + index + "]"));
		}
		return values;
	}

	private static Object scalar(final JsonElement element, final String path) {
		JsonPrimitive primitive = primitive(element, path);
		if (primitive.isBoolean()) {
			return primitive.getAsBoolean();
		}
		if (primitive.isString()) {
			return primitive.getAsString();
		}
		if (primitive.isNumber()) {
			BigDecimal value = primitive.getAsBigDecimal();
			try {
				return value.intValueExact();
			} catch (ArithmeticException ignored) {
				double number = value.doubleValue();
				if (!Double.isFinite(number)) {
					throw new IllegalArgumentException(path + " must be a finite JSON scalar");
				}
				return number;
			}
		}
		throw new IllegalArgumentException(path + " must be a JSON scalar");
	}

	private static <E extends Enum<E>> E enumValue(final JsonElement element, final Class<E> type, final String path) {
		String value = string(element, path);
		try {
			return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException error) {
			throw new IllegalArgumentException(path + " has unsupported value '" + value + "'");
		}
	}

	private static boolean bool(final JsonElement element, final String path) {
		JsonPrimitive primitive = primitive(element, path);
		if (!primitive.isBoolean()) {
			throw new IllegalArgumentException(path + " must be a boolean");
		}
		return primitive.getAsBoolean();
	}

	private static int integer(final JsonElement element, final String path) {
		JsonPrimitive primitive = primitive(element, path);
		if (!primitive.isNumber()) {
			throw new IllegalArgumentException(path + " must be an integer");
		}
		try {
			return primitive.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException error) {
			throw new IllegalArgumentException(path + " must be a 32-bit integer");
		}
	}

	private static double number(final JsonElement element, final String path) {
		JsonPrimitive primitive = primitive(element, path);
		if (!primitive.isNumber()) {
			throw new IllegalArgumentException(path + " must be a number");
		}
		double value = primitive.getAsDouble();
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(path + " must be finite");
		}
		return value;
	}

	private static String string(final JsonElement element, final String path) {
		JsonPrimitive primitive = primitive(element, path);
		if (!primitive.isString()) {
			throw new IllegalArgumentException(path + " must be a string");
		}
		String value = primitive.getAsString();
		if (value.isBlank()) {
			throw new IllegalArgumentException(path + " cannot be blank");
		}
		return value;
	}

	private static JsonPrimitive primitive(final JsonElement element, final String path) {
		if (element == null || !element.isJsonPrimitive()) {
			throw new IllegalArgumentException(path + " must be a JSON primitive");
		}
		return element.getAsJsonPrimitive();
	}

	private static JsonObject object(final JsonElement element, final String path) {
		if (element == null || !element.isJsonObject()) {
			throw new IllegalArgumentException(path + " must be an object");
		}
		return element.getAsJsonObject();
	}

	private static JsonArray array(final JsonElement element, final String path) {
		if (element == null || !element.isJsonArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		return element.getAsJsonArray();
	}

	private static JsonElement required(final JsonObject object, final String name, final String path) {
		if (!object.has(name) || object.get(name).isJsonNull()) {
			throw new IllegalArgumentException(path + " is missing required field '" + name + "'");
		}
		return object.get(name);
	}

	private static void requireFields(final JsonObject object, final String path, final Set<String> allowed) {
		for (String name : object.keySet()) {
			if (!allowed.contains(name)) {
				throw new IllegalArgumentException(path + " has unknown field '" + name + "'");
			}
		}
	}

	private static ShaderPackLoader.LoadException failure(final String packId, final String message) {
		return new ShaderPackLoader.LoadException("Shader pack '" + packId + "': " + message);
	}

	private static ShaderPackLoader.LoadException failure(
		final String packId,
		final String message,
		final Throwable cause
	) {
		return new ShaderPackLoader.LoadException("Shader pack '" + packId + "': " + message, cause);
	}
}
