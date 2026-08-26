package dev.metalcraft.client.shader;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.regex.Pattern;

/** An immutable, validated shader-pack manifest and its MSL sources. */
public record ShaderPack(String id, Manifest manifest, Map<String, String> metalSources) {
	private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
	/** Names a pack may reference but never declare; the engine binds these per frame. */
	private static final Set<String> RESERVED_TARGET_IDS = Set.of("scene", "depth", "drawable");

	public ShaderPack {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("A shader pack requires a non-blank ID");
		}
		Objects.requireNonNull(manifest, "manifest");
		metalSources = immutableMap(metalSources);
		for (Map.Entry<String, String> source : metalSources.entrySet()) {
			if (source.getKey().isBlank() || !source.getKey().endsWith(".metal")) {
				throw new IllegalArgumentException("Invalid Metal source path '" + source.getKey() + "'");
			}
			Objects.requireNonNull(source.getValue(), "Metal source " + source.getKey());
		}
	}

	public record Manifest(
		int format,
		String name,
		Map<String, Target> targets,
		List<Pass> passes,
		List<Option> options
	) {
		public Manifest {
			if (format != 1) {
				throw new IllegalArgumentException("Unsupported shader-pack format " + format + "; expected 1");
			}
			if (name == null || name.isBlank()) {
				throw new IllegalArgumentException("A shader pack requires a non-blank name");
			}
			targets = immutableMap(targets);
			for (Map.Entry<String, Target> target : targets.entrySet()) {
				requireId(target.getKey(), "target");
				if (RESERVED_TARGET_IDS.contains(target.getKey())) {
					throw new IllegalArgumentException("Target ID '" + target.getKey() + "' is reserved for a host-supplied attachment");
				}
				Objects.requireNonNull(target.getValue(), "target " + target.getKey());
			}
			passes = List.copyOf(passes);
			options = List.copyOf(options);
			requireUniqueIds(passes.stream().map(Pass::id).toList(), "pass");
			requireUniqueIds(options.stream().map(Option::id).toList(), "option");
		}
	}

	public enum PixelFormat {
		BGRA8_UNORM(true),
		R8_UNORM(true), R8_SNORM(true), R8_UINT(true), R8_SINT(true),
		RG8_UNORM(true), RG8_SNORM(true), RG8_UINT(true), RG8_SINT(true),
		RGBA8_UNORM(true), RGBA8_SNORM(true), RGBA8_UINT(true), RGBA8_SINT(true),
		R16_UNORM(true), R16_SNORM(true), R16_UINT(true), R16_SINT(true), R16_FLOAT(true),
		RG16_UNORM(true), RG16_SNORM(true), RG16_UINT(true), RG16_SINT(true), RG16_FLOAT(true),
		RGBA16_UNORM(true), RGBA16_SNORM(true), RGBA16_UINT(true), RGBA16_SINT(true), RGBA16_FLOAT(true),
		R32_UINT(true), R32_SINT(true), R32_FLOAT(true),
		RG32_UINT(true), RG32_SINT(true), RG32_FLOAT(true),
		RGBA32_UINT(true), RGBA32_SINT(true), RGBA32_FLOAT(true),
		RGB10A2_UNORM(true), RGB10A2_UINT(true), RG11B10_FLOAT(true),
		DEPTH16_UNORM(false), DEPTH32_FLOAT(false), STENCIL8(false),
		DEPTH24_UNORM_STENCIL8(false), DEPTH32_FLOAT_STENCIL8(false);

		private final boolean color;

		PixelFormat(final boolean color) {
			this.color = color;
		}

		public boolean isColor() {
			return this.color;
		}
	}

	public sealed interface Extent permits Scale, FixedSize {
	}

	public record Scale(double value) implements Extent {
		public Scale {
			if (!Double.isFinite(value) || value <= 0.0) {
				throw new IllegalArgumentException("Target scale must be finite and positive");
			}
		}
	}

	public record FixedSize(int value) implements Extent {
		public FixedSize {
			if (value <= 0) {
				throw new IllegalArgumentException("Target size must be positive");
			}
		}
	}

	public enum Lifetime {
		TRANSIENT,
		FRAME,
		HISTORY
	}

	public record Target(PixelFormat format, Extent extent, Lifetime lifetime, int layers) {
		public Target {
			Objects.requireNonNull(format, "format");
			Objects.requireNonNull(extent, "extent");
			Objects.requireNonNull(lifetime, "lifetime");
			if (layers < 1) {
				throw new IllegalArgumentException("Target layers must be at least one");
			}
		}
	}

	public enum PassKind {
		GEOMETRY,
		FULLSCREEN,
		COMPUTE
	}

	public record Pass(
		String id,
		PassKind kind,
		List<String> geometry,
		List<String> reads,
		List<String> writes,
		List<String> tileReads,
		String mergeWith
	) {
		public Pass {
			requireId(id, "pass");
			Objects.requireNonNull(kind, "kind");
			geometry = immutableIds(geometry, "geometry selector in pass '" + id + "'");
			reads = immutableIds(reads, "read in pass '" + id + "'");
			writes = immutableIds(writes, "write in pass '" + id + "'");
			tileReads = immutableIds(tileReads, "tile read in pass '" + id + "'");
			if (mergeWith != null) {
				requireId(mergeWith, "merge_with pass");
				if (mergeWith.equals(id)) {
					throw new IllegalArgumentException("Pass '" + id + "' cannot merge with itself");
				}
			}
			Set<String> duplicateReads = new HashSet<>(reads);
			duplicateReads.retainAll(tileReads);
			if (!duplicateReads.isEmpty()) {
				throw new IllegalArgumentException(
					"Pass '" + id + "' declares targets as both reads and tile_reads: " + duplicateReads
				);
			}
		}
	}

	public enum OptionType {
		BOOL,
		INT,
		FLOAT,
		ENUM
	}

	public enum ApplyMode {
		UNIFORM,
		RECOMPILE,
		RELOAD
	}

	/** Numeric bounds are present only for int and float options; enum values are immutable scalars. */
	public record Option(
		String id,
		String category,
		OptionType type,
		Object defaultValue,
		OptionalDouble min,
		OptionalDouble max,
		OptionalDouble step,
		List<Object> values,
		ApplyMode apply
	) {
		public Option {
			requireId(id, "option");
			requireId(category, "option category");
			Objects.requireNonNull(type, "type");
			requireScalar(defaultValue, "default for option '" + id + "'");
			Objects.requireNonNull(min, "min");
			Objects.requireNonNull(max, "max");
			Objects.requireNonNull(step, "step");
			values = List.copyOf(values);
			for (Object value : values) {
				requireScalar(value, "value for option '" + id + "'");
			}
			Objects.requireNonNull(apply, "apply");
			validateShape(id, type, defaultValue, min, max, step, values);
		}

		private static void validateShape(
			final String id,
			final OptionType type,
			final Object defaultValue,
			final OptionalDouble min,
			final OptionalDouble max,
			final OptionalDouble step,
			final List<Object> values
		) {
			if (type == OptionType.BOOL) {
				if (!(defaultValue instanceof Boolean) || min.isPresent() || max.isPresent() || step.isPresent() || !values.isEmpty()) {
					throw new IllegalArgumentException("Boolean option '" + id + "' has incompatible fields");
				}
				return;
			}
			if (type == OptionType.ENUM) {
				if (min.isPresent() || max.isPresent() || step.isPresent() || values.isEmpty() || !values.contains(defaultValue)) {
					throw new IllegalArgumentException("Enum option '" + id + "' requires values containing its default");
				}
				if (new HashSet<>(values).size() != values.size()) {
					throw new IllegalArgumentException("Enum option '" + id + "' has duplicate values");
				}
				return;
			}
			if (type == OptionType.INT && !(defaultValue instanceof Integer)
				|| type == OptionType.FLOAT && !(defaultValue instanceof Double)) {
				throw new IllegalArgumentException("Option '" + id + "' has a default of the wrong type");
			}
			if (min.isEmpty() || max.isEmpty() || !values.isEmpty()) {
				throw new IllegalArgumentException("Numeric option '" + id + "' requires min and max and cannot have values");
			}
			double defaultNumber = ((Number)defaultValue).doubleValue();
			if (!Double.isFinite(min.getAsDouble()) || !Double.isFinite(max.getAsDouble())
				|| min.getAsDouble() >= max.getAsDouble()
				|| defaultNumber < min.getAsDouble() || defaultNumber > max.getAsDouble()) {
				throw new IllegalArgumentException("Numeric option '" + id + "' has an invalid range or default");
			}
			if (step.isPresent() && (!Double.isFinite(step.getAsDouble()) || step.getAsDouble() <= 0.0)) {
				throw new IllegalArgumentException("Numeric option '" + id + "' step must be finite and positive");
			}
		}
	}

	static void requireId(final String value, final String kind) {
		if (value == null || !ID.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid " + kind + " ID '" + value + "'");
		}
	}

	private static List<String> immutableIds(final List<String> values, final String kind) {
		List<String> copy = List.copyOf(values);
		for (String value : copy) {
			requireId(value, kind);
		}
		requireUniqueIds(copy, kind);
		return copy;
	}

	private static void requireUniqueIds(final List<String> ids, final String kind) {
		Set<String> seen = new HashSet<>();
		for (String id : ids) {
			if (!seen.add(id)) {
				throw new IllegalArgumentException("Duplicate " + kind + " ID '" + id + "'");
			}
		}
	}

	private static void requireScalar(final Object value, final String description) {
		if (!(value instanceof Boolean || value instanceof Integer || value instanceof Double || value instanceof String)) {
			throw new IllegalArgumentException("Invalid " + description);
		}
		if (value instanceof Double number && !Double.isFinite(number)) {
			throw new IllegalArgumentException("Invalid non-finite " + description);
		}
	}

	private static <K, V> Map<K, V> immutableMap(final Map<K, V> values) {
		Objects.requireNonNull(values, "values");
		return Collections.unmodifiableMap(new LinkedHashMap<>(values));
	}
}
