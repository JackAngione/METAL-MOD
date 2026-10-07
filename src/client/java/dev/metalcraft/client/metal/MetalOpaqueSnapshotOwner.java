package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import java.util.Optional;

/**
 * Owns the stored, single-sample opaque inputs sampled by later water passes.
 *
 * <p>A successful capture contains a distinct {@code RGBA16_FLOAT} color copy and
 * {@code D32_FLOAT} depth copy at the source extent. Incompatible or missing frame inputs must
 * use the caller's normal forward fallback: they invalidate this owner and produce no snapshot.
 * No operation in this class waits for the GPU or reads pixels back to the CPU.
 */
public final class MetalOpaqueSnapshotOwner implements AutoCloseable {
	private static final int DESTINATION_USAGE = GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;

	private final MetalGpuDevice device;
	private Attachments attachments;
	private Snapshot current;
	private long generation;
	private boolean closed;

	private record Attachments(
		MetalGpuTexture color,
		MetalGpuTextureView colorView,
		MetalGpuTexture depth,
		MetalGpuTextureView depthView
	) implements AutoCloseable {
		@Override
		public void close() {
			this.colorView.close();
			this.depthView.close();
			this.color.close();
			this.depth.close();
		}
	}

	public MetalOpaqueSnapshotOwner(final MetalGpuDevice device) {
		if (device == null) throw new NullPointerException("device");
		this.device = device;
	}

	/**
	 * Encodes an opaque color/depth copy and publishes it as the only current snapshot.
	 *
	 * @return the new snapshot, or empty when the inputs cannot satisfy the stored snapshot
	 * contract. An empty result also invalidates any snapshot from an earlier frame.
	 */
	Optional<Snapshot> capture(
		final MetalCommandEncoder encoder,
		final MetalGpuTextureView opaqueColor,
		final MetalGpuTextureView opaqueDepth
	) {
		this.requireOpen();
		if (encoder == null || !this.isCompatible(opaqueColor, GpuFormat.RGBA16_FLOAT)
			|| !this.isCompatible(opaqueDepth, GpuFormat.D32_FLOAT)
			|| opaqueColor.getWidth(0) != opaqueDepth.getWidth(0)
			|| opaqueColor.getHeight(0) != opaqueDepth.getHeight(0)
			|| this.aliasesDestination(opaqueColor) || this.aliasesDestination(opaqueDepth)) {
			this.invalidate();
			return Optional.empty();
		}

		int width = opaqueColor.getWidth(0);
		int height = opaqueColor.getHeight(0);
		// The destination is about to be overwritten. From here until both copies are encoded there
		// must be no published snapshot, including when allocation or command encoding throws.
		this.invalidate();
		this.prepare(width, height);
		encoder.copyTextureToTexture(opaqueColor.texture(), this.attachments.color(), 0, 0, 0, 0, 0, width, height);
		encoder.copyTextureToTexture(opaqueDepth.texture(), this.attachments.depth(), 0, 0, 0, 0, 0, width, height);
		Snapshot snapshot = new Snapshot(this, ++this.generation, width, height);
		this.current = snapshot;
		return Optional.of(snapshot);
	}

	/** Returns the captured inputs only while their generation remains current. */
	public Optional<Snapshot> current() {
		return this.closed ? Optional.empty() : Optional.ofNullable(this.current);
	}

	/** Makes stale pixels unavailable while retaining same-size storage for the next frame. */
	public void invalidate() {
		if (this.closed) return;
		this.generation++;
		this.current = null;
	}

	private boolean isCompatible(final MetalGpuTextureView view, final GpuFormat format) {
		if (view == null || view.isClosed() || view.gpuFormat() != format) return false;
		MetalGpuTexture texture = view.texture();
		if (texture.isClosed()) return false;
		MetalTexture metal = texture.metal();
		MetalTexture.Descriptor descriptor = metal.descriptor();
		return metal.device() == this.device.metal()
			&& (texture.usage() & GpuTexture.USAGE_COPY_SRC) != 0
			&& view.baseMipLevel() == 0
			&& descriptor.width() > 0 && descriptor.height() > 0
			&& descriptor.depthOrLayers() == 1 && descriptor.mipLevels() == 1
			&& !descriptor.cubemap()
			&& descriptor.storageMode() == MetalTexture.StorageMode.PRIVATE;
	}

	private boolean aliasesDestination(final MetalGpuTextureView source) {
		if (source == null || this.attachments == null) return false;
		MetalTexture attachment = source.attachment();
		return attachment == this.attachments.color().metal() || attachment == this.attachments.depth().metal();
	}

	private void prepare(final int width, final int height) {
		if (this.attachments != null
			&& this.attachments.color().getWidth(0) == width
			&& this.attachments.color().getHeight(0) == height) return;

		MetalGpuTexture color = null, depth = null;
		MetalGpuTextureView colorView = null, depthView = null;
		try {
			color = (MetalGpuTexture)this.device.createTexture("Metal Mod opaque HDR color snapshot",
				DESTINATION_USAGE, GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
			colorView = (MetalGpuTextureView)this.device.createTextureView(color);
			depth = (MetalGpuTexture)this.device.createTexture("Metal Mod opaque depth snapshot",
				DESTINATION_USAGE, GpuFormat.D32_FLOAT, width, height, 1, 1);
			depthView = (MetalGpuTextureView)this.device.createTextureView(depth);
		} catch (RuntimeException error) {
			if (colorView != null) colorView.close();
			if (depthView != null) depthView.close();
			if (color != null) color.close();
			if (depth != null) depth.close();
			this.invalidate();
			throw error;
		}

		Attachments previous = this.attachments;
		this.attachments = new Attachments(color, colorView, depth, depthView);
		this.generation++;
		this.current = null;
		// Metal command buffers retain resources referenced by already encoded blits. Closing the
		// superseded Java owners therefore retires them without a CPU wait in the frame loop.
		if (previous != null) previous.close();
	}

	private void requireOpen() {
		if (this.closed) throw new IllegalStateException("Opaque snapshot owner is closed");
	}

	private void requireCurrent(final Snapshot snapshot) {
		if (this.closed || this.current != snapshot || snapshot.generation != this.generation) {
			throw new IllegalStateException("Opaque snapshot is stale");
		}
	}

	@Override
	public void close() {
		if (this.closed) return;
		this.generation++;
		this.current = null;
		this.closed = true;
		if (this.attachments != null) this.attachments.close();
		this.attachments = null;
	}

	/** Immutable, generation-checked view of one complete opaque capture. */
	public static final class Snapshot {
		private final MetalOpaqueSnapshotOwner owner;
		private final long generation;
		private final int width;
		private final int height;

		private Snapshot(final MetalOpaqueSnapshotOwner owner, final long generation, final int width, final int height) {
			this.owner = owner;
			this.generation = generation;
			this.width = width;
			this.height = height;
		}

		public MetalGpuTextureView color() {
			this.owner.requireCurrent(this);
			return this.owner.attachments.colorView();
		}

		public MetalGpuTextureView depth() {
			this.owner.requireCurrent(this);
			return this.owner.attachments.depthView();
		}

		public int width() {
			this.owner.requireCurrent(this);
			return this.width;
		}

		public int height() {
			this.owner.requireCurrent(this);
			return this.height;
		}
	}
}
