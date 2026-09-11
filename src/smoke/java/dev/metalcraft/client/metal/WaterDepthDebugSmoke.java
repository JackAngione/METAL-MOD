package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.WorldGeometryAdapter;
import dev.metalcraft.client.shader.water.WaterFrameInputs;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;

/**
 * Actual forward-water depth debug: reverse-Z reconstruction at native and odd half extents.
 *
 * <p>Surface debug uses the interpolated view position. Opaque debug reconstructs from a stored
 * D32 snapshot using the captured inverse projection. Glass fragments keep vanilla shading.
 */
final class WaterDepthDebugSmoke {
	private static final int NATIVE_WIDTH = 8;
	private static final int NATIVE_HEIGHT = 4;
	private static final int HALF_WIDTH = 5;
	private static final int HALF_HEIGHT = 3;
	private static final int VERTEX_STRIDE = 28;
	private static final int BASE_VERTEX = 2;
	private static final int DRAW_VERTICES = 8;
	private static final int STAGES = MetalRenderPass.STAGE_VERTEX | MetalRenderPass.STAGE_FRAGMENT;
	private static final float SURFACE_VIEW_Z = -2.0F;
	private static final float OPAQUE_VIEW_Z = -8.0F;
	private static final float DEPTH_RANGE = 32.0F;
	private static final int SOURCE_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT
		| GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;

	private WaterDepthDebugSmoke() { }

	static void run() {
		var gpu = new MetalGpuDevice(MetalNative.openDefaultDevice().orElseThrow(), (id, type) -> null);
		try {
			ShaderPackRuntime runtime = gpu.shaderPackRuntime();
			if (runtime == null) throw new AssertionError("Missing shader runtime for water depth fixture");
			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
			runExtent(gpu, runtime, NATIVE_WIDTH, NATIVE_HEIGHT, "native");
			runExtent(gpu, runtime, HALF_WIDTH, HALF_HEIGHT, "half");
		} finally {
			gpu.close();
		}
		System.out.println("Water depth debug: reverse-Z reconstruction, native and odd half extents, "
			+ "clear-zero far fallback, mixed water/glass and CPU references passed");
	}

	private static void runExtent(final MetalGpuDevice gpu, final ShaderPackRuntime runtime,
		final int width, final int height, final String label) {
		runtime.resize(width, height);
		WorldGeometryAdapter geometry = runtime.worldGeometry();
		if (geometry == null) throw new AssertionError("Standard did not create its geometry adapter");
		geometry.beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);

