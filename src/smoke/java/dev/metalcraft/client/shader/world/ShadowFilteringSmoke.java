package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalTexture;
import java.nio.ByteOrder;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** A stored depth edge sampled through production filtering during subtexel motion. */
final class ShadowFilteringSmoke {
	static void run(final MetalDevice device, final String contract) {
		for (int[] configuration : new int[][]{
			{1, 256, 256, 0}, {1, 256, 256, 256}, {4, 256, 256, 256}, {4, 1024, 96, 96}, {4, 4096, 32, 256}
		}) {
			check(device, contract, configuration[0], configuration[1], configuration[2], configuration[3]);
		}
		System.out.println("Shadow filtering: continuous horizontal/vertical edge motion across cascade, resolution and both distance settings passed");
	}

	private static void check(final MetalDevice device, final String contract, final int count,
		final int resolution, final int distance, final int casterDistance) {
		var settings = new ShadowCascades.Settings(count, resolution, 0.1F, distance, 0.6F, casterDistance);
		var camera = new Vector3d();
		var rotation = new Quaternionf();
		var sun = new Vector3f(0, 1, 0);
		var cascade = ShadowCascades.fit(settings, camera, rotation, 1, 1, sun).getFirst();
		String source = "#include <metal_stdlib>\nusing namespace metal;\n" + contract + """
			struct V { float4 position [[position]]; uint layer [[render_target_array_index]]; };
			vertex V vs(uint id [[vertex_id]]) {
			    float2 p = id == 0 ? float2(-1,-1) : (id == 1 ? float2(3,-1) : float2(-1,3));
			    return {float4(p,0.5,1), 0};
			}
			struct Depth { float value [[depth(any)]]; };
			fragment Depth depth_fs(V in [[stage_in]], constant MCShadowFrame& f [[buffer(3)]]) {
			    return {any(in.position.xy * f.inverseResolution < 0.5) ? 0.25 : 0.75};
			}
			fragment float4 sample_fs(V in [[stage_in]], constant MCShadowFrame& f [[buffer(3)]],
			    constant float4x4& shadowToRelative [[buffer(4)]], depth2d_array<float> map [[texture(5)]],
			    sampler s [[sampler(5)]]) {
			    float offset = (floor(in.position.x) - 32.0) / 16.0;
			    float moving = 0.5 + offset * f.inverseResolution;
			    float2 uv = in.position.y < 1.0 ? float2(moving, 0.75) : float2(0.75, moving);
			    float3 relative = (shadowToRelative * float4(uv * 2.0 - 1.0, 0.5, 1)).xyz;
			    float visibility = mc_shadow_visibility(relative, f.cascadeFar[0] * 0.5, 0, f, map, s);
			    return float4(visibility, 0, 0, 1);
			}
			""";
		try (var module = new WorldShadowModule(device, settings);
			 var frame = module.prepareFrame(camera, rotation, 1, 1, sun, new Matrix4f());
			 var inverse = device.createBuffer(64, MetalBuffer.StorageMode.SHARED);
			 var queue = device.createCommandQueue();
			 var output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 65, 2, 1));
			 var depthPipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 source, "vs", source, "depth_fs", List.of(MetalRenderPipeline.ColorTarget.unused()),
				 MetalTexture.Format.DEPTH32_FLOAT, MetalRenderPipeline.VertexDescriptor.EMPTY,
				 new MetalRenderPipeline.DepthState(true, true, MetalRenderPipeline.CompareFunction.ALWAYS, 0, 0),
				 MetalRenderPipeline.RasterState.DEFAULT, MetalRenderPipeline.InputPrimitiveTopology.TRIANGLE));
			 var samplePipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 source, "vs", source, "sample_fs", List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT)),
				 null, MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
				 MetalRenderPipeline.RasterState.DEFAULT, MetalRenderPipeline.InputPrimitiveTopology.TRIANGLE))) {
			try (var mapping = inverse.map()) {
				new Matrix4f(cascade.cameraRelativeToShadow()).invert().get(0, mapping.bytes());
			}
			try (var commands = queue.createCommandBuffer()) {
				try (var pass = commands.beginRenderPass(module.depthPass())) {
					pass.setPipeline(depthPipeline);
					frame.bindUniforms(pass, 3, MetalRenderPass.STAGE_FRAGMENT);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
				}
				try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					MetalRenderPass.ColorAttachment.clear(output, 0, 0, 0, 1)))) {
					pass.setPipeline(samplePipeline);
					frame.bindUniforms(pass, 3, MetalRenderPass.STAGE_FRAGMENT);
					frame.bindDepth(pass, 5);
					pass.setUniformBuffer(4, inverse, 0, MetalRenderPass.STAGE_FRAGMENT);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
				}
				commands.commitAndWait();
			}
			var pixels = output.readback(queue, 0).order(ByteOrder.nativeOrder());
			float previous = 0;
			for (int i = 0; i < 130; i++) {
				int sample = i % 65;
				float actual = Float.float16ToFloat(pixels.getShort(i * 8));
				float expected = Math.clamp(((sample - 32) / 16.0F + 1.5F) / 3, 0, 1);
				if (!Float.isFinite(actual) || Math.abs(actual - expected) > 0.002F
					|| sample > 0 && (actual < previous - 0.002F || actual - previous > 0.023F)) {
					throw new AssertionError("Shadow edge pops during subtexel motion: cascades=" + count
						+ ", resolution=" + resolution + ", distance=" + distance + ", casterDistance=" + casterDistance
						+ ", axis=" + i / 65 + ", sample=" + sample
						+ ", expected=" + expected + ", visibility=" + actual + ", previous=" + previous);
				}
				previous = actual;
			}
		}
	}
}
