package dev.metalcraft.client.metal;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.joml.Vector4fc;

/** Persistent Blaze3D encoder that rotates owned Metal command buffers on submit. */
final class MetalCommandEncoder implements CommandEncoderBackend, AutoCloseable {
	/**
	 * Whether consecutive passes sharing their attachments continue in one Metal encoder.
	 *
	 * <p>A kill switch rather than a setting, for the reason the command-batching one exists:
	 * turning it off restores the pass-per-encoder path exactly, which is what makes the two
	 * comparable back to back in one session - the only comparison this renderer's run-to-run
	 * spread admits.
	 */
	private static final boolean PASS_MERGING = Boolean.parseBoolean(System.getProperty("metalcraft.passMerging", "true"));

	private final MetalGpuDevice device;
	private final MetalCommandQueue commandQueue;
	private final MetalTransientMemory transientMemory;
	private final List<AutoCloseable> temporaryResources = new ArrayList<>();
	private final List<Runnable> completionCallbacks = new ArrayList<>();
	private MetalCommandBuffer commands;
	private MetalRenderPass renderPass;
	/** The attachments {@link #renderPass} was opened against, for {@link #canMerge}. */
	private MetalRenderPass.Descriptor renderPassDescriptor;
	/**
	 * A pass Blaze3D has submitted whose Metal encoder is deliberately still open.
	 *
	 * <p>Ending a render encoder on a tile renderer is not free: it resolves the tile memory out to
	 * the attachments, and beginning the next one loads them back in. At 3840x2104 that is a full
	 * colour and depth round trip per pass, and the sky alone is five consecutive passes - stars,
	 * moon, sun, sunrise/sunset and the disc - drawing very little geometry each into exactly the
	 * same attachments with no clear between them.
	 *
	 * <p>So the encoder is left open at {@code submitRenderPass} and only ended when something
	 * actually needs it ended. If the next thing is a pass that {@link #canMerge} accepts, it
	 * continues into the same encoder and the round trip never happens.
	 */
	private MetalRenderPass deferredRenderPass;
	private MetalRenderPass.Descriptor deferredDescriptor;
	/** Reused across passes; only one pass can be active on an encoder at a time. */
	private MetalRenderPassBackend renderPassBackend;
	private boolean closed;

	MetalCommandEncoder(final MetalGpuDevice device, final MetalCommandQueue commandQueue) {
		this.device = device;
		this.commandQueue = commandQueue;
		this.transientMemory = new MetalTransientMemory(device, this);
	}

	@Override
	public void submit() {
		// Timed because this runs after Minecraft stops its own frame timer, so nothing upstream can
		// see it. Overlaps SUBMIT_WAIT, which is raised from inside when a readback forces a wait.
		long startedNs = MetalStallProbe.begin();
		this.finishSubmission(false);
		MetalStallProbe.end(MetalStallProbe.Source.SUBMIT, startedNs);
	}

