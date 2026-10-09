package dev.metalcraft.client.lod;

/** Counters for tests, benchmarks and the debug overlay. Cumulative fields only grow within a session. */
public final class LodStats {
    public int drawnNodes;
    /** Drawn nodes that use block textures. */
    public int texturedNodes;
    public int residentNodes;
    public int draws;
    public int inFlight;
    /** Nodes wanted this frame that are missing or out of date; zero once the view has settled. */
    public int pending;
    /** A settings change is still building its view; the previous view is released when it completes. */
    public boolean updating;
    public int capturedChunks;
    public long gpuBytes;
    /** Effective texture radius after memory-budget reductions, in blocks. */
    public double textureDistance;
    public int atlasSize;
    /** Vertex bytes of the nodes drawn this frame, and of the textured ones among them. */
    public long drawnBytes;
    public long texturedBytes;
    public long builds;
    public long buildNanos;
    public long quads;
    public long captures;
    /** Saved chunks read from region files to replace generated terrain. */
    public long savedChunks;

    public LodStats copy() {
        LodStats copy = new LodStats();
        copy.drawnNodes = this.drawnNodes;
        copy.texturedNodes = this.texturedNodes;
        copy.residentNodes = this.residentNodes;
        copy.draws = this.draws;
        copy.inFlight = this.inFlight;
        copy.pending = this.pending;
        copy.updating = this.updating;
        copy.capturedChunks = this.capturedChunks;
        copy.gpuBytes = this.gpuBytes;
        copy.textureDistance = this.textureDistance;
        copy.atlasSize = this.atlasSize;
        copy.drawnBytes = this.drawnBytes;
        copy.texturedBytes = this.texturedBytes;
        copy.builds = this.builds;
        copy.buildNanos = this.buildNanos;
        copy.quads = this.quads;
        copy.captures = this.captures;
        copy.savedChunks = this.savedChunks;
        return copy;
    }
}
