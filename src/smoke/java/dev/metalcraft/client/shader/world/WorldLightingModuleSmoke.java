package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalTexture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** GPU layout check for the lighting/fog frame uploaded to resolve. */
public final class WorldLightingModuleSmoke {
	private WorldLightingModuleSmoke() {
	}

	public static void run(final MetalDevice device) {
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
