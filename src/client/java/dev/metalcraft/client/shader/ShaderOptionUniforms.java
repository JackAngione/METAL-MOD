package dev.metalcraft.client.shader;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Function;

/** Shared pack-option layout for geometry, fullscreen, and compute passes. */
final class ShaderOptionUniforms {
	private ShaderOptionUniforms() {
	}

	static void write(
		final ByteBuffer bytes,
		final List<ShaderPack.Option> options,
		final Function<String, Object> optionValue
	) {
		bytes.clear();
		while (bytes.hasRemaining()) {
			bytes.put((byte)0);
		}
		bytes.rewind();
		for (ShaderPack.Option option : options) {
			Object value = optionValue.apply(option.id());
			switch (option.type()) {
				case BOOL -> bytes.putInt(Boolean.TRUE.equals(value) ? 1 : 0);
				case INT -> bytes.putInt(((Number)value).intValue());
				case FLOAT -> bytes.putFloat(((Number)value).floatValue());
				case ENUM -> bytes.putInt(option.values().indexOf(value));
			}
		}
	}
}
