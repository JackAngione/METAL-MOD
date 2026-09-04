package dev.metalcraft.client.metal;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** A scoped {@code MTLRenderCommandEncoder}; closing it ends the render pass. */
public final class MetalRenderPass implements AutoCloseable {
	/**
	 * Bits naming the shader stages a resource binding applies to.
	 *
	 * <p>Metal holds one argument table per stage, so a binding costs an encoder call per stage it
	 * is made for. These let a caller that knows which stages read a slot pay only for those.
	 */
	public static final int STAGE_VERTEX = 1;
	public static final int STAGE_FRAGMENT = 2;
	public static final int STAGE_ALL = STAGE_VERTEX | STAGE_FRAGMENT;
	/** Buffers and textures a Metal argument table addresses. */
	public static final int RESOURCE_SLOTS = 16;
	/** Vertex-buffer binding points a Metal render encoder addresses. */
	public static final int VERTEX_BUFFER_SLOTS = 31;
	/** Color attachments one render pass can write, matching Metal's and Blaze3D's own limit. */
	public static final int MAX_COLOR_ATTACHMENTS = 8;
	/**
	 * Layout of the per-attachment field array that crosses JNI with a pass descriptor.
	 *
	 * <p>This order is load-bearing: native {@code MC_COLOR_FIELD_*} must match.
	 */
	static final int COLOR_FIELDS = 5;
	static final int COLOR_FIELD_IS_DRAWABLE = 0;
	static final int COLOR_FIELD_MIP_LEVEL = 1;
	static final int COLOR_FIELD_LOAD_ACTION = 2;
	static final int COLOR_FIELD_STORE_ACTION = 3;
	static final int COLOR_FIELD_ARRAY_SLICE = 4;
	/** Clear components per color attachment, in red, green, blue, alpha order. */
	static final int COLOR_CLEAR_COMPONENTS = 4;

	public enum LoadAction {
		LOAD,
		CLEAR,
		DONT_CARE
	}

	public enum StoreAction {
		STORE,
		DONT_CARE
	}

	public enum Primitive {
		POINT,
		LINE,
		LINE_STRIP,
		TRIANGLE,
		TRIANGLE_STRIP
	}

	public enum IndexType {
		UINT16(2),
		UINT32(4);

		/** Package-private so {@link MetalCommandStream} can align the offsets it records. */
		final int bytes;

		IndexType(final int bytes) {
			this.bytes = bytes;
		}
	}

	public sealed interface ColorTarget permits MetalDrawable, MetalTexture {
	}

	/** @return the slices a color target addresses: array layers, a cubemap's faces, or one image */
	private static int sliceCount(final ColorTarget target) {
		return target instanceof MetalTexture texture ? texture.descriptor().sliceCount() : 1;
	}

	/**
	 * A memoryless attachment exists only for the duration of its pass, so there is nothing on the
	 * way in for a load to read and nowhere on the way out for a store to write.
	 */
	private static void validateMemorylessActions(final LoadAction loadAction, final StoreAction storeAction) {
		if (loadAction == LoadAction.LOAD) {
			throw new IllegalArgumentException("A memoryless Metal attachment has no previous contents to load");
		}
		if (storeAction != StoreAction.DONT_CARE) {
			throw new IllegalArgumentException("A memoryless Metal attachment cannot be stored");
		}
	}

