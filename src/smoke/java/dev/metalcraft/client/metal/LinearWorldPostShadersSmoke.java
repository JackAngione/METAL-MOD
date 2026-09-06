package dev.metalcraft.client.metal;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Actual vanilla Fabulous/copy GPU coverage. This does not activate live HDR rendering. */
final class LinearWorldPostShadersSmoke {
	private static final String[] FABULOUS_SAMPLERS = {
		"MainSampler", "MainDepthSampler", "TranslucentSampler", "TranslucentDepthSampler",
		"ItemEntitySampler", "ItemEntityDepthSampler", "ParticlesSampler", "ParticlesDepthSampler",
		"WeatherSampler", "WeatherDepthSampler", "CloudsSampler", "CloudsDepthSampler"
	};

	private LinearWorldPostShadersSmoke() { }

	static void run() {
		ShaderSource sources = (id, type) -> resource(id, type);
		var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), sources);
		try {
			RenderPipeline fabulous = postPipeline("fabulous", "transparency", FABULOUS_SAMPLERS, false);
			RenderPipeline copy = postPipeline("copy", "blit", new String[]{"InSampler"}, true);
			RenderPipeline missingAfterLegacy = postPipeline("missing_after_legacy", "blit", new String[]{"InSampler"}, true);
			RenderPipeline fabulousReplacement = postPipeline("fabulous_replacement", "transparency", FABULOUS_SAMPLERS, false);
			RenderPipeline copyReplacement = postPipeline("copy_replacement", "blit", new String[]{"InSampler"}, true);
			var fabulousCompiled = (MetalCompiledRenderPipeline)gpu.precompileLinearWorldPostPipeline(
				fabulous, sources, LinearWorldPostShaders.Semantic.FABULOUS_TRANSPARENCY);
			var copyCompiled = (MetalCompiledRenderPipeline)gpu.precompileLinearWorldPostPipeline(
				copy, sources, LinearWorldPostShaders.Semantic.LINEAR_COPY);
			if (!fabulousCompiled.isValid() || !copyCompiled.isValid()) throw new AssertionError("Linear post compilation failed");
			if (fabulousCompiled != gpu.precompileLinearWorldPostPipeline(fabulous, sources,
				LinearWorldPostShaders.Semantic.FABULOUS_TRANSPARENCY)
				|| copyCompiled != gpu.precompileLinearWorldPostPipeline(copy, sources,
				LinearWorldPostShaders.Semantic.LINEAR_COPY)) throw new AssertionError("Linear post cache did not reuse entries");
			if (fabulousCompiled == gpu.precompilePipeline(fabulous, sources)
				|| copyCompiled == gpu.precompilePipeline(copy, sources)) throw new AssertionError("Linear post cache aliased legacy cache");

			MetalRenderPipeline fabulousMetal = fabulousCompiled.metal(false, MetalTexture.Format.RGBA16_FLOAT);
			MetalRenderPipeline copyMetal = copyCompiled.metal(false, MetalTexture.Format.RGBA16_FLOAT);
			verifyComposition(gpu.metal(), fabulousMetal, copyMetal,
				fabulousCompiled.uniformLayout().size(), copyCompiled.uniformLayout().size(),
				copyCompiled.uniformLayout().stream().map(BindGroupLayout.UniformDescription::name).toList().indexOf("BlitConfig"));

			reject(() -> gpu.precompileLinearWorldPostPipeline(fabulousReplacement, replaced(sources, "post/transparency"),
				LinearWorldPostShaders.Semantic.FABULOUS_TRANSPARENCY));
			reject(() -> gpu.precompileLinearWorldPostPipeline(copyReplacement, replaced(sources, "post/blit"),
				LinearWorldPostShaders.Semantic.LINEAR_COPY));
			reject(() -> gpu.precompileLinearWorldPostPipeline(copy, replaced(sources, "post/blit"),
				LinearWorldPostShaders.Semantic.LINEAR_COPY));
			reject(() -> gpu.precompileLinearWorldPostPipeline(copy, (id, type) -> null,
				LinearWorldPostShaders.Semantic.LINEAR_COPY));
			reject(() -> gpu.precompileLinearWorldPostPipeline(copy, sources,
				LinearWorldPostShaders.Semantic.FABULOUS_TRANSPARENCY));
			if (!gpu.precompilePipeline(missingAfterLegacy, sources).isValid()) {
				throw new AssertionError("Legacy setup for missing-source regression failed");
			}
			reject(() -> gpu.precompileLinearWorldPostPipeline(missingAfterLegacy, (id, type) -> null,
				LinearWorldPostShaders.Semantic.LINEAR_COPY));

			gpu.clearPipelineCache();
			if (!fabulousMetal.isClosed() || !copyMetal.isClosed()) throw new AssertionError("Linear post cache leaked on reload");
			System.out.println("Linear post: actual vanilla Fabulous depth ordering/premultiplied alpha, HDR preservation, no extra decode, copy alpha, source rejection and retirement passed");
		} finally {
			gpu.close();
		}
	}

	private static void verifyComposition(final MetalDevice device, final MetalRenderPipeline fabulous,
		final MetalRenderPipeline copy, final int fabulousTextureStart, final int copyTextureStart,
		final int modulationSlot) {
		// Main plus five premultiplied layers. Depth order is deliberately unrelated to binding order.
		double[][] colors = {
			{4.0, 1.5, 0.25, 1.0}, {0.5, 0.0, 0.0, 0.25}, {9.0, 7.0, 5.0, 0.0},
			{0.0, 0.8, 0.0, 0.5}, {0.0, 0.0, 1.8, 0.75}, {0.3, 0.2, 0.1, 0.2}
		};
		double[] depths = {0.55, 0.20, 0.95, 0.80, 0.40, 0.65};
		List<MetalTexture> textures = new ArrayList<>();
		List<MetalTextureView> views = new ArrayList<>();
		try (var queue = device.createCommandQueue();
			 var composed = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 1, 1, 1));
			 var copied = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 1, 1, 1));
			 var composedView = composed.createView();
			 var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,
				 MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 var modulate = device.createBuffer(16, MetalBuffer.StorageMode.SHARED)) {
			for (int i = 0; i < colors.length; i++) {
				MetalTexture color = texture(device, queue, colors[i]);
				MetalTexture depth = texture(device, queue, new double[]{depths[i], 0, 0, 1});
				textures.add(color); textures.add(depth);
				views.add(color.createView()); views.add(depth.createView());
			}
			try (var commands = queue.createCommandBuffer()) {
				try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					MetalRenderPass.ColorAttachment.clear(composed, 0, 0, 0, 0)))) {
					pass.setPipeline(fabulous);
					for (int i = 0; i < views.size(); i++) {
						pass.setTexture(fabulousTextureStart + i, views.get(i), MetalRenderPass.STAGE_FRAGMENT);
						pass.setSampler(fabulousTextureStart + i, sampler, MetalRenderPass.STAGE_FRAGMENT);
					}
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
				}
				commands.commitAndWait();
			}
			double[] expected = fabulousReference(colors, depths);
			checkPixel("Fabulous", expected, composed.readback(queue, 0), 0.006);
			try (var mapped = modulate.map()) {
				mapped.bytes().order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{0.5F, 0.75F, 1.25F, 0.6F});
			}
			try (var commands = queue.createCommandBuffer()) {
				try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					MetalRenderPass.ColorAttachment.clear(copied, 0, 0, 0, 0)))) {
					pass.setPipeline(copy);
					pass.setTexture(copyTextureStart, composedView, MetalRenderPass.STAGE_FRAGMENT);
					pass.setSampler(copyTextureStart, sampler, MetalRenderPass.STAGE_FRAGMENT);
					pass.setUniformBuffer(modulationSlot, modulate, 0, MetalRenderPass.STAGE_FRAGMENT);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
				}
				commands.commitAndWait();
			}
			checkPixel("copy", new double[]{expected[0] * 0.5, expected[1] * 0.75,
				expected[2] * 1.25, 0.6}, copied.readback(queue, 0), 0.008);
		} finally {
			for (MetalTextureView view : views) view.close();
			for (MetalTexture texture : textures) texture.close();
		}
	}

	private static double[] fabulousReference(final double[][] colors, final double[] depths) {
		List<Integer> active = new ArrayList<>();
		for (int i = 0; i < colors.length; i++) if (i == 0 || colors[i][3] != 0) active.add(i);
		active.sort((a, b) -> Double.compare(depths[a], depths[b]));
		double[] result = colors[active.getFirst()].clone();
		for (int n = 1; n < active.size(); n++) {
			double[] source = colors[active.get(n)];
			for (int c = 0; c < 3; c++) result[c] = result[c] * (1 - source[3]) + source[c];
		}
		result[3] = 1;
		return result;
	}

	private static MetalTexture texture(final MetalDevice device, final MetalCommandQueue queue, final double[] value) {
		MetalTexture texture = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 1, 1, 1));
		ByteBuffer bytes = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
		for (double channel : value) bytes.putShort(Float.floatToFloat16((float)channel));
		bytes.flip();
		texture.upload(queue, 0, bytes);
		return texture;
	}

	private static void checkPixel(final String label, final double[] expected, final ByteBuffer bytes, final double tolerance) {
		bytes.order(ByteOrder.nativeOrder());
		for (int c = 0; c < 4; c++) {
			double actual = Float.float16ToFloat(bytes.getShort(c * 2));
			if (!Double.isFinite(actual) || Math.abs(expected[c] - actual) > tolerance) {
				throw new AssertionError(label + " channel " + c + ": expected " + expected[c] + ", got " + actual);
			}
		}
	}

	private static RenderPipeline postPipeline(final String name, final String fragment, final String[] samplers,
		final boolean colorModulate) {
		BindGroupLayout.Builder bindings = BindGroupLayout.builder();
		for (String sampler : samplers) bindings.withSampler(sampler);
		if (colorModulate) bindings.withUniform("BlitConfig", UniformType.UNIFORM_BUFFER);
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(Identifier.parse("metalcraft:smoke/linear_post_" + name))
			.withVertexShader(Identifier.parse("minecraft:core/screenquad"))
			.withFragmentShader(Identifier.parse("minecraft:post/" + fragment))
			.withBindGroupLayout(bindings.build()).build();
	}

	private static ShaderSource replaced(final ShaderSource source, final String path) {
		return (id, type) -> {
			String value = source.get(id, type);
			return id.getPath().equals(path) && type == ShaderType.FRAGMENT
				? value.replaceFirst("fragColor", "replacementColor") : value;
		};
	}

	private static String resource(final Identifier id, final ShaderType type) {
		String path = "assets/" + id.getNamespace() + "/shaders/" + id.getPath()
			+ (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
		try (var input = LinearWorldPostShadersSmoke.class.getClassLoader().getResourceAsStream(path)) {
			if (input == null) return null;
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new AssertionError(error);
		}
	}

	private static void reject(final Runnable action) {
		try { action.run(); } catch (IllegalArgumentException expected) { return; }
		throw new AssertionError("Expected linear post source rejection");
	}
}
