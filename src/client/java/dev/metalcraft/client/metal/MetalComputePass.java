package dev.metalcraft.client.metal;

/**
 * A scoped {@code MTLComputeCommandEncoder}; closing it ends the pass.
 *
 * <p>Dispatches within one pass are serially ordered, so a dispatch reads what the dispatch before
 * it wrote without a barrier between them. Across encoders, Metal's own hazard tracking orders a
 * compute write against a later render read of the same resource.
 */
public final class MetalComputePass implements AutoCloseable {
	/** Buffers, textures, and samplers a Metal argument table addresses. */
	public static final int RESOURCE_SLOTS = MetalRenderPass.RESOURCE_SLOTS;

	private final MetalCommandBuffer commandBuffer;
	private long handle;
	private boolean pipelineBound;
	private int maxThreadsPerThreadgroup;

	MetalComputePass(final MetalCommandBuffer commandBuffer, final long handle) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal compute-pass handle cannot be zero");
		}
		this.commandBuffer = commandBuffer;
		this.handle = handle;
	}

	public synchronized void setPipeline(final MetalComputePipeline pipeline) {
		if (pipeline == null) {
			throw new NullPointerException("pipeline");
		}
		MetalNative.nSetComputePipeline(this.requireOpenHandle(), pipeline.requireOpenHandle());
		this.pipelineBound = true;
		this.maxThreadsPerThreadgroup = pipeline.maxThreadsPerThreadgroup();
	}

	public synchronized void setBuffer(final int index, final MetalBuffer buffer, final long offset) {
		this.requireSlot(index);
		MetalBuffer.checkRange(buffer.size(), offset, 1L, "compute binding");
		MetalNative.nSetComputeBuffer(this.requireOpenHandle(), index, buffer.requireOpenHandle(), offset);
	}

	public synchronized void setTexture(final int index, final MetalTextureView view) {
		this.requireSlot(index);
		MetalNative.nSetComputeTexture(this.requireOpenHandle(), index, view.requireOpenHandle());
	}

	public synchronized void setSampler(final int index, final MetalSampler sampler) {
		this.requireSlot(index);
		MetalNative.nSetComputeSampler(this.requireOpenHandle(), index, sampler.requireOpenHandle());
	}

	/**
	 * @param threadsX threads per threadgroup, not total threads: Metal dispatches whole
	 *     threadgroups, so a kernel over a grid that does not divide evenly has to bound itself
	 */
	public synchronized void dispatch(
		final int groupsX,
		final int groupsY,
		final int groupsZ,
		final int threadsX,
		final int threadsY,
		final int threadsZ
	) {
		if (!this.pipelineBound) {
			throw new IllegalStateException("Bind a Metal compute pipeline before dispatching");
		}
		if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0 || threadsX <= 0 || threadsY <= 0 || threadsZ <= 0) {
			throw new IllegalArgumentException("A Metal dispatch requires positive threadgroup and thread counts");
		}
		long threads = (long)threadsX * threadsY * threadsZ;
		if (threads > this.maxThreadsPerThreadgroup) {
			throw new IllegalArgumentException(
				"A threadgroup of " + threads + " threads exceeds the " + this.maxThreadsPerThreadgroup
					+ " this kernel compiled to support"
			);
		}
		MetalNative.nDispatchThreadgroups(this.requireOpenHandle(), groupsX, groupsY, groupsZ, threadsX, threadsY, threadsZ);
	}

	/** Dispatches enough whole threadgroups to cover a two-dimensional grid. */
	public void dispatchCovering(final int width, final int height, final int threadsX, final int threadsY) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("A Metal dispatch grid requires positive dimensions");
		}
		this.dispatch(ceilDiv(width, threadsX), ceilDiv(height, threadsY), 1, threadsX, threadsY, 1);
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	@Override
	public void close() {
		long ending;
		synchronized (this) {
			if (this.handle == 0L) {
				return;
			}
			ending = this.handle;
			this.handle = 0L;
		}
		MetalNative.nEndComputePass(ending);
		this.commandBuffer.forget(this);
	}

	private static int ceilDiv(final int value, final int divisor) {
		if (divisor <= 0) {
			throw new IllegalArgumentException("A Metal threadgroup dimension must be positive");
		}
		return (value + divisor - 1) / divisor;
	}

	private void requireSlot(final int index) {
		if (index < 0 || index >= RESOURCE_SLOTS) {
			throw new IllegalArgumentException("Metal compute binding index must be between 0 and " + (RESOURCE_SLOTS - 1));
		}
	}

	private synchronized long requireOpenHandle() {
		if (this.handle == 0L) {
			throw new IllegalStateException("Metal compute pass is closed");
		}
		return this.handle;
	}
}
