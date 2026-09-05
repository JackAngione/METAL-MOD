package dev.metalcraft.client.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;

/** Blaze3D buffer adapter backed by one owned {@link MetalBuffer}. */
final class MetalGpuBuffer extends GpuBuffer {
	private final MetalBuffer metal;
	private final java.nio.ByteBuffer uniformUpload;
	private final java.util.BitSet uploaded;


	MetalGpuBuffer(final @GpuBuffer.Usage int usage, final long size, final MetalBuffer metal) {
		super(usage, size);
		this.metal = metal;
		boolean mirror = (usage & USAGE_UNIFORM) != 0 && metal.storageMode() == MetalBuffer.StorageMode.PRIVATE;
		this.uniformUpload = mirror ? java.nio.ByteBuffer.allocate(Math.toIntExact(size)).order(java.nio.ByteOrder.nativeOrder()) : null;
		this.uploaded = mirror ? new java.util.BitSet(Math.toIntExact(size)) : null;
	}

	MetalBuffer metal() {
		return this.metal;
	}

	/** CPU provenance for private uniform uploads; never reads back GPU memory. */
	void recordUpload(long offset, java.nio.ByteBuffer data, long length) {
		if (this.uniformUpload == null) return;
		int start = Math.toIntExact(offset), count = Math.toIntExact(length);
		if (data == null) {
			this.uploaded.clear(start, start + count);
		} else {
			this.uniformUpload.slice(start, count).put(data.duplicate());
			this.uploaded.set(start, start + count);
		}
	}

	void recordCopy(long offset, MetalGpuBuffer source, long sourceOffset, long length) {
		if (this.uniformUpload != null) this.recordUpload(offset, source.cpuBytes(sourceOffset, length), length);
	}

	void captureUniform(String name, long offset, long length, WorldUniformCapture capture) {
		if (!name.equals("Projection") && !name.equals("ChunkSection")
			&& !name.equals("DynamicTransforms") && !name.equals("Fog")) return;
		java.nio.ByteBuffer bytes = this.cpuBytes(offset, length);
		if (bytes != null) capture.capture(name, bytes);
	}

	java.nio.ByteBuffer cpuBytes(long offset, long length) {
		if (this.metal.storageMode() == MetalBuffer.StorageMode.SHARED) {
			try (var mapping = this.metal.map(offset, length)) {
				return mapping.bytes().asReadOnlyBuffer().order(java.nio.ByteOrder.nativeOrder());
			}
		}
		int start = Math.toIntExact(offset), count = Math.toIntExact(length);
		if (this.uniformUpload == null || this.uploaded.nextClearBit(start) < start + count) return null;
		return this.uniformUpload.asReadOnlyBuffer().slice(start, count).order(java.nio.ByteOrder.nativeOrder());
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
