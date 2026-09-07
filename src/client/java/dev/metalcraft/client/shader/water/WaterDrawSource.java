package dev.metalcraft.client.shader.water;

import org.jspecify.annotations.Nullable;

/** Attaches the original section sidecar to a sorted draw without changing its indices. */
public interface WaterDrawSource {
	@Nullable WaterMeshBinding metalcraft$waterMesh();
	void metalcraft$waterMesh(@Nullable WaterMeshBinding binding);
}
