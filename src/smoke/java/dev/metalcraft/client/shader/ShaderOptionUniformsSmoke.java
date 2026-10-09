package dev.metalcraft.client.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/** CPU-only check of the option buffer ABI shared by all pack pass kinds. */
public final class ShaderOptionUniformsSmoke {
	private ShaderOptionUniformsSmoke() {
	}

	public static void main(final String[] arguments) {
		run();
	}

	public static void run() {
		List<ShaderPack.Option> options = List.of(
			option("enabled", ShaderPack.OptionType.BOOL, false, List.of()),
			option("count", ShaderPack.OptionType.INT, 0, List.of()),
			option("amount", ShaderPack.OptionType.FLOAT, 0.0, List.of()),
			option("mode", ShaderPack.OptionType.ENUM, "first", List.of("first", "second"))
		);
		Map<String, Object> values = Map.of("enabled", true, "count", -7.0, "amount", 1.25, "mode", "second");
		ByteBuffer bytes = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
		for (int index = 0; index < bytes.capacity(); index++) bytes.put(index, (byte)0x7F);
		bytes.position(5).limit(7);
		ShaderOptionUniforms.write(bytes, options, values::get);
		if (bytes.position() != 16 || bytes.limit() != 32 || bytes.order() != ByteOrder.LITTLE_ENDIAN
			|| bytes.getInt(0) != 1 || bytes.getInt(4) != -7 || bytes.getFloat(8) != 1.25F || bytes.getInt(12) != 1) {
			throw new AssertionError("Shader option uniform scalar layout or buffer state changed");
		}
		for (int index = 16; index < bytes.capacity(); index++) {
			if (bytes.get(index) != 0) throw new AssertionError("Shader option uniform tail was not cleared");
		}
		ShaderOptionUniforms.write(bytes, options.subList(0, 1), ignored -> false);
		for (int index = 0; index < bytes.capacity(); index++) {
			if (bytes.get(index) != 0) throw new AssertionError("Reusing the uniform buffer retained a previous option value");
		}
		ShaderOptionUniforms.write(bytes, List.of(), ignored -> { throw new AssertionError("No option was declared"); });
		if (bytes.position() != 0) throw new AssertionError("An empty option buffer must remain at position zero");
		System.out.println("Shader option uniforms: scalar ABI, byte order, reused buffers and zero tails passed");
	}

	private static ShaderPack.Option option(
		final String id,
		final ShaderPack.OptionType type,
		final Object defaultValue,
		final List<Object> values
	) {
		boolean numeric = type == ShaderPack.OptionType.INT || type == ShaderPack.OptionType.FLOAT;
		return new ShaderPack.Option(id, "test", type, defaultValue,
			numeric ? OptionalDouble.of(-10) : OptionalDouble.empty(),
			numeric ? OptionalDouble.of(10) : OptionalDouble.empty(),
			OptionalDouble.empty(), values, ShaderPack.ApplyMode.UNIFORM);
	}
}
