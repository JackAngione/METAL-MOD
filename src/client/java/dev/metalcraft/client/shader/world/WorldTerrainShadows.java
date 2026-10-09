package dev.metalcraft.client.shader.world;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.PrimitiveTopology;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Collects loaded terrain against the light volumes and records it before the world's opaque pass. */
public final class WorldTerrainShadows implements AutoCloseable {
	private final MetalGpuDevice device;
	private final WorldShadowModule resources;
	private final @Nullable WorldShadowModule distantResources;
	private final TerrainShadowRenderer renderer;
	private @Nullable WorldShadowModule.Frame frame;
	private @Nullable WorldShadowModule.Frame distantFrame;
	private @Nullable WorldShadowModule.Frame cachedFrame, cachedDistantFrame;
	private final ShadowFrameReuse frameReuse = new ShadowFrameReuse();
	private final DistantShadowReuse distantReuse = new DistantShadowReuse();
	private long distantUpdatedFrames, distantReusedFrames;
	private int cachedDrawCount;
	private long reusedFrames, updatedFrames;
	private @Nullable Object casterSectionsOwner;
	private long casterSectionsRevision = Long.MIN_VALUE;
	private List<SectionRenderDispatcher.RenderSection> populatedSections = List.of();
	private long renderedFrames;
	private int lastDrawCount;
	private final boolean windEnabled;
	private static final boolean PROFILE = Boolean.getBoolean("metalcraft.localLightingBenchmark");
	private static final boolean BASELINE_PROBE = Boolean.getBoolean("metalcraft.baselineLightingBenchmark");
	private record Caster(SectionRenderDispatcher.RenderSection section,
		net.minecraft.client.renderer.chunk.SectionMesh mesh, int mask, int distantMask) { }

	public WorldTerrainShadows(final MetalGpuDevice device, final ShadowCascades.Settings settings,
		final String sharedSource, final String terrainSource, final String windSource) {
		this(device, settings, sharedSource, terrainSource, windSource, settings.distance());
	}

	public WorldTerrainShadows(final MetalGpuDevice device, final ShadowCascades.Settings settings,
		final String sharedSource, final String terrainSource, final String windSource, final float distantDistance) {
		this.device = device;
		this.windEnabled = !windSource.isEmpty();
		this.resources = new WorldShadowModule(device.metal(), settings);
		WorldShadowModule distant = null;
		try {
			if (distantDistance > settings.distance()) {
				distant = new WorldShadowModule(device.metal(), distantSettings(settings, distantDistance));
			}
			this.renderer = new TerrainShadowRenderer(device.metal(), sharedSource, terrainSource, windSource);
		} catch (RuntimeException error) {
			if (distant != null) distant.close();
			this.resources.close();
			throw error;
		}
		this.distantResources = distant;
	}

	/** One low-resolution cascade overlaps the detailed range's final 10% for a continuous handoff. */
	public static ShadowCascades.Settings distantSettings(ShadowCascades.Settings detailed, float distance) {
		return new ShadowCascades.Settings(1, Math.min(512, detailed.resolution()),
			detailed.distance() * 0.9F, distance, 0, detailed.casterExtension());
	}

	public static void render(final LevelRenderer levelRenderer, final CameraRenderState camera,
		final SkyRenderState sky, final Iterable<SectionRenderDispatcher.RenderSection> sections,
		final GpuSampler sampler) {
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		if (runtime == null || runtime.worldShadows() == null) return;
		// A final extracted frame can outlive the client level during disconnect.
		if (Minecraft.getInstance().level == null) {
			runtime.worldShadows().clearScene();
			return;
		}
		try {
			long started = dev.metalcraft.client.metal.MetalStallProbe.begin();
			if (runtime.worldGeometry() != null && runtime.worldGeometry().localLighting() != null)
				runtime.worldGeometry().localLighting().prepare(camera);
			dev.metalcraft.client.metal.MetalStallProbe.end(dev.metalcraft.client.metal.MetalStallProbe.Source.LOCAL_LIGHT_PREPARE, started);
			started = dev.metalcraft.client.metal.MetalStallProbe.begin();
			try { runtime.worldShadows().renderTerrain(levelRenderer, camera, sky, sections, sampler); }
			finally { dev.metalcraft.client.metal.MetalStallProbe.end(dev.metalcraft.client.metal.MetalStallProbe.Source.SHADOW_RENDER, started); }
		} catch (RuntimeException error) {
			runtime.markFailed("Could not render terrain shadows: " + error.getMessage(), error);
		}
	}