	public record ColorAttachment(
		ColorTarget target,
		int mipLevel,
		int arraySlice,
		LoadAction loadAction,
		StoreAction storeAction,
		double clearRed,
		double clearGreen,
		double clearBlue,
		double clearAlpha
	) {
		public ColorAttachment {
			if (target == null) {
				throw new NullPointerException("target");
			}
			if (target instanceof MetalTexture texture && !texture.descriptor().format().hasColorAspect()) {
				throw new IllegalArgumentException("A depth/stencil texture cannot be used as a Metal color attachment");
			}
			if (mipLevel < 0 || target instanceof MetalDrawable && mipLevel != 0
				|| target instanceof MetalTexture texture && mipLevel >= texture.descriptor().mipLevels()) {
				throw new IllegalArgumentException("Metal color attachment mip level is out of bounds");
			}
			if (arraySlice < 0 || arraySlice >= sliceCount(target)) {
				throw new IllegalArgumentException("Metal color attachment array slice is out of bounds");
			}
			if (loadAction == null || storeAction == null) {
				throw new NullPointerException("Metal color attachment actions cannot be null");
			}
			if (target instanceof MetalTexture memoryless && memoryless.isMemoryless()) {
				validateMemorylessActions(loadAction, storeAction);
			}
			validateClearComponent(clearRed);
			validateClearComponent(clearGreen);
			validateClearComponent(clearBlue);
			validateClearComponent(clearAlpha);
		}

		public ColorAttachment(
			final ColorTarget target,
			final int mipLevel,
			final LoadAction loadAction,
			final StoreAction storeAction,
			final double clearRed,
			final double clearGreen,
			final double clearBlue,
			final double clearAlpha
		) {
			this(target, mipLevel, 0, loadAction, storeAction, clearRed, clearGreen, clearBlue, clearAlpha);
		}

		public ColorAttachment(
			final ColorTarget target,
			final LoadAction loadAction,
			final StoreAction storeAction,
			final double clearRed,
			final double clearGreen,
			final double clearBlue,
			final double clearAlpha
		) {
			this(target, 0, 0, loadAction, storeAction, clearRed, clearGreen, clearBlue, clearAlpha);
		}

		public static ColorAttachment clear(final MetalDrawable drawable, final double red, final double green, final double blue, final double alpha) {
			return new ColorAttachment(drawable, 0, 0, LoadAction.CLEAR, StoreAction.STORE, red, green, blue, alpha);
		}

		public static ColorAttachment clear(final MetalTexture texture, final double red, final double green, final double blue, final double alpha) {
			return new ColorAttachment(texture, 0, 0, LoadAction.CLEAR, StoreAction.STORE, red, green, blue, alpha);
		}

		private static void validateClearComponent(final double value) {
			if (!Double.isFinite(value)) {
				throw new IllegalArgumentException("Metal clear-color components must be finite");
			}
		}
	}

	public record DepthAttachment(
		MetalTexture texture,
		int mipLevel,
		int arraySlice,
		LoadAction loadAction,
		StoreAction storeAction,
		double clearDepth
	) {
		public DepthAttachment {
			if (texture == null || loadAction == null || storeAction == null) {
				throw new NullPointerException("Metal depth attachment values cannot be null");
			}
			if (!texture.descriptor().format().hasDepthAspect()) {
				throw new IllegalArgumentException("A Metal depth attachment requires a depth texture");
			}
			if (mipLevel < 0 || mipLevel >= texture.descriptor().mipLevels()) {
				throw new IllegalArgumentException("Metal depth attachment mip level is out of bounds");
			}
			if (arraySlice < 0 || arraySlice >= texture.descriptor().sliceCount()) {
				throw new IllegalArgumentException("Metal depth attachment array slice is out of bounds");
			}
			if (!Double.isFinite(clearDepth) || clearDepth < 0.0 || clearDepth > 1.0) {
				throw new IllegalArgumentException("Metal clear depth must be between zero and one");
			}
			if (texture.isMemoryless()) {
				validateMemorylessActions(loadAction, storeAction);
			}
		}

		public DepthAttachment(
			final MetalTexture texture,
			final int mipLevel,
			final LoadAction loadAction,
			final StoreAction storeAction,
			final double clearDepth
		) {
			this(texture, mipLevel, 0, loadAction, storeAction, clearDepth);
		}

		public DepthAttachment(
			final MetalTexture texture,
			final LoadAction loadAction,
			final StoreAction storeAction,
			final double clearDepth
		) {
			this(texture, 0, 0, loadAction, storeAction, clearDepth);
		}
	}

