package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.SceneColor;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/**
 * Fail-closed HDR session: identity routing, on-demand linear compile, draw suppression, and
 * forced-legacy recovery. Does not wrap live GameRenderer or promote Fabulous targets.
 */
final class LinearWorldSessionSmoke {
	private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
	private static final String LINEAR_SOURCE = """
		#include <metal_stdlib>
		using namespace metal;
		vertex float4 vs(uint id [[vertex_id]]) {
		    return float4(id == 1 ? 3.0 : -1.0, id == 2 ? 3.0 : -1.0, 0.5, 1);
		}
		fragment float4 fs() { return float4(2.0, 0.25, 0.125, 1.0); }
		""";
	private static final String UNSEEN_SOURCE = """
		#include <metal_stdlib>
		using namespace metal;
		vertex float4 vs(uint id [[vertex_id]]) {
		    return float4(id == 1 ? 3.0 : -1.0, id == 2 ? 3.0 : -1.0, 0.5, 1);
		}
		fragment float4 fs() { return float4(1.5, 0.5, 0.25, 1.0); }
		""";
	private static final String LEGACY_SOURCE = """
		#include <metal_stdlib>
		using namespace metal;
		vertex float4 vs(uint id [[vertex_id]]) {
		    return float4(id == 1 ? 3.0 : -1.0, id == 2 ? 3.0 : -1.0, 0.5, 1);
		}
		fragment float4 fs() { return float4(4.0, 1.0, 0.0, 1.0); }
		""";

	private LinearWorldSessionSmoke() { }

