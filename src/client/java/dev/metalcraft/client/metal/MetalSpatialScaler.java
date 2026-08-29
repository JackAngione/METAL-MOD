package dev.metalcraft.client.metal;

/** A reusable MetalFX spatial scaler with fixed input/output formats and dimensions. */
public final class MetalSpatialScaler implements AutoCloseable {
	public record Descriptor(
		MetalTexture.Format inputFormat,
		int inputWidth,
		int inputHeight,
		MetalTexture.Format outputFormat,
		int outputWidth,
		int outputHeight
	) {
		public Descriptor {
			if (inputFormat == null || outputFormat == null) {
				throw new NullPointerException("MetalFX formats");
			}
			if (!inputFormat.hasColorAspect() || !outputFormat.hasColorAspect()) {
				throw new IllegalArgumentException("MetalFX spatial scaling requires color formats");
			}
			if (inputWidth <= 0 || inputHeight <= 0 || outputWidth < inputWidth || outputHeight < inputHeight) {
				throw new IllegalArgumentException(
					"MetalFX output dimensions must be positive and no smaller than its input"
				);
			}
		}
	}

	private final MetalDevice device;
	private final Descriptor descriptor;
	private long handle;

	MetalSpatialScaler(final MetalDevice device, final long handle, final Descriptor descriptor) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A MetalFX spatial-scaler handle cannot be zero");
		}
		this.device = device;
		this.handle = handle;
		this.descriptor = descriptor;
	}

	public Descriptor descriptor() {
		return this.descriptor;
	}

	public synchronized void encode(
		final MetalCommandBuffer commands,
		final MetalTexture input,
		final MetalTexture output
	) {
		this.validateInput(input);
		MetalTexture.Descriptor target = output.descriptor();
		if (target.format() != this.descriptor.outputFormat()
			|| target.width() != this.descriptor.outputWidth() || target.height() != this.descriptor.outputHeight()) {
			throw new IllegalArgumentException("MetalFX output texture does not match the scaler descriptor");
		}
		commands.encodeSpatialScale(this, input, output);
	}

	public synchronized void encode(
		final MetalCommandBuffer commands,
		final MetalTexture input,
		final MetalDrawable output
	) {
		this.validateInput(input);
		if (output.surface().width() != this.descriptor.outputWidth()
			|| output.surface().height() != this.descriptor.outputHeight()
			|| this.descriptor.outputFormat() != MetalTexture.Format.BGRA8_UNORM) {
			throw new IllegalArgumentException("MetalFX drawable does not match the scaler descriptor");
		}
		commands.encodeSpatialScale(this, input, output);
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
			MetalNative.nReleaseSpatialScaler(this.handle);
			this.handle = 0L;
		}
		this.device.forget(this);
	}

	synchronized long requireOpenHandle() {
		if (this.handle == 0L) {
			throw new IllegalStateException("MetalFX spatial scaler is closed");
		}
		return this.handle;
	}

	private void validateInput(final MetalTexture input) {
		MetalTexture.Descriptor source = input.descriptor();
		if (source.format() != this.descriptor.inputFormat()
			|| source.width() != this.descriptor.inputWidth() || source.height() != this.descriptor.inputHeight()) {
			throw new IllegalArgumentException("MetalFX input texture does not match the scaler descriptor");
		}
	}
}
