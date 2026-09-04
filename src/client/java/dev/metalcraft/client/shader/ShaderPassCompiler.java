package dev.metalcraft.client.shader;

import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalTexture;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Builds the MSL compile unit and fullscreen PSO for one pack pass. */
final class ShaderPassCompiler {
	private ShaderPassCompiler() {
	}

	static String source(
		final ShaderPack pack,
		final ShaderPack.Pass pass,
		final Map<String, Object> optionValues
	) throws ShaderPackLoader.LoadException {
		Objects.requireNonNull(pack, "pack");
		Objects.requireNonNull(pass, "pass");
		Objects.requireNonNull(optionValues, "optionValues");
		StringBuilder preamble = new StringBuilder();
		preamble.append("#define MC_PASS_").append(symbol(pass.id())).append(" 1\n");
		int textureSlot = 0;
		for (String read : pass.reads()) {
			preamble.append("#define MC_TEX_").append(symbol(read)).append(' ').append(textureSlot++).append('\n');
		}
		int colorIndex = 0;
		for (String write : pass.writes()) {
			preamble.append("#define MC_TARGET_").append(symbol(write)).append(' ').append(colorIndex++).append('\n');
		}
		for (ShaderPack.Option option : pack.manifest().options()) {
			if (option.apply() != ShaderPack.ApplyMode.RECOMPILE) {
				continue;
			}
			Object value = optionValues.getOrDefault(option.id(), option.defaultValue());
			preamble.append("#define MC_OPTION_").append(symbol(option.id())).append(' ')
				.append(compileConstant(option, value)).append('\n');
		}
		return preamble + ShaderPackLoader.expandPassSource(pack, pass);
	}

	static MetalRenderPipeline compileFullscreen(
		final MetalDevice device,
		final ShaderPack pack,
		final ShaderPack.Pass pass,
		final Map<String, Object> optionValues,
		final MetalTexture.Format colorFormat
	) throws ShaderPackLoader.LoadException {
		String compiled = source(pack, pass, optionValues);
		String passId = pass.id();
		return device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
			compiled,
			passId + "_vertex",
			compiled,
			passId + "_fragment",
			List.of(MetalRenderPipeline.ColorTarget.opaque(colorFormat)),
			null,
			MetalRenderPipeline.VertexDescriptor.EMPTY,
			MetalRenderPipeline.DepthState.DISABLED,
			MetalRenderPipeline.RasterState.DEFAULT
		));
	}

	static String symbol(final String id) {
		return id.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
	}

	static String compileConstant(final ShaderPack.Option option, final Object value) {
		return switch (option.type()) {
			case BOOL -> Boolean.TRUE.equals(value) ? "1" : "0";
			case INT -> Integer.toString(((Number)value).intValue());
			case FLOAT -> Double.toString(((Number)value).doubleValue());
			case ENUM -> Integer.toString(option.values().indexOf(value));
		};
	}
}
