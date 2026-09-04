package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.Blaze3DMetalMappings;
import dev.metalcraft.client.metal.MetalBuffer;
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
		float relativeX, float relativeY, float relativeZ) { }

	private final MetalDevice device;
	private final EnumMap<ChunkSectionLayer, MetalRenderPipeline> pipelines = new EnumMap<>(ChunkSectionLayer.class);

	public TerrainShadowRenderer(final MetalDevice device, final String sharedSource, final String terrainSource) {
		this.device = device;
		try {
			for (ChunkSectionLayer layer : ChunkSectionLayerGroup.OPAQUE.layers()) {
				var pipeline = layer.pipeline();
				String alpha = pipeline.getShaderDefines().values().get("ALPHA_CUTOUT");
				String source = "#include <metal_stdlib>\nusing namespace metal;\n"
					+ "#define MC_HAS_ALPHA_CUTOUT " + (alpha == null ? "0" : "1") + "\n"
					+ "#define MC_ALPHA_CUTOUT " + (alpha == null ? "0.0" : alpha) + "\n"
					+ sharedSource + "\n" + terrainSource;
				this.pipelines.put(layer, device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
					source, "shadow_terrain_vertex", source, "shadow_terrain_fragment",
					List.of(MetalRenderPipeline.ColorTarget.unused()), MetalTexture.Format.DEPTH32_FLOAT,
					Blaze3DMetalMappings.vertexDescriptor(pipeline.getVertexFormatBindings()),
					new MetalRenderPipeline.DepthState(true, true, MetalRenderPipeline.CompareFunction.LESS_EQUAL, 0, 0),
					MetalRenderPipeline.RasterState.DEFAULT, MetalRenderPipeline.InputPrimitiveTopology.TRIANGLE)));
			}
		} catch (RuntimeException error) {
			this.close();
			throw error;
		}
	}

	public void encode(final MetalRenderPass pass, final WorldShadowModule.Frame frame,
		final List<Draw> draws, final MetalTextureView atlas, final MetalSampler sampler) {
		if (draws.isEmpty()) return;
		try (MetalBuffer offsets = this.device.createBuffer(Math.multiplyExact((long)draws.size(), 16), MetalBuffer.StorageMode.SHARED)) {
			try (var mapping = offsets.map()) {
				for (Draw draw : draws) {
					mapping.bytes().putFloat(draw.relativeX()).putFloat(draw.relativeY()).putFloat(draw.relativeZ()).putFloat(0);
				}
			}
			frame.bindUniforms(pass, 0, MetalRenderPass.STAGE_VERTEX);
			pass.setUniformBuffer(1, offsets, 0, MetalRenderPass.STAGE_VERTEX);
			pass.setTexture(0, atlas, MetalRenderPass.STAGE_FRAGMENT);
			pass.setSampler(0, sampler, MetalRenderPass.STAGE_FRAGMENT);
			for (int index = 0; index < draws.size(); index++) {
				Draw draw = draws.get(index);
				MetalRenderPipeline pipeline = this.pipelines.get(draw.layer());
				if (pipeline == null) throw new IllegalArgumentException("Unsupported shadow terrain layer " + draw.layer());
				pass.setPipeline(pipeline);
				pass.setVertexBuffer(Blaze3DMetalMappings.VERTEX_BUFFER_BASE_INDEX, draw.vertices(), draw.vertexOffset());
				pass.drawIndexed(MetalRenderPass.Primitive.TRIANGLE, draw.indices(), draw.indexOffset(), draw.indexType(),
					draw.indexCount(), frame.cascadeCount(), 0, Math.multiplyExact(index, frame.cascadeCount()));
			}
		}
	}

	@Override
	public void close() {
		this.pipelines.values().forEach(MetalRenderPipeline::close);
		this.pipelines.clear();
	}
}
