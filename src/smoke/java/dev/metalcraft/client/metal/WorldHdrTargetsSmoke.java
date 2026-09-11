package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Exercises the real Blaze3D backend and host grade handoff, with synthetic linear producers. */
final class WorldHdrTargetsSmoke {
	static void run() {
		var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
		try {
			var runtime = gpu.shaderPackRuntime();
			if (runtime == null) throw new AssertionError("Missing smoke shader runtime");
			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
			runtime.setOption("exposure", 0.5F);
			runtime.setOption("tonemap", "none");
			runtime.setOption("invert", false);
			runtime.setOption("debug_view", "off");
			String source = """
				#include <metal_stdlib>
				using namespace metal;
				vertex float4 vs(uint id [[vertex_id]]) {
				    return float4(id == 1 ? 3.0 : -1.0, id == 2 ? 3.0 : -1.0, 0.5, 1);
				}
				fragment float4 fs() { return float4(4, 0.5, 0.125, 0.5); }
				""";
			var info = RenderPipeline.builder().withLocation(Identifier.parse("metalcraft:smoke/hdr_host"))
				.withVertexShader(Identifier.parse("metalcraft:smoke/hdr_host"))
				.withFragmentShader(Identifier.parse("metalcraft:smoke/hdr_host"))
				.withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT),
					GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL)).build();
			gpu.registerNativePipeline(info, new MetalGpuDevice.NativeProgram(source, "vs", "fs"));
			var compiled = gpu.getOrCompilePipeline(info);
			var hdrPipeline = compiled.metal(true, MetalTexture.Format.RGBA16_FLOAT);
			if (hdrPipeline != compiled.metal(true, MetalTexture.Format.RGBA16_FLOAT)) throw new AssertionError("Variant not cached");
			if (!hdrPipeline.descriptor().colorTargets().getFirst().blendState().equals(
				compiled.metal(true).descriptor().colorTargets().getFirst().blendState())) throw new AssertionError("Blend state changed");
			var backend = (MetalCommandEncoder)gpu.createCommandEncoder();
			try (var queue = gpu.metal().createCommandQueue()) {
				for (int size : new int[]{9, 5, 9}) {
					var targets = gpu.prepareLinearWorldTargets(size, 3);
					if (targets.color() != gpu.prepareLinearWorldTargets(size, 3).color()) throw new AssertionError("Same-size reallocation");
					try (var output = gpu.metal().createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, size, 3, 1));
						 var outputView = gpu.wrapAttachment(output, "hdr-output")) {
						for (boolean depth : new boolean[]{true, false}) {
							var descriptor = RenderPassDescriptor.create(() -> "HDR backend fixture")
								.withColorAttachment(targets.color(), Optional.of(new Vector4f(0, 0, 0, 0)))
								.withRenderArea(new RenderPass.RenderArea(0, 0, size, 3));
							if (depth) descriptor.withDepthAttachment(targets.depth(), OptionalDouble.of(0));
							var pass = backend.createRenderPass(descriptor);
							pass.setPipeline(info);
							pass.draw(3, 1, 0, 0);
							backend.submitRenderPass();
							gpu.gradeLinearWorld(outputView);
							backend.finishPendingWork();
							var hdr = targets.color().attachment().readback(queue, 0).order(ByteOrder.nativeOrder());
							check(2, Float.float16ToFloat(hdr.getShort(0)), 0.002);
							check(0.25, Float.float16ToFloat(hdr.getShort(2)), 0.002);
							check(0.5, Float.float16ToFloat(hdr.getShort(6)), 0.002);
							var pixels = output.readback(queue, 0);
							check(255, pixels.get(0) & 255, 2);
							check(99, pixels.get(1) & 255, 2);
							check(49, pixels.get(2) & 255, 2);
							check(255, pixels.get(3) & 255, 0);
						}
						try {
							gpu.gradeLinearWorld(targets.color());
							throw new AssertionError("Aliased HDR output accepted");
						} catch (IllegalArgumentException expected) { }
					}
				}
			}
			var retired = gpu.prepareLinearWorldTargets(9, 3).color();
			gpu.prepareLinearWorldTargets(7, 3);
			if (!retired.isClosed()) throw new AssertionError("Old target not retired");
			var reloaded = gpu.prepareLinearWorldTargets(7, 3).color();
			gpu.clearPipelineCache();
			if (!hdrPipeline.isClosed() || !reloaded.isClosed()) throw new AssertionError("Reload leaked HDR resources");
			gpu.prepareLinearWorldTargets(7, 3);
			runtime.selectPack(ShaderPackRuntime.NONE_ID);
		} finally {
			gpu.close();
		}
		System.out.println("HDR host: backend format variants, blending, explicit grade, odd resize, alias rejection and reload passed");
	}

	private static void check(double expected, double actual, double tolerance) {
		if (!Double.isFinite(actual) || Math.abs(expected - actual) > tolerance) {
			throw new AssertionError("Expected " + expected + ", got " + actual);
		}
	}
}