	/**
	 * The attachments one pass writes.
	 *
	 * <p>The color list is positional: entry {@code n} is {@code colorAttachments[n]} in the Metal
	 * descriptor and {@code [[color(n)]]} in the fragment shader. An entry may be null, meaning the
	 * layout reserves that index but this pass attaches nothing to it.
	 */
	public record Descriptor(
		List<@Nullable ColorAttachment> colorAttachments,
		DepthAttachment depthAttachment,
		int renderTargetArrayLength
	) {
		public Descriptor {
			colorAttachments = Collections.unmodifiableList(new ArrayList<>(colorAttachments));
			if (colorAttachments.size() > MAX_COLOR_ATTACHMENTS) {
				throw new IllegalArgumentException(
					"A Metal render pass accepts at most " + MAX_COLOR_ATTACHMENTS
						+ " color attachments, not " + colorAttachments.size()
				);
			}
			if (depthAttachment == null && colorAttachments.stream().allMatch(attachment -> attachment == null)) {
				throw new IllegalArgumentException("A Metal render pass requires at least one attachment");
			}
			if (renderTargetArrayLength < 0) {
				throw new IllegalArgumentException("A layered Metal render pass cannot have a negative layer count");
			}
			if (renderTargetArrayLength > 0) {
				for (ColorAttachment attachment : colorAttachments) {
					if (attachment == null) {
						continue;
					}
					requireLayered(sliceCount(attachment.target()), attachment.arraySlice(), renderTargetArrayLength, "color");
				}
				if (depthAttachment != null) {
					requireLayered(
						depthAttachment.texture().descriptor().sliceCount(),
						depthAttachment.arraySlice(),
						renderTargetArrayLength,
						"depth"
					);
				}
			}
		}

		private static void requireLayered(final int slices, final int slice, final int layers, final String kind) {
			if (slice != 0) {
				throw new IllegalArgumentException("A layered Metal render pass cannot also select a single " + kind + " slice");
			}
			if (slices < layers) {
				throw new IllegalArgumentException(
					"A layered Metal render pass over " + layers + " layers needs a " + kind
						+ " attachment with at least that many slices, not " + slices
				);
			}
		}

		public static Descriptor depthOnly(final DepthAttachment depthAttachment) {
			if (depthAttachment == null) {
				throw new NullPointerException("depthAttachment");
			}
			return new Descriptor(List.of(), depthAttachment, 0);
		}

		public Descriptor(final List<@Nullable ColorAttachment> colorAttachments, final DepthAttachment depthAttachment) {
			this(colorAttachments, depthAttachment, 0);
		}

		public Descriptor(final ColorAttachment colorAttachment, final DepthAttachment depthAttachment) {
			this(colorAttachment == null ? List.of() : List.of(colorAttachment), depthAttachment, 0);
		}

		public Descriptor(final ColorAttachment colorAttachment) {
			this(colorAttachment, null);
		}

		/** @return whether every primitive picks its own target layer in the vertex stage */
		public boolean isLayered() {
			return this.renderTargetArrayLength > 0;
		}

		/** The first (or only) color attachment, for vanilla 1-color callers. */
		public @Nullable ColorAttachment colorAttachment() {
			return this.colorAttachments.isEmpty() ? null : this.colorAttachments.get(0);
		}

		/** @return the attachment at {@code index}, or null when the pass leaves that index empty */
		public @Nullable ColorAttachment colorAttachment(final int index) {
			return index < this.colorAttachments.size() ? this.colorAttachments.get(index) : null;
		}
	}

	private final MetalCommandBuffer commandBuffer;
	/** Attachment formats by index; a null entry is an index the pass leaves empty. */
	private final List<MetalTexture.@Nullable Format> colorFormats;
	private final MetalTexture.Format depthFormat;
	private long handle;
	private boolean pipelineBound;

