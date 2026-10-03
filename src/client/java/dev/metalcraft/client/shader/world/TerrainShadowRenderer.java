package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.Blaze3DMetalMappings;
import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalCommandStream;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalSampler;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import java.util.EnumMap;
import java.util.List;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;

/** Draws borrowed terrain buffers into every active cascade in a single depth encoder. */
public final class TerrainShadowRenderer implements AutoCloseable {
	public record Draw(ChunkSectionLayer layer, MetalBuffer vertices, long vertexOffset,
		MetalBuffer indices, long indexOffset, MetalRenderPass.IndexType indexType, int indexCount,
		float relativeX, float relativeY, float relativeZ, int cascadeMask, MetalBuffer wind, int originX, int originY, int originZ) {
		public Draw(ChunkSectionLayer layer, MetalBuffer vertices, long vertexOffset,
			MetalBuffer indices, long indexOffset, MetalRenderPass.IndexType indexType, int indexCount,
			float relativeX, float relativeY, float relativeZ, int cascadeMask) {
			this(layer, vertices, vertexOffset, indices, indexOffset, indexType, indexCount, relativeX, relativeY, relativeZ, cascadeMask, null, 0, 0, 0);
		}
		public Draw(ChunkSectionLayer layer, MetalBuffer vertices, long vertexOffset,
			MetalBuffer indices, long indexOffset, MetalRenderPass.IndexType indexType, int indexCount,
			float relativeX, float relativeY, float relativeZ) {
			this(layer, vertices, vertexOffset, indices, indexOffset, indexType, indexCount, relativeX, relativeY, relativeZ, 15);
		}
	}

	private final MetalDevice device;
	private final MetalCommandStream commands = new MetalCommandStream();
	private static final boolean BATCHING = Boolean.parseBoolean(System.getProperty("metalcraft.commandBatching", "true"));
	private final EnumMap<ChunkSectionLayer, MetalRenderPipeline> windPipelines = new EnumMap<>(ChunkSectionLayer.class);
	private final EnumMap<ChunkSectionLayer, MetalRenderPipeline> pipelines = new EnumMap<>(ChunkSectionLayer.class);

	public TerrainShadowRenderer(final MetalDevice device, final String sharedSource, final String terrainSource) {
		this(device, sharedSource, terrainSource, "");
	}

