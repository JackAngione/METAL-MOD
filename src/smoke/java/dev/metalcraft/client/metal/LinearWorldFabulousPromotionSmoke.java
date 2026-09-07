package dev.metalcraft.client.metal;

import com.google.gson.JsonParser;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.serialization.JsonOps;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Mixin-equivalent Fabulous promotion and post-contract registration. Does not wrap GameRenderer. */
final class LinearWorldFabulousPromotionSmoke {
	private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
	private static final String[] FABULOUS_SAMPLERS = {
		"MainSampler", "MainDepthSampler", "TranslucentSampler", "TranslucentDepthSampler",
		"ItemEntitySampler", "ItemEntityDepthSampler", "ParticlesSampler", "ParticlesDepthSampler",
		"WeatherSampler", "WeatherDepthSampler", "CloudsSampler", "CloudsDepthSampler"
	};

	private LinearWorldFabulousPromotionSmoke() { }

	static void run() {
		LinearWorldTransparencyConfigSmoke.run();
		PostChainConfig vanilla = config("assets/minecraft/post_effect/transparency.json");
		PostChainConfig outline = config("assets/minecraft/post_effect/entity_outline.json");
		if (!LinearWorldTransparencyConfig.tryVerify(vanilla) || LinearWorldTransparencyConfig.tryVerify(outline)) {
			throw new AssertionError("Live transparency verifier accepted an unexpected graph");
		}

		ShaderSource sources = (id, type) -> resource(id, type);
		var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), sources);
		try {
			var runtime = gpu.shaderPackRuntime();
			if (runtime == null) throw new AssertionError("Missing smoke shader runtime");
			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);

			RenderTargetDescriptor encoded = rgba8();
			MetalLinearWorldPostActivation.resetForTest();
			if (MetalLinearWorldPostActivation.promoteFabulousLayers(gpu, encoded).format() != GpuFormat.RGBA8_UNORM
				|| MetalLinearWorldPostActivation.promoteFabulousLayers(null, encoded).format() != GpuFormat.RGBA8_UNORM) {
				throw new AssertionError("Promotion ran without a fabulous HDR session");
			}

			RenderPipeline fabulousPipeline = postPipeline("fabulous", "transparency", FABULOUS_SAMPLERS, false);
			RenderPipeline copyPipeline = postPipeline("copy", "blit", new String[]{"InSampler"}, true);
			MetalLinearWorldPostActivation.beginChainLoad(id("transparency"), vanilla);
			MetalLinearWorldPostActivation.registerCreatedPass(gpu, fabulousPipeline);
			MetalLinearWorldPostActivation.registerCreatedPass(gpu, copyPipeline);
			if (!MetalLinearWorldPostActivation.finishChainLoad()
				|| !MetalLinearWorldPostActivation.liveTransparencyVerified()) {
				throw new AssertionError("Vanilla transparency graph was not accepted for promotion");
			}

			try (var color = gpu.createTexture("fabulous-main-color", USAGE, GpuFormat.RGBA8_UNORM, 5, 3, 1, 1);
				 var depth = gpu.createTexture("fabulous-main-depth", USAGE, GpuFormat.D32_FLOAT, 5, 3, 1, 1);
				 var colorView = gpu.createTextureView(color);
				 var depthView = gpu.createTextureView(depth)) {
				if (MetalLinearWorldPostActivation.promoteFabulousLayers(gpu, encoded).format() != GpuFormat.RGBA8_UNORM) {
					throw new AssertionError("Verified graph promoted before a session token existed");
				}

				MetalLinearWorldSession session = gpu.beginLinearWorld(colorView, depthView, true, List.of(), sources);
				if (session == null || !session.token().fabulous()) {
					throw new AssertionError("Fabulous HDR session was not created");
				}
				try {
					RenderTargetDescriptor fabulous = MetalLinearWorldPostActivation.promoteFabulousLayers(gpu, encoded);
					RenderTargetDescriptor chainFinal = MetalLinearWorldPostActivation.promoteTransparencyInternal(gpu, true, encoded);
					if (fabulous.format() != GpuFormat.RGBA16_FLOAT || chainFinal.format() != GpuFormat.RGBA16_FLOAT
						|| !fabulous.useDepth() || fabulous.width() != encoded.width()
						|| encoded.canUsePhysicalResource(fabulous)) {
						throw new AssertionError("Fabulous/transparency internals were not promoted to RGBA16_FLOAT");
					}

					RenderTargetDescriptor outlineTarget = MetalLinearWorldPostActivation.promoteTransparencyInternal(gpu, false, encoded);
					if (outlineTarget.format() != GpuFormat.RGBA8_UNORM
						|| MetalLinearWorldPostActivation.promote(encoded, false).format() != GpuFormat.RGBA8_UNORM) {
						throw new AssertionError("Outline or unrelated descriptors were promoted");
					}

					var fabulousCompiled = gpu.linearPipelineFor(session, fabulousPipeline);
					var copyCompiled = gpu.linearPipelineFor(session, copyPipeline);
					if (fabulousCompiled == null || copyCompiled == null || !fabulousCompiled.isValid() || !copyCompiled.isValid()) {
						throw new AssertionError("Registered Fabulous post contracts were not selected");
					}
					if (fabulousCompiled != gpu.precompileLinearWorldPostPipeline(
							fabulousPipeline, sources, LinearWorldPostShaders.Semantic.FABULOUS_TRANSPARENCY)
						|| copyCompiled != gpu.precompileLinearWorldPostPipeline(
							copyPipeline, sources, LinearWorldPostShaders.Semantic.LINEAR_COPY)) {
						throw new AssertionError("linearPipelineFor did not reuse the linear post cache");
					}
					if (fabulousCompiled == gpu.precompilePipeline(fabulousPipeline, sources)
						|| copyCompiled == gpu.precompilePipeline(copyPipeline, sources)) {
						throw new AssertionError("Linear post contracts aliased the legacy cache");
					}

					PostChainConfig persistent = new PostChainConfig(
						Map.of(id("final"), new PostChainConfig.InternalTarget(Optional.empty(), Optional.empty(), true, 0)),
						vanilla.passes());
					MetalLinearWorldPostActivation.beginChainLoad(id("transparency"), persistent);
					if (MetalLinearWorldPostActivation.finishChainLoad()
						|| MetalLinearWorldPostActivation.liveTransparencyVerified()
						|| MetalLinearWorldPostActivation.promoteFabulousLayers(gpu, encoded).format() != GpuFormat.RGBA8_UNORM) {
						throw new AssertionError("Replaced transparency graph was promoted");
					}

					MetalLinearWorldPostActivation.beginChainLoad(id("transparency"), vanilla);
					MetalLinearWorldPostActivation.finishChainLoad();
					MetalLinearWorldPostActivation.beginChainLoad(id("entity_outline"), outline);
					var outlineBlit = postPipeline("outline_blit", "blit", new String[]{"InSampler"}, true);
					MetalLinearWorldPostActivation.registerCreatedPass(gpu, outlineBlit);
					if (MetalLinearWorldPostActivation.finishChainLoad()) {
						throw new AssertionError("Entity outline graph inherited the transparency contract");
					}
					if (gpu.linearPipelineFor(session, outlineBlit) != null || !session.isPoisoned()) {
						throw new AssertionError("Unrelated post/blit was treated as a linear copy");
					}
				} finally {
					session.close();
				}

				if (gpu.beginLinearWorld(colorView, depthView, true, List.of(), sources) != null) {
					throw new AssertionError("Poisoned Fabulous session did not force the next frame legacy");
				}

				MetalLinearWorldSession encodedSession = gpu.beginLinearWorld(
					colorView, depthView, false, List.of(), sources);
				if (encodedSession == null) throw new AssertionError("Recovered session was not created");
				try {
					if (encodedSession.token().fabulous()
						|| MetalLinearWorldPostActivation.promoteFabulousLayers(gpu, encoded).format() != GpuFormat.RGBA8_UNORM
						|| MetalLinearWorldPostActivation.promoteTransparencyInternal(gpu, true, encoded).format() != GpuFormat.RGBA8_UNORM) {
						throw new AssertionError("Non-fabulous token promoted Fabulous descriptors");
					}
				} finally {
					encodedSession.close();
				}
			}

			MetalLinearWorldPostActivation.resetForTest();
			runtime.selectPack(ShaderPackRuntime.NONE_ID);
			System.out.println("Linear Fabulous promotion: HDR RGBA16_FLOAT while a fabulous token is verified, legacy RGBA8 otherwise, outline skipped, post contracts selected passed");
		} finally {
			MetalLinearWorldPostActivation.resetForTest();
			gpu.close();
		}
	}

	private static RenderTargetDescriptor rgba8() {
		return new RenderTargetDescriptor(8, 4, true, new Vector4f(0), GpuFormat.RGBA8_UNORM);
	}

	private static RenderPipeline postPipeline(final String name, final String fragment, final String[] samplers,
		final boolean colorModulate) {
		BindGroupLayout.Builder bindings = BindGroupLayout.builder();
		for (String sampler : samplers) bindings.withSampler(sampler);
		if (colorModulate) bindings.withUniform("BlitConfig", UniformType.UNIFORM_BUFFER);
		return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
			.withLocation(Identifier.parse("metalcraft:smoke/linear_fabulous_" + name))
			.withVertexShader(Identifier.parse("minecraft:core/screenquad"))
			.withFragmentShader(Identifier.parse("minecraft:post/" + fragment))
			.withBindGroupLayout(bindings.build()).build();
	}

	private static PostChainConfig config(final String path) {
		try (var stream = LinearWorldFabulousPromotionSmoke.class.getClassLoader().getResourceAsStream(path)) {
			if (stream == null) throw new AssertionError("Missing " + path);
			return PostChainConfig.CODEC.parse(JsonOps.INSTANCE,
				JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))).getOrThrow();
		} catch (IOException error) {
			throw new AssertionError(error);
		}
	}

	private static String resource(final Identifier id, final ShaderType type) {
		String path = "assets/" + id.getNamespace() + "/shaders/" + id.getPath()
			+ (type == ShaderType.VERTEX ? ".vsh" : ".fsh");
		try (var input = LinearWorldFabulousPromotionSmoke.class.getClassLoader().getResourceAsStream(path)) {
			if (input == null) return null;
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new AssertionError(error);
		}
	}

	private static Identifier id(final String path) {
		return Identifier.withDefaultNamespace(path);
	}
}
