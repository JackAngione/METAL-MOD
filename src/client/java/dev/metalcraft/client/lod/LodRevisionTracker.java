package dev.metalcraft.client.lod;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Bounded extraction-side identity table. Workers only read immutable keys and a revocation flag. */
public final class LodRevisionTracker {
    private record Section(int x, int y, int z) { }

    public static final class Ticket {
        private final TerrainSnapshot.Key key;
        private volatile boolean current = true;
        private final java.util.List<Runnable> invalidations = new java.util.ArrayList<>();
        private Ticket(TerrainSnapshot.Key key) { this.key = key; }
        public TerrainSnapshot.Key key() { return key; }
        public boolean current() { return current; }

        void onInvalidate(Runnable action) {
            synchronized (this) {
                if (current) { invalidations.add(action); return; }
            }
            action.run();
        }

        synchronized void forget(Runnable action) { invalidations.remove(action); }

        private void revoke() {
            java.util.List<Runnable> actions;
            synchronized (this) {
                current = false;
                actions = java.util.List.copyOf(invalidations);
                invalidations.clear();
            }
            // Do not hold the ticket monitor while disposing a candidate; its owner
            // may be closing it concurrently. Revocation never touches GPU resources.
            actions.forEach(Runnable::run);
        }
    }

    private final int maxSections;
    private final LinkedHashMap<Section, Ticket> sections = new LinkedHashMap<>();
    private long session, generation, revision;
    private String dimension = "disconnected";

    public LodRevisionTracker(int maxSections) {
        if (maxSections < 1) throw new IllegalArgumentException("Positive section limit required");
        this.maxSections = maxSections;
    }

    /** Capture at extraction, before the immutable region is passed to a compiler worker. */
    public synchronized Ticket capture(int x, int y, int z) {
        Section section = new Section(x, y, z);
        revoke(sections.remove(section));
        if (sections.size() == maxSections) {
            var oldest = sections.pollFirstEntry();
            revoke(oldest.getValue());
        }
        Ticket ticket = new Ticket(new TerrainSnapshot.Key(session, dimension, x, y, z, ++revision, generation));
        sections.put(section, ticket);
        return ticket;
    }

    /** The game's dirty-neighbor expansion supplies light, biome and shared-boundary invalidation. */
    public synchronized void dirty(int x, int y, int z) { revoke(sections.remove(new Section(x, y, z))); }

    public synchronized void unload(int x, int z) {
        var entries = sections.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getKey().x == x && entry.getKey().z == z) {
                revoke(entry.getValue());
                entries.remove();
            }
        }
    }

    public synchronized void world(String dimension) {
        this.dimension = Objects.requireNonNull(dimension);
        session++;
        clear();
    }

    public synchronized void resources() { generation++; clear(); }
    public synchronized int trackedSections() { return sections.size(); }

    private void clear() { sections.values().forEach(LodRevisionTracker::revoke); sections.clear(); }
    private static void revoke(Ticket ticket) { if (ticket != null) ticket.revoke(); }
}
