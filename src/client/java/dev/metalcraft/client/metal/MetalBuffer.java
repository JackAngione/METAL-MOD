package dev.metalcraft.client.metal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** An owned {@code MTLBuffer} supporting scoped mapping when allocated in shared memory. */
public final class MetalBuffer implements AutoCloseable {
	public enum StorageMode {
		SHARED,
		PRIVATE
	}

	private final MetalDevice device;
	private final long size;
	private final StorageMode storageMode;
	private final Set<Mapping> mappings = Collections.newSetFromMap(new IdentityHashMap<>());
	private long handle;
	private boolean closing;

	MetalBuffer(final MetalDevice device, final long handle, final long size, final StorageMode storageMode) {
		if (handle == 0L || size <= 0L) {
			throw new IllegalArgumentException("A Metal buffer requires a handle and positive size");
		}
		this.device = device;
		this.handle = handle;
		this.size = size;
		this.storageMode = storageMode;
	}

	public MetalDevice device() {
		return this.device;
	}

	public long size() {
		return this.size;
	}

	public StorageMode storageMode() {
		return this.storageMode;
	}

	public synchronized Mapping map() {
		return this.map(0L, this.size);
	}

	public synchronized Mapping map(final long offset, final long length) {
		if (this.storageMode != StorageMode.SHARED) {
			throw new IllegalStateException("Only shared Metal buffers can be mapped");
		}
		checkRange(this.size, offset, length, "mapping");
		if (length > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("A Java buffer mapping cannot exceed Integer.MAX_VALUE bytes");
		}
		ByteBuffer bytes = MetalNative.nMappedBufferBytes(this.requireOpenHandle(), offset, length);
		if (bytes == null) {
			throw new IllegalStateException("Metal did not expose the mapped buffer bytes");
		}
		Mapping mapping = new Mapping(this, bytes);
		this.mappings.add(mapping);
		return mapping;
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	@Override
	public void close() {
		List<Mapping> ownedMappings;
		synchronized (this) {
			if (this.handle == 0L || this.closing) {
				return;
			}
			this.closing = true;
			ownedMappings = new ArrayList<>(this.mappings);
		}
		for (Mapping mapping : ownedMappings) {
			mapping.close();
		}
		synchronized (this) {
			MetalNative.nReleaseBuffer(this.handle);
			this.handle = 0L;
			this.mappings.clear();
			this.closing = false;
		}
		this.device.forget(this);
	}

	synchronized long requireOpenHandle() {
		if (this.handle == 0L || this.closing) {
			throw new IllegalStateException("Metal buffer is closed");
		}
		return this.handle;
	}

	private synchronized void forget(final Mapping mapping) {
		this.mappings.remove(mapping);
	}

	static void checkRange(final long capacity, final long offset, final long length, final String operation) {
		if (offset < 0L || length <= 0L || offset > capacity || length > capacity - offset) {
			throw new IllegalArgumentException("Metal buffer " + operation + " range is out of bounds");
		}
	}

	/** A scoped direct view of unified shared memory. Do not retain {@link #bytes()} after close. */
	public static final class Mapping implements AutoCloseable {
		private final MetalBuffer buffer;
		private final ByteBuffer bytes;
		private boolean closed;

		private Mapping(final MetalBuffer buffer, final ByteBuffer bytes) {
			this.buffer = buffer;
			this.bytes = bytes.order(ByteOrder.nativeOrder());
		}

		public synchronized ByteBuffer bytes() {
			if (this.closed) {
				throw new IllegalStateException("Metal buffer mapping is closed");
			}
			return this.bytes;
		}

		public synchronized boolean isClosed() {
			return this.closed;
		}

		@Override
		public void close() {
			synchronized (this) {
				if (this.closed) {
					return;
				}
				this.closed = true;
			}
			this.buffer.forget(this);
		}
	}
}