	static void run() {
		var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
		try {
			var runtime = gpu.shaderPackRuntime();
			if (runtime == null) throw new AssertionError("Missing smoke shader runtime");
			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
			runtime.setOption("exposure", 1.0F);
			runtime.setOption("tonemap", "none");
			runtime.setOption("invert", false);
			runtime.setOption("debug_view", "off");

			RenderPipeline known = pipeline("metalcraft:smoke/linear_session_known");
			RenderPipeline unseen = pipeline("metalcraft:smoke/linear_session_unseen");
			RenderPipeline legacy = pipeline("metalcraft:smoke/linear_session_legacy");
			RenderPipeline unsupported = pipeline("metalcraft:smoke/linear_session_unsupported");
			gpu.registerNativePipeline(known, new MetalGpuDevice.NativeProgram(
				LINEAR_SOURCE, "vs", "fs", FrameBindings.ColorEncoding.LINEAR_SRGB));
			gpu.registerNativePipeline(unseen, new MetalGpuDevice.NativeProgram(
				UNSEEN_SOURCE, "vs", "fs", FrameBindings.ColorEncoding.LINEAR_SRGB));
			gpu.registerNativePipeline(legacy, new MetalGpuDevice.NativeProgram(LEGACY_SOURCE, "vs", "fs"));
			if (!gpu.getOrCompilePipeline(legacy).isValid()) {
				throw new AssertionError("Legacy native program did not enter the encoded cache");
			}

			var backend = (MetalCommandEncoder)gpu.createCommandEncoder();
			try (var queue = gpu.metal().createCommandQueue();
				 var mainColor = gpu.createTexture("session-main-color", USAGE, GpuFormat.RGBA8_UNORM, 9, 3, 1, 1);
				 var mainDepth = gpu.createTexture("session-main-depth", USAGE, GpuFormat.D32_FLOAT, 9, 3, 1, 1);
				 var mainColorView = gpu.createTextureView(mainColor);
				 var mainDepthView = gpu.createTextureView(mainDepth);
				 var skyView = gpu.createTextureView(mainColor);
				 var unrelated = gpu.createTexture("session-unrelated", USAGE, GpuFormat.RGBA8_UNORM, 9, 3, 1, 1);
				 var unrelatedView = gpu.createTextureView(unrelated);
				 var copyDest = gpu.createTexture("session-copy", USAGE, GpuFormat.RGBA16_FLOAT, 9, 3, 1, 1);
				 var output = gpu.createTexture("session-grade-out", USAGE, GpuFormat.RGBA8_UNORM, 9, 3, 1, 1);
				 var outputView = gpu.createTextureView(output)) {
				backend.clearColorTexture(mainColor, new Vector4f(0, 1, 0, 1));
				backend.clearDepthTexture(mainDepth, 1.0);
				backend.finishPendingWork();

				if (gpu.beginLinearWorld(mainColorView, mainDepthView, false, List.of(unsupported), null) != null) {
					throw new AssertionError("Preflight of an unsupported producer activated HDR");
				}

				MetalLinearWorldSession session = gpu.beginLinearWorld(
					mainColorView, mainDepthView, true, List.of(known), null);
				if (session == null) throw new AssertionError("Eligible linear world session was not created");
				if (!session.token().fabulous() || session.hdrColor() == mainColorView) {
					throw new AssertionError("Session token did not capture distinct HDR attachments");
				}
				try {
					gpu.beginLinearWorld(mainColorView, mainDepthView, false, List.of(known), null);
					throw new AssertionError("Nested linear world session accepted");
				} catch (IllegalStateException expected) { }

				try {
					backend.clearColorAndDepthTextures(mainColor, new Vector4f(0.5F, 0.25F, 1.0F, 1), mainDepth, 0.25);
					backend.clearColorTexture(unrelated, new Vector4f(0, 0, 1, 1));
					backend.finishPendingWork();
					assertHdr(read(queue, session.hdrColor()),
						SceneColor.srgbToLinear(0.5F), SceneColor.srgbToLinear(0.25F), SceneColor.srgbToLinear(1.0F), 1.0);
					assertRgba8(read(queue, unrelatedView), 0, 0, 255, 255);

					draw(backend, skyView, mainDepthView, 9, 3, known);
					backend.copyTextureToTexture(mainColor, copyDest, 0, 0, 0, 0, 0, 9, 3);
					backend.finishPendingWork();

					assertRgba8(read(queue, mainColorView), 0, 255, 0, 255);
					assertRgba8(read(queue, unrelatedView), 0, 0, 255, 255);
					assertHdr(read(queue, session.hdrColor()), 2.0, 0.25, 0.125, 1.0);
					assertHdr(read(queue, copyDest), 2.0, 0.25, 0.125, 1.0);

					draw(backend, mainColorView, null, 9, 3, unseen);
					backend.finishPendingWork();
					if (session.isPoisoned()) throw new AssertionError("Supported unseen pipeline poisoned the session");
					assertHdr(read(queue, session.hdrColor()), 1.5, 0.5, 0.25, 1.0);

					draw(backend, mainColorView, null, 9, 3, legacy);
					gpu.gradeLinearWorld(outputView);
					backend.finishPendingWork();
					if (!session.isPoisoned()) throw new AssertionError("Unsupported HDR draw did not poison the session");
					assertHdr(read(queue, session.hdrColor()), 1.5, 0.5, 0.25, 1.0);
				} finally {
					session.close();
				}
			}

			try (var color = gpu.createTexture("forced-color", USAGE, GpuFormat.RGBA8_UNORM, 5, 3, 1, 1);
				 var depth = gpu.createTexture("forced-depth", USAGE, GpuFormat.D32_FLOAT, 5, 3, 1, 1);
				 var colorView = gpu.createTextureView(color);
				 var depthView = gpu.createTextureView(depth)) {
				if (gpu.beginLinearWorld(colorView, depthView, false, List.of(known), null) != null) {
					throw new AssertionError("Poisoned session did not force the next frame legacy");
				}
			}

			try (var queue = gpu.metal().createCommandQueue();
				 var color = gpu.createTexture("retry-color", USAGE, GpuFormat.RGBA8_UNORM, 5, 3, 1, 1);
				 var depth = gpu.createTexture("retry-depth", USAGE, GpuFormat.D32_FLOAT, 5, 3, 1, 1);
				 var colorView = gpu.createTextureView(color);
				 var depthView = gpu.createTextureView(depth)) {
				MetalLinearWorldSession retry = gpu.beginLinearWorld(colorView, depthView, false, List.of(known), null);
				if (retry == null) throw new AssertionError("Linear world did not recover after the forced legacy frame");
				try {
					RenderPipeline standIn = pipeline("metalcraft:smoke/linear_session_standin");
					gpu.registerNativePipeline(standIn, new MetalGpuDevice.NativeProgram(
						UNSEEN_SOURCE, "vs", "fs", FrameBindings.ColorEncoding.LINEAR_SRGB));
					if (retry.isPoisoned()) {
						throw new AssertionError("First-time linear native stand-in poisoned the HDR session");
					}
					draw(backend, colorView, null, 5, 3, standIn, new Vector4f(0, 0, 0, 0));
					backend.finishPendingWork();
					assertHdr(read(queue, retry.hdrColor()), 1.5, 0.5, 0.25, 1.0);

					gpu.registerNativePipeline(known, new MetalGpuDevice.NativeProgram(
						UNSEEN_SOURCE, "vs", "fs", FrameBindings.ColorEncoding.LINEAR_SRGB));
					if (!retry.isPoisoned()) {
						throw new AssertionError("Native program replacement did not poison the session");
					}
					draw(backend, colorView, null, 5, 3, known, new Vector4f(0, 0, 0, 0));
					backend.finishPendingWork();
					assertHdr(read(queue, retry.hdrColor()), 0, 0, 0, 0);
				} finally {
					retry.close();
				}
			}

			gpu.clearPipelineCache();
			runtime.selectPack(ShaderPackRuntime.NONE_ID);
			System.out.println("Linear world session: identity routing, fog-clear decode, on-demand compile, linear stand-in admission, legacy discard, poison recovery and replacement guards passed");
		} finally {
			gpu.close();
		}
	}

