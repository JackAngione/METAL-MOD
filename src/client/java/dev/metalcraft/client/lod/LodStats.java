package dev.metalcraft.client.lod;

/** Counters for tests, benchmarks and the debug overlay. Cumulative fields only grow within a session. */
public final class LodStats {
    public int drawnNodes;
    public int residentNodes;
    public int draws;
    public int inFlight;
    /** Nodes wanted this frame that are missing or out of date; zero once the view has settled. */
    public int pending;
    public int capturedChunks;
    public long gpuBytes;
    public long builds;
    public long buildNanos;
    public long quads;
    public long captures;
    /** Saved chunks read from region files to replace generated terrain. */
    public long savedChunks;

    public LodStats copy() {
        LodStats copy = new LodStats();
        copy.drawnNodes = this.drawnNodes;
        copy.residentNodes = this.residentNodes;
        copy.draws = this.draws;
        copy.inFlight = this.inFlight;
        copy.pending = this.pending;
        copy.capturedChunks = this.capturedChunks;
        copy.gpuBytes = this.gpuBytes;
        copy.builds = this.builds;
        copy.buildNanos = this.buildNanos;
        copy.quads = this.quads;
        copy.captures = this.captures;
        copy.savedChunks = this.savedChunks;
        return copy;
    }
}