	private void renderTerrain(final LevelRenderer levelRenderer, final CameraRenderState camera,
		final SkyRenderState sky, final Iterable<SectionRenderDispatcher.RenderSection> sections,
		final GpuSampler sampler) {
		this.endFrame();
		this.lastDrawCount = 0;
		if (BASELINE_PROBE && Boolean.getBoolean("metalcraft.baselineSkipShadows")
			|| PROFILE && Boolean.getBoolean("metalcraft.benchmarkSkipMoon") && Math.cos(sky.sunAngle) < 0
			|| !camera.initialized || sky.skybox != DimensionType.Skybox.OVERWORLD
			|| !Float.isFinite(sky.sunAngle)
			|| Minecraft.getInstance().wireframe) {
			this.frame = this.resources.prepareUnoccludedFrame();
			this.invalidateCachedFrames();
			return;
		}
		var world = Minecraft.getInstance().level;
		var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
		long tick = world == null ? Long.MIN_VALUE : world.getGameTime();
		long dayTime = world == null ? Long.MIN_VALUE : world.getOverworldClockTime();
		long meshRevision = ShadowFrameReuse.meshRevision();
		if (this.cachedFrame != null && (this.distantResources == null || this.cachedDistantFrame != null
			&& this.distantReuse.matches(world, sections, atlas, sampler, tick, dayTime, meshRevision,
				new Vector3d(camera.pos.x, camera.pos.y, camera.pos.z), camera.orientation, camera.projectionMatrix,
				sky.sunAngle, System.nanoTime())) && this.frameReuse.matches(world, sections, atlas, sampler,
			tick, dayTime, meshRevision, camera.pos.x, camera.pos.y, camera.pos.z,
			camera.orientation, camera.projectionMatrix, sky.sunAngle, this.windEnabled)) {
			this.frame = this.cachedFrame;
			if (this.distantResources != null) {
				this.distantFrame = this.distantResources.reprojectFrame(this.cachedDistantFrame,
					this.distantReuse.capturePosition(), new Vector3d(camera.pos.x, camera.pos.y, camera.pos.z),
					camera.orientation, new Matrix4f(camera.projectionMatrix).invert());
				this.distantReusedFrames++; this.distantReuse.reused();
			}
			this.lastDrawCount = this.cachedDrawCount;
			this.reusedFrames++;
			this.renderedFrames++;
			return;
		}
		this.invalidateDetailedFrame();
		long now = System.nanoTime();
		var cameraPosition = new Vector3d(camera.pos.x, camera.pos.y, camera.pos.z);
		boolean updateDistant = this.distantResources != null && (this.cachedDistantFrame == null
			|| !this.distantReuse.matches(world, sections, atlas, sampler, tick, dayTime, meshRevision,
				cameraPosition, camera.orientation, camera.projectionMatrix, sky.sunAngle, now));
		var projection = camera.projectionMatrix;
		float fov = 2 * (float)Math.atan(1.0 / Math.abs(projection.m11()));
		float aspect = Math.abs(projection.m11() / projection.m00());
		// The moon occupies the opposite direction at night; its dim lightmap seed is preserved.
		float celestialSign = Math.cos(sky.sunAngle) < 0 ? -1.0F : 1.0F;
		this.frame = this.resources.prepareFrame(new Vector3d(camera.pos.x, camera.pos.y, camera.pos.z),
			camera.orientation, fov, aspect,
			new Vector3f(-(float)Math.sin(sky.sunAngle) * celestialSign, (float)Math.cos(sky.sunAngle) * celestialSign, 0),
			new Matrix4f(projection).invert());
		if (updateDistant) {
			if (this.cachedDistantFrame != null) this.cachedDistantFrame.close();
			this.cachedDistantFrame = null;
			this.distantFrame = this.distantResources.prepareFrame(new Vector3d(camera.pos.x, camera.pos.y, camera.pos.z),
				camera.orientation, fov, aspect,
				new Vector3f(-(float)Math.sin(sky.sunAngle) * celestialSign, (float)Math.cos(sky.sunAngle) * celestialSign, 0),
				new Matrix4f(projection).invert(), true);
		}
		if (this.distantResources != null && !updateDistant) {
			this.distantFrame = this.distantResources.reprojectFrame(this.cachedDistantFrame,
				this.distantReuse.capturePosition(), cameraPosition, camera.orientation, new Matrix4f(projection).invert());
			this.distantReuse.reused();
			this.distantReusedFrames++;
		}
		var dispatcher = levelRenderer.sectionRenderDispatcher();
		if (dispatcher == null) {
			this.device.encodeNativePass(this.resources.depthPass(), "Metal Mod shader: shadow_terrain", pass -> {});
			if (updateDistant) this.device.encodeNativePass(this.distantResources.depthPass(),
				"Metal Mod shader: shadow_distant", pass -> {});
			return;
		}
		var session = this.device.linearWorldSession();
		var inputs = session == null ? null : session.waterFrameInputs();
		List<TerrainShadowRenderer.Draw> draws = new ArrayList<>();
		List<TerrainShadowRenderer.Draw> distantDraws = new ArrayList<>();
		List<Caster> casters = new ArrayList<>();
		var sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		// Lazy storage snapshots under its monitor. A visibility worker can hold that monitor
		// while creating a RenderSection, which takes the dispatcher lock in reset(). Acquire
		// the snapshot first so shadows never take those locks in the opposite order.
		boolean rebuildCasterSections = this.casterSectionsOwner != sections || this.casterSectionsRevision != meshRevision
			|| BASELINE_PROBE && Boolean.getBoolean("metalcraft.baselineDisableShadowReuse");
		var sectionIterator = rebuildCasterSections ? sections.iterator() : this.populatedSections.iterator();
		List<SectionRenderDispatcher.RenderSection> nextPopulated = rebuildCasterSections ? new ArrayList<>() : null;
		// Keep the uber-buffer slices stable through encoding, as vanilla does while preparing draws.
		dispatcher.lock();
		try {
			while (sectionIterator.hasNext()) {
				var section = sectionIterator.next();
				var mesh = section.getSectionMesh();
				// Most loaded sections are air or only translucent. They have no shadow draws,
				// so reject them before testing four six-plane light volumes.
				if (!mesh.hasRenderableLayers() || mesh.isEmpty(ChunkSectionLayer.SOLID) && mesh.isEmpty(ChunkSectionLayer.CUTOUT)) continue;
				if (nextPopulated != null) nextPopulated.add(section);
				var box = section.getBoundingBox();
				int mask = this.frame.cascadeMask((float)(box.minX - camera.pos.x - 1), (float)(box.minY - camera.pos.y - 1),
					(float)(box.minZ - camera.pos.z - 1), (float)(box.maxX - camera.pos.x + 1),
					(float)(box.maxY - camera.pos.y + 1), (float)(box.maxZ - camera.pos.z + 1));
				int distantMask = !updateDistant || this.distantFrame == null ? 0 : this.distantFrame.cascadeMask(
					(float)(box.minX - camera.pos.x - 1), (float)(box.minY - camera.pos.y - 1),
					(float)(box.minZ - camera.pos.z - 1), (float)(box.maxX - camera.pos.x + 1),
					(float)(box.maxY - camera.pos.y + 1), (float)(box.maxZ - camera.pos.z + 1));
				if (mask != 0 || distantMask != 0) casters.add(new Caster(section, mesh, mask, distantMask));
			}
			if (nextPopulated != null) {
				this.populatedSections = nextPopulated;
				this.casterSectionsOwner = sections;
				this.casterSectionsRevision = meshRevision;
			}
			int maxIndices = 0;
			for (var caster : casters) {
				for (var layer : ChunkSectionLayerGroup.OPAQUE.layers()) {
					var draw = caster.mesh().getSectionDraw(layer);
					if (draw != null && !draw.hasCustomIndexBuffer()) maxIndices = Math.max(maxIndices, draw.indexCount());
				}
			}
			// Grow once before retaining any slices: a later growth closes the previous index buffer.
			var sharedIndices = maxIndices == 0 ? null : sequential.getBuffer(maxIndices);
			for (var caster : casters) {
				var section = caster.section();
				var mesh = caster.mesh();
				var origin = section.getRenderOrigin();
				for (var layer : ChunkSectionLayerGroup.OPAQUE.layers()) {
					var draw = mesh.getSectionDraw(layer);
					if (draw == null || draw.indexCount() == 0) continue;
					var slice = dispatcher.getRenderSectionSlice(mesh, layer);
					if (slice == null || draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) continue;
					var indices = draw.hasCustomIndexBuffer() ? slice.indexBuffer() : sharedIndices;
					IndexType type = draw.hasCustomIndexBuffer() ? draw.indexType() : sequential.type();
					var wind = caster.mask() != 0 && this.windEnabled && inputs != null && mesh instanceof dev.metalcraft.client.shader.wind.WindMeshSource source
						? source.metalcraft$windMesh(layer) : null;
					var shadowDraw = new TerrainShadowRenderer.Draw(layer, this.device.nativeBuffer(slice.vertexBuffer()),
						slice.vertexBufferOffset(), this.device.nativeBuffer(indices),
						draw.hasCustomIndexBuffer() ? slice.indexBufferOffset() : 0,
						type == IndexType.SHORT ? MetalRenderPass.IndexType.UINT16 : MetalRenderPass.IndexType.UINT32,
						draw.indexCount(), (float)(origin.getX() - camera.pos.x),
						(float)(origin.getY() - camera.pos.y), (float)(origin.getZ() - camera.pos.z), caster.mask(),
						wind == null ? null : wind.upload(this.device.metal()), origin.getX(), origin.getY(), origin.getZ());
					if (caster.mask() != 0) draws.add(shadowDraw);
					if (caster.distantMask() != 0) distantDraws.add(new TerrainShadowRenderer.Draw(layer, shadowDraw.vertices(), shadowDraw.vertexOffset(),
						shadowDraw.indices(), shadowDraw.indexOffset(), shadowDraw.indexType(), shadowDraw.indexCount(),
						shadowDraw.relativeX(), shadowDraw.relativeY(), shadowDraw.relativeZ(), caster.distantMask(), null,
						shadowDraw.originX(), shadowDraw.originY(), shadowDraw.originZ()));
				}
			}
			this.device.encodeNativePass(this.resources.depthPass(), "Metal Mod shader: shadow_terrain", pass ->
				this.renderer.encode(pass, this.frame, draws, this.device.nativeTextureView(atlas), this.device.nativeSampler(sampler),
					inputs == null ? 0 : session.windAnimationSeconds()));
			if (updateDistant && this.distantFrame != null) {
				this.device.encodeNativePass(this.distantResources.depthPass(), "Metal Mod shader: shadow_distant", pass ->
					this.renderer.encode(pass, this.distantFrame, distantDraws, this.device.nativeTextureView(atlas),
						this.device.nativeSampler(sampler), inputs == null ? 0 : session.windAnimationSeconds()));
			}
			this.lastDrawCount = draws.size() + distantDraws.size();
			this.renderedFrames++;
			this.updatedFrames++;
			if (updateDistant) this.distantUpdatedFrames++;
			// If a worker published/recycled a mesh during collection, draw again next frame.
			if (meshRevision == ShadowFrameReuse.meshRevision()) {
				this.cachedFrame = this.frame;
				if (updateDistant) {
					this.cachedDistantFrame = this.distantFrame;
					this.distantReuse.store(world, sections, atlas, sampler, tick, dayTime, meshRevision,
						cameraPosition, camera.orientation, camera.projectionMatrix, sky.sunAngle, now);

				}
				this.cachedDrawCount = this.lastDrawCount;
				this.frameReuse.store(world, sections, atlas, sampler, tick, dayTime, meshRevision,
					camera.pos.x, camera.pos.y, camera.pos.z, camera.orientation, camera.projectionMatrix, sky.sunAngle);
			}
		} finally {
			dispatcher.unlock();
		}
	}

