package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exhaustive translation from Blaze3D pipeline state to the direct Metal bridge. */
public final class Blaze3DMetalMappings {
	public static final int VERTEX_BUFFER_BASE_INDEX = 16;
	private static final Pattern VERTEX_INPUT = Pattern.compile(
		"(?m)^(\\h*)in(\\h+[^;\\r\\n]+?\\h+)([A-Za-z_]\\w*)(\\h*(?:\\[[^;\\r\\n]+\\])?\\h*;)"
	);
	private static final Pattern UNIFORM_BLOCK = Pattern.compile(
		"(?m)^(\\h*)layout\\(([^)]+)\\)(\\h+uniform\\h+)([A-Za-z_]\\w*)(\\h*\\{)"
	);
	private static final Pattern SAMPLER_UNIFORM = Pattern.compile(
		"(?m)^(\\h*)(uniform\\h+[iu]?sampler\\w+\\h+)([A-Za-z_]\\w*)(\\h*(?:\\[[^;\\r\\n]+\\])?\\h*;)"
	);
	private static final String RESOURCE_BINDING_EXTENSION = "#extension GL_ARB_shading_language_420pack : enable\n";

	private Blaze3DMetalMappings() {
	}

	public static MetalRenderPipeline.Descriptor pipelineDescriptor(
		final RenderPipeline pipeline,
		final MetalShaderTranslator.PipelineTranslation shaders
	) {
		if (pipeline == null || shaders == null) {
			throw new NullPointerException("Blaze3D pipeline and translated shaders cannot be null");
		}
		MetalShaderTranslator.Translation vertex = shaders.vertex();
		MetalShaderTranslator.Translation fragment = shaders.fragment();
		DepthStencilState depthStencil = pipeline.getDepthStencilState();
		return new MetalRenderPipeline.Descriptor(
			vertex.metalSource(),
			vertex.entryPoint(),
			fragment.metalSource(),
			fragment.entryPoint(),
			colorTargets(pipeline.getColorTargetStates()),
			depthStencil == null ? null : MetalTexture.Format.DEPTH32_FLOAT,
			vertexDescriptor(pipeline.getVertexFormatBindings()),
			depthState(depthStencil),
			new MetalRenderPipeline.RasterState(
				pipeline.isCull() ? MetalRenderPipeline.CullMode.BACK : MetalRenderPipeline.CullMode.NONE,
				switch (pipeline.getPolygonMode()) {
					case FILL -> MetalRenderPipeline.FillMode.FILL;
					case WIREFRAME -> MetalRenderPipeline.FillMode.LINES;
				}
			)
		);
	}

