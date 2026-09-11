package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalSampler;
import dev.metalcraft.client.metal.MetalTexture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Production terrain shader, real vertex/index buffers, cutout holes and each cascade's depth. */
final class TerrainShadowRendererSmoke {
	static void run(final MetalDevice device, final String contract) {
		if (ShadowCasterVolume.intersects(new Matrix4f(), -0.1F,-0.1F,-0.9F,0.1F,0.1F,-0.1F)
			|| !ShadowCasterVolume.intersects(new Matrix4f(), -2,-2,0,2,2,1)) {
			throw new AssertionError("Caster volume does not use Metal [0,1] depth");
		}
		String source;
		try (var input = TerrainShadowRendererSmoke.class.getResourceAsStream(
			"/assets/metalcraft/shaderpacks/standard/shadow.metal")) {
			if (input == null) throw new AssertionError("Missing terrain shadow source");
			source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) { throw new AssertionError(error); }
		LodShadowSmoke.run(device, contract, source);
		for (int count = 1; count <= 4; count++) {
			for (var layer : ChunkSectionLayerGroup.OPAQUE.layers()) {
				check(device, contract, source, count, layer, false);
				check(device, contract, source, count, layer, true);
			}
		}
		System.out.println("Terrain shadows: 1–4 cascades, depth draws, cutouts, visibility, split boundaries and PCF edges passed");
	}

