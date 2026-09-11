package dev.metalcraft.client.lod;

import org.jspecify.annotations.Nullable;

public interface LodMeshSource {
    @Nullable LodCapturedMesh metalcraft$lodCandidate();
}
