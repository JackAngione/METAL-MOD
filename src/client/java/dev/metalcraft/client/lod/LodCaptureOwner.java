package dev.metalcraft.client.lod;

import org.jspecify.annotations.Nullable;

/** Compiler-results ownership transfers exactly once, or releases on compilation cancellation. */
public interface LodCaptureOwner {
    void metalcraft$lodCandidate(@Nullable LodCapturedMesh candidate);
    @Nullable LodCapturedMesh metalcraft$takeLodCandidate();
}
