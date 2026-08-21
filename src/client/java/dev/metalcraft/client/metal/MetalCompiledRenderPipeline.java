package dev.metalcraft.client.metal;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.jspecify.annotations.Nullable;

/** Result cached at the Blaze3D pipeline seam. */
final class MetalCompiledRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
	private final RenderPipeline info;
	private final @Nullable MetalRenderPipeline withDepth;
	private final @Nullable MetalRenderPipeline withoutDepth;

	MetalCompiledRenderPipeline(
		final RenderPipeline info,
		final @Nullable MetalRenderPipeline withDepth,
		final @Nullable MetalRenderPipeline withoutDepth
	) {
		this.info = info;
		this.withDepth = withDepth;
		this.withoutDepth = withoutDepth;
	}

	RenderPipeline info() {
		return this.info;
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
