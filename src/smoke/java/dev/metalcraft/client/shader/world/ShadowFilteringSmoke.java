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
			{1, 256, 256, 0}, {1, 256, 256, 256}, {4, 256, 256, 256}, {2, 768, 96, 144},
			{4, 1024, 96, 96}, {4, 1536, 96, 144}, {4, 4096, 32, 256}
		}) {
			check(device, contract, configuration[0], configuration[1], configuration[2], configuration[3], false);
		}
		check(device, contract, 1, 512, 256, 96, true);
		check(device, contract, 4, 1536, 96, 144, false, true);
		check(device, contract, 1, 512, 320, 144, true, true);
		System.out.println("Shadow filtering: continuous edge motion and gather/scalar agreement for sloped/grazing receivers and map borders passed");
	}

	private static void check(final MetalDevice device, final String contract, final int count,
		final int resolution, final int distance, final int casterDistance, final boolean coarse) {
		check(device, contract, count, resolution, distance, casterDistance, coarse, false);
	}

	private static void check(final MetalDevice device, final String contract, final int count,
		final int resolution, final int distance, final int casterDistance, final boolean coarse, final boolean dense) {
		var settings = new ShadowCascades.Settings(count, resolution, 0.1F, distance, 0.6F, casterDistance);
		var camera = new Vector3d();
		var rotation = new Quaternionf();
		var sun = new Vector3f(0, 1, 0);
		var cascade = ShadowCascades.fit(settings, camera, rotation, 1, 1, sun).getFirst();
		int referenceStart = contract.indexOf("float mc_shadow_visibility_in_cascade(");
		int referenceEnd = contract.indexOf("// Matches ShadowCascades", referenceStart);
		String reference = contract.substring(referenceStart, referenceEnd)
			.replace("mc_shadow_visibility_in_cascade(", "mc_scalar_reference(");
		String source = "#include <metal_stdlib>\nusing namespace metal;\n#define MC_TEST_COARSE " + (coarse ? 1 : 0)
			+ "\n#define MC_TEST_DENSE " + (dense ? 1 : 0) + "\n" + contract
			+ "\n#undef MC_SHADOW_GATHER\n#define MC_SHADOW_GATHER 0\n" + reference + """
			struct V { float4 position [[position]]; uint layer [[render_target_array_index]]; };
			vertex V vs(uint id [[vertex_id]]) {
			    float2 p = id == 0 ? float2(-1,-1) : (id == 1 ? float2(3,-1) : float2(-1,3));
			    return {float4(p,0.5,1), 0};
			}
			// The sampled color target is 2D; only the depth writer selects an array layer.
			struct SampleV { float4 position [[position]]; };
			vertex SampleV sample_vs(uint id [[vertex_id]]) {
			    float2 p = id == 0 ? float2(-1,-1) : (id == 1 ? float2(3,-1) : float2(-1,3));
			    return {float4(p,0.5,1)};
			}
			struct Depth { float value [[depth(any)]]; };
			fragment Depth depth_fs(V in [[stage_in]], constant MCShadowFrame& f [[buffer(3)]]) {
			    if (MC_TEST_DENSE) {
			        uint2 p = uint2(in.position.xy);
			        return {0.25 + 0.5 * float((p.x * 1973u ^ p.y * 9277u) & 255u) / 255.0};
			    }
			    return {any(in.position.xy * f.inverseResolution < 0.5) ? 0.25 : 0.75};
			}
			fragment float4 sample_fs(SampleV in [[stage_in]], constant MCShadowFrame& f [[buffer(3)]],
			    constant float4x4& shadowToRelative [[buffer(4)]], depth2d_array<float> map [[texture(5)]],
			    sampler s [[sampler(5)]]) {
			    float offset = (floor(in.position.x) - 32.0) / 16.0;
			    float moving = 0.5 + offset * f.inverseResolution;
			    float2 uv = in.position.y < 1.0 ? float2(moving, 0.75) : float2(0.75, moving);
			    float receiverDepth = 0.5;
			    float3 normal = float3(0);
			    if (in.position.y >= 2.0) {
			        // Sloping/grazing receivers, clear texels, and every edge of the map.
			        uint row = uint(in.position.y) - 2u;
			        float axis = (floor(in.position.x) - 1.0) / 62.0;
			        uv = row < 4u ? float2(axis, row & 1u ? 0.999 : 0.001)
			            : float2(row & 1u ? 0.999 : 0.001, axis);
			        receiverDepth = 0.25 + (floor(in.position.x) - 32.0) * 0.0007;
			        normal = normalize(float3(0.4, row & 2u ? 0.001 : 0.5, -0.7));
			    }
			    if (MC_TEST_DENSE) {
			        uv = in.position.xy / float2(3840, 2160);
			        receiverDepth = 0.5;
			        normal = normalize(float3(0.4, 0.5, -0.7));
			    }
			    float3 relative = (shadowToRelative * float4(uv * 2.0 - 1.0, receiverDepth, 1)).xyz;
                float visibility = MC_TEST_COARSE
                    ? mc_shadow_visibility_in_cascade(relative, 0u, 0, f, map, s, normal, true)
                    : mc_shadow_visibility(relative, f.cascadeFar[0] * 0.5, 0, f, map, s, normal);
			    float scalar = mc_scalar_reference(relative, 0u, 0, f, map, s, normal, MC_TEST_COARSE);
			    return float4(visibility, scalar, 0, 1);
			}
			""";
		try (var module = new WorldShadowModule(device, settings);
			 var frame = module.prepareFrame(camera, rotation, 1, 1, sun, new Matrix4f());
			 var inverse = device.createBuffer(64, MetalBuffer.StorageMode.SHARED);
			 var queue = device.createCommandQueue();
			 var output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA16_FLOAT,
				 dense ? 3840 : 65, dense ? 2160 : 10, 1));
			 var depthPipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 source, "vs", source, "depth_fs", List.of(MetalRenderPipeline.ColorTarget.unused()),
				 MetalTexture.Format.DEPTH32_FLOAT, MetalRenderPipeline.VertexDescriptor.EMPTY,
				 new MetalRenderPipeline.DepthState(true, true, MetalRenderPipeline.CompareFunction.ALWAYS, 0, 0),
				 MetalRenderPipeline.RasterState.DEFAULT, MetalRenderPipeline.InputPrimitiveTopology.TRIANGLE));
			 var samplePipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 source, "sample_vs", source, "sample_fs", List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA16_FLOAT)),
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
			int mismatches = 0;
			float maxError = 0;
			for (int i = 0; i < (dense ? 3840 * 2160 : 650); i++) {
				int sample = i % 65;
				float actual = Float.float16ToFloat(pixels.getShort(i * 8));
				float scalar = Float.float16ToFloat(pixels.getShort(i * 8 + 2));
				maxError = Math.max(maxError, Math.abs(actual - scalar));
				if (!Float.isFinite(actual) || Math.abs(actual - scalar) > 0.001F) mismatches++;
				if (dense || i >= 130) continue;
				float expected = coarse ? Math.clamp((sample - 32) / 16.0F + 0.5F, 0, 1)
					: Math.clamp(((sample - 32) / 16.0F + 1.5F) / 3, 0, 1);
				if (!Float.isFinite(actual) || Math.abs(actual - expected) > 0.002F
					|| sample > 0 && (actual < previous - 0.002F || actual - previous > (coarse ? 0.065F : 0.023F))) {
					throw new AssertionError("Shadow edge pops during subtexel motion: cascades=" + count
						+ ", resolution=" + resolution + ", distance=" + distance + ", casterDistance=" + casterDistance
						+ ", axis=" + i / 65 + ", sample=" + sample
						+ ", expected=" + expected + ", visibility=" + actual + ", previous=" + previous);
				}
				previous = actual;
			}
			System.out.println("Shadow filter agreement: resolution=" + resolution + ", coarse=" + coarse
				+ ", dense=" + dense + ", mismatches=" + mismatches + ", maxError=" + maxError);
			if (mismatches > 0) throw new AssertionError("Gather/scalar shadow mismatch: " + mismatches + ", maxError=" + maxError);
		}
	}
}
