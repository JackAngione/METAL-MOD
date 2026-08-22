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
	private final MetalGpuDevice device;
	private final MetalRenderPass metal;
	private final RenderPass.RenderArea renderArea;
	private final int outputWidth;
	private final int outputHeight;
	private final boolean hasDepth;
	private final Map<String, GpuBufferSlice> uniforms = new HashMap<>();
	private final Map<String, TextureBinding> textures = new HashMap<>();
	private final Map<Integer, GpuBufferSlice> boundUniforms = new HashMap<>();
	private final Map<Integer, TextureBinding> boundTextures = new HashMap<>();
	private MetalCompiledRenderPipeline pipeline;
	private MetalGpuBuffer indexBuffer;
	private MetalRenderPass.IndexType indexType;
	private int debugGroups;

	MetalRenderPassBackend(
		final MetalGpuDevice device,
		final MetalRenderPass metal,
		final RenderPass.RenderArea renderArea,
		final int outputWidth,
		final int outputHeight,
		final boolean hasDepth
	) {
		this.device = device;
		this.metal = metal;
		this.renderArea = renderArea;
		this.outputWidth = outputWidth;
		this.outputHeight = outputHeight;
		this.hasDepth = hasDepth;
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
			this.boundUniforms.clear();
			this.boundTextures.clear();
			this.metal.setPipeline(compiled.metal(this.hasDepth));
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
		this.metal.setScissor(x, y, width, height);
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
			this.metal.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX + slot, buffer.metal(), vertexBuffer.offset());
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
		this.metal.drawIndexed(this.primitive(), this.indexBuffer.metal(), indexOffset(firstIndex), this.indexType, indexCount, instanceCount, vertexOffset, firstInstance);
	}

	@Override
	public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		this.bindResources();
		this.requireIndexBuffer();
		this.metal.multiDrawIndexed(this.primitive(), this.indexBuffer.metal(), this.indexType, drawParameters, instanceCount, firstInstance, drawCount);
	}

	@Override
	public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
		this.bindResources();
		this.requireIndexBuffer();
		for (int draw = 0; draw < drawCount; draw++) {
			int count = indexCounts.get(indexCounts.position() + draw);
			int baseVertex = vertexOffsets.get(vertexOffsets.position() + draw);
			this.metal.drawIndexed(this.primitive(), this.indexBuffer.metal(), firstIndexOffsets.get(firstIndexOffsets.position() + draw), this.indexType, count, 1, baseVertex, 0);
		}
	}

	@Override
	public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
		this.bindResources();
		this.requireIndexBuffer();
		this.metal.drawIndexedIndirect(this.primitive(), this.indexBuffer.metal(), this.indexType, requireBuffer(commands.buffer()).metal(), commands.offset(), drawCount);
	}

	@Override
	public <T> void drawMultipleIndexed(
		final Collection<RenderPass.Draw<T>> draws,
		final @Nullable GpuBuffer defaultIndexBuffer,
		final @Nullable IndexType defaultIndexType,
		final Collection<String> dynamicUniforms,
		final T uniformArgument
	) {
		for (RenderPass.Draw<T> draw : draws) {
			BiConsumer<T, RenderPass.UniformUploader> uploader = draw.uniformUploaderConsumer();
			if (uploader != null) uploader.accept(uniformArgument, this::setUniform);
			this.setIndexBuffer(draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer(), draw.indexType() == null ? defaultIndexType : draw.indexType());
			this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
			this.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
		}
	}

	@Override
	public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		this.bindResources();
		if (this.isTriangleFan()) {
			this.drawTriangleFan(vertexCount, instanceCount, firstVertex, firstInstance);
		} else {
			this.metal.draw(this.primitive(), firstVertex, vertexCount, instanceCount, firstInstance);
		}
	}

	@Override
	public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		this.bindResources();
		this.metal.multiDraw(this.primitive(), drawParameters, instanceCount, firstInstance, drawCount);
	}

	@Override
	public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
		this.bindResources();
		this.metal.multiDraw(this.primitive(), firstVertices, vertexCounts, drawCount);
	}

	@Override
	public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
		this.bindResources();
		this.metal.drawIndirect(this.primitive(), requireBuffer(commands.buffer()).metal(), commands.offset(), drawCount);
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		if (!(pool instanceof MetalTimestampQueryPool queryPool)) throw new IllegalArgumentException("Query pool does not belong to Metal");
		this.metal.writeTimestamp(queryPool, index);
	}

	private void bindResources() {
		if (this.pipeline == null || !this.pipeline.isValid()) throw new IllegalStateException("A valid Metal pipeline must be bound before drawing");
		List<BindGroupLayout.UniformDescription> uniformLayout = BindGroupLayout.flattenUniforms(this.pipeline.info().getBindGroupLayouts());
		for (int index = 0; index < uniformLayout.size(); index++) {
			BindGroupLayout.UniformDescription description = uniformLayout.get(index);
			GpuBufferSlice value = this.uniforms.get(description.name());
			if (value == null) throw new IllegalStateException("Missing Metal uniform " + description.name());
			if (!value.equals(this.boundUniforms.get(index))) {
				MetalBuffer buffer = requireBuffer(value.buffer()).metal();
				if (description.type() == UniformType.TEXEL_BUFFER) {
					if (description.gpuFormat() == null) {
						throw new IllegalStateException("Metal texel-buffer uniform has no format: " + description.name());
					}
					this.metal.setTexelBuffer(
						index, buffer, value.offset(), value.length(), Blaze3DMetalMappings.textureFormat(description.gpuFormat())
					);
				} else {
					this.metal.setUniformBuffer(index, buffer, value.offset());
				}
				this.boundUniforms.put(index, value);
			}
		}
		List<String> samplerLayout = BindGroupLayout.flattenSamplers(this.pipeline.info().getBindGroupLayouts());
		for (int index = 0; index < samplerLayout.size(); index++) {
			String name = samplerLayout.get(index);
			TextureBinding value = this.textures.get(name);
			if (value == null) throw new IllegalStateException("Missing Metal sampler " + name);
			int resourceIndex = uniformLayout.size() + index;
			if (!value.equals(this.boundTextures.get(resourceIndex))) {
				this.metal.setTexture(resourceIndex, value.view.metal());
				this.metal.setSampler(resourceIndex, value.sampler.metal());
				this.boundTextures.put(resourceIndex, value);
			}
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
		this.metal.drawIndexed(
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

	private static MetalGpuBuffer requireBuffer(final GpuBuffer buffer) {
		if (buffer instanceof MetalGpuBuffer metal) return metal;
		throw new IllegalArgumentException("Buffer does not belong to the direct Metal backend");
	}

	private record TextureBinding(MetalGpuTextureView view, MetalGpuSampler sampler) {
	}
}
