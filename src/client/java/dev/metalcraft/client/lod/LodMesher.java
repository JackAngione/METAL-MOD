package dev.metalcraft.client.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * Turns a node's cells into boxes: merged tops, exposed side walls and, for floating terrain,
 * bottoms.
 *
 * <p>Tops merge wherever heights match, because colour comes from the node's texel block rather
 * than from vertices; walls merge along runs with the same exposed interval. Every wall on a
 * group or node edge reaches at least {@link #skirt} blocks below its top, so a neighbour drawn
 * at another level, or replaced by native terrain, never opens a gap. Fluids emit their surface
 * only; all their vertices therefore share one upward water normal.
 */
final class LodMesher {
    /** Vertex colour for each face direction (up, down, north, south, west, east) as brightness. */
    private final float[] shade;
    private final int atlasWidth;
    private final int atlasHeight;
    private final float whiteU;
    private final float whiteV;
    private final int worldMinY;

    LodMesher(float[] shade, int atlasWidth, int atlasHeight, float whiteU, float whiteV, int worldMinY) {
        this.shade = shade.clone();
        this.atlasWidth = atlasWidth;
        this.atlasHeight = atlasHeight;
        this.whiteU = whiteU;
        this.whiteV = whiteV;
        this.worldMinY = worldMinY;
    }

    static int skirt(int cell) { return 2 * cell + 4; }

    /**
     * @param split one quad group per 16-block chunk (cells up to 8 blocks), so the renderer can
     *              leave out chunks that native rendering covers
     * @param slotX first texel column of this node in the colour atlas
     * @param slotY first texel row of this node in the colour atlas
     */
    LodMesh mesh(LodTile tile, int level, boolean split, int slotX, int slotY) {
        int cell = 1 << level;
        int groupCells = split ? Math.max(1, 16 / cell) : LodTile.CELLS;
        int groups = LodTile.CELLS / groupCells;
        Writer writer = new Writer(this, tile, cell, slotX, slotY);
        int[] solidRanges = new int[groups * groups * 2];
        int[] fluidRanges = new int[groups * groups * 2];
        try {
            for (int gz = 0; gz < groups; gz++) for (int gx = 0; gx < groups; gx++) {
                int group = gx + gz * groups;
                solidRanges[2 * group] = writer.quads;
                writer.solid(gx * groupCells, gz * groupCells, groupCells);
                solidRanges[2 * group + 1] = writer.quads - solidRanges[2 * group];
            }
            int solid = writer.quads;
            for (int gz = 0; gz < groups; gz++) for (int gx = 0; gx < groups; gx++) {
                int group = gx + gz * groups;
                fluidRanges[2 * group] = writer.quads - solid;
                writer.fluid(gx * groupCells, gz * groupCells, groupCells);
                fluidRanges[2 * group + 1] = writer.quads - solid - fluidRanges[2 * group];
            }
            ByteBuffer colors = MemoryUtil.memAlloc(LodTile.CELLS * LodTile.CELLS * 4);
            for (int j = 0; j < LodTile.CELLS; j++) for (int i = 0; i < LodTile.CELLS; i++) {
                long value = tile.cell(i, j);
                int rgb = LodCell.rgb(value);
                colors.put((byte)(rgb >> 16)).put((byte)(rgb >> 8)).put((byte)rgb).put((byte)(LodCell.hasTerrain(value) ? 255 : 0));
            }
            colors.flip();
            ByteBuffer vertices = writer.finish();
            return new LodMesh(vertices, solid, writer.quads - solid, solidRanges, fluidRanges, groups, colors,
                writer.quads == 0 ? 0 : writer.minY, writer.quads == 0 ? 0 : writer.maxY);
        } catch (RuntimeException | Error failure) {
            writer.release();
            throw failure;
        }
    }

    /** Growable native vertex buffer plus the per-node emission state. */
    private static final class Writer {
        private final LodMesher mesher;
        private final LodTile tile;
        private final int cell;
        private final int slotX;
        private final int slotY;
        private final boolean[] used = new boolean[LodTile.CELLS * LodTile.CELLS];
        private ByteBuffer buffer = MemoryUtil.memAlloc(64 * 4 * LodMesh.VERTEX_BYTES).order(ByteOrder.nativeOrder());
        int quads;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        Writer(LodMesher mesher, LodTile tile, int cell, int slotX, int slotY) {
            this.mesher = mesher;
            this.tile = tile;
            this.cell = cell;
            this.slotX = slotX;
            this.slotY = slotY;
        }

        void solid(int i0, int j0, int size) {
            this.tops(i0, j0, size);
            for (int side = 0; side < 4; side++) this.walls(i0, j0, size, side);
            this.bottoms(i0, j0, size);
        }

        private void tops(int i0, int j0, int size) {
            java.util.Arrays.fill(this.used, false);
            for (int j = j0; j < j0 + size; j++) for (int i = i0; i < i0 + size; i++) {
                if (this.used[i + j * LodTile.CELLS]) continue;
                long value = this.tile.cell(i, j);
                if (!LodCell.hasTerrain(value)) continue;
                int top = LodCell.top(value), light = LodCell.blockLight(value);
                int width = 1;
                while (i + width < i0 + size && !this.used[i + width + j * LodTile.CELLS] && sameTop(this.tile.cell(i + width, j), top, light)) width++;
                int depth = 1;
                rows:
                while (j + depth < j0 + size) {
                    for (int k = 0; k < width; k++) {
                        if (this.used[i + k + (j + depth) * LodTile.CELLS] || !sameTop(this.tile.cell(i + k, j + depth), top, light)) break rows;
                    }
                    depth++;
                }
                for (int dz = 0; dz < depth; dz++) java.util.Arrays.fill(this.used, i + (j + dz) * LodTile.CELLS, i + width + (j + dz) * LodTile.CELLS, true);
                float x0 = i * this.cell, x1 = (i + width) * this.cell, z0 = j * this.cell, z1 = (j + depth) * this.cell;
                float u0 = this.u(i), u1 = this.u(i + width), v0 = this.v(j), v1 = this.v(j + depth);
                int color = this.gray(0);
                this.quad(x0, top, z0, u0, v0, x0, top, z1, u0, v1, x1, top, z1, u1, v1, x1, top, z0, u1, v0, color, light);
            }
        }

        private static boolean sameTop(long value, int top, int light) {
            return LodCell.hasTerrain(value) && LodCell.top(value) == top && LodCell.blockLight(value) == light;
        }

        private void bottoms(int i0, int j0, int size) {
            for (int j = j0; j < j0 + size; j++) for (int i = i0; i < i0 + size; i++) {
                long value = this.tile.cell(i, j);
                if (!LodCell.hasTerrain(value)) continue;
                int bottom = LodCell.bottom(value);
                if (bottom <= this.mesher.worldMinY) continue;
                float x0 = i * this.cell, x1 = (i + 1) * this.cell, z0 = j * this.cell, z1 = (j + 1) * this.cell;
                float u0 = this.u(i), u1 = this.u(i + 1), v0 = this.v(j), v1 = this.v(j + 1);
                this.quad(x0, bottom, z1, u0, v1, x0, bottom, z0, u0, v0, x1, bottom, z0, u1, v0, x1, bottom, z1, u1, v1,
                    this.gray(1), LodCell.blockLight(value));
            }
        }

        /** Side 0 north (-z), 1 south (+z), 2 west (-x), 3 east (+x). Runs merge along the edge. */
        private void walls(int i0, int j0, int size, int side) {
            boolean alongX = side < 2;
            int dx = side == 2 ? -1 : side == 3 ? 1 : 0, dz = side == 0 ? -1 : side == 1 ? 1 : 0;
            int skirt = skirt(this.cell);
            for (int line = 0; line < size; line++) {
                int runStart = 0, runLow = 0, runHigh = 0, runLight = 0;
                boolean open = false;
                for (int along = 0; along <= size; along++) {
                    int low = 0, high = 0, light = 0;
                    boolean exposed = false;
                    if (along < size) {
                        int i = alongX ? i0 + along : i0 + line, j = alongX ? j0 + line : j0 + along;
                        long value = this.tile.cell(i, j);
                        if (LodCell.hasTerrain(value)) {
                            int top = LodCell.top(value), bottom = LodCell.bottom(value);
                            int ni = i + dx, nj = j + dz;
                            boolean edge = ni < i0 || nj < j0 || ni >= i0 + size || nj >= j0 + size;
                            long neighbor = this.tile.cell(ni, nj);
                            low = bottom;
                            if (LodCell.hasTerrain(neighbor)) {
                                int coverTop = LodCell.top(neighbor), coverBottom = LodCell.bottom(neighbor);
                                // Edge walls reach below the neighbour: it may be drawn at another level or natively.
                                if (edge) coverTop = Math.min(coverTop, top - skirt);
                                if (coverTop > coverBottom) {
                                    low = Math.max(bottom, coverTop);
                                    int under = Math.min(top, coverBottom);
                                    if (under > bottom) this.wall(side, i0, j0, line, along, along + 1, bottom, under, LodCell.blockLight(value));
                                }
                            }
                            high = top;
                            light = LodCell.blockLight(value);
                            exposed = low < high;
                        }
                    }
                    if (open && (!exposed || low != runLow || high != runHigh || light != runLight)) {
                        this.wall(side, i0, j0, line, runStart, along, runLow, runHigh, runLight);
                        open = false;
                    }
                    if (exposed && !open) {
                        open = true;
                        runStart = along;
                        runLow = low;
                        runHigh = high;
                        runLight = light;
                    }
                }
            }
        }

        private void wall(int side, int i0, int j0, int line, int start, int end, int low, int high, int light) {
            int color = this.gray(side + 2);
            if (side < 2) {
                int j = j0 + line;
                float z = (side == 0 ? j : j + 1) * this.cell;
                float x0 = (i0 + start) * this.cell, x1 = (i0 + end) * this.cell;
                float u0 = this.u(i0 + start), u1 = this.u(i0 + end), v = this.vCenter(j);
                if (side == 1) this.quad(x0, low, z, u0, v, x1, low, z, u1, v, x1, high, z, u1, v, x0, high, z, u0, v, color, light);
                else this.quad(x1, low, z, u1, v, x0, low, z, u0, v, x0, high, z, u0, v, x1, high, z, u1, v, color, light);
            } else {
                int i = i0 + line;
                float x = (side == 2 ? i : i + 1) * this.cell;
                float z0 = (j0 + start) * this.cell, z1 = (j0 + end) * this.cell;
                float u = this.uCenter(i), v0 = this.v(j0 + start), v1 = this.v(j0 + end);
                if (side == 3) this.quad(x, low, z1, u, v1, x, low, z0, u, v0, x, high, z0, u, v0, x, high, z1, u, v1, color, light);
                else this.quad(x, low, z0, u, v0, x, low, z1, u, v1, x, high, z1, u, v1, x, high, z0, u, v0, color, light);
            }
        }

        void fluid(int i0, int j0, int size) {
            java.util.Arrays.fill(this.used, false);
            for (int j = j0; j < j0 + size; j++) for (int i = i0; i < i0 + size; i++) {
                if (this.used[i + j * LodTile.CELLS]) continue;
                long value = this.tile.cell(i, j);
                if (!LodCell.hasFluid(value)) continue;
                int top = LodCell.fluidTop(value), color = this.tile.fluidColor(i, j);
                int width = 1;
                while (i + width < i0 + size && !this.used[i + width + j * LodTile.CELLS] && this.sameFluid(i + width, j, top, color)) width++;
                int depth = 1;
                rows:
                while (j + depth < j0 + size) {
                    for (int k = 0; k < width; k++) {
                        if (this.used[i + k + (j + depth) * LodTile.CELLS] || !this.sameFluid(i + k, j + depth, top, color)) break rows;
                    }
                    depth++;
                }
                for (int dz = 0; dz < depth; dz++) java.util.Arrays.fill(this.used, i + (j + dz) * LodTile.CELLS, i + width + (j + dz) * LodTile.CELLS, true);
                float x0 = i * this.cell, x1 = (i + width) * this.cell, z0 = j * this.cell, z1 = (j + depth) * this.cell;
                float u = this.mesher.whiteU, v = this.mesher.whiteV;
                this.quad(x0, top, z0, u, v, x0, top, z1, u, v, x1, top, z1, u, v, x1, top, z0, u, v, color, 0);
            }
        }

        private boolean sameFluid(int i, int j, int top, int color) {
            long value = this.tile.cell(i, j);
            return LodCell.hasFluid(value) && LodCell.fluidTop(value) == top && this.tile.fluidColor(i, j) == color;
        }

        private float u(int i) { return (float)(this.slotX + i) / this.mesher.atlasWidth; }
        private float v(int j) { return (float)(this.slotY + j) / this.mesher.atlasHeight; }
        private float uCenter(int i) { return (this.slotX + i + 0.5F) / this.mesher.atlasWidth; }
        private float vCenter(int j) { return (this.slotY + j + 0.5F) / this.mesher.atlasHeight; }

        /** Opaque grey vertex colour for a face direction's cardinal shading. */
        private int gray(int face) {
            int level = Math.round(this.mesher.shade[face] * 255);
            return 0xFF000000 | level << 16 | level << 8 | level;
        }

        private void quad(float x0, float y0, float z0, float u0, float v0, float x1, float y1, float z1, float u1, float v1,
                          float x2, float y2, float z2, float u2, float v2, float x3, float y3, float z3, float u3, float v3,
                          int argb, int light) {
            if (this.buffer.remaining() < 4 * LodMesh.VERTEX_BYTES) {
                this.buffer = MemoryUtil.memRealloc(this.buffer, this.buffer.capacity() * 2).order(ByteOrder.nativeOrder());
            }
            this.vertex(x0, y0, z0, u0, v0, argb, light);
            this.vertex(x1, y1, z1, u1, v1, argb, light);
            this.vertex(x2, y2, z2, u2, v2, argb, light);
            this.vertex(x3, y3, z3, u3, v3, argb, light);
            this.minY = (int)Math.min(this.minY, Math.min(Math.min(y0, y1), Math.min(y2, y3)));
            this.maxY = (int)Math.max(this.maxY, Math.max(Math.max(y0, y1), Math.max(y2, y3)));
            this.quads++;
        }

        private void vertex(float x, float y, float z, float u, float v, int argb, int light) {
            this.buffer.putFloat(x).putFloat(y).putFloat(z);
            this.buffer.put((byte)(argb >> 16)).put((byte)(argb >> 8)).put((byte)argb).put((byte)(argb >>> 24));
            this.buffer.putFloat(u).putFloat(v);
            this.buffer.putShort((short)(light << 4)).putShort((short)240);
        }

        ByteBuffer finish() {
            this.buffer.flip();
            ByteBuffer result = this.buffer;
            this.buffer = null;
            return result;
        }

        void release() {
            if (this.buffer != null) MemoryUtil.memFree(this.buffer);
            this.buffer = null;
        }
    }
}
