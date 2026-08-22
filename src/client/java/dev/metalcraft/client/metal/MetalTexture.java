package dev.metalcraft.client.metal;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

	/** An owned two-dimensional or cubemap {@code MTLTexture}. */
public final class MetalTexture implements MetalRenderPass.ColorTarget, AutoCloseable {
	public static final int USAGE_SHADER_READ = 1;
	public static final int USAGE_SHADER_WRITE = 2;
	public static final int USAGE_RENDER_TARGET = 4;
	public static final int USAGE_ALL = USAGE_SHADER_READ | USAGE_SHADER_WRITE | USAGE_RENDER_TARGET;

	public enum Format {
		BGRA8_UNORM(0, 4, true, false, false),
		R8_UNORM(1, 1, true, false, false),
		R8_SNORM(2, 1, true, false, false),
		R8_UINT(3, 1, true, false, false),
		R8_SINT(4, 1, true, false, false),
		RG8_UNORM(5, 2, true, false, false),
		RG8_SNORM(6, 2, true, false, false),
		RG8_UINT(7, 2, true, false, false),
		RG8_SINT(8, 2, true, false, false),
		RGBA8_UNORM(9, 4, true, false, false),
		RGBA8_SNORM(10, 4, true, false, false),
		RGBA8_UINT(11, 4, true, false, false),
		RGBA8_SINT(12, 4, true, false, false),
		R16_UNORM(13, 2, true, false, false),
		R16_SNORM(14, 2, true, false, false),
		R16_UINT(15, 2, true, false, false),
		R16_SINT(16, 2, true, false, false),
		R16_FLOAT(17, 2, true, false, false),
		RG16_UNORM(18, 4, true, false, false),
		RG16_SNORM(19, 4, true, false, false),
		RG16_UINT(20, 4, true, false, false),
		RG16_SINT(21, 4, true, false, false),
		RG16_FLOAT(22, 4, true, false, false),
		RGBA16_UNORM(23, 8, true, false, false),
		RGBA16_SNORM(24, 8, true, false, false),
		RGBA16_UINT(25, 8, true, false, false),
		RGBA16_SINT(26, 8, true, false, false),
		RGBA16_FLOAT(27, 8, true, false, false),
		R32_UINT(28, 4, true, false, false),
		R32_SINT(29, 4, true, false, false),
		R32_FLOAT(30, 4, true, false, false),
		RG32_UINT(31, 8, true, false, false),
		RG32_SINT(32, 8, true, false, false),
		RG32_FLOAT(33, 8, true, false, false),
		RGBA32_UINT(34, 16, true, false, false),
		RGBA32_SINT(35, 16, true, false, false),
		RGBA32_FLOAT(36, 16, true, false, false),
		RGB10A2_UNORM(37, 4, true, false, false),
		RGB10A2_UINT(38, 4, true, false, false),
		RG11B10_FLOAT(39, 4, true, false, false),
		DEPTH16_UNORM(40, 2, false, true, false),
		DEPTH32_FLOAT(41, 4, false, true, false),
		STENCIL8(42, 1, false, false, true),
		DEPTH24_UNORM_STENCIL8(43, 4, false, true, true),
		DEPTH32_FLOAT_STENCIL8(44, 8, false, true, true);

		private final int nativeCode;
		private final int bytesPerPixel;
		private final boolean color;
		private final boolean depth;
		private final boolean stencil;

		Format(
			final int nativeCode,
			final int bytesPerPixel,
			final boolean color,
			final boolean depth,
			final boolean stencil
		) {
			this.nativeCode = nativeCode;
			this.bytesPerPixel = bytesPerPixel;
			this.color = color;
			this.depth = depth;
			this.stencil = stencil;
		}

		public int bytesPerPixel() {
			return this.bytesPerPixel;
		}

		public boolean hasColorAspect() {
			return this.color;
		}

		public boolean hasDepthAspect() {
			return this.depth;
		}

		public boolean hasStencilAspect() {
			return this.stencil;
		}

		int nativeCode() {
			return this.nativeCode;
		}
	}

