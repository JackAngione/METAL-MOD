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
import dev.metalcraft.client.shader.SceneColor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
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
	private static final boolean ATTACHMENT_LIVENESS = Boolean.parseBoolean(System.getProperty("metalcraft.attachmentLiveness", "true"));
	private static final boolean PASS_MERGING = Boolean.parseBoolean(System.getProperty("metalcraft.passMerging", "true"));

	private final MetalGpuDevice device;
	private final MetalCommandQueue commandQueue;
	private final MetalTransientMemory transientMemory;
	private final ArrayDeque<PendingReadback> pendingReadbacks = new ArrayDeque<>();
	private final List<Runnable> completionCallbacks = new ArrayList<>();
	private MetalCommandBuffer commands;
	private @Nullable MetalFence resourceCompletion;
	private long resourceSubmission;
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
	private @Nullable DeferredResolveHook deferredResolve;
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
		// see it. Readback callbacks are polled after submission without forcing GPU completion.
		long startedNs = MetalStallProbe.begin();
		this.finishSubmission(false);
		MetalStallProbe.end(MetalStallProbe.Source.SUBMIT, startedNs);
	}

	@Override
	public TransientMemory transientMemory() {
		return this.transientMemory;
	}

	/** Render-owner only. Reserving ownership must not split an active or merged render pass. */
	long reserveResourceSubmission() {
		if (this.closed) throw new IllegalStateException("Metal command encoder is closed");
		if (this.resourceCompletion == null) this.resourceCompletion = this.device.metal().createFence();
		if (this.resourceSubmission == 0) this.resourceSubmission = this.resourceCompletion.reserveValue();
		return this.resourceSubmission;
	}

	long completedResourceSubmission() {
		if (this.closed) throw new IllegalStateException("Metal command encoder is closed");
		return this.resourceCompletion == null ? 0 : this.resourceCompletion.completedValue();
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
		List<MetalRenderPass.ColorAttachment> colorAttachments = new ArrayList<>(colors.size());
		MetalGpuTextureView firstColorView = null;
		for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> color : colors) {
			if (color == null) {
				colorAttachments.add(null);
				continue;
			}
			MetalGpuTextureView colorView = this.routedView(requireTextureView(color.textureView()));
			if (firstColorView == null) {
				firstColorView = colorView;
			}
			Optional<Vector4fc> colorClear = color.clearValue();
			if (colorClear.isPresent()) {
				colorClear = Optional.of(decodeWorldClear(colorView.texture().metal(), colorClear.get()));
			}
			boolean memoryless = colorView.texture().metal().isMemoryless();
			colorAttachments.add(new MetalRenderPass.ColorAttachment(
				colorView.texture().metal(),
				colorView.baseMipLevel(),
				colorClear.isPresent() ? MetalRenderPass.LoadAction.CLEAR
					: memoryless ? MetalRenderPass.LoadAction.DONT_CARE : MetalRenderPass.LoadAction.LOAD,
				memoryless ? MetalRenderPass.StoreAction.DONT_CARE : MetalRenderPass.StoreAction.STORE,
				colorClear.map(Vector4fc::x).orElse(0.0F),
				colorClear.map(Vector4fc::y).orElse(0.0F),
				colorClear.map(Vector4fc::z).orElse(0.0F),
				colorClear.map(Vector4fc::w).orElse(0.0F)
			));
		}
		MetalRenderPass.DepthAttachment depthAttachment = null;
		MetalGpuTextureView depthView = null;
		if (descriptor.depthAttachment() != null) {
			RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();
			depthView = this.routedView(requireTextureView(depth.textureView()));
			depthAttachment = new MetalRenderPass.DepthAttachment(
				depthView.texture().metal(),
				depthView.baseMipLevel(),
				depth.clearValue().isPresent() ? MetalRenderPass.LoadAction.CLEAR : MetalRenderPass.LoadAction.LOAD,
				MetalRenderPass.StoreAction.STORE,
				depth.clearValue().orElse(1.0)
			);
		}
		if (firstColorView == null && depthView == null) {
			throw new UnsupportedOperationException("Direct Metal requires at least one render attachment");
		}
		MetalRenderPass.Descriptor next = new MetalRenderPass.Descriptor(colorAttachments, depthAttachment, 0);
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
			this.endDeferredRenderPass(next);
			this.renderPass = this.commands().beginRenderPass(next, passKind(descriptor));
		}
		this.renderPassDescriptor = next;
		RenderPass.RenderArea area = descriptor.renderArea;
		this.renderPass.setScissor(area.x(), area.y(), area.width(), area.height());
		if (this.renderPassBackend == null) this.renderPassBackend = new MetalRenderPassBackend(this.device);
		MetalGpuTextureView sizeView = firstColorView != null ? firstColorView : depthView;
		MetalLinearWorldSession session = this.device.linearWorldSession();
		boolean hdrOwned = session != null && session.ownsTranslated(firstColorView, depthView);
		this.renderPassBackend.reset(this.renderPass, area, sizeView.getWidth(0), sizeView.getHeight(0), depthAttachment != null,
			colorAttachments.isEmpty() || colorAttachments.getFirst() == null ? null
				: ((MetalTexture)colorAttachments.getFirst().target()).descriptor().format(),
			hdrOwned);
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
		// A forced split must resolve memoryless attachments before the next world descriptor
		// is built, so its adapter knows to clear a fresh G-buffer rather than continue one.
		if (!PASS_MERGING) this.endDeferredRenderPass();
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
		if (open.renderTargetArrayLength() != next.renderTargetArrayLength() || open.colorAttachments().size() != next.colorAttachments().size()) {
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
			// Memoryless attachments continue only with DONT_CARE; they cannot LOAD or STORE.
			boolean memoryless = openColor.target() instanceof MetalTexture texture && texture.isMemoryless();
			MetalRenderPass.LoadAction continuingLoad = memoryless
				? MetalRenderPass.LoadAction.DONT_CARE : MetalRenderPass.LoadAction.LOAD;
			MetalRenderPass.StoreAction continuingStore = memoryless
				? MetalRenderPass.StoreAction.DONT_CARE : MetalRenderPass.StoreAction.STORE;
			if (openColor.target() != nextColor.target() || openColor.mipLevel() != nextColor.mipLevel() || openColor.arraySlice() != nextColor.arraySlice()
				|| nextColor.loadAction() != continuingLoad
				|| openColor.storeAction() != continuingStore) {
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
			&& openDepth.arraySlice() == nextDepth.arraySlice()
			&& nextDepth.loadAction() == MetalRenderPass.LoadAction.LOAD
			&& openDepth.storeAction() == MetalRenderPass.StoreAction.STORE;
	}

	void setDeferredResolve(final @Nullable DeferredResolveHook hook) {
		this.deferredResolve = hook;
	}

	/** Encodes a pending merged resolve without ending the deferred pass. */
	void flushDeferredResolve() {
		MetalRenderPass deferred = this.deferredRenderPass;
		DeferredResolveHook hook = this.deferredResolve;
		if (deferred != null && hook != null) {
			hook.encodeMergedResolve(deferred);
		}
	}

	/** Ends the pass left open by {@link #submitRenderPass}, resolving its attachments. */
	private void endDeferredRenderPass() {
		this.endDeferredRenderPass(null);
	}

	private void endDeferredRenderPass(final MetalRenderPass.@Nullable Descriptor next) {
		MetalRenderPass deferred = this.deferredRenderPass;
		if (deferred != null) {
			this.flushDeferredResolve();
			if (ATTACHMENT_LIVENESS && next != null) this.discardOverwrittenAttachments(deferred, next);
			this.deferredRenderPass = null;
			this.deferredDescriptor = null;
			deferred.close();
		}
	}

	/** One-operation lookahead: a full CLEAR kills only the matching mip/slice's old contents.
	 * Copies, samples, compute and partial clears go through commands() first and preserve stores.
	 * Resolve hooks run before this decision so shader-pack tile reads still see their inputs.
	 */
	private void discardOverwrittenAttachments(final MetalRenderPass pass, final MetalRenderPass.Descriptor next) {
		MetalRenderPass.Descriptor previous = this.deferredDescriptor;
		if (previous == null || previous.renderTargetArrayLength() != next.renderTargetArrayLength()) return;
		int colors = 0;
		for (int i = 0; i < previous.colorAttachments().size(); i++) {
			var before = previous.colorAttachments().get(i);
			if (before == null || before.storeAction() != MetalRenderPass.StoreAction.STORE) continue;
			for (var after : next.colorAttachments()) {
				if (after != null && after.loadAction() == MetalRenderPass.LoadAction.CLEAR
					&& before.target() == after.target() && before.mipLevel() == after.mipLevel()
					&& before.arraySlice() == after.arraySlice()) colors |= 1 << i;
			}
		}
		var beforeDepth = previous.depthAttachment();
		var afterDepth = next.depthAttachment();
		boolean depth = beforeDepth != null && afterDepth != null
			&& beforeDepth.storeAction() == MetalRenderPass.StoreAction.STORE
			&& afterDepth.loadAction() == MetalRenderPass.LoadAction.CLEAR
			&& beforeDepth.texture() == afterDepth.texture() && beforeDepth.mipLevel() == afterDepth.mipLevel()
			&& beforeDepth.arraySlice() == afterDepth.arraySlice();
		if (colors != 0 || depth) pass.discardAttachments(colors, depth);
	}

	/** Begin the clear now to pin its resources, then let a compatible drawing pass reuse it. */
	private void beginClear(final MetalRenderPass.Descriptor descriptor, final int kind) {
		if (this.renderPass != null) throw new IllegalStateException("Cannot clear inside an active render pass");
		this.endDeferredRenderPass(descriptor);
		this.deferredRenderPass = this.commands().beginRenderPass(descriptor, kind);
		this.deferredDescriptor = descriptor;
		if (!PASS_MERGING) this.endDeferredRenderPass();
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
		GpuTexture routedColor = this.routedTexture(colorTexture);
		GpuTexture routedDepth = this.routedTexture(depthTexture);
		int width = routedColor.getWidth(0);
		int height = routedColor.getHeight(0);
		if (regionX == 0 && regionY == 0 && regionWidth == width && regionHeight == height) {
			this.clear(routedColor, clearColor, routedDepth, clearDepth);
			return;
		}
		if (regionX < 0 || regionY < 0 || regionWidth <= 0 || regionHeight <= 0
			|| regionX + regionWidth > width || regionY + regionHeight > height) {
			throw new IllegalArgumentException("Metal attachment clear region lies outside the color attachment");
		}
		MetalGpuTexture color = requireTexture(routedColor);
		MetalGpuTexture depth = requireTexture(routedDepth);
		if (routedDepth.getWidth(0) < regionX + regionWidth || routedDepth.getHeight(0) < regionY + regionHeight) {
			throw new IllegalArgumentException("Metal attachment clear region lies outside the depth attachment");
		}
		Vector4fc decoded = decodeWorldClear(color.metal(), clearColor);
		ByteBuffer parameters = ByteBuffer.allocate(MetalRegionClear.PARAMETER_BYTES).order(ByteOrder.nativeOrder());
		MetalRegionClear.writeParameters(
			parameters, decoded.x(), decoded.y(), decoded.z(), decoded.w(), (float) clearDepth
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
		MetalGpuTexture depth = requireTexture(this.routedTexture(depthTexture));
		this.beginClear(MetalRenderPass.Descriptor.depthOnly(
			new MetalRenderPass.DepthAttachment(depth.metal(), MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, clearDepth)
		), MetalPassCensus.kindFor("(depth clear)"));
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
			target.recordUpload(destination.offset(), data, length);
		}
	}

	@Override
	public void copyToBuffer(final GpuBufferSlice source, final GpuBufferSlice target) {
		this.commands().copyBuffer(
			requireBuffer(source.buffer()).metal(), source.offset(), requireBuffer(target.buffer()).metal(), target.offset(), source.length()
		);
		requireBuffer(target.buffer()).recordCopy(target.offset(),
			requireBuffer(source.buffer()), source.offset(), source.length());
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
		int paddedRow = tightRow;
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
		long startingOffset = source.offset() + Math.multiplyExact((long)sourceY * sourceWidth + sourceX, bytesPerPixel);
		MetalBuffer transfer = input.metal();
		long transferOffset = startingOffset;
		int transferRow = tightRow;
		if (startingOffset % bytesPerPixel != 0) {
			// A transient slice may have been allocated with byte alignment. Repack only
			// this exceptional layout, using the submission-owned arena instead of new buffers.
			transferRow = Math.multiplyExact(copyWidth, bytesPerPixel);
			GpuBufferSlice staging = this.transientMemory.allocateGpu(
				Math.multiplyExact((long)transferRow, copyHeight), 256L, GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST);
			transfer = requireBuffer(staging.buffer()).metal();
			transferOffset = staging.offset();
			for (int row = 0; row < copyHeight; row++) {
				this.commands().copyBuffer(input.metal(), startingOffset + (long)row * tightRow,
					transfer, transferOffset + (long)row * transferRow, transferRow);
			}
		}
		this.commands().copyBufferToTextureRegion(
			transfer, transferOffset, transferRow, texture.metal(), mipLevel, arrayLayer,
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
		if (offset % source.getFormat().blockSize() == 0) {
			this.commands().copyTextureToBufferRegion(texture.metal(), mipLevel, x, y, width, height, output.metal(), offset, tightRow);
		} else {
			GpuBufferSlice staging = this.transientMemory.allocateGpu(
				Math.multiplyExact((long)tightRow, height), 256L, GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST);
			MetalBuffer transfer = requireBuffer(staging.buffer()).metal();
			this.commands().copyTextureToBufferRegion(texture.metal(), mipLevel, x, y, width, height, transfer, staging.offset(), tightRow);
			this.commands().copyBuffer(transfer, staging.offset(), output.metal(), offset, Math.multiplyExact((long)tightRow, height));
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
			requireTexture(this.routedTexture(source)).metal(), requireTexture(this.routedTexture(destination)).metal(),
			mipLevel, sourceX, sourceY, destX, destY, width, height
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

	MetalComputePass beginComputePass(final int gpuTimingKind) {
		return this.commands().beginComputePass(gpuTimingKind);
	}

	void finishPendingWork() {
		do {
			this.finishSubmission(true);
		} while (this.commands != null || !this.pendingReadbacks.isEmpty());
	}

	@Override
	public void close() {
		if (!this.closed) {
			this.finishPendingWork();
			this.transientMemory.close();
			if (this.resourceCompletion != null) this.resourceCompletion.close();
			this.closed = true;
		}
	}

	private GpuTexture routedTexture(final GpuTexture texture) {
		MetalLinearWorldSession session = this.device.linearWorldSession();
		return session == null ? texture : session.translateTexture(texture);
	}

	private MetalGpuTextureView routedView(final MetalGpuTextureView view) {
		MetalLinearWorldSession session = this.device.linearWorldSession();
		return session == null ? view : requireTextureView(session.translateView(view));
	}

	private void clear(final GpuTexture colorTexture, final Vector4fc clearColor, final GpuTexture depthTexture, final double clearDepth) {
		MetalGpuTexture color = requireTexture(this.routedTexture(colorTexture));
		Vector4fc decoded = decodeWorldClear(color.metal(), clearColor);
		MetalRenderPass.DepthAttachment depthAttachment = depthTexture == null ? null : new MetalRenderPass.DepthAttachment(
			requireTexture(this.routedTexture(depthTexture)).metal(), MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, clearDepth
		);
		this.beginClear(new MetalRenderPass.Descriptor(
			MetalRenderPass.ColorAttachment.clear(color.metal(), decoded.x(), decoded.y(), decoded.z(), decoded.w()), depthAttachment
		), MetalPassCensus.kindFor("(attachment clear)"));
	}

	/** Encoded fog/clear RGB becomes linear when the routed attachment is the HDR world target. */
	private Vector4fc decodeWorldClear(final MetalTexture routed, final Vector4fc encoded) {
		if (this.device.linearWorldSession() == null || routed == null || encoded == null) {
			return encoded;
		}
		if (routed.descriptor().format() != MetalTexture.Format.RGBA16_FLOAT) {
			return encoded;
		}
		return SceneColor.decodeRgb(encoded);
	}

	void encodeNativePass(final MetalRenderPass.Descriptor descriptor, final int kind,
		final java.util.function.Consumer<MetalRenderPass> encode) {
		if (this.renderPass != null) throw new IllegalStateException("Cannot encode a native pass inside a Blaze3D pass");
		try (MetalRenderPass pass = this.commands().beginRenderPass(descriptor, kind)) {
			encode.accept(pass);
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

	MetalCommandBuffer commands() {
		if (this.closed) throw new IllegalStateException("Metal command encoder is closed");
		this.endDeferredRenderPass();
		if (this.commands == null) this.commands = this.commandQueue.createCommandBuffer();
		return this.commands;
	}

	private void finishSubmission(final boolean wait) {
		if (this.renderPass != null) throw new IllegalStateException("Cannot submit with an active Metal render pass");
		this.endDeferredRenderPass();
		// A reservation can outlive a declined draw. Signal even an otherwise empty
		// submission, so retired resources never remain charged for an abandoned borrow.
		if (this.resourceSubmission != 0) {
			if (this.commands == null) this.commands = this.commandQueue.createCommandBuffer();
			this.commands.signal(this.resourceCompletion, this.resourceSubmission);
		}
		MetalCommandBuffer submitted = this.commands;
		boolean completed = wait;
		if (submitted != null) {
			submitted.commit();
			if (!this.completionCallbacks.isEmpty()) {
				this.pendingReadbacks.addLast(new PendingReadback(submitted.completion(), List.copyOf(this.completionCallbacks)));
				this.completionCallbacks.clear();
			}
			if (completed) {
				long startedNs = MetalStallProbe.begin();
				submitted.waitUntilCompleted();
				MetalStallProbe.end(MetalStallProbe.Source.SUBMIT_WAIT, startedNs);
			}
		}
		boolean retainedByTransientMemory = this.transientMemory.finishSubmission(submitted, completed);
		if (submitted != null && !retainedByTransientMemory) submitted.close();
		this.commands = null;
		this.resourceSubmission = 0;
		if (wait) this.transientMemory.waitForAllFrames();
		this.drainReadbacks(wait);
	}

	/** Runs only on the encoder's owner, after submission state is reset for callback reentrancy. */
	private void drainReadbacks(final boolean wait) {
		Throwable failure = null;
		while (!this.pendingReadbacks.isEmpty()) {
			PendingReadback pending = this.pendingReadbacks.getFirst();
			boolean ready;
			try {
				ready = pending.completion().completed(wait);
			} catch (RuntimeException | Error error) {
				this.pendingReadbacks.removeFirst();
				pending.completion().close();
				if (failure == null) failure = error; else failure.addSuppressed(error);
				continue;
			}
			if (!ready) break;
			this.pendingReadbacks.removeFirst();
			try {
				for (Runnable callback : pending.callbacks()) {
					try { callback.run(); }
					catch (RuntimeException | Error error) {
						if (failure == null) failure = error; else failure.addSuppressed(error);
					}
				}
			} finally { pending.completion().close(); }
		}
		if (failure instanceof RuntimeException error) throw error;
		if (failure instanceof Error error) throw error;
	}

	private record PendingReadback(MetalCommandCompletion completion, List<Runnable> callbacks) { }


	private static void copyRows(final ByteBuffer source, final ByteBuffer destination, final int tightRow, final int paddedRow, final int height) {
		if (tightRow == paddedRow) {
			destination.put(source.slice(source.position(), Math.multiplyExact(tightRow, height)));
			return;
		}
		for (int row = 0; row < height; row++) {
			destination.position(row * paddedRow).put(source.slice(source.position() + row * tightRow, tightRow));
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
