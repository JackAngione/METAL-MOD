package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalSampler;
import dev.metalcraft.client.metal.MetalTexture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Draws production Standard geometry into stored HDR/MRT attachments; no live routing claim. */
final class StandardGeometryHdrSmoke {
	private StandardGeometryHdrSmoke() { }

	static void run(final MetalDevice device) {
		for (String program : List.of("terrain", "block", "entity")) {
			for (boolean linear : new boolean[]{false, true}) {
				for (int scenario = 0; scenario < 5; scenario++) draw(device, program, linear, scenario);
			}
		}
		System.out.println("Standard geometry HDR: actual terrain/block/entity draws, seed transfer, fog, cutout and metadata passed");
	}

	private static void draw(final MetalDevice device, final String program, final boolean linear, final int scenario) {
		boolean terrain = program.equals("terrain"), entity = program.equals("entity");
		String source = "#include <metal_stdlib>\nusing namespace metal;\n"
			+ "#define MC_PASS_GBUFFER 1\n#define MC_PROGRAM_" + program.toUpperCase(java.util.Locale.ROOT) + " 1\n"
			+ (linear ? "#define MC_SCENE_LINEAR_HDR 1\n" : "")
			+ """
			#define MC_TARGET_SCENE 0
			#define MC_TARGET_GBUFFER_ALBEDO 1
			#define MC_TARGET_GBUFFER_NORMAL 2
			#define MC_TARGET_GBUFFER_LIGHT 3
			#define MC_SLOT_PROJECTION 0
			#define MC_SLOT_TRANSFORMS 1
			#define MC_SLOT_GLOBALS 2
			#define MC_SLOT_FOG 3
			#define MC_SLOT_SAMPLER0 0
			#define MC_SLOT_SAMPLER2 2
			#define MC_HAS_ALPHA_CUTOUT 1
			#define MC_ALPHA_CUTOUT 0.3
			#define MC_MATERIAL 0
			#define MC_DEFINE_NO_CARDINAL_LIGHTING 1
			#define MC_DEFINE_NO_OVERLAY 1
			"""
			+ resource("shared/shadows.metal")
			+ resource("shared/lighting.metal").replace("#include \"shared/color.metal\"", resource("shared/color.metal"))
			+ resource("gbuffer.metal");
		var attributes = new ArrayList<MetalRenderPipeline.VertexAttribute>();
		attributes.add(attribute(0, 0, MetalRenderPipeline.VertexAttributeFormat.FLOAT3));
		attributes.add(attribute(1, 12, MetalRenderPipeline.VertexAttributeFormat.FLOAT4));
		attributes.add(attribute(2, 28, MetalRenderPipeline.VertexAttributeFormat.FLOAT2));
		attributes.add(attribute(3, 36, MetalRenderPipeline.VertexAttributeFormat.SHORT2));
		if (entity) {
			attributes.add(attribute(4, 40, MetalRenderPipeline.VertexAttributeFormat.SHORT2));
			attributes.add(attribute(5, 44, MetalRenderPipeline.VertexAttributeFormat.FLOAT4));
		}
		var format = MetalTexture.Format.RGBA16_FLOAT;
		var metadata = MetalTexture.Format.RGBA8_UNORM;
		try (var queue = device.createCommandQueue();
			 var scene = device.createTexture(new MetalTexture.Descriptor(format, 4, 4, 1));
			 var albedo = device.createTexture(new MetalTexture.Descriptor(metadata, 4, 4, 1));
			 var normal = device.createTexture(new MetalTexture.Descriptor(metadata, 4, 4, 1));
			 var light = device.createTexture(new MetalTexture.Descriptor(metadata, 4, 4, 1));
			 var atlas = device.createTexture(new MetalTexture.Descriptor(format, 1, 1, 1));
			 var lightmap = device.createTexture(new MetalTexture.Descriptor(format, 1, 1, 1));
			 var atlasView = atlas.createView(); var lightView = lightmap.createView();
			 var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,
				 MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 var vertices = device.createBuffer(192, MetalBuffer.StorageMode.SHARED);
			 var projection = device.createBuffer(64, MetalBuffer.StorageMode.SHARED);
			 var transforms = device.createBuffer(176, MetalBuffer.StorageMode.SHARED);
			 var globals = device.createBuffer(64, MetalBuffer.StorageMode.SHARED);
			 var fog = device.createBuffer(48, MetalBuffer.StorageMode.SHARED);
			 var pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source,
				 "gbuffer_" + program + "_vertex", source, "gbuffer_" + program + "_fragment",
				 List.of(MetalRenderPipeline.ColorTarget.opaque(format), MetalRenderPipeline.ColorTarget.opaque(metadata),
					 MetalRenderPipeline.ColorTarget.opaque(metadata), MetalRenderPipeline.ColorTarget.opaque(metadata)), null,
				 new MetalRenderPipeline.VertexDescriptor(attributes, List.of(new MetalRenderPipeline.VertexBufferLayout(16, 64, 0))),
				 MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT))) {
			for (var buffer : List.of(vertices, projection, transforms, globals, fog)) {
				try (var mapping = buffer.map()) {
					var bytes = mapping.bytes().order(ByteOrder.nativeOrder());
					for (int i = 0; i < bytes.capacity(); i++) bytes.put(i, (byte)0);
				}
			}
			try (var mapping = vertices.map()) {
				var bytes = mapping.bytes().order(ByteOrder.nativeOrder());
				for (int i = 0; i < 3; i++) {
					int offset = i * 64;
					bytes.putFloat(offset, i == 1 ? 3 : -1).putFloat(offset + 4, i == 2 ? 3 : -1).putFloat(offset + 8, 0.5F);
					for (int c = 0; c < 4; c++) bytes.putFloat(offset + 12 + c * 4, 1);
					bytes.putFloat(offset + 28, i == 1 ? 1 : 0).putFloat(offset + 32, i == 2 ? 1 : 0);
					bytes.putShort(offset + 36, (short)240).putShort(offset + 38, (short)240);
					bytes.putShort(offset + 40, (short)240).putShort(offset + 42, (short)240);
					bytes.putFloat(offset + 52, 1);
				}
			}
			try (var mapping = projection.map()) { identity(mapping.bytes()); }
			float visibility = scenario == 2 ? 0.25F : scenario == 4 ? 0.1F : 1;
			try (var mapping = transforms.map()) {
				var bytes = mapping.bytes().order(ByteOrder.nativeOrder());
				identity(bytes);
				if (terrain) bytes.putFloat(64, visibility).putInt(72, 1).putInt(76, 1);
				else for (int c = 0; c < 4; c++) bytes.putFloat(64 + c * 4, 1);
			}
			try (var mapping = fog.map()) {
				var bytes = mapping.bytes().order(ByteOrder.nativeOrder());
				bytes.putFloat(0, 0.5F).putFloat(4, 0.25F).putFloat(8, 0.75F).putFloat(12, 0.5F);
				for (int i = 0; i < 2; i++) bytes.putFloat(16 + i * 8, scenario == 1 ? -2 : 100)
					.putFloat(20 + i * 8, scenario == 1 ? -1 : 200);
			}
			float alpha = scenario == 3 ? 0.125F : 0.5F;
			atlas.upload(queue, 0, pixel(0.5F, 0.25F, 0.03125F, alpha));
			lightmap.upload(queue, 0, pixel(4, 2, 1, 1));
			try (var commands = queue.createCommandBuffer()) {
				try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(List.of(
					MetalRenderPass.ColorAttachment.clear(scene, 0, 0, 0, 0),
					MetalRenderPass.ColorAttachment.clear(albedo, 0, 0, 0, 0),
					MetalRenderPass.ColorAttachment.clear(normal, 0, 0, 0, 0),
					MetalRenderPass.ColorAttachment.clear(light, 0, 0, 0, 0)), null, 0))) {
					pass.setPipeline(pipeline);
					pass.setVertexBuffer(16, vertices, 0);
					int stages = MetalRenderPass.STAGE_VERTEX | MetalRenderPass.STAGE_FRAGMENT;
					pass.setUniformBuffer(0, projection, 0, stages);
					pass.setUniformBuffer(1, transforms, 0, stages);
					pass.setUniformBuffer(2, globals, 0, stages);
					pass.setUniformBuffer(3, fog, 0, stages);
					pass.setTexture(0, atlasView, stages); pass.setSampler(0, sampler, stages);
					pass.setTexture(2, lightView, stages); pass.setSampler(2, sampler, stages);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
				}
				commands.commitAndWait();
			}
			var pixels = scene.readback(queue, 0).order(ByteOrder.nativeOrder());
			var material = albedo.readback(queue, 0);
			double[] seed = {2, 0.5, 0.03125}, fogColor = {0.5, 0.25, 0.75};
			for (int p = 0; p < 16; p++) {
				for (int c = 0; c < 4; c++) {
					double expected = alpha;
					if (c < 3) {
						expected = linear ? decode(seed[c]) : seed[c];
						double fogValue = linear ? decode(fogColor[c]) : fogColor[c];
						if (terrain && (scenario == 2 || scenario == 4)) expected = expected * visibility + fogValue * (1 - visibility);
						if (scenario == 1) expected = (expected + fogValue) * 0.5;
					} else if (terrain) expected *= 0.5 + 0.5 * visibility;
					if (scenario == 3 || terrain && scenario == 4) expected = 0;
					double actual = Float.float16ToFloat(pixels.getShort((p * 4 + c) * 2));
					if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.006) {
						throw new AssertionError(program + " linear=" + linear + " scenario=" + scenario + " pixel=" + p
							+ " channel=" + c + " expected=" + expected + " actual=" + actual);
					}
				}
				int[] expectedMetadata = (scenario == 3 || terrain && scenario == 4) ? new int[4] : new int[]{128, 64, 8, 1};
				for (int c = 0; c < 4; c++) if (Math.abs((material.get(p * 4 + c) & 255) - expectedMetadata[c]) > 1) {
					throw new AssertionError("Encoded albedo/material changed for " + program);
				}
			}
		}
	}

	private static MetalRenderPipeline.VertexAttribute attribute(final int location, final int offset,
		final MetalRenderPipeline.VertexAttributeFormat format) {
		return new MetalRenderPipeline.VertexAttribute(location, 16, offset, format);
	}
	private static void identity(final ByteBuffer bytes) {
		bytes.order(ByteOrder.nativeOrder());
		for (int i = 0; i < 4; i++) bytes.putFloat(i * 20, 1);
	}
	private static ByteBuffer pixel(final float... values) {
		var bytes = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
		for (float value : values) bytes.putShort(Float.floatToFloat16(value));
		return bytes.flip();
	}
	private static double decode(final double value) {
		return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
	}
	private static String resource(final String path) {
		try (var input = StandardGeometryHdrSmoke.class.getResourceAsStream("/assets/metalcraft/shaderpacks/standard/" + path)) {
			if (input == null) throw new IOException("Missing " + path);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) { throw new AssertionError(error); }
	}
}