	public record Descriptor(
		Format format,
		int width,
		int height,
		int depthOrLayers,
		int mipLevels,
		int usage,
		boolean cubemap
	) {
		public Descriptor(final Format format, final int width, final int height, final int mipLevels) {
			this(format, width, height, 1, mipLevels, USAGE_SHADER_READ | USAGE_RENDER_TARGET, false);
		}

		public Descriptor(final Format format, final int width, final int height, final int mipLevels, final int usage) {
			this(format, width, height, 1, mipLevels, usage, false);
		}

		public Descriptor {
			if (format == null) {
				throw new NullPointerException("format");
			}
			if (width <= 0 || height <= 0 || mipLevels <= 0) {
				throw new IllegalArgumentException("Metal texture dimensions and mip levels must be positive");
			}
			if (cubemap) {
				if (width != height || depthOrLayers != 6) {
					throw new IllegalArgumentException("A Metal cubemap requires six square faces");
				}
			} else if (depthOrLayers != 1) {
				throw new IllegalArgumentException("Only two-dimensional and single-cubemap Metal textures are supported");
			}
			int maximumMipLevels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
			if (mipLevels > maximumMipLevels) {
				throw new IllegalArgumentException("Too many mip levels for the texture dimensions");
			}
			if ((usage & ~USAGE_ALL) != 0) {
				throw new IllegalArgumentException("Metal texture usage contains unknown bits");
			}
		}

		/** Approximate device footprint of every mip level and layer, for allocation telemetry. */
		public long byteSize() {
			long total = 0L;
			for (int level = 0; level < this.mipLevels; level++) {
				long levelWidth = Math.max(1, this.width >> level);
				long levelHeight = Math.max(1, this.height >> level);
				total = Math.addExact(total, levelWidth * levelHeight * this.format.bytesPerPixel());
			}
			return Math.multiplyExact(total, this.depthOrLayers);
		}
	}

	private final MetalDevice device;
	private final Descriptor descriptor;
	private final Set<MetalTextureView> views = Collections.newSetFromMap(new IdentityHashMap<>());
	private long handle;
	private boolean closing;

	MetalTexture(final MetalDevice device, final long handle, final Descriptor descriptor) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal texture handle cannot be zero");
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

	public synchronized MetalTextureView createView() {
		return this.createView(0, this.descriptor.mipLevels());
	}

	public synchronized MetalTextureView createView(final int baseMipLevel, final int mipLevels) {
		if (baseMipLevel < 0 || mipLevels <= 0 || baseMipLevel > this.descriptor.mipLevels() || mipLevels > this.descriptor.mipLevels() - baseMipLevel) {
			throw new IllegalArgumentException("Metal texture-view mip range is out of bounds");
		}
		long viewHandle = MetalNative.nCreateTextureView(this.requireOpenHandle(), baseMipLevel, mipLevels);
		if (viewHandle == 0L) {
			throw new IllegalStateException("Metal did not create a texture view");
		}
		MetalTextureView view = new MetalTextureView(this, viewHandle);
		this.views.add(view);
		return view;
	}

	public void upload(final MetalCommandQueue commandQueue, final int mipLevel, final ByteBuffer source) {
		this.requireSameDevice(commandQueue);
		int width = this.widthAtMip(mipLevel);
		int height = this.heightAtMip(mipLevel);
		int tightBytesPerRow = Math.multiplyExact(width, this.bytesPerPixel());
		int requiredBytes = Math.multiplyExact(tightBytesPerRow, height);
		if (source.remaining() < requiredBytes) {
			throw new IllegalArgumentException("Texture upload source does not contain enough bytes");
		}
		int stagingBytesPerRow = alignedBytesPerRow(tightBytesPerRow);
		long stagingSize = Math.multiplyExact((long)stagingBytesPerRow, height);
		try (MetalBuffer staging = this.device.createBuffer(stagingSize, MetalBuffer.StorageMode.SHARED)) {
			try (MetalBuffer.Mapping mapping = staging.map()) {
				copyRows(source.duplicate(), mapping.bytes(), tightBytesPerRow, stagingBytesPerRow, height, false);
			}
			try (MetalCommandBuffer commands = commandQueue.createCommandBuffer()) {
				commands.copyBufferToTexture(staging, 0L, stagingBytesPerRow, this, mipLevel);
				commands.commitAndWait();
			}
		}
	}

