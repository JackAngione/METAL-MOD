package dev.metalcraft.client.shader.wind;

import org.jspecify.annotations.Nullable;

/** Attaches the original section sidecar to a sorted draw without changing its indices. */
public interface WindDrawSource {
	@Nullable WindMeshBinding metalcraft$windMesh();
	void metalcraft$windMesh(@Nullable WindMeshBinding binding);
}