	MetalRenderPass(final MetalCommandBuffer commandBuffer, final long handle, final Descriptor descriptor) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal render-pass handle cannot be zero");
		}
		this.commandBuffer = commandBuffer;
		List<MetalTexture.@Nullable Format> formats = new ArrayList<>(descriptor.colorAttachments().size());
		for (ColorAttachment attachment : descriptor.colorAttachments()) {
			formats.add(attachment == null
				? null
				: attachment.target() instanceof MetalTexture texture
					? texture.descriptor().format()
					: MetalTexture.Format.BGRA8_UNORM);
		}
		this.colorFormats = Collections.unmodifiableList(formats);
		this.depthFormat = descriptor.depthAttachment() == null
			? null
			: descriptor.depthAttachment().texture().descriptor().format();
		this.handle = handle;
	}

	public synchronized void setPipeline(final MetalRenderPipeline pipeline) {
		if (pipeline == null) {
			throw new NullPointerException("pipeline");
		}
		List<MetalRenderPipeline.ColorTarget> targets = pipeline.descriptor().colorTargets();
		if (pipeline.descriptor().depthStencilFormat() != this.depthFormat) {
			throw new IllegalArgumentException(
				"Metal render pipeline depth format " + pipeline.descriptor().depthStencilFormat()
					+ " does not match the render pass's " + this.depthFormat
			);
		}
		for (int index = 0; index < targets.size(); index++) {
			MetalTexture.Format attachmentFormat = index < this.colorFormats.size() ? this.colorFormats.get(index) : null;
			if (targets.get(index).format() != attachmentFormat) {
				throw new IllegalArgumentException(
					"Metal render pipeline color target " + index + " is " + targets.get(index).format()
						+ " but the render pass attaches " + attachmentFormat
				);
			}
		}
		for (int index = targets.size(); index < this.colorFormats.size(); index++) {
			if (this.colorFormats.get(index) != null) {
				throw new IllegalArgumentException(
					"Metal render pass attaches color target " + index + " but the pipeline declares only " + targets.size()
				);
			}
		}
		MetalNative.nSetRenderPipeline(this.requireOpenHandle(), pipeline.requireOpenHandle());
		this.pipelineBound = true;
	}

	public synchronized void setScissor(final int x, final int y, final int width, final int height) {
		if (x < 0 || y < 0 || width <= 0 || height <= 0) {
			throw new IllegalArgumentException("A Metal scissor rectangle requires a non-negative origin and positive size");
		}
		MetalNative.nSetScissor(this.requireOpenHandle(), x, y, width, height);
	}

	public synchronized void setVertexBuffer(final int index, final MetalBuffer buffer, final long offset) {
		if (index < 0 || index >= VERTEX_BUFFER_SLOTS) {
			throw new IllegalArgumentException("Metal vertex buffer index must be between 0 and " + (VERTEX_BUFFER_SLOTS - 1));
		}
		MetalBuffer.checkRange(buffer.size(), offset, 1L, "vertex binding");
		MetalNative.nSetVertexBuffer(this.requireOpenHandle(), index, buffer.requireOpenHandle(), offset);
	}

	public synchronized void setUniformBuffer(final int index, final MetalBuffer buffer, final long offset, final int stages) {
		if (index < 0 || index >= RESOURCE_SLOTS) {
			throw new IllegalArgumentException("Metal uniform buffer index must be between 0 and 15");
		}
		if (checkedStages(stages) == 0) {
			return;
		}
		MetalBuffer.checkRange(buffer.size(), offset, 1L, "uniform binding");
		MetalNative.nSetUniformBuffer(this.requireOpenHandle(), index, buffer.requireOpenHandle(), offset, stages);
	}

	public synchronized void setTexelBuffer(
		final int index,
		final MetalBuffer buffer,
		final long offset,
		final long length,
		final MetalTexture.Format format,
		final int stages
	) {
		if (index < 0 || index >= RESOURCE_SLOTS) {
			throw new IllegalArgumentException("Metal texel-buffer binding index must be between 0 and 15");
		}
		if (checkedStages(stages) == 0) {
			return;
		}
		MetalBuffer.checkRange(buffer.size(), offset, length, "texel-buffer binding");
		if (!format.hasColorAspect() || length % format.bytesPerPixel() != 0L) {
			throw new IllegalArgumentException("Metal texel-buffer format must evenly cover the bound buffer range");
		}
		MetalNative.nSetTexelBuffer(
			this.requireOpenHandle(), index, buffer.requireOpenHandle(), offset, length, format.nativeCode(), stages
		);
	}

	public synchronized void setTexture(final int index, final MetalTextureView textureView, final int stages) {
		if (index < 0 || index >= RESOURCE_SLOTS) {
			throw new IllegalArgumentException("Metal texture index must be between 0 and 15");
		}
		if (checkedStages(stages) == 0) {
			return;
		}
		MetalNative.nSetTexture(this.requireOpenHandle(), index, textureView.requireOpenHandle(), stages);
	}

	public synchronized void setSampler(final int index, final MetalSampler sampler, final int stages) {
		if (index < 0 || index >= RESOURCE_SLOTS) {
			throw new IllegalArgumentException("Metal sampler index must be between 0 and 15");
		}
		if (checkedStages(stages) == 0) {
			return;
		}
		MetalNative.nSetSampler(this.requireOpenHandle(), index, sampler.requireOpenHandle(), stages);
	}

	/** Package-private because {@link MetalCommandStream} records the same masks. */
	static int checkedStages(final int stages) {
		if ((stages & ~STAGE_ALL) != 0) {
			throw new IllegalArgumentException("Metal resource bindings apply to the vertex and fragment stages only");
		}
		return stages;
	}

	public synchronized void writeTimestamp(final MetalTimestampQueryPool pool, final int index) {
		if (pool == null) {
			throw new NullPointerException("pool");
		}
		if (pool.device() != this.commandBuffer.commandQueue().device()) {
			throw new IllegalArgumentException("Metal timestamp query pool and render pass must belong to the same device");
		}
		if (!pool.device().supportsRenderTimestampQueries()) {
			throw new UnsupportedOperationException("The selected Metal device does not support timestamps within a render pass");
		}
		if (index < 0 || index >= pool.size()) {
			throw new IndexOutOfBoundsException("Metal timestamp query index is out of bounds");
		}
		MetalNative.nWriteRenderTimestamp(this.requireOpenHandle(), pool.requireOpenHandle(), index);
	}

	public synchronized void draw(
		final Primitive primitive,
		final int vertexStart,
		final int vertexCount,
		final int instanceCount,
		final int baseInstance
	) {
		this.validateDraw(primitive, vertexCount, instanceCount);
		if (vertexStart < 0 || baseInstance < 0) {
			throw new IllegalArgumentException("Metal draw offsets cannot be negative");
		}
		if (vertexCount == 0 || instanceCount == 0) {
			return;
		}
		MetalNative.nDraw(this.requireOpenHandle(), primitive.ordinal(), vertexStart, vertexCount, instanceCount, baseInstance);
	}

	public synchronized void draw(final Primitive primitive, final int vertexStart, final int vertexCount) {
		this.draw(primitive, vertexStart, vertexCount, 1, 0);
	}

	public synchronized void drawIndexed(
		final Primitive primitive,
		final MetalBuffer indexBuffer,
		final long indexBufferOffset,
		final IndexType indexType,
		final int indexCount,
		final int instanceCount,
		final int baseVertex,
		final int baseInstance
	) {
		this.validateDraw(primitive, indexCount, instanceCount);
		if (indexType == null) {
			throw new NullPointerException("indexType");
		}
		if (baseInstance < 0 || indexBufferOffset % indexType.bytes != 0L) {
			throw new IllegalArgumentException("Metal index-buffer offsets must be aligned and base instance cannot be negative");
		}
		if (indexCount == 0 || instanceCount == 0) {
			return;
		}
		long indexBytes = Math.multiplyExact((long)indexCount, indexType.bytes);
		MetalBuffer.checkRange(indexBuffer.size(), indexBufferOffset, indexBytes, "index binding");
		MetalNative.nDrawIndexed(
			this.requireOpenHandle(),
			primitive.ordinal(),
			indexBuffer.requireOpenHandle(),
			indexBufferOffset,
			indexType.ordinal(),
			indexCount,
			instanceCount,
			baseVertex,
			baseInstance
		);
	}

	public synchronized void multiDraw(
		final Primitive primitive,
		final IntBuffer drawParameters,
		final int instanceCount,
		final int firstInstance,
		final int drawCount
	) {
		this.validateBatch(primitive, instanceCount, firstInstance, drawCount);
		int[] parameters = copy(drawParameters, Math.multiplyExact(drawCount, 2), "drawParameters");
		int[] firstVertices = new int[drawCount];
		int[] vertexCounts = new int[drawCount];
		for (int draw = 0; draw < drawCount; draw++) {
			firstVertices[draw] = parameters[draw * 2];
			vertexCounts[draw] = parameters[draw * 2 + 1];
		}
		this.multiDraw(primitive, firstVertices, vertexCounts, instanceCount, firstInstance);
	}

	public synchronized void multiDraw(
		final Primitive primitive,
		final IntBuffer firstVertices,
		final IntBuffer vertexCounts,
		final int drawCount
	) {
		this.validateBatch(primitive, 1, 0, drawCount);
		this.multiDraw(
			primitive,
			copy(firstVertices, drawCount, "firstVertices"),
			copy(vertexCounts, drawCount, "vertexCounts"),
			1,
			0
		);
	}

	public synchronized void multiDrawIndexed(
		final Primitive primitive,
		final MetalBuffer indexBuffer,
		final IndexType indexType,
		final IntBuffer drawParameters,
		final int instanceCount,
		final int firstInstance,
		final int drawCount
	) {
		this.validateBatch(primitive, instanceCount, firstInstance, drawCount);
		if (indexType == null) {
			throw new NullPointerException("indexType");
		}
		int[] parameters = copy(drawParameters, Math.multiplyExact(drawCount, 3), "drawParameters");
		long[] indexBufferOffsets = new long[drawCount];
		int[] indexCounts = new int[drawCount];
		int[] baseVertices = new int[drawCount];
		for (int draw = 0; draw < drawCount; draw++) {
			indexBufferOffsets[draw] = Math.multiplyExact((long)parameters[draw * 3], indexType.bytes);
			indexCounts[draw] = parameters[draw * 3 + 1];
			baseVertices[draw] = parameters[draw * 3 + 2];
		}
		this.multiDrawIndexed(
			primitive,
			indexBuffer,
			indexType,
			indexBufferOffsets,
			indexCounts,
			baseVertices,
			instanceCount,
			firstInstance
		);
	}

	public synchronized void multiDrawIndexed(
		final Primitive primitive,
		final MetalBuffer indexBuffer,
		final IndexType indexType,
		final LongBuffer indexBufferOffsets,
		final IntBuffer indexCounts,
		final IntBuffer baseVertices,
		final int drawCount
	) {
		this.validateBatch(primitive, 1, 0, drawCount);
		this.multiDrawIndexed(
			primitive,
			indexBuffer,
			indexType,
			copy(indexBufferOffsets, drawCount, "indexBufferOffsets"),
			copy(indexCounts, drawCount, "indexCounts"),
			copy(baseVertices, drawCount, "baseVertices"),
			1,
			0
		);
	}

	public synchronized void drawIndirect(
		final Primitive primitive,
		final MetalBuffer commands,
		final long commandsOffset,
		final int drawCount
	) {
		this.validateBatch(primitive, 1, 0, drawCount);
		if (commandsOffset % 4L != 0L) {
			throw new IllegalArgumentException("Metal indirect-command offsets must be 4-byte aligned");
		}
		if (drawCount == 0) {
			return;
		}
		MetalBuffer.checkRange(commands.size(), commandsOffset, Math.multiplyExact((long)drawCount, 16L), "indirect commands");
		MetalNative.nDrawIndirect(this.requireOpenHandle(), primitive.ordinal(), commands.requireOpenHandle(), commandsOffset, drawCount);
	}

	public synchronized void drawIndexedIndirect(
		final Primitive primitive,
		final MetalBuffer indexBuffer,
		final IndexType indexType,
		final MetalBuffer commands,
		final long commandsOffset,
		final int drawCount
	) {
		this.validateBatch(primitive, 1, 0, drawCount);
		if (indexType == null) {
			throw new NullPointerException("indexType");
		}
		if (commandsOffset % 4L != 0L) {
			throw new IllegalArgumentException("Metal indirect-command offsets must be 4-byte aligned");
		}
		if (drawCount == 0) {
			return;
		}
		MetalBuffer.checkRange(commands.size(), commandsOffset, Math.multiplyExact((long)drawCount, 20L), "indexed indirect commands");
		MetalNative.nDrawIndexedIndirect(
			this.requireOpenHandle(),
			primitive.ordinal(),
			indexBuffer.requireOpenHandle(),
			indexType.ordinal(),
			commands.requireOpenHandle(),
			commandsOffset,
			drawCount
		);
	}

	/**
	 * Replays a recorded batch of binds and draws into this pass, in one crossing.
	 *
	 * <p>The batch encodes exactly what the setters above would have encoded, in the order it was
	 * recorded, so this is a cheaper spelling of the same commands rather than a different one. What
	 * it does not carry is the per-command Java monitor and JNI call: everything a batch names is
	 * resolved and pinned together on the far side.
	 *
	 * @see MetalCommandStream
	 */
	public synchronized void submit(final MetalCommandStream commands) {
		if (commands == null) {
			throw new NullPointerException("commands");
		}
		if (commands.commandCount() == 0) {
			return;
		}
		long handle = this.requireOpenHandle();
		this.requirePipeline();
		MetalNative.nSubmitCommandStream(handle, commands.prepared(), commands.byteCount());
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
			MetalNative.nEndRenderPass(this.handle);
			this.handle = 0L;
		}
		this.commandBuffer.forget(this);
	}

	private void validateDraw(final Primitive primitive, final int elementCount, final int instanceCount) {
		this.requireOpenHandle();
		if (primitive == null) {
			throw new NullPointerException("primitive");
		}
		if (!this.pipelineBound) {
			throw new IllegalStateException("Bind a Metal render pipeline before drawing");
		}
		if (elementCount < 0 || instanceCount < 0) {
			throw new IllegalArgumentException("Metal draw counts cannot be negative");
		}
	}

	private void validateBatch(
		final Primitive primitive,
		final int instanceCount,
		final int firstInstance,
		final int drawCount
	) {
		this.requireOpenHandle();
		if (primitive == null) {
			throw new NullPointerException("primitive");
		}
		this.requirePipeline();
		if (instanceCount < 0 || firstInstance < 0 || drawCount < 0) {
			throw new IllegalArgumentException("Metal multi-draw counts and offsets cannot be negative");
		}
	}

	private void multiDraw(
		final Primitive primitive,
		final int[] firstVertices,
		final int[] vertexCounts,
		final int instanceCount,
		final int firstInstance
	) {
		if (firstVertices.length == 0 || instanceCount == 0) {
			return;
		}
		MetalNative.nMultiDraw(
			this.requireOpenHandle(), primitive.ordinal(), firstVertices, vertexCounts, instanceCount, firstInstance
		);
	}

	private void multiDrawIndexed(
		final Primitive primitive,
		final MetalBuffer indexBuffer,
		final IndexType indexType,
		final long[] indexBufferOffsets,
		final int[] indexCounts,
		final int[] baseVertices,
		final int instanceCount,
		final int firstInstance
	) {
		if (indexType == null) {
			throw new NullPointerException("indexType");
		}
		for (int draw = 0; draw < indexBufferOffsets.length; draw++) {
			long offset = indexBufferOffsets[draw];
			int count = indexCounts[draw];
			if (offset < 0L || offset % indexType.bytes != 0L || count < 0) {
				throw new IllegalArgumentException("Metal multi-draw index offsets must be aligned and counts cannot be negative");
			}
			if (count > 0) {
				MetalBuffer.checkRange(indexBuffer.size(), offset, Math.multiplyExact((long)count, indexType.bytes), "multi-draw index binding");
			}
		}
		if (indexBufferOffsets.length == 0 || instanceCount == 0) {
			return;
		}
		MetalNative.nMultiDrawIndexed(
			this.requireOpenHandle(),
			primitive.ordinal(),
			indexBuffer.requireOpenHandle(),
			indexType.ordinal(),
			indexBufferOffsets,
			indexCounts,
			baseVertices,
			instanceCount,
			firstInstance
		);
	}

	private void requirePipeline() {
		if (!this.pipelineBound) {
			throw new IllegalStateException("Bind a Metal render pipeline before drawing");
		}
	}

	private static int[] copy(final IntBuffer source, final int count, final String name) {
		if (source == null) {
			throw new NullPointerException(name);
		}
		if (source.remaining() < count) {
			throw new IllegalArgumentException(name + " does not contain enough values for the requested draw count");
		}
		int[] values = new int[count];
		source.duplicate().get(values);
		return values;
	}

	private static long[] copy(final LongBuffer source, final int count, final String name) {
		if (source == null) {
			throw new NullPointerException(name);
		}
		if (source.remaining() < count) {
			throw new IllegalArgumentException(name + " does not contain enough values for the requested draw count");
		}
		long[] values = new long[count];
		source.duplicate().get(values);
		return values;
	}

	private long requireOpenHandle() {
		if (this.handle == 0L) {
			throw new IllegalStateException("Metal render pass is closed");
		}
		return this.handle;
	}
}
