package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalLong;
import net.minecraft.resources.Identifier;

public final class MetalShaderTranslationSmoke {
	private static final int SPIRV_MAGIC = 0x07230203;

	private static final String VERTEX_GLSL = """
		#version 450
		layout(location = 0) out vec2 texCoord;

		void main() {
		    vec2 positions[3] = vec2[](
		        vec2(-1.0, -1.0),
		        vec2( 3.0, -1.0),
		        vec2(-1.0,  3.0)
		    );
		    vec2 position = positions[gl_VertexID % 3];
		    gl_Position = vec4(position, 0.0, 1.0);
		    texCoord = position * 0.5 + 0.5;
		}
		""";

	private static final String FRAGMENT_GLSL = """
		#version 450
		layout(location = 0) in vec2 texCoord;
		layout(location = 0) out vec4 color;

		void main() {
		    color = vec4(texCoord, 0.25, 1.0);
		}
		""";
	private static final String MAPPED_VERTEX_GLSL = """
		#version 450
		in vec4 Color;
		in vec3 Position;
		layout(location = 0) out vec4 vertexColor;

		void main() {
		    gl_Position = vec4(Position, 1.0);
		    vertexColor = Color;
		}
		""";
	private static final String MAPPED_FRAGMENT_GLSL = """
		#version 450
		layout(location = 0) in vec4 vertexColor;
		layout(location = 0) out vec4 color;

		void main() {
		    color = vertexColor;
		}
		""";
	private static final String SPARSE_FRAGMENT_GLSL = """
		#version 450
		in vec2 texCoord;
		layout(location = 0) out vec4 color;

		void main() {
		    color = vec4(texCoord, 0.0, 1.0);
		}
		""";
	private static final String SPARSE_VERTEX_GLSL = """
		#version 450
		out float unusedProgress;
		out vec2 texCoord;

		void main() {
		    gl_Position = vec4(0.0);
		    unusedProgress = 0.0;
		    texCoord = vec2(0.0);
		}
		""";
	private static final String BOUND_FRAGMENT_GLSL = """
		#version 450
		layout(binding = 3, std140) uniform BoundData { vec4 Tint; };
		layout(binding = 5) uniform sampler2D BoundTexture;
		layout(location = 0) out vec4 color;
		void main() { color = Tint * texture(BoundTexture, vec2(0.5)); }
		""";
	private static final String ORIENTATION_VERTEX_GLSL = """
		#version 450
		void main() {
		    vec2 positions[6] = vec2[](
		        vec2(-1.0, 0.0), vec2(1.0, 0.0), vec2(-1.0, 1.0),
		        vec2(-1.0, 1.0), vec2(1.0, 0.0), vec2(1.0, 1.0)
		    );
		    gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
		}
		""";
	private static final String RED_FRAGMENT_GLSL = """
		#version 450
		layout(location = 0) out vec4 color;
		void main() { color = vec4(1.0, 0.0, 0.0, 1.0); }
		""";
	private static final String MIP_VIEWPORT_VERTEX_GLSL = """
		#version 450
		void main() {
		    vec2 positions[6] = vec2[](
		        vec2(0.0, -1.0), vec2(1.0, -1.0), vec2(0.0, 1.0),
		        vec2(0.0, 1.0), vec2(1.0, -1.0), vec2(1.0, 1.0)
		    );
		    gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
		}
		""";
	private static final String TEXEL_VERTEX_GLSL = """
		#version 450
		layout(binding = 0) uniform isamplerBuffer Values;
		layout(location = 0) flat out int sampledValue;
		void main() {
		    vec2 positions[3] = vec2[](
		        vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0)
		    );
		    gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
		    sampledValue = texelFetch(Values, 0).r;
		}
		""";
	private static final String TEXEL_FRAGMENT_GLSL = """
		#version 450
		layout(location = 0) flat in int sampledValue;
		layout(location = 0) out vec4 color;
		void main() {
		    color = sampledValue == 42 ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(0.0, 1.0, 0.0, 1.0);
		}
		""";
	private static final String MIP_FRAGMENT_GLSL = """
		#version 450
		layout(binding = 0) uniform sampler2D Source;
		layout(location = 0) out vec4 color;
		void main() { color = textureLod(Source, vec2(0.5), 2.0); }
		""";

	private MetalShaderTranslationSmoke() {
	}

