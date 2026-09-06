package dev.metalcraft.client.metal;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Actual vanilla source compilation and numeric forward color coverage; not live HDR activation. */
final class LinearWorldShadersSmoke {
	private static final Set<String> SUPPORTED = Set.of("core/terrain", "core/block", "core/entity", "core/particle", "core/rendertype_clouds",
		"core/glint", "core/position", "core/sky", "core/stars", "core/position_color",
		"core/position_tex_color", "core/position_tex", "core/rendertype_lightning", "core/rendertype_world_border",
		"core/rendertype_beacon_beam", "core/rendertype_crumbling", "core/rendertype_entity_shadow",
		"core/rendertype_lines", "core/rendertype_leash", "core/rendertype_end_portal", "core/text", "core/text_background", "core/debug_point", "core/item");

	static void run() {
		ShaderSource sources = (id, type) -> expanded(id, type);
		var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), sources);
		try {
			var emptyGpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
			try {
				reject(() -> emptyGpu.precompileLinearWorldPipeline(RenderPipelines.TRANSLUCENT_PARTICLE, null));
			} finally { emptyGpu.close(); }
			List<MetalRenderPipeline> owned = new ArrayList<>();
			int count = 0;
			for (var info : RenderPipelines.getStaticPipelines()) {
				if (!SUPPORTED.contains(info.getVertexShader().getPath()) || !SUPPORTED.contains(info.getFragmentShader().getPath())) continue;
				var legacy = (MetalCompiledRenderPipeline)gpu.precompilePipeline(info, sources);
				var linear = (MetalCompiledRenderPipeline)gpu.precompileLinearWorldPipeline(info, sources);
				if (!legacy.isValid() || !linear.isValid()) throw new AssertionError("Forward compilation failed: " + info.getLocation());
				if (legacy == linear || linear != gpu.precompileLinearWorldPipeline(info, sources)
					|| legacy != gpu.precompilePipeline(info, sources)) throw new AssertionError("Semantic caches alias or fail to reuse");
				owned.add(legacy.metal(false));
				owned.add(linear.metal(false, MetalTexture.Format.RGBA16_FLOAT));
				count++;
			}
			if (count < 73) throw new AssertionError("Unexpectedly narrow vanilla forward fixture: " + count);
			reject(() -> LinearWorldShaders.fragment(Identifier.parse("minecraft:core/screenquad"), "#version 330\nvoid main() {}"));
			Identifier particle = Identifier.parse("minecraft:core/particle");
			String fragment = expanded(particle, ShaderType.FRAGMENT);
			reject(() -> LinearWorldShaders.fragment(particle, fragment.replace("color.a < 0.1", "color.a < 0.2")));
			reject(() -> LinearWorldShaders.fragment(Identifier.parse("other:core/particle"), fragment));
			Identifier terrain = Identifier.parse("minecraft:core/terrain");
			String terrainSource = expanded(terrain, ShaderType.FRAGMENT);
			if (!terrainSource.contains("++i")) throw new AssertionError("Missing token-boundary fixture anchor");
			reject(() -> LinearWorldShaders.fragment(terrain, terrainSource.replace("++i", "+ +i")));
			reject(() -> LinearWorldShaders.verify(particle, ".vsh", expanded(particle, ShaderType.VERTEX) + "\nvoid replaced() {}\n"));
			verifyParticle(gpu.metal(), fragment);
			verifySimpleEffects(gpu.metal());
			gpu.clearPipelineCache();
			for (var pipeline : owned) if (!pipeline.isClosed()) throw new AssertionError("Semantic pipeline leaked on reload");
			// Ensure a replacement cannot be accepted through the original pipeline's retired cache entry.
			reject(() -> gpu.precompileLinearWorldPipeline(RenderPipelines.TRANSLUCENT_PARTICLE,
				(id, type) -> type == ShaderType.FRAGMENT ? fragment.replace("color.a < 0.1", "color.a < 0.2") : expanded(id, type)));
			System.out.println("Linear forward: " + count + " actual vanilla pipelines, separate caches, source rejection, particle fog/HDR/alpha/blend, simple producer decoding, sky fog, glint/lightning attenuation, crumbling overlap, text discard/defines, leash/portal fog and retirement passed");
		} finally {
			gpu.close();
		}
	}

	private static String expanded(Identifier id, ShaderType type) {
		String path = "assets/" + id.getNamespace() + "/shaders/" + id.getPath() + (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
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

	private static String resource(String path) {
		try (var input = LinearWorldShadersSmoke.class.getClassLoader().getResourceAsStream(path)) {
			if (input == null) throw new AssertionError("Missing vanilla shader " + path);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new AssertionError(error);
		}
	}

	private static void verifyParticle(MetalDevice device, String original) {
		String vertex = """
			#version 450
			out float sphericalVertexDistance;
			out float cylindricalVertexDistance;
			out vec2 texCoord0;
			out vec4 vertexColor;
			void main() {
			    gl_Position = vec4(gl_VertexIndex == 1 ? 3.0 : -1.0, gl_VertexIndex == 2 ? 3.0 : -1.0, 0.5, 1.0);
			    sphericalVertexDistance = 5.0;
			    cylindricalVertexDistance = 5.0;
			    texCoord0 = vec2(0.5);
			    vertexColor = vec4(2.0, 0.5, 0.02, 1.0);
			}
			""";
		var blend = new MetalRenderPipeline.BlendState(MetalRenderPipeline.BlendFactor.SOURCE_ALPHA,
			MetalRenderPipeline.BlendFactor.ONE_MINUS_SOURCE_ALPHA, MetalRenderPipeline.BlendOperation.ADD,
			MetalRenderPipeline.BlendFactor.ONE, MetalRenderPipeline.BlendFactor.ONE_MINUS_SOURCE_ALPHA,
			MetalRenderPipeline.BlendOperation.ADD);
		try (var queue = device.createCommandQueue();
			 var target = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 3, 1, 1));
			 var tex = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 1, 1, 1));
			 var view = tex.createView();
			 var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 var fog = device.createBuffer(48, MetalBuffer.StorageMode.SHARED);
			 var dynamic = device.createBuffer(160, MetalBuffer.StorageMode.SHARED)) {
			tex.upload(queue, 0, ByteBuffer.allocateDirect(4).putInt(-1).flip());
			for (boolean linear : new boolean[]{false, true}) {
				String fragment = linear ? LinearWorldShaders.fragment(Identifier.parse("minecraft:core/particle"), original) : original;
				fragment = fragment.replaceFirst("#version 330", "#version 450")
					.replace("layout(std140) uniform Fog", "layout(std140, binding = 0) uniform Fog")
					.replace("layout(std140) uniform DynamicTransforms", "layout(std140, binding = 1) uniform DynamicTransforms")
					.replace("uniform sampler2D Sampler0;", "layout(binding = 2) uniform sampler2D Sampler0;");
				var translated = MetalShaderTranslator.translatePipeline(vertex, "particle-fixture.vsh", fragment, "actual-particle.fsh");
				try (var program = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
					translated.vertex().metalSource(), translated.vertex().entryPoint(), translated.fragment().metalSource(), translated.fragment().entryPoint(),
					List.of(new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA16_FLOAT, MetalRenderPipeline.WRITE_ALL, blend)),
					null, MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT))) {
					for (float amount : new float[]{0, 0.3F, 1}) {
						try (var mapped = fog.map()) {
							var b = mapped.bytes().order(ByteOrder.nativeOrder());
							b.asFloatBuffer().put(new float[]{0.5F, 0.25F, 0.75F, amount == 1 ? 1 : 0.6F,
								0, amount == 0 ? 100000000 : amount == 1 ? 5 : 10, 0, 100000000, 100, 100});
						}
						for (float alpha : new float[]{0, 0.05F, 0.25F, 0.5F, 1}) {
							try (var mapped = dynamic.map()) {
								var b = mapped.bytes().order(ByteOrder.nativeOrder());
								b.putFloat(64, 1).putFloat(68, 1).putFloat(72, 1).putFloat(76, alpha);
							}
							try (var commands = queue.createCommandBuffer()) {
								try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(target, 0.2, 0.4, 0.6, 0.3)))) {
									pass.setPipeline(program);
									pass.setUniformBuffer(0, fog, 0, MetalRenderPass.STAGE_FRAGMENT);
									pass.setUniformBuffer(1, dynamic, 0, MetalRenderPass.STAGE_FRAGMENT);
									pass.setTexture(2, view, MetalRenderPass.STAGE_FRAGMENT);
									pass.setSampler(2, sampler, MetalRenderPass.STAGE_FRAGMENT);
									pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
								}
								commands.commitAndWait();
							}
							var pixels = target.readback(queue, 0).order(ByteOrder.nativeOrder());
							double[] seed = {2, 0.5, 0.02}, fogRgb = {0.5, 0.25, 0.75}, background = {0.2, 0.4, 0.6, 0.3};
							for (int c = 0; c < 4; c++) {
								double expected = background[c];
								if (alpha >= 0.1) {
									if (c == 3) expected = alpha + expected * (1 - alpha);
									else {
										double s = linear ? decode(seed[c]) : seed[c], f = linear ? decode(fogRgb[c]) : fogRgb[c];
										expected = (s * (1 - amount) + f * amount) * alpha + expected * (1 - alpha);
									}
								}
								check(expected, Float.float16ToFloat(pixels.getShort(c * 2)));
							}
						}
					}
				}
			}
		}
	}

	/** Actual fragment programs with controlled varyings; independent CPU transfer/attenuation references. */
	private static void verifySimpleEffects(MetalDevice device) {
		String vertex = """
			#version 450
			out float sphericalVertexDistance;
			out float cylindricalVertexDistance;
			out vec2 texCoord0;
			out vec4 texProj0;
			out vec4 vertexColor;
			void main() {
			    gl_Position = vec4(gl_VertexIndex == 1 ? 3.0 : -1.0, gl_VertexIndex == 2 ? 3.0 : -1.0, 0.5, 1.0);
			    sphericalVertexDistance = 5.0;
			    cylindricalVertexDistance = 5.0;
			    texCoord0 = vec2(0.5);
			    texProj0 = vec4(0.5, 0.5, 0.5, 1.0);
			    vertexColor = vec4(0.5, 1.0, 1.0, 0.5);
			}
			""";
		try (var queue = device.createCommandQueue();
			 var target = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 3, 1, 1));
			 var tex = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 1, 1, 1));
			 var view = tex.createView();
			 var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 var fog = device.createBuffer(48, MetalBuffer.StorageMode.SHARED);
			 var dynamic = device.createBuffer(160, MetalBuffer.StorageMode.SHARED);
			 var globals = device.createBuffer(64, MetalBuffer.StorageMode.SHARED)) {
			tex.upload(queue, 0, ByteBuffer.allocateDirect(4).putInt(-1).flip());
			try (var mapped = globals.map()) { mapped.bytes().order(ByteOrder.nativeOrder()).putFloat(40, 0.4F); }
			for (String fixture : List.of("glint", "position", "sky", "stars", "position_color", "position_tex_color",
				"position_tex", "rendertype_lightning", "rendertype_world_border", "rendertype_crumbling", "rendertype_leash",
				"rendertype_end_portal", "text", "text:IS_SEE_THROUGH", "text:IS_GUI", "text:IS_GRAYSCALE",
				"text:IS_SEE_THROUGH:IS_GRAYSCALE", "text:IS_GUI:IS_GRAYSCALE", "text_background", "text_background:IS_SEE_THROUGH")) {
				String name = fixture.split(":")[0];
				boolean text = name.equals("text"), textBackground = name.equals("text_background");
				boolean seeThrough = fixture.contains("IS_SEE_THROUGH"), gui = fixture.contains("IS_GUI"), grayscale = fixture.contains("IS_GRAYSCALE");
				boolean crumbling = name.equals("rendertype_crumbling"), leash = name.equals("rendertype_leash"), portal = name.equals("rendertype_end_portal");
				tex.upload(queue, 0, ByteBuffer.allocateDirect(4).put(text ? new byte[]{(byte)204, 102, 51, (byte)153} : new byte[]{-1, -1, -1, -1}).flip());
				boolean glint = name.equals("glint"), lightning = name.equals("rendertype_lightning");
				boolean colored = name.equals("position_color") || name.equals("position_tex_color") || lightning || crumbling || text || textBackground;
				boolean overlay = name.equals("stars") || name.equals("rendertype_world_border");
				boolean fogged = name.equals("position") || name.equals("sky") || crumbling || leash || portal || ((text || textBackground) && !seeThrough && !gui);
				var blend = crumbling ? new MetalRenderPipeline.BlendState(
					MetalRenderPipeline.BlendFactor.DESTINATION_COLOR, MetalRenderPipeline.BlendFactor.SOURCE_COLOR, MetalRenderPipeline.BlendOperation.ADD,
					MetalRenderPipeline.BlendFactor.ONE, MetalRenderPipeline.BlendFactor.ZERO, MetalRenderPipeline.BlendOperation.ADD)
					: glint || lightning || overlay ? new MetalRenderPipeline.BlendState(
					glint ? MetalRenderPipeline.BlendFactor.SOURCE_COLOR : MetalRenderPipeline.BlendFactor.SOURCE_ALPHA,
					MetalRenderPipeline.BlendFactor.ONE, MetalRenderPipeline.BlendOperation.ADD,
					glint ? MetalRenderPipeline.BlendFactor.ZERO : lightning ? MetalRenderPipeline.BlendFactor.SOURCE_ALPHA : MetalRenderPipeline.BlendFactor.ONE,
					overlay ? MetalRenderPipeline.BlendFactor.ZERO : MetalRenderPipeline.BlendFactor.ONE,
					MetalRenderPipeline.BlendOperation.ADD) : null;
				var id = Identifier.parse("minecraft:core/" + name);
				for (boolean linear : new boolean[]{false, true}) {
					String fragment = expanded(id, ShaderType.FRAGMENT);
					if (linear) fragment = LinearWorldShaders.fragment(id, fragment);
					String defines = portal ? "#define PORTAL_LAYERS 2\n" : "";
					for (String define : fixture.split(":")) if (define.startsWith("IS_")) defines += "#define " + define + "\n";
					fragment = fragment.replaceFirst("#version 330", "#version 330\n" + defines);
					fragment = fragment.replaceFirst("#version 330", "#version 450")
						.replace("layout(std140) uniform Fog", "layout(std140, binding = 0) uniform Fog")
						.replace("layout(std140) uniform DynamicTransforms", "layout(std140, binding = 1) uniform DynamicTransforms")
						.replace("uniform sampler2D Sampler0;", "layout(binding = 2) uniform sampler2D Sampler0;")
						.replace("uniform sampler2D Sampler1;", "layout(binding = 4) uniform sampler2D Sampler1;")
						.replace("layout(std140) uniform Globals", "layout(std140, binding = 3) uniform Globals");
					var translated = MetalShaderTranslator.translatePipeline(leash ? vertex.replace("out vec4 vertexColor;", "flat out vec4 vertexColor;") : vertex, "effects-fixture.vsh", fragment, "actual-" + name + ".fsh");
					try (var program = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
						translated.vertex().metalSource(), translated.vertex().entryPoint(), translated.fragment().metalSource(), translated.fragment().entryPoint(),
						List.of(new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA16_FLOAT, MetalRenderPipeline.WRITE_ALL, blend)),
						null, MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT))) {
						for (float fogFraction : new float[]{0, 0.5F, 1}) {
							float end = fogFraction == 0 ? 100000000 : fogFraction == 1 ? 5 : 10;
							try (var mapped = fog.map()) {
								mapped.bytes().order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{0.5F, 0.25F, 0.75F, 0.6F,
									0, end, 0, 100000000, end, 100});
							}
							for (float alpha : new float[]{0, 0.05F, 0.5F, 1}) {
								try (var mapped = dynamic.map()) {
									mapped.bytes().order(ByteOrder.nativeOrder()).putFloat(64, 2).putFloat(68, 0.5F).putFloat(72, 0.02F).putFloat(76, alpha);
								}
								try (var commands = queue.createCommandBuffer()) {
									try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(target, 0.2, 0.4, 0.6, 0.3)))) {
										pass.setPipeline(program);
										pass.setUniformBuffer(0, fog, 0, MetalRenderPass.STAGE_FRAGMENT);
										pass.setUniformBuffer(1, dynamic, 0, MetalRenderPass.STAGE_FRAGMENT);
										pass.setTexture(2, view, MetalRenderPass.STAGE_FRAGMENT);
										pass.setSampler(2, sampler, MetalRenderPass.STAGE_FRAGMENT);
										pass.setUniformBuffer(3, globals, 0, MetalRenderPass.STAGE_FRAGMENT);
										pass.setTexture(4, view, MetalRenderPass.STAGE_FRAGMENT);
										pass.setSampler(4, sampler, MetalRenderPass.STAGE_FRAGMENT);
										pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
									}
									commands.commitAndWait();
								}
								var pixels = target.readback(queue, 0).order(ByteOrder.nativeOrder());
								double[] seed = {colored ? 1 : 2, 0.5, 0.02}, fogRgb = {0.5, 0.25, 0.75}, background = {0.2, 0.4, 0.6, 0.3};
								if (leash) seed = new double[]{0.5, 1, 1};
								if (portal) seed = new double[]{0.056066, 0.292722, 0.311121};
								if (text) for (int c = 0; c < 3; c++) seed[c] *= grayscale ? 0.8 : new double[]{0.8, 0.4, 0.2}[c];
								double outputAlpha = alpha * (colored ? 0.5 : 1) * (lightning ? 1 - fogFraction : 1);
								if (text) outputAlpha *= grayscale ? 0.8 : 0.6;
								if (leash) outputAlpha = 0.5;
								if (portal) outputAlpha = 1;
								boolean discarded = glint && alpha < 0.1 || (text || textBackground) && !seeThrough && outputAlpha < 0.1;
								for (int c = 0; c < 4; c++) {
									double expected;
									if (discarded) expected = background[c];
									else if (c == 3) expected = glint ? background[c] : lightning ? outputAlpha * outputAlpha + background[c] : outputAlpha;
									else {
										double value = linear ? decode(seed[c]) : seed[c];
										if (glint || lightning) value *= (1 - fogFraction) * (glint ? 0.4 : 1);
										if (fogged) value = value * (1 - fogFraction * 0.6) + (linear ? decode(fogRgb[c]) : fogRgb[c]) * fogFraction * 0.6;
										expected = crumbling ? 2 * value * background[c] : glint ? value * value + background[c] : lightning || overlay ? value * outputAlpha + background[c] : value;
									}
									try { check(expected, Float.float16ToFloat(pixels.getShort(c * 2))); }
									catch (AssertionError failure) { throw new AssertionError(fixture + " linear=" + linear + " fog=" + fogFraction + " alpha=" + alpha + " channel=" + c, failure); }
								}
							}
						}
					}
				}
			}
		}
	}

	private static double decode(double x) { return x <= 0.04045 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }
	private static void check(double expected, double actual) {
		if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.006) throw new AssertionError("Forward expected " + expected + ", got " + actual);
	}
	private static void reject(Runnable action) {
		try { action.run(); } catch (IllegalArgumentException expected) { return; }
		throw new AssertionError("Unsupported source was accepted");
	}
}
