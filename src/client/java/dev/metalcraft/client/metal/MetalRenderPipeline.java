package dev.metalcraft.client.metal;

import java.util.List;
import org.jspecify.annotations.Nullable;

/** An owned Metal render-pipeline and depth-stencil state pair. */
public final class MetalRenderPipeline implements AutoCloseable {
	public static final int WRITE_RED = 1;
	public static final int WRITE_GREEN = 2;
	public static final int WRITE_BLUE = 4;
	public static final int WRITE_ALPHA = 8;
	public static final int WRITE_ALL = WRITE_RED | WRITE_GREEN | WRITE_BLUE | WRITE_ALPHA;

	public enum VertexAttributeFormat {
		UCHAR(0), UCHAR2(1), UCHAR3(2), UCHAR4(3),
		CHAR(4), CHAR2(5), CHAR3(6), CHAR4(7),
		UCHAR_NORMALIZED(8), UCHAR2_NORMALIZED(9), UCHAR3_NORMALIZED(10), UCHAR4_NORMALIZED(11),
		CHAR_NORMALIZED(12), CHAR2_NORMALIZED(13), CHAR3_NORMALIZED(14), CHAR4_NORMALIZED(15),
		USHORT(16), USHORT2(17), USHORT3(18), USHORT4(19),
		SHORT(20), SHORT2(21), SHORT3(22), SHORT4(23),
		USHORT_NORMALIZED(24), USHORT2_NORMALIZED(25), USHORT3_NORMALIZED(26), USHORT4_NORMALIZED(27),
		SHORT_NORMALIZED(28), SHORT2_NORMALIZED(29), SHORT3_NORMALIZED(30), SHORT4_NORMALIZED(31),
		HALF(32), HALF2(33), HALF3(34), HALF4(35),
		UINT(36), UINT2(37), UINT3(38), UINT4(39),
		INT(40), INT2(41), INT3(42), INT4(43),
		FLOAT(44), FLOAT2(45), FLOAT3(46), FLOAT4(47),
		UINT1010102_NORMALIZED(48), FLOAT_RG11B10(49);

		private final int nativeCode;

		VertexAttributeFormat(final int nativeCode) {
			this.nativeCode = nativeCode;
		}

		int nativeCode() {
			return this.nativeCode;
		}
	}

	public enum CompareFunction {
		NEVER(0), LESS(1), EQUAL(2), LESS_EQUAL(3), GREATER(4), NOT_EQUAL(5), GREATER_EQUAL(6), ALWAYS(7);

		private final int nativeCode;

		CompareFunction(final int nativeCode) {
			this.nativeCode = nativeCode;
		}

		int nativeCode() {
			return this.nativeCode;
		}
	}

	public enum BlendFactor {
		ZERO(0), ONE(1), SOURCE_COLOR(2), ONE_MINUS_SOURCE_COLOR(3), SOURCE_ALPHA(4),
		ONE_MINUS_SOURCE_ALPHA(5), DESTINATION_COLOR(6), ONE_MINUS_DESTINATION_COLOR(7),
		DESTINATION_ALPHA(8), ONE_MINUS_DESTINATION_ALPHA(9), SOURCE_ALPHA_SATURATED(10),
		BLEND_COLOR(11), ONE_MINUS_BLEND_COLOR(12), BLEND_ALPHA(13), ONE_MINUS_BLEND_ALPHA(14);

		private final int nativeCode;

		BlendFactor(final int nativeCode) {
			this.nativeCode = nativeCode;
		}

		int nativeCode() {
			return this.nativeCode;
		}
	}

	public enum BlendOperation {
		ADD(0), SUBTRACT(1), REVERSE_SUBTRACT(2), MIN(3), MAX(4);

		private final int nativeCode;

		BlendOperation(final int nativeCode) {
			this.nativeCode = nativeCode;
		}

		int nativeCode() {
			return this.nativeCode;
		}
	}

	public enum CullMode {
		NONE(0), FRONT(1), BACK(2);

		private final int nativeCode;

		CullMode(final int nativeCode) {
			this.nativeCode = nativeCode;
		}

		int nativeCode() {
			return this.nativeCode;
		}
	}

	public enum FillMode {
		FILL(0), LINES(1);

		private final int nativeCode;

		FillMode(final int nativeCode) {
			this.nativeCode = nativeCode;
		}

		int nativeCode() {
			return this.nativeCode;
		}
	}

	public record VertexAttribute(int location, int bufferIndex, int offset, VertexAttributeFormat format) {
		public VertexAttribute {
			if (location < 0 || location >= 16 || bufferIndex < 0 || bufferIndex >= 31 || offset < 0) {
				throw new IllegalArgumentException("Metal vertex attribute locations, buffer indices, and offsets are out of range");
			}
			if (format == null) {
				throw new NullPointerException("format");
			}
		}
	}