	public TerrainShadowRenderer(final MetalDevice device, final String sharedSource, final String terrainSource, final String windSource) {
		this.device = device;
		try {
			for (ChunkSectionLayer layer : ChunkSectionLayerGroup.OPAQUE.layers()) {
				var pipeline = layer.pipeline();
				String alpha = pipeline.getShaderDefines().values().get("ALPHA_CUTOUT");
				for (boolean wind : new boolean[]{false, true}) {
					if (wind && windSource.isEmpty()) continue;
					String source = (wind ? "#define MC_TERRAIN_WIND 1\n" + windSource : "") + "#include <metal_stdlib>\nusing namespace metal;\n"
						+ "#define MC_HAS_ALPHA_CUTOUT " + (alpha == null ? "0" : "1") + "\n"
						+ "#define MC_ALPHA_CUTOUT " + (alpha == null ? "0.0" : alpha) + "\n"
						+ sharedSource + "\n" + terrainSource;
					(wind ? this.windPipelines : this.pipelines).put(layer, device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
						source, "shadow_terrain_vertex", source, "shadow_terrain_fragment",
						List.of(MetalRenderPipeline.ColorTarget.unused()), MetalTexture.Format.DEPTH32_FLOAT,
						Blaze3DMetalMappings.vertexDescriptor(pipeline.getVertexFormatBindings()),
						new MetalRenderPipeline.DepthState(true, true, MetalRenderPipeline.CompareFunction.LESS_EQUAL, 0, 0),
						MetalRenderPipeline.RasterState.DEFAULT, MetalRenderPipeline.InputPrimitiveTopology.TRIANGLE)));
				}
			}
		} catch (RuntimeException error) {
			this.close();
			throw error;
		}
	}

	public void encode(final MetalRenderPass pass, final WorldShadowModule.Frame frame,
		final List<Draw> draws, final MetalTextureView atlas, final MetalSampler sampler) {
		this.encode(pass, frame, draws, atlas, sampler, 0);
	}

	public void encode(final MetalRenderPass pass, final WorldShadowModule.Frame frame,
		final List<Draw> draws, final MetalTextureView atlas, final MetalSampler sampler, final float seconds) {
		if (draws.isEmpty() || frame.cascadeCount() == 0) return;
		int activeMask = (1 << frame.cascadeCount()) - 1;
		try (MetalBuffer offsets = this.device.createBuffer(Math.multiplyExact((long)draws.size(), 32), MetalBuffer.StorageMode.SHARED)) {
			try (var mapping = offsets.map()) {
				for (Draw draw : draws) {
					mapping.bytes().putFloat(draw.relativeX()).putFloat(draw.relativeY()).putFloat(draw.relativeZ())
						.putInt(draw.cascadeMask() & activeMask)
						.putFloat(draw.originX() & 1023).putFloat(draw.originY() & 1023).putFloat(draw.originZ() & 1023).putFloat(seconds);
				}
			}
			frame.bindUniforms(pass, 0, MetalRenderPass.STAGE_VERTEX);
			pass.setUniformBuffer(1, offsets, 0, MetalRenderPass.STAGE_VERTEX);
			pass.setTexture(0, atlas, MetalRenderPass.STAGE_FRAGMENT);
			pass.setSampler(0, sampler, MetalRenderPass.STAGE_FRAGMENT);
			this.commands.reset();
			MetalRenderPipeline bound = null;
			for (int index = 0; index < draws.size(); index++) {
				Draw draw = draws.get(index);
				int instances = Integer.bitCount(draw.cascadeMask() & activeMask);
				if (instances == 0) continue;
				boolean wind = draw.wind() != null && this.windPipelines.containsKey(draw.layer());
				MetalRenderPipeline pipeline = (wind ? this.windPipelines : this.pipelines).get(draw.layer());
				if (pipeline == null) throw new IllegalArgumentException("Unsupported shadow terrain layer " + draw.layer());
				if (bound != pipeline) {
					if (bound == null || !BATCHING) pass.setPipeline(pipeline);
					else pass.recordPipeline(this.commands, pipeline);
					bound = pipeline;
				}
				if (BATCHING) {
					if (wind) this.commands.setUniformBuffer(14, draw.wind(), 0, MetalRenderPass.STAGE_VERTEX);
					this.commands.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, draw.vertices(), draw.vertexOffset());
					this.commands.drawIndexed(MetalRenderPass.Primitive.TRIANGLE, draw.indices(), draw.indexOffset(), draw.indexType(),
						draw.indexCount(), instances, 0, Math.multiplyExact(index, frame.cascadeCount()));
				} else {
					if (wind) pass.setUniformBuffer(14, draw.wind(), 0, MetalRenderPass.STAGE_VERTEX);
					pass.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, draw.vertices(), draw.vertexOffset());
					pass.drawIndexed(MetalRenderPass.Primitive.TRIANGLE, draw.indices(), draw.indexOffset(), draw.indexType(),
						draw.indexCount(), instances, 0, Math.multiplyExact(index, frame.cascadeCount()));
				}
			}
			// Replay before releasing the immutable section offsets or borrowed mesh buffers.
			if (BATCHING && this.commands.commandCount() > 0) pass.submit(this.commands);
			this.commands.reset();
		}
	}

	@Override
	public void close() {
		this.pipelines.values().forEach(MetalRenderPipeline::close);
		this.pipelines.clear();
		this.windPipelines.values().forEach(MetalRenderPipeline::close);
		this.windPipelines.clear();
	}
}
