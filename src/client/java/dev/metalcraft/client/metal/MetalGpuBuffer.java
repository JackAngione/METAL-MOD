package dev.metalcraft.client.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

/** Blaze3D buffer adapter backed by one owned {@link MetalBuffer}. */
final class MetalGpuBuffer extends GpuBuffer {
	private final MetalBuffer metal;

	MetalGpuBuffer(final @GpuBuffer.Usage int usage, final long size, final MetalBuffer metal) {
		super(usage, size);
		this.metal = metal;
	}

	MetalBuffer metal() {
		return this.metal;
	}

	@Override
	public boolean isClosed() {
		return this.metal.isClosed();
	}

	@Override
	public void close() {
		this.metal.close();
	}

	@Override
	public GpuBufferSlice.MappedView map(final long offset, final long length, final boolean read, final boolean write) {
		if (!read && !write) {
			throw new IllegalArgumentException("At least one of read or write must be true");
		}
		if (read && (this.usage() & USAGE_MAP_READ) == 0) {
			throw new IllegalStateException("Buffer was not created with USAGE_MAP_READ");
		}
		if (write && (this.usage() & USAGE_MAP_WRITE) == 0) {
			throw new IllegalStateException("Buffer was not created with USAGE_MAP_WRITE");
		}
		MetalBuffer.Mapping mapping = this.metal.map(offset, length);
		return new GpuBufferSlice.MappedView(this.slice(offset, length), mapping.bytes(), mapping::close);
	}
}
