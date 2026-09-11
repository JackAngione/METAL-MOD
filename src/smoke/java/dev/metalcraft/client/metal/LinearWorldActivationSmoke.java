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
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/**
 * Wrap helper: linear geometry selection, encoded grade handoff, forced-legacy grade, and
 * exception-safe session close. Does not require a running Minecraft LevelRenderer.
 */
final class LinearWorldActivationSmoke {
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

	private LinearWorldActivationSmoke() {
	}

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
			runtime.resize(9, 3);
			if (runtime.worldGeometry() == null) {
				throw new AssertionError("World geometry adapter was not built");
			}
			runtime.worldGeometry().beginFrame();

			RenderPipeline known = pipeline("metalcraft:smoke/linear_activation_known");
			gpu.registerNativePipeline(known, new MetalGpuDevice.NativeProgram(
				LINEAR_SOURCE, "vs", "fs", FrameBindings.ColorEncoding.LINEAR_SRGB));

			var backend = (MetalCommandEncoder)gpu.createCommandEncoder();
			try (var queue = gpu.metal().createCommandQueue();
				 var mainColor = gpu.createTexture("activation-main-color", USAGE, GpuFormat.RGBA8_UNORM, 9, 3, 1, 1);
				 var mainDepth = gpu.createTexture("activation-main-depth", USAGE, GpuFormat.D32_FLOAT, 9, 3, 1, 1);
				 var mainColorView = gpu.createTextureView(mainColor);
				 var mainDepthView = gpu.createTextureView(mainDepth)) {
				backend.clearColorTexture(mainColor, new Vector4f(0, 1, 0, 1));
				backend.clearDepthTexture(mainDepth, 1.0);
				backend.finishPendingWork();

				MetalLinearWorldActivation.Frame frame = MetalLinearWorldActivation.begin(
					gpu, mainColorView, mainDepthView, false, List.of(known));
				if (!frame.sessionActive() || frame.session() == null) {
					throw new AssertionError("Eligible linear world wrap did not begin a session");
				}
				if (!frame.selectedLinearGeometry()) {
					throw new AssertionError("Linear geometry encoding was not selected for an active session");
				}
				if (gpu.linearWorldSession() != frame.session()) {
					throw new AssertionError("Device session did not match the wrap helper");
				}
				try {
					draw(backend, mainColorView, mainDepthView, 9, 3, known);
					backend.finishPendingWork();
					assertHdr(read(queue, frame.session().hdrColor()), 2.0, 0.25, 0.125, 1.0);
					assertRgba8(read(queue, mainColorView), 0, 255, 0, 255);
				} finally {
					frame.close();
				}
				if (gpu.linearWorldSession() != null || !frame.session().isClosed()) {
					throw new AssertionError("Session remained open after wrap close");
				}
				frame.grade();
				backend.finishPendingWork();
				assertLinearGrade(read(queue, mainColorView));
				frame.grade();

				MetalLinearWorldActivation.Frame poisoned = MetalLinearWorldActivation.begin(
					gpu, mainColorView, mainDepthView, false, List.of(known));
				if (!poisoned.sessionActive()) {
					throw new AssertionError("Recovery session was not created before poison");
				}
				poisoned.session().poison();
				poisoned.close();

				backend.clearColorTexture(mainColor, new Vector4f(0, 1, 0, 1));
				backend.finishPendingWork();
				MetalLinearWorldActivation.Frame legacy = MetalLinearWorldActivation.begin(
					gpu, mainColorView, mainDepthView, false, List.of(known));
				if (legacy.sessionActive() || gpu.linearWorldSession() != null) {
					throw new AssertionError("Forced-legacy begin did not leave the encoded path");
				}
				if (legacy.selectedLinearGeometry()) {
					throw new AssertionError("Forced-legacy frame selected linear geometry");
				}
				try {
					legacy.grade();
					backend.finishPendingWork();
					assertRgba8(read(queue, mainColorView), 0, 255, 0, 255);
				} finally {
					legacy.close();
				}

				MetalLinearWorldActivation.Frame retry = MetalLinearWorldActivation.begin(
					gpu, mainColorView, mainDepthView, false, List.of(known));
				if (!retry.sessionActive()) {
					throw new AssertionError("Linear world did not recover after the forced legacy frame");
				}
				try {
					throw new IllegalStateException("render failed");
				} catch (IllegalStateException expected) {
					if (!"render failed".equals(expected.getMessage())) throw expected;
				} finally {
					retry.close();
				}
				if (gpu.linearWorldSession() != null || !retry.session().isClosed()) {
					throw new AssertionError("Exception during render leaked the linear world session");
				}
			}

			runtime.selectPack(ShaderPackRuntime.NONE_ID);
			System.out.println("Linear world activation: wrap helper, linear geometry, encoded grade, forced-legacy grade and exception-safe close passed");
		} finally {
			gpu.close();
		}
	}

	private static void draw(final MetalCommandEncoder backend, final GpuTextureView color,
		final GpuTextureView depth, final int width, final int height, final RenderPipeline pipeline) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.create(() -> "linear activation")
			.withColorAttachment(color, Optional.empty())
			.withDepthAttachment(depth, OptionalDouble.empty())
			.withRenderArea(new RenderPass.RenderArea(0, 0, width, height));
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

	private static void assertLinearGrade(final ByteBuffer pixels) {
		int r = pixels.get(0) & 255;
		int g = pixels.get(1) & 255;
		int b = pixels.get(2) & 255;
		int a = pixels.get(3) & 255;
		if (r != 255 || a != 255 || Math.abs(g - 137) > 3 || Math.abs(b - 99) > 3) {
			throw new AssertionError("Linear grade RGBA8 " + r + "," + g + "," + b + "," + a
				+ " != 255,137,99,255");
		}
	}

	private static void check(final double expected, final double actual) {
		if (!Double.isFinite(actual) || Math.abs(expected - actual) > 0.002) {
			throw new AssertionError("Expected " + expected + ", got " + actual);
		}
	}
}
