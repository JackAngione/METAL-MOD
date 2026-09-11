package dev.metalcraft.client.lod;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** CPU candidate owned first by compiler results, then by precisely one compiled mesh. */
public final class LodCapturedMesh implements AutoCloseable {
    private final LodRevisionTracker.Ticket ticket;
    private final Runnable release;
    private final Runnable invalidated = this::close;
    private volatile java.util.@Nullable List<LodBakedMesh.Simplified> meshes;
    private final LodAtlas atlas;

    LodCapturedMesh(LodRevisionTracker.Ticket ticket, LodBakedMesh.Simplified mesh, Runnable release) {
        this(ticket, mesh, LodAtlas.current(), release);
    }

    LodCapturedMesh(LodRevisionTracker.Ticket ticket, LodBakedMesh.Simplified mesh, LodAtlas atlas, Runnable release) {
        this(ticket, java.util.Collections.nCopies(4, mesh), atlas, release);
    }

    LodCapturedMesh(LodRevisionTracker.Ticket ticket, java.util.List<LodBakedMesh.Simplified> meshes, LodAtlas atlas, Runnable release) {
        this.ticket = Objects.requireNonNull(ticket);
        if (meshes.size() != 4) throw new IllegalArgumentException("Four loaded tiers required");
        this.meshes = java.util.List.copyOf(meshes);
        this.atlas = Objects.requireNonNull(atlas);
        this.release = Objects.requireNonNull(release);
        ticket.onInvalidate(this.invalidated);
    }

    public TerrainSnapshot.Key key() { return ticket.key(); }

    /** A draw adapter must also recheck the owning CompiledSectionMesh identity before suppression. */
    public LodBakedMesh.@Nullable Simplified currentMesh() {
        return mesh(4);
    }

    public LodBakedMesh.@Nullable Simplified mesh(int tier) {
        if (tier < 1 || tier > 4) throw new IllegalArgumentException("LOD tier must be 1–4");
        var current = meshes;
        return ticket.current() && atlas == LodAtlas.current() && current != null ? current.get(tier - 1) : null;
    }

    @Override public synchronized void close() {
        if (meshes == null) return;
        meshes = null;
        ticket.forget(this.invalidated);
        release.run();
    }
}
