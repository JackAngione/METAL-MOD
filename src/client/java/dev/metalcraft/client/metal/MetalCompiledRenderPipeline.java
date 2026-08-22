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

	MetalCompiledRenderPipeline(
		final RenderPipeline info,
		final @Nullable MetalRenderPipeline withDepth,
		final @Nullable MetalRenderPipeline withoutDepth
	) {
		this.info = info;
		this.withDepth = withDepth;
		this.withoutDepth = withoutDepth;
		this.uniformLayout = List.copyOf(BindGroupLayout.flattenUniforms(info.getBindGroupLayouts()));
		this.samplerLayout = List.copyOf(BindGroupLayout.flattenSamplers(info.getBindGroupLayouts()));
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
