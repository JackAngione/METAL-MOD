package dev.metalcraft.client.metal;

/** An owned {@code MTLCommandBuffer} used to schedule and present one frame. */
public final class MetalCommandBuffer implements AutoCloseable {
	private final MetalCommandQueue commandQueue;
	private long handle;
	private boolean committed;
	private MetalRenderPass activeRenderPass;

	MetalCommandBuffer(final MetalCommandQueue commandQueue, final long handle) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal command-buffer handle cannot be zero");
		}
		this.commandQueue = commandQueue;
		this.handle = handle;
	}

	public MetalCommandQueue commandQueue() {
		return this.commandQueue;
	}

	public synchronized void present(final MetalDrawable drawable) {
		this.requireEncodingHandle();
		long drawableHandle = drawable.claimForPresentation();
		try {
			MetalNative.nPresentDrawable(this.handle, drawableHandle);
		} catch (RuntimeException error) {
			drawable.releasePresentationClaim();
			throw error;
		}
	}

	public synchronized void copyBuffer(
		final MetalBuffer source,
		final long sourceOffset,
		final MetalBuffer destination,
		final long destinationOffset,
		final long size
	) {
		MetalBuffer.checkRange(source.size(), sourceOffset, size, "copy source");
		MetalBuffer.checkRange(destination.size(), destinationOffset, size, "copy destination");
		MetalNative.nCopyBuffer(
			this.requireEncodingHandle(),
			source.requireOpenHandle(),
			sourceOffset,
			destination.requireOpenHandle(),
			destinationOffset,
			size
		);
	}

	public synchronized void copyBufferToTexture(
		final MetalBuffer source,
		final long sourceOffset,
		final long bytesPerRow,
		final MetalTexture destination,
		final int mipLevel
	) {
		destination.validateTransfer(mipLevel, bytesPerRow, source.size(), sourceOffset);
		MetalNative.nCopyBufferToTexture(
			this.requireEncodingHandle(),
			source.requireOpenHandle(),
			sourceOffset,
			bytesPerRow,
			destination.requireOpenHandle(),
			mipLevel
		);
	}

	public synchronized void copyTextureToBuffer(
		final MetalTexture source,
		final int mipLevel,
		final MetalBuffer destination,
		final long destinationOffset,
		final long bytesPerRow
	) {
		source.validateTransfer(mipLevel, bytesPerRow, destination.size(), destinationOffset);
		MetalNative.nCopyTextureToBuffer(
			this.requireEncodingHandle(),
			source.requireOpenHandle(),
			mipLevel,
			destination.requireOpenHandle(),
			destinationOffset,
			bytesPerRow
		);
	}

	public synchronized void copyBufferToTextureRegion(
		final MetalBuffer source,
		final long sourceOffset,
		final long bytesPerRow,
		final MetalTexture destination,
		final int mipLevel,
		final int arrayLayer,
		final int destinationX,
		final int destinationY,
		final int width,
		final int height
	) {
		MetalNative.nCopyBufferToTextureRegion(
			this.requireEncodingHandle(), source.requireOpenHandle(), sourceOffset, bytesPerRow,
			destination.requireOpenHandle(), mipLevel, arrayLayer, destinationX, destinationY, width, height
		);
	}

	public synchronized void copyTextureToBufferRegion(
		final MetalTexture source,
		final int mipLevel,
		final int sourceX,
		final int sourceY,
		final int width,
		final int height,
		final MetalBuffer destination,
		final long destinationOffset,
		final long bytesPerRow
	) {
		MetalNative.nCopyTextureToBufferRegion(
			this.requireEncodingHandle(), source.requireOpenHandle(), mipLevel, sourceX, sourceY, width, height,
			destination.requireOpenHandle(), destinationOffset, bytesPerRow
		);
	}

	public synchronized void copyTexture(
		final MetalTexture source,
		final MetalTexture destination,
		final int mipLevel,
		final int sourceX,
		final int sourceY,
		final int destinationX,
		final int destinationY,
		final int width,
		final int height
	) {
		MetalNative.nCopyTexture(
			this.requireEncodingHandle(), source.requireOpenHandle(), destination.requireOpenHandle(), mipLevel,
			sourceX, sourceY, destinationX, destinationY, width, height
		);
	}

	public synchronized void blitToDrawable(final MetalTexture source, final MetalDrawable drawable) {
		MetalNative.nBlitTextureToDrawable(this.requireEncodingHandle(), source.requireOpenHandle(), drawable.requireOpenHandle());
	}

	public synchronized void signal(final MetalFence fence, final long value) {
		if (value <= 0L) {
			throw new IllegalArgumentException("Metal fence values must be positive");
		}
		MetalNative.nSignalFence(this.requireEncodingHandle(), fence.requireOpenHandle(), value);
	}

	public synchronized void waitFor(final MetalFence fence, final long value) {
		if (value <= 0L) {
			throw new IllegalArgumentException("Metal fence values must be positive");
		}
		MetalNative.nWaitForFence(this.requireEncodingHandle(), fence.requireOpenHandle(), value);
	}

	public synchronized void writeTimestamp(final MetalTimestampQueryPool pool, final int index) {
		if (pool == null) {
			throw new NullPointerException("pool");
		}
		if (pool.device() != this.commandQueue.device()) {
			throw new IllegalArgumentException("Metal timestamp query pool and command buffer must belong to the same device");
		}
		if (index < 0 || index >= pool.size()) {
			throw new IndexOutOfBoundsException("Metal timestamp query index is out of bounds");
		}
		MetalNative.nWriteCommandTimestamp(this.requireEncodingHandle(), pool.requireOpenHandle(), index);
	}

	public synchronized MetalRenderPass beginRenderPass(final MetalRenderPass.Descriptor descriptor) {
		if (descriptor == null) {
			throw new NullPointerException("descriptor");
		}
		if (this.activeRenderPass != null) {
			throw new IllegalStateException("A Metal render pass is already active on this command buffer");
		}

		MetalRenderPass.ColorAttachment color = descriptor.colorAttachment();
		long colorTargetHandle;
		boolean colorTargetIsDrawable;
		if (color.target() instanceof MetalDrawable drawable) {
			colorTargetHandle = drawable.requireOpenHandle();
			colorTargetIsDrawable = true;
		} else if (color.target() instanceof MetalTexture texture) {
			colorTargetHandle = texture.requireOpenHandle();
			colorTargetIsDrawable = false;
		} else {
			throw new IllegalArgumentException("Unsupported Metal color attachment target");
		}

		MetalRenderPass.DepthAttachment depth = descriptor.depthAttachment();
		long renderPassHandle = MetalNative.nBeginRenderPass(
			this.requireEncodingHandle(),
			colorTargetHandle,
			colorTargetIsDrawable,
			color.mipLevel(),
			color.loadAction().ordinal(),
			color.storeAction().ordinal(),
			color.clearRed(),
			color.clearGreen(),
			color.clearBlue(),
			color.clearAlpha(),
			depth == null ? 0L : depth.texture().requireOpenHandle(),
			depth == null ? 0 : depth.mipLevel(),
			depth == null ? 0 : depth.loadAction().ordinal(),
			depth == null ? 0 : depth.storeAction().ordinal(),
			depth == null ? 1.0 : depth.clearDepth()
		);
		if (renderPassHandle == 0L) {
			throw new IllegalStateException("Metal did not create a render command encoder");
		}
		MetalRenderPass renderPass = new MetalRenderPass(this, renderPassHandle, descriptor);
		this.activeRenderPass = renderPass;
		return renderPass;
	}

	public synchronized void commit() {
		if (this.committed) {
			throw new IllegalStateException("Metal command buffer is already committed");
		}
		if (this.activeRenderPass != null) {
			throw new IllegalStateException("End the active Metal render pass before committing its command buffer");
		}
		MetalNative.nCommitCommandBuffer(this.requireOpenHandle());
		this.committed = true;
	}

	public synchronized void waitUntilCompleted() {
		if (!this.committed) {
			throw new IllegalStateException("Metal command buffer must be committed before waiting for completion");
		}
		MetalNative.nWaitForCommandBuffer(this.requireOpenHandle());
	}

	public synchronized void commitAndWait() {
		this.commit();
		this.waitUntilCompleted();
	}

	public synchronized boolean isCommitted() {
		return this.committed;
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	synchronized int retainedResourceCount() {
		return MetalNative.nCommandBufferRetainedResourceCount(this.requireOpenHandle());
	}

	@Override
	public void close() {
		MetalRenderPass renderPass;
		synchronized (this) {
			renderPass = this.activeRenderPass;
		}
		if (renderPass != null) {
			renderPass.close();
		}
		synchronized (this) {
			if (this.handle == 0L) {
				return;
			}
			MetalNative.nReleaseCommandBuffer(this.handle);
			this.handle = 0L;
		}
		this.commandQueue.forget(this);
	}

	synchronized void forget(final MetalRenderPass renderPass) {
		if (this.activeRenderPass == renderPass) {
			this.activeRenderPass = null;
		}
	}

	private long requireOpenHandle() {
		if (this.handle == 0L) {
			throw new IllegalStateException("Metal command buffer is closed");
		}
		return this.handle;
	}

	private long requireEncodingHandle() {
		if (this.committed) {
			throw new IllegalStateException("Cannot encode commands after committing a Metal command buffer");
		}
		if (this.activeRenderPass != null) {
			throw new IllegalStateException("End the active Metal render pass before encoding another command");
		}
		return this.requireOpenHandle();
	}
}
