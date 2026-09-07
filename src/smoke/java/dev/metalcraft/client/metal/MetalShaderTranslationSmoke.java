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
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.OptionalDouble;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import dev.metalcraft.client.shader.world.ShadowCascades;
import dev.metalcraft.client.shader.world.TerrainShadowRenderer;
import dev.metalcraft.client.shader.world.WorldLightingModule;
import dev.metalcraft.client.shader.world.WorldShadowModule;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraft.resources.Identifier;
import dev.metalcraft.api.MetalCraftShaderContext;
import dev.metalcraft.api.MetalCraftShaderExtension;
import dev.metalcraft.api.MetalCraftShaders;
import dev.metalcraft.client.MetalCraftClient;
import dev.metalcraft.client.shader.WorldGeometryAdapter;
import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.ShaderFrameExecutor;
import dev.metalcraft.client.shader.ShaderGraphCompiler;
import dev.metalcraft.client.shader.ShaderPack;
import dev.metalcraft.client.shader.ShaderPackLoader;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.WorldComposition;

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

		void main() {
		    target0 = vec4(1.0, 0.0, 0.0, 1.0);
		    target1 = vec4(0.0, 1.0, 0.0, 1.0);
		}
		""";
	private static final String COMPUTE_FILL_MSL = """
		#include <metal_stdlib>
		using namespace metal;

		kernel void fill_one(
		    texture2d<float, access::write> output [[texture(0)]],
		    uint2 pixel [[thread_position_in_grid]]
		) {
		    if (pixel.x >= output.get_width() || pixel.y >= output.get_height()) {
		        return;
		    }
		    output.write(float4(1.0), pixel);
		}
		""";

	private MetalShaderTranslationSmoke() {
	}

	public static void main(final String[] arguments) {
		assertExtensionIsolation();
		assertWorldGeometryRules();
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
			assertTexelBufferSampling(device);
			assertMipLevelSampling(device);
			assertBatchedResourceBindings(device);
			assertQueriesAndLifetime(device, pipeline);
			assertPassGpuTiming(device, pipeline);
			assertComputeFill(device);
			assertMultipleRenderTargets(device);
			assertMemorylessAbi(device);
			assertTextureArrayPass(device);
			dev.metalcraft.client.shader.world.ShadowCascadesSmoke.run();
			dev.metalcraft.client.shader.world.WorldShadowModuleSmoke.run(device);
			dev.metalcraft.client.shader.world.WorldLightingModuleSmoke.run(device);
			assertMemorylessPassMerge();
			WorldHdrTargetsSmoke.run();
			OpaqueSnapshotSmoke.run();
			WaterForwardPipelineSmoke.run();
			dev.metalcraft.client.shader.water.WaterVertexMetadataSmoke.run();
			dev.metalcraft.client.shader.water.WaterFrameInputsSmoke.run();
			LinearWorldSessionSmoke.run();
			LinearWorldActivationSmoke.run();
			LinearWorldReloadSourceSmoke.run();
			LinearWorldShadersSmoke.run();
			LinearWorldPostShadersSmoke.run();
			LinearWorldTransparencyConfigSmoke.run();
			LinearWorldFabulousPromotionSmoke.run();
			NativeColorContractSmoke.run();
			assertIdentityGradePack(device);
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

	private static void assertWorldGeometryRules() {
		RenderPipeline blended = mappedPipeline();
		if (!WorldGeometryAdapter.isBlended(blended)) {
			throw new AssertionError("A translucent color target must decline G-buffer substitution");
		}
		if (WorldGeometryAdapter.programFor(blended) != null) {
			throw new AssertionError("A non-world vertex shader must not select a G-buffer program");
		}
	}

	private static void assertExtensionIsolation() {
		java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
		MetalCraftShaderContext context = new MetalCraftShaderContext(null, MetalCraftShaders.registry());
		MetalCraftShaderExtension throwing = ignored -> {
			throw new IllegalStateException("intentional extension failure");
		};
		MetalCraftShaderExtension counting = ignored -> ran.incrementAndGet();
		MetalCraftClient.registerExtensions(context, java.util.List.of(throwing, counting));
		if (ran.get() != 1) {
			throw new AssertionError("A throwing metalcraft-shaders extension aborted later extensions");
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

	private static void assertComputeFill(final MetalDevice device) {
		int size = 8;
		try (MetalComputePipeline kernel = device.createComputePipeline(
				new MetalComputePipeline.Descriptor(COMPUTE_FILL_MSL, "fill_one"));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture output = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.R8_UNORM, size, size, 1,
				 MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_SHADER_WRITE
			 ));
			 MetalTextureView outputView = output.createView()) {
			if (kernel.maxThreadsPerThreadgroup() < 64 || kernel.threadExecutionWidth() <= 0) {
				throw new AssertionError(
					"Metal reported an unusable threadgroup size for a compiled kernel: "
						+ kernel.maxThreadsPerThreadgroup() + " threads, width " + kernel.threadExecutionWidth()
				);
			}
			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalComputePass compute = commands.beginComputePass()) {
				compute.setPipeline(kernel);
				compute.setTexture(0, outputView);
				compute.dispatchCovering(size, size, 8, 8);
				compute.close();
				commands.commitAndWait();
			}
			ByteBuffer pixels = output.readback(queue, 0);
			int value = Byte.toUnsignedInt(pixels.get(0));
			if (value != 255) {
				throw new AssertionError("Compute kernel wrote " + value + " to an R8 texture, expected 255");
			}
		}
	}

	private static void assertMultipleRenderTargets(final MetalDevice device) {
		MetalShaderTranslator.PipelineTranslation translated = MetalShaderTranslator.translatePipeline(
			MRT_VERTEX_GLSL, "smoke/mrt.vert", MRT_FRAGMENT_GLSL, "smoke/mrt.frag"
		);
		List<MetalRenderPipeline.ColorTarget> targets = List.of(
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)
		);
		List<MetalTexture> written = new ArrayList<>();
		try (MetalRenderPipeline pipeline = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 translated.vertex().metalSource(), translated.vertex().entryPoint(),
				 translated.fragment().metalSource(), translated.fragment().entryPoint(),
				 targets, null, MetalRenderPipeline.VertexDescriptor.EMPTY,
				 MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT
			 ));
			 MetalCommandQueue queue = device.createCommandQueue()) {
			for (int index = 0; index < 2; index++) {
				written.add(device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 4, 4, 1)));
			}
			List<MetalRenderPass.ColorAttachment> attachments = List.of(
				new MetalRenderPass.ColorAttachment(
					written.get(0), MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0.0, 0.0, 0.0, 1.0
				),
				new MetalRenderPass.ColorAttachment(
					written.get(1), MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0.0, 0.0, 0.0, 1.0
				)
			);
			try (MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(attachments, null))) {
				pass.setPipeline(pipeline);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				pass.close();
				commands.commitAndWait();
			}
			int[] expected = {0xFF0000, 0x00FF00};
			for (int index = 0; index < expected.length; index++) {
				ByteBuffer pixels = written.get(index).readback(queue, 0);
				int actual = Byte.toUnsignedInt(pixels.get(0)) << 16
					| Byte.toUnsignedInt(pixels.get(1)) << 8
					| Byte.toUnsignedInt(pixels.get(2));
				if (actual != expected[index]) {
					throw new AssertionError(String.format(
						"Metal color target %d holds %06X but its shader wrote %06X", index, actual, expected[index]
					));
				}
			}
		} finally {
			written.forEach(MetalTexture::close);
		}
	}

	private static void assertMemorylessAbi(final MetalDevice device) {
		try {
			MetalNative.nCreateTexture(
				device.requireOpenHandle(),
				MetalTexture.Format.RGBA8_UNORM.nativeCode(),
				8,
				8,
				1,
				1,
				MetalTexture.USAGE_SHADER_READ,
				false,
				true
			);
			throw new AssertionError("nCreateTexture accepted a memoryless texture whose usage is not render-target only");
		} catch (IllegalStateException expected) {
			if (expected.getMessage() == null || !expected.getMessage().contains("memoryless")) {
				throw new AssertionError("Memoryless usage rejection did not name memoryless storage", expected);
			}
		}

		try (MetalTexture memoryless = device.createTexture(
			MetalTexture.Descriptor.memoryless(MetalTexture.Format.RGBA8_UNORM, 8, 8)
		)) {
			if (!memoryless.isMemoryless() || memoryless.descriptor().byteSize() != 0L) {
				throw new AssertionError("A memoryless Metal texture did not report memoryless storage");
			}
			try {
				new MetalRenderPass.ColorAttachment(
					memoryless, MetalRenderPass.LoadAction.LOAD, MetalRenderPass.StoreAction.DONT_CARE, 0.0, 0.0, 0.0, 1.0
				);
				throw new AssertionError("A memoryless Metal color attachment accepted LOAD");
			} catch (IllegalArgumentException expected) {
				if (expected.getMessage() == null || !expected.getMessage().contains("memoryless")) {
					throw new AssertionError("Memoryless LOAD rejection did not name memoryless storage", expected);
				}
			}
			try (MetalCommandQueue queue = device.createCommandQueue();
				 MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					 new MetalRenderPass.ColorAttachment(
						 memoryless, MetalRenderPass.LoadAction.DONT_CARE, MetalRenderPass.StoreAction.DONT_CARE, 0.0, 0.0, 0.0, 1.0
					 )
				 ))) {
				pass.close();
				commands.commitAndWait();
			}
		}
	}

	private static void assertTextureArrayPass(final MetalDevice device) {
		try (MetalTexture depth = device.createTexture(MetalTexture.Descriptor.array(
				 MetalTexture.Format.DEPTH32_FLOAT, 64, 64, 4, MetalTexture.USAGE_RENDER_TARGET
			 ));
			 MetalTextureView view = depth.createView();
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalCommandBuffer commands = queue.createCommandBuffer();
			 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				 List.of(),
				 new MetalRenderPass.DepthAttachment(
					 depth, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 1.0
				 ),
				 4
			 ))) {
			if (depth.descriptor().sliceCount() != 4 || view.sliceCount() != 4) {
				throw new AssertionError(
					"A 4-layer Metal texture view covered " + view.sliceCount() + " slices"
				);
			}
			pass.close();
			commands.commitAndWait();
		}
	}

	private static void assertMemorylessPassMerge() {
		MetalDevice metal = MetalNative.openDefaultDevice().orElseThrow();
		MetalGpuDevice device = new MetalGpuDevice(metal, (identifier, type) -> null);
		try {
			MetalTexture color0 = metal.createTexture(MetalTexture.Descriptor.memoryless(MetalTexture.Format.RGBA8_UNORM, 8, 8));
			MetalTexture color1 = metal.createTexture(MetalTexture.Descriptor.memoryless(MetalTexture.Format.RGBA8_UNORM, 8, 8));
			MetalGpuTexture gpu0 = new MetalGpuTexture(
				GpuTexture.USAGE_RENDER_ATTACHMENT, "memoryless-0", GpuFormat.RGBA8_UNORM, 8, 8, 1, 1, color0
			);
			MetalGpuTexture gpu1 = new MetalGpuTexture(
				GpuTexture.USAGE_RENDER_ATTACHMENT, "memoryless-1", GpuFormat.RGBA8_UNORM, 8, 8, 1, 1, color1
			);
			MetalGpuTextureView view0 = (MetalGpuTextureView)device.createTextureView(gpu0);
			MetalGpuTextureView view1 = (MetalGpuTextureView)device.createTextureView(gpu1);
			MetalCommandEncoder encoder = (MetalCommandEncoder)device.createCommandEncoder();
			long[] probe = new long[MetalStallProbe.slots()];
			MetalStallProbe.takeFrame(probe, 0);
			RenderPass.RenderArea area = new RenderPass.RenderArea(0, 0, 8, 8);
			encoder.createRenderPass(
				RenderPassDescriptor.create(() -> "memoryless-mrt-a")
					.withColorAttachment(view0)
					.withColorAttachment(view1)
					.withRenderArea(area)
			);
			encoder.submitRenderPass();
			encoder.createRenderPass(
				RenderPassDescriptor.create(() -> "memoryless-mrt-b")
					.withColorAttachment(view0)
					.withColorAttachment(view1)
					.withRenderArea(area)
			);
			encoder.submitRenderPass();
			encoder.submit();
			MetalStallProbe.takeFrame(probe, 0);
			int mergeIndex = MetalStallProbe.Source.RENDER_PASS_MERGE.ordinal() * MetalStallProbe.FIELDS
				+ MetalStallProbe.FIELD_COUNT;
			boolean merging = Boolean.parseBoolean(System.getProperty("metalcraft.passMerging", "true"));
			if (merging ? probe[mergeIndex] < 1L : probe[mergeIndex] != 0L) {
				throw new AssertionError(
					"Unexpected memoryless merge count (enabled=" + merging + "): " + probe[mergeIndex]
				);
			}
		} finally {
			device.close();
		}
	}

	private static final String GRAPH_MSL = """
		#include <metal_stdlib>
		using namespace metal;
		""";

	private static void assertIdentityGradePack(final MetalDevice device) {
		assertDeclaredShaderGraph();
		assertExternalBindings();
		assertInvalidPackJson();
		assertIncludeCycle();
		assertBundledGradeGraph();
		assertDefaultSelectedNone(device);
		assertShadersDisableProperty(device);
		Path root;
		Path settings;
		try {
			root = Files.createTempDirectory("metalcraft-grade-smoke-");
			settings = root.resolve("metalcraft-shaders.json");
		} catch (IOException error) {
			throw new AssertionError("Could not create shader runtime smoke directories", error);
		}
		try (ShaderPackRuntime runtime = new ShaderPackRuntime(device, root.resolve("shaderpacks"), settings)) {
			if (!ShaderPackRuntime.NONE_ID.equals(runtime.selectedPackId()) || runtime.isActive()) {
				throw new AssertionError("A new shader runtime must start with selectedPack none and isActive false");
			}
			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
			if (!runtime.isActive() || runtime.executor().isEmpty()) {
				throw new AssertionError("Selecting metalcraft-standard did not activate the pack: " + runtime.lastError());
			}

			try (MetalCommandQueue queue = device.createCommandQueue();
				 MetalTexture scene = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.BGRA8_UNORM, 64, 64, 1
				 ));
				 MetalTexture output = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.BGRA8_UNORM, 64, 64, 1
				 ))) {
				ShaderFrameExecutor executor = runtime.executor().orElseThrow();
				try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
					if (executor.encodeForTesting(commands, scene, output)) {
						throw new AssertionError("encodeForTesting before resize returned true");
					}
				}

				runtime.resize(64, 64);
				MetalTexture post = runtime.target("post_color");
				if (post == null || post.descriptor().width() != 64 || post.descriptor().height() != 64) {
					throw new AssertionError("resize did not allocate post_color at 64x64");
				}
				if (post.isMemoryless()) {
					throw new AssertionError("post_color must not be memoryless");
				}

				fillSolidBgra(scene, queue, (byte)0x30, (byte)0x60, (byte)0x90, (byte)0xFF);
				MetalStallProbe.setEnabled(true);
				try {
					MetalPassCensus.reset();
					encodeGrade(queue, executor, scene, output);
					if (!MetalPassCensus.internedNames().contains("MetalCraft shader: grade")) {
						throw new AssertionError(
							"Interned census names omitted MetalCraft shader: grade: " + MetalPassCensus.internedNames()
						);
					}
				} finally {
					MetalStallProbe.setEnabled(false);
				}
				assertBgraDelta(output.readback(queue, 0), (byte)0x30, (byte)0x60, (byte)0x90, 2, "identity grade");

				runtime.setOption("invert", true);
				if (!runtime.isActive()) {
					throw new AssertionError("Toggling invert disabled the pack: " + runtime.lastError());
				}
				encodeGrade(queue, runtime.executor().orElseThrow(), scene, output);
				assertBgraDelta(
					output.readback(queue, 0),
					(byte)(0xFF - 0x30),
					(byte)(0xFF - 0x60),
					(byte)(0xFF - 0x90),
					2,
					"invert grade"
				);
			}

			assertFailedMsl(device, runtime, root.resolve("shaderpacks"));
		}
		assertMixedRenderComputeGraph(device);
		assertConsecutiveUniformUploads(device);
		assertFailedComputeKernel(device);
		assertUnsupportedExecutorBindings(device);
		assertWorldCompositionBoundary(device);
		assertGbufferAllocation();
	}

	private static void assertGbufferAllocation() {
		MetalDevice extra = MetalNative.openDefaultDevice().orElseThrow();
		MetalGpuDevice gpu = new MetalGpuDevice(extra, (identifier, type) -> null);
		Path root;
		try {
			root = Files.createTempDirectory("metalcraft-gbuffer-smoke-");
		} catch (IOException error) {
			gpu.close();
			throw new AssertionError("Could not create G-buffer smoke directory", error);
		}
		try (ShaderPackRuntime runtime = new ShaderPackRuntime(
			extra, gpu, root.resolve("shaderpacks"), root.resolve("metalcraft-shaders.json")
		)) {
			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
			runtime.resize(32, 32);
			MetalTexture albedo = runtime.target("gbuffer_albedo");
			MetalTexture post = runtime.target("post_color");
			if (albedo == null || !albedo.isMemoryless()) {
				throw new AssertionError("gbuffer_albedo must be allocated memoryless");
			}
			if (post == null || post.isMemoryless()) {
				throw new AssertionError("post_color must stay in device memory");
			}
			if (runtime.worldGeometry() == null) {
				throw new AssertionError("A geometry pack must construct WorldGeometryAdapter after resize");
			}
			assertWorldResolve(gpu, runtime);
		} finally {
			gpu.close();
		}
	}

	/** Exercises the production adapter and deferred hook, with deterministic MRT seed pixels. */
	private static void assertWorldResolve(final MetalGpuDevice gpu, final ShaderPackRuntime runtime) {
		// Real Projection uniforms use private storage and upload through CommandEncoder.
		// Capture must see CPU upload bytes before GPU submission, including slice offsets.
		try (MetalGpuBuffer uniform = gpu.createBuffer(() -> "capture-private", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 128)) {
			ByteBuffer upload = ByteBuffer.allocateDirect(64).order(java.nio.ByteOrder.nativeOrder());
			new Matrix4f().perspective(1.1F, 1.5F, 0.1F, 100).get(0, upload);
			((MetalCommandEncoder)gpu.createCommandEncoder()).writeToBuffer(uniform.slice(32, 64), upload);
			if (uniform.metal().storageMode() != MetalBuffer.StorageMode.PRIVATE) throw new AssertionError("Expected private uniform");
			boolean[] captured = {false};
			uniform.captureUniform("Projection", 32, 64, (name, bytes) -> {
				for (int i = 0; i < 64; i++) {
					if (bytes.get(i) != upload.get(i)) throw new AssertionError("Private uniform capture differs at " + i);
				}
				captured[0] = true;
			});
			if (!captured[0] || uniform.cpuBytes(0, 64) != null) throw new AssertionError("Private uniform validity tracking failed");
		}

		String seedSource = """
			#include <metal_stdlib>
			using namespace metal;
			vertex float4 seed_vertex(uint id [[vertex_id]]) {
			    const float2 p[3] = {float2(-1,-1), float2(3,-1), float2(-1,3)};
			    return float4(p[id], 0, 1);
			}
			struct Targets {
			    float4 scene [[color(0)]];
			    float4 albedo [[color(1)]];
			    float4 normal [[color(2)]];
			    float4 light [[color(3)]];
			};
			fragment Targets seed_fragment() {
			    return {float4(144.0/255,96.0/255,48.0/255,1), float4(0.2,0.4,0.6,1),
			            float4(0.5,0.5,0.8,1.0/255), float4(0,0,0,0)};
			}
			""";
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.parse("metalcraft:smoke/resolve_seed"))
			.withVertexShader(Identifier.parse("metalcraft:seed"))
			.withFragmentShader(Identifier.parse("metalcraft:seed"))
			.withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
		for (int index = 0; index < 4; index++) {
			builder.withColorTargetState(index, new ColorTargetState(Optional.empty(),
				GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
		}
		RenderPipeline seed = builder.build();
		gpu.registerNativePipeline(seed, new MetalGpuDevice.NativeProgram(seedSource, "seed_vertex", "seed_fragment"));
		try (
			MetalTexture scene = gpu.metal().createTexture(new MetalTexture.Descriptor(
				MetalTexture.Format.RGBA8_UNORM, 32, 32, 1, MetalTexture.USAGE_RENDER_TARGET | MetalTexture.USAGE_SHADER_READ));
			MetalTexture depth = gpu.metal().createTexture(new MetalTexture.Descriptor(
				MetalTexture.Format.DEPTH32_FLOAT, 32, 32, 1, MetalTexture.USAGE_RENDER_TARGET));
			MetalGpuTextureView sceneView = gpu.wrapAttachment(scene, "resolve-scene");
			MetalGpuTextureView depthView = gpu.wrapAttachment(depth, "resolve-depth");
			MetalCommandQueue queue = gpu.metal().createCommandQueue()
		) {
			// The standalone smoke has no global RenderSystem device. Use the public pass wrapper
			// over the real backend without CommandEncoder's global-device validation.
			MetalCommandEncoder backend = (MetalCommandEncoder)gpu.createCommandEncoder();
			CommandEncoder encoder = new CommandEncoder(null, gpu, backend) {
				@Override
				public RenderPass createRenderPass(final RenderPassDescriptor descriptor) {
					return new RenderPass(backend.createRenderPass(descriptor), gpu, descriptor.colorAttachments(),
						backend::submitRenderPass, descriptor.renderArea);
				}
			};
			for (String debug : List.of("off", "albedo", "mixed", "camera")) {
				runtime.setOption("debug_view", debug.equals("mixed") ? "off" : debug.equals("camera") ? "receiver" : debug);
				runtime.worldGeometry().beginFrame();
				for (int half = 0; half < 2; half++) {
					if (debug.equals("camera")) {
						// Changing the raster view flushes the first resolve before updating the
						// CPU matrices. Its camera upload must survive that change until submission.
						runtime.worldGeometry().setRasterTransforms(
							new Matrix4f().perspective((float)Math.PI / 2, 1, 0.1F, 1000),
							new Matrix4f().rotationY(half == 0 ? 0 : (float)Math.PI));
					}
					if (debug.equals("mixed") && half == 1) {
						// Both resolves are recorded before submission: the second upload must not
						// change the options read by the first draw when the GPU finally executes.
						WorldGeometryAdapter.resolveOpaque();
						runtime.setOption("debug_view", "albedo");
					}
					try (RenderPass pass = WorldGeometryAdapter.beginWorldPass(encoder, () -> "world-resolve-smoke",
						sceneView, half == 0 ? Optional.of(new Vector4f(0, 0, 0, 1)) : Optional.empty(),
						depthView, half == 0 ? OptionalDouble.of(1) : OptionalDouble.empty(),
						List.of(RenderPipelines.SOLID_TERRAIN))) {
						RenderPipeline terrain = WorldGeometryAdapter.substitute(pass, RenderPipelines.SOLID_TERRAIN);
						if (terrain == RenderPipelines.SOLID_TERRAIN || !gpu.precompilePipeline(terrain, null).isValid()) {
							throw new AssertionError("Terrain must compile and be substituted before entering the G-buffer");
						}
						if (WorldGeometryAdapter.isBlended(RenderPipelines.SOLID_TERRAIN)
							|| !WorldGeometryAdapter.isBlended(RenderPipelines.TRANSLUCENT_TERRAIN)) {
							throw new AssertionError("Translucent terrain must remain forward rendered");
						}
						pass.enableScissor(half * 16, 0, 16, 32);
						pass.setPipeline(seed);
						pass.draw(3, 1, 0, 0);
					}
				}
				WorldGeometryAdapter.resolveOpaque();
				try (var fence = encoder.createFence()) {
					encoder.submit();
					if (!fence.awaitCompletion(5_000_000_000L)) {
						throw new AssertionError("World resolve GPU submission timed out");
					}
				}
				if (debug.equals("camera")) {
					ByteBuffer pixels = scene.readback(queue, 0);
					float viewDepth = 65536.0F * (1024.0F / 16777215.0F);
					for (int y = 0; y < 32; y++) {
						for (int x = 0; x < 32; x++) {
							float direction = x < 16 ? 1 : -1;
							float receiverX = ((x + 0.5F) / 16 - 1) * viewDepth * direction;
							float receiverY = ((y + 0.5F) / 16 - 1) * viewDepth;
							float receiverZ = -viewDepth * direction;
							assertBgraDelta(pixels.slice((y * 32 + x) * 4, 4),
								(byte)Math.round((receiverX / 64 + 0.5F) * 255),
								(byte)Math.round((receiverY / 64 + 0.5F) * 255),
								(byte)Math.round((receiverZ / 64 + 0.5F) * 255), 2,
								"immutable resolve camera at " + x + "," + y);
						}
					}
				} else if (debug.equals("mixed")) {
					ByteBuffer pixels = scene.readback(queue, 0);
					for (int y = 0; y < 32; y++) {
						for (int x = 0; x < 32; x++) {
							assertBgraDelta(pixels.slice((y * 32 + x) * 4, 4),
								(byte)(x < 16 ? 144 : 51), (byte)(x < 16 ? 96 : 102),
								(byte)(x < 16 ? 48 : 153), 2, "immutable resolve options at " + x + "," + y);
						}
					}
				} else if (debug.equals("off")) {
					assertBgraDelta(scene.readback(queue, 0), (byte)144, (byte)96, (byte)48, 2, "world shaded seed");
				} else {
					assertBgraDelta(scene.readback(queue, 0), (byte)51, (byte)102, (byte)153, 2, "world tile resolve");
				}
			}
			assertWorldResolveColorEncoding(gpu, runtime, encoder, scene, sceneView, depthView, queue, seed);
			assertWorldShadowDebugViews(gpu, runtime, encoder, scene, sceneView, depthView, queue);
		} finally {
			gpu.forgetNativePipeline(seed);
		}
	}

	private static void assertWorldResolveColorEncoding(
		final MetalGpuDevice gpu,
		final ShaderPackRuntime runtime,
		final CommandEncoder encoder,
		final MetalTexture legacyScene,
		final MetalGpuTextureView legacySceneView,
		final MetalGpuTextureView depthView,
		final MetalCommandQueue queue,
		final RenderPipeline legacySeed
	) {
		String source = """
			#include <metal_stdlib>
			using namespace metal;
			vertex float4 seed_vertex(uint id [[vertex_id]]) {
			    const float2 p[3] = {float2(-1,-1), float2(3,-1), float2(-1,3)};
			    return float4(p[id], 0, 1);
			}
			struct Targets {
			    float4 scene [[color(0)]];
			    float4 albedo [[color(1)]];
			    float4 normal [[color(2)]];
			    float4 light [[color(3)]];
			};
			fragment Targets seed_fragment() {
			    return {float4(2.0,0.5,0.03125,1), float4(0.2,0.4,0.6,1),
			            float4(0.5,0.5,0.8,1.0/255), float4(0,0,0,0)};
			}
			""";
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.parse("metalcraft:smoke/resolve_hdr_seed"))
			.withVertexShader(Identifier.parse("metalcraft:seed"))
			.withFragmentShader(Identifier.parse("metalcraft:seed"))
			.withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withColorTargetState(0, new ColorTargetState(Optional.empty(), GpuFormat.RGBA16_FLOAT,
				ColorTargetState.WRITE_ALL));
		for (int index = 1; index < 4; index++) {
			builder.withColorTargetState(index, new ColorTargetState(Optional.empty(),
				GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
		}
		RenderPipeline seed = builder.build();
		gpu.registerNativePipeline(seed, new MetalGpuDevice.NativeProgram(source, "seed_vertex", "seed_fragment"));
		try (MetalTexture hdr = gpu.metal().createTexture(new MetalTexture.Descriptor(
			MetalTexture.Format.RGBA16_FLOAT, 32, 32, 1,
			MetalTexture.USAGE_RENDER_TARGET | MetalTexture.USAGE_SHADER_READ));
			 MetalGpuTextureView hdrView = gpu.wrapAttachment(hdr, "resolve-hdr-scene")) {
			runtime.setOption("debug_view", "albedo");
			runtime.worldGeometry().beginFrame();
			try (RenderPass pass = WorldGeometryAdapter.beginWorldPass(encoder, () -> "world-mode-flush-smoke",
				legacySceneView, Optional.of(new Vector4f(0, 0, 0, 1)), depthView, OptionalDouble.of(1),
				List.of(RenderPipelines.SOLID_TERRAIN))) {
				WorldGeometryAdapter.substitute(pass, RenderPipelines.SOLID_TERRAIN);
				pass.setPipeline(legacySeed);
				pass.draw(3, 1, 0, 0);
			}
			// Switching modes must encode the pending legacy resolve before retiring its pipeline.
			runtime.worldGeometry().beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);
			submitWorldFixture(encoder, "World mode-switch flush GPU submission timed out");
			assertBgraDelta(legacyScene.readback(queue, 0), (byte)51, (byte)102, (byte)153, 2,
				"pending legacy resolve before linear switch");
			runtime.setOption("debug_view", "vanilla");
			RenderPipeline linearStand;
			try (RenderPass pass = WorldGeometryAdapter.beginWorldPass(encoder, () -> "world-linear-resolve-smoke",
				hdrView, Optional.of(new Vector4f(0, 0, 0, 1)), depthView, OptionalDouble.of(1),
				List.of(RenderPipelines.SOLID_TERRAIN))) {
				linearStand = WorldGeometryAdapter.substitute(pass, RenderPipelines.SOLID_TERRAIN);
				if (linearStand == RenderPipelines.SOLID_TERRAIN
					|| !gpu.precompileLinearWorldPipeline(linearStand, null).isValid()) {
					throw new AssertionError("Linear Standard stand-in must carry a valid native linear contract");
				}
				pass.setPipeline(seed);
				pass.draw(3, 1, 0, 0);
			}
			WorldGeometryAdapter.resolveOpaque();
			submitWorldFixture(encoder, "Linear world resolve GPU submission timed out");
			assertHalfPixel(hdr.readback(queue, 0).order(ByteOrder.nativeOrder()),
				2.0F, 0.5F, 0.03125F, "linear world resolve preserves HDR seed");

			runtime.setOption("debug_view", "albedo");
			try (RenderPass pass = WorldGeometryAdapter.beginWorldPass(encoder, () -> "world-linear-debug-smoke",
				hdrView, Optional.of(new Vector4f(0, 0, 0, 1)), depthView, OptionalDouble.of(1),
				List.of(RenderPipelines.SOLID_TERRAIN))) {
				WorldGeometryAdapter.substitute(pass, RenderPipelines.SOLID_TERRAIN);
				pass.setPipeline(seed);
				pass.draw(3, 1, 0, 0);
			}
			WorldGeometryAdapter.resolveOpaque();
			submitWorldFixture(encoder, "Linear debug resolve timed out");
			assertHalfPixel(hdr.readback(queue, 0).order(ByteOrder.nativeOrder()),
				(float)Math.pow((0.2 + 0.055) / 1.055, 2.4),
				(float)Math.pow((0.4 + 0.055) / 1.055, 2.4),
				(float)Math.pow((0.6 + 0.055) / 1.055, 2.4), "linear debug output");
			runtime.setOption("debug_view", "vanilla");

			runtime.worldGeometry().beginFrame(FrameBindings.ColorEncoding.LINEAR_SRGB);
			try {
				try (RenderPass ignored = WorldGeometryAdapter.beginWorldPass(encoder, () -> "world-linear-format-mismatch",
					legacySceneView, Optional.of(new Vector4f()), depthView, OptionalDouble.of(1),
					List.of(RenderPipelines.SOLID_TERRAIN))) {
					throw new AssertionError("Linear world resolve accepted RGBA8 scene storage");
				}
			} catch (IllegalArgumentException expected) {
				if (!expected.getMessage().contains("RGBA16_FLOAT")) throw expected;
			}

			runtime.worldGeometry().beginFrame();
			try (RenderPass pass = WorldGeometryAdapter.beginWorldPass(encoder, () -> "world-legacy-float-resolve-smoke",
				hdrView, Optional.of(new Vector4f(0, 0, 0, 1)), depthView, OptionalDouble.of(1),
				List.of(RenderPipelines.SOLID_TERRAIN))) {
				RenderPipeline legacyStand = WorldGeometryAdapter.substitute(pass, RenderPipelines.SOLID_TERRAIN);
				if (legacyStand == linearStand || legacyStand == RenderPipelines.SOLID_TERRAIN
					|| !gpu.precompilePipeline(legacyStand, null).isValid()) {
					throw new AssertionError("Switching back must build a fresh valid legacy stand-in");
				}
				pass.setPipeline(seed);
				pass.draw(3, 1, 0, 0);
			}
			WorldGeometryAdapter.resolveOpaque();
			submitWorldFixture(encoder, "Legacy float world resolve GPU submission timed out");
			assertHalfPixel(hdr.readback(queue, 0).order(ByteOrder.nativeOrder()),
				2.0F, 0.5F, 0.03125F, "legacy world resolve on float storage");
			runtime.setOption("debug_view", "albedo");
			try (RenderPass pass = WorldGeometryAdapter.beginWorldPass(encoder, () -> "world-legacy-debug-restore",
				hdrView, Optional.of(new Vector4f()), depthView, OptionalDouble.of(1),
				List.of(RenderPipelines.SOLID_TERRAIN))) {
				WorldGeometryAdapter.substitute(pass, RenderPipelines.SOLID_TERRAIN);
				pass.setPipeline(seed);
				pass.draw(3, 1, 0, 0);
			}
			WorldGeometryAdapter.resolveOpaque();
			submitWorldFixture(encoder, "Legacy debug restoration timed out");
			assertHalfPixel(hdr.readback(queue, 0).order(ByteOrder.nativeOrder()),
				0.2F, 0.4F, 0.6F, "legacy debug restoration");
			System.out.println("World adapter encoding: pending resolve flush, explicit native contract, HDR seed, linear debug, format rejection and legacy restoration passed");
		} finally {
			gpu.forgetNativePipeline(seed);
			runtime.worldGeometry().beginFrame();
			runtime.setOption("debug_view", "off");
		}
	}

	private static void submitWorldFixture(final CommandEncoder encoder, final String timeout) {
		try (var fence = encoder.createFence()) {
			encoder.submit();
			if (!fence.awaitCompletion(5_000_000_000L)) throw new AssertionError(timeout);
		}
	}

	private static void assertHalfPixel(final ByteBuffer pixels, final float red, final float green,
		final float blue, final String label) {
		float[] expected = {red, green, blue};
		for (int channel = 0; channel < expected.length; channel++) {
			float actual = Float.float16ToFloat(pixels.getShort(channel * Short.BYTES));
			if (!Float.isFinite(actual) || Math.abs(actual - expected[channel]) > 0.008F) {
				throw new AssertionError(label + " channel " + channel + " expected=" + expected[channel]
					+ " actual=" + actual);
			}
		}
	}

	private static void assertExternalBindings() {
		ShaderPack.Target colour = new ShaderPack.Target(
			ShaderPack.PixelFormat.RGBA8_UNORM, new ShaderPack.Scale(1.0), ShaderPack.Lifetime.TRANSIENT, 1
		);
		ShaderPack.Pass gbuffer = new ShaderPack.Pass(
			"gbuffer", ShaderPack.PassKind.GEOMETRY, null, List.of("terrain"),
			List.of(), List.of("g"), List.of(), null, null
		);
		ShaderPack.Pass resolve = new ShaderPack.Pass(
			"resolve", ShaderPack.PassKind.FULLSCREEN, null, List.of(),
			List.of("shadow_map"), List.of("g"), List.of("g"), "gbuffer", null, List.of("shadow_frame")
		);
		ShaderGraphCompiler.CompiledGraph graph = ShaderGraphCompiler.compile(new ShaderPack.Manifest(
			1, "Shadows", Map.of("g", colour), List.of(gbuffer, resolve), List.of()
		));
		if (!graph.passes().get(1).declaration().reads().contains("shadow_map")
			|| !graph.passes().get(1).declaration().buffers().contains("shadow_frame")) {
			throw new AssertionError("Compiled graph dropped external shadow bindings");
		}
		try {
			new ShaderPack.Pass(
				"resolve", ShaderPack.PassKind.FULLSCREEN, null, List.of(),
				List.of("shadow_frame"), List.of("g"), List.of(), null, null
			);
			throw new AssertionError("shadow_frame was accepted as a sampled read");
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains("shadow_frame")) {
				throw new AssertionError("Buffer-as-read diagnostic omitted the name", expected);
			}
		}
		try {
			new ShaderPack.Pass(
				"resolve", ShaderPack.PassKind.FULLSCREEN, null, List.of(),
				List.of(), List.of("g"), List.of(), null, null, List.of("shadow_map")
			);
			throw new AssertionError("shadow_map was accepted as a buffer");
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains("shadow_map")) {
				throw new AssertionError("Texture-as-buffer diagnostic omitted the name", expected);
			}
		}
		try {
			new ShaderPack.Manifest(1, "Reserved", Map.of("shadow_map", colour), List.of(gbuffer), List.of());
			throw new AssertionError("shadow_map was accepted as a pack target");
		} catch (IllegalArgumentException expected) {
			if (!expected.getMessage().contains("shadow_map")) {
				throw new AssertionError("Reserved-target diagnostic omitted the name", expected);
			}
		}
		try {
			ShaderGraphCompiler.compile(new ShaderPack.Manifest(
				1, "Write", Map.of("g", colour),
				List.of(new ShaderPack.Pass(
					"p", ShaderPack.PassKind.FULLSCREEN, null, List.of(),
					List.of(), List.of("shadow_map"), List.of(), null, null
				)),
				List.of()
			));
			throw new AssertionError("A write of shadow_map was accepted");
		} catch (ShaderGraphCompiler.CompileException expected) {
			if (!expected.getMessage().contains("shadow_map")) {
				throw new AssertionError("External-write diagnostic omitted the name", expected);
			}
		}
		try {
			ShaderGraphCompiler.compile(new ShaderPack.Manifest(
				1, "Unknown", Map.of("g", colour),
				List.of(new ShaderPack.Pass(
					"p", ShaderPack.PassKind.FULLSCREEN, null, List.of(),
					List.of(), List.of("g"), List.of(), null, null, List.of("camera")
				)),
				List.of()
			));
			throw new AssertionError("An unknown buffer was accepted");
		} catch (ShaderGraphCompiler.CompileException expected) {
			if (!expected.getMessage().contains("camera")) {
				throw new AssertionError("Unknown-buffer diagnostic omitted the name", expected);
			}
		}
	}

	private static void assertWorldShadowDebugViews(
		final MetalGpuDevice gpu,
		final ShaderPackRuntime runtime,
		final CommandEncoder encoder,
		final MetalTexture scene,
		final MetalGpuTextureView sceneView,
		final MetalGpuTextureView depthView,
		final MetalCommandQueue queue
	) {
		final float viewDepth = 8.0F;
		final float fov = (float)Math.toRadians(90.0);
		Matrix4f projection = new Matrix4f().perspective(fov, 1.0F, 0.05F, 1024.0F, true);
		Matrix4f view = new Matrix4f();
		int packed = Math.round(Math.min(1.0F, Math.max(0.0F, viewDepth / 1024.0F)) * 16_777_215.0F);
		String packedSeed = packedSeedSource(packed);
		RenderPipeline seed = packedSeedPipeline();
		gpu.registerNativePipeline(seed, new MetalGpuDevice.NativeProgram(packedSeed, "seed_vertex", "seed_fragment"));
		String shadows;
		String terrain;
		try {
			shadows = resourceText("/assets/metalcraft/shaderpacks/standard/shared/shadows.metal");
			terrain = resourceText("/assets/metalcraft/shaderpacks/standard/shadow.metal");
		} catch (IOException error) {
			gpu.forgetNativePipeline(seed);
			throw new AssertionError(error);
		}
		var settings = new ShadowCascades.Settings(4, 32, 0.1F, 96, 0.6F, 96);
		var layer = ChunkSectionLayerGroup.OPAQUE.layers()[0];
		int stride = layer.pipeline().getVertexFormatBinding(0).getVertexSize();
		try (WorldShadowModule module = new WorldShadowModule(gpu.metal(), settings);
			 TerrainShadowRenderer renderer = new TerrainShadowRenderer(gpu.metal(), shadows, terrain);
			 MetalBuffer vertices = gpu.metal().createBuffer(32L + (long)stride * 4, MetalBuffer.StorageMode.SHARED);
			 MetalBuffer indices = gpu.metal().createBuffer(16, MetalBuffer.StorageMode.SHARED);
			 MetalTexture atlas = gpu.metal().createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 1, 1, 1));
			 MetalTextureView atlasView = atlas.createView();
			 MetalSampler sampler = gpu.metal().createSampler(new MetalSampler.Descriptor(
				 MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE
			 ))) {
			try (var mapping = vertices.map()) {
				var bytes = mapping.bytes();
				for (int i = 0; i < bytes.capacity(); i++) {
					bytes.put(i, (byte)0);
				}
				for (int i = 0; i < 4; i++) {
					int start = 32 + i * stride;
					bytes.putFloat(start, (i == 0 || i == 3) ? -1024 : 1024);
					bytes.putFloat(start + 8, i < 2 ? -1024 : 1024);
				}
			}
			try (var mapping = indices.map()) {
				mapping.bytes().position(4);
				for (int i : new int[]{0, 1, 2, 0, 2, 3}) {
					mapping.bytes().putShort((short)i);
				}
			}
			ByteBuffer pixel = ByteBuffer.allocateDirect(4);
			pixel.put(new byte[]{-1, -1, -1, -1}).flip();
			atlas.upload(queue, 0, pixel);
			List<TerrainShadowRenderer.Draw> draws = List.of(
				new TerrainShadowRenderer.Draw(layer, vertices, 32, indices, 4, MetalRenderPass.IndexType.UINT16, 6, 0, 8, 0)
			);
			runtime.worldGeometry().setRasterTransforms(projection, view);

			runtime.setOption("debug_view", "receiver");
			drawWorldSeed(runtime, encoder, sceneView, depthView, seed);
			assertReceiverPixel(scene.readback(queue, 0), viewDepth, fov, "receiver reconstruction");

			runtime.setOption("debug_view", "visibility");
			drawWorldSeed(runtime, encoder, sceneView, depthView, seed);
			assertBgraDelta(scene.readback(queue, 0), (byte)255, (byte)255, (byte)255, 2, "unoccluded visibility without a shadow frame");

			try (WorldShadowModule.Frame frame = module.prepareFrame(
				new Vector3d(), new Quaternionf(), fov, 1.0F, new Vector3f(0, 1, 0), new Matrix4f(projection).invert()
			)) {
				runtime.worldGeometry().setShadowFrameSupplier(() -> frame);
				gpu.encodeNativePass(module.depthPass(), "MetalCraft shader: shadow_terrain", pass ->
					renderer.encode(pass, frame, draws, atlasView, sampler));
				runtime.setOption("debug_view", "visibility");
				drawWorldSeed(runtime, encoder, sceneView, depthView, seed);
				assertBgraDelta(scene.readback(queue, 0), (byte)0, (byte)0, (byte)0, 8, "occluded visibility");

				runtime.setOption("debug_view", "cascade");
				drawWorldSeed(runtime, encoder, sceneView, depthView, seed);
				assertBgraDelta(scene.readback(queue, 0), (byte)64, (byte)255, (byte)255, 8, "cascade 0 coverage");

				runtime.setOption("debug_view", "off");
				drawWorldSeed(runtime, encoder, sceneView, depthView, seed);
				assertBgraDelta(scene.readback(queue, 0), (byte)144, (byte)96, (byte)48, 2, "unshadowed seed when UV2 sky is zero");
				assertWorldSunLighting(gpu, runtime, encoder, scene, sceneView, depthView, queue, packed, viewDepth, fov, true);
			}

			try (WorldShadowModule.Frame empty = module.prepareUnoccludedFrame()) {
				runtime.worldGeometry().setShadowFrameSupplier(() -> empty);
				runtime.setOption("debug_view", "visibility");
				drawWorldSeed(runtime, encoder, sceneView, depthView, seed);
				assertBgraDelta(scene.readback(queue, 0), (byte)255, (byte)255, (byte)255, 2, "no-sun visibility");
				assertWorldSunLighting(gpu, runtime, encoder, scene, sceneView, depthView, queue, packed, viewDepth, fov, false);
			}

			runtime.worldGeometry().setShadowFrameSupplier(
				() -> runtime.worldShadows() == null ? null : runtime.worldShadows().currentFrame()
			);
			runtime.setOption("debug_view", "receiver");
			runtime.worldGeometry().beginFrame();
			try (RenderPass pass = WorldGeometryAdapter.beginWorldPass(encoder, () -> "world-sky-smoke",
				sceneView, Optional.of(new Vector4f(0, 0, 0, 1)),
				depthView, OptionalDouble.of(1),
				List.of(RenderPipelines.SOLID_TERRAIN))) {
				WorldGeometryAdapter.substitute(pass, RenderPipelines.SOLID_TERRAIN);
			}
			WorldGeometryAdapter.resolveOpaque();
			try (var fence = encoder.createFence()) {
				encoder.submit();
				if (!fence.awaitCompletion(5_000_000_000L)) {
					throw new AssertionError("Sky resolve GPU submission timed out");
				}
			}
			assertBgraDelta(scene.readback(queue, 0), (byte)0, (byte)0, (byte)0, 2, "sky/far pixels must not reconstruct");
			runtime.setOption("debug_view", "off");
		} finally {
			gpu.forgetNativePipeline(seed);
		}
	}

	private static void drawWorldSeed(
		final ShaderPackRuntime runtime,
		final CommandEncoder encoder,
		final MetalGpuTextureView sceneView,
		final MetalGpuTextureView depthView,
		final RenderPipeline seed
	) {
		runtime.worldGeometry().beginFrame();
		try (RenderPass pass = WorldGeometryAdapter.beginWorldPass(encoder, () -> "world-shadow-smoke",
			sceneView, Optional.of(new Vector4f(0, 0, 0, 1)),
			depthView, OptionalDouble.of(1),
			List.of(RenderPipelines.SOLID_TERRAIN))) {
			WorldGeometryAdapter.substitute(pass, RenderPipelines.SOLID_TERRAIN);
			pass.setPipeline(seed);
			pass.draw(3, 1, 0, 0);
		}
		WorldGeometryAdapter.resolveOpaque();
		try (var fence = encoder.createFence()) {
			encoder.submit();
			if (!fence.awaitCompletion(5_000_000_000L)) {
				throw new AssertionError("World shadow resolve GPU submission timed out");
			}
		}
	}

	private static void assertWorldSunLighting(
		final MetalGpuDevice gpu,
		final ShaderPackRuntime runtime,
		final CommandEncoder encoder,
		final MetalTexture scene,
		final MetalGpuTextureView sceneView,
		final MetalGpuTextureView depthView,
		final MetalCommandQueue queue,
		final int packed,
		final float viewDepth,
		final float fov,
		final boolean occluded
	) {
		float solidAlpha = (WorldGeometryAdapter.Material.SOLID.ordinal() + 1) / 255.0F;
		float emissiveAlpha = (WorldGeometryAdapter.Material.EMISSIVE.ordinal() + 1) / 255.0F;
		float nx = 0.5F;
		float ny = 1.0F;
		runtime.worldGeometry().setFog(new Vector4f(),
			WorldLightingModule.DISABLED_FOG_DISTANCE, WorldLightingModule.DISABLED_FOG_DISTANCE,
			WorldLightingModule.DISABLED_FOG_DISTANCE, WorldLightingModule.DISABLED_FOG_DISTANCE);

		runtime.setOption("debug_view", "off");
		drawLightingSeed(gpu, runtime, encoder, sceneView, depthView, packed,
			144 / 255.0F, 96 / 255.0F, 48 / 255.0F,
			0.2F, 0.4F, 0.6F, solidAlpha, nx, ny, 0.85F, 1.0F, 0.0F, "cave");
		assertBgraDelta(scene.readback(queue, 0), (byte)144, (byte)96, (byte)48, 2,
			occluded ? "occluded cave keeps blocklight" : "unoccluded cave keeps blocklight");

		drawLightingSeed(gpu, runtime, encoder, sceneView, depthView, packed,
			200 / 255.0F, 40 / 255.0F, 40 / 255.0F,
			200 / 255.0F, 40 / 255.0F, 40 / 255.0F, emissiveAlpha, nx, ny, 0.7F, 0.0F, 1.0F, "emissive");
		assertBgraDelta(scene.readback(queue, 0), (byte)200, (byte)40, (byte)40, 2,
			occluded ? "occluded emissive stays unshadowed" : "unoccluded emissive stays fullbright");

		runtime.setOption("shadow_strength", 1.0);
		drawLightingSeed(gpu, runtime, encoder, sceneView, depthView, packed,
			0.4F, 0.4F, 0.4F, 0.4F, 0.4F, 0.4F, solidAlpha, nx, ny, 0.85F, 0.0F, 1.0F, "sun");
		if (occluded) {
			assertBgraDelta(scene.readback(queue, 0), (byte)0, (byte)0, (byte)0, 16, "occluded sun term is darkened");
			// Octahedral +X: N·L with the overhead sun is 0. The vanilla seed still carries
			// full sky lightmap on that face, so gating the sun term on N·L leaves dawn
			// ground and walls unshadowed.
			drawLightingSeed(gpu, runtime, encoder, sceneView, depthView, packed,
				0.4F, 0.4F, 0.4F, 0.4F, 0.4F, 0.4F, solidAlpha, 1.0F, 0.5F, 0.85F, 0.0F, 1.0F, "grazing-sun");
			assertBgraDelta(scene.readback(queue, 0), (byte)0, (byte)0, (byte)0, 16,
				"occluded sky-lit grazing face still loses the sun term");
			runtime.setOption("debug_view", "vanilla");
			drawLightingSeed(gpu, runtime, encoder, sceneView, depthView, packed,
				0.4F, 0.4F, 0.4F, 0.4F, 0.4F, 0.4F, solidAlpha, nx, ny, 0.85F, 0.0F, 1.0F, "vanilla");
			assertBgraDelta(scene.readback(queue, 0), (byte)102, (byte)102, (byte)102, 2, "vanilla debug view preserves the seed");
			runtime.setOption("debug_view", "visibility");
			drawLightingSeed(gpu, runtime, encoder, sceneView, depthView, packed,
				0.4F, 0.4F, 0.4F, 0.4F, 0.4F, 0.4F, solidAlpha, 1.0F, 0.5F, 0.85F, 0.0F, 1.0F, "grazing");
			assertBgraDelta(scene.readback(queue, 0), (byte)0, (byte)0, (byte)0, 8, "grazing slope bias still occludes");
			runtime.setOption("debug_view", "off");
			assertPartialFogUnshadowed(gpu, runtime, encoder, scene, sceneView, depthView, queue, packed, viewDepth, fov, solidAlpha, nx, ny);
		} else {
			assertBgraDelta(scene.readback(queue, 0), (byte)102, (byte)102, (byte)102, 2, "visibility=1 sunlit seed is an identity");
			drawLightingSeed(gpu, runtime, encoder, sceneView, depthView, packed,
				0.4F, 0.4F, 0.4F, 0.4F, 0.4F, 0.4F, solidAlpha, 1.0F, 0.5F, 0.85F, 0.0F, 1.0F, "grazing-identity");
			assertBgraDelta(scene.readback(queue, 0), (byte)102, (byte)102, (byte)102, 2,
				"visibility=1 grazing seed is an identity");
		}
		runtime.setOption("debug_view", "off");
	}

	private static void assertPartialFogUnshadowed(
		final MetalGpuDevice gpu,
		final ShaderPackRuntime runtime,
		final CommandEncoder encoder,
		final MetalTexture scene,
		final MetalGpuTextureView sceneView,
		final MetalGpuTextureView depthView,
		final MetalCommandQueue queue,
		final int packed,
		final float viewDepth,
		final float fov,
		final float solidAlpha,
		final float nx,
		final float ny
	) {
		float spherical = receiverSpherical(viewDepth, fov, 32);
		runtime.worldGeometry().setFog(new Vector4f(1.0F, 1.0F, 1.0F, 1.0F), 0.0F, spherical * 2.0F,
			WorldLightingModule.DISABLED_FOG_DISTANCE, WorldLightingModule.DISABLED_FOG_DISTANCE);
		float unfogged = 0.4F;
		float fogged = unfogged * 0.5F + 0.5F;
		runtime.setOption("debug_view", "vanilla");
		drawLightingSeed(gpu, runtime, encoder, sceneView, depthView, packed,
			fogged, fogged, fogged, unfogged, unfogged, unfogged, solidAlpha, nx, ny, 0.85F, 0.0F, 1.0F, "fog-vanilla");
		assertBgraDelta(scene.readback(queue, 0), unorm8(fogged), unorm8(fogged), unorm8(fogged), 3, "fogged seed before lighting");
		runtime.setOption("debug_view", "off");
		drawLightingSeed(gpu, runtime, encoder, sceneView, depthView, packed,
			fogged, fogged, fogged, unfogged, unfogged, unfogged, solidAlpha, nx, ny, 0.85F, 0.0F, 1.0F, "fog-lit");
		int actual = Byte.toUnsignedInt(scene.readback(queue, 0).get(0));
		int foggedByte = Byte.toUnsignedInt(unorm8(fogged));
		if (actual < 80 || actual > 160 || Math.abs(actual - foggedByte) < 20) {
			throw new AssertionError("Fog must be reapplied after shadowing the sun term, not multiplied with the seed: got "
				+ actual + " foggedSeed=" + foggedByte);
		}
		runtime.worldGeometry().setFog(new Vector4f(),
			WorldLightingModule.DISABLED_FOG_DISTANCE, WorldLightingModule.DISABLED_FOG_DISTANCE,
			WorldLightingModule.DISABLED_FOG_DISTANCE, WorldLightingModule.DISABLED_FOG_DISTANCE);
	}

	private static void drawLightingSeed(
		final MetalGpuDevice gpu,
		final ShaderPackRuntime runtime,
		final CommandEncoder encoder,
		final MetalGpuTextureView sceneView,
		final MetalGpuTextureView depthView,
		final int packed,
		final float sceneR, final float sceneG, final float sceneB,
		final float albedoR, final float albedoG, final float albedoB, final float albedoA,
		final float nx, final float ny, final float roughness,
		final float block, final float sky,
		final String id
	) {
		RenderPipeline seed = lightingSeedPipeline(id);
		gpu.registerNativePipeline(seed, new MetalGpuDevice.NativeProgram(
			lightingSeedSource(packed, sceneR, sceneG, sceneB, albedoR, albedoG, albedoB, albedoA, nx, ny, roughness, block, sky),
			"seed_vertex", "seed_fragment"
		));
		try {
			drawWorldSeed(runtime, encoder, sceneView, depthView, seed);
		} finally {
			gpu.forgetNativePipeline(seed);
		}
	}

	private static RenderPipeline lightingSeedPipeline(final String id) {
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.parse("metalcraft:smoke/lighting_seed_" + id))
			.withVertexShader(Identifier.parse("metalcraft:lighting_seed_" + id))
			.withFragmentShader(Identifier.parse("metalcraft:lighting_seed_" + id))
			.withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
		for (int index = 0; index < 4; index++) {
			builder.withColorTargetState(index, new ColorTargetState(Optional.empty(),
				GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
		}
		return builder.build();
	}

	private static String lightingSeedSource(
		final int packed,
		final float sceneR, final float sceneG, final float sceneB,
		final float albedoR, final float albedoG, final float albedoB, final float albedoA,
		final float nx, final float ny, final float roughness,
		final float block, final float sky
	) {
		float hi = ((packed >> 16) & 255) / 255.0F;
		float mid = ((packed >> 8) & 255) / 255.0F;
		float lo = (packed & 255) / 255.0F;
		return """
			#include <metal_stdlib>
			using namespace metal;
			vertex float4 seed_vertex(uint id [[vertex_id]]) {
			    const float2 p[3] = {float2(-1,-1), float2(3,-1), float2(-1,3)};
			    return float4(p[id], 0, 1);
			}
			struct Targets {
			    float4 scene [[color(0)]];
			    float4 albedo [[color(1)]];
			    float4 normal [[color(2)]];
			    float4 light [[color(3)]];
			};
			fragment Targets seed_fragment() {
			    return {float4(%s,%s,%s,1), float4(%s,%s,%s,%s),
			            float4(%s,%s,%s,%s), float4(%s,%s,%s,%s)};
			}
			""".formatted(sceneR, sceneG, sceneB, albedoR, albedoG, albedoB, albedoA,
			nx, ny, roughness, hi, block, sky, mid, lo);
	}

	private static float receiverSpherical(final float viewDepth, final float fov, final int width) {
		float uv = 0.5F / width;
		float ndcX = uv * 2.0F - 1.0F;
		float ndcY = uv * 2.0F - 1.0F;
		float tanHalf = (float)Math.tan(fov * 0.5);
		float x = ndcX * tanHalf * viewDepth;
		float y = ndcY * tanHalf * viewDepth;
		float z = -viewDepth;
		return (float)Math.sqrt(x * x + y * y + z * z);
	}

	private static RenderPipeline packedSeedPipeline() {
		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(Identifier.parse("metalcraft:smoke/shadow_seed"))
			.withVertexShader(Identifier.parse("metalcraft:shadow_seed"))
			.withFragmentShader(Identifier.parse("metalcraft:shadow_seed"))
			.withCull(false).withPrimitiveTopology(PrimitiveTopology.TRIANGLES);
		for (int index = 0; index < 4; index++) {
			builder.withColorTargetState(index, new ColorTargetState(Optional.empty(),
				GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
		}
		return builder.build();
	}

	private static String packedSeedSource(final int packed) {
		float hi = ((packed >> 16) & 255) / 255.0F;
		float mid = ((packed >> 8) & 255) / 255.0F;
		float lo = (packed & 255) / 255.0F;
		return """
			#include <metal_stdlib>
			using namespace metal;
			vertex float4 seed_vertex(uint id [[vertex_id]]) {
			    const float2 p[3] = {float2(-1,-1), float2(3,-1), float2(-1,3)};
			    return float4(p[id], 0, 1);
			}
			struct Targets {
			    float4 scene [[color(0)]];
			    float4 albedo [[color(1)]];
			    float4 normal [[color(2)]];
			    float4 light [[color(3)]];
			};
			fragment Targets seed_fragment() {
			    return {float4(144.0/255,96.0/255,48.0/255,1), float4(0.2,0.4,0.6,1),
			            float4(0.5,0.5,0.8, %s), float4(0, 0, %s, %s)};
			}
			""".formatted(hi, mid, lo);
	}

	private static void assertReceiverPixel(final ByteBuffer pixels, final float viewDepth, final float fov, final String description) {
		float uv = 0.5F / 32.0F;
		float ndcX = uv * 2.0F - 1.0F;
		float ndcY = uv * 2.0F - 1.0F;
		float tanHalf = (float)Math.tan(fov * 0.5);
		float x = ndcX * tanHalf * viewDepth;
		float y = ndcY * tanHalf * viewDepth;
		float z = -viewDepth;
		assertBgraDelta(pixels, unorm8(x / 64.0F + 0.5F), unorm8(y / 64.0F + 0.5F), unorm8(z / 64.0F + 0.5F), 3, description);
	}

	private static byte unorm8(final float value) {
		return (byte)Math.round(Math.min(1.0F, Math.max(0.0F, value)) * 255.0F);
	}

	private static String resourceText(final String path) throws IOException {
		try (var input = MetalShaderTranslationSmoke.class.getResourceAsStream(path)) {
			if (input == null) {
				throw new IOException("Missing " + path);
			}
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static void assertBundledGradeGraph() {
		try {
			ShaderPack pack = ShaderPackLoader.loadBundled(
				ShaderPackRuntime.class.getClassLoader(),
				ShaderPackRuntime.BUILTIN_ID,
				"assets/metalcraft/shaderpacks/standard"
			);
			ShaderGraphCompiler.CompiledGraph graph = ShaderGraphCompiler.compile(pack);
			List<String> order = graph.passes().stream().map(pass -> pass.declaration().id()).toList();
			if (!order.equals(List.of("gbuffer", "resolve", "grade"))) {
				throw new AssertionError("Built-in pack pass order was " + order);
			}
			ShaderPack.Pass gbuffer = graph.passes().getFirst().declaration();
			if (!gbuffer.writes().containsAll(List.of("scene", "gbuffer_albedo", "gbuffer_normal", "gbuffer_light", "depth"))) {
				throw new AssertionError("G-buffer writes were " + gbuffer.writes());
			}
			if (!"gbuffer".equals(graph.passes().get(1).declaration().mergeWith())) {
				throw new AssertionError("Resolve must merge_with gbuffer");
			}
			ShaderPack.Pass resolve = graph.passes().get(1).declaration();
			if (!resolve.reads().contains("shadow_map") || !resolve.buffers().contains("shadow_frame")) {
				throw new AssertionError("Resolve must declare shadow_map and shadow_frame: reads="
					+ resolve.reads() + " buffers=" + resolve.buffers());
			}
			List<Object> debugViews = pack.manifest().options().stream()
				.filter(option -> option.id().equals("debug_view"))
				.findFirst()
				.orElseThrow()
				.values();
			if (!debugViews.containsAll(List.of("receiver", "cascade", "visibility", "vanilla"))) {
				throw new AssertionError("debug_view values were " + debugViews);
			}
			for (String id : List.of("gbuffer_albedo", "gbuffer_normal", "gbuffer_light")) {
				ShaderGraphCompiler.TargetInfo info = graph.targets().get(id);
				if (info == null || !info.memoryless()) {
					throw new AssertionError(id + " must be memoryless");
				}
			}
			ShaderGraphCompiler.TargetInfo post = graph.targets().get("post_color");
			if (post == null || post.memoryless()) {
				throw new AssertionError("Built-in post_color must exist and not be memoryless");
			}
		} catch (IOException error) {
			throw new AssertionError("Could not load the bundled MetalCraft Standard pack", error);
		}
	}

	private static void assertDefaultSelectedNone(final MetalDevice device) {
		try {
			Path root = Files.createTempDirectory("metalcraft-default-none-");
			try (ShaderPackRuntime runtime = new ShaderPackRuntime(
				device, root.resolve("shaderpacks"), root.resolve("metalcraft-shaders.json")
			)) {
				if (!ShaderPackRuntime.NONE_ID.equals(runtime.selectedPackId()) || runtime.isActive()) {
					throw new AssertionError(
						"Default selectedPackId is " + runtime.selectedPackId() + ", isActive=" + runtime.isActive()
					);
				}
			}
		} catch (IOException error) {
			throw new AssertionError("Could not construct a default shader runtime", error);
		}
	}

	private static void assertShadersDisableProperty(final MetalDevice device) {
		String previous = System.getProperty("metalcraft.shaders.disable");
		System.setProperty("metalcraft.shaders.disable", "true");
		try {
			if (ShaderPackRuntime.createDefault(device) != null) {
				throw new AssertionError("createDefault must return null when metalcraft.shaders.disable is true");
			}
		} finally {
			if (previous == null) {
				System.clearProperty("metalcraft.shaders.disable");
			} else {
				System.setProperty("metalcraft.shaders.disable", previous);
			}
		}
	}

	private static void assertFailedMsl(final MetalDevice device, final ShaderPackRuntime runtime, final Path shaderpacks) {
		try {
			Path broken = shaderpacks.resolve("broken");
			Files.createDirectories(broken);
			Files.writeString(broken.resolve("pack.json"), """
				{
				  "format": 2,
				  "name": "Broken",
				  "targets": {
				    "post_color": { "format": "bgra8_unorm", "scale": 1.0, "lifetime": "frame" }
				  },
				  "passes": [
				    {
				      "id": "grade",
				      "kind": "fullscreen",
				      "source": "grade.metal",
				      "reads": ["scene"],
				      "writes": ["post_color"]
				    }
				  ]
				}
				""", StandardCharsets.UTF_8);
			Files.writeString(broken.resolve("grade.metal"), "this is not valid MSL\n", StandardCharsets.UTF_8);
			runtime.selectPack("broken");
			if (runtime.lastError().isEmpty() || runtime.isActive()) {
				throw new AssertionError(
					"Failed MSL must set lastError and isActive false, lastError=" + runtime.lastError()
						+ " isActive=" + runtime.isActive()
				);
			}
			try (MetalCommandQueue queue = device.createCommandQueue();
				 MetalTexture scene = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.BGRA8_UNORM, 8, 8, 1
				 ));
				 MetalTexture output = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.BGRA8_UNORM, 8, 8, 1
				 ));
				 MetalCommandBuffer commands = queue.createCommandBuffer()) {
				if (runtime.executor().isPresent()) {
					runtime.executor().orElseThrow().encodeForTesting(commands, scene, output);
				}
			}
		} catch (IOException error) {
			throw new AssertionError("Could not build the failed-MSL fixture", error);
		}
	}

	private static void assertDeclaredShaderGraph() {
		Path root = Path.of("build", "shader-graph-smoke");
		deleteTree(root);
		String manifest = """
			{
			  "format": 1,
			  "name": "Declared Graph",
			  "targets": {
			    "g": {"format": "rgba8_unorm", "scale": 1.0, "lifetime": "transient"},
			    "lit": {"format": "rgba8_unorm", "scale": 1.0, "lifetime": "frame"}
			  },
			  "passes": [
			    {"id": "resolve", "kind": "fullscreen", "tile_reads": ["g"], "merge_with": "gbuffer", "writes": ["lit"]},
			    {"id": "final", "kind": "fullscreen", "reads": ["lit"], "writes": ["drawable"]},
			    {"id": "gbuffer", "kind": "geometry", "geometry": "terrain", "writes": ["g"]}
			  ],
			  "options": []
			}
			""";
		try {
			Files.createDirectories(root);
			Path zipPath = root.resolve("declared.zip");
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
				writeZipText(zip, "pack.json", manifest);
				writeZipText(zip, "graph.metal", GRAPH_MSL);
			}
			List<ShaderPackLoader.PackRef> discovered = ShaderPackLoader.discover(root);
			if (discovered.size() != 1 || discovered.getFirst().kind() != ShaderPackLoader.Kind.ZIP) {
				throw new AssertionError("Shader-pack zip discovery did not return the declared pack");
			}
			ShaderGraphCompiler.CompiledGraph graph = ShaderGraphCompiler.compile(ShaderPackLoader.load(discovered.getFirst()));
			List<String> order = graph.passes().stream().map(pass -> pass.declaration().id()).toList();
			if (!order.equals(List.of("gbuffer", "resolve", "final")) || !graph.targets().get("g").memoryless()) {
				throw new AssertionError("Declared shader graph did not derive ordering and memoryless promotion: " + order);
			}
			if (graph.passes().getFirst().writes().getFirst().storeAction() != ShaderGraphCompiler.StoreAction.DONT_CARE
				|| graph.passes().get(1).writes().getFirst().storeAction() != ShaderGraphCompiler.StoreAction.STORE) {
				throw new AssertionError("Declared shader graph did not derive attachment store actions");
			}

			Path invalid = root.resolve("invalid");
			Files.createDirectories(invalid);
			String invalidManifest = manifest.replace(
				"\"tile_reads\": [\"g\"], \"merge_with\": \"gbuffer\"", "\"reads\": [\"g\"]"
			);
			Files.writeString(invalid.resolve("pack.json"), invalidManifest, StandardCharsets.UTF_8);
			Files.writeString(invalid.resolve("graph.metal"), GRAPH_MSL, StandardCharsets.UTF_8);
			try {
				ShaderGraphCompiler.compile(ShaderPackLoader.load(invalid));
				throw new AssertionError("A transient target sampled by an unmerged pass was accepted");
			} catch (ShaderGraphCompiler.CompileException expected) {
				if (!expected.getMessage().contains("gbuffer") || !expected.getMessage().contains("resolve")) {
					throw new AssertionError("Transient-read diagnostic omitted the offending passes", expected);
				}
			}
		} catch (IOException error) {
			throw new AssertionError("Could not build the declared shader graph fixture", error);
		}
	}

	private static void assertInvalidPackJson() {
		try {
			Path root = Files.createTempDirectory("metalcraft-invalid-pack-");
			Files.writeString(root.resolve("pack.json"), "{not json", StandardCharsets.UTF_8);
			try {
				ShaderPackLoader.load(root);
				throw new AssertionError("Invalid pack.json was accepted");
			} catch (ShaderPackLoader.LoadException expected) {
				if (expected.getMessage() == null || expected.getMessage().isBlank()) {
					throw new AssertionError("Invalid pack.json diagnostic was blank", expected);
				}
			}
		} catch (IOException error) {
			throw new AssertionError("Could not build the invalid pack.json fixture", error);
		}
	}

	private static void assertIncludeCycle() {
		ShaderPack.Target post = new ShaderPack.Target(
			ShaderPack.PixelFormat.BGRA8_UNORM, new ShaderPack.Scale(1.0), ShaderPack.Lifetime.FRAME, 1
		);
		ShaderPack.Pass pass = new ShaderPack.Pass(
			"grade",
			ShaderPack.PassKind.FULLSCREEN,
			"grade.metal",
			List.of(),
			List.of("scene"),
			List.of("post_color"),
			List.of(),
			null,
			null
		);
		ShaderPack pack = new ShaderPack(
			"cycle",
			new ShaderPack.Manifest(
				2,
				"Cycle",
				List.of(),
				Map.of("post_color", post),
				List.of(pass),
				List.of(),
				Map.of()
			),
			Map.of(
				"a.metal", "#include \"b.metal\"\n",
				"b.metal", "#include \"a.metal\"\n",
				"grade.metal", "#include \"a.metal\"\n"
			)
		);
		try {
			ShaderPackLoader.expandPassSource(pack, pass);
			throw new AssertionError("An include cycle was accepted");
		} catch (ShaderPackLoader.LoadException expected) {
			if (expected.getMessage() == null || !expected.getMessage().contains("Include cycle")) {
				throw new AssertionError("Include-cycle diagnostic omitted the cycle", expected);
			}
		}
	}

	private static void assertMixedRenderComputeGraph(final MetalDevice device) {
		Path root = Path.of("build", "shader-graph-smoke");
		try {
			Files.createDirectories(root);
			Path zipPath = root.resolve("mixed-graph.zip");
			try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
				writeZipText(zip, "pack.json", MIXED_GRAPH_PACK);
				writeZipText(zip, "lift.metal", MIXED_LIFT_MSL);
				writeZipText(zip, "filter.metal", MIXED_FILTER_MSL);
				writeZipText(zip, "unit.metal", MIXED_UNIT_MSL);
				writeZipText(zip, "compose.metal", MIXED_COMPOSE_MSL);
			}
			ShaderPack pack = ShaderPackLoader.load(zipPath);
			ShaderGraphCompiler.CompiledGraph graph = ShaderGraphCompiler.compile(pack);
			List<String> order = graph.passes().stream().map(pass -> pass.declaration().id()).toList();
			if (!order.equals(List.of("lift", "filter", "unit", "compose"))) {
				throw new AssertionError("Mixed graph pass order was " + order);
			}
			if (graph.targets().get("linear").declaration().format() != ShaderPack.PixelFormat.RGBA16_FLOAT
				|| graph.targets().get("half").declaration().format() != ShaderPack.PixelFormat.RGBA8_UNORM
				|| graph.targets().get("dot").declaration().format() != ShaderPack.PixelFormat.R8_UNORM) {
				throw new AssertionError("Mixed graph lost intermediate formats");
			}
		} catch (IOException error) {
			throw new AssertionError("Could not build the mixed render/compute graph fixture", error);
		}

		Path settingsRoot;
		try {
			settingsRoot = Files.createTempDirectory("metalcraft-mixed-graph-");
		} catch (IOException error) {
			throw new AssertionError("Could not create mixed-graph runtime directory", error);
		}
		try (ShaderPackRuntime runtime = new ShaderPackRuntime(
			device, settingsRoot.resolve("shaderpacks"), settingsRoot.resolve("metalcraft-shaders.json")
		)) {
			installPack(settingsRoot.resolve("shaderpacks"), "mixed-graph", MIXED_GRAPH_PACK, Map.of(
				"lift.metal", MIXED_LIFT_MSL,
				"filter.metal", MIXED_FILTER_MSL,
				"unit.metal", MIXED_UNIT_MSL,
				"compose.metal", MIXED_COMPOSE_MSL
			));
			runtime.selectPack("mixed-graph");
			if (!runtime.isActive()) {
				throw new AssertionError("Mixed graph pack did not activate: " + runtime.lastError());
			}
			assertMixedPixels(device, runtime, 17, 13);
			assertMixedPixels(device, runtime, 1, 8);
			assertMixedPixels(device, runtime, 8, 1);
		}
	}

	private static void assertMixedPixels(
		final MetalDevice device,
		final ShaderPackRuntime runtime,
		final int width,
		final int height
	) {
		runtime.resize(width, height);
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture scene = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.BGRA8_UNORM, width, height, 1
			 ));
			 MetalTexture output = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.BGRA8_UNORM, width, height, 1
			 ))) {
			fillSolidBgra(scene, queue, (byte)0x20, (byte)0x40, (byte)0x80, (byte)0xFF);
			encodeGrade(queue, runtime.executor().orElseThrow(), scene, output);
			assertBgraDelta(output.readback(queue, 0), (byte)0x20, (byte)0x80, (byte)0x80, 3, "mixed graph " + width + "x" + height);
			MetalTexture half = runtime.target("half");
			MetalTexture dot = runtime.target("dot");
			if (half == null || dot == null) {
				throw new AssertionError("Mixed graph intermediates were not allocated");
			}
			if ((half.descriptor().usage() & MetalTexture.USAGE_SHADER_WRITE) == 0
				|| (half.descriptor().usage() & MetalTexture.USAGE_SHADER_READ) == 0) {
				throw new AssertionError("Compute intermediate half must be shader-read/write, usage=" + half.descriptor().usage());
			}
			if (dot.descriptor().width() != 1 || dot.descriptor().height() != 1) {
				throw new AssertionError("1-pixel target was " + dot.descriptor().width() + "x" + dot.descriptor().height());
			}
			ByteBuffer halfPixels = half.readback(queue, 0);
			int halfR = Byte.toUnsignedInt(halfPixels.get(0));
			int halfG = Byte.toUnsignedInt(halfPixels.get(1));
			int halfB = Byte.toUnsignedInt(halfPixels.get(2));
			if (halfR > 3 || Math.abs(halfG - 0x80) > 3 || Math.abs(halfB - 0x20) > 3) {
				throw new AssertionError(
					"half intermediate RGBA was " + halfR + "," + halfG + "," + halfB + " expected ~0,128,32"
				);
			}
			int marker = Byte.toUnsignedInt(dot.readback(queue, 0).get(0));
			if (Math.abs(marker - 0x20) > 3) {
				throw new AssertionError("1-pixel compute write was " + marker + ", expected ~32");
			}
		}
	}

	private static void assertConsecutiveUniformUploads(final MetalDevice device) {
		Path root;
		try {
			root = Files.createTempDirectory("metalcraft-uniform-ring-");
		} catch (IOException error) {
			throw new AssertionError("Could not create uniform-ring runtime directory", error);
		}
		try (ShaderPackRuntime runtime = new ShaderPackRuntime(
			device, root.resolve("shaderpacks"), root.resolve("metalcraft-shaders.json")
		)) {
			runtime.selectPack(ShaderPackRuntime.BUILTIN_ID);
			runtime.resize(16, 16);
			try (MetalCommandQueue queue = device.createCommandQueue();
				 MetalTexture scene = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.BGRA8_UNORM, 16, 16, 1
				 ));
				 MetalTexture first = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.BGRA8_UNORM, 16, 16, 1
				 ));
				 MetalTexture second = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.BGRA8_UNORM, 16, 16, 1
				 ))) {
				fillSolidBgra(scene, queue, (byte)0x30, (byte)0x60, (byte)0x90, (byte)0xFF);
				runtime.setOption("exposure", 1.0);
				MetalCommandBuffer frameN = queue.createCommandBuffer();
				if (!runtime.executor().orElseThrow().encodeForTesting(frameN, scene, first)) {
					throw new AssertionError("Frame N encodeForTesting declined");
				}
				frameN.commit();
				runtime.setOption("exposure", 0.5);
				MetalCommandBuffer frameN1 = queue.createCommandBuffer();
				if (!runtime.executor().orElseThrow().encodeForTesting(frameN1, scene, second)) {
					throw new AssertionError("Frame N+1 encodeForTesting declined");
				}
				frameN1.commit();
				frameN.waitUntilCompleted();
				frameN1.waitUntilCompleted();
				frameN.close();
				frameN1.close();
				assertBgraDelta(first.readback(queue, 0), (byte)0x30, (byte)0x60, (byte)0x90, 2, "in-flight frame N exposure");
				assertBgraDelta(second.readback(queue, 0), (byte)0x18, (byte)0x30, (byte)0x48, 2, "in-flight frame N+1 exposure");
			}
		}
	}

	private static void assertFailedComputeKernel(final MetalDevice device) {
		Path root;
		try {
			root = Files.createTempDirectory("metalcraft-failed-compute-");
		} catch (IOException error) {
			throw new AssertionError("Could not create failed-compute runtime directory", error);
		}
		try (ShaderPackRuntime runtime = new ShaderPackRuntime(
			device, root.resolve("shaderpacks"), root.resolve("metalcraft-shaders.json")
		)) {
			installPack(root.resolve("shaderpacks"), "broken-compute", BROKEN_COMPUTE_PACK, Map.of(
				"filter.metal", "this is not a Metal kernel\n"
			));
			runtime.selectPack("broken-compute");
			if (runtime.lastError().isEmpty() || runtime.isActive() || runtime.executor().isPresent()) {
				throw new AssertionError(
					"Failed compute kernel must set lastError and disable the pack, lastError="
						+ runtime.lastError() + " isActive=" + runtime.isActive()
				);
			}
		}
	}

	private static void assertUnsupportedExecutorBindings(final MetalDevice device) {
		Path root;
		try {
			root = Files.createTempDirectory("metalcraft-invalid-bind-");
		} catch (IOException error) {
			throw new AssertionError("Could not create invalid-binding runtime directory", error);
		}
		try (ShaderPackRuntime runtime = new ShaderPackRuntime(
			device, root.resolve("shaderpacks"), root.resolve("metalcraft-shaders.json")
		)) {
			installPack(root.resolve("shaderpacks"), "shadow-read", SHADOW_READ_PACK, Map.of(
				"grade.metal", MIXED_COMPOSE_MSL.replace("compose_", "grade_")
			));
			runtime.selectPack("shadow-read");
			if (runtime.lastError().isEmpty() || runtime.isActive()) {
				throw new AssertionError(
					"Unsupported executor binding must fail configuration, lastError=" + runtime.lastError()
				);
			}
			if (runtime.lastError().orElse("").isBlank()) {
				throw new AssertionError("Unsupported binding lastError was blank");
			}
		}
	}

	private static void assertWorldCompositionBoundary(final MetalDevice device) {
		if (WorldComposition.PACK_POST != WorldComposition.Stage.WORLD_GRADE_AA) {
			throw new AssertionError("Pack post must insert at WORLD_GRADE_AA, not " + WorldComposition.PACK_POST);
		}
		if (!WorldComposition.excludesHud(WorldComposition.PACK_POST)
			|| WorldComposition.excludesHud(WorldComposition.Stage.PRESENT)
			|| WorldComposition.excludesHud(WorldComposition.Stage.HUD)) {
			throw new AssertionError("Only WORLD_GRADE_AA excludes HUD/hand");
		}
		if (WorldComposition.WORLD_STAGE_SITES.stream().noneMatch(site -> site.contains("addMainPass"))
			|| WorldComposition.WORLD_STAGE_SITES.stream().noneMatch(site -> site.contains("Fabulous"))
			|| WorldComposition.WORLD_STAGE_SITES.stream().noneMatch(site -> site.contains("addCloudsPass"))
			|| WorldComposition.WORLD_STAGE_SITES.stream().noneMatch(site -> site.contains("ENTITY_OUTLINE"))) {
			throw new AssertionError("World-stage audit omitted 26.2 call sites: " + WorldComposition.WORLD_STAGE_SITES);
		}
		if (WorldComposition.AFTER_WORLD_SITES.stream().noneMatch(site -> site.contains("renderItemInHand"))
			|| WorldComposition.AFTER_WORLD_SITES.stream().noneMatch(site -> site.contains("submitWater"))
			|| WorldComposition.AFTER_WORLD_SITES.stream().noneMatch(site -> site.contains("checkEntityPostEffect"))
			|| WorldComposition.AFTER_WORLD_SITES.stream().noneMatch(site -> site.contains("GuiRenderer"))) {
			throw new AssertionError("After-world audit omitted 26.2 call sites: " + WorldComposition.AFTER_WORLD_SITES);
		}

		Path root;
		try {
			root = Files.createTempDirectory("metalcraft-world-depth-");
		} catch (IOException error) {
			throw new AssertionError("Could not create world-depth runtime directory", error);
		}
		try (ShaderPackRuntime runtime = new ShaderPackRuntime(
			device, root.resolve("shaderpacks"), root.resolve("metalcraft-shaders.json")
		)) {
			installPack(root.resolve("shaderpacks"), "world-depth", DEPTH_PACK, Map.of("grade.metal", DEPTH_MSL));
			runtime.selectPack("world-depth");
			if (!runtime.isActive()) {
				throw new AssertionError("World-depth pack did not activate: " + runtime.lastError());
			}
			runtime.resize(8, 8);
			try (MetalCommandQueue queue = device.createCommandQueue();
				 MetalTexture scene = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.BGRA8_UNORM, 8, 8, 1
				 ));
				 MetalTexture worldDepth = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.DEPTH32_FLOAT, 8, 8, 1,
					 MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_RENDER_TARGET
				 ));
				 MetalTexture handDepth = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.DEPTH32_FLOAT, 8, 8, 1,
					 MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_RENDER_TARGET
				 ));
				 MetalTextureView worldView = worldDepth.createView();
				 MetalTextureView sceneView = scene.createView()) {
				fillSolidBgra(scene, queue, (byte)0x10, (byte)0x10, (byte)0x10, (byte)0xFF);
				fillDepth(worldDepth, queue, 0.25F);
				fillDepth(handDepth, queue, 0.75F);
				ShaderFrameExecutor executor = runtime.executor().orElseThrow();
				try (MetalCommandBuffer present = queue.createCommandBuffer()) {
					FrameBindings presentBindings = WorldComposition.present(scene, sceneView, 8, 8);
					if (executor.encode(present, presentBindings)) {
						throw new AssertionError("Present-time encode must not sample missing world depth");
					}
				}
				Matrix4f projection = new Matrix4f().identity();
				FrameBindings worldBindings = WorldComposition.world(
					scene, sceneView, 8, 8, worldDepth, worldView, projection
				);
				if (worldBindings.stage() != WorldComposition.PACK_POST
					|| worldBindings.worldDepthWidth() != 8
					|| worldBindings.worldDepthHeight() != 8
					|| worldBindings.worldProjection() == null
					|| worldBindings.worldDepth() != worldDepth) {
					throw new AssertionError("World FrameBindings lost depth/projection/stage metadata");
				}
				if (worldBindings.worldDepth() == handDepth) {
					throw new AssertionError("Hand depth must not be interchangeable with world depth");
				}
				try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
					if (!executor.encode(commands, worldBindings)) {
						throw new AssertionError("World-composition encode declined a depth snapshot");
					}
					commands.commitAndWait();
				}
				MetalTexture post = runtime.target("post_color");
				assertBgraDelta(
					post.readback(queue, 0),
					(byte)64,
					(byte)64,
					(byte)64,
					3,
					"world depth snapshot"
				);
			}
		}
	}

	private static void installPack(
		final Path shaderpacks,
		final String id,
		final String packJson,
		final Map<String, String> sources
	) {
		try {
			Path pack = shaderpacks.resolve(id);
			Files.createDirectories(pack);
			Files.writeString(pack.resolve("pack.json"), packJson, StandardCharsets.UTF_8);
			for (Map.Entry<String, String> source : sources.entrySet()) {
				Files.writeString(pack.resolve(source.getKey()), source.getValue(), StandardCharsets.UTF_8);
			}
		} catch (IOException error) {
			throw new AssertionError("Could not install shader pack " + id, error);
		}
	}

	private static void fillDepth(final MetalTexture texture, final MetalCommandQueue queue, final float depth) {
		int pixels = texture.descriptor().width() * texture.descriptor().height();
		ByteBuffer bytes = ByteBuffer.allocateDirect(pixels * 4).order(ByteOrder.nativeOrder());
		for (int pixel = 0; pixel < pixels; pixel++) {
			bytes.putFloat(depth);
		}
		texture.upload(queue, 0, bytes.flip());
	}

	private static final String MIXED_GRAPH_PACK = """
		{
		  "format": 2,
		  "name": "Mixed Graph",
		  "targets": {
		    "linear": { "format": "rgba16_float", "scale": 1.0, "lifetime": "frame" },
		    "half": { "format": "rgba8_unorm", "scale": 0.5, "lifetime": "frame" },
		    "dot": { "format": "r8_unorm", "size": 1, "lifetime": "frame" },
		    "post_color": { "format": "bgra8_unorm", "scale": 1.0, "lifetime": "frame" }
		  },
		  "passes": [
		    {
		      "id": "lift",
		      "kind": "fullscreen",
		      "source": "lift.metal",
		      "reads": ["scene"],
		      "writes": ["linear"]
		    },
		    {
		      "id": "filter",
		      "kind": "compute",
		      "source": "filter.metal",
		      "reads": ["linear"],
		      "writes": ["half"]
		    },
		    {
		      "id": "unit",
		      "kind": "compute",
		      "source": "unit.metal",
		      "reads": ["half"],
		      "writes": ["dot"]
		    },
		    {
		      "id": "compose",
		      "kind": "fullscreen",
		      "source": "compose.metal",
		      "reads": ["scene", "half", "dot"],
		      "writes": ["post_color"]
		    }
		  ]
		}
		""";

	private static final String MIXED_LIFT_MSL = """
		#include <metal_stdlib>
		using namespace metal;
		struct Varyings { float4 position [[position]]; float2 uv; };
		vertex Varyings lift_vertex(uint vertexId [[vertex_id]]) {
		    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    float2 p = corners[vertexId % 3];
		    return {float4(p, 0.0, 1.0), p * 0.5 + 0.5};
		}
		fragment float4 lift_fragment(
		    Varyings in [[stage_in]],
		    texture2d<float> sceneTex [[texture(MC_TEX_SCENE)]],
		    sampler sceneSampler [[sampler(MC_TEX_SCENE)]]
		) {
		    float3 sampled = sceneTex.sample(sceneSampler, in.uv).rgb;
		    return float4(sampled + float3(0.25, 0.0, 0.0), 1.0);
		}
		""";

	private static final String MIXED_FILTER_MSL = """
		#include <metal_stdlib>
		using namespace metal;
		kernel void filter_kernel(
		    texture2d<float> linearTex [[texture(MC_TEX_LINEAR)]],
		    sampler linearSampler [[sampler(MC_TEX_LINEAR)]],
		    texture2d<float, access::write> halfTex [[texture(MC_TARGET_HALF)]],
		    uint2 pixel [[thread_position_in_grid]]
		) {
		    if (pixel.x >= halfTex.get_width() || pixel.y >= halfTex.get_height()) {
		        return;
		    }
		    float2 uv = (float2(pixel) + 0.5) / float2(halfTex.get_width(), halfTex.get_height());
		    float4 c = linearTex.sample(linearSampler, uv);
		    halfTex.write(float4(0.0, c.g + 0.25, c.b, 1.0), pixel);
		}
		""";

	private static final String MIXED_UNIT_MSL = """
		#include <metal_stdlib>
		using namespace metal;
		kernel void unit_kernel(
		    texture2d<float> halfTex [[texture(MC_TEX_HALF)]],
		    sampler halfSampler [[sampler(MC_TEX_HALF)]],
		    texture2d<float, access::write> dotTex [[texture(MC_TARGET_DOT)]],
		    uint2 pixel [[thread_position_in_grid]]
		) {
		    if (pixel.x >= dotTex.get_width() || pixel.y >= dotTex.get_height()) {
		        return;
		    }
		    float4 c = halfTex.sample(halfSampler, float2(0.5, 0.5));
		    dotTex.write(float4(c.b), pixel);
		}
		""";

	private static final String MIXED_COMPOSE_MSL = """
		#include <metal_stdlib>
		using namespace metal;
		struct Varyings { float4 position [[position]]; float2 uv; };
		vertex Varyings compose_vertex(uint vertexId [[vertex_id]]) {
		    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    float2 p = corners[vertexId % 3];
		    return {float4(p, 0.0, 1.0), p * 0.5 + 0.5};
		}
		fragment float4 compose_fragment(
		    Varyings in [[stage_in]],
		    texture2d<float> sceneTex [[texture(MC_TEX_SCENE)]],
		    sampler sceneSampler [[sampler(MC_TEX_SCENE)]],
		    texture2d<float> halfTex [[texture(MC_TEX_HALF)]],
		    sampler halfSampler [[sampler(MC_TEX_HALF)]],
		    texture2d<float> dotTex [[texture(MC_TEX_DOT)]],
		    sampler dotSampler [[sampler(MC_TEX_DOT)]]
		) {
		    float3 scene = sceneTex.sample(sceneSampler, in.uv).rgb;
		    float3 halfc = halfTex.sample(halfSampler, in.uv).rgb;
		    float marker = dotTex.sample(dotSampler, float2(0.5, 0.5)).r;
		    return float4(scene.r, halfc.g, marker, 1.0);
		}
		""";

	private static final String BROKEN_COMPUTE_PACK = """
		{
		  "format": 2,
		  "name": "Broken Compute",
		  "targets": {
		    "half": { "format": "rgba8_unorm", "scale": 1.0, "lifetime": "frame" },
		    "post_color": { "format": "bgra8_unorm", "scale": 1.0, "lifetime": "frame" }
		  },
		  "passes": [
		    {
		      "id": "filter",
		      "kind": "compute",
		      "source": "filter.metal",
		      "reads": ["scene"],
		      "writes": ["half"]
		    },
		    {
		      "id": "grade",
		      "kind": "fullscreen",
		      "source": "filter.metal",
		      "reads": ["half"],
		      "writes": ["post_color"]
		    }
		  ]
		}
		""";

	private static final String SHADOW_READ_PACK = """
		{
		  "format": 2,
		  "name": "Shadow Read",
		  "targets": {
		    "post_color": { "format": "bgra8_unorm", "scale": 1.0, "lifetime": "frame" }
		  },
		  "passes": [
		    {
		      "id": "grade",
		      "kind": "fullscreen",
		      "source": "grade.metal",
		      "reads": ["shadow_map"],
		      "writes": ["post_color"]
		    }
		  ]
		}
		""";

	private static final String DEPTH_PACK = """
		{
		  "format": 2,
		  "name": "World Depth",
		  "targets": {
		    "post_color": { "format": "bgra8_unorm", "scale": 1.0, "lifetime": "frame" }
		  },
		  "passes": [
		    {
		      "id": "grade",
		      "kind": "fullscreen",
		      "source": "grade.metal",
		      "reads": ["depth"],
		      "writes": ["post_color"]
		    }
		  ]
		}
		""";

	private static final String DEPTH_MSL = """
		#include <metal_stdlib>
		using namespace metal;
		struct Varyings { float4 position [[position]]; float2 uv; };
		vertex Varyings grade_vertex(uint vertexId [[vertex_id]]) {
		    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    float2 p = corners[vertexId % 3];
		    return {float4(p, 0.0, 1.0), p * 0.5 + 0.5};
		}
		fragment float4 grade_fragment(
		    Varyings in [[stage_in]],
		    texture2d<float> depthTex [[texture(MC_TEX_DEPTH)]],
		    sampler depthSampler [[sampler(MC_TEX_DEPTH)]]
		) {
		    float depth = depthTex.sample(depthSampler, in.uv).r;
		    return float4(depth, depth, depth, 1.0);
		}
		""";

	private static void encodeGrade(
		final MetalCommandQueue queue,
		final ShaderFrameExecutor executor,
		final MetalTexture scene,
		final MetalTexture output
	) {
		try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
			if (!executor.encodeForTesting(commands, scene, output)) {
				throw new AssertionError("encodeForTesting declined a resized grade pass");
			}
			commands.commitAndWait();
		}
	}

	private static void fillSolidBgra(
		final MetalTexture texture,
		final MetalCommandQueue queue,
		final byte blue,
		final byte green,
		final byte red,
		final byte alpha
	) {
		int pixels = texture.descriptor().width() * texture.descriptor().height();
		ByteBuffer bytes = ByteBuffer.allocateDirect(pixels * 4).order(ByteOrder.nativeOrder());
		for (int pixel = 0; pixel < pixels; pixel++) {
			bytes.put(blue).put(green).put(red).put(alpha);
		}
		texture.upload(queue, 0, bytes.flip());
	}

	private static void assertBgraDelta(
		final ByteBuffer pixels,
		final byte blue,
		final byte green,
		final byte red,
		final int delta,
		final String description
	) {
		int actualB = Byte.toUnsignedInt(pixels.get(0));
		int actualG = Byte.toUnsignedInt(pixels.get(1));
		int actualR = Byte.toUnsignedInt(pixels.get(2));
		if (Math.abs(actualB - Byte.toUnsignedInt(blue)) > delta
			|| Math.abs(actualG - Byte.toUnsignedInt(green)) > delta
			|| Math.abs(actualR - Byte.toUnsignedInt(red)) > delta) {
			throw new AssertionError(description + " RGB delta exceeded " + delta + ": got BGRA="
				+ actualB + "," + actualG + "," + actualR
				+ " expected " + Byte.toUnsignedInt(blue) + "," + Byte.toUnsignedInt(green) + "," + Byte.toUnsignedInt(red));
		}
	}

	private static void writeZipText(final ZipOutputStream zip, final String path, final String text) throws IOException {
		zip.putNextEntry(new ZipEntry(path));
		zip.write(text.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	private static void deleteTree(final Path root) {
		if (Files.notExists(root)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException error) {
					throw new UncheckedIOException(error);
				}
			});
		} catch (IOException error) {
			throw new AssertionError("Could not delete " + root, error);
		}
	}
}
