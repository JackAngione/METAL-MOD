package dev.metalcraft.client.metal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * Owns a stored, single-sample HDR world color/depth pair, separate from hand/HUD output.
 * Borrowed views are valid until resize, reload or device close. Encoded Metal commands retain
 * their native resources through GPU completion; this owner does not wait or read pixels.
 */
public final class MetalWorldTargets implements AutoCloseable {
	private static final int USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
	private final MetalGpuDevice device;
	private Attachments attachments;
	private boolean closed;

	private record Attachments(MetalGpuTexture color, MetalGpuTextureView colorView,
		MetalGpuTexture depth, MetalGpuTextureView depthView) implements AutoCloseable {
		@Override
		public void close() {
			this.colorView.close();
			this.depthView.close();
			this.color.close();
			this.depth.close();
		}
	}

	MetalWorldTargets(final MetalGpuDevice device) { this.device = device; }

	void resize(final int width, final int height) {
		if (this.closed) throw new IllegalStateException("World targets are closed");
		if (width <= 0 || height <= 0) throw new IllegalArgumentException("World target dimensions must be positive");
		if (this.attachments != null && this.color().getWidth(0) == width && this.color().getHeight(0) == height) return;
		MetalGpuTexture color = null, depth = null;
		MetalGpuTextureView colorView = null, depthView = null;
		try {
			color = (MetalGpuTexture)this.device.createTexture("Metal Mod HDR world color", USAGE,
				GpuFormat.RGBA16_FLOAT, width, height, 1, 1);
			colorView = (MetalGpuTextureView)this.device.createTextureView(color);
			depth = (MetalGpuTexture)this.device.createTexture("Metal Mod HDR world depth", USAGE,
				GpuFormat.D32_FLOAT, width, height, 1, 1);
			depthView = (MetalGpuTextureView)this.device.createTextureView(depth);
		} catch (RuntimeException error) {
			if (colorView != null) colorView.close();
			if (depthView != null) depthView.close();
			if (color != null) color.close();
			if (depth != null) depth.close();
			throw error;
		}
		Attachments previous = this.attachments;
		this.attachments = new Attachments(color, colorView, depth, depthView);
		if (previous != null) previous.close();
	}

	public MetalGpuTextureView color() { return this.requireAttachments().colorView(); }
	public MetalGpuTextureView depth() { return this.requireAttachments().depthView(); }

	private Attachments requireAttachments() {
		if (this.closed || this.attachments == null) throw new IllegalStateException("World targets are unavailable");
		return this.attachments;
	}

	@Override
	public void close() {
		this.closed = true;
		if (this.attachments != null) this.attachments.close();
		this.attachments = null;
	}
}