	@Override
	public TransientMemory transientMemory() {
		return this.transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(final RenderPassDescriptor descriptor) {
		if (this.renderPass != null) {
			throw new IllegalStateException("A direct Metal render pass is already active");
		}
		List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colors = descriptor.colorAttachments();
		if (colors.size() > MetalRenderPass.MAX_COLOR_ATTACHMENTS) {
			throw new UnsupportedOperationException(
				"Direct Metal accepts at most " + MetalRenderPass.MAX_COLOR_ATTACHMENTS
					+ " color attachments, not " + colors.size()
			);
		}
		// Blaze3D's list is positional and may hold a null where the layout reserves an index the
		// pass does not write, so the attachment list keeps the same shape rather than compacting.
		List<MetalRenderPass.ColorAttachment> colorAttachments = new ArrayList<>(colors.size());
		MetalGpuTextureView firstColorView = null;
		for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> color : colors) {
			if (color == null) {
				colorAttachments.add(null);
				continue;
			}
			MetalGpuTextureView colorView = requireTextureView(color.textureView());
			if (firstColorView == null) {
				firstColorView = colorView;
			}
			Optional<Vector4fc> colorClear = color.clearValue();
			colorAttachments.add(new MetalRenderPass.ColorAttachment(
				colorView.texture().metal(),
				colorView.baseMipLevel(),
				colorClear.isPresent() ? MetalRenderPass.LoadAction.CLEAR : MetalRenderPass.LoadAction.LOAD,
				MetalRenderPass.StoreAction.STORE,
				colorClear.map(Vector4fc::x).orElse(0.0F),
				colorClear.map(Vector4fc::y).orElse(0.0F),
				colorClear.map(Vector4fc::z).orElse(0.0F),
				colorClear.map(Vector4fc::w).orElse(0.0F)
			));
		}
		if (firstColorView == null) {
			throw new UnsupportedOperationException("Direct Metal requires at least one color attachment on a render pass");
		}
		MetalGpuTextureView colorView = firstColorView;
		MetalRenderPass.DepthAttachment depthAttachment = null;
		if (descriptor.depthAttachment() != null) {
			RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
			MetalGpuTextureView depthView = requireTextureView(depth.textureView());
			depthAttachment = new MetalRenderPass.DepthAttachment(
				depthView.texture().metal(),
				depthView.baseMipLevel(),
				depth.clearValue().isPresent() ? MetalRenderPass.LoadAction.CLEAR : MetalRenderPass.LoadAction.LOAD,
				MetalRenderPass.StoreAction.STORE,
				depth.clearValue().orElse(1.0)
			);
		}
		MetalRenderPass.Descriptor next = new MetalRenderPass.Descriptor(colorAttachments, depthAttachment);
		if (PASS_MERGING && this.deferredRenderPass != null && canMerge(this.deferredDescriptor, next)) {
			// Continue into the encoder the previous pass left open, so its attachments are never
			// resolved out and loaded back in.
			this.renderPass = this.deferredRenderPass;
			this.deferredRenderPass = null;
			this.deferredDescriptor = null;
			MetalStallProbe.record(MetalStallProbe.Source.RENDER_PASS_MERGE, 0L, 1L, 0L);
		} else {
			// commands() ends any deferred pass first, which is what keeps a non-mergeable pass
			// ordered after the one before it.
			this.renderPass = this.commands().beginRenderPass(next, passKind(descriptor));
		}
		this.renderPassDescriptor = next;
		RenderPass.RenderArea area = descriptor.renderArea;
		this.renderPass.setScissor(area.x(), area.y(), area.width(), area.height());
		if (this.renderPassBackend == null) this.renderPassBackend = new MetalRenderPassBackend(this.device);
		this.renderPassBackend.reset(this.renderPass, area, colorView.getWidth(0), colorView.getHeight(0), depthAttachment != null);
		return this.renderPassBackend;
	}

	@Override
	public void submitRenderPass() {
		if (this.renderPass == null) {
			throw new IllegalStateException("No direct Metal render pass is active");
		}
		// Held open rather than closed. Nothing may reach the command buffer without going through
		// commands(), which ends it, so deferring cannot reorder this pass against anything.
		this.deferredRenderPass = this.renderPass;
		this.deferredDescriptor = this.renderPassDescriptor;
		this.renderPass = null;
		this.renderPassDescriptor = null;
		if (this.renderPassBackend != null) this.renderPassBackend.finish();
	}