	public static void main(final String[] arguments) {
		assertTransientArenaSuballocation();
		MetalShaderTranslator.PipelineTranslation translated = MetalShaderTranslator.translatePipeline(
			VERTEX_GLSL,
			"smoke/fullscreen.vert",
			FRAGMENT_GLSL,
			"smoke/fullscreen.frag"
		);
		assertSpirv(translated.vertex());
		assertSpirv(translated.fragment());
		assertFormatCoverage();
		MetalShaderTranslator.Translation bound = MetalShaderTranslator.translate(
			BOUND_FRAGMENT_GLSL, MetalShaderTranslator.Stage.FRAGMENT, "smoke/bound.frag"
		);
		if (!bound.metalSource().contains("[[buffer(3)]]")
			|| !bound.metalSource().contains("[[texture(5)]]")
			|| !bound.metalSource().contains("[[sampler(5)]]")) {
			throw new AssertionError("SPIRV-Cross did not preserve explicit Metal resource bindings:\n" + bound.metalSource());
		}

		RenderPipeline mappedPipelineDefinition = mappedPipeline();
		String mappedVertex = Blaze3DMetalMappings.vertexShaderWithLocations(
			MAPPED_VERTEX_GLSL, mappedPipelineDefinition.getVertexFormatBindings()
		);
		MetalShaderTranslator.PipelineTranslation mappedShaders = MetalShaderTranslator.translatePipeline(
			mappedVertex,
			"smoke/mapped.vert",
			MAPPED_FRAGMENT_GLSL,
			"smoke/mapped.frag"
		);
		MetalRenderPipeline.Descriptor mappedDescriptor = Blaze3DMetalMappings.pipelineDescriptor(mappedPipelineDefinition, mappedShaders);
		assertMappedDescriptor(mappedDescriptor);

		if (!MetalNative.load()) {
			throw new AssertionError("MetalCraft native library did not load", MetalNative.loadFailure().orElse(null));
		}
		try (MetalDevice device = MetalNative.openDefaultDevice().orElseThrow();
			 MetalRenderPipeline pipeline = device.createRenderPipeline(new MetalRenderPipeline.GlslDescriptor(
				 VERTEX_GLSL,
				 "smoke/fullscreen.vert",
				 FRAGMENT_GLSL,
				 "smoke/fullscreen.frag",
				 MetalTexture.Format.RGBA8_UNORM,
				 null
			 ));
			 MetalRenderPipeline sparsePipeline = device.createRenderPipeline(new MetalRenderPipeline.GlslDescriptor(
				 SPARSE_VERTEX_GLSL,
				 "smoke/sparse.vert",
				 SPARSE_FRAGMENT_GLSL,
				 "smoke/sparse.frag",
				 MetalTexture.Format.BGRA8_UNORM,
				 null
			 ))) {
			if (pipeline.isClosed() || sparsePipeline.isClosed()) {
				throw new AssertionError("Translated Metal pipeline unexpectedly closed");
			}
			assertFramebufferOrientation(device);
			assertMipRenderTargetViewport(device);
			assertTexelBufferSampling(device);
			assertMipLevelSampling(device);
			assertQueriesAndLifetime(device, pipeline);
			MetalRenderPipeline.Descriptor drawableMappedDescriptor = new MetalRenderPipeline.Descriptor(
				mappedDescriptor.vertexSource(), mappedDescriptor.vertexFunction(), mappedDescriptor.fragmentSource(), mappedDescriptor.fragmentFunction(),
				mappedDescriptor.colorTargets(), null, mappedDescriptor.vertexDescriptor(), MetalRenderPipeline.DepthState.DISABLED,
				new MetalRenderPipeline.RasterState(MetalRenderPipeline.CullMode.NONE, MetalRenderPipeline.FillMode.FILL)
			);
			try (MetalRenderPipeline mappedPipeline = device.createRenderPipeline(drawableMappedDescriptor)) {
				if (mappedPipeline.isClosed()) {
					throw new AssertionError("Mapped Blaze3D Metal pipeline unexpectedly closed");
				}
				assertMappedVertexDraw(device, mappedPipeline);
			}
		}

		try {
			MetalShaderTranslator.translate("#version 450\nthis is not GLSL", MetalShaderTranslator.Stage.VERTEX, "smoke/broken.vert");
			throw new AssertionError("Invalid GLSL unexpectedly compiled");
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains("smoke/broken.vert")) {
				throw new AssertionError("Shader diagnostic omitted its source name", expected);
			}
		}
	}

	private static void assertTransientArenaSuballocation() {
		MetalDevice metal = MetalNative.openDefaultDevice().orElseThrow();
		MetalGpuDevice device = new MetalGpuDevice(metal, (identifier, type) -> null);
		try {
			MetalCommandEncoder encoder = (MetalCommandEncoder)device.createCommandEncoder();
			MetalTransientMemory transientMemory = (MetalTransientMemory)encoder.transientMemory();
			GpuBufferSlice first = transientMemory.allocateGpu(64, 256, GpuBuffer.USAGE_VERTEX, 64, 1);
			GpuBufferSlice second = transientMemory.allocateGpu(64, 256, GpuBuffer.USAGE_INDEX, 64, 1);
			if (first.buffer() != second.buffer() || first.offset() != 0L || second.offset() != 256L) {
				throw new AssertionError("Transient GPU allocations were not aligned slices of one shared arena");
			}
			if (transientMemory.nativeAllocationCountForTesting() != 1) {
				throw new AssertionError("Two transient slices created more than one native arena buffer");
			}
			encoder.submit();
		} finally {
			device.close();
		}
	}

	private static void assertMipRenderTargetViewport(final MetalDevice device) {
		try (MetalRenderPipeline pipeline = device.createRenderPipeline(new MetalRenderPipeline.GlslDescriptor(
				 MIP_VIEWPORT_VERTEX_GLSL, "smoke/mip-viewport.vert", RED_FRAGMENT_GLSL, "smoke/red.frag",
				 MetalTexture.Format.RGBA8_UNORM, null));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 8, 8, 3));
			 MetalCommandBuffer commands = queue.createCommandBuffer();
			 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				 new MetalRenderPass.ColorAttachment(
					 color, 2, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0.0, 0.0, 1.0, 1.0
				 )))) {
			pass.setPipeline(pipeline);
			pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 6, 1, 0);
			pass.close();
			commands.commitAndWait();
			ByteBuffer pixels = color.readback(queue, 2);
			int leftBlue = Byte.toUnsignedInt(pixels.get(2));
			int rightRed = Byte.toUnsignedInt(pixels.get(4));
			if (leftBlue < 200 || rightRed < 200) {
				throw new AssertionError("Metal mip render target used the base-level viewport: leftBlue=" + leftBlue + ", rightRed=" + rightRed);
			}
		}
	}

	private static void assertMipLevelSampling(final MetalDevice device) {
		try (MetalRenderPipeline pipeline = device.createRenderPipeline(new MetalRenderPipeline.GlslDescriptor(
				 VERTEX_GLSL, "smoke/mip.vert", MIP_FRAGMENT_GLSL, "smoke/mip.frag",
				 MetalTexture.Format.RGBA8_UNORM, null));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture source = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 8, 8, 4));
			 MetalTextureView sourceView = source.createView();
			 MetalSampler sampler = device.createSampler(new MetalSampler.Descriptor(
				 MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 MetalTexture color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 8, 8, 1))) {
			int[] colors = {0xFF0000FF, 0xFF00FF00, 0xFFFF0000, 0xFF00FFFF};
			for (int mip = 0; mip < colors.length; mip++) {
				int size = 8 >> mip;
				ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * Integer.BYTES).order(ByteOrder.nativeOrder());
				for (int pixel = 0; pixel < size * size; pixel++) pixels.putInt(colors[mip]);
				source.upload(queue, mip, pixels.flip());
			}
			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					 MetalRenderPass.ColorAttachment.clear(color, 0.0, 0.0, 0.0, 1.0)))) {
				pass.setPipeline(pipeline);
				pass.setTexture(0, sourceView);
				pass.setSampler(0, sampler);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				pass.close();
				commands.commitAndWait();
			}
			ByteBuffer pixels = color.readback(queue, 0);
			int red = Byte.toUnsignedInt(pixels.get((4 * 8 + 4) * 4));
			int green = Byte.toUnsignedInt(pixels.get((4 * 8 + 4) * 4 + 1));
			int blue = Byte.toUnsignedInt(pixels.get((4 * 8 + 4) * 4 + 2));
			if (red > 20 || green > 20 || blue < 200) {
				throw new AssertionError("Metal explicit LOD 2 sampled the wrong mip level: rgb=" + red + "," + green + "," + blue);
			}
		}
	}

	private static void assertFramebufferOrientation(final MetalDevice device) {
		try (MetalRenderPipeline pipeline = device.createRenderPipeline(new MetalRenderPipeline.GlslDescriptor(
				 ORIENTATION_VERTEX_GLSL, "smoke/orientation.vert", RED_FRAGMENT_GLSL, "smoke/red.frag",
				 MetalTexture.Format.RGBA8_UNORM, null));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 8, 8, 1));
			 MetalCommandBuffer commands = queue.createCommandBuffer();
			 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				 MetalRenderPass.ColorAttachment.clear(color, 0.0, 0.0, 1.0, 1.0)))) {
			pass.setPipeline(pipeline);
			pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 6, 1, 0);
			pass.close();
			commands.commitAndWait();
			ByteBuffer pixels = color.readback(queue, 0);
			int topRed = Byte.toUnsignedInt(pixels.get((1 * 8 + 4) * 4));
			int bottomRed = Byte.toUnsignedInt(pixels.get((6 * 8 + 4) * 4));
			if (topRed > 20 || bottomRed < 200) {
				throw new AssertionError("Vulkan-to-Metal framebuffer coordinates were not converted: top red=" + topRed + ", bottom red=" + bottomRed);
			}
		}
	}

	private static void assertTexelBufferSampling(final MetalDevice device) {
		try (MetalRenderPipeline pipeline = device.createRenderPipeline(new MetalRenderPipeline.GlslDescriptor(
				 TEXEL_VERTEX_GLSL, "smoke/texel.vert", TEXEL_FRAGMENT_GLSL, "smoke/texel.frag",
				 MetalTexture.Format.RGBA8_UNORM, null));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 8, 8, 1));
			 MetalBuffer values = device.createBuffer(256, MetalBuffer.StorageMode.SHARED)) {
			try (MetalBuffer.Mapping mapping = values.map()) {
				mapping.bytes().put(0, (byte)42);
			}
			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					 MetalRenderPass.ColorAttachment.clear(color, 0.0, 1.0, 0.0, 1.0)))) {
				pass.setPipeline(pipeline);
				pass.setTexelBuffer(0, values, 0L, 1L, MetalTexture.Format.R8_SINT);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				pass.close();
				commands.commitAndWait();
			}
			ByteBuffer pixels = color.readback(queue, 0);
			int red = Byte.toUnsignedInt(pixels.get((4 * 8 + 4) * 4));
			int green = Byte.toUnsignedInt(pixels.get((4 * 8 + 4) * 4 + 1));
			if (red < 200 || green > 20) {
				throw new AssertionError("Metal texel-buffer shader did not read the bound R8_SINT value");
			}
		}
	}

	private static void assertMappedVertexDraw(final MetalDevice device, final MetalRenderPipeline pipeline) {
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 16, 16, 1));
			 MetalBuffer vertices = device.createBuffer(3L * 16L, MetalBuffer.StorageMode.SHARED);
			 MetalBuffer indices = device.createBuffer(3L * Short.BYTES, MetalBuffer.StorageMode.SHARED)) {
			try (MetalBuffer.Mapping mapping = vertices.map()) {
				ByteBuffer bytes = mapping.bytes().order(ByteOrder.nativeOrder());
				putVertex(bytes, -1.0F, -1.0F, 0.0F, 0xFF0000FF);
				putVertex(bytes, 3.0F, -1.0F, 0.0F, 0xFF00FF00);
				putVertex(bytes, -1.0F, 3.0F, 0.0F, 0xFFFF0000);
			}
			try (MetalBuffer.Mapping mapping = indices.map()) {
				mapping.bytes().order(ByteOrder.nativeOrder()).asShortBuffer().put(new short[]{0, 1, 2});
			}
			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					 MetalRenderPass.ColorAttachment.clear(color, 0.0, 0.0, 0.0, 1.0)))) {
				pass.setPipeline(pipeline);
				pass.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, vertices, 0L);
				pass.drawIndexed(MetalRenderPass.Primitive.TRIANGLE, indices, 0L, MetalRenderPass.IndexType.UINT16, 3, 1, 0, 0);
				pass.close();
				commands.commitAndWait();
			}
			assertRenderedPixelsVary(color.readback(queue, 0));
		}
	}

	private static void putVertex(final ByteBuffer bytes, final float x, final float y, final float z, final int color) {
		bytes.putFloat(x).putFloat(y).putFloat(z).putInt(color);
	}

	private static void assertQueriesAndLifetime(final MetalDevice device, final MetalRenderPipeline pipeline) {
		if (!device.supportsTimestampQueries()) {
			throw new AssertionError("The active Apple GPU did not expose Metal timestamp query support");
		}
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalTimestampQueryPool queries = device.createTimestampQueryPool(4);
			 MetalTexture color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 16, 16, 1));
			 MetalBuffer indexUpload = device.createBuffer(3L * Short.BYTES, MetalBuffer.StorageMode.SHARED);
			 MetalBuffer indices = device.createBuffer(3L * Short.BYTES, MetalBuffer.StorageMode.PRIVATE)) {
			try (MetalBuffer.Mapping mapping = indexUpload.map()) {
				mapping.bytes().order(ByteOrder.nativeOrder()).asShortBuffer().put(new short[]{0, 1, 2});
			}
			MetalBuffer source = device.createBuffer(4096, MetalBuffer.StorageMode.SHARED);
			MetalBuffer destination = device.createBuffer(4096, MetalBuffer.StorageMode.PRIVATE);
			try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
				commands.writeTimestamp(queries, 0);
				commands.copyBuffer(source, 0, destination, 0, 4096);
				commands.writeTimestamp(queries, 1);
				if (queries.getValue(0).isPresent()) {
					throw new AssertionError("Uncommitted Metal timestamp unexpectedly reported as available");
				}
				if (commands.retainedResourceCount() < 3) {
					throw new AssertionError("Metal command buffer did not retain its in-flight query and buffer resources");
				}
				commands.commit();
				source.close();
				destination.close();
				commands.waitUntilCompleted();
				if (commands.retainedResourceCount() != 0) {
					throw new AssertionError("Metal command buffer did not release resources after GPU completion");
				}
			}

			int completedQueryCount = 2;
			if (device.supportsRenderTimestampQueries()) {
				try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
					commands.copyBuffer(indexUpload, 0L, indices, 0L, 3L * Short.BYTES);
					try (MetalRenderPass clearPass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
						MetalRenderPass.ColorAttachment.clear(color, 0.0, 0.0, 0.0, 1.0)
					))) {
						// Match Minecraft's separate attachment-clear pass.
					}
					try (MetalRenderPass renderPass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
						new MetalRenderPass.ColorAttachment(
							color, MetalRenderPass.LoadAction.LOAD, MetalRenderPass.StoreAction.STORE, 0.0, 0.0, 0.0, 0.0
						)
					))) {
						renderPass.setScissor(0, 0, 16, 16);
						renderPass.setPipeline(pipeline);
						renderPass.writeTimestamp(queries, 2);
						renderPass.drawIndexed(
							MetalRenderPass.Primitive.TRIANGLE, indices, 0L, MetalRenderPass.IndexType.UINT16, 3, 1, 3, 0
						);
						renderPass.writeTimestamp(queries, 3);
					}
					commands.commitAndWait();
				}
				assertRenderedPixelsVary(color.readback(queue, 0));
				completedQueryCount = 4;
			}

			OptionalLong[] values = queries.getValues(0, completedQueryCount);
			for (OptionalLong value : values) {
				if (value.isEmpty()) {
					throw new AssertionError("Completed Metal timestamp query was unavailable");
				}
			}
			if (values[1].getAsLong() < values[0].getAsLong()
				|| (completedQueryCount == 4 && values[3].getAsLong() < values[2].getAsLong())) {
				throw new AssertionError("Metal timestamp queries were not monotonically ordered");
			}
		}
	}

	private static void assertRenderedPixelsVary(final ByteBuffer pixels) {
		ByteBuffer values = pixels.order(ByteOrder.nativeOrder());
		int first = values.getInt(0);
		for (int offset = Integer.BYTES; offset < values.remaining(); offset += Integer.BYTES) {
			if (values.getInt(offset) != first) {
				return;
			}
		}
		throw new AssertionError("Metal fullscreen draw readback contains only one color");
	}

	private static RenderPipeline mappedPipeline() {
		VertexFormat vertices = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.build();
		return RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("metalcraft", "mapping_smoke"))
			.withVertexShader(Identifier.fromNamespaceAndPath("metalcraft", "mapping_smoke"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("metalcraft", "mapping_smoke"))
			.withVertexBinding(0, vertices)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withColorTargetState(new ColorTargetState(Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_COLOR))
			.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false, 2.0F, 1.0F))
			.withPolygonMode(PolygonMode.WIREFRAME)
			.withCull(true)
			.build();
	}

	private static void assertMappedDescriptor(final MetalRenderPipeline.Descriptor descriptor) {
		if (descriptor.vertexDescriptor().attributes().size() != 2 || descriptor.vertexDescriptor().layouts().size() != 1) {
			throw new AssertionError("Blaze3D vertex formats did not produce a complete Metal vertex descriptor");
		}
		MetalRenderPipeline.ColorTarget color = descriptor.colorTargets().getFirst();
		if (color.format() != MetalTexture.Format.RGBA8_UNORM
			|| color.writeMask() != MetalRenderPipeline.WRITE_RED + MetalRenderPipeline.WRITE_GREEN + MetalRenderPipeline.WRITE_BLUE
			|| color.blendState() == null) {
			throw new AssertionError("Blaze3D color/blend state was not preserved");
		}
		if (descriptor.depthState().compareFunction() != MetalRenderPipeline.CompareFunction.LESS_EQUAL
			|| descriptor.depthState().writeEnabled()
			|| descriptor.depthState().biasSlopeScale() != 2.0F
			|| descriptor.depthState().biasConstant() != 1.0F) {
			throw new AssertionError("Blaze3D depth state was not preserved");
		}
		if (descriptor.rasterState().cullMode() != MetalRenderPipeline.CullMode.BACK
			|| descriptor.rasterState().fillMode() != MetalRenderPipeline.FillMode.LINES) {
			throw new AssertionError("Blaze3D raster state was not preserved");
		}
	}

	private static void assertFormatCoverage() {
		MetalTexture.Descriptor usageDescriptor = Blaze3DMetalMappings.textureDescriptor(
			GpuFormat.RGBA8_UNORM,
			GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
			16,
			16,
			1
		);
		if (usageDescriptor.usage() != (MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_RENDER_TARGET)) {
			throw new AssertionError("Blaze3D texture usage was not preserved");
		}
		EnumSet<GpuFormat> unsupportedTextures = EnumSet.of(
			GpuFormat.RGB8_UNORM, GpuFormat.RGB8_SNORM, GpuFormat.RGB8_UINT, GpuFormat.RGB8_SINT,
			GpuFormat.RGB16_UNORM, GpuFormat.RGB16_SNORM, GpuFormat.RGB16_UINT, GpuFormat.RGB16_SINT, GpuFormat.RGB16_FLOAT,
			GpuFormat.RGB32_UINT, GpuFormat.RGB32_SINT, GpuFormat.RGB32_FLOAT
		);
		EnumSet<GpuFormat> unsupportedVertices = EnumSet.of(
			GpuFormat.RGB10A2_UINT,
			GpuFormat.D32_FLOAT, GpuFormat.D32_FLOAT_S8_UINT, GpuFormat.D24_UNORM_S8_UINT, GpuFormat.D16_UNORM, GpuFormat.S8_UINT
		);
		for (GpuFormat format : GpuFormat.values()) {
			assertMappingClassification(format, unsupportedTextures.contains(format), true);
			assertMappingClassification(format, unsupportedVertices.contains(format), false);
		}
	}

	private static void assertMappingClassification(final GpuFormat format, final boolean unsupported, final boolean texture) {
		try {
			if (texture) {
				Blaze3DMetalMappings.textureFormat(format);
			} else {
				Blaze3DMetalMappings.vertexFormat(format);
			}
			if (unsupported) {
				throw new AssertionError(format + " unexpectedly has a Metal " + (texture ? "texture" : "vertex") + " mapping");
			}
		} catch (UnsupportedOperationException expected) {
			if (!unsupported || !expected.getMessage().contains(format.name())) {
				throw new AssertionError("Incorrect unsupported-format diagnostic for " + format, expected);
			}
		}
	}

	private static void assertSpirv(final MetalShaderTranslator.Translation translation) {
		byte[] spirv = translation.spirv();
		if (spirv.length < Integer.BYTES || ByteBuffer.wrap(spirv).order(ByteOrder.LITTLE_ENDIAN).getInt() != SPIRV_MAGIC) {
			throw new AssertionError(translation.sourceName() + " did not produce valid SPIR-V");
		}
		if (!translation.metalSource().contains("#include <metal_stdlib>")) {
			throw new AssertionError(translation.sourceName() + " did not produce Metal source");
		}
	}
}
