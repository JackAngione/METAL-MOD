package dev.metalcraft.client.metal;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.IntBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/** Blaze3D render-pass adapter translating named bindings and draw state to Metal slots. */
final class MetalRenderPassBackend implements RenderPassBackend {
	/**
	 * Metal's shader argument tables hold sixteen buffers and sixteen textures, and the pass rejects
	 * anything above that, so the caches below can be flat arrays rather than maps.
	 */
	private static final int RESOURCE_SLOTS = 16;
	/**
	 * Sized so the name maps never rehash in practice. A pass binds a handful of uniforms and
	 * textures, and the instance outlives the pass, so the tables are paid for once per encoder
	 * instead of once per pass.
	 */
	private static final int NAME_MAP_CAPACITY = 32;
	/**
	 * Whether a multi-draw records its binds and draws and submits them together.
	 *
	 * <p>A kill switch rather than a setting. Turning batching off restores the per-command path
	 * exactly, which is what makes the two comparable back to back in one session - the only
	 * comparison this renderer's 8-10% run-to-run spread admits.
	 */
	private static final boolean BATCHING = Boolean.parseBoolean(System.getProperty("metalcraft.commandBatching", "true"));

	private final MetalGpuDevice device;
	private final Map<String, GpuBufferSlice> uniforms = new HashMap<>(NAME_MAP_CAPACITY);
	private final Map<String, TextureBinding> textures = new HashMap<>(NAME_MAP_CAPACITY);
	/**
	 * What is currently bound in each Metal slot, so a redundant bind can be skipped.
	 *
	 * <p>These were {@code Map<Integer, ...>}, which boxed a dense slot index to hash it and grew a
	 * node table per pass. The slots are already small dense integers, so an array answers the same
	 * question without allocating or hashing anything.
	 */
	private final GpuBufferSlice[] boundUniforms = new GpuBufferSlice[RESOURCE_SLOTS];
	private final MetalGpuTextureView[] boundTextureViews = new MetalGpuTextureView[RESOURCE_SLOTS];
	private final MetalGpuSampler[] boundSamplers = new MetalGpuSampler[RESOURCE_SLOTS];
	private MetalRenderPass metal;
	private RenderPass.RenderArea renderArea;
	private int outputWidth;
	private int outputHeight;
	private boolean hasDepth;
	private MetalCompiledRenderPipeline pipeline;
	private MetalGpuBuffer indexBuffer;
	private MetalRenderPass.IndexType indexType;
	private int debugGroups;
	/**
	 * The batch buffer, allocated on the first multi-draw and reused for the encoder's lifetime.
	 *
	 * <p>One adapter serves every pass on an encoder, so this is allocated once per process in
	 * practice - which is the point: a per-pass batch buffer would trade thousands of JNI calls for
	 * a per-pass direct allocation, and direct allocations are exactly what the renderer has spent
	 * this long removing from the frame.
	 */
	private MetalCommandStream commands;
	/** Non-null while a multi-draw is recording; binds and draws go to it instead of the encoder. */
	private @Nullable MetalCommandStream recording;

	MetalRenderPassBackend(final MetalGpuDevice device) {
		this.device = device;
	}

	/**
	 * Rebinds this adapter to a freshly begun pass.
	 *
	 * <p>One instance is reused for every pass on an encoder, because only one pass can be active at
	 * a time and a per-pass instance meant re-allocating four hash tables per pass - the largest
	 * single source of allocation left in the renderer once bind-group flattening was gone.
	 */
	void reset(
		final MetalRenderPass pass,
		final RenderPass.RenderArea area,
		final int width,
		final int height,
		final boolean depth
	) {
		this.metal = pass;
		this.renderArea = area;
		this.outputWidth = width;
		this.outputHeight = height;
		this.hasDepth = depth;
		this.recording = null;
		this.uniforms.clear();
		this.textures.clear();
		this.clearBoundSlots();
		this.pipeline = null;
		this.indexBuffer = null;
		this.indexType = null;
		this.debugGroups = 0;
	}

	/**
	 * Detaches from the finished pass, so a caller holding the adapter past {@code submitRenderPass}
	 * is told so rather than quietly encoding into whichever pass is opened next. Reuse is what makes
	 * that failure mode possible, so reuse is what has to close it.
	 */
	void finish() {
		this.metal = null;
	}

	/**
	 * The pass, with any batch recorded so far submitted first.
	 *
	 * <p>Every command outside the batch ABI reaches the encoder through here, and each one has to
	 * land after the draws already recorded rather than ahead of them. Flushing at this seam is what
	 * keeps that true without each caller having to know that a batch might be open.
	 */
	private MetalRenderPass pass() {
		if (this.recording != null) this.submitBatch(this.recording);
		return this.metal();
	}

