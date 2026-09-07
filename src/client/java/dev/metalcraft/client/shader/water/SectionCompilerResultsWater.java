package dev.metalcraft.client.shader.water;

import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** Water sidecars produced with a section's original, unsorted terrain vertices. */
public interface SectionCompilerResultsWater {
	Map<ChunkSectionLayer, WaterVertexMetadata> metalcraft$waterVertexMetadata();

	void metalcraft$setWaterVertexMetadata(Map<ChunkSectionLayer, WaterVertexMetadata> metadata);
}
