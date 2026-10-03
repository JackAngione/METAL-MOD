package dev.metalcraft.client.shader.wind;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import org.jspecify.annotations.Nullable;

/** Immutable CPU sidecar published with a mesh; GPU admission is atomic before its first draw. */
public final class WindMeshBinding implements AutoCloseable {
	private final WindVertexMetadata metadata;
	private @Nullable MetalBuffer buffer;
	private boolean closed;

	public WindMeshBinding(final WindVertexMetadata metadata) { this.metadata = metadata; }
	public int vertexCount() { return this.metadata.vertexCount(); }

	/** Shared storage is written once, before binding, and never rewritten while GPU work is pending. */
	public synchronized @Nullable MetalBuffer upload(final MetalDevice device) {
		if (this.closed) return null;
		if (this.buffer != null && this.buffer.device() == device) return this.buffer;
		MetalBuffer next = device.createBuffer((long)this.vertexCount() * WindVertexMetadata.STRIDE_BYTES, MetalBuffer.StorageMode.SHARED);
		try (MetalBuffer.Mapping mapping = next.map()) {
			mapping.bytes().put(this.metadata.bytes());
		} catch (RuntimeException error) {
			next.close();
			throw error;
		}
		if (this.buffer != null) this.buffer.close();
		this.buffer = next;
		return next;
	}

	@Override
	public synchronized void close() {
		this.closed = true;
		if (this.buffer != null) this.buffer.close();
		this.buffer = null;
	}
}