	public record VertexBufferLayout(int bufferIndex, int stride, int stepRate) {
		public VertexBufferLayout {
			if (bufferIndex < 0 || bufferIndex >= 31 || stride <= 0 || stepRate < 0) {
				throw new IllegalArgumentException("Metal vertex buffer layout is out of range");
			}
		}
	}

	public record VertexDescriptor(List<VertexAttribute> attributes, List<VertexBufferLayout> layouts) {
		public static final VertexDescriptor EMPTY = new VertexDescriptor(List.of(), List.of());

		public VertexDescriptor {
			attributes = List.copyOf(attributes);
			layouts = List.copyOf(layouts);
			if (attributes.size() > 16 || layouts.size() > 16) {
				throw new IllegalArgumentException("Metal supports at most 16 vertex attributes and 16 Blaze3D vertex buffers");
			}
			boolean[] locations = new boolean[16];
			boolean[] buffers = new boolean[31];
			for (VertexBufferLayout layout : layouts) {
				if (buffers[layout.bufferIndex()]) {
					throw new IllegalArgumentException("Duplicate Metal vertex buffer layout " + layout.bufferIndex());
				}
				buffers[layout.bufferIndex()] = true;
			}
			for (VertexAttribute attribute : attributes) {
				if (locations[attribute.location()]) {
					throw new IllegalArgumentException("Duplicate Metal vertex attribute location " + attribute.location());
				}
				if (!buffers[attribute.bufferIndex()]) {
					throw new IllegalArgumentException("Metal vertex attribute references an unconfigured buffer layout");
				}
				locations[attribute.location()] = true;
			}
		}
	}

	public record BlendState(
		BlendFactor sourceColor,
		BlendFactor destinationColor,
		BlendOperation colorOperation,
		BlendFactor sourceAlpha,
		BlendFactor destinationAlpha,
		BlendOperation alphaOperation
	) {
		public BlendState {
			if (sourceColor == null || destinationColor == null || colorOperation == null
				|| sourceAlpha == null || destinationAlpha == null || alphaOperation == null) {
				throw new NullPointerException("Metal blend state fields cannot be null");
			}
		}
	}

	public record ColorTarget(MetalTexture.@Nullable Format format, int writeMask, @Nullable BlendState blendState) {
		public ColorTarget {
			if ((writeMask & ~WRITE_ALL) != 0) {
				throw new IllegalArgumentException("Metal color write mask contains unknown bits");
			}
			if (format == null) {
				if (writeMask != 0 || blendState != null) {
					throw new IllegalArgumentException("An unused Metal color target cannot write or blend");
				}
			} else if (!format.hasColorAspect()) {
				throw new IllegalArgumentException("A Metal color target requires a color format");
			}
		}

		public static ColorTarget opaque(final MetalTexture.Format format) {
			return new ColorTarget(format, WRITE_ALL, null);
		}

		public static ColorTarget unused() {
			return new ColorTarget(null, 0, null);
		}
	}

	public record DepthState(
		boolean testEnabled,
		boolean writeEnabled,
		CompareFunction compareFunction,
		float biasSlopeScale,
		float biasConstant
	) {
		public static final DepthState DISABLED = new DepthState(false, false, CompareFunction.ALWAYS, 0.0F, 0.0F);

		public DepthState {
			if (compareFunction == null) {
				throw new NullPointerException("compareFunction");
			}
			if (!Float.isFinite(biasSlopeScale) || !Float.isFinite(biasConstant)) {
				throw new IllegalArgumentException("Metal depth bias values must be finite");
			}
			if (!testEnabled && writeEnabled) {
				throw new IllegalArgumentException("Metal cannot write depth when the pipeline has no depth state");
			}
		}
	}

	/**
	 * The primitive class a pipeline will be drawn with.
	 *
	 * <p>Metal infers this from the draw call unless the vertex stage writes
	 * {@code [[render_target_array_index]]}, in which case the pipeline has to declare it up front,
	 * because the layer is resolved before the primitive is assembled.
	 */
	public enum TopologyClass {
		UNSPECIFIED,
		POINT,
		LINE,
		TRIANGLE;

		public int nativeCode() {
			return this.ordinal();
		}
	}

	public record RasterState(CullMode cullMode, FillMode fillMode, TopologyClass topologyClass) {
		public static final RasterState DEFAULT = new RasterState(CullMode.NONE, FillMode.FILL, TopologyClass.UNSPECIFIED);

		public RasterState(final CullMode cullMode, final FillMode fillMode) {
			this(cullMode, fillMode, TopologyClass.UNSPECIFIED);
		}

		public RasterState {
			if (cullMode == null || fillMode == null || topologyClass == null) {
				throw new NullPointerException("Metal raster state fields cannot be null");
			}
		}
	}

