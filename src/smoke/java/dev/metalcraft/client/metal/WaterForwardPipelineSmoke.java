package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.WorldGeometryAdapter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Exercises Standard's actual forward-water program with Minecraft BLOCK vertices and a sidecar. */
final class WaterForwardPipelineSmoke {
	private static final int WIDTH = 8;
	private static final int HEIGHT = 4;
	private static final int VERTEX_STRIDE = 28;
	private static final int BASE_VERTEX = 2;
	private static final int DRAW_VERTICES = 8;
	private static final int STAGES = MetalRenderPass.STAGE_VERTEX | MetalRenderPass.STAGE_FRAGMENT;

	private WaterForwardPipelineSmoke() { }

	static void run() {
		var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
		try {
			ShaderPackRuntime runtime = gpu.shaderPackRuntime();
			if (runtime == null) throw new AssertionError("Missing shader runtime for forward-water fixture");
			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
			runtime.resize(WIDTH, HEIGHT);
			WorldGeometryAdapter geometry = runtime.worldGeometry();
			if (geometry == null) throw new AssertionError("Standard did not create its geometry adapter");
			geometry.beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);

			RenderPipeline original = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
				.withLocation(Identifier.parse("metalcraft:smoke/water_forward_original"))
				.withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT),
					GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
				.withShaderDefine("ALPHA_CUTOUT", 0.1F)
				.withCull(false)
				.build();
			RenderPipeline water = geometry.waterPipeline(original).orElseThrow(
				() -> new AssertionError("Actual Standard forward-water pipeline was unavailable"));
			if (water == original || water.getVertexFormatBinding(0).getVertexSize() != VERTEX_STRIDE
				|| !WorldGeometryAdapter.isBlended(water)) {
				throw new AssertionError("Forward-water stand-in did not preserve the translucent BLOCK contract");
			}
			MetalCompiledRenderPipeline compiled = (MetalCompiledRenderPipeline)gpu.precompileLinearWorldPipeline(water, null);
			MetalRenderPipeline pipeline = compiled.metal(false, MetalTexture.Format.RGBA16_FLOAT);
			// Surface acquisition must reset inputs without retiring the previous HDR programs.
			geometry.beginFrame();
			geometry.beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);
			if (geometry.waterPipeline(original).orElseThrow() != water
				|| gpu.precompileLinearWorldPipeline(water, null) != compiled) {
				throw new AssertionError("Steady HDR frame rebuilt its cached water pipeline");
			}

			try (var queue = gpu.metal().createCommandQueue();
				 var baseline = gpu.metal().createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.RGBA16_FLOAT, WIDTH, HEIGHT, 1));
				 var identity = gpu.metal().createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.RGBA16_FLOAT, WIDTH, HEIGHT, 1));
				 var atlas = gpu.metal().createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.RGBA16_FLOAT, 1, 1, 1));
				 var alternateAtlas = gpu.metal().createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.RGBA16_FLOAT, 1, 1, 1));
				 var lightmap = gpu.metal().createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.RGBA16_FLOAT, 1, 1, 1));
				 var atlasView = atlas.createView();
				 var alternateAtlasView = alternateAtlas.createView();
				 var lightmapView = lightmap.createView();
				 var sampler = gpu.metal().createSampler(new MetalSampler.Descriptor(
					 MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
				 var vertices = gpu.metal().createBuffer((long)(BASE_VERTEX + DRAW_VERTICES) * VERTEX_STRIDE,
					 MetalBuffer.StorageMode.SHARED);
				 var indices = gpu.metal().createBuffer(12L * Short.BYTES, MetalBuffer.StorageMode.SHARED);
				 var metadata = gpu.metal().createBuffer((long)DRAW_VERTICES * 32, MetalBuffer.StorageMode.SHARED);
				 var projection = gpu.metal().createBuffer(64, MetalBuffer.StorageMode.SHARED);
				 var section = gpu.metal().createBuffer(96, MetalBuffer.StorageMode.SHARED);
				 var globals = gpu.metal().createBuffer(64, MetalBuffer.StorageMode.SHARED);
				 var fog = gpu.metal().createBuffer(48, MetalBuffer.StorageMode.SHARED);
				 var baselineDraw = gpu.metal().createBuffer(16, MetalBuffer.StorageMode.SHARED);
				 var identityDraw = gpu.metal().createBuffer(16, MetalBuffer.StorageMode.SHARED);
				 var waterFrame = gpu.metal().createBuffer(
					 dev.metalcraft.client.shader.water.WaterFrameInputs.UNIFORM_BYTES,
					 MetalBuffer.StorageMode.SHARED);
				 var opaqueColor = gpu.metal().createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.RGBA16_FLOAT, WIDTH, HEIGHT, 1));
				 var opaqueDepth = gpu.metal().createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.DEPTH32_FLOAT, WIDTH, HEIGHT, 1));
				 var opaqueColorView = opaqueColor.createView();
				 var opaqueDepthView = opaqueDepth.createView()) {
				atlas.upload(queue, 0, halfPixel(2, 2, 2, 1));
				alternateAtlas.upload(queue, 0, halfPixel(0.25F, 0.7F, 0.9F, 0.25F));
				lightmap.upload(queue, 0, halfPixel(1, 1, 1, 1));
				writeVertices(vertices);
				writeSortedIndices(indices);
				writeMetadata(metadata);
				writeUniforms(projection, section, globals, fog);
				// Mode 4 is the explicit W3/vanilla-compatible baseline; mode 0 enables W4 effects.
				writeDraw(baselineDraw, 4);
				writeDraw(identityDraw, 1);
				draw(queue, pipeline, baseline, atlasView, lightmapView, sampler, vertices, indices,
					metadata, projection, section, globals, fog, baselineDraw, waterFrame,
					opaqueColorView, opaqueDepthView);
				draw(queue, pipeline, identity, atlasView, lightmapView, sampler, vertices, indices,
					metadata, projection, section, globals, fog, identityDraw, waterFrame,
					opaqueColorView, opaqueDepthView);

				double decoded = decode(2.0);
				double alpha = 128.0 / 255.0;
				double foreground = decoded * alpha;
				double behind = foreground * (1.0 - alpha);
				ByteBuffer normalPixels = baseline.readback(queue, 0).order(ByteOrder.nativeOrder());
				ByteBuffer debugPixels = identity.readback(queue, 0).order(ByteOrder.nativeOrder());
				for (int y = 0; y < HEIGHT; y++) {
					// Water-only, overlap, and glass-only samples avoid rasterisation boundaries.
					assertRgb(normalPixels, 0, y, foreground, 0, 0, "baseline water");
					assertRgb(normalPixels, 3, y, foreground, behind, 0, "sorted glass then water");
					assertRgb(normalPixels, 7, y, 0, foreground, 0, "baseline glass");
					assertRgb(debugPixels, 0, y, foreground, 0, foreground, "identity water");
					assertRgb(debugPixels, 3, y, foreground, behind, foreground, "identity sorted overlap");
					assertRgb(debugPixels, 7, y, 0, foreground, 0, "identity excludes glass");
				}
				// Production mode with water disabled must retain baseline RGB and sorted alpha.
				runtime.setOption("water_enabled", false);
				runtime.reload();
				if (!Boolean.FALSE.equals(runtime.optionValue("water_enabled"))) {
					throw new AssertionError("Water enable did not survive reload");
				}
				WorldGeometryAdapter disabledGeometry = runtime.worldGeometry();
				disabledGeometry.beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);
				var disabledWater = disabledGeometry.waterPipeline(original).orElseThrow();
				var disabledCompiled = (MetalCompiledRenderPipeline)gpu.precompileLinearWorldPipeline(disabledWater, null);
				writeDraw(baselineDraw, 0);
				draw(queue, disabledCompiled.metal(false, MetalTexture.Format.RGBA16_FLOAT), baseline,
					atlasView, lightmapView, sampler, vertices, indices, metadata, projection,
					section, globals, fog, baselineDraw, waterFrame, opaqueColorView, opaqueDepthView);
				ByteBuffer disabledPixels = baseline.readback(queue, 0).order(ByteOrder.nativeOrder());
				for (int y = 0; y < HEIGHT; y++) {
					assertRgb(disabledPixels, 0, y, foreground, 0, 0, "water-off baseline");
					assertRgb(disabledPixels, 3, y, foreground, behind, 0, "water-off sorted overlap");
					assertRgb(disabledPixels, 7, y, 0, foreground, 0, "water-off glass");
				}
				var disabledDescriptor = disabledCompiled.metal(false, MetalTexture.Format.RGBA16_FLOAT).descriptor();
				for (int repeat = 0; repeat < 4; repeat++) {
					runtime.setOption("water_enabled", true);
					runtime.setOption("water_detail", repeat);
					for (String id : List.of("water_wave_strength", "water_refraction_strength",
						"water_absorption", "water_foam", "water_underwater_distortion")) {
						runtime.setOption(id, repeat == 0 ? 0.0 : 1.0);
					}
					runtime.setOption("water_reflection_quality", repeat == 0 ? "off" : "baseline");
					var toggledGeometry = runtime.worldGeometry();
					toggledGeometry.beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);
					var toggledWater = toggledGeometry.waterPipeline(original).orElseThrow();
					if (!gpu.precompileLinearWorldPipeline(toggledWater, null).isValid()) {
						throw new AssertionError("Water controls failed to compile after toggle");
					}
				}
				// With water enabled, changing both atlas RGB and alpha must leave a water-only
				// pixel unchanged. The same atlas change must still affect a glass-only pixel.
				var activeGeometry = runtime.worldGeometry();
				activeGeometry.beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);
				var activeWater = activeGeometry.waterPipeline(original).orElseThrow();
				var activePipeline = ((MetalCompiledRenderPipeline)gpu.precompileLinearWorldPipeline(
					activeWater, null)).metal(false, MetalTexture.Format.RGBA16_FLOAT);
				writeDraw(baselineDraw, 0);
				draw(queue, activePipeline, baseline, atlasView, lightmapView, sampler, vertices, indices,
					metadata, projection, section, globals, fog, baselineDraw, waterFrame,
					opaqueColorView, opaqueDepthView);
				draw(queue, activePipeline, identity, alternateAtlasView, lightmapView, sampler, vertices, indices,
					metadata, projection, section, globals, fog, baselineDraw, waterFrame,
					opaqueColorView, opaqueDepthView);
				ByteBuffer firstAtlasPixels = baseline.readback(queue, 0).order(ByteOrder.nativeOrder());
				ByteBuffer alternateAtlasPixels = identity.readback(queue, 0).order(ByteOrder.nativeOrder());
				for (int y = 0; y < HEIGHT; y++) {
					assertSamePixel(firstAtlasPixels, alternateAtlasPixels, 0, y, "enabled water ignores atlas");
					assertDifferentPixel(firstAtlasPixels, alternateAtlasPixels, 7, y, "glass retains atlas");
				}
				// Compare the full fragment against the frozen pre-optimization atlas path. Nonuniform
				// mips and adjacent water/glass pixels expose changed derivatives or texture LODs.
				try (var patterned = gpu.metal().createTexture(new MetalTexture.Descriptor(
						MetalTexture.Format.RGBA16_FLOAT, 8, 8, 4));
					 var patternedView = patterned.createView();
					 var disabledPipeline = gpu.metal().createRenderPipeline(disabledDescriptor)) {
					for (int mip = 0; mip < 4; mip++) {
						int size = 8 >> mip;
						ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 8).order(ByteOrder.nativeOrder());
						for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
							for (float channel : new float[]{0.15F + x * 0.07F, 0.2F + y * 0.08F,
								0.25F + mip * 0.15F, 0.35F + ((x + y) % 2) * 0.5F}) {
								pixels.putShort(Float.floatToFloat16(channel));
							}
						}
						patterned.upload(queue, mip, pixels.flip());
					}
					try (var mapping = section.map()) {
						mapping.bytes().order(ByteOrder.nativeOrder()).putInt(72, 8).putInt(76, 8);
					}
					for (var checked : List.of(activePipeline, disabledPipeline)) {
						try (var reference = atlasReference(gpu.metal(), checked)) {
							for (int rgss = 0; rgss <= 1; rgss++) {
								try (var mapping = globals.map()) { mapping.bytes().putInt(52, rgss); }
								for (int debug = 0; debug <= 8; debug++) {
									writeDraw(baselineDraw, debug);
									draw(queue, checked, baseline, patternedView, lightmapView, sampler, vertices,
										indices, metadata, projection, section, globals, fog, baselineDraw,
										waterFrame, opaqueColorView, opaqueDepthView);
									draw(queue, reference, identity, patternedView, lightmapView, sampler, vertices,
										indices, metadata, projection, section, globals, fog, baselineDraw,
										waterFrame, opaqueColorView, opaqueDepthView);
									ByteBuffer actual = baseline.readback(queue, 0).order(ByteOrder.nativeOrder());
									ByteBuffer expected = identity.readback(queue, 0).order(ByteOrder.nativeOrder());
									for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) {
										assertSamePixel(actual, expected, x, y,
											"atlas reference, RGSS=" + rgss + " debug=" + debug);
									}
								}
							}
						}
					}
				}
				try (var mapping = globals.map()) { mapping.bytes().putInt(52, 0); }
				try (var mapping = section.map()) { mapping.bytes().putInt(72, 1).putInt(76, 1); }
				writeDraw(baselineDraw, 0);
				if (Float.float16ToFloat(normalPixels.getShort(0)) <= 1.0F) {
					throw new AssertionError("Forward-water baseline did not preserve HDR above 1");
				}

				// Put water at view z=-1 and the opaque shelf at z=-1.2. Use a reverse-Z
				// orthographic projection so every water-only pixel has shallow contact foam.
				try (var mapping = section.map()) {
					mapping.bytes().order(ByteOrder.nativeOrder()).putFloat(56, -1.5F);
				}
				try (var mapping = projection.map()) {
					mapping.bytes().order(ByteOrder.nativeOrder()).putFloat(40, 0.5F).putFloat(56, 1);
				}
				try (var mapping = waterFrame.map()) {
					ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
					for (int i = 0; i < bytes.capacity(); i++) bytes.put(i, (byte)0);
					identity(bytes);
					bytes.putFloat(40, 0.5F).putFloat(56, 1);
					identity(bytes.slice(64, 64));
					bytes.putFloat(104, 2).putFloat(120, -2).putInt(168, 1);
				}
				try (var commands = queue.createCommandBuffer()) {
					try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
						List.of(MetalRenderPass.ColorAttachment.clear(opaqueColor, 0, 0, 0, 1)),
						new MetalRenderPass.DepthAttachment(opaqueDepth, 0, 0,
							MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0.4), 1))) { }
					commands.commitAndWait();
				}
				float[][] lighting = {{0, 0, 0}, {0.1F, 0.1F, 0.1F}, {1, 1, 1}, {0.9F, 0.4F, 0.1F}};
				ByteBuffer[] foamPixels = new ByteBuffer[lighting.length];
				for (int sample = 0; sample < lighting.length; sample++) {
					float[] light = lighting[sample];
					lightmap.upload(queue, 0, halfPixel(light[0], light[1], light[2], 1));
					draw(queue, activePipeline, baseline, atlasView, lightmapView, sampler, vertices, indices,
						metadata, projection, section, globals, fog, baselineDraw, waterFrame,
						opaqueColorView, opaqueDepthView);
					foamPixels[sample] = baseline.readback(queue, 0).order(ByteOrder.nativeOrder());
				}
				for (int y = 0; y < HEIGHT; y++) {
					for (int c = 0; c < 3; c++) {
						float dark = Float.float16ToFloat(foamPixels[0].getShort(y * WIDTH * 8 + c * 2));
						if (Math.abs(dark) > 0.00001F) {
							throw new AssertionError("Unlit shallow water emits light: " + dark);
						}
					}
					// The water's red biome tint contributes no green/blue: these isolate foam.
					for (int c = 1; c < 3; c++) {
						int offset = y * WIDTH * 8 + c * 2;
						float night = Float.float16ToFloat(foamPixels[1].getShort(offset));
						float day = Float.float16ToFloat(foamPixels[2].getShort(offset));
						float torch = Float.float16ToFloat(foamPixels[3].getShort(offset));
						if (!(day > 0.005F && night > 0 && night < day * 0.02F
							&& torch > 0 && torch < day)) {
							throw new AssertionError("Foam ignores scene lighting: day=" + day
								+ " night=" + night + " torch=" + torch);
						}
					}
					int pixel = y * WIDTH * 8;
					if (Float.float16ToFloat(foamPixels[3].getShort(pixel + 2))
						<= Float.float16ToFloat(foamPixels[3].getShort(pixel + 4)) * 5) {
						throw new AssertionError("Contact foam lost warm block-light color");
					}
				}
			}
		} finally {
			gpu.close();
		}
		System.out.println("Water forward: actual adapter pipeline, BLOCK ABI, nonzero base vertex, mixed identity, sorted indices, atlas-independent enabled water, dark/night/day/block-lit foam, debug toggle and HDR blend passed");
	}

	private static void draw(final MetalCommandQueue queue, final MetalRenderPipeline pipeline,
		final MetalTexture target, final MetalTextureView atlas, final MetalTextureView lightmap,
		final MetalSampler sampler, final MetalBuffer vertices, final MetalBuffer indices,
		final MetalBuffer metadata, final MetalBuffer projection, final MetalBuffer section,
		final MetalBuffer globals, final MetalBuffer fog, final MetalBuffer waterDraw,
		final MetalBuffer waterFrame, final MetalTextureView opaqueColor, final MetalTextureView opaqueDepth) {
		try (var commands = queue.createCommandBuffer();
			 var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				 MetalRenderPass.ColorAttachment.clear(target, 0, 0, 0, 0)))) {
			pass.setPipeline(pipeline);
			pass.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, vertices, 0);
			pass.setUniformBuffer(0, globals, 0, STAGES);
			pass.setUniformBuffer(1, fog, 0, STAGES);
			pass.setUniformBuffer(2, projection, 0, STAGES);
			pass.setUniformBuffer(3, section, 0, STAGES);
			pass.setTexture(4, atlas, STAGES);
			pass.setSampler(4, sampler, STAGES);
			pass.setTexture(5, lightmap, STAGES);
			pass.setSampler(5, sampler, STAGES);
			pass.setUniformBuffer(13, waterFrame, 0, STAGES);
			pass.setTexture(12, opaqueColor, MetalRenderPass.STAGE_FRAGMENT);
			pass.setTexture(13, opaqueDepth, MetalRenderPass.STAGE_FRAGMENT);
			pass.setUniformBuffer(14, metadata, 0, MetalRenderPass.STAGE_VERTEX);
			pass.setUniformBuffer(15, waterDraw, 0, STAGES);
			pass.drawIndexed(MetalRenderPass.Primitive.TRIANGLE, indices, 0,
				MetalRenderPass.IndexType.UINT16, 12, 1, BASE_VERTEX, 0);
			pass.close();
			commands.commitAndWait();
		}
	}

	private static MetalRenderPipeline atlasReference(MetalDevice device, MetalRenderPipeline pipeline) {
		try {
			String[] frozen = Files.readString(Path.of("src/smoke/resources/water-atlas-reference.metal"))
				.split("// MC_REFERENCE_ATLAS_FRAGMENT\\n", 2);
			var descriptor = pipeline.descriptor();
			String source = descriptor.fragmentSource();
			int helperStart = source.indexOf("static inline float4 mc_sample_rgss(");
			int helperEnd = source.indexOf("#ifdef MC_WATER_FORWARD\nfragment float4 gbuffer_terrain_fragment", helperStart);
			if (helperStart < 0 || helperEnd < 0 || frozen.length != 2) {
				throw new AssertionError("Could not locate terrain atlas helper fixture");
			}
			source = source.substring(0, helperStart) + frozen[0] + source.substring(helperEnd);
			int atlasStart = source.indexOf("    float2 pixelSize = 1.0 / float2(section.TextureSize);");
			int atlasEnd = source.indexOf("    // The visibility fade changes alpha", atlasStart);
			int conditionStart = source.indexOf("    bool shadeWater =", atlasStart);
			int conditionEnd = source.indexOf(';', conditionStart) + 1;
			if (atlasStart < 0 || atlasEnd < 0 || conditionStart < 0 || conditionEnd <= conditionStart) {
				throw new AssertionError("Could not locate terrain atlas branch fixture");
			}
			String condition = "#ifdef MC_WATER_FORWARD\n" + source.substring(conditionStart, conditionEnd)
				+ "\n#endif\n";
			source = source.substring(0, atlasStart) + condition + frozen[1] + source.substring(atlasEnd);
			return device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source, descriptor.vertexFunction(),
				source, descriptor.fragmentFunction(), descriptor.colorTargets(), descriptor.depthStencilFormat(),
				descriptor.vertexDescriptor(), descriptor.depthState(), descriptor.rasterState(), descriptor.inputPrimitiveTopology()));
		} catch (IOException error) {
			throw new AssertionError("Could not load frozen atlas reference", error);
		}
	}

	private static void writeVertices(final MetalBuffer vertices) {
		try (var mapping = vertices.map()) {
			ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			for (int vertex = 0; vertex < BASE_VERTEX; vertex++) putVertex(bytes, vertex, 0, 0, 0, 0);
			// Water spans x=-1..0.5 and glass spans x=-0.5..1, leaving a four-pixel overlap.
			putVertex(bytes, 2, -1, -1, 0x800000FF, 0);
			putVertex(bytes, 3, 0.5F, -1, 0x800000FF, 1);
			putVertex(bytes, 4, 0.5F, 1, 0x800000FF, 1);
			putVertex(bytes, 5, -1, 1, 0x800000FF, 0);
			putVertex(bytes, 6, -0.5F, -1, 0x8000FF00, 0);
			putVertex(bytes, 7, 1, -1, 0x8000FF00, 1);
			putVertex(bytes, 8, 1, 1, 0x8000FF00, 1);
			putVertex(bytes, 9, -0.5F, 1, 0x8000FF00, 0);
		}
	}

	private static void putVertex(final ByteBuffer bytes, final int vertex, final float x, final float y,
		final int rgba, final float u) {
		int offset = vertex * VERTEX_STRIDE;
		bytes.putFloat(offset, x).putFloat(offset + 4, y).putFloat(offset + 8, 0.5F);
		bytes.putInt(offset + 12, rgba);
		bytes.putFloat(offset + 16, u).putFloat(offset + 20, y > 0 ? 1 : 0);
		bytes.putShort(offset + 24, (short)240).putShort(offset + 26, (short)240);
	}

	private static void writeSortedIndices(final MetalBuffer indices) {
		try (var mapping = indices.map()) {
			// Relative to BASE_VERTEX. Glass is the farther sorted quad; water must blend over it.
			mapping.bytes().order(ByteOrder.nativeOrder()).asShortBuffer().put(new short[]{
				4, 5, 6, 6, 7, 4,
				0, 1, 2, 2, 3, 0
			});
		}
	}

	private static void writeMetadata(final MetalBuffer metadata) {
		try (var mapping = metadata.map()) {
			ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			for (int vertex = 0; vertex < DRAW_VERTICES; vertex++) {
				int offset = vertex * 32;
				bytes.putFloat(offset, 0).putFloat(offset + 4, 1).putFloat(offset + 8, 0)
					.putFloat(offset + 12, vertex < 4 ? 1 : 0);
				bytes.putFloat(offset + 16, vertex < 4 ? 0.25F : 0)
					.putFloat(offset + 20, 0).putFloat(offset + 24, vertex < 4 ? -0.5F : 0)
					.putFloat(offset + 28, 0);
			}
		}
	}

	private static void writeUniforms(final MetalBuffer projection, final MetalBuffer section,
		final MetalBuffer globals, final MetalBuffer fog) {
		for (MetalBuffer buffer : List.of(projection, section, globals, fog)) {
			try (var mapping = buffer.map()) {
				ByteBuffer bytes = mapping.bytes();
				for (int i = 0; i < bytes.capacity(); i++) bytes.put(i, (byte)0);
			}
		}
		try (var mapping = projection.map()) { identity(mapping.bytes()); }
		try (var mapping = section.map()) {
			ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			identity(bytes);
			bytes.putFloat(64, 1).putInt(72, 1).putInt(76, 1);
		}
		try (var mapping = fog.map()) {
			ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			for (int i = 0; i < 6; i++) bytes.putFloat(16 + i * 4, i % 2 == 0 ? 100 : 200);
		}
	}

	private static void writeDraw(final MetalBuffer draw, final int debugIdentity) {
		try (var mapping = draw.map()) {
			mapping.bytes().order(ByteOrder.nativeOrder()).putInt(BASE_VERTEX).putInt(DRAW_VERTICES)
				.putInt(debugIdentity).putInt(0);
		}
	}

	private static void identity(final ByteBuffer bytes) {
		bytes.order(ByteOrder.nativeOrder());
		for (int i = 0; i < 4; i++) bytes.putFloat(i * 20, 1);
	}

	private static ByteBuffer halfPixel(final float red, final float green, final float blue, final float alpha) {
		ByteBuffer bytes = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
		for (float value : new float[]{red, green, blue, alpha}) bytes.putShort(Float.floatToFloat16(value));
		return bytes.flip();
	}

	private static double decode(final double value) {
		return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
	}

	private static void assertRgb(final ByteBuffer pixels, final int x, final int y,
		final double red, final double green, final double blue, final String label) {
		int offset = (y * WIDTH + x) * 8;
		double[] expected = {red, green, blue};
		for (int channel = 0; channel < 3; channel++) {
			double actual = Float.float16ToFloat(pixels.getShort(offset + channel * 2));
			if (!Double.isFinite(actual) || Math.abs(actual - expected[channel]) > 0.04) {
				throw new AssertionError(label + " at " + x + "," + y + " channel " + channel
					+ " expected=" + expected[channel] + " actual=" + actual);
			}
		}
	}

	private static void assertSamePixel(final ByteBuffer first, final ByteBuffer second,
		final int x, final int y, final String label) {
		int offset = (y * WIDTH + x) * 8;
		for (int channel = 0; channel < 4; channel++) {
			if (first.getShort(offset + channel * 2) != second.getShort(offset + channel * 2)) {
				throw new AssertionError(label + " at " + x + "," + y + " channel " + channel);
			}
		}
	}

	private static void assertDifferentPixel(final ByteBuffer first, final ByteBuffer second,
		final int x, final int y, final String label) {
		int offset = (y * WIDTH + x) * 8;
		for (int channel = 0; channel < 4; channel++) {
			if (first.getShort(offset + channel * 2) != second.getShort(offset + channel * 2)) return;
		}
		throw new AssertionError(label + " at " + x + "," + y);
	}
}