	public static MetalTexture.Format textureFormat(final GpuFormat format) {
		if (format == null) {
			throw new NullPointerException("format");
		}
		return switch (format) {
			case R8_UNORM -> MetalTexture.Format.R8_UNORM;
			case R8_SNORM -> MetalTexture.Format.R8_SNORM;
			case RG8_UNORM -> MetalTexture.Format.RG8_UNORM;
			case RG8_SNORM -> MetalTexture.Format.RG8_SNORM;
			case RGBA8_UNORM -> MetalTexture.Format.RGBA8_UNORM;
			case RGBA8_SNORM -> MetalTexture.Format.RGBA8_SNORM;
			case R16_UNORM -> MetalTexture.Format.R16_UNORM;
			case R16_SNORM -> MetalTexture.Format.R16_SNORM;
			case RG16_UNORM -> MetalTexture.Format.RG16_UNORM;
			case RG16_SNORM -> MetalTexture.Format.RG16_SNORM;
			case RGBA16_UNORM -> MetalTexture.Format.RGBA16_UNORM;
			case RGBA16_SNORM -> MetalTexture.Format.RGBA16_SNORM;
			case R8_UINT -> MetalTexture.Format.R8_UINT;
			case R8_SINT -> MetalTexture.Format.R8_SINT;
			case RG8_UINT -> MetalTexture.Format.RG8_UINT;
			case RG8_SINT -> MetalTexture.Format.RG8_SINT;
			case RGBA8_UINT -> MetalTexture.Format.RGBA8_UINT;
			case RGBA8_SINT -> MetalTexture.Format.RGBA8_SINT;
			case R16_UINT -> MetalTexture.Format.R16_UINT;
			case R16_SINT -> MetalTexture.Format.R16_SINT;
			case RG16_UINT -> MetalTexture.Format.RG16_UINT;
			case RG16_SINT -> MetalTexture.Format.RG16_SINT;
			case RGBA16_UINT -> MetalTexture.Format.RGBA16_UINT;
			case RGBA16_SINT -> MetalTexture.Format.RGBA16_SINT;
			case R32_UINT -> MetalTexture.Format.R32_UINT;
			case R32_SINT -> MetalTexture.Format.R32_SINT;
			case RG32_UINT -> MetalTexture.Format.RG32_UINT;
			case RG32_SINT -> MetalTexture.Format.RG32_SINT;
			case RGBA32_UINT -> MetalTexture.Format.RGBA32_UINT;
			case RGBA32_SINT -> MetalTexture.Format.RGBA32_SINT;
			case R16_FLOAT -> MetalTexture.Format.R16_FLOAT;
			case RG16_FLOAT -> MetalTexture.Format.RG16_FLOAT;
			case RGBA16_FLOAT -> MetalTexture.Format.RGBA16_FLOAT;
			case R32_FLOAT -> MetalTexture.Format.R32_FLOAT;
			case RG32_FLOAT -> MetalTexture.Format.RG32_FLOAT;
			case RGBA32_FLOAT -> MetalTexture.Format.RGBA32_FLOAT;
			case RGB10A2_UNORM -> MetalTexture.Format.RGB10A2_UNORM;
			case RGB10A2_UINT -> MetalTexture.Format.RGB10A2_UINT;
			case RG11B10_FLOAT -> MetalTexture.Format.RG11B10_FLOAT;
			case D32_FLOAT -> MetalTexture.Format.DEPTH32_FLOAT;
			case D32_FLOAT_S8_UINT -> MetalTexture.Format.DEPTH32_FLOAT_STENCIL8;
			case D24_UNORM_S8_UINT -> MetalTexture.Format.DEPTH24_UNORM_STENCIL8;
			case D16_UNORM -> MetalTexture.Format.DEPTH16_UNORM;
			case S8_UINT -> MetalTexture.Format.STENCIL8;
			case RGB8_UNORM, RGB8_SNORM, RGB8_UINT, RGB8_SINT,
				RGB16_UNORM, RGB16_SNORM, RGB16_UINT, RGB16_SINT, RGB16_FLOAT,
				RGB32_UINT, RGB32_SINT, RGB32_FLOAT -> throw unsupportedTexture(format);
		};
	}

	public static MetalTexture.Descriptor textureDescriptor(
		final GpuFormat format,
		final @GpuTexture.Usage int usage,
		final int width,
		final int height,
		final int mipLevels
	) {
		return textureDescriptor(format, usage, width, height, 1, mipLevels);
	}