	public record Descriptor(
		String vertexSource,
		String vertexFunction,
		String fragmentSource,
		String fragmentFunction,
		List<ColorTarget> colorTargets,
		MetalTexture.@Nullable Format depthStencilFormat,
		VertexDescriptor vertexDescriptor,
		DepthState depthState,
		RasterState rasterState
	) {
		public Descriptor(
			final String vertexSource,
			final String vertexFunction,
			final String fragmentSource,
			final String fragmentFunction,
			final MetalTexture.Format colorFormat,
			final MetalTexture.@Nullable Format depthFormat
		) {
			this(
				vertexSource, vertexFunction, fragmentSource, fragmentFunction,
				List.of(ColorTarget.opaque(colorFormat)), depthFormat, VertexDescriptor.EMPTY,
				depthFormat == null ? DepthState.DISABLED : new DepthState(true, true, CompareFunction.GREATER_EQUAL, 0.0F, 0.0F),
				RasterState.DEFAULT
			);
		}

		public Descriptor(
			final String source,
			final String vertexFunction,
			final String fragmentFunction,
			final MetalTexture.Format colorFormat,
			final MetalTexture.@Nullable Format depthFormat
		) {
			this(source, vertexFunction, source, fragmentFunction, colorFormat, depthFormat);
		}

		public Descriptor {
			if (vertexSource == null || vertexSource.isBlank()) {
				throw new IllegalArgumentException("A Metal render pipeline requires vertex MSL source");
			}
			if (vertexFunction == null || vertexFunction.isBlank()) {
				throw new IllegalArgumentException("A Metal render pipeline requires a vertex function name");
			}
			if (fragmentSource == null || fragmentSource.isBlank()) {
				throw new IllegalArgumentException("A Metal render pipeline requires fragment MSL source");
			}
			if (fragmentFunction == null || fragmentFunction.isBlank()) {
				throw new IllegalArgumentException("A Metal render pipeline requires a fragment function name");
			}
			colorTargets = List.copyOf(colorTargets);
			if (colorTargets.isEmpty() || colorTargets.size() > 8) {
				throw new IllegalArgumentException("A Metal render pipeline requires between one and eight color target slots");
			}
			if (depthStencilFormat != null && !depthStencilFormat.hasDepthAspect() && !depthStencilFormat.hasStencilAspect()) {
				throw new IllegalArgumentException("The Metal depth-stencil format has no depth or stencil aspect");
			}
			if (vertexDescriptor == null || depthState == null || rasterState == null) {
				throw new NullPointerException("Metal pipeline descriptor state cannot be null");
			}
			if (depthState.testEnabled() && (depthStencilFormat == null || !depthStencilFormat.hasDepthAspect())) {
				throw new IllegalArgumentException("Enabled Metal depth state requires a depth-capable attachment format");
			}
		}

		public MetalTexture.@Nullable Format colorFormat() {
			return this.colorTargets.getFirst().format();
		}

		public MetalTexture.@Nullable Format depthFormat() {
			return this.depthStencilFormat;
		}
	}

	/** GLSL sources that are translated before the native Metal pipeline is compiled. */
	public record GlslDescriptor(
		String vertexSource,
		String vertexSourceName,
		String fragmentSource,
		String fragmentSourceName,
		MetalTexture.Format colorFormat,
		MetalTexture.@Nullable Format depthFormat
	) {
		public GlslDescriptor(
			final String vertexSource,
			final String fragmentSource,
			final MetalTexture.Format colorFormat,
			final MetalTexture.@Nullable Format depthFormat
		) {
			this(vertexSource, "vertex.glsl", fragmentSource, "fragment.glsl", colorFormat, depthFormat);
		}

		public GlslDescriptor {
			if (vertexSource == null || vertexSource.isBlank() || fragmentSource == null || fragmentSource.isBlank()) {
				throw new IllegalArgumentException("A GLSL render pipeline requires non-blank vertex and fragment sources");
			}
			if (vertexSourceName == null || vertexSourceName.isBlank() || fragmentSourceName == null || fragmentSourceName.isBlank()) {
				throw new IllegalArgumentException("A GLSL render pipeline requires source names for diagnostics");
			}
			if (colorFormat == null || !colorFormat.hasColorAspect()) {
				throw new IllegalArgumentException("A Metal render pipeline requires a color-renderable color format");
			}
			if (depthFormat != null && !depthFormat.hasDepthAspect()) {
				throw new IllegalArgumentException("The Metal pipeline depth format must have a depth aspect");
			}
		}
	}

	private final MetalDevice device;
	private final Descriptor descriptor;
	private long handle;

	MetalRenderPipeline(final MetalDevice device, final long handle, final Descriptor descriptor) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal render-pipeline handle cannot be zero");
		}
		this.device = device;
		this.handle = handle;
		this.descriptor = descriptor;
	}

	public MetalDevice device() {
		return this.device;
	}

	public Descriptor descriptor() {
		return this.descriptor;
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	@Override
	public void close() {
		synchronized (this) {
			if (this.handle == 0L) {
				return;
			}
			MetalNative.nReleaseRenderPipeline(this.handle);
			this.handle = 0L;
		}
		this.device.forget(this);
	}

	synchronized long requireOpenHandle() {
		if (this.handle == 0L) {
			throw new IllegalStateException("Metal render pipeline is closed");
		}
		return this.handle;
	}
}