		RenderPipeline original = RenderPipeline.builder(RenderPipelines.TERRAIN_SNIPPET)
			.withLocation(Identifier.parse("metalcraft:smoke/water_depth_" + label))
			.withColorTargetState(new ColorTargetState(java.util.Optional.of(BlendFunction.TRANSLUCENT),
				GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.withShaderDefine("ALPHA_CUTOUT", 0.1F)
			.withCull(false)
			.build();
		RenderPipeline water = geometry.waterPipeline(original).orElseThrow(
			() -> new AssertionError("Actual Standard forward-water pipeline was unavailable"));
		MetalCompiledRenderPipeline compiled = (MetalCompiledRenderPipeline)gpu.precompileLinearWorldPipeline(water, null);
		MetalRenderPipeline pipeline = compiled.metal(false, MetalTexture.Format.RGBA16_FLOAT);

		Matrix4f projection = reverseZProjection(width, height);
		Matrix4f inverse = new Matrix4f(projection).invert();
		WaterFrameInputs frame = WaterFrameInputs.create(projection, new Vector3d(), 0L, 0.0F, false)
			.orElseThrow(() -> new AssertionError(label + " reverse-Z frame rejected"));
		float opaqueDeviceDepth = deviceDepth(projection, OPAQUE_VIEW_Z);
		if (!(opaqueDeviceDepth > 0.0F && opaqueDeviceDepth < 1.0F)) {
			throw new AssertionError(label + " opaque device depth was not in (0,1): " + opaqueDeviceDepth);
		}

		try (var queue = gpu.metal().createCommandQueue();
			 var owner = new MetalOpaqueSnapshotOwner(gpu);
			 var surface = gpu.metal().createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.RGBA16_FLOAT, width, height, 1));
			 var opaque = gpu.metal().createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.RGBA16_FLOAT, width, height, 1));
			 var far = gpu.metal().createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.RGBA16_FLOAT, width, height, 1));
			 var atlas = gpu.metal().createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.RGBA16_FLOAT, 1, 1, 1));
			 var lightmap = gpu.metal().createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.RGBA16_FLOAT, 1, 1, 1));
			 var atlasView = atlas.createView();
			 var lightmapView = lightmap.createView();
			 var sampler = gpu.metal().createSampler(new MetalSampler.Descriptor(
				 MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 var vertices = gpu.metal().createBuffer((long)(BASE_VERTEX + DRAW_VERTICES) * VERTEX_STRIDE,
				 MetalBuffer.StorageMode.SHARED);
			 var indices = gpu.metal().createBuffer(12L * Short.BYTES, MetalBuffer.StorageMode.SHARED);
			 var metadata = gpu.metal().createBuffer((long)DRAW_VERTICES * 32, MetalBuffer.StorageMode.SHARED);
			 var projectionBuffer = gpu.metal().createBuffer(64, MetalBuffer.StorageMode.SHARED);
			 var section = gpu.metal().createBuffer(96, MetalBuffer.StorageMode.SHARED);
			 var globals = gpu.metal().createBuffer(64, MetalBuffer.StorageMode.SHARED);
			 var fog = gpu.metal().createBuffer(48, MetalBuffer.StorageMode.SHARED);
			 var waterFrame = gpu.metal().createBuffer(WaterFrameInputs.UNIFORM_BYTES, MetalBuffer.StorageMode.SHARED);
			 var surfaceDraw = gpu.metal().createBuffer(16, MetalBuffer.StorageMode.SHARED);
			 var opaqueDraw = gpu.metal().createBuffer(16, MetalBuffer.StorageMode.SHARED);
			 var sourceColor = (MetalGpuTexture)gpu.createTexture(label + "-opaque-color", SOURCE_USAGE,
				 GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
			 var sourceDepth = (MetalGpuTexture)gpu.createTexture(label + "-opaque-depth", SOURCE_USAGE,
				 GpuFormat.D32_FLOAT, width, height, 1, 1);
			 var sourceColorView = (MetalGpuTextureView)gpu.createTextureView(sourceColor);
			 var sourceDepthView = (MetalGpuTextureView)gpu.createTextureView(sourceDepth)) {
			atlas.upload(queue, 0, halfPixel(2, 2, 2, 1));
			lightmap.upload(queue, 0, halfPixel(1, 1, 1, 1));
			writeVertices(vertices, inverse, projection);
			writeSortedIndices(indices);
			writeMetadata(metadata);
			writeUniforms(projectionBuffer, section, globals, fog, projection);
			writeFrame(waterFrame, frame);
			writeDraw(surfaceDraw, 2);
			writeDraw(opaqueDraw, 3);

			var encoder = (MetalCommandEncoder)gpu.createCommandEncoder();
			encoder.clearColorAndDepthTextures(sourceColor, new Vector4f(0.5F, 0.25F, 0.125F, 1),
				sourceDepth, opaqueDeviceDepth);
			MetalOpaqueSnapshotOwner.Snapshot snapshot = owner.capture(encoder, sourceColorView, sourceDepthView)
				.orElseThrow(() -> new AssertionError(label + " opaque snapshot was not captured"));
			if (snapshot.width() != width || snapshot.height() != height) {
				throw new AssertionError(label + " snapshot extent " + snapshot.width() + "x" + snapshot.height()
					+ " != " + width + "x" + height);
			}
			encoder.finishPendingWork();

			draw(queue, pipeline, surface, atlasView, lightmapView, sampler, vertices, indices, metadata,
				projectionBuffer, section, globals, fog, surfaceDraw, waterFrame,
				snapshot.color().metal(), snapshot.depth().metal());
			draw(queue, pipeline, opaque, atlasView, lightmapView, sampler, vertices, indices, metadata,
				projectionBuffer, section, globals, fog, opaqueDraw, waterFrame,
				snapshot.color().metal(), snapshot.depth().metal());

			encoder.clearColorAndDepthTextures(sourceColor, new Vector4f(0, 0, 0, 0), sourceDepth, 0.0);
			MetalOpaqueSnapshotOwner.Snapshot farSnapshot = owner.capture(encoder, sourceColorView, sourceDepthView)
				.orElseThrow(() -> new AssertionError(label + " clear-zero snapshot was not captured"));
			encoder.finishPendingWork();
			draw(queue, pipeline, far, atlasView, lightmapView, sampler, vertices, indices, metadata,
				projectionBuffer, section, globals, fog, opaqueDraw, waterFrame,
				farSnapshot.color().metal(), farSnapshot.depth().metal());

			ByteBuffer surfacePixels = surface.readback(queue, 0).order(ByteOrder.nativeOrder());
			ByteBuffer opaquePixels = opaque.readback(queue, 0).order(ByteOrder.nativeOrder());
			ByteBuffer farPixels = far.readback(queue, 0).order(ByteOrder.nativeOrder());
			float surfaceEncoded = encode(SURFACE_VIEW_Z);
			float opaqueEncoded = encode(OPAQUE_VIEW_Z);
			Vector3f reconstructed = reconstruct(inverse, 1.5F, 1.5F, opaqueDeviceDepth, width, height);
			if (Math.abs(reconstructed.z - OPAQUE_VIEW_Z) > 0.15F) {
				throw new AssertionError(label + " CPU reconstruction view Z expected " + OPAQUE_VIEW_Z
					+ " actual " + reconstructed.z);
			}
			float farEncoded = encodeView(reconstruct(inverse, 1.5F, 1.5F, 0.0F, width, height));
			if (farEncoded < 0.95F) {
				throw new AssertionError(label + " clear-zero far encode was " + farEncoded);
			}

			int waterX = 0;
			int glassX = width - 1;
			for (int y = 0; y < height; y++) {
				assertRgb(surfacePixels, waterX, y, width, 1.0, surfaceEncoded, 0.0, label + " surface water");
				assertRgb(opaquePixels, waterX, y, width, opaqueEncoded, 1.0, 0.0, label + " opaque water");
				assertRgb(farPixels, waterX, y, width, farEncoded, 1.0, 0.0, label + " opaque far/clear");
				assertGlass(surfacePixels, glassX, y, width, label + " surface glass");
				assertGlass(opaquePixels, glassX, y, width, label + " opaque glass");
			}
			if (!(surfaceEncoded < opaqueEncoded)) {
				throw new AssertionError(label + " surface encode was not nearer than opaque");
			}
		}
	}

	private static void draw(final MetalCommandQueue queue, final MetalRenderPipeline pipeline,
		final MetalTexture target, final MetalTextureView atlas, final MetalTextureView lightmap,
		final MetalSampler sampler, final MetalBuffer vertices, final MetalBuffer indices,
		final MetalBuffer metadata, final MetalBuffer projection, final MetalBuffer section,
		final MetalBuffer globals, final MetalBuffer fog, final MetalBuffer waterDraw,
		final MetalBuffer waterFrame, final MetalTextureView opaqueColor, final MetalTextureView opaqueDepth) {
		try (var commands = queue.createCommandBuffer();
			 var pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				 MetalRenderPass.ColorAttachment.clear(target, 0, 0, 0, 0)))) {
			pass.setPipeline(pipeline);
			pass.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, vertices, 0);
			pass.setUniformBuffer(0, globals, 0, STAGES);
			pass.setUniformBuffer(1, fog, 0, STAGES);
			pass.setUniformBuffer(2, projection, 0, STAGES);
			pass.setUniformBuffer(3, section, 0, STAGES);
			pass.setTexture(4, atlas, STAGES);
			pass.setSampler(4, sampler, STAGES);
			pass.setTexture(5, lightmap, STAGES);
			pass.setSampler(5, sampler, STAGES);
			pass.setUniformBuffer(13, waterFrame, 0, STAGES);
			pass.setTexture(12, opaqueColor, MetalRenderPass.STAGE_FRAGMENT);
			pass.setTexture(13, opaqueDepth, MetalRenderPass.STAGE_FRAGMENT);
			pass.setUniformBuffer(14, metadata, 0, MetalRenderPass.STAGE_VERTEX);
			pass.setUniformBuffer(15, waterDraw, 0, STAGES);
			pass.drawIndexed(MetalRenderPass.Primitive.TRIANGLE, indices, 0,
				MetalRenderPass.IndexType.UINT16, 12, 1, BASE_VERTEX, 0);
			pass.close();
			commands.commitAndWait();
		}
	}

	private static void writeVertices(final MetalBuffer vertices, final Matrix4f inverse, final Matrix4f projection) {
		float deviceDepth = deviceDepth(projection, SURFACE_VIEW_Z);
		try (var mapping = vertices.map()) {
			ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			for (int vertex = 0; vertex < BASE_VERTEX; vertex++) putVertex(bytes, vertex, 0, 0, 0, 0, 0);
			putUnprojected(bytes, 2, -1, -1, deviceDepth, inverse, 0x800000FF, 0);
			putUnprojected(bytes, 3, 0.5F, -1, deviceDepth, inverse, 0x800000FF, 1);
			putUnprojected(bytes, 4, 0.5F, 1, deviceDepth, inverse, 0x800000FF, 1);
			putUnprojected(bytes, 5, -1, 1, deviceDepth, inverse, 0x800000FF, 0);
			putUnprojected(bytes, 6, -0.5F, -1, deviceDepth, inverse, 0x8000FF00, 0);
			putUnprojected(bytes, 7, 1, -1, deviceDepth, inverse, 0x8000FF00, 1);
			putUnprojected(bytes, 8, 1, 1, deviceDepth, inverse, 0x8000FF00, 1);
			putUnprojected(bytes, 9, -0.5F, 1, deviceDepth, inverse, 0x8000FF00, 0);
		}
	}

	private static void putUnprojected(final ByteBuffer bytes, final int vertex, final float ndcX, final float ndcY,
		final float deviceDepth, final Matrix4f inverse, final int rgba, final float u) {
		Vector4f view = inverse.transform(new Vector4f(ndcX, ndcY, deviceDepth, 1.0F), new Vector4f());
		view.div(view.w);
		putVertex(bytes, vertex, view.x, view.y, view.z, rgba, u);
	}

	private static void putVertex(final ByteBuffer bytes, final int vertex, final float x, final float y,
		final float z, final int rgba, final float u) {
		int offset = vertex * VERTEX_STRIDE;
		bytes.putFloat(offset, x).putFloat(offset + 4, y).putFloat(offset + 8, z);
		bytes.putInt(offset + 12, rgba);
		bytes.putFloat(offset + 16, u).putFloat(offset + 20, y > 0 ? 1 : 0);
		bytes.putShort(offset + 24, (short)240).putShort(offset + 26, (short)240);
	}

	private static void writeSortedIndices(final MetalBuffer indices) {
		try (var mapping = indices.map()) {
			mapping.bytes().order(ByteOrder.nativeOrder()).asShortBuffer().put(new short[]{
				4, 5, 6, 6, 7, 4,
				0, 1, 2, 2, 3, 0
			});
		}
	}

	private static void writeMetadata(final MetalBuffer metadata) {
		try (var mapping = metadata.map()) {
			ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			for (int vertex = 0; vertex < DRAW_VERTICES; vertex++) {
				int offset = vertex * 32;
				bytes.putFloat(offset, 0).putFloat(offset + 4, 1).putFloat(offset + 8, 0)
					.putFloat(offset + 12, vertex < 4 ? 1 : 0);
				bytes.putFloat(offset + 16, 0).putFloat(offset + 20, 0).putFloat(offset + 24, 0)
					.putFloat(offset + 28, 0);
			}
		}
	}

	private static void writeUniforms(final MetalBuffer projection, final MetalBuffer section,
		final MetalBuffer globals, final MetalBuffer fog, final Matrix4f proj) {
		for (MetalBuffer buffer : List.of(projection, section, globals, fog)) {
			try (var mapping = buffer.map()) {
				ByteBuffer bytes = mapping.bytes();
				for (int i = 0; i < bytes.capacity(); i++) bytes.put(i, (byte)0);
			}
		}
		try (var mapping = projection.map()) { putMatrix(mapping.bytes(), 0, proj); }
		try (var mapping = section.map()) {
			ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			identity(bytes);
			bytes.putFloat(64, 1).putInt(72, 1).putInt(76, 1);
		}
		try (var mapping = fog.map()) {
			ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
			for (int i = 0; i < 6; i++) bytes.putFloat(16 + i * 4, i % 2 == 0 ? 100 : 200);
		}
	}

	private static void writeFrame(final MetalBuffer buffer, final WaterFrameInputs frame) {
		try (var mapping = buffer.map()) {
			ByteBuffer bytes = mapping.bytes();
			bytes.clear();
			frame.write(bytes);
		}
	}

	private static void writeDraw(final MetalBuffer draw, final int debugMode) {
		try (var mapping = draw.map()) {
			mapping.bytes().order(ByteOrder.nativeOrder()).putInt(BASE_VERTEX).putInt(DRAW_VERTICES)
				.putInt(debugMode).putInt(0);
		}
	}

	private static void identity(final ByteBuffer bytes) {
		bytes.order(ByteOrder.nativeOrder());
		for (int i = 0; i < 4; i++) bytes.putFloat(i * 20, 1);
	}

	private static void putMatrix(final ByteBuffer bytes, final int offset, final Matrix4f matrix) {
		bytes.order(ByteOrder.nativeOrder());
		bytes.putFloat(offset, matrix.m00()).putFloat(offset + 4, matrix.m01())
			.putFloat(offset + 8, matrix.m02()).putFloat(offset + 12, matrix.m03())
			.putFloat(offset + 16, matrix.m10()).putFloat(offset + 20, matrix.m11())
			.putFloat(offset + 24, matrix.m12()).putFloat(offset + 28, matrix.m13())
			.putFloat(offset + 32, matrix.m20()).putFloat(offset + 36, matrix.m21())
			.putFloat(offset + 40, matrix.m22()).putFloat(offset + 44, matrix.m23())
			.putFloat(offset + 48, matrix.m30()).putFloat(offset + 52, matrix.m31())
			.putFloat(offset + 56, matrix.m32()).putFloat(offset + 60, matrix.m33());
	}

	private static Matrix4f reverseZProjection(final int width, final int height) {
		return new Matrix4f().perspective((float)Math.toRadians(70), (float)width / (float)height,
			1024.0F, 0.05F, true);
	}

	private static float deviceDepth(final Matrix4f projection, final float viewZ) {
		Vector4f clip = projection.transform(new Vector4f(0, 0, viewZ, 1.0F), new Vector4f());
		return clip.z / clip.w;
	}

	private static float encode(final float viewZ) {
		return Math.min(1.0F, Math.max(0.0F, -viewZ / DEPTH_RANGE));
	}

	private static float encodeView(final Vector3f view) {
		return encode(view.z);
	}

	private static Vector3f reconstruct(final Matrix4f inverse, final float pixelX, final float pixelY,
		final float deviceDepth, final int width, final int height) {
		float uvx = pixelX / width;
		float uvy = pixelY / height;
		float ndcX = uvx * 2.0F - 1.0F;
		float ndcY = -(uvy * 2.0F - 1.0F);
		Vector4f view = inverse.transform(new Vector4f(ndcX, ndcY, deviceDepth, 1.0F), new Vector4f());
		view.div(view.w);
		return new Vector3f(view.x, view.y, view.z);
	}

	private static ByteBuffer halfPixel(final float red, final float green, final float blue, final float alpha) {
		ByteBuffer bytes = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder());
		for (float value : new float[]{red, green, blue, alpha}) bytes.putShort(Float.floatToFloat16(value));
		return bytes.flip();
	}

	private static void assertRgb(final ByteBuffer pixels, final int x, final int y, final int width,
		final double red, final double green, final double blue, final String label) {
		int offset = (y * width + x) * 8;
		double[] expected = {red, green, blue};
		for (int channel = 0; channel < 3; channel++) {
			double actual = Float.float16ToFloat(pixels.getShort(offset + channel * 2));
			if (!Double.isFinite(actual) || Math.abs(actual - expected[channel]) > 0.06) {
				throw new AssertionError(label + " at " + x + "," + y + " channel " + channel
					+ " expected=" + expected[channel] + " actual=" + actual);
			}
		}
	}

	private static void assertGlass(final ByteBuffer pixels, final int x, final int y, final int width,
		final String label) {
		int offset = (y * width + x) * 8;
		double red = Float.float16ToFloat(pixels.getShort(offset));
		double green = Float.float16ToFloat(pixels.getShort(offset + 2));
		double blue = Float.float16ToFloat(pixels.getShort(offset + 4));
		if (!(green > red && green > blue && blue < 0.08)) {
			throw new AssertionError(label + " at " + x + "," + y + " lost vanilla glass shading: "
				+ red + "," + green + "," + blue);
		}
	}

	private record Vector3f(float x, float y, float z) { }
}
