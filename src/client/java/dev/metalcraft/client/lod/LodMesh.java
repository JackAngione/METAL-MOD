package dev.metalcraft.client.lod;

import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * CPU mesh of one node, produced on a worker and consumed once by the upload.
 *
 * <p>Vertices use Minecraft's block format (position, colour, UV0, UV2) so the ordinary terrain
 * pipelines, and shader packs that replace them, draw it unchanged. Quads are grouped: one group
 * per chunk for nodes that may meet native terrain, otherwise one group. Solid quads for every
 * group come first, then fluid quads. {@code colors} is the node's 32×32 RGBA texel block.
 */
final class LodMesh implements AutoCloseable {
    static final int VERTEX_BYTES = 28;

    private @Nullable ByteBuffer vertices;
    final int solidQuads;
    final int fluidQuads;
    /** Pairs of (first quad, quad count) per group, indexed {@code 2 * group}. */
    final int[] solidRanges;
    final int[] fluidRanges;
    final int groupsPerSide;
    private @Nullable ByteBuffer colors;
    final int minY;
    final int maxY;

    LodMesh(ByteBuffer vertices, int solidQuads, int fluidQuads, int[] solidRanges, int[] fluidRanges, int groupsPerSide,
            ByteBuffer colors, int minY, int maxY) {
        this.vertices = vertices;
        this.solidQuads = solidQuads;
        this.fluidQuads = fluidQuads;
        this.solidRanges = solidRanges;
        this.fluidRanges = fluidRanges;
        this.groupsPerSide = groupsPerSide;
        this.colors = colors;
        this.minY = minY;
        this.maxY = maxY;
    }

    ByteBuffer vertices() {
        if (this.vertices == null) throw new IllegalStateException("LOD mesh already released");
        return this.vertices;
    }

    ByteBuffer colors() {
        if (this.colors == null) throw new IllegalStateException("LOD mesh already released");
        return this.colors;
    }

    long bytes() { return (long)(this.solidQuads + this.fluidQuads) * 4 * VERTEX_BYTES; }

    @Override
    public void close() {
        if (this.vertices != null) {
            MemoryUtil.memFree(this.vertices);
            this.vertices = null;
        }
        if (this.colors != null) {
            MemoryUtil.memFree(this.colors);
            this.colors = null;
        }
    }
}