	public long renderedFrames() { return this.renderedFrames; }
	public int lastDrawCount() { return this.lastDrawCount; }
	public WorldShadowModule.@Nullable Frame currentFrame() { return this.frame; }
	public WorldShadowModule.@Nullable Frame currentDistantFrame() { return this.distantFrame; }
	public long distantUpdatedFrames() { return this.distantUpdatedFrames; }
	public long distantReusedFrames() { return this.distantReusedFrames; }
	public long reusedFrames() { return this.reusedFrames; }
	public long updatedFrames() { return this.updatedFrames; }
	public int cachedCasterSections() { return this.populatedSections.size(); }

	/** World unload must not retain the old level, section storage or mesh owners in a pack cache. */
	public static void clearWorldCache() {
		var runtime = ShaderPackRuntime.active();
		if (runtime != null && runtime.worldShadows() != null) runtime.worldShadows().clearScene();
	}

	private void clearScene() {
		this.endFrame();
		this.invalidateCachedFrames();
		this.resources.resetStabilization();
		if (this.distantResources != null) this.distantResources.resetStabilization();
		this.populatedSections = List.of();
		this.casterSectionsOwner = null;
		this.casterSectionsRevision = Long.MIN_VALUE;
		this.cachedDrawCount = this.lastDrawCount = 0;
	}

	private void invalidateDetailedFrame() {
		this.frameReuse.invalidate();
		if (this.cachedFrame != null) this.cachedFrame.close();
		this.cachedFrame = null;
	}

	private void invalidateCachedFrames() {
		this.invalidateDetailedFrame();
		this.distantReuse.invalidate();
		if (this.cachedDistantFrame != null) this.cachedDistantFrame.close();
		this.cachedFrame = this.cachedDistantFrame = null;
	}

	public void endFrame() {
		if (this.frame != null && this.frame != this.cachedFrame) this.frame.close();
		this.frame = null;
		if (this.distantFrame != null && this.distantFrame != this.cachedDistantFrame) this.distantFrame.close();
		this.distantFrame = null;
	}

	@Override
	public void close() {
		this.clearScene();
		this.renderer.close();
		this.resources.close();
		if (this.distantResources != null) this.distantResources.close();
	}
}