	/**
	 * Whether {@code next} can continue in the encoder {@code open} left behind.
	 *
	 * <p>Deliberately narrow: the same attachments at the same mip levels, and no clear on the new
	 * pass. A clear is the one thing an already-running encoder cannot be asked to do, and differing
	 * attachments would render into the wrong texture rather than merely slowly. The previous pass's
	 * load actions do not matter - whatever it loaded or cleared has already happened.
	 */
	private static boolean canMerge(final MetalRenderPass.Descriptor open, final MetalRenderPass.Descriptor next) {
		if (open == null || next == null) {
			return false;
		}
		if (open.colorAttachments().size() != next.colorAttachments().size()) {
			return false;
		}
		for (int index = 0; index < open.colorAttachments().size(); index++) {
			MetalRenderPass.ColorAttachment openColor = open.colorAttachments().get(index);
			MetalRenderPass.ColorAttachment nextColor = next.colorAttachments().get(index);
			if (openColor == null || nextColor == null) {
				if (openColor != nextColor) {
					return false;
				}
				continue;
			}
			if (openColor.target() != nextColor.target() || openColor.mipLevel() != nextColor.mipLevel()
				|| nextColor.loadAction() != MetalRenderPass.LoadAction.LOAD
				|| openColor.storeAction() != MetalRenderPass.StoreAction.STORE) {
				return false;
			}
		}
		MetalRenderPass.DepthAttachment openDepth = open.depthAttachment();
		MetalRenderPass.DepthAttachment nextDepth = next.depthAttachment();
		if (openDepth == null || nextDepth == null) {
			return openDepth == nextDepth;
		}
		return openDepth.texture() == nextDepth.texture()
			&& openDepth.mipLevel() == nextDepth.mipLevel()
			&& nextDepth.loadAction() == MetalRenderPass.LoadAction.LOAD
			&& openDepth.storeAction() == MetalRenderPass.StoreAction.STORE;
	}

	/** Ends the pass left open by {@link #submitRenderPass}, resolving its attachments. */
	private void endDeferredRenderPass() {
		MetalRenderPass deferred = this.deferredRenderPass;
		if (deferred != null) {
			this.deferredRenderPass = null;
			this.deferredDescriptor = null;
			deferred.close();
		}
	}

	@Override
	public void clearColorTexture(final GpuTexture colorTexture, final Vector4fc clearColor) {
		this.clear(colorTexture, clearColor, null, 1.0);
	}

	@Override
	public void clearColorAndDepthTextures(
		final GpuTexture colorTexture,
		final Vector4fc clearColor,
		final GpuTexture depthTexture,
		final double clearDepth
	) {
		this.clear(colorTexture, clearColor, depthTexture, clearDepth);
	}

	@Override
	public void clearColorAndDepthTextures(
		final GpuTexture colorTexture,
		final Vector4fc clearColor,
		final GpuTexture depthTexture,
		final double clearDepth,
		final int regionX,
		final int regionY,
		final int regionWidth,
		final int regionHeight
	) {
		int width = colorTexture.getWidth(0);
		int height = colorTexture.getHeight(0);
		if (regionX == 0 && regionY == 0 && regionWidth == width && regionHeight == height) {
			this.clear(colorTexture, clearColor, depthTexture, clearDepth);
			return;
		}
		if (regionX < 0 || regionY < 0 || regionWidth <= 0 || regionHeight <= 0
			|| regionX + regionWidth > width || regionY + regionHeight > height) {
			throw new IllegalArgumentException("Metal attachment clear region lies outside the color attachment");
		}
		MetalGpuTexture color = requireTexture(colorTexture);
		MetalGpuTexture depth = requireTexture(depthTexture);
		if (depthTexture.getWidth(0) < regionX + regionWidth || depthTexture.getHeight(0) < regionY + regionHeight) {
			throw new IllegalArgumentException("Metal attachment clear region lies outside the depth attachment");
		}
		ByteBuffer parameters = ByteBuffer.allocate(MetalRegionClear.PARAMETER_BYTES).order(ByteOrder.nativeOrder());
		MetalRegionClear.writeParameters(
			parameters, clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w(), (float) clearDepth
		);
		GpuBufferSlice staged = this.transientMemory.uploadGpu(
			parameters.flip(), MetalRegionClear.PARAMETER_ALIGNMENT, GpuBuffer.USAGE_UNIFORM
		);
		this.device.regionClear().clear(
			this.commands(), color.metal(), depth.metal(), regionX, regionY, regionWidth, regionHeight,
			requireBuffer(staged.buffer()).metal(), staged.offset()
		);
	}

