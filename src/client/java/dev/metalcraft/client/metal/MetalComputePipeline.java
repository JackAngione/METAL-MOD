package dev.metalcraft.client.metal;

/** An owned {@code MTLComputePipelineState} compiled from one MSL kernel function. */
public final class MetalComputePipeline implements AutoCloseable {
	/**
	 * A kernel's MSL source and the function in it to compile.
	 *
	 * @param source Metal Shading Language, already complete: nothing expands includes here
	 * @param functionName the {@code kernel} function to build a pipeline state from
	 */
	public record Descriptor(String source, String functionName) {
		public Descriptor {
			if (source == null || source.isBlank()) {
				throw new IllegalArgumentException("A Metal compute pipeline requires non-blank MSL source");
			}
			if (functionName == null || functionName.isBlank()) {
				throw new IllegalArgumentException("A Metal compute pipeline requires a kernel function name");
			}
		}
	}

	private final MetalDevice device;
	private final Descriptor descriptor;
	private final int maxThreadsPerThreadgroup;
	private final int threadExecutionWidth;
	private long handle;

	MetalComputePipeline(final MetalDevice device, final long handle, final Descriptor descriptor) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal compute-pipeline handle cannot be zero");
		}
		this.device = device;
		this.handle = handle;
		this.descriptor = descriptor;
		this.maxThreadsPerThreadgroup = MetalNative.nComputePipelineMaxThreadsPerThreadgroup(handle);
		this.threadExecutionWidth = MetalNative.nComputePipelineThreadExecutionWidth(handle);
	}

	public MetalDevice device() {
		return this.device;
	}

	public Descriptor descriptor() {
		return this.descriptor;
	}

	/**
	 * The largest threadgroup this kernel can be dispatched with, which depends on the register
	 * pressure of the compiled kernel rather than on the device alone.
	 */
	public int maxThreadsPerThreadgroup() {
		return this.maxThreadsPerThreadgroup;
	}

	/** The SIMD width to keep threadgroup sizes a multiple of, so no lanes are dispatched idle. */
	public int threadExecutionWidth() {
		return this.threadExecutionWidth;
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	@Override
	public void close() {
		synchronized (this) {
			if (this.handle == 0L) {
				return;
			}
			MetalNative.nReleaseComputePipeline(this.handle);
			this.handle = 0L;
		}
		this.device.forget(this);
	}

	synchronized long requireOpenHandle() {
		if (this.handle == 0L) {
			throw new IllegalStateException("Metal compute pipeline is closed");
		}
		return this.handle;
	}
}
