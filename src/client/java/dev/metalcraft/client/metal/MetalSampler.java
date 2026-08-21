package dev.metalcraft.client.metal;

/** An owned immutable {@code MTLSamplerState}. */
public final class MetalSampler implements AutoCloseable {
	public enum Filter {
		NEAREST,
		LINEAR
	}

	public enum AddressMode {
		CLAMP_TO_EDGE,
		REPEAT,
		MIRROR_REPEAT
	}

	public record Descriptor(
		Filter minFilter,
		Filter magFilter,
		AddressMode addressModeU,
		AddressMode addressModeV,
		int maxAnisotropy,
		double maxLod
	) {
		public Descriptor(final Filter minFilter, final Filter magFilter, final AddressMode addressMode) {
			this(minFilter, magFilter, addressMode, addressMode, 1, Double.POSITIVE_INFINITY);
		}

		public Descriptor {
			if (minFilter == null || magFilter == null || addressModeU == null || addressModeV == null) {
				throw new NullPointerException("Metal sampler descriptor fields cannot be null");
			}
			if (maxAnisotropy < 1 || maxAnisotropy > 16 || Double.isNaN(maxLod) || maxLod < 0.0) {
				throw new IllegalArgumentException("Metal sampler anisotropy or maximum LOD is out of range");
			}
		}
	}

	private final MetalDevice device;
	private final Descriptor descriptor;
	private long handle;

	MetalSampler(final MetalDevice device, final long handle, final Descriptor descriptor) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal sampler handle cannot be zero");
		}
		this.device = device;
		this.handle = handle;
		this.descriptor = descriptor;
	}

	public MetalDevice device() {
		return this.device;
	}

	public Descriptor descriptor() {
		return this.descriptor;
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	synchronized long requireOpenHandle() {
		if (this.handle == 0L) {
			throw new IllegalStateException("Metal sampler is closed");
		}
		return this.handle;
	}

	@Override
	public void close() {
		synchronized (this) {
			if (this.handle == 0L) {
				return;
			}
			MetalNative.nReleaseSampler(this.handle);
			this.handle = 0L;
		}
		this.device.forget(this);
	}
}
