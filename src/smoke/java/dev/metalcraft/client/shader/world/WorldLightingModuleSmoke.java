package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.shader.SceneColor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** GPU layout check for the lighting/fog frame uploaded to resolve. */
public final class WorldLightingModuleSmoke {
	private WorldLightingModuleSmoke() {
	}

	public static void run(final MetalDevice device) {
		assertHostTransfer();
		assertMappedFrameLayout();
		String shadows;
		String lighting;
		try {
			shadows = resource("/assets/metalcraft/shaderpacks/standard/shared/shadows.metal");
			lighting = resource("/assets/metalcraft/shaderpacks/standard/shared/lighting.metal");
		} catch (IOException error) {
			throw new AssertionError(error);
		}
		String source = "#include <metal_stdlib>\nusing namespace metal;\n" + shadows + "\n" + lighting + """
			struct V { float4 position [[position]]; };
			vertex V vs(uint id [[vertex_id]]) {
			    float2 p = id == 0 ? float2(-1,-1) : (id == 1 ? float2(3,-1) : float2(-1,3));
			    return {float4(p,0,1)};
			}
			fragment float4 fs(V in [[stage_in]], constant McFog& fog [[buffer(2)]]) {
			    bool ok = sizeof(McFog) == 48;
			    ok = ok && fog.FogColor.x == 0.0 && fog.FogColor.w == 0.0;
			    ok = ok && fog.FogEnvironmentalStart > 1.0e9 && fog.FogRenderDistanceEnd > 1.0e9;
			    return ok ? float4(0,1,0,1) : float4(1,0,0,1);
			}
			""";
		try (var queue = device.createCommandQueue();
			 var color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 4, 4, 1));
			 var lightingFrame = device.createBuffer(WorldLightingModule.FRAME_BYTES, MetalBuffer.StorageMode.SHARED);
			 var pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 source, "vs", source, "fs",
				 List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)),
				 null, MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
				 MetalRenderPipeline.RasterState.DEFAULT
			 ))) {
			WorldLightingModule.writeIdentity(lightingFrame);
			try (var commands = queue.createCommandBuffer()) {
				try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					MetalRenderPass.ColorAttachment.clear(color, 0, 0, 0, 1)
				))) {
					pass.setPipeline(pipeline);
					pass.setUniformBuffer(2, lightingFrame, 0, MetalRenderPass.STAGE_FRAGMENT);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				}
				commands.commitAndWait();
			}
			ByteBuffer pixels = color.readback(queue, 0);
			if ((pixels.get(0) & 255) != 0 || (pixels.get(1) & 255) != 255) {
				throw new AssertionError("Lighting frame layout did not match McFog");
			}
		}
		System.out.println("Lighting frame: McFog layout and identity fog upload passed");
		runColorTransfer(device);
		HdrCompositionSmoke.run(device);
	}

	private static void assertMappedFrameLayout() {
		ByteBuffer arena = ByteBuffer.allocateDirect(80).order(ByteOrder.nativeOrder());
		for (int index = 0; index < arena.capacity(); index++) arena.put(index, (byte)0x5a);
		ByteBuffer frame = arena.slice(16, WorldLightingModule.FRAME_BYTES).order(ByteOrder.nativeOrder());
		WorldLightingModule.write(frame, new org.joml.Vector4f(0.125F, 0.25F, 0.5F, 1.0F),
			12.0F, 34.0F, 56.0F, 78.0F, 90.0F, 123.0F);
		float[] expected = {0.125F, 0.25F, 0.5F, 1.0F, 12.0F, 34.0F, 56.0F, 78.0F, 90.0F, 123.0F};
		for (int index = 0; index < expected.length; index++) {
			if (frame.getFloat(index * Float.BYTES) != expected[index]) {
				throw new AssertionError("Mapped lighting frame field differs at " + index);
			}
		}
		for (int index = 0; index < arena.capacity(); index++) {
			if ((index < 16 || index >= 64) && arena.get(index) != (byte)0x5a) {
				throw new AssertionError("Mapped lighting frame overwrote an adjacent arena slice");
			}
			if (index >= 56 && index < 64 && arena.get(index) != 0) {
				throw new AssertionError("Mapped lighting frame retained stale ABI padding");
			}
		}
	}

	private static void assertHostTransfer() {
		double[][] expected = {
			{0, 0.2140411405, 1}, {0, 0.7353569831, 1},
			{0.00313003096, 0.00313080495, 0.00313159455},
			{0.040448644, 0.040449936, 0.0404511778},
			{0.18, 2, 4}, {0.02, 0.5, 1}, {0, 0, 0}, {0, 0, 0}
		};
		checkHost(expected[0], SceneColor.srgbToLinear(0), SceneColor.srgbToLinear(0.5F), SceneColor.srgbToLinear(1));
		checkHost(expected[1], SceneColor.linearToSrgb(0), SceneColor.linearToSrgb(0.5F), SceneColor.linearToSrgb(1));
		checkHost(expected[2], SceneColor.srgbToLinear(0.04044F), SceneColor.srgbToLinear(0.04045F), SceneColor.srgbToLinear(0.04046F));
		checkHost(expected[3], SceneColor.linearToSrgb(0.0031307F), SceneColor.linearToSrgb(0.0031308F), SceneColor.linearToSrgb(0.0031309F));
		checkHost(expected[4],
			SceneColor.srgbToLinear(SceneColor.linearToSrgb(0.18F)),
			SceneColor.srgbToLinear(SceneColor.linearToSrgb(2.0F)),
			SceneColor.srgbToLinear(SceneColor.linearToSrgb(4.0F)));
		checkHost(expected[5],
			SceneColor.linearToSrgb(SceneColor.srgbToLinear(0.02F)),
			SceneColor.linearToSrgb(SceneColor.srgbToLinear(0.5F)),
			SceneColor.linearToSrgb(SceneColor.srgbToLinear(1.0F)));
		checkHost(expected[6], SceneColor.srgbToLinear(-1), SceneColor.srgbToLinear(-0.01F), SceneColor.srgbToLinear(0));
		checkHost(expected[7], SceneColor.linearToSrgb(-1), SceneColor.linearToSrgb(-0.01F), SceneColor.linearToSrgb(0));
		System.out.println("Host color transfer: SceneColor matches shared/color.metal references");
	}

	private static void checkHost(final double[] expected, final float r, final float g, final float b) {
		float[] actual = {r, g, b};
		for (int channel = 0; channel < 3; channel++) {
			double tolerance = Math.max(0.000002, Math.abs(expected[channel]) * 0.001);
			if (!Float.isFinite(actual[channel]) || Math.abs(actual[channel] - expected[channel]) > tolerance) {
				throw new AssertionError("Host color transfer channel " + channel
					+ ": expected " + expected[channel] + ", got " + actual[channel]);
			}
		}
	}

	/** Check the production transfer helpers against independent reference values in an HDR target. */
	private static void runColorTransfer(final MetalDevice device) {
		String helpers;
		try {
			helpers = resource("/assets/metalcraft/shaderpacks/standard/shared/color.metal");
		} catch (IOException error) {
			throw new AssertionError(error);
		}
		String source = "#include <metal_stdlib>\nusing namespace metal;\n" + helpers + """
			struct V { float4 position [[position]]; };
			vertex V vs(uint id [[vertex_id]]) {
			    float2 p = id == 0 ? float2(-1,-1) : (id == 1 ? float2(3,-1) : float2(-1,3));
			    return {float4(p,0,1)};
			}
			fragment float4 fs(V in [[stage_in]]) {
			    uint x = uint(in.position.x);
			    if (x == 0) return float4(mc_srgb_to_linear(float3(0, 0.5, 1)), 0.25);
			    if (x == 1) return float4(mc_linear_to_srgb(float3(0, 0.5, 1)), 0.25);
			    if (x == 2) return float4(mc_srgb_to_linear(float3(0.04044, 0.04045, 0.04046)), 0.25);
			    if (x == 3) return float4(mc_linear_to_srgb(float3(0.0031307, 0.0031308, 0.0031309)), 0.25);
			    if (x == 4) return float4(mc_srgb_to_linear(mc_linear_to_srgb(float3(0.18, 2, 4))), 0.25);
			    if (x == 5) return float4(mc_linear_to_srgb(mc_srgb_to_linear(float3(0.02, 0.5, 1))), 0.25);
			    if (x == 6) return float4(mc_srgb_to_linear(float3(-1, -0.01, 0)), 0.25);
			    return float4(mc_linear_to_srgb(float3(-1, -0.01, 0)), 0.25);
			}
			""";
		double[][] expected = {
			{0, 0.2140411405, 1}, {0, 0.7353569831, 1},
			{0.00313003096, 0.00313080495, 0.00313159455},
			{0.040448644, 0.040449936, 0.0404511778},
			{0.18, 2, 4}, {0.02, 0.5, 1}, {0, 0, 0}, {0, 0, 0}
		};
		try (var queue = device.createCommandQueue();
			 var color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 8, 1, 1));
			 var pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 source, "vs", source, "fs",
				 List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT)),
				 null, MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
				 MetalRenderPipeline.RasterState.DEFAULT
			 ))) {
			try (var commands = queue.createCommandBuffer()) {
				try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					MetalRenderPass.ColorAttachment.clear(color, 0, 0, 0, 0)
				))) {
					pass.setPipeline(pipeline);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				}
				commands.commitAndWait();
			}
			ByteBuffer pixels = color.readback(queue, 0).order(ByteOrder.nativeOrder());
			for (int x = 0; x < expected.length; x++) {
				for (int channel = 0; channel < 4; channel++) {
					float actual = Float.float16ToFloat(pixels.getShort((x * 4 + channel) * Short.BYTES));
					double reference = channel == 3 ? 0.25 : expected[x][channel];
					// Half-float storage has ten fraction bits; allow one ULP plus shader rounding.
					double tolerance = Math.max(0.000002, Math.abs(reference) * 0.001);
					if (!Float.isFinite(actual) || Math.abs(actual - reference) > tolerance) {
						throw new AssertionError("Color transfer pixel " + x + " channel " + channel
							+ ": expected " + reference + ", got " + actual);
					}
				}
			}
		}
		System.out.println("Color transfer: sRGB reference values, knees, round trips, alpha and HDR readback passed");
	}

	private static String resource(final String path) throws IOException {
		try (var input = WorldLightingModuleSmoke.class.getResourceAsStream(path)) {
			if (input == null) {
				throw new IOException("Missing " + path);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