	/** The pass without flushing, for the batch path itself and for {@link #submitBatch}. */
	private MetalRenderPass metal() {
		if (this.metal == null) throw new IllegalStateException("This Metal render pass has already been submitted");
		return this.metal;
	}

	private void clearBoundSlots() {
		Arrays.fill(this.boundUniforms, null);
		Arrays.fill(this.boundTextureViews, null);
		Arrays.fill(this.boundSamplers, null);
	}

	@Override
	public void pushDebugGroup(final Supplier<String> label) {
		this.debugGroups++;
	}

	@Override
	public void popDebugGroup() {
		if (this.debugGroups == 0) throw new IllegalStateException("No Metal debug group is active");
		this.debugGroups--;
	}

	@Override
	public void setPipeline(final RenderPipeline pipeline) {
		MetalCompiledRenderPipeline compiled = this.device.getOrCompilePipeline(pipeline);
		if (!compiled.isValid()) throw new IllegalStateException("Direct Metal pipeline is invalid: " + pipeline.getLocation());
		if (this.pipeline != compiled) {
			this.pipeline = compiled;
			this.clearBoundSlots();
			this.pass().setPipeline(compiled.metal(this.hasDepth));
		}
	}

	@Override
	public void bindTexture(final String name, final @Nullable GpuTextureView textureView, final @Nullable GpuSampler sampler) {
		if (textureView == null && sampler == null) {
			this.textures.remove(name);
		} else if (textureView instanceof MetalGpuTextureView view && sampler instanceof MetalGpuSampler metalSampler) {
			this.textures.put(name, new TextureBinding(view, metalSampler));
		} else {
			throw new IllegalArgumentException("Metal texture and sampler must both be supplied by the direct Metal backend");
		}
	}

	@Override
	public void setUniform(final String name, final GpuBuffer value) {
		this.setUniform(name, value.slice());
	}

	@Override
	public void setUniform(final String name, final GpuBufferSlice value) {
		if (!(value.buffer() instanceof MetalGpuBuffer)) throw new IllegalArgumentException("Uniform buffer does not belong to Metal");
		this.uniforms.put(name, value);
	}

	@Override
	public void enableScissor(final int x, final int y, final int width, final int height) {
		this.pass().setScissor(x, y, width, height);
	}

	@Override
	public void disableScissor() {
		if (this.renderArea != null) this.enableScissor(this.renderArea.x(), this.renderArea.y(), this.renderArea.width(), this.renderArea.height());
		else this.enableScissor(0, 0, this.outputWidth, this.outputHeight);
	}

