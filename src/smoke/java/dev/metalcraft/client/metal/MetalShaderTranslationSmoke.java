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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
	private static final String MRT_VERTEX_GLSL = """
		#version 450

		void main() {
		    vec2 positions[3] = vec2[](
		        vec2(-1.0, -1.0),
		        vec2( 3.0, -1.0),
		        vec2(-1.0,  3.0)
		    );
		    gl_Position = vec4(positions[gl_VertexID % 3], 0.0, 1.0);
		}
		""";

	private static final String MRT_FRAGMENT_GLSL = """
		#version 450
		layout(location = 0) out vec4 target0;
		layout(location = 1) out vec4 target1;
		layout(location = 2) out vec4 target2;
		layout(location = 3) out vec4 target3;

		void main() {
		    target0 = vec4(1.0, 0.0, 0.0, 1.0);
		    target1 = vec4(0.0, 1.0, 0.0, 1.0);
		    target2 = vec4(0.0, 0.0, 1.0, 1.0);
		    target3 = vec4(1.0, 1.0, 0.0, 1.0);
		}
		""";
	/**
	 * Hand-written MSL, because framebuffer fetch has no GLSL spelling this translator emits and
	 * because a shader pack authored in MSL reaches the pipeline through this same path.
	 */
	private static final String TILE_MSL = """
		#include <metal_stdlib>
		using namespace metal;

		struct Varyings {
		    float4 position [[position]];
		};

		vertex Varyings tile_vertex(uint vertexId [[vertex_id]]) {
		    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    Varyings out;
		    out.position = float4(corners[vertexId % 3], 0.0, 1.0);
		    return out;
		}

		struct TileTargets {
		    float4 albedo [[color(0)]];
		    float4 normal [[color(1)]];
		    float4 scene [[color(2)]];
		};

		fragment TileTargets tile_fill() {
		    TileTargets out;
		    out.albedo = float4(0.5, 0.25, 0.0, 1.0);
		    out.normal = float4(0.0, 0.5, 0.25, 1.0);
		    out.scene = float4(0.0, 0.0, 0.0, 1.0);
		    return out;
		}

		// The G-buffer arrives as a fragment input rather than a sampled texture: these are the
		// values the previous draw left in tile memory at this pixel.
		fragment TileTargets tile_resolve(TileTargets fetched) {
		    TileTargets out;
		    out.albedo = fetched.albedo;
		    out.normal = fetched.normal;
		    out.scene = float4(fetched.albedo.rgb + fetched.normal.rgb, 1.0);
		    return out;
		}
		""";
	/** One instance per layer, each routed to its own slice by the vertex stage. */
	private static final String LAYERED_MSL = """
		#include <metal_stdlib>
		using namespace metal;

		struct LayeredVaryings {
		    float4 position [[position]];
		    uint layer [[render_target_array_index]];
		    float4 color;
		};

		vertex LayeredVaryings layered_vertex(uint vertexId [[vertex_id]], uint instanceId [[instance_id]]) {
		    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    const float4 colors[4] = {
		        float4(1.0, 0.0, 0.0, 1.0),
		        float4(0.0, 1.0, 0.0, 1.0),
		        float4(0.0, 0.0, 1.0, 1.0),
		        float4(1.0, 1.0, 0.0, 1.0)
		    };
		    LayeredVaryings out;
		    out.position = float4(corners[vertexId % 3], 0.0, 1.0);
		    out.layer = instanceId;
		    out.color = colors[instanceId % 4];
		    return out;
		}

		fragment float4 layered_fragment(LayeredVaryings in [[stage_in]]) {
		    return in.color;
		}
		""";
	private static final String COMPUTE_MSL = """
		#include <metal_stdlib>
		using namespace metal;

		kernel void fill_tint(
		    texture2d<float, access::write> output [[texture(0)]],
		    constant float4 &tint [[buffer(0)]],
		    uint2 pixel [[thread_position_in_grid]]
		) {
		    // The dispatch covers whole threadgroups, so the grid overhangs a target this small.
		    if (pixel.x >= output.get_width() || pixel.y >= output.get_height()) {
		        return;
		    }
		    output.write(tint, pixel);
		}

		struct ReadbackVaryings {
		    float4 position [[position]];
		};

		vertex ReadbackVaryings readback_vertex(uint vertexId [[vertex_id]]) {
		    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    ReadbackVaryings out;
		    out.position = float4(corners[vertexId % 3], 0.0, 1.0);
		    return out;
		}

		fragment float4 readback_fragment(texture2d<float, access::read> source [[texture(0)]]) {
		    return source.read(uint2(0, 0));
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
	private static final String BATCH_VERTEX_GLSL = """
		#version 450
		void main() {
		    vec2 positions[3] = vec2[](
		        vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0)
		    );
		    gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
		}
		""";
	/**
	 * Multiplies a bound uniform by a bound texture, so a slot that the batch failed to bind reads
	 * as black rather than as the expected product.
	 */
	private static final String BATCH_FRAGMENT_GLSL = """
		#version 450
		layout(binding = 0, std140) uniform BatchTint { vec4 Tint; };
		layout(binding = 1) uniform sampler2D BatchTexture;
		layout(location = 0) out vec4 color;
		void main() { color = Tint * texture(BatchTexture, vec2(0.5)); }
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
		assertSlotMasks(bound, 1 << 3, 1 << 5);

		// A texel buffer sampled only by the vertex stage. The render path binds resources to the
		// stages that declare them, so this pair is what proves the two stages report separately
		// rather than both reporting everything the pipeline has.
		MetalShaderTranslator.PipelineTranslation texel = MetalShaderTranslator.translatePipeline(
			TEXEL_VERTEX_GLSL, "smoke/texel.vert", TEXEL_FRAGMENT_GLSL, "smoke/texel.frag"
		);
		assertSlotMasks(texel.vertex(), 0, 1);
		assertSlotMasks(texel.fragment(), 0, 0);

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
			assertMultipleRenderTargets(device);
			assertMemorylessTileResolve(device);
			assertLayeredArrayRendering(device);
			assertComputeDispatch(device);
			assertTexelBufferSampling(device);
			assertMipLevelSampling(device);
			assertBatchedResourceBindings(device);
			assertQueriesAndLifetime(device, pipeline);
			assertPassGpuTiming(device, pipeline);
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

	/**
	 * Four attachments written from one pass, each with its own color.
	 *
	 * <p>A single-attachment pass cannot tell a correct MRT layout from one that routes every
	 * target through slot zero, so each target here is given a distinct color and all four are read
	 * back. The pass also declares a fifth index and leaves it empty, which is the shape Blaze3D
	 * produces when a layout reserves a target the pipeline does not write: Metal rejects a pipeline
	 * whose color format disagrees with its attachment, so an empty index must stay empty on both
	 * sides.
	 */
	private static void assertMultipleRenderTargets(final MetalDevice device) {
		MetalShaderTranslator.PipelineTranslation translated = MetalShaderTranslator.translatePipeline(
			MRT_VERTEX_GLSL, "smoke/mrt.vert", MRT_FRAGMENT_GLSL, "smoke/mrt.frag"
		);
		List<MetalRenderPipeline.ColorTarget> targets = List.of(
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.unused()
		);
		int[] expected = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00};
		List<MetalTexture> written = new ArrayList<>();
		try (MetalRenderPipeline pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 translated.vertex().metalSource(), translated.vertex().entryPoint(),
				 translated.fragment().metalSource(), translated.fragment().entryPoint(),
				 targets, null, MetalRenderPipeline.VertexDescriptor.EMPTY,
				 MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT
			 ));
			 MetalCommandQueue queue = device.createCommandQueue()) {
			List<MetalRenderPass.ColorAttachment> attachments = new ArrayList<>();
			for (int index = 0; index < expected.length; index++) {
				MetalTexture texture = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 4, 4, 1));
				written.add(texture);
				attachments.add(new MetalRenderPass.ColorAttachment(
					texture, 0, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0.0, 0.0, 0.0, 1.0
				));
			}
			attachments.add(null);

			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(attachments, null))) {
				pass.setPipeline(pipeline);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				pass.close();
				commands.commitAndWait();
			}

			for (int index = 0; index < expected.length; index++) {
				ByteBuffer pixels = written.get(index).readback(queue, 0);
				int actual = Byte.toUnsignedInt(pixels.get(0)) << 16
					| Byte.toUnsignedInt(pixels.get(1)) << 8
					| Byte.toUnsignedInt(pixels.get(2));
				if (!isNearColor(actual, expected[index])) {
					throw new AssertionError(String.format(
						"Metal color target %d holds %06X but its shader wrote %06X", index, actual, expected[index]
					));
				}
			}

			// A pipeline bound to a pass with a different layout would be a Metal validation failure
			// at draw time, which is late and reported against the encoder rather than the caller.
			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalRenderPass narrow = commands.beginRenderPass(
					 new MetalRenderPass.Descriptor(attachments.subList(0, 2), null))) {
				narrow.setPipeline(pipeline);
				throw new AssertionError("A five-target Metal pipeline bound to a two-attachment pass");
			} catch (IllegalArgumentException expectedFailure) {
				if (!expectedFailure.getMessage().contains("2")) {
					throw new AssertionError("MRT layout mismatch did not name the offending index", expectedFailure);
				}
			}
		} finally {
			written.forEach(MetalTexture::close);
		}
	}

	/**
	 * A deferred resolve that never touches device memory.
	 *
	 * <p>Two G-buffer attachments are allocated memoryless, filled by one draw, and consumed by a
	 * second draw in the same pass through Metal's framebuffer fetch, which reads the values still
	 * sitting in tile memory. Both are discarded at the end of the pass and only the resolved scene
	 * is stored. This is the mechanism the built-in shader pack is built on, so it is worth pinning
	 * before anything depends on it: if framebuffer fetch silently read cleared values instead, the
	 * scene would come back black rather than fail somewhere nearer the cause.
	 */
	private static void assertMemorylessTileResolve(final MetalDevice device) {
		List<MetalRenderPipeline.ColorTarget> fillTargets = List.of(
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA8_UNORM, 0, null)
		);
		List<MetalRenderPipeline.ColorTarget> resolveTargets = List.of(
			new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA8_UNORM, 0, null),
			new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA8_UNORM, 0, null),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)
		);
		MetalTexture.Descriptor gBufferDescriptor = MetalTexture.Descriptor.memoryless(MetalTexture.Format.RGBA8_UNORM, 4, 4);
		if (gBufferDescriptor.byteSize() != 0L) {
			throw new AssertionError("A memoryless Metal texture reported a device footprint of " + gBufferDescriptor.byteSize());
		}
		try (MetalRenderPipeline fill = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 TILE_MSL, "tile_vertex", TILE_MSL, "tile_fill", fillTargets, null,
				 MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
				 MetalRenderPipeline.RasterState.DEFAULT
			 ));
			 MetalRenderPipeline resolve = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 TILE_MSL, "tile_vertex", TILE_MSL, "tile_resolve", resolveTargets, null,
				 MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
				 MetalRenderPipeline.RasterState.DEFAULT
			 ));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture albedo = device.createTexture(gBufferDescriptor);
			 MetalTexture normal = device.createTexture(gBufferDescriptor);
			 MetalTexture scene = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 4, 4, 1))) {
			if (!albedo.isMemoryless() || scene.isMemoryless()) {
				throw new AssertionError("Metal texture storage mode did not survive creation");
			}
			try {
				albedo.readback(queue, 0);
				throw new AssertionError("A memoryless Metal texture was read back");
			} catch (IllegalStateException expected) {
				// Nothing to read: the texture has no device allocation to read from.
			}

			List<MetalRenderPass.ColorAttachment> attachments = List.of(
				new MetalRenderPass.ColorAttachment(
					albedo, 0, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.DONT_CARE, 0.0, 0.0, 0.0, 1.0
				),
				new MetalRenderPass.ColorAttachment(
					normal, 0, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.DONT_CARE, 0.0, 0.0, 0.0, 1.0
				),
				new MetalRenderPass.ColorAttachment(
					scene, 0, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0.0, 0.0, 0.0, 1.0
				)
			);
			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(attachments, null))) {
				pass.setPipeline(fill);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				pass.setPipeline(resolve);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				pass.close();
				commands.commitAndWait();
			}

			ByteBuffer pixels = scene.readback(queue, 0);
			int actual = Byte.toUnsignedInt(pixels.get(0)) << 16
				| Byte.toUnsignedInt(pixels.get(1)) << 8
				| Byte.toUnsignedInt(pixels.get(2));
			// (0.5, 0.25, 0.0) + (0.0, 0.5, 0.25), the two G-buffer values summed in tile memory.
			int expected = 0x80C040;
			if (!isNearColor(actual, expected)) {
				throw new AssertionError(String.format(
					"Deferred tile resolve produced %06X, expected %06X", actual, expected
				));
			}

			try {
				new MetalRenderPass.ColorAttachment(
					albedo, 0, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0.0, 0.0, 0.0, 1.0
				);
				throw new AssertionError("A memoryless Metal attachment accepted a store action");
			} catch (IllegalArgumentException expectedFailure) {
				// Storing tile memory would need somewhere to store it to.
			}
		}
	}

	/**
	 * Four array layers filled by one encoder.
	 *
	 * <p>A shadow cascade is four renders of the same world from four frusta, and encoding it as
	 * four passes costs four encoder boundaries on a GPU where a boundary is a tile flush. Layered
	 * rendering covers every layer in one pass and lets the vertex stage choose which layer each
	 * primitive lands on, so one instanced draw fills the whole cascade.
	 *
	 * <p>Each layer is given its own color and read back separately, because a pass that ignored
	 * the layer index would write every instance to layer zero and still look like it worked from
	 * layer zero alone.
	 */
	private static void assertLayeredArrayRendering(final MetalDevice device) {
		int layers = 4;
		int[] expected = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00};
		try (MetalRenderPipeline pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 LAYERED_MSL, "layered_vertex", LAYERED_MSL, "layered_fragment",
				 List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)), null,
				 MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
				 new MetalRenderPipeline.RasterState(
					 MetalRenderPipeline.CullMode.NONE,
					 MetalRenderPipeline.FillMode.FILL,
					 MetalRenderPipeline.TopologyClass.TRIANGLE
				 )
			 ));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture cascade = device.createTexture(MetalTexture.Descriptor.array(
				 MetalTexture.Format.RGBA8_UNORM, 4, 4, layers, MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_RENDER_TARGET
			 ))) {
			if (!cascade.descriptor().isArray() || cascade.descriptor().sliceCount() != layers) {
				throw new AssertionError("Metal array texture did not keep its layer count");
			}
			MetalRenderPass.Descriptor descriptor = new MetalRenderPass.Descriptor(
				List.of(new MetalRenderPass.ColorAttachment(
					cascade, 0, 0, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0.0, 0.0, 0.0, 1.0
				)),
				null,
				layers
			);
			if (!descriptor.isLayered()) {
				throw new AssertionError("A layered Metal render pass did not report itself as layered");
			}
			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalRenderPass pass = commands.beginRenderPass(descriptor)) {
				pass.setPipeline(pipeline);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, layers, 0);
				pass.close();
				commands.commitAndWait();
			}

			for (int layer = 0; layer < layers; layer++) {
				ByteBuffer pixels = cascade.readback(queue, 0, layer);
				int actual = Byte.toUnsignedInt(pixels.get(0)) << 16
					| Byte.toUnsignedInt(pixels.get(1)) << 8
					| Byte.toUnsignedInt(pixels.get(2));
				if (!isNearColor(actual, expected[layer])) {
					throw new AssertionError(String.format(
						"Metal array layer %d holds %06X, expected %06X: the layer index did not reach the attachment",
						layer, actual, expected[layer]
					));
				}
			}

			try {
				new MetalRenderPass.Descriptor(
					List.of(new MetalRenderPass.ColorAttachment(
						cascade, 0, 2, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0.0, 0.0, 0.0, 1.0
					)),
					null,
					layers
				);
				throw new AssertionError("A layered Metal pass also selected a single slice");
			} catch (IllegalArgumentException expectedFailure) {
				// A layered pass covers every layer, so picking one for the attachment is a
				// contradiction rather than a narrowing.
			}
		}
	}

	/**
	 * A compute dispatch whose output is read by a render pass in the same command buffer.
	 *
	 * <p>The bloom chain and the ambient-occlusion pass in the built-in pack are compute, and both
	 * of them end by handing a texture to a later render pass. What has to hold for that to work is
	 * not just that the dispatch runs, but that its writes are visible to the pass after it: Metal
	 * tracks the hazard across encoders, and this is the assertion that says so rather than assuming
	 * it. Reading the color back through a second encoder is the point; reading the compute output
	 * directly would pass even if the ordering were wrong.
	 */
	private static void assertComputeDispatch(final MetalDevice device) {
		int size = 4;
		try (MetalComputePipeline kernel = device.createComputePipeline(
				 new MetalComputePipeline.Descriptor(COMPUTE_MSL, "fill_tint"));
			 MetalRenderPipeline readback = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 COMPUTE_MSL, "readback_vertex", COMPUTE_MSL, "readback_fragment",
				 List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)), null,
				 MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
				 MetalRenderPipeline.RasterState.DEFAULT
			 ));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture computed = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.RGBA8_UNORM, size, size, 1,
				 MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_SHADER_WRITE
			 ));
			 MetalTextureView computedView = computed.createView();
			 MetalTexture target = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, size, size, 1));
			 MetalBuffer tint = device.createBuffer(4L * Float.BYTES, MetalBuffer.StorageMode.SHARED)) {
			if (kernel.maxThreadsPerThreadgroup() < 64 || kernel.threadExecutionWidth() <= 0) {
				throw new AssertionError(
					"Metal reported an unusable threadgroup size for a compiled kernel: "
						+ kernel.maxThreadsPerThreadgroup() + " threads, width " + kernel.threadExecutionWidth()
				);
			}
			try (MetalBuffer.Mapping mapping = tint.map()) {
				mapping.bytes().order(ByteOrder.nativeOrder()).asFloatBuffer()
					.put(0.25F).put(0.5F).put(0.75F).put(1.0F);
			}

			try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
				try (MetalComputePass compute = commands.beginComputePass()) {
					compute.setPipeline(kernel);
					compute.setTexture(0, computedView);
					compute.setBuffer(0, tint, 0L);
					compute.dispatchCovering(size, size, 8, 8);
				}
				try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					MetalRenderPass.ColorAttachment.clear(target, 0.0, 0.0, 0.0, 1.0)))) {
					pass.setPipeline(readback);
					pass.setTexture(0, computedView, MetalRenderPass.STAGE_FRAGMENT);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				}
				commands.commitAndWait();
			}

			ByteBuffer pixels = target.readback(queue, 0);
			int actual = Byte.toUnsignedInt(pixels.get(0)) << 16
				| Byte.toUnsignedInt(pixels.get(1)) << 8
				| Byte.toUnsignedInt(pixels.get(2));
			int expected = 0x4080BF;
			if (!isNearColor(actual, expected)) {
				throw new AssertionError(String.format(
					"A render pass read %06X from a compute dispatch that wrote %06X", actual, expected
				));
			}

			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalComputePass compute = commands.beginComputePass()) {
				compute.setPipeline(kernel);
				compute.dispatch(1, 1, 1, 64, 64, 1);
				throw new AssertionError("A threadgroup larger than the kernel supports was dispatched");
			} catch (IllegalArgumentException expectedFailure) {
				// The limit belongs to the compiled kernel rather than the device, so it is worth
				// reporting against the kernel rather than as an encoder failure.
			}
		}
	}

	private static boolean isNearColor(final int actual, final int expected) {
		for (int shift = 0; shift <= 16; shift += 8) {
			if (Math.abs((actual >> shift & 0xFF) - (expected >> shift & 0xFF)) > 2) {
				return false;
			}
		}
		return true;
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
				// Bound to the fragment stage alone, which is the only stage that samples it. Getting
				// the mip colour back is what proves stage-selective binding reaches the right table.
				pass.setTexture(0, sourceView, MetalRenderPass.STAGE_FRAGMENT);
				pass.setSampler(0, sampler, MetalRenderPass.STAGE_FRAGMENT);
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
				// The mirror of the mip case: only the vertex stage fetches this one.
				pass.setTexelBuffer(0, values, 0L, 1L, MetalTexture.Format.R8_SINT, MetalRenderPass.STAGE_VERTEX);
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
			 MetalTexture batchedColor = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 16, 16, 1));
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
			drawMappedTriangle(queue, pipeline, color, vertices, indices, null);
			ByteBuffer immediate = color.readback(queue, 0);
			assertRenderedPixelsVary(immediate);

			// A batch is only worth having if it encodes what the per-command setters encode, and
			// the pixels are the only place that shows. Both ABIs are compared against the same
			// immediate render, so a checked build and a shipping build are held to one answer.
			boolean checkedOriginally = MetalCommandStream.isChecked();
			try {
				for (boolean checked : new boolean[]{false, true}) {
					MetalCommandStream.setChecked(checked);
					drawMappedTriangle(queue, pipeline, batchedColor, vertices, indices, new MetalCommandStream());
					assertSamePixels(immediate, batchedColor.readback(queue, 0), checked);
				}
			} finally {
				MetalCommandStream.setChecked(checkedOriginally);
			}
		}
	}

	/** Draws the mapped triangle, through the batch ABI when {@code batch} is present. */
	private static void drawMappedTriangle(
		final MetalCommandQueue queue,
		final MetalRenderPipeline pipeline,
		final MetalTexture target,
		final MetalBuffer vertices,
		final MetalBuffer indices,
		final MetalCommandStream batch
	) {
		try (MetalCommandBuffer commands = queue.createCommandBuffer();
			 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				 MetalRenderPass.ColorAttachment.clear(target, 0.0, 0.0, 0.0, 1.0)))) {
			pass.setPipeline(pipeline);
			if (batch == null) {
				pass.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, vertices, 0L);
				pass.drawIndexed(MetalRenderPass.Primitive.TRIANGLE, indices, 0L, MetalRenderPass.IndexType.UINT16, 3, 1, 0, 0);
			} else {
				batch.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, vertices, 0L);
				batch.drawIndexed(MetalRenderPass.Primitive.TRIANGLE, indices, 0L, MetalRenderPass.IndexType.UINT16, 3, 1, 0, 0);
				pass.submit(batch);
			}
			pass.close();
			commands.commitAndWait();
		}
	}

	/**
	 * Binds a uniform, a texture, and a sampler through the batch ABI and draws indexed with them.
	 *
	 * <p>The shader multiplies the uniform by the sampled texel, so the expected red is only
	 * produced when every one of those three binds reached the fragment argument table: a missed
	 * uniform or an unbound texture reads as zero and the product is black.
	 */
	private static void assertBatchedResourceBindings(final MetalDevice device) {
		try (MetalRenderPipeline pipeline = device.createRenderPipeline(new MetalRenderPipeline.GlslDescriptor(
				 BATCH_VERTEX_GLSL, "smoke/batch.vert", BATCH_FRAGMENT_GLSL, "smoke/batch.frag",
				 MetalTexture.Format.RGBA8_UNORM, null));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 8, 8, 1));
			 MetalTexture source = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 2, 2, 1));
			 MetalTextureView sourceView = source.createView();
			 MetalSampler sampler = device.createSampler(new MetalSampler.Descriptor(
				 MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 MetalBuffer tint = device.createBuffer(16L, MetalBuffer.StorageMode.SHARED);
			 MetalBuffer indices = device.createBuffer(3L * Short.BYTES, MetalBuffer.StorageMode.SHARED)) {
			// Magenta texels against a yellow tint: only their product is red, so neither binding
			// alone can produce the colour this asserts on.
			ByteBuffer texels = ByteBuffer.allocateDirect(2 * 2 * Integer.BYTES).order(ByteOrder.nativeOrder());
			for (int texel = 0; texel < 4; texel++) texels.putInt(0xFFFF00FF);
			source.upload(queue, 0, texels.flip());
			try (MetalBuffer.Mapping mapping = tint.map()) {
				mapping.bytes().order(ByteOrder.nativeOrder()).asFloatBuffer().put(new float[]{1.0F, 1.0F, 0.0F, 1.0F});
			}
			try (MetalBuffer.Mapping mapping = indices.map()) {
				mapping.bytes().order(ByteOrder.nativeOrder()).asShortBuffer().put(new short[]{0, 1, 2});
			}

			boolean checkedOriginally = MetalCommandStream.isChecked();
			try {
				for (boolean checked : new boolean[]{false, true}) {
					MetalCommandStream.setChecked(checked);
					MetalCommandStream batch = new MetalCommandStream();
					batch.setUniformBuffer(0, tint, 0L, MetalRenderPass.STAGE_FRAGMENT);
					batch.setTexture(1, sourceView, MetalRenderPass.STAGE_FRAGMENT);
					batch.setSampler(1, sampler, MetalRenderPass.STAGE_FRAGMENT);
					batch.drawIndexed(MetalRenderPass.Primitive.TRIANGLE, indices, 0L, MetalRenderPass.IndexType.UINT16, 3, 1, 0, 0);
					if (batch.commandCount() != 4) {
						throw new AssertionError("Metal command batch recorded " + batch.commandCount() + " commands rather than four");
					}
					try (MetalCommandBuffer commands = queue.createCommandBuffer();
						 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
							 MetalRenderPass.ColorAttachment.clear(color, 0.0, 0.0, 1.0, 1.0)))) {
						pass.setPipeline(pipeline);
						pass.submit(batch);
						pass.close();
						commands.commitAndWait();
					}
					ByteBuffer pixels = color.readback(queue, 0);
					int red = Byte.toUnsignedInt(pixels.get((4 * 8 + 4) * 4));
					int green = Byte.toUnsignedInt(pixels.get((4 * 8 + 4) * 4 + 1));
					int blue = Byte.toUnsignedInt(pixels.get((4 * 8 + 4) * 4 + 2));
					if (red < 200 || green > 20 || blue > 20) {
						throw new AssertionError("Metal command batch (checked=" + checked + ") did not bind its uniform, texture, and sampler: rgb="
							+ red + "," + green + "," + blue);
					}
				}
			} finally {
				MetalCommandStream.setChecked(checkedOriginally);
			}
		}
	}

	private static void assertSamePixels(final ByteBuffer immediate, final ByteBuffer batched, final boolean checked) {
		if (immediate.remaining() != batched.remaining()) {
			throw new AssertionError("Metal command batch produced a readback of a different size");
		}
		for (int index = 0; index < immediate.remaining(); index++) {
			if (immediate.get(immediate.position() + index) != batched.get(batched.position() + index)) {
				throw new AssertionError("Metal command batch (checked=" + checked
					+ ") rendered differently from the per-command path, first at byte " + index);
			}
		}
	}

	private static void putVertex(final ByteBuffer bytes, final float x, final float y, final float z, final int color) {
		bytes.putFloat(x).putFloat(y).putFloat(z).putInt(color);
	}

	/**
	 * Pins the per-pass GPU timing against the command-buffer total it has to fit inside.
	 *
	 * <p>The pass is measured by counter samples the GPU writes at the encoder's stage boundaries,
	 * and the command buffer is measured by Metal's own {@code GPUStartTime}/{@code GPUEndTime}.
	 * They are separate mechanisms, so a pass reported as longer than the buffer that contains it
	 * means the two have stopped agreeing about what they are timing - which is the failure mode
	 * that would otherwise be discovered only as an implausible benchmark line.
	 *
	 * <p><b>Exactly one pass is timed here, and the containment check depends on that.</b> Pass
	 * spans overlap each other on this hardware, so several timed passes in one command buffer can
	 * and do sum past its GPU time; that is a property of the GPU rather than a fault, and asserting
	 * against it would be asserting something false. See {@link MetalPassCensus}.
	 */
	private static void assertPassGpuTiming(final MetalDevice device, final MetalRenderPipeline pipeline) {
		if (!device.supportsPassGpuTiming()) {
			throw new AssertionError("The active Apple GPU did not expose stage-boundary counter sampling");
		}
		long[] frame = new long[MetalStallProbe.slots()];
		List<MetalPassCensus.PassKind> passes;
		MetalStallProbe.setEnabled(true);
		try {
			MetalPassCensus.reset();
			int kind = MetalPassCensus.kindFor("smoke pass");
			if (kind == MetalPassCensus.UNTIMED_KIND) {
				throw new AssertionError("Metal pass census refused to intern a pass label while capturing");
			}
			try (MetalCommandQueue queue = device.createCommandQueue();
				 MetalTexture color = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 64, 64, 1));
				 MetalBuffer indexUpload = device.createBuffer(3L * Short.BYTES, MetalBuffer.StorageMode.SHARED);
				 MetalBuffer indices = device.createBuffer(3L * Short.BYTES, MetalBuffer.StorageMode.PRIVATE)) {
				try (MetalBuffer.Mapping mapping = indexUpload.map()) {
					mapping.bytes().order(ByteOrder.nativeOrder()).asShortBuffer().put(new short[]{0, 1, 2});
				}
				try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
					commands.copyBuffer(indexUpload, 0L, indices, 0L, 3L * Short.BYTES);
					// Untimed, so the timed pass below has to come in under the buffer's own total
					// rather than accounting for all of it.
					try (MetalRenderPass clearPass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
						MetalRenderPass.ColorAttachment.clear(color, 0.0, 0.0, 0.0, 1.0)
					))) {
						// Beginning and ending the pass performs the clear.
					}
					try (MetalRenderPass renderPass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
						new MetalRenderPass.ColorAttachment(
							color, MetalRenderPass.LoadAction.LOAD, MetalRenderPass.StoreAction.STORE, 0.0, 0.0, 0.0, 0.0
						)
					), kind)) {
						renderPass.setScissor(0, 0, 64, 64);
						renderPass.setPipeline(pipeline);
						for (int repeat = 0; repeat < 64; repeat++) {
							renderPass.drawIndexed(
								MetalRenderPass.Primitive.TRIANGLE, indices, 0L, MetalRenderPass.IndexType.UINT16, 3, 1, 3, 0
							);
						}
					}
					commands.commitAndWait();
				}
				assertRenderedPixelsVary(color.readback(queue, 0));
			}
			passes = MetalPassCensus.take();
			MetalStallProbe.recordCompletedGpuWork();
			MetalStallProbe.takeFrame(frame, 0);
		} finally {
			MetalStallProbe.setEnabled(false);
		}

		MetalPassCensus.PassKind timed = passes.stream()
			.filter(pass -> pass.name().equals("smoke pass"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Metal pass census did not report the timed render pass"));
		if (timed.count() != 1L) {
			throw new AssertionError("Metal pass census reported " + timed.count() + " samples for one render pass");
		}
		if (!(timed.totalMs() > 0.0)) {
			throw new AssertionError("Metal pass census reported no GPU time for a pass that drew 64 triangles");
		}
		int base = MetalStallProbe.Source.GPU_FRAME.ordinal() * MetalStallProbe.FIELDS;
		double commandBufferMs = frame[base + MetalStallProbe.FIELD_NANOS] / 1_000_000.0;
		if (!(commandBufferMs > 0.0)) {
			throw new AssertionError("Metal reported no GPU time for a completed command buffer");
		}
		if (timed.totalMs() > commandBufferMs) {
			throw new AssertionError("Metal pass GPU time of " + timed.totalMs()
				+ " ms exceeded its command buffer's " + commandBufferMs + " ms");
		}
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

	/**
	 * Checks the reflected slot masks against the bindings the stage was written with.
	 *
	 * <p>The masks decide which stages a resource is bound to, so a wrong bit renders the wrong
	 * thing rather than merely running slowly, and the mapping they rest on - that a GLSL
	 * {@code binding} survives into the Metal slot of the same number - is a SPIRV-Cross option that
	 * a version bump could change underneath us.
	 */
	private static void assertSlotMasks(
		final MetalShaderTranslator.Translation translation,
		final int expectedBuffers,
		final int expectedTextures
	) {
		if (translation.bufferSlots() != expectedBuffers || translation.textureSlots() != expectedTextures) {
			throw new AssertionError(String.format(
				"%s reflected slots buffers=0x%X textures=0x%X, expected 0x%X/0x%X",
				translation.sourceName(), translation.bufferSlots(), translation.textureSlots(),
				expectedBuffers, expectedTextures));
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