	@Override
	public void clearDepthTexture(final GpuTexture depthTexture, final double clearDepth) {
		MetalGpuTexture depth = requireTexture(depthTexture);
		// A depth-only pass. This previously created a full-size BGRA scratch render target for every
		// clear purely to satisfy the descriptor, which at 3840x2160 allocated and released 33 MB of
		// texture per call on the render path.
		try (MetalRenderPass pass = this.commands().beginRenderPass(MetalRenderPass.Descriptor.depthOnly(
			new MetalRenderPass.DepthAttachment(depth.metal(), MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, clearDepth)
		), MetalPassCensus.kindFor("(depth clear)"))) {
			// Beginning and ending the pass performs the clear.
		}
	}

	@Override
	public void writeToBuffer(final GpuBufferSlice destination, final ByteBuffer data) {
		MetalGpuBuffer target = requireBuffer(destination.buffer());
		int length = data.remaining();
		long startedNs = MetalStallProbe.begin();
		try (GpuBufferSlice.MappedView mapping = this.transientMemory.allocateStaging(
			length, 16L, GpuBuffer.USAGE_COPY_SRC, length, 1L
		)) {
			mapping.data().put(data.duplicate());
			MetalStallProbe.end(MetalStallProbe.Source.UPLOAD_COPY, startedNs, length);
			GpuBufferSlice staging = mapping.slice();
			this.commands().copyBuffer(
				requireBuffer(staging.buffer()).metal(), staging.offset(), target.metal(), destination.offset(), length
			);
		}
	}