	@Override
	public void setVertexBuffer(final int slot, final @Nullable GpuBufferSlice vertexBuffer) {
		if (vertexBuffer != null) {
			MetalGpuBuffer buffer = requireBuffer(vertexBuffer.buffer());
			this.encodeVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX + slot, buffer.metal(), vertexBuffer.offset());
		}
	}

	@Override
	public void setIndexBuffer(final GpuBuffer indexBuffer, final IndexType indexType) {
		this.indexBuffer = requireBuffer(indexBuffer);
		this.indexType = switch (indexType) {
			case SHORT -> MetalRenderPass.IndexType.UINT16;
			case INT -> MetalRenderPass.IndexType.UINT32;
		};
	}

	@Override
	public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
		this.bindResources();
		this.requireIndexBuffer();
		this.encodeDrawIndexed(this.primitive(), this.indexBuffer.metal(), indexOffset(firstIndex), this.indexType, indexCount, instanceCount, vertexOffset, firstInstance);
	}

	@Override
	public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		this.bindResources();
		this.requireIndexBuffer();
		this.pass().multiDrawIndexed(this.primitive(), this.indexBuffer.metal(), this.indexType, drawParameters, instanceCount, firstInstance, drawCount);
	}

	@Override
	public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
		this.bindResources();
		this.requireIndexBuffer();
		for (int draw = 0; draw < drawCount; draw++) {
			int count = indexCounts.get(indexCounts.position() + draw);
			int baseVertex = vertexOffsets.get(vertexOffsets.position() + draw);
			this.encodeDrawIndexed(this.primitive(), this.indexBuffer.metal(), firstIndexOffsets.get(firstIndexOffsets.position() + draw), this.indexType, count, 1, baseVertex, 0);
		}
	}

	@Override
	public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
		this.bindResources();
		this.requireIndexBuffer();
		this.pass().drawIndexedIndirect(this.primitive(), this.indexBuffer.metal(), this.indexType, requireBuffer(commands.buffer()).metal(), commands.offset(), drawCount);
	}

	@Override
	public <T> void drawMultipleIndexed(
		final Collection<RenderPass.Draw<T>> draws,
		final @Nullable GpuBuffer defaultIndexBuffer,
		final @Nullable IndexType defaultIndexType,
		final Collection<String> dynamicUniforms,
		final T uniformArgument
	) {
		MetalCommandStream batch = BATCHING ? this.beginRecording() : null;
		try {
			for (RenderPass.Draw<T> draw : draws) {
				BiConsumer<T, RenderPass.UniformUploader> uploader = draw.uniformUploaderConsumer();
				if (uploader != null) uploader.accept(uniformArgument, this::setUniform);
				this.setIndexBuffer(draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer(), draw.indexType() == null ? defaultIndexType : draw.indexType());
				this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
				this.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
			}
		} finally {
			// Cleared before the batch is submitted, so a draw that threw part-way discards what it
			// recorded rather than encoding half a multi-draw into the pass.
			this.recording = null;
		}
		if (batch != null) this.submitBatch(batch);
	}

	@Override
	public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		this.bindResources();
		if (this.isTriangleFan()) {
			this.drawTriangleFan(vertexCount, instanceCount, firstVertex, firstInstance);
		} else {
			this.pass().draw(this.primitive(), firstVertex, vertexCount, instanceCount, firstInstance);
		}
	}

	@Override
	public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		this.bindResources();
		this.pass().multiDraw(this.primitive(), drawParameters, instanceCount, firstInstance, drawCount);
	}

	@Override
	public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
		this.bindResources();
		this.pass().multiDraw(this.primitive(), firstVertices, vertexCounts, drawCount);
	}

	@Override
	public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
		this.bindResources();
		this.pass().drawIndirect(this.primitive(), requireBuffer(commands.buffer()).metal(), commands.offset(), drawCount);
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		if (!(pool instanceof MetalTimestampQueryPool queryPool)) throw new IllegalArgumentException("Query pool does not belong to Metal");
		this.pass().writeTimestamp(queryPool, index);
	}

	private void bindResources() {
		if (this.pipeline == null || !this.pipeline.isValid()) throw new IllegalStateException("A valid Metal pipeline must be bound before drawing");
		List<BindGroupLayout.UniformDescription> uniformLayout = this.pipeline.uniformLayout();
		for (int index = 0; index < uniformLayout.size(); index++) {
			BindGroupLayout.UniformDescription description = uniformLayout.get(index);
			GpuBufferSlice value = this.uniforms.get(description.name());
			if (value == null) throw new IllegalStateException("Missing Metal uniform " + description.name());
			requireSlot(index, description.name());
			if (!value.equals(this.boundUniforms[index])) {
				MetalBuffer buffer = requireBuffer(value.buffer()).metal();
				if (description.type() == UniformType.TEXEL_BUFFER) {
					if (description.gpuFormat() == null) {
						throw new IllegalStateException("Metal texel-buffer uniform has no format: " + description.name());
					}
					this.encodeTexelBuffer(
						index, buffer, value.offset(), value.length(), Blaze3DMetalMappings.textureFormat(description.gpuFormat()),
						this.pipeline.textureStages(index)
					);
				} else {
					this.encodeUniformBuffer(index, buffer, value.offset(), this.pipeline.bufferStages(index));
				}
				this.boundUniforms[index] = value;
			}
		}
		List<String> samplerLayout = this.pipeline.samplerLayout();
		for (int index = 0; index < samplerLayout.size(); index++) {
			String name = samplerLayout.get(index);
			TextureBinding value = this.textures.get(name);
			if (value == null) throw new IllegalStateException("Missing Metal sampler " + name);
			int resourceIndex = uniformLayout.size() + index;
			requireSlot(resourceIndex, name);
			// Views and samplers have identity equality, so this is the same test the record's
			// equals() performed, without boxing the slot to look the pair up.
			if (this.boundTextureViews[resourceIndex] != value.view || this.boundSamplers[resourceIndex] != value.sampler) {
				int stages = this.pipeline.textureStages(resourceIndex);
				this.encodeTexture(resourceIndex, value.view.metal(), stages);
				this.encodeSampler(resourceIndex, value.sampler.metal(), stages);
				this.boundTextureViews[resourceIndex] = value.view;
				this.boundSamplers[resourceIndex] = value.sampler;
			}
		}
	}

	/**
	 * Starts recording into the reusable batch buffer.
	 *
	 * <p>Every bind and draw below asks {@link #recording} where to go, so opening a batch is the
	 * only thing that has to know batching exists.
	 */
	private MetalCommandStream beginRecording() {
		if (this.commands == null) this.commands = new MetalCommandStream();
		this.commands.reset();
		this.recording = this.commands;
		return this.commands;
	}

	/**
	 * Hands a recorded batch to the pass and empties it.
	 *
	 * <p>Timed as a whole, because that is the granularity the change is about: a batch's cost has
	 * to be read against the commands it carried, which is what the recorded byte count reports.
	 */
	private void submitBatch(final MetalCommandStream batch) {
		if (batch.commandCount() == 0) return;
		long startedNs = MetalStallProbe.begin();
		long bytes = batch.byteCount();
		this.metal().submit(batch);
		MetalStallProbe.end(MetalStallProbe.Source.COMMAND_BATCH, startedNs, bytes);
		batch.reset();
	}

	private void encodeVertexBuffer(final int index, final MetalBuffer buffer, final long offset) {
		if (this.recording != null) this.recording.setVertexBuffer(index, buffer, offset);
		else this.metal().setVertexBuffer(index, buffer, offset);
	}

	private void encodeUniformBuffer(final int index, final MetalBuffer buffer, final long offset, final int stages) {
		if (this.recording != null) this.recording.setUniformBuffer(index, buffer, offset, stages);
		else this.metal().setUniformBuffer(index, buffer, offset, stages);
	}

	private void encodeTexture(final int index, final MetalTextureView textureView, final int stages) {
		if (this.recording != null) this.recording.setTexture(index, textureView, stages);
		else this.metal().setTexture(index, textureView, stages);
	}

	private void encodeSampler(final int index, final MetalSampler sampler, final int stages) {
		if (this.recording != null) this.recording.setSampler(index, sampler, stages);
		else this.metal().setSampler(index, sampler, stages);
	}

	/**
	 * Texel buffers are not in the batch ABI, deliberately: binding one means resolving a cached
	 * texture view against the buffer's format alignment, which is far more native work than a
	 * compact record can describe and is rebound once per pass rather than once per draw.
	 */
	private void encodeTexelBuffer(
		final int index,
		final MetalBuffer buffer,
		final long offset,
		final long length,
		final MetalTexture.Format format,
		final int stages
	) {
		this.pass().setTexelBuffer(index, buffer, offset, length, format, stages);
	}

	private void encodeDrawIndexed(
		final MetalRenderPass.Primitive primitive,
		final MetalBuffer indexBuffer,
		final long indexBufferOffset,
		final MetalRenderPass.IndexType indexType,
		final int indexCount,
		final int instanceCount,
		final int baseVertex,
		final int baseInstance
	) {
		if (this.recording != null) {
			this.recording.drawIndexed(primitive, indexBuffer, indexBufferOffset, indexType, indexCount, instanceCount, baseVertex, baseInstance);
		} else {
			this.metal().drawIndexed(primitive, indexBuffer, indexBufferOffset, indexType, indexCount, instanceCount, baseVertex, baseInstance);
		}
	}

	private MetalRenderPass.Primitive primitive() {
		if (this.pipeline == null) throw new IllegalStateException("No Metal pipeline is bound");
		return Blaze3DMetalMappings.primitive(this.pipeline.info().getPrimitiveTopology());
	}

	private boolean isTriangleFan() {
		return this.pipeline != null && this.pipeline.info().getPrimitiveTopology() == PrimitiveTopology.TRIANGLE_FAN;
	}

	private void drawTriangleFan(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		if (vertexCount < 3 || instanceCount == 0) {
			return;
		}
		int indexCount = Math.multiplyExact(vertexCount - 2, 3);
		boolean useShorts = vertexCount <= 1 << 16;
		// The shared fan buffer is already filled; a smaller fan is a prefix of a larger one.
		MetalBuffer indices = this.device.fanIndices(vertexCount, useShorts);
		this.encodeDrawIndexed(
			MetalRenderPass.Primitive.TRIANGLE,
			indices,
			0L,
			useShorts ? MetalRenderPass.IndexType.UINT16 : MetalRenderPass.IndexType.UINT32,
			indexCount,
			instanceCount,
			firstVertex,
			firstInstance
		);
	}

	private void requireIndexBuffer() {
		if (this.indexBuffer == null || this.indexType == null) throw new IllegalStateException("No Metal index buffer is bound");
	}

	private long indexOffset(final int firstIndex) {
		return Math.multiplyExact((long)firstIndex, this.indexType == MetalRenderPass.IndexType.UINT16 ? 2L : 4L);
	}

	private static void requireSlot(final int slot, final String name) {
		if (slot < 0 || slot >= RESOURCE_SLOTS) {
			throw new IllegalStateException("Metal resource slot " + slot + " for " + name
				+ " is outside the " + RESOURCE_SLOTS + " slots a Metal argument table provides");
		}
	}

	private static MetalGpuBuffer requireBuffer(final GpuBuffer buffer) {
		if (buffer instanceof MetalGpuBuffer metal) return metal;
		throw new IllegalArgumentException("Buffer does not belong to the direct Metal backend");
	}

	private record TextureBinding(MetalGpuTextureView view, MetalGpuSampler sampler) {
	}
}