	public static MetalTexture.Descriptor textureDescriptor(
		final GpuFormat format,
		final @GpuTexture.Usage int usage,
		final int width,
		final int height,
		final int depthOrLayers,
		final int mipLevels
	) {
		int metalUsage = 0;
		if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0) {
			metalUsage |= MetalTexture.USAGE_SHADER_READ;
		}
		if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
			metalUsage |= MetalTexture.USAGE_RENDER_TARGET;
		}
		boolean cubemap = (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0;
		return new MetalTexture.Descriptor(textureFormat(format), width, height, depthOrLayers, mipLevels, metalUsage, cubemap);
	}

	public static MetalRenderPipeline.VertexAttributeFormat vertexFormat(final GpuFormat format) {
		if (format == null) {
			throw new NullPointerException("format");
		}
		return switch (format) {
			case R8_UNORM -> MetalRenderPipeline.VertexAttributeFormat.UCHAR_NORMALIZED;
			case RG8_UNORM -> MetalRenderPipeline.VertexAttributeFormat.UCHAR2_NORMALIZED;
			case RGB8_UNORM -> MetalRenderPipeline.VertexAttributeFormat.UCHAR3_NORMALIZED;
			case RGBA8_UNORM -> MetalRenderPipeline.VertexAttributeFormat.UCHAR4_NORMALIZED;
			case R8_SNORM -> MetalRenderPipeline.VertexAttributeFormat.CHAR_NORMALIZED;
			case RG8_SNORM -> MetalRenderPipeline.VertexAttributeFormat.CHAR2_NORMALIZED;
			case RGB8_SNORM -> MetalRenderPipeline.VertexAttributeFormat.CHAR3_NORMALIZED;
			case RGBA8_SNORM -> MetalRenderPipeline.VertexAttributeFormat.CHAR4_NORMALIZED;
			case R16_UNORM -> MetalRenderPipeline.VertexAttributeFormat.USHORT_NORMALIZED;
			case RG16_UNORM -> MetalRenderPipeline.VertexAttributeFormat.USHORT2_NORMALIZED;
			case RGB16_UNORM -> MetalRenderPipeline.VertexAttributeFormat.USHORT3_NORMALIZED;
			case RGBA16_UNORM -> MetalRenderPipeline.VertexAttributeFormat.USHORT4_NORMALIZED;
			case R16_SNORM -> MetalRenderPipeline.VertexAttributeFormat.SHORT_NORMALIZED;
			case RG16_SNORM -> MetalRenderPipeline.VertexAttributeFormat.SHORT2_NORMALIZED;
			case RGB16_SNORM -> MetalRenderPipeline.VertexAttributeFormat.SHORT3_NORMALIZED;
			case RGBA16_SNORM -> MetalRenderPipeline.VertexAttributeFormat.SHORT4_NORMALIZED;
			case R8_UINT -> MetalRenderPipeline.VertexAttributeFormat.UCHAR;
			case RG8_UINT -> MetalRenderPipeline.VertexAttributeFormat.UCHAR2;
			case RGB8_UINT -> MetalRenderPipeline.VertexAttributeFormat.UCHAR3;
			case RGBA8_UINT -> MetalRenderPipeline.VertexAttributeFormat.UCHAR4;
			case R8_SINT -> MetalRenderPipeline.VertexAttributeFormat.CHAR;
			case RG8_SINT -> MetalRenderPipeline.VertexAttributeFormat.CHAR2;
			case RGB8_SINT -> MetalRenderPipeline.VertexAttributeFormat.CHAR3;
			case RGBA8_SINT -> MetalRenderPipeline.VertexAttributeFormat.CHAR4;
			case R16_UINT -> MetalRenderPipeline.VertexAttributeFormat.USHORT;
			case RG16_UINT -> MetalRenderPipeline.VertexAttributeFormat.USHORT2;
			case RGB16_UINT -> MetalRenderPipeline.VertexAttributeFormat.USHORT3;
			case RGBA16_UINT -> MetalRenderPipeline.VertexAttributeFormat.USHORT4;
			case R16_SINT -> MetalRenderPipeline.VertexAttributeFormat.SHORT;
			case RG16_SINT -> MetalRenderPipeline.VertexAttributeFormat.SHORT2;
			case RGB16_SINT -> MetalRenderPipeline.VertexAttributeFormat.SHORT3;
			case RGBA16_SINT -> MetalRenderPipeline.VertexAttributeFormat.SHORT4;
			case R32_UINT -> MetalRenderPipeline.VertexAttributeFormat.UINT;
			case RG32_UINT -> MetalRenderPipeline.VertexAttributeFormat.UINT2;
			case RGB32_UINT -> MetalRenderPipeline.VertexAttributeFormat.UINT3;
			case RGBA32_UINT -> MetalRenderPipeline.VertexAttributeFormat.UINT4;
			case R32_SINT -> MetalRenderPipeline.VertexAttributeFormat.INT;
			case RG32_SINT -> MetalRenderPipeline.VertexAttributeFormat.INT2;
			case RGB32_SINT -> MetalRenderPipeline.VertexAttributeFormat.INT3;
			case RGBA32_SINT -> MetalRenderPipeline.VertexAttributeFormat.INT4;
			case R16_FLOAT -> MetalRenderPipeline.VertexAttributeFormat.HALF;
			case RG16_FLOAT -> MetalRenderPipeline.VertexAttributeFormat.HALF2;
			case RGB16_FLOAT -> MetalRenderPipeline.VertexAttributeFormat.HALF3;
			case RGBA16_FLOAT -> MetalRenderPipeline.VertexAttributeFormat.HALF4;
			case R32_FLOAT -> MetalRenderPipeline.VertexAttributeFormat.FLOAT;
			case RG32_FLOAT -> MetalRenderPipeline.VertexAttributeFormat.FLOAT2;
			case RGB32_FLOAT -> MetalRenderPipeline.VertexAttributeFormat.FLOAT3;
			case RGBA32_FLOAT -> MetalRenderPipeline.VertexAttributeFormat.FLOAT4;
			case RGB10A2_UNORM -> MetalRenderPipeline.VertexAttributeFormat.UINT1010102_NORMALIZED;
			case RG11B10_FLOAT -> MetalRenderPipeline.VertexAttributeFormat.FLOAT_RG11B10;
			case RGB10A2_UINT, D32_FLOAT, D32_FLOAT_S8_UINT, D24_UNORM_S8_UINT, D16_UNORM, S8_UINT -> throw unsupportedVertex(format);
		};
	}

	public static MetalRenderPipeline.VertexDescriptor vertexDescriptor(final VertexFormat[] bindings) {
		if (bindings == null) {
			throw new NullPointerException("bindings");
		}
		List<MetalRenderPipeline.VertexAttribute> attributes = new ArrayList<>();
		List<MetalRenderPipeline.VertexBufferLayout> layouts = new ArrayList<>();
		int location = 0;
		for (int bufferIndex = 0; bufferIndex < bindings.length; bufferIndex++) {
			VertexFormat binding = bindings[bufferIndex];
			if (binding == null) {
				continue;
			}
			int metalBufferIndex = VERTEX_BUFFER_BASE_INDEX + bufferIndex;
			layouts.add(new MetalRenderPipeline.VertexBufferLayout(metalBufferIndex, binding.getVertexSize(), binding.getStepRate()));
			for (VertexFormatElement element : binding.getElements()) {
				attributes.add(new MetalRenderPipeline.VertexAttribute(location++, metalBufferIndex, element.offset(), vertexFormat(element.format())));
			}
		}
		return new MetalRenderPipeline.VertexDescriptor(attributes, layouts);
	}

	/** Mirrors OpenGL's name-based attribute binding with explicit Vulkan input locations. */
	public static String vertexShaderWithLocations(final String source, final VertexFormat[] bindings) {
		if (source == null || bindings == null) {
			throw new NullPointerException("Vertex shader source and bindings cannot be null");
		}
		Map<String, Integer> locations = new LinkedHashMap<>();
		int location = 0;
		for (VertexFormat binding : bindings) {
			if (binding != null) {
				for (VertexFormatElement element : binding.getElements()) {
					locations.putIfAbsent(element.name(), location++);
				}
			}
		}

		Matcher matcher = VERTEX_INPUT.matcher(source);
		StringBuilder result = new StringBuilder(source.length() + locations.size() * 24);
		while (matcher.find()) {
			Integer mappedLocation = locations.get(matcher.group(3));
			if (mappedLocation == null) {
				continue;
			}
			String declaration = matcher.group(1) + "layout(location = " + mappedLocation + ") in"
				+ matcher.group(2) + matcher.group(3) + matcher.group(4);
			matcher.appendReplacement(result, Matcher.quoteReplacement(declaration));
		}
		matcher.appendTail(result);
		return result.toString();
	}

	/** Assigns a single stable resource ABI shared by both shader stages. */
	public static String shaderWithResourceBindings(final String source, final RenderPipeline pipeline) {
		if (source == null || pipeline == null) {
			throw new NullPointerException("Shader source and pipeline cannot be null");
		}
		Map<String, Integer> bindings = new LinkedHashMap<>();
		for (BindGroupLayout.UniformDescription uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
			bindings.put(uniform.name(), bindings.size());
		}
		for (String sampler : BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts())) {
			bindings.put(sampler, bindings.size());
		}

		Matcher blocks = UNIFORM_BLOCK.matcher(source);
		StringBuilder withBlocks = new StringBuilder(source.length() + bindings.size() * 24);
		while (blocks.find()) {
			Integer binding = bindings.get(blocks.group(4));
			if (binding == null) {
				continue;
			}
			String declaration = blocks.group(1) + "layout(binding = " + binding + ", " + blocks.group(2) + ")"
				+ blocks.group(3) + blocks.group(4) + blocks.group(5);
			blocks.appendReplacement(withBlocks, Matcher.quoteReplacement(declaration));
		}
		blocks.appendTail(withBlocks);

		Matcher samplers = SAMPLER_UNIFORM.matcher(withBlocks);
		StringBuilder result = new StringBuilder(withBlocks.length() + bindings.size() * 24);
		while (samplers.find()) {
			Integer binding = bindings.get(samplers.group(3));
			if (binding == null) {
				continue;
			}
			String declaration = samplers.group(1) + "layout(binding = " + binding + ") "
				+ samplers.group(2) + samplers.group(3) + samplers.group(4);
			samplers.appendReplacement(result, Matcher.quoteReplacement(declaration));
		}
		samplers.appendTail(result);
		return enableResourceBindings(result.toString());
	}

	private static String enableResourceBindings(final String source) {
		if (source.contains("GL_ARB_shading_language_420pack")) {
			return source;
		}
		int version = source.indexOf("#version");
		int lineEnd = version < 0 ? -1 : source.indexOf('\n', version);
		if (lineEnd < 0) {
			return RESOURCE_BINDING_EXTENSION + source;
		}
		return source.substring(0, lineEnd + 1) + RESOURCE_BINDING_EXTENSION + source.substring(lineEnd + 1);
	}

	public static MetalRenderPass.Primitive primitive(final PrimitiveTopology topology) {
		if (topology == null) {
			throw new NullPointerException("topology");
		}
		return switch (topology) {
			case LINES, TRIANGLES, QUADS -> MetalRenderPass.Primitive.TRIANGLE;
			case DEBUG_LINES -> MetalRenderPass.Primitive.LINE;
			case DEBUG_LINE_STRIP -> MetalRenderPass.Primitive.LINE_STRIP;
			case POINTS -> MetalRenderPass.Primitive.POINT;
			case TRIANGLE_STRIP -> MetalRenderPass.Primitive.TRIANGLE_STRIP;
			case TRIANGLE_FAN -> throw new UnsupportedOperationException("Metal has no triangle-fan primitive; Blaze3D must provide converted indices");
		};
	}

	private static List<MetalRenderPipeline.ColorTarget> colorTargets(final ColorTargetState[] states) {
		if (states == null || states.length == 0) {
			throw new IllegalArgumentException("Blaze3D pipeline has no color target states");
		}
		List<MetalRenderPipeline.ColorTarget> targets = new ArrayList<>(states.length);
		for (ColorTargetState state : states) {
			if (state == null) {
				targets.add(MetalRenderPipeline.ColorTarget.unused());
			} else {
				targets.add(new MetalRenderPipeline.ColorTarget(
					textureFormat(state.format()), state.writeMask(), state.blendFunction().map(Blaze3DMetalMappings::blendState).orElse(null)
				));
			}
		}
		return targets;
	}

	private static MetalRenderPipeline.DepthState depthState(final DepthStencilState state) {
		return state == null
			? MetalRenderPipeline.DepthState.DISABLED
			: new MetalRenderPipeline.DepthState(
				true, state.writeDepth(), compareFunction(state.depthTest()), state.depthBiasScaleFactor(), state.depthBiasConstant()
			);
	}

	private static MetalRenderPipeline.BlendState blendState(final BlendFunction function) {
		BlendEquation color = function.color();
		BlendEquation alpha = function.alpha();
		return new MetalRenderPipeline.BlendState(
			blendFactor(color.sourceFactor()), blendFactor(color.destFactor()), blendOperation(color.op()),
			blendFactor(alpha.sourceFactor()), blendFactor(alpha.destFactor()), blendOperation(alpha.op())
		);
	}

	private static MetalRenderPipeline.CompareFunction compareFunction(final CompareOp operation) {
		return switch (operation) {
			case ALWAYS_PASS -> MetalRenderPipeline.CompareFunction.ALWAYS;
			case LESS_THAN -> MetalRenderPipeline.CompareFunction.LESS;
			case LESS_THAN_OR_EQUAL -> MetalRenderPipeline.CompareFunction.LESS_EQUAL;
			case EQUAL -> MetalRenderPipeline.CompareFunction.EQUAL;
			case NOT_EQUAL -> MetalRenderPipeline.CompareFunction.NOT_EQUAL;
			case GREATER_THAN_OR_EQUAL -> MetalRenderPipeline.CompareFunction.GREATER_EQUAL;
			case GREATER_THAN -> MetalRenderPipeline.CompareFunction.GREATER;
			case NEVER_PASS -> MetalRenderPipeline.CompareFunction.NEVER;
		};
	}

	private static MetalRenderPipeline.BlendFactor blendFactor(final BlendFactor factor) {
		return switch (factor) {
			case CONSTANT_ALPHA -> MetalRenderPipeline.BlendFactor.BLEND_ALPHA;
			case CONSTANT_COLOR -> MetalRenderPipeline.BlendFactor.BLEND_COLOR;
			case DST_ALPHA -> MetalRenderPipeline.BlendFactor.DESTINATION_ALPHA;
			case DST_COLOR -> MetalRenderPipeline.BlendFactor.DESTINATION_COLOR;
			case ONE -> MetalRenderPipeline.BlendFactor.ONE;
			case ONE_MINUS_CONSTANT_ALPHA -> MetalRenderPipeline.BlendFactor.ONE_MINUS_BLEND_ALPHA;
			case ONE_MINUS_CONSTANT_COLOR -> MetalRenderPipeline.BlendFactor.ONE_MINUS_BLEND_COLOR;
			case ONE_MINUS_DST_ALPHA -> MetalRenderPipeline.BlendFactor.ONE_MINUS_DESTINATION_ALPHA;
			case ONE_MINUS_DST_COLOR -> MetalRenderPipeline.BlendFactor.ONE_MINUS_DESTINATION_COLOR;
			case ONE_MINUS_SRC_ALPHA -> MetalRenderPipeline.BlendFactor.ONE_MINUS_SOURCE_ALPHA;
			case ONE_MINUS_SRC_COLOR -> MetalRenderPipeline.BlendFactor.ONE_MINUS_SOURCE_COLOR;
			case SRC_ALPHA -> MetalRenderPipeline.BlendFactor.SOURCE_ALPHA;
			case SRC_ALPHA_SATURATE -> MetalRenderPipeline.BlendFactor.SOURCE_ALPHA_SATURATED;
			case SRC_COLOR -> MetalRenderPipeline.BlendFactor.SOURCE_COLOR;
			case ZERO -> MetalRenderPipeline.BlendFactor.ZERO;
		};
	}

	private static MetalRenderPipeline.BlendOperation blendOperation(final BlendOp operation) {
		return switch (operation) {
			case ADD -> MetalRenderPipeline.BlendOperation.ADD;
			case SUBTRACT -> MetalRenderPipeline.BlendOperation.SUBTRACT;
			case REVERSE_SUBTRACT -> MetalRenderPipeline.BlendOperation.REVERSE_SUBTRACT;
			case MIN -> MetalRenderPipeline.BlendOperation.MIN;
			case MAX -> MetalRenderPipeline.BlendOperation.MAX;
		};
	}

	private static UnsupportedOperationException unsupportedTexture(final GpuFormat format) {
		return new UnsupportedOperationException("Blaze3D texture format " + format + " has no byte-compatible Metal pixel format");
	}

	private static UnsupportedOperationException unsupportedVertex(final GpuFormat format) {
		return new UnsupportedOperationException("Blaze3D vertex format " + format + " has no Metal vertex-attribute equivalent");
	}
}
