package dev.metalcraft.client.metal;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.metalcraft.client.shader.WorldGeometryAdapter;
import dev.metalcraft.client.shader.water.WaterDrawSource;
import dev.metalcraft.client.shader.water.WaterMeshBinding;
import dev.metalcraft.client.shader.water.WaterRoutingDebug;
import java.nio.IntBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

/** Blaze3D render-pass adapter translating named bindings and draw state to Metal slots. */
final class MetalRenderPassBackend implements RenderPassBackend {
	/**
	 * Metal's shader argument tables hold sixteen buffers and sixteen textures, and the pass rejects
	 * anything above that, so the caches below can be flat arrays rather than maps.
	 */
	private static final int RESOURCE_SLOTS = 16;
	/**
	 * Sized so the name maps never rehash in practice. A pass binds a handful of uniforms and
	 * textures, and the instance outlives the pass, so the tables are paid for once per encoder
	 * instead of once per pass.
	 */
	private static final int NAME_MAP_CAPACITY = 32;
	/**
	 * Whether a multi-draw records its binds and draws and submits them together.
	 *
	 * <p>A kill switch rather than a setting. Turning batching off restores the per-command path
	 * exactly, which is what makes the two comparable back to back in one session - the only
	 * comparison this renderer's 8-10% run-to-run spread admits.
	 */
	private static final boolean BATCHING = Boolean.parseBoolean(System.getProperty("metalcraft.commandBatching", "true"));

	private final MetalGpuDevice device;
    private final MetalCommandEncoder owner;
	private final Map<String, GpuBufferSlice> uniforms = new HashMap<>(NAME_MAP_CAPACITY);
	private final Map<String, TextureBinding> textures = new HashMap<>(NAME_MAP_CAPACITY);
	/**
	 * What is currently bound in each Metal slot, so a redundant bind can be skipped.
	 *
	 * <p>These were {@code Map<Integer, ...>}, which boxed a dense slot index to hash it and grew a
	 * node table per pass. The slots are already small dense integers, so an array answers the same
	 * question without allocating or hashing anything.
	 */
	private final GpuBufferSlice[] boundUniforms = new GpuBufferSlice[RESOURCE_SLOTS];
	private final MetalGpuTextureView[] boundTextureViews = new MetalGpuTextureView[RESOURCE_SLOTS];
	private final MetalGpuSampler[] boundSamplers = new MetalGpuSampler[RESOURCE_SLOTS];
	private int terrainTextureMip;
	private MetalRenderPass metal;
	private RenderPass.RenderArea renderArea;
	private int outputWidth;
	private int outputHeight;
    private int scissorX, scissorY, scissorWidth, scissorHeight;
	private boolean hasDepth;
	private MetalTexture.@Nullable Format colorFormat;
	private MetalCompiledRenderPipeline pipeline;
	private RenderPipeline originalPipeline;
	private MetalGpuBuffer indexBuffer;
	private MetalRenderPass.IndexType indexType;
	private int debugGroups;
	private boolean hdrOwned;
	private boolean discardDraws;
	/**
	 * The batch buffer, allocated on the first multi-draw and reused for the encoder's lifetime.
	 *
	 * <p>One adapter serves every pass on an encoder, so this is allocated once per process in
	 * practice - which is the point: a per-pass batch buffer would trade thousands of JNI calls for
	 * a per-pass direct allocation, and direct allocations are exactly what the renderer has spent
	 * this long removing from the frame.
	 */
	private MetalCommandStream commands;
	/** Non-null while a multi-draw is recording; binds and draws go to it instead of the encoder. */
	private @Nullable MetalCommandStream recording;

	MetalRenderPassBackend(final MetalGpuDevice device, final MetalCommandEncoder owner) {
		this.device = device;
        this.owner = owner;
	}