	public ByteBuffer readback(final MetalCommandQueue commandQueue, final int mipLevel) {
		this.requireSameDevice(commandQueue);
		int width = this.widthAtMip(mipLevel);
		int height = this.heightAtMip(mipLevel);
		int tightBytesPerRow = Math.multiplyExact(width, this.bytesPerPixel());
		int stagingBytesPerRow = alignedBytesPerRow(tightBytesPerRow);
		long stagingSize = Math.multiplyExact((long)stagingBytesPerRow, height);
		ByteBuffer result = ByteBuffer.allocateDirect(Math.multiplyExact(tightBytesPerRow, height));
		try (MetalBuffer staging = this.device.createBuffer(stagingSize, MetalBuffer.StorageMode.SHARED)) {
			try (MetalCommandBuffer commands = commandQueue.createCommandBuffer()) {
				commands.copyTextureToBuffer(this, mipLevel, staging, 0L, stagingBytesPerRow);
				commands.commitAndWait();
			}
			try (MetalBuffer.Mapping mapping = staging.map()) {
				copyRows(mapping.bytes(), result, tightBytesPerRow, stagingBytesPerRow, height, true);
			}
		}
		return result.flip();
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	@Override
	public void close() {
		List<MetalTextureView> ownedViews;
		synchronized (this) {
			if (this.handle == 0L || this.closing) {
				return;
			}
			this.closing = true;
			ownedViews = new ArrayList<>(this.views);
		}
		for (MetalTextureView view : ownedViews) {
			view.close();
		}
		synchronized (this) {
			long startedNs = MetalStallProbe.begin();
			MetalNative.nReleaseTexture(this.handle);
			MetalStallProbe.end(MetalStallProbe.Source.RESOURCE_RELEASE, startedNs, this.descriptor.byteSize());
			this.handle = 0L;
			this.views.clear();
			this.closing = false;
		}
		this.device.forget(this);
	}

	synchronized void forget(final MetalTextureView view) {
		this.views.remove(view);
	}

	synchronized long requireOpenHandle() {
		if (this.handle == 0L || this.closing) {
			throw new IllegalStateException("Metal texture is closed");
		}
		return this.handle;
	}

	void validateTransfer(final int mipLevel, final long bytesPerRow, final long bufferSize, final long bufferOffset) {
		int width = this.widthAtMip(mipLevel);
		int height = this.heightAtMip(mipLevel);
		long minimumBytesPerRow = Math.multiplyExact((long)width, this.bytesPerPixel());
		if (bytesPerRow < minimumBytesPerRow || bytesPerRow % 256L != 0L) {
			throw new IllegalArgumentException("Metal texture transfer row pitch must cover one row and be 256-byte aligned");
		}
		long transferSize = Math.multiplyExact(bytesPerRow, height);
		MetalBuffer.checkRange(bufferSize, bufferOffset, transferSize, "texture transfer");
	}

	private int widthAtMip(final int mipLevel) {
		this.validateMipLevel(mipLevel);
		return Math.max(1, this.descriptor.width() >> mipLevel);
	}

	private int heightAtMip(final int mipLevel) {
		this.validateMipLevel(mipLevel);
		return Math.max(1, this.descriptor.height() >> mipLevel);
	}

	private void validateMipLevel(final int mipLevel) {
		if (mipLevel < 0 || mipLevel >= this.descriptor.mipLevels()) {
			throw new IllegalArgumentException("Metal texture mip level is out of bounds");
		}
	}

	private int bytesPerPixel() {
		return this.descriptor.format().bytesPerPixel();
	}

	private void requireSameDevice(final MetalCommandQueue commandQueue) {
		if (commandQueue.device() != this.device) {
			throw new IllegalArgumentException("Metal transfer queue and texture must belong to the same device");
		}
	}

	private static int alignedBytesPerRow(final int minimum) {
		return Math.addExact(minimum, 255) & -256;
	}

	private static void copyRows(
		final ByteBuffer source,
		final ByteBuffer destination,
		final int copiedBytesPerRow,
		final int sourceOrDestinationPitch,
		final int height,
		final boolean sourceIsPadded
	) {
		for (int row = 0; row < height; row++) {
			int sourceOffset = source.position() + row * (sourceIsPadded ? sourceOrDestinationPitch : copiedBytesPerRow);
			int destinationOffset = destination.position() + row * (sourceIsPadded ? copiedBytesPerRow : sourceOrDestinationPitch);
			ByteBuffer sourceRow = source.duplicate().position(sourceOffset).limit(sourceOffset + copiedBytesPerRow);
			destination.duplicate().position(destinationOffset).put(sourceRow);
		}
		if (sourceIsPadded) {
			destination.position(destination.position() + Math.multiplyExact(copiedBytesPerRow, height));
		} else {
			source.position(source.position() + Math.multiplyExact(copiedBytesPerRow, height));
		}
	}
}
