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
import java.util.List;

/** Numeric reference for W2; this fixture does not establish live world HDR routing. */
final class HdrCompositionSmoke {
	private HdrCompositionSmoke() { }

	static void run(final MetalDevice device) {
		StandardGeometryHdrSmoke.run(device);
		verifyOpaqueColor(device, false);
		verifyOpaqueColor(device, true);
		String color = resource("shared/color.metal");
		String source = "#include <metal_stdlib>\nusing namespace metal;\n" + color + """
			vertex float4 vs(uint id [[vertex_id]]) {
			    return float4(id == 1 ? 3.0 : -1.0, id == 2 ? 3.0 : -1.0, 0, 1);
			}
			fragment float4 opaque() { return float4(4, 2, 0.18, 0.4); }
			fragment float4 forwardA(float4 p [[position]]) {
			    const float coverage[4] = {0, 0.25, 0.5, 1};
			    float3 fog = mc_srgb_to_linear(float3(0.5, 0.25, 1));
			    return float4(mix(float3(2, 0.25, 0.5), fog, 0.35), coverage[uint(p.x) % 4]);
			}
			fragment float4 forwardB(float4 p [[position]]) {
			    return float4(0.1, 3, 1, uint(p.x) >= 4 ? 0.5 : 0.0);
			}
			""";
		var blend = new MetalRenderPipeline.BlendState(
			MetalRenderPipeline.BlendFactor.SOURCE_ALPHA, MetalRenderPipeline.BlendFactor.ONE_MINUS_SOURCE_ALPHA,
			MetalRenderPipeline.BlendOperation.ADD, MetalRenderPipeline.BlendFactor.ONE,
			MetalRenderPipeline.BlendFactor.ONE_MINUS_SOURCE_ALPHA, MetalRenderPipeline.BlendOperation.ADD);
		var transparent = new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA16_FLOAT,
			MetalRenderPipeline.WRITE_ALL, blend);
		try (var queue = device.createCommandQueue();
			 var scene = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 8, 1, 1));
			 var output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.BGRA8_UNORM, 8, 1, 1));
			 var sceneView = scene.createView();
			 var options = device.createBuffer(60, MetalBuffer.StorageMode.SHARED);
			 var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,
				MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 var opaque = pipeline(device, source, "vs", "opaque", MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT));
			 var forwardA = pipeline(device, source, "vs", "forwardA", transparent);
			 var forwardB = pipeline(device, source, "vs", "forwardB", transparent)) {
			// Separate encoders exercise STORE/LOAD, not just arithmetic within one fragment.
			try (var commands = queue.createCommandBuffer()) {
				for (var draw : List.of(opaque, forwardA, forwardB)) {
					var attachment = draw == opaque ? MetalRenderPass.ColorAttachment.clear(scene, 0, 0, 0, 0)
						: new MetalRenderPass.ColorAttachment(scene, MetalRenderPass.LoadAction.LOAD,
							MetalRenderPass.StoreAction.STORE, 0, 0, 0, 0);
					try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(attachment))) {
						pass.setPipeline(draw);
						pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
					}
				}
				commands.commitAndWait();
			}
			double[][] expected = referenceComposition();
			ByteBuffer hdr = scene.readback(queue, 0).order(ByteOrder.nativeOrder());
			for (int x = 0; x < 8; x++) {
				for (int c = 0; c < 4; c++) {
					check("HDR overlap " + x + "/" + c, expected[x][c],
						Float.float16ToFloat(hdr.getShort((x * 4 + c) * 2)), 0.005);
				}
			}
			for (boolean invert : new boolean[]{false, true}) {
				String gradeSource = "#define MC_PASS_GRADE 1\n#define MC_SCENE_LINEAR_HDR 1\n#define MC_TEX_SCENE 0\n"
					+ "#define MC_OPTION_INVERT " + (invert ? 1 : 0) + "\n" + resource("shared/options.metal")
					+ resource("grade.metal").replace("#include \"shared/color.metal\"", color)
						.replace("#include \"shared/post_effects.metal\"", resource("shared/post_effects.metal"));
				try (var grade = pipeline(device, gradeSource, "grade_vertex", "grade_fragment",
					MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.BGRA8_UNORM))) {
					for (int tonemap : new int[]{0, 1}) {
						for (float exposure : new float[]{0.5F, 1.0F, 2.0F}) {
							try (var mapping = options.map()) {
								mapping.bytes().order(ByteOrder.nativeOrder()).putFloat(0, exposure)
									.putInt(4, tonemap).putInt(8, 0).putFloat(12, 1)
									.putFloat(16, 0).putFloat(20, 0).putFloat(24, 1).putFloat(28, 1)
									.putFloat(32, 0).putFloat(36, 1).putFloat(40, 0).putFloat(44, 0)
									.putInt(48, 0).putInt(52, 0).putInt(56, 0);
							}
							try (var commands = queue.createCommandBuffer()) {
								try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
									MetalRenderPass.ColorAttachment.clear(output, 0, 0, 0, 0)))) {
									pass.setPipeline(grade);
									pass.setTexture(0, sceneView, MetalRenderPass.STAGE_FRAGMENT);
									pass.setSampler(0, sampler, MetalRenderPass.STAGE_FRAGMENT);
									pass.setUniformBuffer(0, options, 0, MetalRenderPass.STAGE_FRAGMENT);
									pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
								}
								commands.commitAndWait();
							}
							ByteBuffer pixels = output.readback(queue, 0);
							for (int x = 0; x < 8; x++) {
								for (int c = 0; c < 3; c++) {
									double linear = expected[x][c] * exposure;
									if (tonemap == 1) linear = clamp((linear * (2.51 * linear + 0.03))
										/ (linear * (2.43 * linear + 0.59) + 0.14));
									double encoded = linear <= 0.0031308 ? 12.92 * linear
										: 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
									if (invert) encoded = 1 - encoded;
									check("grade " + tonemap + "/" + exposure + "/" + invert + " pixel " + x + "/" + c,
										clamp(encoded), (pixels.get(x * 4 + (2 - c)) & 255) / 255.0, 2.0 / 255);
								}
								check("grade alpha", 255, pixels.get(x * 4 + 3) & 255, 0);
							}
						}
					}
				}
			}
		}
		System.out.println("HDR composition: stored HDR, linear fog, two forward overlaps, coverage alpha and Standard output transfer passed");
	}

	private static void verifyOpaqueColor(final MetalDevice device, final boolean linear) {
		String source = "#include <metal_stdlib>\nusing namespace metal;\n"
			+ (linear ? "#define MC_SCENE_LINEAR_HDR 1\n" : "")
			+ resource("shared/shadows.metal")
			+ resource("shared/lighting.metal").replace("#include \"shared/color.metal\"", resource("shared/color.metal"))
			+ """
			constant McFog fixtureFog = {float4(0.5, 0.25, 0.75, 0.6), 0, 10, 0, 10, 10, 10};
			vertex float4 seedVertex(uint id [[vertex_id]]) {
			    return float4(id == 1 ? 3.0 : -1.0, id == 2 ? 3.0 : -1.0, 0, 1);
			}
			fragment float4 seedFragment(float4 p [[position]]) {
			    uint test = uint(p.x);
			    float4 seed = mc_scene_seed(float4(2, 0.5, 0.02, 0.4));
			    if (test == 0) return seed;
			    if (test == 1) return mc_chunk_fade(seed, 0.25, fixtureFog);
			    float4 fogged = mc_apply_fog(seed, 5, 5, fixtureFog);
			    if (test == 2) return fogged;
			    if (test == 3) return float4(mc_unfog(fogged.rgb, fixtureFog, 0.3), fogged.a);
			    return float4(mc_unfog(fogged.rgb, fixtureFog, 1.0), fogged.a);
			}
			""";
		try (var queue = device.createCommandQueue();
			 var target = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 5, 1, 1));
			 var program = pipeline(device, source, "seedVertex", "seedFragment",
				MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT))) {
			try (var commands = queue.createCommandBuffer()) {
				try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					MetalRenderPass.ColorAttachment.clear(target, 0, 0, 0, 0)))) {
					pass.setPipeline(program);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
				}
				commands.commitAndWait();
			}
			ByteBuffer pixels = target.readback(queue, 0).order(ByteOrder.nativeOrder());
			double[] seed = {2, 0.5, 0.02}, fog = {0.5, 0.25, 0.75};
			for (int x = 0; x < 5; x++) {
				for (int c = 0; c < 4; c++) {
					double expected;
					if (c == 3) expected = x == 1 ? 0.4 * (0.6 * 0.75 + 0.25) : 0.4;
					else {
						double a = linear ? decode(seed[c]) : seed[c];
						double b = linear ? decode(fog[c]) : fog[c];
						expected = switch (x) {
							case 1 -> a * 0.25 + b * 0.75;
							case 2 -> a * 0.7 + b * 0.3;
							case 4 -> b;
							default -> a;
						};
					}
					check("opaque color " + linear + "/" + x + "/" + c, expected,
						Float.float16ToFloat(pixels.getShort((x * 4 + c) * 2)), 0.006);
				}
			}
		}
	}

	private static double decode(final double value) {
		return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
	}

	private static double[][] referenceComposition() {
		double[][] result = new double[8][4];
		double[] base = {4, 2, 0.18}, surface = {2, 0.25, 0.5}, fog = {0.5, 0.25, 1}, second = {0.1, 3, 1};
		double[] coverage = {0, 0.25, 0.5, 1};
		for (int x = 0; x < 8; x++) {
			double a = coverage[x % 4], b = x >= 4 ? 0.5 : 0;
			for (int c = 0; c < 3; c++) {
				double fogLinear = Math.pow((fog[c] + 0.055) / 1.055, 2.4);
				double lit = surface[c] * 0.65 + fogLinear * 0.35;
				result[x][c] = (base[c] * (1 - a) + lit * a) * (1 - b) + second[c] * b;
			}
			result[x][3] = (0.4 * (1 - a) + a) * (1 - b) + b;
		}
		return result;
	}

	private static MetalRenderPipeline pipeline(final MetalDevice device, final String source,
		final String vertex, final String fragment, final MetalRenderPipeline.ColorTarget target) {
		return device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source, vertex, source, fragment,
			List.of(target), null, MetalRenderPipeline.VertexDescriptor.EMPTY,
			MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT));
	}

	private static double clamp(final double value) { return Math.max(0, Math.min(1, value)); }

	private static void check(final String label, final double expected, final double actual, final double tolerance) {
		if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance) {
			throw new AssertionError(label + ": expected " + expected + ", got " + actual);
		}
	}

	private static String resource(final String path) {
		try (var input = HdrCompositionSmoke.class.getResourceAsStream("/assets/metalcraft/shaderpacks/standard/" + path)) {
			if (input == null) throw new IOException("Missing " + path);
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new AssertionError(error);
		}
	}
}