	private static void check(final MetalDevice device, final String contract, final String source,
		final int count, final ChunkSectionLayer layer, final boolean transparent) {
		var settings = new ShadowCascades.Settings(count, 32, 0.1F, 96, 0.6F, 96);
		var camera = new Vector3d();
		var orientation = new Quaternionf();
		var sun = new Vector3f(0,1,0);
		var cascades = ShadowCascades.fit(settings, camera, orientation, 1, 1, sun);
		boolean cutout = layer.pipeline().getShaderDefines().values().containsKey("ALPHA_CUTOUT");
		StringBuilder checks = new StringBuilder();
		for (int i = 0; i < Math.max(2,count); i++) {
			float expected = i >= count || transparent && cutout ? 1 : cascades.get(i)
				.cameraRelativeToShadow().transformPosition(new Vector3f(0,8,0)).z;
			checks.append("ok = ok && abs(map.sample(s,float2(0.5),").append(i).append(") - ")
				.append(expected).append(") < 0.0001;\n");
		}
		// Sample real terrain depth through the production visibility helper. Receivers below
		// the y=8 caster are occluded; those above it, or behind a cutout hole, are lit.
		for (int i = 0; i < count; i++) {
			float viewDepth = (cascades.get(i).near() + cascades.get(i).far()) * 0.5F;
			checks.append("ok = ok && mc_shadow_cascade(").append(viewDepth).append(", f) == ").append(i).append(";\n");
			checks.append("ok = ok && mc_shadow_cascade(").append(cascades.get(i).far())
				.append(", f) == ").append(i).append(";\n");
			checks.append("ok = ok && mc_shadow_cascade(").append(Math.nextUp(cascades.get(i).far()))
				.append(", f) == ").append(i + 1).append(";\n");
			for (int height : new int[]{0, 16}) {
				int visibility = height == 16 || transparent && cutout ? 1 : 0;
				checks.append("ok = ok && abs(mc_shadow_visibility(float3(0,").append(height).append(",-")
					.append(viewDepth).append("), ").append(viewDepth).append(", 0.00001, f, map, s) - ")
					.append(visibility).append(") < 0.0001;\n");
			}
			// At the first texel's center, three of nine taps fall beyond the light volume.
			// This distinguishes filtered visibility from a single comparison or clamped taps.
			var shadowMatrix = cascades.get(i).cameraRelativeToShadow();
			float receiverDepth = shadowMatrix.transformPosition(new Vector3f(0,0,0)).z;
			var edge = new Matrix4f(shadowMatrix).invert().transformPosition(
				new Vector3f(-1 + 1.0F / settings.resolution(), 0, receiverDepth));
			checks.append("ok = ok && abs(mc_shadow_visibility(float3(").append(edge.x).append(',')
				.append(edge.y).append(',').append(edge.z).append("), ").append(viewDepth)
				.append(", 0.00001, f, map, s) - ").append(transparent && cutout ? "1.0" : "(1.0 / 3.0)")
				.append(") < 0.0001;\n");
		}
		checks.append("""
			ok = ok && mc_shadow_cascade(0, f) == f.cascadeCount;
			ok = ok && mc_shadow_cascade(-1, f) == f.cascadeCount;
			ok = ok && mc_shadow_cascade(97, f) == f.cascadeCount;
			ok = ok && mc_shadow_visibility(float3(0,0,-97),97,0,f,map,s) == 1.0;
			ok = ok && mc_shadow_visibility(float3(100000,0,-1),1,0,f,map,s) == 1.0;
			ok = ok && mc_shadow_visibility(float3(0,100000,-1),1,0,f,map,s) == 1.0;
			ok = ok && mc_shadow_visibility(float3(0,0,-1),1,1,f,map,s) == 1.0;
			""");
		String sampleSource = """
			#include <metal_stdlib>
			using namespace metal;
			""" + contract + "\n" + """
			struct V { float4 position [[position]]; };
			vertex V vs(uint id [[vertex_id]]) {
			    return {float4(id == 0 ? float2(-1,-1) : (id == 1 ? float2(3,-1) : float2(-1,3)),0,1)};
			}
			fragment float4 fs(V in [[stage_in]], constant MCShadowFrame& f [[buffer(3)]],
			    depth2d_array<float> map [[texture(5)]], sampler s [[sampler(5)]]) {
			    bool ok = true;
			""" + checks + "return ok ? float4(0,1,0,1) : float4(1,0,0,1); }";
		int stride = layer.pipeline().getVertexFormatBinding(0).getVertexSize();
		try (var module = new WorldShadowModule(device, settings);
			 var frame = module.prepareFrame(camera, orientation, 1, 1, sun, new Matrix4f());
			 var renderer = new TerrainShadowRenderer(device, contract, source);
			 var queue = device.createCommandQueue();
			 var vertices = device.createBuffer(32 + stride * 4L, MetalBuffer.StorageMode.SHARED);
			 var indices = device.createBuffer(16, MetalBuffer.StorageMode.SHARED);
			 var atlas = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,1,1,1));
			 var atlasView = atlas.createView();
			 var sampler = device.createSampler(new MetalSampler.Descriptor(MetalSampler.Filter.NEAREST,
				 MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 var output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM,4,4,1));
			 var sample = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(sampleSource,"vs","fs",MetalTexture.Format.RGBA8_UNORM,null))) {
			// Above the view frustum at z=-50, but inside the sunward caster extension.
			if (!frame.intersects(-1,99,-51,1,101,-49) || frame.intersects(-1,9999,-51,1,10001,-49)) {
				throw new AssertionError("Caster union lost off-camera terrain or accepted terrain beyond the light volume");
			}
			try (var mapping = vertices.map()) {
				var bytes = mapping.bytes();
				for (int i=0; i<bytes.capacity(); i++) bytes.put(i,(byte)0);
				for (int i=0; i<4; i++) {
					int start = 32 + i * stride;
					bytes.putFloat(start, (i == 0 || i == 3) ? -1024 : 1024);
					bytes.putFloat(start + 8, i < 2 ? -1024 : 1024);
				}
			}
			try (var mapping = indices.map()) {
				mapping.bytes().position(4);
				for (int i : new int[]{0,1,2,0,2,3}) mapping.bytes().putShort((short)i);
			}
			ByteBuffer pixel = ByteBuffer.allocateDirect(4);
			pixel.put(new byte[]{-1,-1,-1,(byte)(transparent ? 0 : 255)}).flip();
			atlas.upload(queue,0,pixel);
			List<TerrainShadowRenderer.Draw> draws = List.of(
				new TerrainShadowRenderer.Draw(layer,vertices,32,indices,4,MetalRenderPass.IndexType.UINT16,6,0,0,0),
				new TerrainShadowRenderer.Draw(layer,vertices,32,indices,4,MetalRenderPass.IndexType.UINT16,6,0,8,0));
			try (var commands = queue.createCommandBuffer()) {
				try (var pass = commands.beginRenderPass(module.depthPass())) {
					renderer.encode(pass,frame,draws,atlasView,sampler);
				}
				try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(MetalRenderPass.ColorAttachment.clear(output,0,0,0,1)))) {
					pass.setPipeline(sample);
					frame.bindUniforms(pass,3,MetalRenderPass.STAGE_FRAGMENT);
					frame.bindDepth(pass,5);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE,0,3);
				}
				commands.commitAndWait();
			}
			var pixels = output.readback(queue,0);
			for (int i=0; i<16; i++) {
				if ((pixels.get(i*4+1)&255) != 255 || pixels.get(i*4) != 0) {
					throw new AssertionError("Terrain shadow depth mismatch: " + layer + ", cascades=" + count + ", transparent=" + transparent);
				}
			}
		}
	}
}
