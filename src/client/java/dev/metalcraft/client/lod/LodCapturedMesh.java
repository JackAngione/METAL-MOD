package dev.metalcraft.client.lod;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** CPU candidate owned first by compiler results, then by precisely one compiled mesh. */
public final class LodCapturedMesh implements AutoCloseable {
    private final LodRevisionTracker.Ticket ticket;
    private final Runnable release;
    private final Runnable invalidated = this::close;
    private LodBakedMesh.@Nullable Simplified mesh;

    LodCapturedMesh(LodRevisionTracker.Ticket ticket, LodBakedMesh.Simplified mesh, Runnable release) {
        this.ticket = Objects.requireNonNull(ticket);
        this.mesh = Objects.requireNonNull(mesh);
        this.release = Objects.requireNonNull(release);
        ticket.onInvalidate(this.invalidated);
    }

    public TerrainSnapshot.Key key() { return ticket.key(); }

    /** A draw adapter must also recheck the owning CompiledSectionMesh identity before suppression. */
    public synchronized LodBakedMesh.@Nullable Simplified currentMesh() { return ticket.current() ? mesh : null; }

    @Override public synchronized void close() {
        if (mesh == null) return;
        mesh = null;
        ticket.forget(this.invalidated);
        release.run();
    }
}