	/**
	 * Rebinds this adapter to a freshly begun pass.
	 *
	 * <p>One instance is reused for every pass on an encoder, because only one pass can be active at
	 * a time and a per-pass instance meant re-allocating four hash tables per pass - the largest
	 * single source of allocation left in the renderer once bind-group flattening was gone.
	 */
	void reset(
		final MetalRenderPass pass,
		final RenderPass.RenderArea area,
		final int width,
		final int height,
		final boolean depth,
		final MetalTexture.@Nullable Format colorFormat,
		final boolean hdrOwned
	) {
		this.metal = pass;
		this.renderArea = area;
		this.outputWidth = width;
		this.outputHeight = height;
        this.scissorX = area.x(); this.scissorY = area.y();
        this.scissorWidth = area.width(); this.scissorHeight = area.height();
		this.hasDepth = depth;
		this.colorFormat = colorFormat;
		this.hdrOwned = hdrOwned;
		this.discardDraws = false;
		this.recording = null;
		this.uniforms.clear();
		this.textures.clear();
		this.clearBoundSlots();
		this.pipeline = null;
		this.originalPipeline = null;
		this.indexBuffer = null;
		this.indexType = null;
		this.debugGroups = 0;
	}

	/**
	 * Detaches from the finished pass, so a caller holding the adapter past {@code submitRenderPass}
	 * is told so rather than quietly encoding into whichever pass is opened next. Reuse is what makes
	 * that failure mode possible, so reuse is what has to close it.
	 */
	void finish() {
		this.metal = null;
	}

	/**
	 * The pass, with any batch recorded so far submitted first.
	 *
	 * <p>Every command outside the batch ABI reaches the encoder through here, and each one has to
	 * land after the draws already recorded rather than ahead of them. Flushing at this seam is what
	 * keeps that true without each caller having to know that a batch might be open.
	 */
	private MetalRenderPass pass() {
		if (this.recording != null) this.submitBatch(this.recording);
		return this.metal();
	}

	/** The pass without flushing, for the batch path itself and for {@link #submitBatch}. */
	private MetalRenderPass metal() {
		if (this.metal == null) throw new IllegalStateException("This Metal render pass has already been submitted");
		return this.metal;
	}

	private void clearBoundSlots() {
		Arrays.fill(this.boundUniforms, null);
		Arrays.fill(this.boundTextureViews, null);
		Arrays.fill(this.boundSamplers, null);
	}

	@Override
	public void pushDebugGroup(final Supplier<String> label) {
		this.debugGroups++;
	}

	@Override
	public void popDebugGroup() {
		if (this.debugGroups == 0) throw new IllegalStateException("No Metal debug group is active");
		this.debugGroups--;
	}

	@Override
	public void setPipeline(final RenderPipeline pipeline) {
		this.originalPipeline = pipeline;
		MetalCompiledRenderPipeline compiled;
		if (this.hdrOwned) {
			MetalLinearWorldSession session = this.device.linearWorldSession();
			compiled = session == null ? null : this.device.linearPipelineFor(session, pipeline);
			if (compiled == null || !compiled.isValid()) {
				this.pipeline = null;
				this.discardDraws = true;
				return;
			}
			this.discardDraws = false;
		} else {
			compiled = this.device.getOrCompilePipeline(pipeline);
			if (!compiled.isValid()) throw new IllegalStateException("Direct Metal pipeline is invalid: " + pipeline.getLocation());
		}
		if (this.pipeline != compiled) {
			this.pipeline = compiled;
			this.clearBoundSlots();
			this.encodePipeline(compiled.metal(this.hasDepth, this.colorFormat));
		}
	}

	@Override
	public void bindTexture(final String name, final @Nullable GpuTextureView textureView, final @Nullable GpuSampler sampler) {
		if (textureView == null && sampler == null) {
			this.textures.remove(name);
		} else if (textureView instanceof MetalGpuTextureView view && sampler instanceof MetalGpuSampler metalSampler) {
			// Framegraph post passes still refer to the captured main views. Route reads
			// through the same identity mapping as attachments/clears/copies, including
			// Fabulous passes writing a separate internal target (hdrOwned is false).
			MetalLinearWorldSession session = this.device.linearWorldSession();
			MetalGpuTextureView sampled = session == null ? view : (MetalGpuTextureView)session.translateView(view);
			this.textures.put(name, new TextureBinding(sampled, metalSampler));
		} else {
			throw new IllegalArgumentException("Metal texture and sampler must both be supplied by the direct Metal backend");
		}
	}

