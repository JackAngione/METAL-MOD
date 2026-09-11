package dev.metalcraft.client.shader.water;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.jspecify.annotations.Nullable;

/** Metadata has exactly the same publication and retirement owner as its compiled section. */
public interface WaterMeshSource {
	@Nullable WaterMeshBinding metalcraft$waterMesh(ChunkSectionLayer layer);
}
