package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalTexture;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Real GPU checks for the shadow allocation, Java/MSL layout, reconstruction, and upload lifetime. */
public final class WorldShadowModuleSmoke {
	private WorldShadowModuleSmoke() {
	}

	public static void run(final MetalDevice device) {
		ShadowFrameReuseSmoke.run();
		String contract;
		try (var input = WorldShadowModuleSmoke.class.getResourceAsStream(
			"/assets/metalcraft/shaderpacks/standard/shared/shadows.metal")) {
			if (input == null) throw new AssertionError("Missing shadow MSL contract");
			contract = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new AssertionError(error);
		}
		for (int count = 1; count <= 4; count++) check(device, contract, count);
		checkWalkingReconstruction(device, contract);
		ShadowFilteringSmoke.run(device, contract);
		ShadowAxisTagSmoke.run(device, contract);
		ShadowReceiverPlaneSmoke.run(device, contract);
		ShadowFaceLightSmoke.run(device, contract);
		ShadowCascadeTransitionSmoke.run(device, contract);
		TerrainShadowRendererSmoke.run(device, contract);
		ShadowSunMotionSmoke.run(device, contract);
		System.out.println("Shadow resources: 1–4 cascades, named bindings, reconstruction and immutable uploads passed");
	}

	/** Project fixed receivers with Minecraft's walking bob, then reconstruct with production MSL. */
	private static void checkWalkingReconstruction(final MetalDevice device, final String contract) {
		final int samples = 128;
		final float[] depths = {1, 8, 32, 96};
		String source = "#include <metal_stdlib>\nusing namespace metal;\n" + contract + """
			struct Sample { float4x4 inverseProjection; float4 point; float4 clip; };
			struct V { float4 position [[position]]; };
			vertex V vs(uint id [[vertex_id]]) {
			    float2 p = id == 0 ? float2(-1,-1) : (id == 1 ? float2(3,-1) : float2(-1,3));
			    return {float4(p,0,1)};
			}
			fragment float4 fs(V in [[stage_in]], constant Sample* samples [[buffer(0)]]) {
			    Sample s = samples[uint(in.position.x)];
			    float2 uv = (s.clip.xy / s.clip.w + 1.0) * 0.5;
			    float3 actual = mc_reconstruct_view_position(uv, -s.point.z, s.inverseProjection);
			    return float4(abs(actual - s.point.xyz), 1);
			}
			""";
		try (var queue = device.createCommandQueue();
			 var inputs = device.createBuffer(samples * 96, MetalBuffer.StorageMode.SHARED);
			 var color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, samples, 1, 1));
			 var pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 source, "vs", source, "fs", List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT)),
				 null, MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
				 MetalRenderPipeline.RasterState.DEFAULT))) {
			try (var mapping = inputs.map()) {
				var bytes = mapping.bytes();
				for (int i = 0; i < samples; i++) {
					float phase = (i % 16) * (float)Math.PI / 8;
					float bob = i % 16 == 0 ? 0 : 0.1F;
					Matrix4f projection = new Matrix4f().perspective(1.2F, 1.5F, 0.05F, 1024, true)
						.translate(i < 64 ? (float)Math.sin(phase) * bob * 0.5F : 0,
							i < 64 ? -(float)Math.abs(Math.cos(phase) * bob) : 0, 0)
						.rotateZ((float)Math.toRadians(Math.sin(phase) * bob * 3))
						.rotateX((float)Math.toRadians(Math.abs(Math.cos(phase - 0.2F) * bob) * 5));
					Vector4f point = new Vector4f(0.3F, -0.4F, -depths[i / 16 % depths.length], 1);
					new Matrix4f(projection).invert().get(i * 96, bytes);
					point.get(i * 96 + 64, bytes);
					projection.transform(point, new Vector4f()).get(i * 96 + 80, bytes);
				}
			}
			try (var commands = queue.createCommandBuffer()) {
				try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					MetalRenderPass.ColorAttachment.clear(color, 0, 0, 0, 1)))) {
					pass.setPipeline(pipeline);
					pass.setUniformBuffer(0, inputs, 0, MetalRenderPass.STAGE_FRAGMENT);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				}
				commands.commitAndWait();
			}
			ByteBuffer pixels = color.readback(queue, 0).order(ByteOrder.nativeOrder());
			for (int i = 0; i < samples; i++) {
				for (int axis = 0; axis < 3; axis++) {
					float error = Float.float16ToFloat(pixels.getShort(i * 8 + axis * 2));
					if (!Float.isFinite(error) || error > 0.002F) {
						throw new AssertionError("Walking shadow receiver moved: sample " + i + ", axis " + axis + ", error " + error + " blocks");
					}
				}
			}
		}
		System.out.println("Walking reconstruction: fixed receivers stable through walking and rotation-only phases at 1–96 blocks");
	}

	private static void check(final MetalDevice device, final String contract, final int count) {
		var settings = new ShadowCascades.Settings(count, 32, 0.1F, 96, 0.6F, 48);
		var camera = new Vector3d(30_000_000, 80, -30_000_000);
		var rotation = new Quaternionf().rotateY(0.7F).rotateX(-0.2F);
		var sun = new Vector3f(1, 2, 3).normalize();
		float fov = 1.1F;
		var inverse = new Matrix4f().perspective(fov, 1.5F, 0.1F, 200, true).invert();
		var cascades = ShadowCascades.fit(settings, camera, rotation, fov, 1.5F, sun, new ShadowCascades.Stabilization());
		var point = new Vector4f(2, 3, -7, 1);
		StringBuilder checks = new StringBuilder();
		for (int i = 0; i < count; i++) {
			Vector4f expected = cascades.get(i).cameraRelativeToShadow().transform(point, new Vector4f());
			checks.append("ok = ok && all(abs(f.cameraRelativeToShadow[").append(i)
				.append("] * float4(2,3,-7,1) - ").append(vector(expected)).append(") < 0.0001);\n");
			checks.append("ok = ok && abs(f.cascadeFar[").append(i).append("] - ")
				.append(cascades.get(i).far()).append(") < 0.0001;\n");
		}
		Vector4f view = inverse.transform(new Vector4f(-0.5F, -0.5F, 0.6F, 1));
		Vector3f relative = rotation.transform(new Vector3f(view.x / view.w, view.y / view.w, view.z / view.w));
		String source = "#include <metal_stdlib>\nusing namespace metal;\n"
			+ "#define MC_BUFFER_SHADOW_FRAME 3\n#define MC_TEX_SHADOW_MAP 5\n" + contract + "\n"
			+ """
			struct V { float4 position [[position]]; };
			vertex V vs(uint id [[vertex_id]]) {
			    float2 p = id == 0 ? float2(-1,-1) : (id == 1 ? float2(3,-1) : float2(-1,3));
			    return {float4(p,0,1)};
			}
			fragment float4 fs(V in [[stage_in]], constant MCShadowFrame& f [[buffer(MC_BUFFER_SHADOW_FRAME)]],
			    depth2d_array<float> depth [[texture(MC_TEX_SHADOW_MAP)]], sampler s [[sampler(MC_TEX_SHADOW_MAP)]]) {
			    bool ok = sizeof(MCShadowFrame) == 432;
			"""
			+ checks + "ok = ok && f.cascadeCount == " + count + ";\n"
			+ "ok = ok && f.inverseResolution == 0.03125 && f.shadowDistance == 96 && f.casterExtension == 48;\n"
			+ "ok = ok && all(abs(f.directionToSun - " + vector(new Vector4f(sun, 0)) + ") < 0.0001);\n"
			+ "ok = ok && all(abs(mc_shadow_camera_relative(float2(0.25,0.75),0.6,f) - float3("
			+ relative.x + "," + relative.y + "," + relative.z + ")) < 0.0001);\n"
			+ "for (uint i = 0; i < depth.get_array_size(); i++) ok = ok && depth.sample(s,float2(0.5),i) == 1.0;\n"
			+ "return ok ? float4(0,1,0,1) : float4(1,0,0,1); }\n";
		try (var module = new WorldShadowModule(device, settings);
			 var first = module.prepareFrame(camera, rotation, fov, 1.5F, sun, inverse);
			 // Upload a different frame before submitting the first: shared-buffer reuse would fail.
			 var second = module.prepareFrame(camera, rotation, fov, 1.5F, new Vector3f(0,1,0), inverse);
			 var queue = device.createCommandQueue();
			 var color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 4, 4, 1));
			 var pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 source, "vs", source, "fs", List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)),
				 null, MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
				 MetalRenderPipeline.RasterState.DEFAULT))) {
			MetalTexture depth = module.depthPass().depthAttachment().texture();
			if (!depth.descriptor().isArray() || depth.descriptor().sliceCount() != Math.max(2, count)
				|| depth.isMemoryless()) throw new AssertionError("Wrong shadow array allocation");
			try (var commands = queue.createCommandBuffer()) {
				try (var clear = commands.beginRenderPass(module.depthPass())) { }
				try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					MetalRenderPass.ColorAttachment.clear(color, 0, 0, 0, 1)))) {
					pass.setPipeline(pipeline);
					first.bindUniforms(pass, 3, MetalRenderPass.STAGE_FRAGMENT);
					first.bindDepth(pass, 5);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				}
				first.close();
				module.close(); // Encoded buffers, views, texture and sampler must survive this.
				commands.commitAndWait();
			}
			ByteBuffer pixels = color.readback(queue, 0);
			for (int i = 0; i < 16; i++) {
				if ((pixels.get(i * 4) & 255) != 0 || (pixels.get(i * 4 + 1) & 255) != 255) {
					throw new AssertionError("Shadow binding/reconstruction mismatch for " + count + " cascades");
				}
			}
			if (!depth.isClosed()) throw new AssertionError("Shadow depth leaked after close");
			try {
				module.depthPass();
				throw new AssertionError("Closed shadow module accepted encoding");
			} catch (IllegalStateException expected) { }
		}
	}

	private static String vector(final Vector4f v) {
		return "float4(" + v.x + "," + v.y + "," + v.z + "," + v.w + ")";
	}
}