	private static void draw(final MetalCommandEncoder backend, final GpuTextureView color,
		final GpuTextureView depth, final int width, final int height, final RenderPipeline pipeline) {
		draw(backend, color, depth, width, height, pipeline, null);
	}

	private static void draw(final MetalCommandEncoder backend, final GpuTextureView color,
		final GpuTextureView depth, final int width, final int height, final RenderPipeline pipeline,
		final Vector4f clear) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "linear session")
			.withColorAttachment(color, Optional.ofNullable(clear))
			.withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
		if (depth != null) {
			descriptor = descriptor.withDepthAttachment(depth, OptionalDouble.empty());
		}
		var pass = backend.createRenderPass(descriptor);
		pass.setPipeline(pipeline);
		pass.draw(3, 1, 0, 0);
		backend.submitRenderPass();
	}

	private static RenderPipeline pipeline(final String id) {
		return RenderPipeline.builder().withLocation(Identifier.parse(id))
			.withVertexShader(Identifier.parse(id)).withFragmentShader(Identifier.parse(id))
			.withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM,
				ColorTargetState.WRITE_ALL)).build();
	}

	private static ByteBuffer read(final MetalCommandQueue queue, final GpuTextureView view) {
		return ((MetalGpuTextureView)view).attachment().readback(queue, 0);
	}

	private static ByteBuffer read(final MetalCommandQueue queue, final GpuTexture texture) {
		return ((MetalGpuTexture)texture).metal().readback(queue, 0);
	}

	private static void assertRgba8(final ByteBuffer pixels, final int r, final int g, final int b, final int a) {
		if ((pixels.get(0) & 255) != r || (pixels.get(1) & 255) != g
			|| (pixels.get(2) & 255) != b || (pixels.get(3) & 255) != a) {
			throw new AssertionError("RGBA8 " + (pixels.get(0) & 255) + "," + (pixels.get(1) & 255)
				+ "," + (pixels.get(2) & 255) + "," + (pixels.get(3) & 255)
				+ " != " + r + "," + g + "," + b + "," + a);
		}
	}

	private static void assertHdr(final ByteBuffer pixels, final double r, final double g,
		final double b, final double a) {
		ByteBuffer ordered = pixels.duplicate().order(ByteOrder.nativeOrder());
		check(r, Float.float16ToFloat(ordered.getShort(0)));
		check(g, Float.float16ToFloat(ordered.getShort(2)));
		check(b, Float.float16ToFloat(ordered.getShort(4)));
		check(a, Float.float16ToFloat(ordered.getShort(6)));
	}

	private static void check(final double expected, final double actual) {
		if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.002) {
			throw new AssertionError("Expected " + expected + ", got " + actual);
		}
	}
}
