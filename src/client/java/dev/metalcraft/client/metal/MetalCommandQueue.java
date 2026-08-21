package dev.metalcraft.client.metal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** An owned {@code MTLCommandQueue} tied to the lifetime of its {@link MetalDevice}. */
public final class MetalCommandQueue implements AutoCloseable {
	private final MetalDevice device;
	private final Set<MetalCommandBuffer> commandBuffers = Collections.newSetFromMap(new IdentityHashMap<>());
	private long handle;
	private boolean closing;

	MetalCommandQueue(final MetalDevice device, final long handle) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal command-queue handle cannot be zero");
		}
		this.device = device;
		this.handle = handle;
	}

	public MetalDevice device() {
		return this.device;
	}

	public synchronized MetalCommandBuffer createCommandBuffer() {
		long commandBufferHandle = MetalNative.nCreateCommandBuffer(this.requireOpenHandle());
		if (commandBufferHandle == 0L) {
			throw new IllegalStateException("Metal did not return a command buffer");
		}
		MetalCommandBuffer commandBuffer = new MetalCommandBuffer(this, commandBufferHandle);
		this.commandBuffers.add(commandBuffer);
		return commandBuffer;
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	@Override
	public void close() {
		List<MetalCommandBuffer> ownedCommandBuffers;
		synchronized (this) {
			if (this.handle == 0L || this.closing) {
				return;
			}
			this.closing = true;
			ownedCommandBuffers = new ArrayList<>(this.commandBuffers);
		}

		for (MetalCommandBuffer commandBuffer : ownedCommandBuffers) {
			commandBuffer.close();
		}

		synchronized (this) {
			MetalNative.nReleaseCommandQueue(this.handle);
			this.handle = 0L;
			this.commandBuffers.clear();
			this.closing = false;
		}
		this.device.forget(this);
	}

	synchronized void forget(final MetalCommandBuffer commandBuffer) {
		this.commandBuffers.remove(commandBuffer);
	}

	private long requireOpenHandle() {
		if (this.handle == 0L || this.closing) {
			throw new IllegalStateException("Metal command queue is closed");
		}
		return this.handle;
	}
}
