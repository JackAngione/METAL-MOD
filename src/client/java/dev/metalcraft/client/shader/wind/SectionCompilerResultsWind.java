package dev.metalcraft.client.shader.wind;

import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/** Wind sidecars produced with a section's original, unsorted terrain vertices. */
public interface SectionCompilerResultsWind {
	Map<ChunkSectionLayer, WindVertexMetadata> metalcraft$windVertexMetadata();

	void metalcraft$setWindVertexMetadata(Map<ChunkSectionLayer, WindVertexMetadata> metadata);
}
