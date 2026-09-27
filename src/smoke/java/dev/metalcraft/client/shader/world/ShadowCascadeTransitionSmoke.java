package dev.metalcraft.client.shader.world;

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

/** Different shadow resolutions can disagree at an edge; crossing a split must not pop. */
final class ShadowCascadeTransitionSmoke {
	static void run(final MetalDevice device, final String contract) {
		String source = "#include <metal_stdlib>\nusing namespace metal;\n" + contract + """
			struct V { float4 position [[position]]; uint layer [[render_target_array_index]]; };
			vertex V depth_vs(uint id [[vertex_id]], uint layer [[instance_id]]) {
			    float2 p = id == 0 ? float2(-1,-1) : (id == 1 ? float2(3,-1) : float2(-1,3));
			    return {float4(p, (layer & 1u) == 0u ? 0.0 : 1.0, 1.0), layer};
			}
			fragment void depth_fs() {}
			struct SampleV { float4 position [[position]]; };
			vertex SampleV sample_vs(uint id [[vertex_id]]) {
			    float2 p = id == 0 ? float2(-1,-1) : (id == 1 ? float2(3,-1) : float2(-1,3));
			    return {float4(p,0,1)};
			}
			fragment float4 sample_fs(SampleV in [[stage_in]], constant MCShadowFrame& f [[buffer(3)]],
			    depth2d_array<float> map [[texture(5)]], sampler s [[sampler(5)]]) {
			    uint cascade = uint(in.position.y);
			    float near = cascade == 0u ? 0.0 : f.cascadeFar[cascade - 1u];
			    float far = f.cascadeFar[cascade];
			    float phase = floor(in.position.x) / 128.0;
			    float viewDepth = far + (phase * 0.20 - 0.15) * (far - near);
			    float visibility = mc_shadow_visibility(float3(0,0,-viewDepth), viewDepth, 0, f, map, s);
			    float worldBias = mc_shadow_receiver_bias(float3(0,1,0), viewDepth, f)
			        * mc_shadow_cascade_depth_range(mc_shadow_cascade(viewDepth, f), f);
			    return float4(visibility,worldBias,0,1);
			}
			""";
		for (int count = 2; count <= 4; count++) {
			for (int[] configuration : new int[][]{{128, 256}, {1024, 32}}) {
				var settings = new ShadowCascades.Settings(count, configuration[0], 0.05F, configuration[1], 0.6F, 256);
				try (var module = new WorldShadowModule(device, settings);
					 var frame = module.prepareFrame(new Vector3d(), new Quaternionf(), 1, 1, new Vector3f(0,1,0), new Matrix4f());
					 var queue = device.createCommandQueue();
					 var output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT, 129, count - 1, 1));
					 var depthPipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
						 source, "depth_vs", source, "depth_fs", List.of(MetalRenderPipeline.ColorTarget.unused()),
						 MetalTexture.Format.DEPTH32_FLOAT, MetalRenderPipeline.VertexDescriptor.EMPTY,
						 new MetalRenderPipeline.DepthState(true, true, MetalRenderPipeline.CompareFunction.ALWAYS, 0, 0),
						 MetalRenderPipeline.RasterState.DEFAULT, MetalRenderPipeline.InputPrimitiveTopology.TRIANGLE));
					 var samplePipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
						 source, "sample_vs", source, "sample_fs", List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT)),
						 null, MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
						 MetalRenderPipeline.RasterState.DEFAULT))) {
					try (var commands = queue.createCommandBuffer()) {
						try (var pass = commands.beginRenderPass(module.depthPass())) {
							pass.setPipeline(depthPipeline);
							pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, count, 0);
						}
						try (var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
							MetalRenderPass.ColorAttachment.clear(output, 0, 0, 0, 1)))) {
							pass.setPipeline(samplePipeline);
							frame.bindUniforms(pass, 3, MetalRenderPass.STAGE_FRAGMENT);
							frame.bindDepth(pass, 5);
							pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
						}
						commands.commitAndWait();
					}
					var pixels = output.readback(queue, 0).order(ByteOrder.nativeOrder());
					for (int split = 0; split < count - 1; split++) {
						float previous = split % 2;
						float fromBias = Float.float16ToFloat(pixels.getShort(split * 129 * 8 + 2));
						float toBias = Float.float16ToFloat(pixels.getShort((split * 129 + 128) * 8 + 2));
						float previousBias = fromBias;
						float biasStepLimit = Math.max(Math.abs(toBias - fromBias) * 0.04F, 0.0001F);
						for (int x = 0; x <= 128; x++) {
							float actual = Float.float16ToFloat(pixels.getShort((split * 129 + x) * 8));
							float direction = split % 2 == 0 ? 1 : -1;
							boolean wrongEndpoint = x <= 32 && Math.abs(actual - split % 2) > 0.002F
								|| x >= 96 && Math.abs(actual - (1 - split % 2)) > 0.002F;
							if (!Float.isFinite(actual) || actual < 0 || actual > 1 || wrongEndpoint
								|| direction * (actual - previous) < -0.002F || Math.abs(actual - previous) > 0.025F) {
								throw new AssertionError("Shadow cascade transition pops: count=" + count + ", split=" + split
									+ ", sample=" + x + ", actual=" + actual + ", previous=" + previous);
							}
							float worldBias = Float.float16ToFloat(pixels.getShort((split * 129 + x) * 8 + 2));
							if (!Float.isFinite(worldBias) || worldBias <= 0 || worldBias > 0.12F
								|| Math.abs(worldBias - previousBias) > biasStepLimit) {
								throw new AssertionError("Shadow receiver bias pops across cascade split: previous="
									+ previousBias + ", actual=" + worldBias);
							}
							previous = actual;
							previousBias = worldBias;
						}
					}
				}
			}
		}
		System.out.println("Shadow cascade transitions: continuous visibility across every split with 2–4 cascades passed");
	}
}