	@Override
	public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
		this.commands().copyBuffer(
			requireBuffer(source.buffer()).metal(), source.offset(), requireBuffer(target.buffer()).metal(), target.offset(), source.length()
		);
	}

	@Override
	public void writeToTexture(
		final GpuTexture destination,
		final ByteBuffer source,
		final int mipLevel,
		final int depthOrLayer,
		final int destX,
		final int destY,
		final int width,
		final int height
	) {
		MetalGpuTexture texture = requireTexture(destination);
		int tightRow = Math.multiplyExact(width, destination.getFormat().blockSize());
		int paddedRow = alignedRow(tightRow);
		long stagingSize = Math.multiplyExact((long)paddedRow, height);
		long startedNs = MetalStallProbe.begin();
		try (GpuBufferSlice.MappedView mapping = this.transientMemory.allocateStaging(
			stagingSize, 256L, GpuBuffer.USAGE_COPY_SRC, stagingSize, 1L
		)) {
			copyRows(source.duplicate(), mapping.data(), tightRow, paddedRow, height);
			MetalStallProbe.end(MetalStallProbe.Source.UPLOAD_COPY, startedNs, stagingSize);
			GpuBufferSlice staging = mapping.slice();
			this.commands().copyBufferToTextureRegion(
				requireBuffer(staging.buffer()).metal(), staging.offset(), paddedRow,
				texture.metal(), mipLevel, depthOrLayer, destX, destY, width, height
			);
		}
	}

	@Override
	public void copyBufferToTexture(
		final GpuBufferSlice source,
		final int sourceX,
		final int sourceY,
		final int sourceWidth,
		final int sourceHeight,
		final GpuTexture destination,
		final int destinationX,
		final int destinationY,
		final int copyWidth,
		final int copyHeight,
		final int mipLevel,
		final int arrayLayer
	) {
		MetalGpuBuffer input = requireBuffer(source.buffer());
		MetalGpuTexture texture = requireTexture(destination);
		int bytesPerPixel = destination.getFormat().blockSize();
		int tightRow = Math.multiplyExact(sourceWidth, bytesPerPixel);
		int paddedRow = alignedRow(Math.multiplyExact(copyWidth, bytesPerPixel));
		long startingOffset = source.offset() + Math.multiplyExact((long)sourceY * sourceWidth + sourceX, bytesPerPixel);
		MetalBuffer staging = this.device.metal().createBuffer(Math.multiplyExact((long)paddedRow, copyHeight), MetalBuffer.StorageMode.PRIVATE);
		this.temporaryResources.add(staging);
		for (int row = 0; row < copyHeight; row++) {
			this.commands().copyBuffer(input.metal(), startingOffset + (long)row * tightRow, staging, (long)row * paddedRow, (long)copyWidth * bytesPerPixel);
		}
		this.commands().copyBufferToTextureRegion(
			staging, 0L, paddedRow, texture.metal(), mipLevel, arrayLayer,
			destinationX, destinationY, copyWidth, copyHeight
		);
	}

	@Override
	public void copyTextureToBuffer(final GpuTexture source, final GpuBuffer destination, final long offset, final Runnable callback, final int mipLevel) {
		this.copyTextureToBuffer(source, destination, offset, callback, mipLevel, 0, 0, source.getWidth(mipLevel), source.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(
		final GpuTexture source,
		final GpuBuffer destination,
		final long offset,
		final Runnable callback,
		final int mipLevel,
		final int x,
		final int y,
		final int width,
		final int height
	) {
		MetalGpuTexture texture = requireTexture(source);
		MetalGpuBuffer output = requireBuffer(destination);
		int tightRow = Math.multiplyExact(width, source.getFormat().blockSize());
		int paddedRow = alignedRow(tightRow);
		if (tightRow == paddedRow) {
			this.commands().copyTextureToBufferRegion(texture.metal(), mipLevel, x, y, width, height, output.metal(), offset, paddedRow);
		} else {
			MetalBuffer staging = this.device.metal().createBuffer(Math.multiplyExact((long)paddedRow, height), MetalBuffer.StorageMode.PRIVATE);
			this.temporaryResources.add(staging);
			this.commands().copyTextureToBufferRegion(texture.metal(), mipLevel, x, y, width, height, staging, 0L, paddedRow);
			for (int row = 0; row < height; row++) {
				this.commands().copyBuffer(staging, (long)row * paddedRow, output.metal(), offset + (long)row * tightRow, tightRow);
			}
		}
		this.completionCallbacks.add(callback);
	}

	@Override
	public void copyTextureToTexture(
		final GpuTexture source,
		final GpuTexture destination,
		final int mipLevel,
		final int destX,
		final int destY,
		final int sourceX,
		final int sourceY,
		final int width,
		final int height
	) {
		this.commands().copyTexture(
			requireTexture(source).metal(), requireTexture(destination).metal(), mipLevel, sourceX, sourceY, destX, destY, width, height
		);
	}

	@Override
	public GpuFence createFence() {
		MetalFence fence = this.device.metal().createFence();
		long value = fence.reserveValue();
		this.commands().signal(fence, value);
		return new MetalGpuFence(fence, value);
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		this.commands().writeTimestamp(requireQueryPool(pool), index);
	}

	void blitToDrawable(final MetalTexture texture, final MetalDrawable drawable) {
		this.commands().blitToDrawable(texture, drawable);
		this.commands().present(drawable);
	}

	void finishPendingWork() {
		this.finishSubmission(true);
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.finishSubmission(true);
			this.transientMemory.close();
			this.closed = true;
		}
	}

	private void clear(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture, final double clearDepth) {
		MetalGpuTexture color = requireTexture(colorTexture);
		MetalRenderPass.DepthAttachment depthAttachment = depthTexture == null ? null : new MetalRenderPass.DepthAttachment(
			requireTexture(depthTexture).metal(), MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, clearDepth
		);
		try (MetalRenderPass pass = this.commands().beginRenderPass(new MetalRenderPass.Descriptor(
			MetalRenderPass.ColorAttachment.clear(color.metal(), clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w()), depthAttachment
		))) {
			// Beginning and ending the pass performs the clear.
		}
	}

	/**
	 * The command buffer, with any deferred render pass ended first.
	 *
	 * <p>Every command-buffer operation in this class reaches it through here, which is what makes
	 * holding a render encoder open safe: a copy, a blit, a present, a fence, a timestamp or a
	 * non-mergeable pass all end the deferred pass before they encode, so nothing can be reordered
	 * ahead of the passes already recorded into it. {@code createRenderPass} is the one caller that
	 * may skip this, and only when {@link #canMerge} says the work continues in the same encoder.
	 */
	private MetalCommandBuffer commands() {
		if (this.closed) throw new IllegalStateException("Metal command encoder is closed");
		this.endDeferredRenderPass();
		if (this.commands == null) this.commands = this.commandQueue.createCommandBuffer();
		return this.commands;
	}

	private void finishSubmission(final boolean wait) {
		if (this.renderPass != null) throw new IllegalStateException("Cannot submit with an active Metal render pass");
		this.endDeferredRenderPass();
		MetalCommandBuffer submitted = this.commands;
		boolean completed = wait || !this.completionCallbacks.isEmpty();
		if (submitted != null) {
			submitted.commit();
			if (completed) {
				long startedNs = MetalStallProbe.begin();
				submitted.waitUntilCompleted();
				MetalStallProbe.end(MetalStallProbe.Source.SUBMIT_WAIT, startedNs);
			}
		}
		boolean retainedByTransientMemory = this.transientMemory.finishSubmission(submitted, completed);
		if (submitted != null && !retainedByTransientMemory) submitted.close();
		this.commands = null;
		for (Runnable callback : this.completionCallbacks) callback.run();
		this.completionCallbacks.clear();
		for (AutoCloseable resource : this.temporaryResources) close(resource);
		this.temporaryResources.clear();
		if (wait) this.transientMemory.waitForAllFrames();
	}

	private static void close(final AutoCloseable resource) {
		try {
			resource.close();
		} catch (Exception error) {
			throw new IllegalStateException("Failed to close a temporary Metal resource", error);
		}
	}

	private static int alignedRow(final int row) {
		return Math.addExact(row, 255) & -256;
	}

	private static void copyRows(final ByteBuffer source, final ByteBuffer destination, final int tightRow, final int paddedRow, final int height) {
		for (int row = 0; row < height; row++) {
			ByteBuffer bytes = source.slice(source.position() + row * tightRow, tightRow);
			destination.position(row * paddedRow).put(bytes);
		}
	}

	private static MetalGpuBuffer requireBuffer(final GpuBuffer buffer) {
		if (buffer instanceof MetalGpuBuffer metal) return metal;
		throw new IllegalArgumentException("Buffer does not belong to the direct Metal backend");
	}

	private static MetalGpuTexture requireTexture(final GpuTexture texture) {
		if (texture instanceof MetalGpuTexture metal) return metal;
		throw new IllegalArgumentException("Texture does not belong to the direct Metal backend");
	}

	private static MetalGpuTextureView requireTextureView(final com.mojang.blaze3d.textures.GpuTextureView view) {
		if (view instanceof MetalGpuTextureView metal) return metal;
		throw new IllegalArgumentException("Texture view does not belong to the direct Metal backend");
	}

	/**
	 * The pass census kind for a Blaze3D pass, from the label it was created with.
	 *
	 * <p>The label is a supplier, and resolving it allocates, so it is asked for only while a
	 * capture is running - which is also the only time the pass carries counter samples.
	 */
	private static int passKind(final RenderPassDescriptor descriptor) {
		if (!MetalStallProbe.isEnabled()) {
			return MetalPassCensus.UNTIMED_KIND;
		}
		Supplier<String> label = descriptor.label();
		return MetalPassCensus.kindFor(label == null ? "(unlabelled)" : label.get());
	}

	private static MetalTimestampQueryPool requireQueryPool(final GpuQueryPool pool) {
		if (pool instanceof MetalTimestampQueryPool metal) return metal;
		throw new IllegalArgumentException("Query pool does not belong to the direct Metal backend");
	}
}