	@Override
	public void setUniform(final String name, final GpuBuffer value) {
		this.setUniform(name, value.slice());
	}

	@Override
	public void setUniform(final String name, final GpuBufferSlice value) {
		if (!(value.buffer() instanceof MetalGpuBuffer metal)) throw new IllegalArgumentException("Uniform buffer does not belong to Metal");
		this.uniforms.put(name, value);
		WorldUniformCapture capture = this.device.worldUniformCapture();
		if (capture != null) {
			metal.captureUniform(name, value.offset(), value.length(), capture);
		}
	}

	@Override
	public void enableScissor(final int x, final int y, final int width, final int height) {
		this.pass().setScissor(x, y, width, height);
        this.scissorX = x; this.scissorY = y; this.scissorWidth = width; this.scissorHeight = height;
	}

	@Override
	public void disableScissor() {
		if (this.renderArea != null) this.enableScissor(this.renderArea.x(), this.renderArea.y(), this.renderArea.width(), this.renderArea.height());
		else this.enableScissor(0, 0, this.outputWidth, this.outputHeight);
	}

	@Override
	public void setVertexBuffer(final int slot, final @Nullable GpuBufferSlice vertexBuffer) {
		if (vertexBuffer != null) {
			MetalGpuBuffer buffer = requireBuffer(vertexBuffer.buffer());
			this.encodeVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX + slot, buffer.metal(), vertexBuffer.offset());
		}
	}

	@Override
	public void setIndexBuffer(final GpuBuffer indexBuffer, final IndexType indexType) {
		this.indexBuffer = requireBuffer(indexBuffer);
		this.indexType = switch (indexType) {
			case SHORT -> MetalRenderPass.IndexType.UINT16;
			case INT -> MetalRenderPass.IndexType.UINT32;
		};
	}

	@Override
	public void drawIndexed(final int indexCount, final int instanceCount, final int firstIndex, final int vertexOffset, final int firstInstance) {
		if (this.discardDraws) return;
		this.bindResources();
		this.requireIndexBuffer();
		this.encodeDrawIndexed(this.primitive(), this.indexBuffer.metal(), indexOffset(firstIndex), this.indexType, indexCount, instanceCount, vertexOffset, firstInstance);
	}

	@Override
	public void multiDrawIndexed(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		if (this.discardDraws) return;
		this.bindResources();
		this.requireIndexBuffer();
		this.pass().multiDrawIndexed(this.primitive(), this.indexBuffer.metal(), this.indexType, drawParameters, instanceCount, firstInstance, drawCount);
	}

	@Override
	public void multiDrawIndexed(final PointerBuffer firstIndexOffsets, final IntBuffer indexCounts, final IntBuffer vertexOffsets, final int drawCount) {
		if (this.discardDraws) return;
		this.bindResources();
		this.requireIndexBuffer();
		for (int draw = 0; draw < drawCount; draw++) {
			int count = indexCounts.get(indexCounts.position() + draw);
			int baseVertex = vertexOffsets.get(vertexOffsets.position() + draw);
			this.encodeDrawIndexed(this.primitive(), this.indexBuffer.metal(), firstIndexOffsets.get(firstIndexOffsets.position() + draw), this.indexType, count, 1, baseVertex, 0);
		}
	}

	@Override
	public void drawIndexedIndirect(final GpuBufferSlice commands, final int drawCount) {
		if (this.discardDraws) return;
		this.bindResources();
		this.requireIndexBuffer();
		this.pass().drawIndexedIndirect(this.primitive(), this.indexBuffer.metal(), this.indexType, requireBuffer(commands.buffer()).metal(), commands.offset(), drawCount);
	}

	@Override
	public <T> void drawMultipleIndexed(
		final Collection<RenderPass.Draw<T>> draws,
		final @Nullable GpuBuffer defaultIndexBuffer,
		final @Nullable IndexType defaultIndexType,
		final Collection<String> dynamicUniforms,
		final T uniformArgument
	) {
		if (this.discardDraws) return;
		RenderPipeline baseline = this.originalPipeline;
        MetalCompiledRenderPipeline reconstruction = this.prepareTerrainResolution(draws, defaultIndexBuffer, defaultIndexType, uniformArgument);
        MetalTerrainResolution.Band boundBand = null;
        MetalCompiledRenderPipeline ordinary = this.pipeline;
        boolean sharedTerrainBindings = reconstruction != null && reconstruction.sharesTerrainBindings(ordinary);
		WorldGeometryAdapter geometry = WorldGeometryAdapter.active();
		boolean waterEligible = geometry != null && this.device.opaqueWaterInputs().isPresent()
			&& this.device.linearWorldSession().waterFrameInputs() != null
			&& draws.stream().anyMatch(draw -> ((Object)draw) instanceof WaterDrawSource source && source.metalcraft$waterMesh() != null);
		// Per-draw immutable uniforms are retired after encoding; don't defer their native binds.
		MetalCommandStream batch = BATCHING && !waterEligible ? this.beginRecording() : null;
		boolean lodDraws = dev.metalcraft.client.lod.LodLoadedRenderer.trackingDraws();
		try {
			for (RenderPass.Draw<T> draw : draws) {
				this.terrainTextureMip = ((Object)draw) instanceof dev.metalcraft.client.lod.LodDrawSource source
					? source.metalcraft$textureMip() : 0;
                if (reconstruction != null) {
                    MetalCompiledRenderPipeline selected = this.terrainTextureMip > 0 ? reconstruction : ordinary;
                    if (this.pipeline != selected) {
                        this.pipeline = selected;
                        if (!sharedTerrainBindings) this.clearBoundSlots();
                        this.encodePipeline(selected.metal(this.hasDepth, this.colorFormat));
                    }
                    if (this.terrainTextureMip > 0) {
                        var band = this.device.terrainResolution().band(this.terrainTextureMip, this.outputWidth, this.outputHeight, this.colorFormat);
                        if (band != boundBand) {
                            this.encodeTexture(14, band.colorView, MetalRenderPass.STAGE_FRAGMENT);
                            this.encodeTexture(15, band.depthView, MetalRenderPass.STAGE_FRAGMENT);
                            this.encodeUniformBuffer(15, band.parameters, 0, MetalRenderPass.STAGE_FRAGMENT);
                            boundBand = band;
                        }
                    }
                }
				BiConsumer<T, RenderPass.UniformUploader> uploader = draw.uniformUploaderConsumer();
				if (uploader != null) uploader.accept(uniformArgument, this::setUniform);
				if (lodDraws
					&& ((Object)draw) instanceof dev.metalcraft.client.lod.LodDrawSource source
					&& source.metalcraft$lodDraw() != null
					&& this.tryLodDraw(source.metalcraft$lodDraw(), baseline, defaultIndexBuffer, defaultIndexType)) continue;
				this.setIndexBuffer(draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer(), draw.indexType() == null ? defaultIndexType : draw.indexType());
				this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
				WaterMeshBinding water = waterEligible && ((Object)draw) instanceof WaterDrawSource source ? source.metalcraft$waterMesh() : null;
				MetalBuffer metadata = water == null ? null : water.upload(this.device.metal());
				RenderPipeline selected = metadata == null ? baseline : geometry.waterPipeline(baseline).orElse(baseline);
				if (selected != this.originalPipeline) this.setPipeline(selected);
				if (selected != baseline) {
					try (MetalBuffer waterDraw = this.device.metal().createBuffer(16, MetalBuffer.StorageMode.SHARED)) {
						try (MetalBuffer.Mapping mapping = waterDraw.map()) {
							mapping.bytes().putInt(draw.baseVertex()).putInt(water.vertexCount())
								.putInt(WaterRoutingDebug.mode().gpuValue).putInt(0);
						}
						this.device.linearWorldSession().recordWaterDraw();
						this.encodeUniformBuffer(13, this.device.linearWorldSession().waterFrameBuffer(), 0, MetalRenderPass.STAGE_VERTEX | MetalRenderPass.STAGE_FRAGMENT);
						var opaque = this.device.opaqueWaterInputs().orElseThrow();
						this.encodeTexture(12, opaque.color().metal(), MetalRenderPass.STAGE_FRAGMENT);
						this.encodeTexture(13, opaque.depth().metal(), MetalRenderPass.STAGE_FRAGMENT);
						this.encodeUniformBuffer(14, metadata, 0, MetalRenderPass.STAGE_VERTEX);
						this.encodeUniformBuffer(15, waterDraw, 0,
							MetalRenderPass.STAGE_VERTEX | MetalRenderPass.STAGE_FRAGMENT);
						this.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
					}
				} else {
					this.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
				}
				if (lodDraws
					&& ((Object)draw) instanceof dev.metalcraft.client.lod.LodDrawSource source && source.metalcraft$isExtended())
					dev.metalcraft.client.lod.LodDistantRenderer.encoded(draw.indexCount());
				if (lodDraws
					&& ((Object)draw) instanceof dev.metalcraft.client.lod.LodDrawSource source && source.metalcraft$isTerrain())
					dev.metalcraft.client.lod.LodLoadedRenderer.encodedTerrain(draw.indexCount(), draw.indexCount(), source.metalcraft$isDistant());
			}
		} finally {
			this.terrainTextureMip = 0;
			// Cleared before the batch is submitted, so a draw that threw part-way discards what it
			// recorded rather than encoding half a multi-draw into the pass.
			this.recording = null;
		}
		if (batch != null) this.submitBatch(batch);
        if (reconstruction != null) this.setPipeline(baseline);
		if (this.originalPipeline != baseline) this.setPipeline(baseline);
	}

    /** Shade each distant band once at fewer pixels; ordinary draws then provide exact coverage/depth. */
    private <T> MetalCompiledRenderPipeline prepareTerrainResolution(Collection<RenderPass.Draw<T>> draws,
            GpuBuffer defaultIndices, IndexType defaultType, T uniformArgument) {
        if (this.originalPipeline != net.minecraft.client.renderer.RenderPipelines.SOLID_TERRAIN
                || !MetalTerrainResolution.ENABLED || !dev.metalcraft.client.MetalCraftConfig.nativeLodPixels() || this.hdrOwned || !this.hasDepth || this.colorFormat == null
                || !this.owner.supportsTerrainResolution() || this.scissorX != 0 || this.scissorY != 0
                || this.scissorWidth != this.outputWidth || this.scissorHeight != this.outputHeight)
            return null;
        int mask = 0;
        for (var draw : draws) if ((Object)draw instanceof dev.metalcraft.client.lod.LodDrawSource source) {
            int tier = source.metalcraft$textureMip();
            if (tier >= 1 && tier <= 2) mask |= 1 << tier;
        }
        if (mask == 0) return null;
        var reconstruction = this.device.terrainResolutionPipeline(this.originalPipeline);
        if (reconstruction == null) return null;
        // Preflight compilation/allocation before changing the scene pass's ownership.
        reconstruction.metal(true, this.colorFormat);
        var targets = this.device.terrainResolution();
        for (int tier = 1; tier <= 2; tier++) if ((mask & (1 << tier)) != 0)
            targets.band(tier, this.outputWidth, this.outputHeight, this.colorFormat);
        var ordinary = this.pipeline;
        this.pass();
        try {
            for (int tier = 1; tier <= 2; tier++) {
                if ((mask & (1 << tier)) == 0) continue;
                var band = targets.band(tier, this.outputWidth, this.outputHeight, this.colorFormat);
                this.metal = this.owner.terrainBand(band);
                this.clearBoundSlots();
                this.metal.setPipeline(ordinary.metal(true, this.colorFormat));
                this.metal.setScissor(0, 0, band.width, band.height);
                var batch = BATCHING ? this.beginRecording() : null;
                try {
                    this.terrainTextureMip = tier;
                    for (var draw : draws) {
                        if (!((Object)draw instanceof dev.metalcraft.client.lod.LodDrawSource source)
                                || source.metalcraft$textureMip() != tier) continue;
                        var uploader = draw.uniformUploaderConsumer();
                        if (uploader != null) uploader.accept(uniformArgument, this::setUniform);
                        this.setIndexBuffer(draw.indexBuffer() == null ? defaultIndices : draw.indexBuffer(),
                            draw.indexType() == null ? defaultType : draw.indexType());
                        this.setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
                        this.drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
                        targets.drawn(tier);
                    }
                } finally { this.recording = null; }
                if (batch != null) this.submitBatch(batch);
            }
        } finally {
            this.terrainTextureMip = 0;
            this.metal = this.owner.resumeTerrainScene();
            this.metal.setScissor(0, 0, this.outputWidth, this.outputHeight);
            this.metal.setPipeline(ordinary.metal(true, this.colorFormat));
            this.clearBoundSlots();
        }
        return reconstruction;
    }

	/** Decline before changing the ordinary draw's ownership if any required resource is unavailable. */
	private boolean tryLodDraw(dev.metalcraft.client.lod.LodLoadedRenderer.Draw draw, RenderPipeline baseline,
		@Nullable GpuBuffer indices, @Nullable IndexType indexType) {
		if (!this.hasDepth || !(indices instanceof MetalGpuBuffer) || indices.isClosed() || indexType == null
			|| !this.uniforms.containsKey("ChunkSection") || !this.uniforms.containsKey("Projection")
			|| !this.uniforms.containsKey("Globals") || !this.uniforms.containsKey("Fog")
			|| !this.textures.containsKey("Sampler0") || !this.textures.containsKey("Sampler2")) return false;
		MetalCompiledRenderPipeline alternate = this.device.lodPipeline(baseline, this.hdrOwned);
		if (alternate == null) return false;
		MetalRenderPipeline nativePipeline = alternate.metal(this.hasDepth, this.colorFormat);
		var mesh = draw.borrow(this.device);
		if (mesh == null) return false;
		// Pipeline changes stay in draw order inside the same native command stream.
		this.encodePipeline(nativePipeline);
		this.pipeline = alternate;
		this.clearBoundSlots();
		this.encodeVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, mesh.vertices(), 0);
		this.encodeUniformBuffer(14, mesh.metadata(), 0, MetalRenderPass.STAGE_VERTEX);
		this.setIndexBuffer(indices, indexType);
		this.drawIndexed(mesh.indexCount(), 1, 0, 0, 0);
		draw.encoded(mesh);
		// Restores both the native program and its stage-specific binding cache.
		this.setPipeline(baseline);
		return true;
	}

	private void encodePipeline(final MetalRenderPipeline pipeline) {
		if (this.recording != null) this.metal().recordPipeline(this.recording, pipeline);
		else this.pass().setPipeline(pipeline);
	}

	@Override
	public void draw(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		if (this.discardDraws) return;
		this.bindResources();
		if (this.isTriangleFan()) {
			this.drawTriangleFan(vertexCount, instanceCount, firstVertex, firstInstance);
		} else {
			this.pass().draw(this.primitive(), firstVertex, vertexCount, instanceCount, firstInstance);
		}
	}

	@Override
	public void multiDraw(final IntBuffer drawParameters, final int instanceCount, final int firstInstance, final int drawCount) {
		if (this.discardDraws) return;
		this.bindResources();
		this.pass().multiDraw(this.primitive(), drawParameters, instanceCount, firstInstance, drawCount);
	}

	@Override
	public void multiDraw(final IntBuffer firstVertices, final IntBuffer vertexCounts, final int drawCount) {
		if (this.discardDraws) return;
		this.bindResources();
		this.pass().multiDraw(this.primitive(), firstVertices, vertexCounts, drawCount);
	}

	@Override
	public void drawIndirect(final GpuBufferSlice commands, final int drawCount) {
		if (this.discardDraws) return;
		this.bindResources();
		this.pass().drawIndirect(this.primitive(), requireBuffer(commands.buffer()).metal(), commands.offset(), drawCount);
	}

	@Override
	public void writeTimestamp(final GpuQueryPool pool, final int index) {
		if (!(pool instanceof MetalTimestampQueryPool queryPool)) throw new IllegalArgumentException("Query pool does not belong to Metal");
		this.pass().writeTimestamp(queryPool, index);
	}

	private void bindResources() {
		if (this.pipeline == null || !this.pipeline.isValid()) throw new IllegalStateException("A valid Metal pipeline must be bound before drawing");
		List<BindGroupLayout.UniformDescription> uniformLayout = this.pipeline.uniformLayout();
		for (int index = 0; index < uniformLayout.size(); index++) {
			BindGroupLayout.UniformDescription description = uniformLayout.get(index);
			GpuBufferSlice value = this.uniforms.get(description.name());
			if (value == null) throw new IllegalStateException("Missing Metal uniform " + description.name());
			requireSlot(index, description.name());
			if (!value.equals(this.boundUniforms[index])) {
				MetalBuffer buffer = requireBuffer(value.buffer()).metal();
				if (description.type() == UniformType.TEXEL_BUFFER) {
					if (description.gpuFormat() == null) {
						throw new IllegalStateException("Metal texel-buffer uniform has no format: " + description.name());
					}
					this.encodeTexelBuffer(
						index, buffer, value.offset(), value.length(), Blaze3DMetalMappings.textureFormat(description.gpuFormat()),
						this.pipeline.textureStages(index)
					);
				} else {
					this.encodeUniformBuffer(index, buffer, value.offset(), this.pipeline.bufferStages(index));
				}
				this.boundUniforms[index] = value;
			}
		}
		List<String> samplerLayout = this.pipeline.samplerLayout();
		for (int index = 0; index < samplerLayout.size(); index++) {
			String name = samplerLayout.get(index);
			TextureBinding value = this.textures.get(name);
			if (value == null) throw new IllegalStateException("Missing Metal sampler " + name);
			MetalGpuSampler sampler = this.terrainTextureMip > 0 && name.equals("Sampler0")
				? value.sampler.distant(this.terrainTextureMip) : value.sampler;
			int resourceIndex = uniformLayout.size() + index;
			requireSlot(resourceIndex, name);
			// Views and samplers have identity equality, so this is the same test the record's
			// equals() performed, without boxing the slot to look the pair up.
			if (this.boundTextureViews[resourceIndex] != value.view || this.boundSamplers[resourceIndex] != sampler) {
				int stages = this.pipeline.textureStages(resourceIndex);
				this.encodeTexture(resourceIndex, value.view.metal(), stages);
				this.encodeSampler(resourceIndex, sampler.metal(), stages);
				this.boundTextureViews[resourceIndex] = value.view;
				this.boundSamplers[resourceIndex] = sampler;
			}
		}
	}

	/**
	 * Starts recording into the reusable batch buffer.
	 *
	 * <p>Every bind and draw below asks {@link #recording} where to go, so opening a batch is the
	 * only thing that has to know batching exists.
	 */
	private MetalCommandStream beginRecording() {
		if (this.commands == null) this.commands = new MetalCommandStream();
		this.commands.reset();
		this.recording = this.commands;
		return this.commands;
	}

	/**
	 * Hands a recorded batch to the pass and empties it.
	 *
	 * <p>Timed as a whole, because that is the granularity the change is about: a batch's cost has
	 * to be read against the commands it carried, which is what the recorded byte count reports.
	 */
	private void submitBatch(final MetalCommandStream batch) {
		if (batch.commandCount() == 0) return;
		long startedNs = MetalStallProbe.begin();
		long bytes = batch.byteCount();
		this.metal().submit(batch);
		MetalStallProbe.end(MetalStallProbe.Source.COMMAND_BATCH, startedNs, bytes);
		batch.reset();
	}

	private void encodeVertexBuffer(final int index, final MetalBuffer buffer, final long offset) {
		if (this.recording != null) this.recording.setVertexBuffer(index, buffer, offset);
		else this.metal().setVertexBuffer(index, buffer, offset);
	}

	private void encodeUniformBuffer(final int index, final MetalBuffer buffer, final long offset, final int stages) {
		if (this.recording != null) this.recording.setUniformBuffer(index, buffer, offset, stages);
		else this.metal().setUniformBuffer(index, buffer, offset, stages);
	}

	private void encodeTexture(final int index, final MetalTextureView textureView, final int stages) {
		if (this.recording != null) this.recording.setTexture(index, textureView, stages);
		else this.metal().setTexture(index, textureView, stages);
	}

	private void encodeSampler(final int index, final MetalSampler sampler, final int stages) {
		if (this.recording != null) this.recording.setSampler(index, sampler, stages);
		else this.metal().setSampler(index, sampler, stages);
	}

	/**
	 * Texel buffers are not in the batch ABI, deliberately: binding one means resolving a cached
	 * texture view against the buffer's format alignment, which is far more native work than a
	 * compact record can describe and is rebound once per pass rather than once per draw.
	 */
	private void encodeTexelBuffer(
		final int index,
		final MetalBuffer buffer,
		final long offset,
		final long length,
		final MetalTexture.Format format,
		final int stages
	) {
		this.pass().setTexelBuffer(index, buffer, offset, length, format, stages);
	}

	private void encodeDrawIndexed(
		final MetalRenderPass.Primitive primitive,
		final MetalBuffer indexBuffer,
		final long indexBufferOffset,
		final MetalRenderPass.IndexType indexType,
		final int indexCount,
		final int instanceCount,
		final int baseVertex,
		final int baseInstance
	) {
		if (this.recording != null) {
			this.recording.drawIndexed(primitive, indexBuffer, indexBufferOffset, indexType, indexCount, instanceCount, baseVertex, baseInstance);
		} else {
			this.metal().drawIndexed(primitive, indexBuffer, indexBufferOffset, indexType, indexCount, instanceCount, baseVertex, baseInstance);
		}
	}

	private MetalRenderPass.Primitive primitive() {
		if (this.pipeline == null) throw new IllegalStateException("No Metal pipeline is bound");
		return Blaze3DMetalMappings.primitive(this.pipeline.info().getPrimitiveTopology());
	}

	private boolean isTriangleFan() {
		return this.pipeline != null && this.pipeline.info().getPrimitiveTopology() == PrimitiveTopology.TRIANGLE_FAN;
	}

	private void drawTriangleFan(final int vertexCount, final int instanceCount, final int firstVertex, final int firstInstance) {
		if (vertexCount < 3 || instanceCount == 0) {
			return;
		}
		int indexCount = Math.multiplyExact(vertexCount - 2, 3);
		boolean useShorts = vertexCount <= 1 << 16;
		// The shared fan buffer is already filled; a smaller fan is a prefix of a larger one.
		MetalBuffer indices = this.device.fanIndices(vertexCount, useShorts);
		this.encodeDrawIndexed(
			MetalRenderPass.Primitive.TRIANGLE,
			indices,
			0L,
			useShorts ? MetalRenderPass.IndexType.UINT16 : MetalRenderPass.IndexType.UINT32,
			indexCount,
			instanceCount,
			firstVertex,
			firstInstance
		);
	}

	private void requireIndexBuffer() {
		if (this.indexBuffer == null || this.indexType == null) throw new IllegalStateException("No Metal index buffer is bound");
	}

	private long indexOffset(final int firstIndex) {
		return Math.multiplyExact((long)firstIndex, this.indexType == MetalRenderPass.IndexType.UINT16 ? 2L : 4L);
	}

	private static void requireSlot(final int slot, final String name) {
		if (slot < 0 || slot >= RESOURCE_SLOTS) {
			throw new IllegalStateException("Metal resource slot " + slot + " for " + name
				+ " is outside the " + RESOURCE_SLOTS + " slots a Metal argument table provides");
		}
	}

	private static MetalGpuBuffer requireBuffer(final GpuBuffer buffer) {
		if (buffer instanceof MetalGpuBuffer metal) return metal;
		throw new IllegalArgumentException("Buffer does not belong to the direct Metal backend");
	}

	private record TextureBinding(MetalGpuTextureView view, MetalGpuSampler sampler) {
	}
}
