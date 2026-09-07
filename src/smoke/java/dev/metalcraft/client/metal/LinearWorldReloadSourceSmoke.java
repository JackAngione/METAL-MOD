package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Reload ShaderSource selection on MetalGpuDevice: beginLinearWorld uses the installed source,
 * identity-equal installs no-op, replacement poisons, and null restores the constructor default.
 */
final class LinearWorldReloadSourceSmoke {
	private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;

	private LinearWorldReloadSourceSmoke() { }

	static void run() {
		ShaderSource defaultSource = LinearWorldReloadSourceSmoke::vanilla;
		RecordingSource sourceA = new RecordingSource(defaultSource);
		RecordingSource sourceB = new RecordingSource(defaultSource);
		var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), defaultSource);
		try {
			var runtime = gpu.shaderPackRuntime();
			if (runtime == null) throw new AssertionError("Missing smoke shader runtime");
			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
			runtime.setOption("exposure", 1.0F);
			runtime.setOption("tonemap", "none");
			runtime.setOption("invert", false);
			runtime.setOption("debug_view", "off");

			var pipeline = RenderPipelines.TRANSLUCENT_PARTICLE;
			try (var color = gpu.createTexture("reload-color", USAGE, GpuFormat.RGBA8_UNORM, 4, 4, 1, 1);
				 var depth = gpu.createTexture("reload-depth", USAGE, GpuFormat.D32_FLOAT, 4, 4, 1, 1);
				 var colorView = gpu.createTextureView(color);
				 var depthView = gpu.createTextureView(depth)) {
				gpu.setReloadShaderSource(sourceA);
				if (gpu.reloadShaderSource() != sourceA) {
					throw new AssertionError("Reload ShaderSource was not installed");
				}

				MetalLinearWorldSession session = gpu.beginLinearWorld(
					colorView, depthView, false, List.of(pipeline), null);
				if (session == null) throw new AssertionError("Linear world session was not created");
				try {
					if (session.token().shaderSource() != sourceA) {
						throw new AssertionError("beginLinearWorld did not capture the reload ShaderSource");
					}
					if (!sourceA.saw(pipeline.getVertexShader(), ShaderType.VERTEX)
						|| !sourceA.saw(pipeline.getFragmentShader(), ShaderType.FRAGMENT)) {
						throw new AssertionError("Linear compile did not read GLSL from the reload ShaderSource");
					}

					long generation = gpu.shaderGeneration();
					gpu.setReloadShaderSource(sourceA);
					if (gpu.shaderGeneration() != generation || session.isPoisoned()) {
						throw new AssertionError("Identity-equal ShaderSource incremented generation or poisoned");
					}

					gpu.setReloadShaderSource(sourceB);
					if (gpu.reloadShaderSource() != sourceB) {
						throw new AssertionError("Replacement ShaderSource was not installed");
					}
					if (gpu.shaderGeneration() != generation + 1 || !session.isPoisoned()) {
						throw new AssertionError("Changed ShaderSource did not increment generation or poison");
					}

					generation = gpu.shaderGeneration();
					gpu.setReloadShaderSource(sourceB);
					if (gpu.shaderGeneration() != generation) {
						throw new AssertionError("Repeated ShaderSource instance incremented generation");
					}
				} finally {
					session.close();
				}

				if (gpu.beginLinearWorld(colorView, depthView, false, List.of(pipeline), null) != null) {
					throw new AssertionError("Poisoned session did not force the next frame legacy");
				}

				gpu.setReloadShaderSource(null);
				if (gpu.reloadShaderSource() != null) {
					throw new AssertionError("Null reload ShaderSource did not clear the override");
				}
				MetalLinearWorldSession fallback = gpu.beginLinearWorld(
					colorView, depthView, false, List.of(pipeline), null);
				if (fallback == null) throw new AssertionError("Null reload did not restore a linear world session");
				try {
					if (fallback.token().shaderSource() != defaultSource) {
						throw new AssertionError("Null reload did not fall back to the constructor ShaderSource");
					}
				} finally {
					fallback.close();
				}
			}

			gpu.clearPipelineCache();
			runtime.selectPack(ShaderPackRuntime.NONE_ID);
			System.out.println("Linear world reload source: install, GLSL selection, identity no-op, poison and null fallback passed");
		} finally {
			gpu.close();
		}
	}

	private static String vanilla(final Identifier id, final ShaderType type) {
		String path = "assets/" + id.getNamespace() + "/shaders/" + id.getPath()
			+ (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
		Set<String> imported = new HashSet<>();
		var preprocessor = new GlslPreprocessor() {
			@Override
			public String applyImport(boolean relative, String name) {
				Identifier include = Identifier.parse(name);
				String resolved = relative ? path.substring(0, path.lastIndexOf('/') + 1) + name
					: "assets/" + include.getNamespace() + "/shaders/include/" + include.getPath();
				return imported.add(resolved) ? resource(resolved) : null;
			}
		};
		return String.join("", preprocessor.process(resource(path)));
	}

	private static String resource(final String path) {
		try (var input = LinearWorldReloadSourceSmoke.class.getClassLoader().getResourceAsStream(path)) {
			if (input == null) throw new AssertionError("Missing vanilla shader " + path);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new AssertionError(error);
		}
	}

	private static final class RecordingSource implements ShaderSource {
		private final ShaderSource delegate;
		private final Set<String> seen = new HashSet<>();

		private RecordingSource(final ShaderSource delegate) {
			this.delegate = delegate;
		}

		@Override
		public String get(final Identifier id, final ShaderType type) {
			this.seen.add(id + ":" + type);
			return this.delegate.get(id, type);
		}

		private boolean saw(final Identifier id, final ShaderType type) {
			return this.seen.contains(id + ":" + type);
		}
	}
}
