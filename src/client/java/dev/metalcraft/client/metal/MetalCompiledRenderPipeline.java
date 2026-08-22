package dev.metalcraft.client.metal;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Result cached at the Blaze3D pipeline seam. */
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
	private final RenderPipeline info;
	private final @Nullable MetalRenderPipeline withDepth;
	private final @Nullable MetalRenderPipeline withoutDepth;
	/**
	 * The pipeline's flattened binding layout, resolved once here.
	 *
	 * <p>Flattening walks the bind-group layouts and builds fresh lists, and the render path needs
	 * it before every draw. Doing it per draw made this the single largest allocation site in the
	 * process: an allocation profile of one 20-second traversal capture attributed 4.18 GB - 18% of
	 * everything the JVM allocated - to these two calls. The layouts are fixed for a compiled
	 * pipeline, so this is a constant being rebuilt thousands of times per frame.
	 */
	private final List<BindGroupLayout.UniformDescription> uniformLayout;
	private final List<String> samplerLayout;
	/**
	 * Which shader stages declare each resource slot, one entry per slot.
	 *
	 * <p>Metal keeps a separate argument table per stage, so binding a resource means one encoder
	 * call per stage that reads it. The backend used to make both calls for every bind because it
	 * had no way to know better; the translated shaders do know, so the answer is resolved once
	 * here, when the pipeline is compiled.
	 */
	private final int[] bufferStages;
	private final int[] textureStages;

	MetalCompiledRenderPipeline(
		final RenderPipeline info,
		final @Nullable MetalRenderPipeline withDepth,
		final @Nullable MetalRenderPipeline withoutDepth,
		final MetalShaderTranslator.@Nullable PipelineTranslation shaders
	) {
		this.info = info;
		this.withDepth = withDepth;
		this.withoutDepth = withoutDepth;
		this.uniformLayout = List.copyOf(BindGroupLayout.flattenUniforms(info.getBindGroupLayouts()));
		this.samplerLayout = List.copyOf(BindGroupLayout.flattenSamplers(info.getBindGroupLayouts()));
		this.bufferStages = stagesPerSlot(
			shaders == null ? -1 : shaders.vertex().bufferSlots(),
			shaders == null ? -1 : shaders.fragment().bufferSlots()
		);
		this.textureStages = stagesPerSlot(
			shaders == null ? -1 : shaders.vertex().textureSlots(),
			shaders == null ? -1 : shaders.fragment().textureSlots()
		);
	}

	/**
	 * A pipeline that failed to compile is never drawn with, so its masks only have to be harmless:
	 * every slot claims both stages, which is what the backend did before it could tell them apart.
	 */
	private static int[] stagesPerSlot(final int vertexSlots, final int fragmentSlots) {
		int[] stages = new int[MetalRenderPass.RESOURCE_SLOTS];
		for (int slot = 0; slot < stages.length; slot++) {
			int mask = 0;
			if ((vertexSlots >>> slot & 1) != 0) mask |= MetalRenderPass.STAGE_VERTEX;
			if ((fragmentSlots >>> slot & 1) != 0) mask |= MetalRenderPass.STAGE_FRAGMENT;
			stages[slot] = mask;
		}
		return stages;
	}

	/** The stages that read the buffer in this slot, as a {@code MetalRenderPass.STAGE_*} mask. */
	int bufferStages(final int slot) {
		return slot >= 0 && slot < this.bufferStages.length ? this.bufferStages[slot] : 0;
	}

	/** The stages that read the texture in this slot, as a {@code MetalRenderPass.STAGE_*} mask. */
	int textureStages(final int slot) {
		return slot >= 0 && slot < this.textureStages.length ? this.textureStages[slot] : 0;
	}

	RenderPipeline info() {
		return this.info;
	}

	/** The flattened uniform bindings, in Metal resource-slot order. */
	List<BindGroupLayout.UniformDescription> uniformLayout() {
		return this.uniformLayout;
	}

	/** The flattened sampler bindings, in Metal resource-slot order after the uniforms. */
	List<String> samplerLayout() {
		return this.samplerLayout;
	}

	MetalRenderPipeline metal(final boolean hasDepth) {
		MetalRenderPipeline selected = hasDepth ? this.withDepth : this.withoutDepth;
		if (selected == null) {
			throw new IllegalStateException("Metal pipeline has no " + (hasDepth ? "depth" : "depthless") + " variant");
		}
		return selected;
	}

	@Override
	public boolean isValid() {
		return this.withoutDepth != null && !this.withoutDepth.isClosed() && this.withDepth != null && !this.withDepth.isClosed();
	}

	@Override
	public void close() {
		if (this.withDepth != null) this.withDepth.close();
		if (this.withoutDepth != null && this.withoutDepth != this.withDepth) this.withoutDepth.close();
	}
}
