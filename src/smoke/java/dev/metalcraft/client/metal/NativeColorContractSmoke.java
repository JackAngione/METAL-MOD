package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.metalcraft.client.shader.FrameBindings;
import java.util.Optional;
import net.minecraft.resources.Identifier;

/** Verifies that native programs enter only the cache matching their declared color contract. */
final class NativeColorContractSmoke {
	private static final String SOURCE = """
		#include <metal_stdlib>
		using namespace metal;
		vertex float4 vs(uint id [[vertex_id]]) {
		    return float4(id == 1 ? 3.0 : -1.0, id == 2 ? 3.0 : -1.0, 0.5, 1.0);
		}
		fragment float4 fs() { return float4(2.0, 0.5, 0.125, 1.0); }
		""";

	static void run() {
		var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
		try {
			RenderPipeline pipeline = RenderPipeline.builder()
				.withLocation(Identifier.parse("metalcraft:smoke/native_color_contract"))
				.withVertexShader(Identifier.parse("metalcraft:smoke/native_color_contract"))
				.withFragmentShader(Identifier.parse("metalcraft:smoke/native_color_contract"))
				.withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
					ColorTargetState.WRITE_ALL)).build();

			gpu.registerNativePipeline(pipeline, new MetalGpuDevice.NativeProgram(SOURCE, "vs", "fs"));
			var legacy = (MetalCompiledRenderPipeline)gpu.precompilePipeline(pipeline, null);
			if (!legacy.isValid()) throw new AssertionError("Legacy native program did not compile");
			reject(() -> gpu.precompileLinearWorldPipeline(pipeline, null));

			gpu.registerNativePipeline(pipeline, new MetalGpuDevice.NativeProgram(
				SOURCE, "vs", "fs", FrameBindings.ColorEncoding.LINEAR_SRGB));
			if (legacy.isValid()) throw new AssertionError("Native replacement retained legacy cache entry");
			var linear = (MetalCompiledRenderPipeline)gpu.precompileLinearWorldPipeline(pipeline, null);
			if (!linear.isValid() || linear != gpu.precompileLinearWorldPipeline(pipeline, null)) {
				throw new AssertionError("Explicit linear native program did not compile or reuse its cache entry");
			}

			gpu.forgetNativePipeline(pipeline);
			if (linear.isValid()) throw new AssertionError("Forgetting native program retained linear cache entry");

			gpu.registerNativePipeline(pipeline, new MetalGpuDevice.NativeProgram(
				"not valid MSL", "vs", "fs", FrameBindings.ColorEncoding.LINEAR_SRGB));
			reject(() -> gpu.precompileLinearWorldPipeline(pipeline, null));
			gpu.registerNativePipeline(pipeline, new MetalGpuDevice.NativeProgram(
				SOURCE, "vs", "fs", FrameBindings.ColorEncoding.LINEAR_SRGB));
			if (!gpu.precompileLinearWorldPipeline(pipeline, null).isValid()) {
				throw new AssertionError("Failed native compile poisoned the linear cache");
			}
			System.out.println("Native color contract: legacy rejection, explicit linear compile, replacement/forget retirement, and failed-compile recovery passed");
		} finally {
			gpu.close();
		}
	}

	private static void reject(final Runnable action) {
		try {
			action.run();
			throw new AssertionError("Expected native color contract rejection");
		} catch (IllegalArgumentException expected) { }
	}
}
