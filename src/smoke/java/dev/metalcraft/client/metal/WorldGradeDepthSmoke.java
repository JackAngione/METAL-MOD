package dev.metalcraft.client.metal;

import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.WorldComposition;
import dev.metalcraft.client.shader.water.UnderwaterFrameInputs;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Exercises conditional world-grade depth capture through the real host seam and Metal GPU. */
final class WorldGradeDepthSmoke {
	private WorldGradeDepthSmoke() { }

	static void run() {
		Path root;
		try {
			root = Files.createTempDirectory("metalcraft-grade-depth-");
			Path pack = Files.createDirectories(root.resolve("shaderpacks/world-depth"));
			Files.writeString(pack.resolve("pack.json"), DEPTH_PACK);
			Files.writeString(pack.resolve("grade.metal"), DEPTH_MSL);
		} catch (IOException error) {
			throw new AssertionError("Could not create depth snapshot fixture", error);
		}
		var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
		try (var queue = gpu.metal().createCommandQueue();
			 var runtime = new ShaderPackRuntime(gpu.metal(), root.resolve("shaderpacks"), root.resolve("settings.json"));
			 var grade = new MetalWorldGrade()) {
			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
			runtime.setOption("exposure", 1.0F);
			runtime.setOption("tonemap", "none");
			runtime.setOption("invert", false);
			runtime.setOption("debug_view", "off");
			assertRequirement(runtime, false);
			assertAbsent(frame(gpu, queue, runtime, grade, 9, 5, false, 0.25F));
			assertAbsent(frame(gpu, queue, runtime, grade, 5, 3, true, 0.25F));

			runtime.selectPack("world-depth");
			assertRequirement(runtime, true);
			Snapshot first = frame(gpu, queue, runtime, grade, 9, 5, false, 0.25F);
			Snapshot reused = frame(gpu, queue, runtime, grade, 9, 5, false, 0.625F);
			if (first.texture() != reused.texture() || first.view() != reused.view()) {
				throw new AssertionError("Same-size world-depth snapshot was reallocated");
			}
			Snapshot resized = frame(gpu, queue, runtime, grade, 5, 3, false, 0.375F);
			assertClosed(first);
			runtime.reload();
			assertRequirement(runtime, true);
			Snapshot reloaded = frame(gpu, queue, runtime, grade, 5, 3, false, 0.5F);
			if (resized.texture() != reloaded.texture()) {
				throw new AssertionError("Same-size pack reload unnecessarily replaced the depth snapshot");
			}

			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
			assertRequirement(runtime, false);
			assertAbsent(frame(gpu, queue, runtime, grade, 5, 3, true, 0.25F));
			assertClosed(reloaded);
			runtime.selectPack("world-depth");
			Snapshot restored = frame(gpu, queue, runtime, grade, 5, 3, false, 0.75F);
			grade.close();
			assertClosed(restored);
			assertAbsent(snapshot(grade));
		} finally {
			gpu.close();
			try (var paths = Files.walk(root)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
			} catch (IOException error) {
				throw new AssertionError("Could not remove depth snapshot fixture", error);
			}
		}
		System.out.println("World grade depth: Standard skips snapshot allocation/copy; custom depth retains pre-hand values, reuse, odd resize, reload, switch and close passed");
	}

	private static void assertRequirement(final ShaderPackRuntime runtime, final boolean expected) {
		if (!runtime.isActive() || runtime.executor().orElseThrow().requiresWorldDepth() != expected) {
			throw new AssertionError("Incorrect executable world-depth requirement: " + runtime.lastError());
		}
	}

