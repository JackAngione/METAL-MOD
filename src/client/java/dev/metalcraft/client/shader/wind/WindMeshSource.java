package dev.metalcraft.client.shader.wind;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.jspecify.annotations.Nullable;

/** Metadata has exactly the same publication and retirement owner as its compiled section. */
public interface WindMeshSource {
	@Nullable WindMeshBinding metalcraft$windMesh(ChunkSectionLayer layer);
}
