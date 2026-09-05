package dev.metalcraft.client.shader.world;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.PrimitiveTopology;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.shader.ShaderPackRuntime;
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
	private final TerrainShadowRenderer renderer;
	private @Nullable WorldShadowModule.Frame frame;
	private long renderedFrames;
	private int lastDrawCount;

	public WorldTerrainShadows(final MetalGpuDevice device, final ShadowCascades.Settings settings,
		final String sharedSource, final String terrainSource) {
		this.device = device;
		this.resources = new WorldShadowModule(device.metal(), settings);
		try {
			this.renderer = new TerrainShadowRenderer(device.metal(), sharedSource, terrainSource);
		} catch (RuntimeException error) {
			this.resources.close();
			throw error;
		}
	}

	public static void render(final LevelRenderer levelRenderer, final CameraRenderState camera,
		final SkyRenderState sky, final Iterable<SectionRenderDispatcher.RenderSection> sections,
		final GpuSampler sampler) {
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		if (runtime == null || runtime.worldShadows() == null) return;
		try {
			runtime.worldShadows().renderTerrain(levelRenderer, camera, sky, sections, sampler);
		} catch (RuntimeException error) {
			runtime.markFailed("Could not render terrain shadows: " + error.getMessage(), error);
		}
	}

	private void renderTerrain(final LevelRenderer levelRenderer, final CameraRenderState camera,
		final SkyRenderState sky, final Iterable<SectionRenderDispatcher.RenderSection> sections,
		final GpuSampler sampler) {
		this.endFrame();
		this.lastDrawCount = 0;
		if (!camera.initialized || sky.skybox != DimensionType.Skybox.OVERWORLD
			|| !Float.isFinite(sky.sunAngle) || Math.cos(sky.sunAngle) <= 0
			|| Minecraft.getInstance().wireframe) {
			this.frame = this.resources.prepareUnoccludedFrame();
			return;
		}
		var projection = camera.projectionMatrix;
		float fov = 2 * (float)Math.atan(1.0 / Math.abs(projection.m11()));
		float aspect = Math.abs(projection.m11() / projection.m00());
		this.frame = this.resources.prepareFrame(new Vector3d(camera.pos.x, camera.pos.y, camera.pos.z),
			camera.orientation, fov, aspect,
			new Vector3f(-(float)Math.sin(sky.sunAngle), (float)Math.cos(sky.sunAngle), 0),
			new Matrix4f(projection).invert());
		var dispatcher = levelRenderer.sectionRenderDispatcher();
		if (dispatcher == null) {
			this.device.encodeNativePass(this.resources.depthPass(), "MetalCraft shader: shadow_terrain", pass -> {});
			return;
		}
		List<TerrainShadowRenderer.Draw> draws = new ArrayList<>();
		List<SectionRenderDispatcher.RenderSection> casters = new ArrayList<>();
		var sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		// Keep the uber-buffer slices stable through encoding, as vanilla does while preparing draws.
		dispatcher.lock();
		try {
			for (var section : sections) {
				var box = section.getBoundingBox();
				if (!this.frame.intersects((float)(box.minX - camera.pos.x), (float)(box.minY - camera.pos.y),
					(float)(box.minZ - camera.pos.z), (float)(box.maxX - camera.pos.x),
					(float)(box.maxY - camera.pos.y), (float)(box.maxZ - camera.pos.z))) continue;
				casters.add(section);
			}
			int maxIndices = 0;
			for (var section : casters) {
				for (var layer : ChunkSectionLayerGroup.OPAQUE.layers()) {
					var draw = section.getSectionMesh().getSectionDraw(layer);
					if (draw != null && !draw.hasCustomIndexBuffer()) maxIndices = Math.max(maxIndices, draw.indexCount());
				}
			}
			// Grow once before retaining any slices: a later growth closes the previous index buffer.
			var sharedIndices = maxIndices == 0 ? null : sequential.getBuffer(maxIndices);
			for (var section : casters) {
				var mesh = section.getSectionMesh();
				var origin = section.getRenderOrigin();
				for (var layer : ChunkSectionLayerGroup.OPAQUE.layers()) {
					var draw = mesh.getSectionDraw(layer);
					if (draw == null || draw.indexCount() == 0) continue;
					var slice = dispatcher.getRenderSectionSlice(mesh, layer);
					if (slice == null || draw.hasCustomIndexBuffer() && slice.indexBuffer() == null) continue;
					var indices = draw.hasCustomIndexBuffer() ? slice.indexBuffer() : sharedIndices;
					IndexType type = draw.hasCustomIndexBuffer() ? draw.indexType() : sequential.type();
					draws.add(new TerrainShadowRenderer.Draw(layer, this.device.nativeBuffer(slice.vertexBuffer()),
						slice.vertexBufferOffset(), this.device.nativeBuffer(indices),
						draw.hasCustomIndexBuffer() ? slice.indexBufferOffset() : 0,
						type == IndexType.SHORT ? MetalRenderPass.IndexType.UINT16 : MetalRenderPass.IndexType.UINT32,
						draw.indexCount(), (float)(origin.getX() - camera.pos.x),
						(float)(origin.getY() - camera.pos.y), (float)(origin.getZ() - camera.pos.z)));
				}
			}
			var atlas = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
			this.device.encodeNativePass(this.resources.depthPass(), "MetalCraft shader: shadow_terrain", pass ->
				this.renderer.encode(pass, this.frame, draws, this.device.nativeTextureView(atlas), this.device.nativeSampler(sampler)));
			this.lastDrawCount = draws.size();
			this.renderedFrames++;
		} finally {
			dispatcher.unlock();
		}
	}

	public long renderedFrames() { return this.renderedFrames; }
	public int lastDrawCount() { return this.lastDrawCount; }
	public WorldShadowModule.@Nullable Frame currentFrame() { return this.frame; }

	public void endFrame() {
		if (this.frame != null) this.frame.close();
		this.frame = null;
	}

	@Override
	public void close() {
		this.endFrame();
		this.renderer.close();
		this.resources.close();
	}
}