	private static Snapshot frame(final MetalGpuDevice gpu, final MetalCommandQueue queue,
		final ShaderPackRuntime runtime, final MetalWorldGrade grade, final int width, final int height,
		final boolean linear, final float worldDepth) {
		boolean readsDepth = runtime.executor().orElseThrow().requiresWorldDepth();
		FrameBindings.ColorEncoding encoding = linear
			? FrameBindings.ColorEncoding.LINEAR_SRGB : FrameBindings.ColorEncoding.LEGACY_ENCODED;
		try (var scene = gpu.metal().createTexture(new MetalTexture.Descriptor(
			linear ? MetalTexture.Format.RGBA16_FLOAT : MetalTexture.Format.BGRA8_UNORM, width, height, 1));
			 var depth = gpu.metal().createTexture(new MetalTexture.Descriptor(MetalTexture.Format.DEPTH32_FLOAT, width, height, 1));
			 var output = gpu.metal().createTexture(new MetalTexture.Descriptor(MetalTexture.Format.BGRA8_UNORM, width, height, 1));
			 var sceneView = gpu.wrapAttachment(scene, "grade-depth scene");
			 var depthView = gpu.wrapAttachment(depth, "grade-depth world");
			 var outputView = gpu.wrapAttachment(output, "grade-depth output");
			 var commands = queue.createCommandBuffer()) {
			FrameBindings absent = WorldComposition.world(scene, sceneView.metal(), width, height, null, null, null, encoding);
			if (absent.worldDepth() != null || absent.worldDepthView() != null
				|| absent.worldDepthWidth() != 0 || absent.worldDepthHeight() != 0
				|| absent.stage() != WorldComposition.PACK_POST || absent.colorEncoding() != encoding) {
				throw new AssertionError("Depth-free world bindings lost their world stage or color contract");
			}
			try (var ignored = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				List.of(MetalRenderPass.ColorAttachment.clear(scene, 0.25, 0.25, 0.25, 1)),
				new MetalRenderPass.DepthAttachment(depth, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, worldDepth), 0))) { }
			grade.encode(gpu, commands, runtime, sceneView, depthView, linear ? outputView : sceneView, encoding, UnderwaterFrameInputs.NONE);
			Snapshot snapshot = snapshot(grade);
			// Simulate the subsequent hand-depth clear in the same command buffer.
			try (var ignored = commands.beginRenderPass(new MetalRenderPass.Descriptor(List.of(),
				new MetalRenderPass.DepthAttachment(depth, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0.875), 0))) { }
			commands.commitAndWait();
			ByteBuffer pixels = (linear ? output : scene).readback(queue, 0);
			int expected = readsDepth ? Math.round(worldDepth * 255) : linear ? 137 : 64;
			for (int pixel = 0; pixel < width * height; pixel++) {
				for (int channel = 0; channel < 3; channel++) {
					if (Math.abs((pixels.get(pixel * 4 + channel) & 255) - expected) > 1) {
						throw new AssertionError("World grade changed pixel " + pixel + " channel " + channel);
					}
				}
				if ((pixels.get(pixel * 4 + 3) & 255) != 255) throw new AssertionError("World grade changed alpha");
			}
			assertDepth(depth.readback(queue, 0), 0.875F);
			if (readsDepth) {
				if (snapshot.texture() == null || snapshot.view() == null || snapshot.texture() == depth
					|| snapshot.texture().descriptor().width() != width || snapshot.texture().descriptor().height() != height) {
					throw new AssertionError("Depth-consuming pack did not receive a separate matching snapshot");
				}
				assertDepth(snapshot.texture().readback(queue, 0), worldDepth);
			} else {
				assertAbsent(snapshot);
			}
			return snapshot;
		}
	}

	private static void assertDepth(final ByteBuffer pixels, final float expected) {
		pixels.order(ByteOrder.nativeOrder());
		while (pixels.hasRemaining()) {
			if (pixels.getFloat() != expected) throw new AssertionError("Stored world depth changed after hand-depth clear");
		}
	}

	private static void assertAbsent(final Snapshot snapshot) {
		if (snapshot.texture() != null || snapshot.view() != null) {
			throw new AssertionError("Depth-free grade retained an unused world-depth snapshot");
		}
	}

	private static void assertClosed(final Snapshot snapshot) {
		if (!snapshot.texture().isClosed() || !snapshot.view().isClosed()) {
			throw new AssertionError("Retired depth snapshot resources leaked");
		}
	}

	private static Snapshot snapshot(final MetalWorldGrade grade) {
		// Keep allocation observability local to this fixture rather than adding a production API.
		try {
			var texture = MetalWorldGrade.class.getDeclaredField("depth");
			var view = MetalWorldGrade.class.getDeclaredField("depthView");
			texture.setAccessible(true);
			view.setAccessible(true);
			return new Snapshot((MetalTexture)texture.get(grade), (MetalTextureView)view.get(grade));
		} catch (ReflectiveOperationException error) {
			throw new AssertionError("Could not inspect world-depth snapshot ownership", error);
		}
	}

	private record Snapshot(MetalTexture texture, MetalTextureView view) { }

	private static final String DEPTH_PACK = """
		{
		  "format": 2,
		  "name": "World Depth",
		  "targets": { "post_color": { "format": "bgra8_unorm", "scale": 1.0, "lifetime": "frame" } },
		  "passes": [{ "id": "grade", "kind": "fullscreen", "source": "grade.metal",
		    "reads": ["depth"], "writes": ["post_color"] }]
		}
		""";

	private static final String DEPTH_MSL = """
		#include <metal_stdlib>
		using namespace metal;
		vertex float4 grade_vertex(uint id [[vertex_id]]) {
		    return float4(id == 1 ? 3.0 : -1.0, id == 2 ? 3.0 : -1.0, 0, 1);
		}
		fragment float4 grade_fragment(float4 position [[position]], texture2d<float> depth [[texture(MC_TEX_DEPTH)]]) {
		    return float4(float3(depth.read(uint2(position.xy)).r), 1);
		}
		""";
}
