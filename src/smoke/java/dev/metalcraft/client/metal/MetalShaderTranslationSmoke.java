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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.shaders.UniformType;
import dev.metalcraft.api.MetalCraftLightRegistry;
import dev.metalcraft.api.MetalCraftLocalLight;
import net.minecraft.resources.Identifier;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType.Skybox;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import dev.metalcraft.client.shader.ShaderPack;
import dev.metalcraft.client.shader.ShaderGraphCompiler;
import dev.metalcraft.client.shader.ShaderPackLoader;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
	private static final String PHASE_FOUR_MSL = """
		#include <metal_stdlib>
		using namespace metal;

		struct Varyings {
		    float4 position [[position]];
		};

		vertex Varyings phase_four_vertex(uint vertexId [[vertex_id]]) {
		    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    return {float4(corners[vertexId % 3], 0.0, 1.0)};
		}

		struct Targets {
		    float4 scene [[color(0)]];
		    float4 albedo [[color(1)]];
		    float4 normal [[color(2)]];
		    float4 light [[color(3)]];
		};

		fragment Targets phase_four_fill() {
		    Targets out;
		    out.scene = float4(0.10, 0.10, 0.10, 1.0);
		    out.albedo = float4(0.80, 0.60, 0.40, 1.0 / 255.0);
		    // Full sky light, no block light, and 24-bit view depth 0.5 encoded as 0x002000.
		    out.normal = float4(0.50, 0.50, 0.85, 0.0);
		    out.light = float4(0.0, 1.0, 32.0 / 255.0, 0.0);
		    return out;
		}

		fragment float4 phase_four_forward() {
		    return float4(1.0, 0.0, 0.0, 0.5);
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
	private static final String PHASE_ZERO_MSL = """
		#include <metal_stdlib>
		using namespace metal;

		struct PhaseZeroVaryings { float4 position [[position]]; };
		vertex PhaseZeroVaryings phase_zero_vertex(uint vertexId [[vertex_id]]) {
		    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    return {float4(corners[vertexId % 3], 0.0, 1.0)};
		}

		struct PhaseZeroTargets {
		    float4 albedo [[color(0)]];
		    float4 normal [[color(1)]];
		    float4 scene [[color(2)]];
		    float4 marker [[color(3)]];
		};
		fragment PhaseZeroTargets phase_zero_fill() {
		    return {
		        float4(0.5, 0.25, 0.0, 1.0),
		        float4(0.0, 0.5, 0.25, 1.0),
		        float4(0.0, 0.0, 0.0, 1.0),
		        float4(1.0, 0.0, 1.0, 1.0)
		    };
		}
		fragment PhaseZeroTargets phase_zero_resolve(PhaseZeroTargets fetched) {
		    return {fetched.albedo, fetched.normal,
		        float4(fetched.albedo.rgb + fetched.normal.rgb, 1.0), fetched.marker};
		}
		kernel void phase_zero_compute(
		    texture2d<float, access::read> source [[texture(0)]],
		    texture2d<float, access::write> output [[texture(1)]],
		    uint2 pixel [[thread_position_in_grid]]) {
		    if (pixel.x < output.get_width() && pixel.y < output.get_height()) {
		        output.write(source.read(pixel), pixel);
		    }
		}
		""";
	private static final String PHASE_ONE_MSL = """
		#include <metal_stdlib>
		using namespace metal;
		#ifdef MC_PASS_FINAL
		struct FinalVaryings { float4 position [[position]]; };
		vertex FinalVaryings final_vertex(uint vertexId [[vertex_id]]) {
		    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
		    return {float4(corners[vertexId % 3], 0.0, 1.0)};
		}
		fragment float4 final_fragment(
		    FinalVaryings in [[stage_in]],
		    texture2d<float, access::read> scene [[texture(MC_TEX_SCENE)]],
		    constant float &exposure [[buffer(0)]]) {
		    return scene.read(uint2(in.position.xy)) * exposure;
		}
		#endif
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
		assertCelestialLightingMatchesSkyTransform();
		assertLocalLightSnapshots();
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
		assertPersistentPipelineCache();
		assertPhaseZeroExit();
		assertDeclaredShaderGraph();
		assertPhaseOneRuntime();
		assertPhaseTwoGBuffer();
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
			assertSpatialScaling(device);
			assertTexelBufferSampling(device);
			assertMipLevelSampling(device);
			assertBatchedResourceBindings(device);
			assertQueriesAndLifetime(device, pipeline);
			assertPassGpuTiming(device, pipeline);
			assertComputePassGpuTiming(device);
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

	/** Production celestial vectors must match the transform used to draw Minecraft's sky discs. */
	private static void assertCelestialLightingMatchesSkyTransform() {
		for (float angle : new float[]{
			0.0F,
			(float)(Math.PI / 2.0),
			(float)(Math.PI * 3.0 / 2.0),
			(float)Math.PI
		}) {
			Vector3f expected = new Matrix4f()
				.rotateY((float)(-Math.PI / 2.0))
				.rotateX(angle)
				.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
			Vector3f actual = MetalCelestialLighting.directionFromSkyTransform(angle);
			assertDirection(actual, expected, "sky angle " + angle);
		}

		// At sunrise the old (0, cos(angle), sin(angle)) derivation is orthogonal to the disc.
		float sunrise = (float)(Math.PI / 2.0);
		Vector3f sunriseDirection = MetalCelestialLighting.directionFromSkyTransform(sunrise);
		Vector3f omittedSkyRotation = new Vector3f(0.0F, (float)Math.cos(sunrise), (float)Math.sin(sunrise));
		if (Math.abs(sunriseDirection.dot(omittedSkyRotation)) > 1.0E-5F) {
			throw new AssertionError("The sunrise regression fixture no longer distinguishes the omitted sky rotation");
		}

		CameraRenderState camera = new CameraRenderState();
		camera.viewRotationMatrix.rotationY(0.63F).rotateX(-0.27F);
		SkyRenderState sky = overworldSky((float)Math.PI, 0.0F, 0.42F);
		MetalCelestialLighting.State moon = MetalCelestialLighting.derive(sky, camera);
		if (moon.source() != MetalCelestialLighting.Source.MOON || Math.abs(moon.intensity() - 0.42F) > 1.0E-6F) {
			throw new AssertionError("Independent moon selection or weather intensity was lost: " + moon);
		}
		Vector3f expectedView = camera.viewRotationMatrix
			.transformDirection(moon.worldDirection(), new Vector3f())
			.normalize();
		assertDirection(moon.viewDirection(), expectedView, "camera-rotated moon");

		sky.sunAngle = 0.35F;
		sky.moonAngle = 1.2F;
		MetalCelestialLighting.State highest = MetalCelestialLighting.derive(sky, camera);
		if (highest.source() != MetalCelestialLighting.Source.SUN) {
			throw new AssertionError("The highest independent celestial source was not selected: " + highest);
		}

		sky.skybox = Skybox.END;
		if (MetalCelestialLighting.derive(sky, camera).active()) {
			throw new AssertionError("Celestial lighting remained active outside the Overworld skybox");
		}
		sky = overworldSky(0.0F, (float)Math.PI, 0.0F);
		if (MetalCelestialLighting.derive(sky, camera).active()) {
			throw new AssertionError("Fully obscured weather retained active celestial lighting");
		}
	}

	/** The world-lighting seam owns provider isolation, visibility, ranking, and immutable publication. */
	private static void assertLocalLightSnapshots() {
		MetalCraftLightRegistry registry = new MetalCraftLightRegistry();
		float[] intensityOffset = {0.0F};
		registry.register(Identifier.parse("metalcraft:failing"), output -> {
			output.accept(localLight(900L, 1.0F));
			throw new IllegalStateException("fixture failure");
		});
		registry.register(Identifier.parse("metalcraft:duplicates"), output -> {
			output.accept(localLight(901L, 1.0F));
			output.accept(localLight(901L, 2.0F));
		});
		registry.register(Identifier.parse("metalcraft:ranked"), output -> {
			for (int index = 0; index < 300; index++) {
				output.accept(localLight(index, index + 1.0F + intensityOffset[0]));
			}
		});
		try {
			registry.register(Identifier.parse("metalcraft:ranked"), output -> {
			});
			throw new AssertionError("Duplicate light-provider registration was accepted");
		} catch (IllegalArgumentException expected) {
			// The registration seam owns provider IDs globally.
		}

		CameraRenderState camera = new CameraRenderState();
		camera.projectionMatrix.setPerspective((float)Math.toRadians(70.0), 16.0F / 9.0F, 0.05F, 512.0F, true);
		camera.viewRotationMatrix.identity();
		camera.pos = new Vec3(20_000_000.25, 70.0, 20_000_000.25);
		MetalWorldLighting lighting = new MetalWorldLighting(registry);
		MetalWorldLighting.Snapshot snapshot = lighting.publish(camera);
		if (snapshot.lights().size() != MetalWorldLighting.MAX_LIGHTS || snapshot.overflowCount() != 44) {
			throw new AssertionError("The deterministic local-light cap was not enforced: " + snapshot);
		}
		if (snapshot.lights().getFirst().id().stableId() != 299L
			|| snapshot.lights().getLast().id().stableId() != 44L) {
			throw new AssertionError("Local lights were not retained strongest-first");
		}
		MetalWorldLighting.FrameLight relative = snapshot.lights().getFirst();
		if (Math.abs(relative.cameraX() - 1.5F) > 1.0E-5F
			|| Math.abs(relative.cameraY() - 2.0F) > 1.0E-5F
			|| Math.abs(relative.cameraZ() + 10.0F) > 1.0E-5F) {
			throw new AssertionError("Large-world camera-relative conversion lost precision: " + relative);
		}
		if (lighting.snapshot() != snapshot) {
			throw new AssertionError("The published local-light snapshot was not made current atomically");
		}
		try {
			snapshot.lights().add(relative);
			throw new AssertionError("The published local-light snapshot remained mutable");
		} catch (UnsupportedOperationException expected) {
			// Render consumers may safely retain this frame value.
		}
		if (relative.usesBlockLightEnvelope()) {
			throw new AssertionError("A registered visual light unexpectedly uses vanilla's block-light envelope");
		}
		MetalWorldLighting.TileGrid tiles = snapshot.tiles();
		int centerX = tiles.tilesX() / 2;
		int centerY = tiles.tilesY() / 2;
		if (tiles.count(centerX, centerY) != MetalWorldLighting.MAX_LIGHTS_PER_TILE
			|| tiles.overflowCount() == 0 || tiles.lightIndex(centerX, centerY, 0) != 0
			|| tiles.lightIndex(centerX, centerY, MetalWorldLighting.MAX_LIGHTS_PER_TILE - 1) != 63) {
			throw new AssertionError("The strongest-first 16x16 tile cap was not enforced");
		}
		Map<MetalWorldLighting.LightId, Integer> firstSlots = new LinkedHashMap<>();
		for (MetalWorldLighting.FrameLight light : snapshot.lights()) {
			if (light.shadowSlot() >= 0) firstSlots.put(light.id(), light.shadowSlot());
		}
		if (firstSlots.size() != MetalWorldLighting.MAX_SHADOW_LIGHTS) {
			throw new AssertionError("Four stable local-shadow slots were not assigned: " + firstSlots);
		}
		if (snapshot.lights().stream().noneMatch(light -> light.shadowEligible() && light.shadowSlot() < 0)) {
			throw new AssertionError("The fifth eligible light was not retained as an unshadowed contribution");
		}
		intensityOffset[0] = 100.0F;
		MetalWorldLighting.Snapshot nextFrame = lighting.publish(camera);
		if (nextFrame == snapshot || nextFrame.lights().getFirst().intensity() != 400.0F
			|| snapshot.lights().getFirst().intensity() != 300.0F || lighting.snapshot() != nextFrame) {
			throw new AssertionError("Dynamic publication mutated or failed to replace the prior frame snapshot");
		}
		for (MetalWorldLighting.FrameLight light : nextFrame.lights()) {
			Integer slot = firstSlots.get(light.id());
			if (slot != null && light.shadowSlot() != slot) {
				throw new AssertionError("A retained stable light changed shadow slots across frames");
			}
		}
		MetalWorldLighting.Snapshot shadowsOff = lighting.publish(camera, null, 16, 16, 0);
		if (shadowsOff.lights().stream().anyMatch(light -> light.shadowSlot() >= 0)) {
			throw new AssertionError("The zero local-shadow option retained an active slot");
		}

		MetalWorldLighting staticCache = new MetalWorldLighting(new MetalCraftLightRegistry());
		BlockPos torch = new BlockPos(31, 70, -17);
		staticCache.blockChangedForTesting(torch, true);
		if (staticCache.staticEmitterCountForTesting() != 1) {
			throw new AssertionError("A vanilla emitting block did not enter the incremental cache");
		}
		staticCache.blockChangedForTesting(torch, false);
		if (staticCache.staticEmitterCountForTesting() != 0) {
			throw new AssertionError("A removed emitting block remained in the incremental cache");
		}
		staticCache.blockChangedForTesting(torch, true);
		staticCache.chunkUnloadedForTesting(ChunkPos.containing(torch));
		if (staticCache.staticEmitterCountForTesting() != 0) {
			throw new AssertionError("Chunk unload retained vanilla static emitters");
		}

		MetalCraftLightRegistry ties = new MetalCraftLightRegistry();
		ties.register(Identifier.parse("metalcraft:z_provider"), output -> output.accept(localLight(4L, 1.0F)));
		ties.register(Identifier.parse("metalcraft:a_provider"), output -> output.accept(localLight(7L, 1.0F)));
		MetalWorldLighting.Snapshot tied = new MetalWorldLighting(ties).publish(camera);
		if (!tied.lights().getFirst().id().providerId().equals(Identifier.parse("metalcraft:a_provider"))) {
			throw new AssertionError("Equal-impact lights depended on provider registration order: " + tied);
		}

		try {
			new MetalCraftLocalLight(1L, Vec3.ZERO, 1.0F, 1.0F, 1.0F, Float.NaN, 8.0F, false);
			throw new AssertionError("An invalid local-light intensity was accepted");
		} catch (IllegalArgumentException expected) {
			// Provider construction errors are caught and isolated by the registry during collection.
		}

		assertLocalLightsStayWorldBound();
		assertViewReconstructionTracksWorldSurfaces();
	}

	/**
	 * A world-fixed light must recover its world position from the uploaded view-space value after
	 * camera translation and Minecraft's real view rotation. If the conversion dropped that
	 * rotation, lighting would sit in camera-relative world space while geometry is in view space,
	 * which makes the contribution follow the player.
	 */
	private static void assertLocalLightsStayWorldBound() {
		Vec3 worldLight = new Vec3(100.5, 72.0, 210.25);
		MetalCraftLightRegistry registry = new MetalCraftLightRegistry();
		registry.register(Identifier.parse("metalcraft:world_bound"), output -> output.accept(
			new MetalCraftLocalLight(1L, worldLight, 1.0F, 0.6F, 0.2F, 4.0F, 16.0F, false)
		));
		MetalWorldLighting lighting = new MetalWorldLighting(registry);
		Matrix4f view = minecraftViewRotation(0.0F, 12.0F);
		Vec3 firstCamera = new Vec3(100.0, 70.0, 200.0);
		MetalWorldLighting.FrameLight first = publishedLight(lighting, firstCamera, view);
		Vec3 recoveredFirst = recoverWorldPosition(first, firstCamera, view);
		if (recoveredFirst.distanceTo(worldLight) > 1.0E-4) {
			throw new AssertionError("View-space local-light conversion did not recover the world position: "
				+ recoveredFirst + " from " + worldLight);
		}
		Vec3 secondCamera = new Vec3(108.0, 71.5, 205.0);
		MetalWorldLighting.FrameLight second = publishedLight(lighting, secondCamera, view);
		Vec3 recoveredSecond = recoverWorldPosition(second, secondCamera, view);
		if (recoveredSecond.distanceTo(worldLight) > 1.0E-4) {
			throw new AssertionError("A camera translation moved a world-fixed local light: "
				+ recoveredSecond + " from " + worldLight);
		}
		if (Math.abs(first.viewX() - second.viewX()) < 1.0E-3F
			&& Math.abs(first.viewY() - second.viewY()) < 1.0E-3F
			&& Math.abs(first.viewZ() - second.viewZ()) < 1.0E-3F) {
			throw new AssertionError("A world-fixed local light kept a camera-attached view position: " + second);
		}
	}

	private static MetalWorldLighting.FrameLight publishedLight(
		final MetalWorldLighting lighting, final Vec3 cameraPos, final Matrix4f viewRotation
	) {
		CameraRenderState camera = new CameraRenderState();
		camera.projectionMatrix.setPerspective((float)Math.toRadians(70.0), 16.0F / 9.0F, 0.05F, 512.0F, true);
		camera.viewRotationMatrix.set(viewRotation);
		camera.pos = cameraPos;
		MetalWorldLighting.Snapshot snapshot = lighting.publish(camera);
		if (snapshot.lights().size() != 1) {
			throw new AssertionError("Expected one world-fixed light, got " + snapshot.lights());
		}
		return snapshot.lights().getFirst();
	}

	private static Vec3 recoverWorldPosition(
		final MetalWorldLighting.FrameLight light, final Vec3 cameraPos, final Matrix4f viewRotation
	) {
		Vector3f relative = viewRotation.invert(new Matrix4f()).transformPosition(
			new Vector3f(light.viewX(), light.viewY(), light.viewZ())
		);
		return cameraPos.add(relative.x, relative.y, relative.z);
	}

	/** Minecraft's view matrix: conjugate of {@code rotationYXZ(PI - yaw, -pitch, 0)}. */
	private static Matrix4f minecraftViewRotation(final float yawDegrees, final float pitchDegrees) {
		float yaw = (float)Math.toRadians(yawDegrees);
		float pitch = (float)Math.toRadians(pitchDegrees);
		org.joml.Quaternionf rotation = new org.joml.Quaternionf().rotationYXZ(
			(float)Math.PI - yaw, -pitch, 0.0F
		);
		return new Matrix4f().rotation(rotation.conjugate());
	}

	/**
	 * Reconstruction must invert the projection Minecraft rasterized, including reversed-Z and
	 * view-bob. Tan-half-FOV from the un-bobbed camera matrix leaves lighting on camera rays.
	 */
	private static void assertViewReconstructionTracksWorldSurfaces() {
		Vector3f viewPos = new Vector3f(1.25F, -0.40F, -8.0F);
		Matrix4f pinhole = new Matrix4f().setPerspective(
			(float)Math.toRadians(70.0), 16.0F / 9.0F, 0.05F, 512.0F, true
		);
		float tanHalfX = 1.0F / Math.abs(pinhole.m00());
		float tanHalfY = 1.0F / Math.abs(pinhole.m11());
		Vector3f pinholeExpected = new Vector3f(tanHalfX * 10.0F, tanHalfY * 10.0F, -10.0F);
		Vector3f pinholeActual = MetalWorldShadow.viewFromNdc(pinhole, 1.0F, 1.0F, -10.0F);
		if (pinholeActual.distance(pinholeExpected) > 1.0E-4F) {
			throw new AssertionError("Pinhole unprojection diverged from tan-half-FOV corners: "
				+ pinholeExpected + " vs " + pinholeActual);
		}

		Matrix4f cameraProjection = new Matrix4f().setPerspective(
			(float)Math.toRadians(70.0), 16.0F / 9.0F, 512.0F, 0.05F, true
		);
		assertReconstructedView("reversed-Z camera projection", viewPos, cameraProjection);

		Matrix4f gpuProjection = new Matrix4f(cameraProjection);
		gpuProjection.translate(0.12F, -0.18F, 0.0F);
		gpuProjection.rotateZ((float)Math.toRadians(3.0));
		gpuProjection.rotateX((float)Math.toRadians(5.0));
		assertReconstructedView("walk-bob GPU projection", viewPos, gpuProjection);
	}

	private static void assertReconstructedView(
		final String label, final Vector3f viewPos, final Matrix4f rasterProjection
	) {
		Vector4f clip = rasterProjection.transform(new Vector4f(viewPos.x, viewPos.y, viewPos.z, 1.0F), new Vector4f());
		Vector3f reconstructed = MetalWorldShadow.viewFromNdc(
			rasterProjection, clip.x / clip.w, clip.y / clip.w, viewPos.z
		);
		if (reconstructed.distance(viewPos) > 1.0E-3F) {
			throw new AssertionError("View reconstruction from " + label
				+ " did not recover a world-surface view position: true=" + viewPos
				+ " reconstructed=" + reconstructed);
		}
	}

	private static MetalCraftLocalLight localLight(final long stableId, final float intensity) {
		return new MetalCraftLocalLight(
			stableId,
			new Vec3(20_000_001.75, 72.0, 19_999_990.25),
			1.0F, 0.6F, 0.2F,
			intensity, 16.0F, stableId % 2L == 0L
		);
	}

	private static SkyRenderState overworldSky(
		final float sunAngle, final float moonAngle, final float rainBrightness
	) {
		SkyRenderState sky = new SkyRenderState();
		sky.skybox = Skybox.OVERWORLD;
		sky.sunAngle = sunAngle;
		sky.moonAngle = moonAngle;
		sky.rainBrightness = rainBrightness;
		return sky;
	}

	private static void assertDirection(final Vector3f actual, final Vector3f expected, final String label) {
		if (actual.distance(expected) > 1.0E-5F) {
			throw new AssertionError(label + " direction differs: expected=" + expected + ", actual=" + actual);
		}
	}

	/**
	 * Phase 2's exit: every world program the pack substitutes compiles on the device, a blended
	 * draw is refused, and one terrain draw fills all four attachments with the values the format
	 * says it should.
	 *
	 * <p>Driven through the real substitution rather than a hand-built pipeline, so what is compiled
	 * here is exactly what a frame would compile: the same stand-in, built from the same bind-group
	 * layout, with the same slot and material defines. The draw then reads back every channel,
	 * because a G-buffer that renders without erroring and packs the wrong bits into the wrong
	 * channel is the failure this phase is most likely to have.
	 */
	private static void assertPhaseTwoGBuffer() {
		Path root = Path.of("build", "phase-two-shaderpacks");
		Path settings = Path.of("build", "phase-two-shader-settings.json");
		deleteTree(root);
		try {
			Files.deleteIfExists(settings);
			Files.createDirectories(root);
		} catch (IOException error) {
			throw new AssertionError("Could not create the Phase 2 shader-pack fixture", error);
		}

		MetalDevice device = MetalNative.openDefaultDevice().orElseThrow();
		MetalGpuDevice gpuDevice = new MetalGpuDevice(device, (id, type) -> null);
		try {
			try (MetalShaderEngine engine = new MetalShaderEngine(device, root, settings)) {
				engine.attachDevice(gpuDevice);
				engine.resize(GBUFFER_SIZE, GBUFFER_SIZE);
				engine.setOption("shadow_resolution", 1024);
				MetalWorldGeometry world = MetalWorldGeometry.active();
				if (world == null) {
					throw new AssertionError("The built-in pack declares no geometry pass");
				}
				if (world.channels().size() != 3) {
					throw new AssertionError("The built-in G-buffer has " + world.channels().size() + " channels, not three");
				}

				assertStandInCompiles(gpuDevice, world, terrainPipeline(null), "solid terrain");
				assertStandInCompiles(gpuDevice, world, terrainPipeline("0.1"), "cutout terrain");
				assertStandInCompiles(gpuDevice, world, blockPipeline(), "loose block models");
				assertStandInCompiles(gpuDevice, world, entityPipeline(true), "entities");
				assertStandInCompiles(gpuDevice, world, entityPipeline(false), "emissive entities");
				assertPhaseThreeShadowPipelines(device, gpuDevice, engine);

				// A blended draw is refused on its own terms rather than by name, which is what keeps
				// translucent terrain, translucent entities and the glint layer out of the G-buffer.
				if (world.standInFor(translucentTerrainPipeline()) != null) {
					throw new AssertionError("A blended pipeline was routed into the G-buffer");
				}
				// And so is a variant switch this pack's programs do not implement, because routing
				// it would render the same geometry a quietly different way.
				if (world.standInFor(dissolveEntityPipeline()) != null) {
					throw new AssertionError("A pipeline with an unimplemented shader define was routed into the G-buffer");
				}

				assertWorldProgramChannels(device, gpuDevice, world);
				assertPhaseFourDeferredResolve(device, engine);
				assertPhaseFiveEffects(device, engine);
			}
		} finally {
			gpuDevice.close();
		}
	}

	/** The built-in graph dispatches every Phase 5 effect and its toggles remove observable work. */
	private static void assertPhaseFiveEffects(final MetalDevice device, final MetalShaderEngine engine) {
		int size = GBUFFER_SIZE;
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture scene = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.RGBA8_UNORM, size, size, 1, MetalTexture.USAGE_ALL));
			 MetalTexture depth = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.DEPTH32_FLOAT, size, size, 1, MetalTexture.USAGE_ALL));
			 MetalTextureView depthView = depth.createView();
			 MetalTexture output = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.BGRA8_UNORM, size, size, 1, MetalTexture.USAGE_ALL))) {
			ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
			for (int index = 0; index < size * size; index++) {
				pixels.put((byte)0xE0).put((byte)0xC0).put((byte)0x80).put((byte)0xFF);
			}
			pixels.flip();
			scene.upload(queue, 0, pixels);
			try (MetalCommandBuffer clear = queue.createCommandBuffer();
				 MetalRenderPass ignored = clear.beginRenderPass(MetalRenderPass.Descriptor.depthOnly(
					 new MetalRenderPass.DepthAttachment(
						 depth, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0.6)))) {
				ignored.close();
				clear.commitAndWait();
			}
			engine.supplyWorldDepthForTesting(depthView);
			int enabled = encodeEffects(queue, engine, scene, output);
			engine.setOption("ssao", false);
			engine.setOption("bloom", false);
			engine.setOption("volumetrics", false);
			engine.supplyWorldDepthForTesting(depthView);
			int disabled = encodeEffects(queue, engine, scene, output);
			if (enabled == 0 || disabled == 0 || enabled == disabled) {
				throw new AssertionError("Phase 5 effects did not produce a distinct non-empty result: enabled="
					+ Integer.toHexString(enabled) + ", disabled=" + Integer.toHexString(disabled));
			}
			assertPhaseFiveOrientation(queue, engine, scene, output, depthView, size);
		} finally {
			engine.supplyWorldDepthForTesting(null);
			engine.setOption("ssao", true);
			engine.setOption("bloom", true);
			engine.setOption("volumetrics", true);
		}
	}

	/** The post chain must perform the scene-to-drawable row conversion exactly once. */
	private static void assertPhaseFiveOrientation(
		final MetalCommandQueue queue,
		final MetalShaderEngine engine,
		final MetalTexture scene,
		final MetalTexture output,
		final MetalTextureView depth,
		final int size
	) {
		ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4);
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				boolean top = y < size / 2;
				pixels.put((byte)(top ? 0xFF : 0x00));
				pixels.put((byte)0x00);
				pixels.put((byte)(top ? 0x00 : 0xFF));
				pixels.put((byte)0xFF);
			}
		}
		scene.upload(queue, 0, pixels.flip());
		engine.supplyWorldDepthForTesting(depth);
		try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
			if (!engine.encodeForTesting(commands, scene, output)) {
				throw new AssertionError("The Phase 5 graph declined the orientation fixture");
			}
			commands.commitAndWait();
		}
		ByteBuffer result = output.readback(queue, 0);
		int topBlue = Byte.toUnsignedInt(result.get((size / 4 * size + size / 2) * 4));
		int topRed = Byte.toUnsignedInt(result.get((size / 4 * size + size / 2) * 4 + 2));
		int bottomBlue = Byte.toUnsignedInt(result.get((size * 3 / 4 * size + size / 2) * 4));
		int bottomRed = Byte.toUnsignedInt(result.get((size * 3 / 4 * size + size / 2) * 4 + 2));
		if (topBlue < 180 || topRed > 30 || bottomRed < 180 || bottomBlue > 30) {
			throw new AssertionError("Phase 5 inverted the drawable row conversion: top BGRA="
				+ topBlue + ",0," + topRed + ", bottom BGRA=" + bottomBlue + ",0," + bottomRed);
		}
	}

	private static int encodeEffects(
		final MetalCommandQueue queue,
		final MetalShaderEngine engine,
		final MetalTexture scene,
		final MetalTexture output
	) {
		try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
			if (!engine.encodeForTesting(commands, scene, output)) {
				throw new AssertionError("The Phase 5 graph declined a supplied world scene");
			}
			commands.commitAndWait();
		}
		ByteBuffer result = output.readback(queue, 0);
		return Byte.toUnsignedInt(result.get(0))
			| Byte.toUnsignedInt(result.get(1)) << 8
			| Byte.toUnsignedInt(result.get(2)) << 16;
	}

	/** Phase 3's pack programs compile as depth-only layered pipelines for every routed world format. */
	private static void assertPhaseThreeShadowPipelines(
		final MetalDevice device,
		final MetalGpuDevice gpuDevice,
		final MetalShaderEngine engine
	) {
		MetalWorldShadow shadow = engine.shadowForTesting();
		if (shadow == null) {
			throw new AssertionError("The built-in pack declares no shadow pass");
		}
		MetalTexture target = engine.targetTextureForTesting("shadow");
		if (target == null || target.descriptor().format() != MetalTexture.Format.DEPTH32_FLOAT
			|| target.descriptor().sliceCount() != 4 || target.descriptor().width() != 1024) {
			throw new AssertionError("The shadow target is not a configurable four-layer depth array");
		}
		shadow.useIdentityCascadesForTesting();
		shadow.useIdentityLocalShadowsForTesting(4);
		MetalWorldLighting lighting = engine.lightingForTesting();
		lighting.publishForTesting(List.of(), new Matrix4f(), GBUFFER_SIZE, GBUFFER_SIZE, 0);
		GpuBufferSlice firstCelestial = shadow.matricesSliceForTesting();
		GpuBufferSlice firstLocal = shadow.localMatricesSliceForTesting();
		MetalBuffer firstLights = lighting.lightBuffer();
		long firstLightsOffset = lighting.lightBufferOffset();
		MetalBuffer firstTiles = lighting.tileBuffer();
		long firstTilesOffset = lighting.tileBufferOffset();
		((MetalCommandEncoder)gpuDevice.createCommandEncoder()).submit();
		shadow.useIdentityCascadesForTesting();
		shadow.useIdentityLocalShadowsForTesting(4);
		lighting.publishForTesting(List.of(), new Matrix4f(), GBUFFER_SIZE, GBUFFER_SIZE, 0);
		if (firstCelestial.equals(shadow.matricesSliceForTesting())
			|| firstLocal.equals(shadow.localMatricesSliceForTesting())
			|| firstLights == lighting.lightBuffer() && firstLightsOffset == lighting.lightBufferOffset()
			|| firstTiles == lighting.tileBuffer() && firstTilesOffset == lighting.tileBufferOffset()) {
			throw new AssertionError("Lighting data reused one shared GPU slice across frame submissions");
		}
		for (Map.Entry<RenderPipeline, String> fixture : Map.of(
			terrainPipeline(null), "solid terrain",
			terrainPipeline("0.1"), "cutout terrain",
			blockPipeline(), "loose block models",
			entityPipeline(true), "entities",
			entityPipeline(false), "emissive entities"
		).entrySet()) {
			RenderPipeline standIn = shadow.standInForTesting(fixture.getKey());
			if (standIn == null) {
				throw new AssertionError("The shadow pass declined " + fixture.getValue());
			}
			if (standIn.getColorTargetStates().length != 0 || standIn.getDepthStencilState() == null
				|| standIn.getDepthStencilState().depthBiasScaleFactor() <= 0.0F) {
				throw new AssertionError("The shadow stand-in for " + fixture.getValue()
					+ " is not depth-only with slope bias (colors=" + standIn.getColorTargetStates().length
					+ ", depth=" + standIn.getDepthStencilState() + ")");
			}
			if (!gpuDevice.getOrCompilePipeline(standIn).isValid()) {
				throw new AssertionError("The shadow program for " + fixture.getValue() + " did not compile on this device");
			}
			RenderPipeline localStandIn = shadow.localStandInForTesting(fixture.getKey());
			if (localStandIn == null || !gpuDevice.getOrCompilePipeline(localStandIn).isValid()) {
				throw new AssertionError("The local-shadow program for " + fixture.getValue() + " did not compile on this device");
			}
		}
		if (shadow.standInForTesting(translucentTerrainPipeline()) != null) {
			throw new AssertionError("A blended surface was routed into the shadow pass");
		}
		assertLayeredShadowDraw(device, gpuDevice, engine, shadow);
		assertLayeredLocalShadowDraw(device, gpuDevice, shadow);
		assertStableShadowCascades(shadow);
	}

	/** A sub-texel camera translation keeps a fixed world point on the same shadow texel. */
	private static void assertStableShadowCascades(final MetalWorldShadow shadow) {
		CameraRenderState camera = new CameraRenderState();
		camera.projectionMatrix.setPerspective((float)Math.toRadians(70.0), 16.0F / 9.0F, 0.05F, 512.0F, true);
		camera.viewRotationMatrix.identity();
		camera.pos = new Vec3(100.25, 70.0, 200.25);
		Vector3f worldPoint = new Vector3f(100.0F, 69.0F, 190.0F);
		SkyRenderState sky = overworldSky(0.75F, 0.75F + (float)Math.PI, 1.0F);
		shadow.prepareForTesting(camera, sky);
		Vector3f before = shadow.projectWorldPointForTesting(0, worldPoint);
		camera.pos = new Vec3(100.251, 70.0, 200.251);
		shadow.prepareForTesting(camera, sky);
		Vector3f after = shadow.projectWorldPointForTesting(0, worldPoint);
		if (Math.abs(before.x - after.x) > 1.0E-5F || Math.abs(before.y - after.y) > 1.0E-5F) {
			throw new AssertionError("A sub-texel camera move shifted the first shadow cascade: "
				+ before + " -> " + after);
		}

		// World geometry reaches the shader camera-relative even near Minecraft's world border. The
		// cascade construction must retain the camera's double-precision translation long enough to
		// keep that same geometry anchored to its shadow texels there too.
		double worldX = 20_000_000.0;
		double cameraX = worldX + 0.25;
		camera.pos = new Vec3(cameraX, 70.0, worldX + 0.25);
		shadow.prepareForTesting(camera, sky);
		before = shadow.projectCameraRelativePointForTesting(0, new Vector3f(
			(float)(worldX - cameraX), -1.0F, (float)(worldX - 10.0 - camera.pos.z)
		));
		cameraX += 0.001;
		camera.pos = new Vec3(cameraX, 70.0, worldX + 0.251);
		shadow.prepareForTesting(camera, sky);
		after = shadow.projectCameraRelativePointForTesting(0, new Vector3f(
			(float)(worldX - cameraX), -1.0F, (float)(worldX - 10.0 - camera.pos.z)
		));
		if (Math.abs(before.x - after.x) > 1.0E-5F || Math.abs(before.y - after.y) > 1.0E-5F) {
			throw new AssertionError("A sub-texel camera move shifted a large-world shadow cascade: "
				+ before + " -> " + after);
		}

		// The identity-camera case above cannot catch a missing inverse-view in the cascade matrix.
		// Minecraft's yaw-0 view is a 180° Y rotation; geometry is multiplied by that matrix, so the
		// cascade must be too, or a stationary world point crawls as the player translates.
		camera.viewRotationMatrix.set(minecraftViewRotation(0.0F, 12.0F));
		camera.pos = new Vec3(100.25, 70.0, 200.25);
		shadow.prepareForTesting(camera, sky);
		before = shadow.projectWorldPointForTesting(0, worldPoint);
		camera.pos = new Vec3(100.251, 70.0, 200.251);
		shadow.prepareForTesting(camera, sky);
		after = shadow.projectWorldPointForTesting(0, worldPoint);
		if (Math.abs(before.x - after.x) > 1.0E-5F || Math.abs(before.y - after.y) > 1.0E-5F) {
			throw new AssertionError("A sub-texel move with Minecraft's view rotation shifted the cascade: "
				+ before + " -> " + after);
		}

		// Occlusion between two world-fixed points is a light-space offset. Camera translation must
		// not change that offset, or tree shadows slide across the ground with the player.
		Vector3f occluder = new Vector3f(102.0F, 74.0F, 190.0F);
		Vector3f receiver = new Vector3f(108.0F, 64.0F, 196.0F);
		camera.pos = new Vec3(100.0, 70.0, 200.0);
		shadow.prepareForTesting(camera, sky);
		Vector3f firstDelta = shadow.projectWorldPointForTesting(0, receiver)
			.sub(shadow.projectWorldPointForTesting(0, occluder));
		camera.pos = new Vec3(115.0, 73.0, 188.0);
		shadow.prepareForTesting(camera, sky);
		Vector3f secondDelta = shadow.projectWorldPointForTesting(0, receiver)
			.sub(shadow.projectWorldPointForTesting(0, occluder));
		if (Math.abs(firstDelta.x - secondDelta.x) > 1.0E-4F
			|| Math.abs(firstDelta.y - secondDelta.y) > 1.0E-4F) {
			throw new AssertionError("Camera translation changed a world-fixed shadow offset: "
				+ firstDelta + " -> " + secondDelta);
		}

		Matrix4f bobbed = new Matrix4f(camera.projectionMatrix)
			.translate(0.12F, -0.18F, 0.0F)
			.rotateZ((float)Math.toRadians(3.0))
			.rotateX((float)Math.toRadians(5.0));
		MetalWorldShadow.captureRasterProjection(bobbed);
		try {
			camera.pos = new Vec3(100.0, 70.0, 200.0);
			shadow.prepareForTesting(camera, sky);
			firstDelta = shadow.projectWorldPointForTesting(0, receiver)
				.sub(shadow.projectWorldPointForTesting(0, occluder));
			camera.pos = new Vec3(115.0, 73.0, 188.0);
			shadow.prepareForTesting(camera, sky);
			secondDelta = shadow.projectWorldPointForTesting(0, receiver)
				.sub(shadow.projectWorldPointForTesting(0, occluder));
			if (Math.abs(firstDelta.x - secondDelta.x) > 1.0E-4F
				|| Math.abs(firstDelta.y - secondDelta.y) > 1.0E-4F) {
				throw new AssertionError("Walk-bob raster projection unbound a world-fixed shadow offset: "
					+ firstDelta + " -> " + secondDelta);
			}

			camera.pos = new Vec3(100.25, 70.0, 200.25);
			MetalWorldShadow.captureRasterProjection(new Matrix4f(camera.projectionMatrix));
			shadow.prepareForTesting(camera, sky);
			Vector3f unbobbedUv = shadow.projectWorldPointForTesting(0, worldPoint);
			MetalWorldShadow.captureRasterProjection(bobbed);
			shadow.prepareForTesting(camera, sky);
			Vector3f bobbedUv = shadow.projectWorldPointForTesting(0, worldPoint);
			if (Math.abs(unbobbedUv.x - bobbedUv.x) > 1.0E-5F
				|| Math.abs(unbobbedUv.y - bobbedUv.y) > 1.0E-5F) {
				throw new AssertionError("View-bob crawled a stationary shadow texel: "
					+ unbobbedUv + " -> " + bobbedUv);
			}
		} finally {
			MetalWorldShadow.clearRasterProjectionForTesting();
		}
	}

	/** Draws one alpha-tested terrain triangle into all four layers with the real shadow program. */
	private static void assertLayeredShadowDraw(
		final MetalDevice device,
		final MetalGpuDevice gpuDevice,
		final MetalShaderEngine engine,
		final MetalWorldShadow shadow
	) {
		RenderPipeline standIn = shadow.standInForTesting(terrainPipeline("0.1"));
		if (standIn == null) throw new AssertionError("The cutout shadow fixture was declined");
		MetalTexture target = engine.targetTextureForTesting("shadow");
		if (target == null) throw new AssertionError("The shadow target disappeared before its draw");
		MetalRenderPipeline pipeline = gpuDevice.getOrCompilePipeline(standIn).metal(true);
		Map<String, Integer> slots = resourceSlots(standIn);
		shadow.useIdentityCascadesForTesting();
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture atlas = whiteTexture(device, queue);
			 MetalTextureView atlasView = atlas.createView();
			 MetalSampler sampler = device.createSampler(new MetalSampler.Descriptor(
				 MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 MetalBuffer vertices = uploadBuffer(device, shadowTerrainTriangle());
			 MetalBuffer globals = uploadBuffer(device, globalsBlock());
			 MetalBuffer section = uploadBuffer(device, chunkSectionBlock());
			 MetalCommandBuffer commands = queue.createCommandBuffer()) {
			try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				List.of(),
				new MetalRenderPass.DepthAttachment(
					target, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 1.0
				),
				4
			))) {
				pass.setPipeline(pipeline);
				pass.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, vertices, 0L);
				pass.setUniformBuffer(slots.get("Globals"), globals, 0L, MetalRenderPass.STAGE_ALL);
				pass.setUniformBuffer(slots.get("ChunkSection"), section, 0L, MetalRenderPass.STAGE_ALL);
				pass.setUniformBuffer(
					slots.get("MetalCraftShadow"), shadow.matricesForTesting(),
					shadow.matricesOffsetForTesting(), MetalRenderPass.STAGE_ALL
				);
				pass.setTexture(slots.get("Sampler0"), atlasView, MetalRenderPass.STAGE_ALL);
				pass.setSampler(slots.get("Sampler0"), sampler, MetalRenderPass.STAGE_ALL);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 4, 0);
			}
			commands.commitAndWait();
			for (int layer = 0; layer < 4; layer++) {
				ByteBuffer depth = target.readback(queue, 0, layer).order(ByteOrder.nativeOrder());
				boolean written = false;
				while (depth.remaining() >= Float.BYTES) {
					float value = depth.getFloat();
					if (value < 0.99F) {
						written = true;
						break;
					}
				}
				if (!written) {
					throw new AssertionError("The layered shadow draw did not reach cascade " + layer);
				}
			}
		}
	}

	/** Draws one alpha-tested triangle through every face of all four local shadow slots. */
	private static void assertLayeredLocalShadowDraw(
		final MetalDevice device,
		final MetalGpuDevice gpuDevice,
		final MetalWorldShadow shadow
	) {
		RenderPipeline standIn = shadow.localStandInForTesting(terrainPipeline("0.1"));
		if (standIn == null) throw new AssertionError("The cutout local-shadow fixture was declined");
		MetalTexture target = shadow.localShadowViewForTesting().texture();
		if (target.descriptor().format() != MetalTexture.Format.DEPTH32_FLOAT
			|| target.descriptor().sliceCount() != 24 || target.descriptor().width() != 256) {
			throw new AssertionError("The local-shadow target is not a configurable 24-layer depth array");
		}
		MetalRenderPipeline pipeline = gpuDevice.getOrCompilePipeline(standIn).metal(true);
		Map<String, Integer> slots = resourceSlots(standIn);
		shadow.useIdentityLocalShadowsForTesting(4);
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture atlas = whiteTexture(device, queue);
			 MetalTextureView atlasView = atlas.createView();
			 MetalSampler sampler = device.createSampler(new MetalSampler.Descriptor(
				 MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 MetalBuffer vertices = uploadBuffer(device, shadowTerrainTriangle());
			 MetalBuffer globals = uploadBuffer(device, globalsBlock());
			 MetalBuffer section = uploadBuffer(device, chunkSectionBlock());
			 MetalCommandBuffer commands = queue.createCommandBuffer()) {
			try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				List.of(),
				new MetalRenderPass.DepthAttachment(
					target, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 1.0
				),
				24
			))) {
				pass.setPipeline(pipeline);
				pass.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, vertices, 0L);
				pass.setUniformBuffer(slots.get("Globals"), globals, 0L, MetalRenderPass.STAGE_ALL);
				pass.setUniformBuffer(slots.get("ChunkSection"), section, 0L, MetalRenderPass.STAGE_ALL);
				pass.setUniformBuffer(
					slots.get("MetalCraftLocalShadow"), shadow.localMatricesForTesting(),
					shadow.localMatricesOffsetForTesting(), MetalRenderPass.STAGE_ALL
				);
				pass.setTexture(slots.get("Sampler0"), atlasView, MetalRenderPass.STAGE_ALL);
				pass.setSampler(slots.get("Sampler0"), sampler, MetalRenderPass.STAGE_ALL);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 24, 0);
			}
			commands.commitAndWait();
			for (int layer = 0; layer < 24; layer++) {
				ByteBuffer depth = target.readback(queue, 0, layer).order(ByteOrder.nativeOrder());
				boolean written = false;
				while (depth.remaining() >= Float.BYTES) {
					if (depth.getFloat() < 0.99F) { written = true; break; }
				}
				if (!written) throw new AssertionError("The local-shadow draw did not reach layer " + layer);
			}
		}
	}

	private static void assertStandInCompiles(
		final MetalGpuDevice gpuDevice,
		final MetalWorldGeometry world,
		final RenderPipeline vanilla,
		final String description
	) {
		RenderPipeline standIn = world.standInFor(vanilla);
		if (standIn == null) {
			throw new AssertionError("The G-buffer pack declined to stand in for " + description);
		}
		if (standIn.getColorTargetStates().length != 4) {
			throw new AssertionError(
				"The stand-in for " + description + " declares " + standIn.getColorTargetStates().length
					+ " colour targets, not Minecraft's plus three G-buffer channels"
			);
		}
		if (!gpuDevice.getOrCompilePipeline(standIn).isValid()) {
			throw new AssertionError("The pack's G-buffer program for " + description + " did not compile on this device");
		}
	}

	/**
	 * Draws every world program the pack substitutes, and reads all four attachments back.
	 *
	 * <p>One tilted quad each, through the real stand-in pipeline, with Minecraft's uniform blocks
	 * filled the way Minecraft fills them - so a field read from the wrong std140 offset moves the
	 * geometry or the colour and fails an assertion rather than passing quietly. The tilt is what
	 * makes a reconstructed normal have all three components: a face normal taken from screen-space
	 * derivatives is exact for a flat surface, and a tilt is what makes a wrong sign or a swapped
	 * pair of derivatives produce a different number rather than the same axis.
	 */
	private static void assertWorldProgramChannels(
		final MetalDevice device,
		final MetalGpuDevice gpuDevice,
		final MetalWorldGeometry world
	) {
		// Solid terrain: the chunk section's own transform, and the surface class everything else is
		// measured against.
		GBufferReadback solid = drawWorldProgram(
			device, gpuDevice, world, terrainPipeline(null), blockQuad(0xFF), chunkSectionBlock(), dynamicTransformsBlock(1.0F)
		);
		// Minecraft's own attachment still carries the fully shaded fragment, because nothing lights
		// the G-buffer yet and the game has to look the same as it did.
		assertChannel(solid.scene(), new int[] {255, 128, 64, 255}, 2, "solid terrain scene");
		// Albedo is the surface before the light: texture times vertex tint, no lightmap, no fog.
		// Alpha is one past the material class, and solid is class zero.
		assertChannel(solid.albedo(), new int[] {255, 128, 64, 1}, 2, "solid terrain albedo");
		// The octahedral normal, solid roughness, and high byte of 24-bit linear view depth.
		assertChannel(solid.normal(), new int[] {223, 191, 217, 0}, 3, "solid terrain normal");
		// Block and sky light keep one byte each; B/A carry the middle and low depth bytes.
		assertChannel(solid.light(), new int[] {255, 128, 40, 0}, 2, "solid terrain light");

		// Cutout terrain, above its threshold: the same surface, classified as foliage and rougher
		// for it. Alpha cutout on world terrain is overwhelmingly leaves, grass and crops.
		GBufferReadback foliage = drawWorldProgram(
			device, gpuDevice, world, terrainPipeline("0.1"), blockQuad(0xFF), chunkSectionBlock(), dynamicTransformsBlock(1.0F)
		);
		assertChannel(foliage.albedo(), new int[] {255, 128, 64, 2}, 2, "cutout terrain albedo");
		assertChannel(foliage.normal(), new int[] {223, 191, 230, 0}, 3, "cutout terrain normal");

		// The same program below its threshold, which is what proves the threshold is compiled in
		// rather than merely declared: every attachment keeps the value the pass cleared it to.
		GBufferReadback discarded = drawWorldProgram(
			device, gpuDevice, world, terrainPipeline("0.1"), blockQuad(0x10), chunkSectionBlock(), dynamicTransformsBlock(1.0F)
		);
		assertChannel(discarded.scene(), new int[] {0, 0, 0, 0}, 2, "discarded cutout scene");
		assertChannel(discarded.albedo(), new int[] {0, 0, 0, 0}, 2, "discarded cutout albedo");
		assertChannel(discarded.normal(), new int[] {128, 128, 0, 0}, 2, "discarded cutout normal");
		assertChannel(discarded.light(), new int[] {0, 0, 0, 0}, 2, "discarded cutout light");

		// Loose block models: the same geometry reached through DynamicTransforms instead, so the
		// model offset and the colour modulator are read from their own std140 offsets. Green is
		// halved by the modulator alone, which is what makes that offset observable.
		GBufferReadback block = drawWorldProgram(
			device, gpuDevice, world, blockPipeline(), blockQuad(0xFF), chunkSectionBlock(), dynamicTransformsBlock(0.5F)
		);
		assertChannel(block.scene(), new int[] {255, 64, 64, 255}, 2, "block model scene");
		assertChannel(block.albedo(), new int[] {255, 64, 64, 1}, 2, "block model albedo");
		assertChannel(block.normal(), new int[] {223, 191, 217, 0}, 3, "block model normal");

		// Entities: the one world format carrying a real normal, so nothing is reconstructed. The
		// vertex points straight up, and the cardinal light folds to 0.7 for the directions below,
		// which the scene carries and the albedo deliberately does not.
		GBufferReadback entity = drawWorldProgram(
			device, gpuDevice, world, entityPipeline(true), entityQuad(), chunkSectionBlock(), dynamicTransformsBlock(1.0F)
		);
		assertChannel(entity.scene(), new int[] {179, 90, 45, 255}, 2, "entity scene");
		assertChannel(entity.albedo(), new int[] {255, 128, 64, 4}, 2, "entity albedo");
		assertChannel(entity.normal(), new int[] {128, 255, 179, 0}, 2, "entity normal");
		assertChannel(entity.light(), new int[] {255, 128, 40, 0}, 2, "entity light");

		// The emissive variant: no lightmap to multiply by and a class of its own. The resolve finds
		// emissive from the material byte, leaving all three depth bytes available at full precision.
		GBufferReadback emissive = drawWorldProgram(
			device, gpuDevice, world, entityPipeline(false), entityQuad(), chunkSectionBlock(), dynamicTransformsBlock(1.0F)
		);
		assertChannel(emissive.scene(), new int[] {179, 90, 45, 255}, 2, "emissive entity scene");
		assertChannel(emissive.albedo(), new int[] {255, 128, 64, 5}, 2, "emissive entity albedo");
		assertChannel(emissive.light(), new int[] {255, 128, 40, 0}, 2, "emissive entity light");
	}

	/** The four attachments one world draw wrote, already read back. */
	private record GBufferReadback(ByteBuffer scene, ByteBuffer albedo, ByteBuffer normal, ByteBuffer light) {
	}

	private static GBufferReadback drawWorldProgram(
		final MetalDevice device,
		final MetalGpuDevice gpuDevice,
		final MetalWorldGeometry world,
		final RenderPipeline vanilla,
		final ByteBuffer vertices,
		final ByteBuffer chunkSection,
		final ByteBuffer dynamicTransforms
	) {
		RenderPipeline standIn = world.standInFor(vanilla);
		if (standIn == null) {
			throw new AssertionError("The G-buffer pack declined to stand in for " + vanilla.getLocation());
		}
		MetalRenderPipeline pipeline = gpuDevice.getOrCompilePipeline(standIn).metal(true);
		// Resolved the way the backend resolves a bound name, rather than restated: flattened
		// uniforms first, then flattened samplers. A slot table written out by hand here would drift
		// from the one the engine compiled the program against.
		Map<String, Integer> slots = resourceSlots(vanilla);
		int size = GBUFFER_SIZE;
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture scene = device.createTexture(colorTarget(size));
			 MetalTexture albedo = device.createTexture(colorTarget(size));
			 MetalTexture normal = device.createTexture(colorTarget(size));
			 MetalTexture light = device.createTexture(colorTarget(size));
			 MetalTexture depth = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.DEPTH32_FLOAT, size, size, 1, MetalTexture.USAGE_RENDER_TARGET));
			 MetalTexture white = whiteTexture(device, queue);
			 MetalTextureView whiteView = white.createView();
			 MetalSampler sampler = device.createSampler(new MetalSampler.Descriptor(
				 MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 MetalBuffer vertexBuffer = uploadBuffer(device, vertices);
			 MetalBuffer projection = uploadBuffer(device, projectionBlock());
			 MetalBuffer fog = uploadBuffer(device, fogBlock());
			 MetalBuffer globals = uploadBuffer(device, globalsBlock());
			 MetalBuffer section = uploadBuffer(device, chunkSection);
			 MetalBuffer transforms = uploadBuffer(device, dynamicTransforms);
			 MetalBuffer lighting = uploadBuffer(device, lightingBlock());
			 MetalCommandBuffer commands = queue.createCommandBuffer()) {
			Map<String, MetalBuffer> uniforms = Map.of(
				"Projection", projection, "Fog", fog, "Globals", globals,
				"ChunkSection", section, "DynamicTransforms", transforms, "Lighting", lighting
			);
			try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				List.of(
					MetalRenderPass.ColorAttachment.clear(scene, 0.0, 0.0, 0.0, 0.0),
					MetalRenderPass.ColorAttachment.clear(albedo, 0.0, 0.0, 0.0, 0.0),
					MetalRenderPass.ColorAttachment.clear(normal, 0.5, 0.5, 0.0, 0.0),
					MetalRenderPass.ColorAttachment.clear(light, 0.0, 0.0, 0.0, 0.0)
				),
				new MetalRenderPass.DepthAttachment(
					depth, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.DONT_CARE, 1.0)
			))) {
				pass.setPipeline(pipeline);
				pass.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, vertexBuffer, 0L);
				for (Map.Entry<String, MetalBuffer> uniform : uniforms.entrySet()) {
					Integer slot = slots.get(uniform.getKey());
					if (slot != null) {
						pass.setUniformBuffer(slot, uniform.getValue(), 0L, MetalRenderPass.STAGE_ALL);
					}
				}
				// One white texture for the atlas, the lightmap and the overlay alike. A white
				// overlay texel has full alpha, which is Minecraft's own "no overlay here" and
				// leaves the colour it is mixed into unchanged.
				for (String texture : List.of("Sampler0", "Sampler1", "Sampler2")) {
					Integer slot = slots.get(texture);
					if (slot != null) {
						pass.setTexture(slot, whiteView, MetalRenderPass.STAGE_ALL);
						pass.setSampler(slot, sampler, MetalRenderPass.STAGE_ALL);
					}
				}
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
			}
			commands.commitAndWait();
			return new GBufferReadback(
				scene.readback(queue, 0), albedo.readback(queue, 0), normal.readback(queue, 0), light.readback(queue, 0)
			);
		}
	}

	/**
	 * Phase 4's exit mechanism: the real pack resolve consumes three memoryless attachments, a
	 * shadowed sample is darker than an unshadowed one, and a blended forward draw lands afterward.
	 */
	private static void assertPhaseFourDeferredResolve(
		final MetalDevice device,
		final MetalShaderEngine engine
	) {
		MetalTexture albedo = engine.targetTextureForTesting("gbuffer_albedo");
		MetalTexture normal = engine.targetTextureForTesting("gbuffer_normal");
		MetalTexture light = engine.targetTextureForTesting("gbuffer_light");
		MetalTexture shadowTarget = engine.targetTextureForTesting("shadow");
		MetalRenderPipeline resolve = engine.deferredResolvePipelineForTesting();
		MetalWorldShadow shadow = engine.shadowForTesting();
		if (albedo == null || normal == null || light == null || shadowTarget == null
			|| resolve == null || shadow == null) {
			throw new AssertionError("The built-in pack did not build the Phase 4 resolve resources");
		}
		for (MetalTexture target : List.of(albedo, normal, light)) {
			if (!target.isMemoryless() || target.descriptor().byteSize() != 0L) {
				throw new AssertionError("A G-buffer target has backing storage: " + target.descriptor());
			}
		}

		List<MetalRenderPipeline.ColorTarget> fillTargets = List.of(
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)
		);
		MetalRenderPipeline.BlendState alphaBlend = new MetalRenderPipeline.BlendState(
			MetalRenderPipeline.BlendFactor.SOURCE_ALPHA,
			MetalRenderPipeline.BlendFactor.ONE_MINUS_SOURCE_ALPHA,
			MetalRenderPipeline.BlendOperation.ADD,
			MetalRenderPipeline.BlendFactor.ONE,
			MetalRenderPipeline.BlendFactor.ONE_MINUS_SOURCE_ALPHA,
			MetalRenderPipeline.BlendOperation.ADD
		);
		shadow.useResolveCascadesForTesting();
		try (MetalRenderPipeline fill = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 PHASE_FOUR_MSL, "phase_four_vertex", PHASE_FOUR_MSL, "phase_four_fill",
				 fillTargets, MetalTexture.Format.DEPTH32_FLOAT, MetalRenderPipeline.VertexDescriptor.EMPTY,
				 MetalRenderPipeline.DepthState.DISABLED, MetalRenderPipeline.RasterState.DEFAULT));
			 MetalRenderPipeline forward = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
				 PHASE_FOUR_MSL, "phase_four_vertex", PHASE_FOUR_MSL, "phase_four_forward",
				 List.of(new MetalRenderPipeline.ColorTarget(
					 MetalTexture.Format.RGBA8_UNORM, MetalRenderPipeline.WRITE_ALL, alphaBlend)),
				 null, MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
				 MetalRenderPipeline.RasterState.DEFAULT));
			 MetalCommandQueue queue = device.createCommandQueue();
			 MetalTextureView shadowView = shadowTarget.createView();
			 MetalSampler sampler = device.createSampler(new MetalSampler.Descriptor(
				 MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			 MetalTexture depth = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.DEPTH32_FLOAT, GBUFFER_SIZE, GBUFFER_SIZE, 1,
				 MetalTexture.USAGE_RENDER_TARGET));
			 MetalTexture scene = device.createTexture(colorTarget(GBUFFER_SIZE))) {
			clearShadowLayer(queue, shadowTarget, 1.0);
			int[] lit = drawPhaseFourResolve(
				queue, fill, resolve, null, scene, albedo, normal, light, depth,
				shadowView, sampler, shadow, engine.lightingForTesting(), engine.optionUniformsForTesting()
			);
			clearShadowLayer(queue, shadowTarget, 0.0);
			int[] occluded = drawPhaseFourResolve(
				queue, fill, resolve, null, scene, albedo, normal, light, depth,
				shadowView, sampler, shadow, engine.lightingForTesting(), engine.optionUniformsForTesting()
			);
			if (luminance(lit) <= luminance(occluded) + 20) {
				throw new AssertionError("The deferred shadow term did not darken the surface: lit="
					+ List.of(lit[0], lit[1], lit[2]) + ", shadowed="
					+ List.of(occluded[0], occluded[1], occluded[2]));
			}

			clearShadowLayer(queue, shadowTarget, 1.0);
			int[] composited = drawPhaseFourResolve(
				queue, fill, resolve, forward, scene, albedo, normal, light, depth,
				shadowView, sampler, shadow, engine.lightingForTesting(), engine.optionUniformsForTesting()
			);
			if (composited[0] <= lit[0] || composited[1] >= lit[1] || composited[2] >= lit[2]) {
				throw new AssertionError("Forward translucency did not blend after deferred lighting: lit="
					+ List.of(lit[0], lit[1], lit[2]) + ", composited="
					+ List.of(composited[0], composited[1], composited[2]));
			}

			Matrix4f projection = new Matrix4f().setPerspective(
				(float)Math.toRadians(70.0), 1.0F, 0.05F, 512.0F, true
			);
			MetalWorldLighting lighting = engine.lightingForTesting();
			shadow.useIdentityLocalShadowsForTesting(0);
			lighting.publishForTesting(List.of(resolveLight(1L, 0.0F, 0.0F, 0.5F,
				1.0F, 0.02F, 0.02F, 5.0F, 6.0F, false)), projection, GBUFFER_SIZE, GBUFFER_SIZE, 0);
			int[] redNear = drawPhaseFourResolve(
				queue, fill, resolve, null, scene, albedo, normal, light, depth,
				shadowView, sampler, shadow, lighting, engine.optionUniformsForTesting()
			);
			lighting.publishForTesting(List.of(resolveLight(1L, 0.0F, 0.0F, 4.0F,
				1.0F, 0.02F, 0.02F, 5.0F, 6.0F, false)), projection, GBUFFER_SIZE, GBUFFER_SIZE, 0);
			int[] redFar = drawPhaseFourResolve(
				queue, fill, resolve, null, scene, albedo, normal, light, depth,
				shadowView, sampler, shadow, lighting, engine.optionUniformsForTesting()
			);
			if (redNear[0] <= lit[0] || redFar[0] <= redFar[1] || luminance(redNear) <= luminance(redFar)) {
				throw new AssertionError("Positioned colored local-light falloff was not resolved: baseline="
					+ List.of(lit[0], lit[1], lit[2]) + ", near="
					+ List.of(redNear[0], redNear[1], redNear[2]) + ", far="
					+ List.of(redFar[0], redFar[1], redFar[2]));
			}
			lighting.publishForTesting(List.of(resolveLight(2L, 0.0F, 0.0F, 0.5F,
				0.02F, 0.08F, 1.0F, 0.12F, 6.0F, false)), projection, GBUFFER_SIZE, GBUFFER_SIZE, 0);
			int[] blue = drawPhaseFourResolve(
				queue, fill, resolve, null, scene, albedo, normal, light, depth,
				shadowView, sampler, shadow, lighting, engine.optionUniformsForTesting()
			);
			if (blue[2] - lit[2] <= blue[0] - lit[0] + 20) {
				throw new AssertionError("A blue positional light lost its color in the GGX resolve: "
					+ List.of(blue[0], blue[1], blue[2]));
			}
			lighting.publishForTesting(List.of(resolveLight(3L, 0.0F, 0.0F, 0.5F,
				1.0F, 0.02F, 0.02F, 5.0F, 6.0F, true)), projection, GBUFFER_SIZE, GBUFFER_SIZE, 0);
			int[] enveloped = drawPhaseFourResolve(
				queue, fill, resolve, null, scene, albedo, normal, light, depth,
				shadowView, sampler, shadow, lighting, engine.optionUniformsForTesting()
			);
			if (Math.abs(luminance(enveloped) - luminance(lit)) > 5) {
				throw new AssertionError("A zero vanilla block-light envelope leaked a static positional light");
			}

			// A light offset in view +X must brighten the right of a facing plane more than the left.
			// If reconstruction flips X relative to the uploaded view-space position, the left wins.
			lighting.publishForTesting(List.of(resolveLight(4L, 0.25F, 0.0F, 0.5F,
				1.0F, 0.02F, 0.02F, 8.0F, 6.0F, false)), projection, GBUFFER_SIZE, GBUFFER_SIZE, 0);
			ByteBuffer offsetLit = drawPhaseFourResolveImage(
				queue, fill, resolve, scene, albedo, normal, light, depth,
				shadowView, sampler, shadow, lighting, engine.optionUniformsForTesting()
			);
			int[] left = pixelRgb(offsetLit, 1, GBUFFER_SIZE / 2, GBUFFER_SIZE);
			int[] right = pixelRgb(offsetLit, GBUFFER_SIZE - 2, GBUFFER_SIZE / 2, GBUFFER_SIZE);
			if (luminance(right) <= luminance(left)) {
				throw new AssertionError("A view-space +X local light did not stay bound to its offset: left="
					+ List.of(left[0], left[1], left[2]) + ", right="
					+ List.of(right[0], right[1], right[2]));
			}
		}
	}

	private static MetalWorldLighting.FrameLight resolveLight(
		final long id,
		final float x,
		final float y,
		final float z,
		final float red,
		final float green,
		final float blue,
		final float intensity,
		final float radius,
		final boolean envelope
	) {
		return new MetalWorldLighting.FrameLight(
			new MetalWorldLighting.LightId(Identifier.parse("metalcraft:resolve_fixture"), id),
			x, y, z, x, y, z, red, green, blue, intensity, radius, false, envelope, -1
		);
	}

	private static int[] drawPhaseFourResolve(
		final MetalCommandQueue queue,
		final MetalRenderPipeline fill,
		final MetalRenderPipeline resolve,
		final MetalRenderPipeline forward,
		final MetalTexture scene,
		final MetalTexture albedo,
		final MetalTexture normal,
		final MetalTexture light,
		final MetalTexture depth,
		final MetalTextureView shadowView,
		final MetalSampler sampler,
		final MetalWorldShadow shadow,
		final MetalWorldLighting lighting,
		final MetalBuffer options
	) {
		return pixelRgb(drawPhaseFourResolveImage(
			queue, fill, resolve, forward, scene, albedo, normal, light, depth,
			shadowView, sampler, shadow, lighting, options
		), 0, 0, GBUFFER_SIZE);
	}

	private static ByteBuffer drawPhaseFourResolveImage(
		final MetalCommandQueue queue,
		final MetalRenderPipeline fill,
		final MetalRenderPipeline resolve,
		final MetalTexture scene,
		final MetalTexture albedo,
		final MetalTexture normal,
		final MetalTexture light,
		final MetalTexture depth,
		final MetalTextureView shadowView,
		final MetalSampler sampler,
		final MetalWorldShadow shadow,
		final MetalWorldLighting lighting,
		final MetalBuffer options
	) {
		return drawPhaseFourResolveImage(
			queue, fill, resolve, null, scene, albedo, normal, light, depth,
			shadowView, sampler, shadow, lighting, options
		);
	}

	private static ByteBuffer drawPhaseFourResolveImage(
		final MetalCommandQueue queue,
		final MetalRenderPipeline fill,
		final MetalRenderPipeline resolve,
		final MetalRenderPipeline forward,
		final MetalTexture scene,
		final MetalTexture albedo,
		final MetalTexture normal,
		final MetalTexture light,
		final MetalTexture depth,
		final MetalTextureView shadowView,
		final MetalSampler sampler,
		final MetalWorldShadow shadow,
		final MetalWorldLighting lighting,
		final MetalBuffer options
	) {
		try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
			try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				List.of(
					MetalRenderPass.ColorAttachment.clear(scene, 0.0, 0.0, 0.0, 1.0),
					new MetalRenderPass.ColorAttachment(
						albedo, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.DONT_CARE,
						0.0, 0.0, 0.0, 0.0),
					new MetalRenderPass.ColorAttachment(
						normal, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.DONT_CARE,
						0.5, 0.5, 0.0, 0.0),
					new MetalRenderPass.ColorAttachment(
						light, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.DONT_CARE,
						0.0, 0.0, 0.0, 0.0)
				),
				new MetalRenderPass.DepthAttachment(
					depth, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.DONT_CARE, 1.0)
			))) {
				pass.setPipeline(fill);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				pass.setPipeline(resolve);
				pass.setUniformBuffer(0, options, 0L, MetalRenderPass.STAGE_FRAGMENT);
				pass.setUniformBuffer(
					1, shadow.matricesForTesting(), shadow.matricesOffsetForTesting(), MetalRenderPass.STAGE_FRAGMENT
				);
				pass.setUniformBuffer(
					2, lighting.lightBuffer(), lighting.lightBufferOffset(), MetalRenderPass.STAGE_FRAGMENT
				);
				pass.setUniformBuffer(
					3, lighting.tileBuffer(), lighting.tileBufferOffset(), MetalRenderPass.STAGE_FRAGMENT
				);
				pass.setUniformBuffer(
					4, shadow.localMatricesForTesting(), shadow.localMatricesOffsetForTesting(),
					MetalRenderPass.STAGE_FRAGMENT
				);
				pass.setTexture(0, shadowView, MetalRenderPass.STAGE_FRAGMENT);
				pass.setTexture(1, shadow.localShadowViewForTesting(), MetalRenderPass.STAGE_FRAGMENT);
				pass.setSampler(0, sampler, MetalRenderPass.STAGE_FRAGMENT);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
			}
			if (forward != null) {
				try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
					new MetalRenderPass.ColorAttachment(
						scene, MetalRenderPass.LoadAction.LOAD, MetalRenderPass.StoreAction.STORE,
						0.0, 0.0, 0.0, 1.0)))) {
					pass.setPipeline(forward);
					pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				}
			}
			commands.commitAndWait();
		}
		return scene.readback(queue, 0);
	}

	private static int[] pixelRgb(final ByteBuffer image, final int x, final int y, final int size) {
		int offset = (y * size + x) * 4;
		return new int[] {
			Byte.toUnsignedInt(image.get(offset)),
			Byte.toUnsignedInt(image.get(offset + 1)),
			Byte.toUnsignedInt(image.get(offset + 2))
		};
	}

	private static void clearShadowLayer(
		final MetalCommandQueue queue,
		final MetalTexture shadow,
		final double depth
	) {
		try (MetalCommandBuffer commands = queue.createCommandBuffer();
			 MetalRenderPass ignored = commands.beginRenderPass(MetalRenderPass.Descriptor.depthOnly(
				 new MetalRenderPass.DepthAttachment(
					 shadow, 0, 0, MetalRenderPass.LoadAction.CLEAR,
					 MetalRenderPass.StoreAction.STORE, depth)))) {
			ignored.close();
			commands.commitAndWait();
		}
	}

	private static int luminance(final int[] rgb) {
		return rgb[0] * 3 + rgb[1] * 6 + rgb[2];
	}

	// ---- Phase 2 fixtures --------------------------------------------------------------------

	private static final int GBUFFER_SIZE = 8;

	/**
	 * Name to Metal argument-table slot, resolved the way the backend resolves a bound name: the
	 * flattened uniforms first, then the flattened samplers after them.
	 */
	private static Map<String, Integer> resourceSlots(final RenderPipeline pipeline) {
		Map<String, Integer> slots = new LinkedHashMap<>();
		for (BindGroupLayout.UniformDescription uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
			slots.put(uniform.name(), slots.size());
		}
		for (String sampler : BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts())) {
			slots.put(sampler, slots.size());
		}
		return slots;
	}

	private static VertexFormat blockVertexFormat() {
		return VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.build();
	}

	private static VertexFormat entityVertexFormat() {
		return VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGB32_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV1", GpuFormat.RG16_SINT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.addAttribute("Normal", GpuFormat.RGBA8_SNORM)
			.build();
	}

	private static RenderPipeline.Builder worldPipeline(final String location, final String program) {
		return RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("metalcraft", location))
			.withVertexShader(Identifier.fromNamespaceAndPath("minecraft", program))
			.withFragmentShader(Identifier.fromNamespaceAndPath("minecraft", program))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
			.withPolygonMode(PolygonMode.FILL)
			.withCull(false);
	}

	/** The layout Minecraft's terrain pipelines carry, in the order that fixes every Metal slot. */
	private static BindGroupLayout terrainBindings() {
		return BindGroupLayout.builder()
			.withUniform("Projection", UniformType.UNIFORM_BUFFER)
			.withUniform("Fog", UniformType.UNIFORM_BUFFER)
			.withUniform("Globals", UniformType.UNIFORM_BUFFER)
			.withUniform("ChunkSection", UniformType.UNIFORM_BUFFER)
			.withSampler("Sampler0")
			.withSampler("Sampler2")
			.build();
	}

	private static RenderPipeline terrainPipeline(final String alphaCutout) {
		RenderPipeline.Builder builder = worldPipeline(
			alphaCutout == null ? "smoke_solid_terrain" : "smoke_cutout_terrain", "core/terrain"
		)
			.withBindGroupLayout(terrainBindings())
			.withVertexBinding(0, blockVertexFormat())
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
		return alphaCutout == null ? builder.build() : builder.withShaderDefine("ALPHA_CUTOUT", Float.parseFloat(alphaCutout)).build();
	}

	private static RenderPipeline translucentTerrainPipeline() {
		return worldPipeline("smoke_translucent_terrain", "core/terrain")
			.withBindGroupLayout(terrainBindings())
			.withVertexBinding(0, blockVertexFormat())
			.withColorTargetState(new ColorTargetState(
				Optional.of(BlendFunction.TRANSLUCENT), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.build();
	}

	private static RenderPipeline blockPipeline() {
		return worldPipeline("smoke_solid_block", "core/block")
			.withBindGroupLayout(BindGroupLayout.builder()
				.withUniform("Projection", UniformType.UNIFORM_BUFFER)
				.withUniform("Fog", UniformType.UNIFORM_BUFFER)
				.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
				.withSampler("Sampler0")
				.withSampler("Sampler2")
				.build())
			.withVertexBinding(0, blockVertexFormat())
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.build();
	}

	/** With a lightmap and overlay, or the emissive variant that has neither. */
	private static RenderPipeline entityPipeline(final boolean lit) {
		BindGroupLayout.Builder bindings = BindGroupLayout.builder()
			.withUniform("Projection", UniformType.UNIFORM_BUFFER)
			.withUniform("Fog", UniformType.UNIFORM_BUFFER)
			.withUniform("Lighting", UniformType.UNIFORM_BUFFER)
			.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
			.withSampler("Sampler0");
		if (lit) {
			bindings = bindings.withSampler("Sampler1").withSampler("Sampler2");
		}
		RenderPipeline.Builder builder = worldPipeline(lit ? "smoke_entity_solid" : "smoke_entity_emissive", "core/entity")
			.withBindGroupLayout(bindings.build())
			.withVertexBinding(0, entityVertexFormat())
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL));
		return lit ? builder.build() : builder.withShaderDefine("EMISSIVE").withShaderDefine("NO_OVERLAY").build();
	}

	/** An entity variant this pack's program does not implement, which must therefore be declined. */
	private static RenderPipeline dissolveEntityPipeline() {
		return worldPipeline("smoke_entity_dissolve", "core/entity")
			.withBindGroupLayout(BindGroupLayout.builder()
				.withUniform("Projection", UniformType.UNIFORM_BUFFER)
				.withUniform("Fog", UniformType.UNIFORM_BUFFER)
				.withUniform("Lighting", UniformType.UNIFORM_BUFFER)
				.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
				.withSampler("Sampler0")
				.withSampler("Sampler1")
				.withSampler("Sampler2")
				.withSampler("DissolveMaskSampler")
				.build())
			.withVertexBinding(0, entityVertexFormat())
			.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
			.withShaderDefine("DISSOLVE")
			.build();
	}

	private static MetalTexture.Descriptor colorTarget(final int size) {
		return new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, size, size, 1, MetalTexture.USAGE_ALL);
	}

	private static MetalTexture whiteTexture(final MetalDevice device, final MetalCommandQueue queue) {
		MetalTexture texture = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, 16, 16, 1));
		ByteBuffer white = ByteBuffer.allocateDirect(16 * 16 * 4);
		while (white.hasRemaining()) {
			white.put((byte)0xFF);
		}
		white.flip();
		texture.upload(queue, 0, white);
		return texture;
	}

	private static MetalBuffer uploadBuffer(final MetalDevice device, final ByteBuffer contents) {
		MetalBuffer buffer = device.createBuffer(contents.remaining(), MetalBuffer.StorageMode.SHARED);
		try (MetalBuffer.Mapping mapping = buffer.map()) {
			mapping.bytes().put(contents.duplicate());
		}
		return buffer;
	}

	/**
	 * One tilted triangle covering the viewport, in Minecraft's BLOCK vertex format.
	 *
	 * <p>The z of each vertex puts the surface on the plane {@code z = 2x + y}, which after the two
	 * or three units the transform adds becomes {@code z = 2x + y + 2} in camera-relative world
	 * space: a plane that never passes through the camera, so the reconstructed normal's facing test
	 * has one answer across the whole quad.
	 *
	 * @param alpha the vertex colour's alpha, which is what an alpha-cutout variant tests against
	 */
	private static ByteBuffer blockQuad(final int alpha) {
		ByteBuffer vertices = ByteBuffer.allocateDirect(3 * 28).order(ByteOrder.nativeOrder());
		for (float[] corner : QUAD_CORNERS) {
			vertices.putFloat(corner[0]).putFloat(corner[1]).putFloat(2.0F * corner[0] + corner[1]);
			vertices.put((byte)0xFF).put((byte)0x80).put((byte)0x40).put((byte)alpha);
			vertices.putFloat(0.5F).putFloat(0.5F);
			vertices.putShort((short)240).putShort((short)120);
		}
		return vertices.flip();
	}

	/** Terrain whose chunk-relative position lands at depth 0.5 under the identity test cascades. */
	private static ByteBuffer shadowTerrainTriangle() {
		ByteBuffer vertices = ByteBuffer.allocateDirect(3 * 28).order(ByteOrder.nativeOrder());
		for (float[] corner : QUAD_CORNERS) {
			vertices.putFloat(corner[0]).putFloat(corner[1]).putFloat(-1.5F);
			vertices.put((byte)0xFF).put((byte)0xFF).put((byte)0xFF).put((byte)0xFF);
			vertices.putFloat(0.5F).putFloat(0.5F);
			vertices.putShort((short)240).putShort((short)240);
		}
		return vertices.flip();
	}

	/**
	 * The same triangle in Minecraft's ENTITY format, whose vertices carry a real normal.
	 *
	 * <p>The normal points straight up and the positions are already camera-relative, so this quad
	 * exercises the path that reads a normal rather than the one that reconstructs one.
	 */
	private static ByteBuffer entityQuad() {
		ByteBuffer vertices = ByteBuffer.allocateDirect(3 * 36).order(ByteOrder.nativeOrder());
		for (float[] corner : QUAD_CORNERS) {
			vertices.putFloat(corner[0]).putFloat(corner[1]).putFloat(2.0F * corner[0] + corner[1] + 2.0F);
			vertices.put((byte)0xFF).put((byte)0x80).put((byte)0x40).put((byte)0xFF);
			vertices.putFloat(0.5F).putFloat(0.5F);
			// UV1 addresses the overlay texture, UV2 the lightmap.
			vertices.putShort((short)0).putShort((short)10);
			vertices.putShort((short)240).putShort((short)120);
			// Straight up, as a signed-normalised byte triple.
			vertices.put((byte)0).put((byte)127).put((byte)0).put((byte)0);
		}
		return vertices.flip();
	}

	private static final float[][] QUAD_CORNERS = {{-1.0F, -1.0F}, {3.0F, -1.0F}, {-1.0F, 3.0F}};

	/** A projection that keeps x and y and pins z, so the quad's tilt reaches only the G-buffer. */
	private static ByteBuffer projectionBlock() {
		ByteBuffer block = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
		float[] columnMajor = {
			1.0F, 0.0F, 0.0F, 0.0F,
			0.0F, 1.0F, 0.0F, 0.0F,
			0.0F, 0.0F, 0.0F, 0.0F,
			0.0F, 0.0F, 0.5F, 1.0F
		};
		for (float value : columnMajor) {
			block.putFloat(value);
		}
		return block.flip();
	}

	/** Fog with zero alpha, which makes the fog blend a no-op without disabling the code path. */
	private static ByteBuffer fogBlock() {
		ByteBuffer block = ByteBuffer.allocateDirect(48).order(ByteOrder.nativeOrder());
		block.putFloat(0.0F).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
		block.putFloat(0.0F).putFloat(1000.0F).putFloat(0.0F).putFloat(1000.0F).putFloat(1000.0F).putFloat(1000.0F);
		return block.flip();
	}

	private static ByteBuffer globalsBlock() {
		ByteBuffer block = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder());
		block.putInt(5).putInt(6).putInt(5).putInt(0);          // CameraBlockPos, padded to a vec4 slot
		block.putFloat(0.0F).putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);  // CameraOffset, likewise
		block.putFloat(GBUFFER_SIZE).putFloat(GBUFFER_SIZE);    // ScreenSize
		block.putFloat(1.0F).putFloat(0.0F);                    // GlintAlpha, GameTime
		block.putInt(0).putInt(0);                              // MenuBlurRadius, UseRgss
		return block.flip();
	}

	private static ByteBuffer chunkSectionBlock() {
		ByteBuffer block = ByteBuffer.allocateDirect(96).order(ByteOrder.nativeOrder());
		for (float value : IDENTITY_MATRIX) {
			block.putFloat(value);
		}
		block.putFloat(1.0F).putFloat(0.0F);                    // ChunkVisibility, then std140 padding
		block.putInt(16).putInt(16);                            // TextureSize
		block.putInt(5).putInt(6).putInt(7).putInt(0);          // ChunkPosition, padded to a vec4 slot
		return block.flip();
	}

	/**
	 * Minecraft's per-draw transform block.
	 *
	 * @param greenModulator the colour modulator's green component, halved by one caller so that the
	 *     field's std140 offset is observable in the result rather than merely present
	 */
	private static ByteBuffer dynamicTransformsBlock(final float greenModulator) {
		ByteBuffer block = ByteBuffer.allocateDirect(160).order(ByteOrder.nativeOrder());
		for (float value : IDENTITY_MATRIX) {
			block.putFloat(value);
		}
		block.putFloat(1.0F).putFloat(greenModulator).putFloat(1.0F).putFloat(1.0F);   // ColorModulator
		block.putFloat(0.0F).putFloat(0.0F).putFloat(2.0F).putFloat(0.0F);             // ModelOffset, padded
		for (float value : IDENTITY_MATRIX) {                                          // TextureMat
			block.putFloat(value);
		}
		return block.flip();
	}

	/**
	 * The two cardinal light directions, chosen so their fold is neither zero nor saturated: the
	 * up-facing quad sees 0.5 from the first and nothing from the second, which folds to 0.7.
	 */
	private static ByteBuffer lightingBlock() {
		ByteBuffer block = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
		block.putFloat(0.0F).putFloat(0.5F).putFloat(0.0F).putFloat(0.0F);
		block.putFloat(0.0F).putFloat(-1.0F).putFloat(0.0F).putFloat(0.0F);
		return block.flip();
	}

	private static final float[] IDENTITY_MATRIX = {
		1.0F, 0.0F, 0.0F, 0.0F,
		0.0F, 1.0F, 0.0F, 0.0F,
		0.0F, 0.0F, 1.0F, 0.0F,
		0.0F, 0.0F, 0.0F, 1.0F
	};

	private static void assertChannel(
		final ByteBuffer pixels,
		final int[] expected,
		final int tolerance,
		final String description
	) {
		for (int component = 0; component < expected.length; component++) {
			int actual = Byte.toUnsignedInt(pixels.get(component));
			if (Math.abs(actual - expected[component]) > tolerance) {
				throw new AssertionError(String.format(
					"%s component %d is %d, expected %d (+/-%d)", description, component, actual, expected[component], tolerance
				));
			}
		}
	}

	private static void assertPersistentPipelineCache() {
		Path root = Path.of("build", "shader-smoke-cache");
		deleteTree(root);
		MetalRenderPipeline.Descriptor renderDescriptor = new MetalRenderPipeline.Descriptor(
			COMPUTE_MSL, "readback_vertex", COMPUTE_MSL, "readback_fragment",
			List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)), null,
			MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
			MetalRenderPipeline.RasterState.DEFAULT
		);
		MetalComputePipeline.Descriptor computeDescriptor = new MetalComputePipeline.Descriptor(COMPUTE_MSL, "fill_tint");

		try (MetalDevice device = MetalNative.openDefaultDevice().orElseThrow()) {
			MetalPipelineCache cold = new MetalPipelineCache(device, root);
			try (MetalRenderPipeline ignoredRender = cold.createRenderPipeline(renderDescriptor);
				 MetalComputePipeline ignoredCompute = cold.createComputePipeline(computeDescriptor)) {
				if (cold.compilationCount() != 2) {
					throw new AssertionError("A cold Metal pipeline cache compiled " + cold.compilationCount() + " pipelines, expected 2");
				}
			}
		}

		try (MetalDevice device = MetalNative.openDefaultDevice().orElseThrow()) {
			MetalPipelineCache warm = new MetalPipelineCache(device, root);
			try (MetalRenderPipeline ignoredRender = warm.createRenderPipeline(renderDescriptor);
				 MetalComputePipeline ignoredCompute = warm.createComputePipeline(computeDescriptor)) {
				if (warm.compilationCount() != 0) {
					throw new AssertionError("A warm Metal pipeline cache recompiled " + warm.compilationCount() + " pipelines");
				}
			}
		}

		try (var archives = Files.walk(root)) {
			Path archive = archives.filter(Files::isRegularFile).findFirst().orElseThrow();
			Files.write(archive, new byte[] {0, 1, 2, 3});
		} catch (IOException error) {
			throw new AssertionError("Could not corrupt a Metal archive for recovery coverage", error);
		}
		try (MetalDevice device = MetalNative.openDefaultDevice().orElseThrow()) {
			MetalPipelineCache recovered = new MetalPipelineCache(device, root);
			try (MetalRenderPipeline ignoredRender = recovered.createRenderPipeline(renderDescriptor);
				 MetalComputePipeline ignoredCompute = recovered.createComputePipeline(computeDescriptor)) {
				if (recovered.compilationCount() != 1) {
					throw new AssertionError("A corrupt Metal archive did not rebuild exactly one pipeline");
				}
			}
		}
	}

	private static void assertPhaseZeroExit() {
		Path root = Path.of("build", "phase-zero-pipeline-cache");
		deleteTree(root);
		try (MetalDevice device = MetalNative.openDefaultDevice().orElseThrow()) {
			MetalPipelineCache cold = new MetalPipelineCache(device, root);
			runPhaseZeroExit(device, cold, true);
			if (cold.compilationCount() != 4) {
				throw new AssertionError("Phase 0 cold launch compiled " + cold.compilationCount() + " pipelines, expected 4");
			}
		}
		try (MetalDevice device = MetalNative.openDefaultDevice().orElseThrow()) {
			MetalPipelineCache warm = new MetalPipelineCache(device, root);
			runPhaseZeroExit(device, warm, false);
			if (warm.compilationCount() != 0) {
				throw new AssertionError("Phase 0 warm launch compiled " + warm.compilationCount() + " pipelines");
			}
		}
	}

	private static void assertPhaseOneRuntime() {
		Path root = Path.of("build", "phase-one-shaderpacks");
		Path settings = Path.of("build", "phase-one-shader-settings.json");
		deleteTree(root);
		try {
			Files.deleteIfExists(settings);
			Path external = root.resolve("external-copy");
			Files.createDirectories(external);
			Files.writeString(external.resolve("pack.json"), phaseOneManifest("External Copy"), StandardCharsets.UTF_8);
			Files.writeString(external.resolve("final.metal"), PHASE_ONE_MSL, StandardCharsets.UTF_8);
		} catch (IOException error) {
			throw new AssertionError("Could not create the Phase 1 shader-pack fixture", error);
		}

		try (MetalDevice device = MetalNative.openDefaultDevice().orElseThrow()) {
			try (MetalShaderEngine engine = new MetalShaderEngine(device, root, settings)) {
				if (engine.availablePacks().size() != 2 || !engine.selectedPackId().equals("metalcraft-standard")) {
					throw new AssertionError("Phase 1 did not discover its built-in and external packs");
				}
				if (engine.options().stream().map(ShaderPack.Option::apply).distinct().count() != 3L) {
					throw new AssertionError("The built-in shader pack does not expose all three apply modes");
				}
				engine.resize(32, 24);
				assertNoWorldDeclinesPresentation(device, engine);
				engine.setOption("exposure", 1.25);
				engine.setOption("invert", true);
				engine.setOption("upscale_filter", "nearest");
				engine.selectPack("external-copy");
				// The external pack has no geometry pass, so it composites whatever it is handed.
				assertPackBlitsScene(device, engine, "External scene-to-drawable pack");
				engine.setOption("quality", 2);
				engine.selectPack("external-copy");
				engine.resize(48, 32);
				engine.reload();
			}

			try (MetalShaderEngine restored = new MetalShaderEngine(device, root, settings)) {
				if (!restored.selectedPackId().equals("external-copy")) {
					throw new AssertionError("The selected shader pack was not persisted");
				}
				if (!Integer.valueOf(2).equals(restored.optionValue("quality"))) {
					throw new AssertionError("A numeric enum shader setting did not retain its manifest type");
				}
				restored.selectPack("metalcraft-standard");
				if (Math.abs(((Number)restored.optionValue("exposure")).doubleValue() - 1.25) > 0.0001) {
					throw new AssertionError("Per-pack shader settings were not persisted");
				}
			}
		}
	}

	/** A pack with no geometry pass composites whatever it is handed, unconditionally. */
	private static void assertPackBlitsScene(final MetalDevice device, final MetalShaderEngine engine, final String description) {
		int size = 4;
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture scene = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, size, size, 1));
			 MetalTexture output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.BGRA8_UNORM, size, size, 1))) {
			ByteBuffer gray = ByteBuffer.allocateDirect(size * size * 4);
			for (int pixel = 0; pixel < size * size; pixel++) {
				gray.put((byte)0x80).put((byte)0x80).put((byte)0x80).put((byte)0xFF);
			}
			gray.flip();
			scene.upload(queue, 0, gray);
			try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
				if (!engine.encodeForTesting(commands, scene, output)) {
					throw new AssertionError(description + " declined to composite a scene it does not need a world for");
				}
				commands.commitAndWait();
			}
			assertFirstPixel(output.readback(queue, 0), 0x808080, description);
		}
	}

	/**
	 * A pack whose graph starts in the world declines to present when no world was drawn.
	 *
	 * <p>Asserted rather than assumed, because the failure it prevents is a black screen on every
	 * menu: the built-in pack's last pass reads a G-buffer and a depth attachment that only exist
	 * once Minecraft has rendered a level into them.
	 */
	private static void assertNoWorldDeclinesPresentation(final MetalDevice device, final MetalShaderEngine engine) {
		int size = 4;
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture scene = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, size, size, 1));
			 MetalTexture output = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.BGRA8_UNORM, size, size, 1));
			 MetalCommandBuffer commands = queue.createCommandBuffer()) {
			if (engine.encodeForTesting(commands, scene, output)) {
				throw new AssertionError("The G-buffer pack presented a frame in which no world was drawn");
			}
			commands.commitAndWait();
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
				writeZipText(zip, "graph.metal", PHASE_ONE_MSL);
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
			Files.writeString(invalid.resolve("graph.metal"), PHASE_ONE_MSL, StandardCharsets.UTF_8);
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

	private static void writeZipText(final ZipOutputStream zip, final String path, final String text) throws IOException {
		zip.putNextEntry(new ZipEntry(path));
		zip.write(text.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	private static String phaseOneManifest(final String name) {
		return """
			{
			  "format": 1,
			  "name": "%s",
			  "targets": {},
			  "passes": [
			    {"id": "final", "kind": "fullscreen", "reads": ["scene"], "writes": ["drawable"]}
			  ],
			  "options": [
			    {"id": "exposure", "category": "test", "type": "float", "min": 0.5, "max": 2.0, "default": 1.0, "apply": "uniform"},
			    {"id": "quality", "category": "test", "type": "enum", "values": [1, 2], "default": 1, "apply": "reload"}
			  ]
			}
			""".formatted(name);
	}

	private static void runPhaseZeroExit(
		final MetalDevice device,
		final MetalPipelineCache cache,
		final boolean render
	) {
		List<MetalRenderPipeline.ColorTarget> fillTargets = List.of(
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)
		);
		List<MetalRenderPipeline.ColorTarget> resolveTargets = List.of(
			new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA8_UNORM, 0, null),
			new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA8_UNORM, 0, null),
			MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM),
			new MetalRenderPipeline.ColorTarget(MetalTexture.Format.RGBA8_UNORM, 0, null)
		);
		MetalRenderPipeline.Descriptor fillDescriptor = new MetalRenderPipeline.Descriptor(
			PHASE_ZERO_MSL, "phase_zero_vertex", PHASE_ZERO_MSL, "phase_zero_fill", fillTargets, null,
			MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
			MetalRenderPipeline.RasterState.DEFAULT
		);
		MetalRenderPipeline.Descriptor resolveDescriptor = new MetalRenderPipeline.Descriptor(
			PHASE_ZERO_MSL, "phase_zero_vertex", PHASE_ZERO_MSL, "phase_zero_resolve", resolveTargets, null,
			MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
			MetalRenderPipeline.RasterState.DEFAULT
		);
		MetalRenderPipeline.Descriptor layeredDescriptor = new MetalRenderPipeline.Descriptor(
			LAYERED_MSL, "layered_vertex", LAYERED_MSL, "layered_fragment",
			List.of(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.RGBA8_UNORM)), null,
			MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
			new MetalRenderPipeline.RasterState(
				MetalRenderPipeline.CullMode.NONE, MetalRenderPipeline.FillMode.FILL,
				MetalRenderPipeline.TopologyClass.TRIANGLE
			)
		);

		try (MetalRenderPipeline fill = cache.createRenderPipeline(fillDescriptor);
			 MetalRenderPipeline resolve = cache.createRenderPipeline(resolveDescriptor);
			 MetalComputePipeline compute = cache.createComputePipeline(
				 new MetalComputePipeline.Descriptor(PHASE_ZERO_MSL, "phase_zero_compute"));
			 MetalRenderPipeline layered = cache.createRenderPipeline(layeredDescriptor)) {
			if (!render) {
				return;
			}
			renderPhaseZeroExit(device, fill, resolve, compute, layered);
		}
	}

	private static void renderPhaseZeroExit(
		final MetalDevice device,
		final MetalRenderPipeline fill,
		final MetalRenderPipeline resolve,
		final MetalComputePipeline compute,
		final MetalRenderPipeline layered
	) {
		int size = 4;
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture albedo = device.createTexture(MetalTexture.Descriptor.memoryless(MetalTexture.Format.RGBA8_UNORM, size, size));
			 MetalTexture normal = device.createTexture(MetalTexture.Descriptor.memoryless(MetalTexture.Format.RGBA8_UNORM, size, size));
			 MetalTexture scene = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, size, size, 1));
			 MetalTexture marker = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.RGBA8_UNORM, size, size, 1));
			 MetalTextureView sceneView = scene.createView();
			 MetalTexture computed = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.RGBA8_UNORM, size, size, 1,
				 MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_SHADER_WRITE));
			 MetalTextureView computedView = computed.createView();
			 MetalTexture layers = device.createTexture(MetalTexture.Descriptor.array(
				 MetalTexture.Format.RGBA8_UNORM, size, size, 4,
				 MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_RENDER_TARGET));
			 MetalCommandBuffer commands = queue.createCommandBuffer()) {
			List<MetalRenderPass.ColorAttachment> attachments = List.of(
				new MetalRenderPass.ColorAttachment(albedo, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.DONT_CARE, 0, 0, 0, 1),
				new MetalRenderPass.ColorAttachment(normal, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.DONT_CARE, 0, 0, 0, 1),
				new MetalRenderPass.ColorAttachment(scene, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0, 0, 0, 1),
				new MetalRenderPass.ColorAttachment(marker, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0, 0, 0, 1)
			);
			try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(attachments, null))) {
				pass.setPipeline(fill);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
				pass.setPipeline(resolve);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
			}
			try (MetalComputePass pass = commands.beginComputePass()) {
				pass.setPipeline(compute);
				pass.setTexture(0, sceneView);
				pass.setTexture(1, computedView);
				pass.dispatchCovering(size, size, 8, 8);
			}
			try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				List.of(new MetalRenderPass.ColorAttachment(
					layers, 0, 0, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 0, 0, 0, 1)),
				null, 4))) {
				pass.setPipeline(layered);
				pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 4, 0);
			}
			commands.commitAndWait();

			assertFirstPixel(computed.readback(queue, 0), 0x80C040, "Phase 0 compute output");
			assertFirstPixel(marker.readback(queue, 0), 0xFF00FF, "Phase 0 fourth attachment");
			int[] expectedLayers = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00};
			for (int layer = 0; layer < expectedLayers.length; layer++) {
				assertFirstPixel(layers.readback(queue, 0, layer), expectedLayers[layer], "Phase 0 array layer " + layer);
			}
		}
	}

	private static void assertFirstPixel(final ByteBuffer pixels, final int expected, final String description) {
		int actual = Byte.toUnsignedInt(pixels.get(0)) << 16
			| Byte.toUnsignedInt(pixels.get(1)) << 8
			| Byte.toUnsignedInt(pixels.get(2));
		if (!isNearColor(actual, expected)) {
			throw new AssertionError(String.format("%s produced %06X, expected %06X", description, actual, expected));
		}
	}

	private static void deleteTree(final Path root) {
		if (Files.notExists(root)) {
			return;
		}
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(path);
			}
		} catch (IOException error) {
			throw new AssertionError("Could not reset Metal pipeline smoke cache " + root, error);
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
	/** MetalFX consumes an HDR render target and writes a larger private output texture. */
	private static void assertSpatialScaling(final MetalDevice device) {
		if (!device.supportsSpatialScaling()) {
			throw new AssertionError("The Apple-silicon smoke-test device does not support MetalFX spatial scaling");
		}
		int inputSize = 4;
		int outputSize = 8;
		MetalSpatialScaler.Descriptor descriptor = new MetalSpatialScaler.Descriptor(
			MetalTexture.Format.RGBA16_FLOAT, inputSize, inputSize,
			MetalTexture.Format.BGRA8_UNORM, outputSize, outputSize
		);
		try (MetalCommandQueue queue = device.createCommandQueue();
			 MetalTexture input = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.RGBA16_FLOAT, inputSize, inputSize, 1, MetalTexture.USAGE_ALL));
			 MetalTexture output = device.createTexture(new MetalTexture.Descriptor(
				 MetalTexture.Format.BGRA8_UNORM, outputSize, outputSize, 1, MetalTexture.USAGE_ALL));
			 MetalSpatialScaler scaler = device.createSpatialScaler(descriptor)) {
			ByteBuffer red = ByteBuffer.allocateDirect(inputSize * inputSize * 8).order(ByteOrder.nativeOrder());
			for (int index = 0; index < inputSize * inputSize; index++) {
				red.putShort(Float.floatToFloat16(1.0F));
				red.putShort(Float.floatToFloat16(0.0F));
				red.putShort(Float.floatToFloat16(0.0F));
				red.putShort(Float.floatToFloat16(1.0F));
			}
			red.flip();
			input.upload(queue, 0, red);
			try (MetalCommandBuffer commands = queue.createCommandBuffer()) {
				scaler.encode(commands, input, output);
				commands.commitAndWait();
			}
			ByteBuffer result = output.readback(queue, 0);
			int blue = Byte.toUnsignedInt(result.get(0));
			int green = Byte.toUnsignedInt(result.get(1));
			int redChannel = Byte.toUnsignedInt(result.get(2));
			if (redChannel < 200 || green > 30 || blue > 30) {
				throw new AssertionError("MetalFX spatial scaling changed a solid red image: "
					+ redChannel + "," + green + "," + blue);
			}
		}
	}

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

	/** Compute effects use the same encoder-boundary census as render passes. */
	private static void assertComputePassGpuTiming(final MetalDevice device) {
		List<MetalPassCensus.PassKind> passes;
		MetalStallProbe.setEnabled(true);
		try {
			MetalPassCensus.reset();
			int kind = MetalPassCensus.kindFor("smoke compute");
			try (MetalComputePipeline pipeline = device.createComputePipeline(
					 new MetalComputePipeline.Descriptor(COMPUTE_MSL, "fill_tint"));
				 MetalCommandQueue queue = device.createCommandQueue();
				 MetalTexture output = device.createTexture(new MetalTexture.Descriptor(
					 MetalTexture.Format.RGBA8_UNORM, 256, 256, 1, MetalTexture.USAGE_ALL));
				 MetalTextureView outputView = output.createView();
				 MetalBuffer tint = device.createBuffer(16L, MetalBuffer.StorageMode.SHARED);
				 MetalCommandBuffer commands = queue.createCommandBuffer();
				 MetalComputePass compute = commands.beginComputePass(kind)) {
				try (MetalBuffer.Mapping mapping = tint.map()) {
					mapping.bytes().order(ByteOrder.nativeOrder()).asFloatBuffer()
						.put(new float[]{0.25F, 0.5F, 0.75F, 1.0F});
				}
				compute.setPipeline(pipeline);
				compute.setTexture(0, outputView);
				compute.setBuffer(0, tint, 0L);
				compute.dispatchCovering(256, 256, 8, 8);
				compute.close();
				commands.commitAndWait();
			}
			passes = MetalPassCensus.take();
		} finally {
			MetalStallProbe.setEnabled(false);
		}
		MetalPassCensus.PassKind timed = passes.stream()
			.filter(pass -> pass.name().equals("smoke compute"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Metal pass census did not report the timed compute pass"));
		if (timed.count() != 1L || !(timed.totalMs() > 0.0)) {
			throw new AssertionError("Metal pass census reported an invalid compute sample: " + timed);
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
