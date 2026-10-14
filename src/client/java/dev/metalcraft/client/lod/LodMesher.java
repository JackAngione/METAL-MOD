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
 *
 * <p>{@link #meshTextured} draws block-sized cells with block-atlas textures instead, like native
 * terrain seen from afar: see {@link Textured}.
 */
final class LodMesher {
    /** Vertex colour for each face direction (up, down, north, south, west, east) as brightness. */
    private final float[] shade;
    private final int[] grays = new int[6];
    private final int atlasWidth;
    private final int atlasHeight;
    private final float whiteU;
    private final float whiteV;
    private final int worldMinY;

    LodMesher(float[] shade, int atlasWidth, int atlasHeight, float whiteU, float whiteV, int worldMinY) {
        this.shade = shade.clone();
        for (int face = 0; face < this.grays.length; face++) {
            int level = Math.round(this.shade[face] * 255);
            this.grays[face] = 0xFF000000 | level << 16 | level << 8 | level;
        }
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
        Writer writer = new Writer(this, tile, cell, slotX, slotY);
        return this.build(writer, tile, split ? Math.max(1, 16 / cell) : LodTile.CELLS, writer::solid, true);
    }

    /**
     * Level-0 cells of a textured sample ({@link LodTile#surfaces}), one quad group per chunk.
     * Draws with the block atlas; only fluids still use the colour atlas's white slot.
     */
    LodMesh meshTextured(LodTile tile, LodBlockColors colors, LodBiomes biomes) {
        Writer writer = new Writer(this, tile, 1, 0, 0);
        return this.build(writer, tile, 16, new Textured(writer, tile, colors, biomes)::solid, false);
    }

    private interface Group { void emit(int i0, int j0, int size); }

    private LodMesh build(Writer writer, LodTile tile, int groupCells, Group solids, boolean colored) {
        int groups = LodTile.CELLS / groupCells;
        int[] solidRanges = new int[groups * groups * 2];
        int[] fluidRanges = new int[groups * groups * 2];
        ByteBuffer colors = null;
        try {
            for (int gz = 0; gz < groups; gz++) for (int gx = 0; gx < groups; gx++) {
                int group = gx + gz * groups;
                solidRanges[2 * group] = writer.quads;
                solids.emit(gx * groupCells, gz * groupCells, groupCells);
                solidRanges[2 * group + 1] = writer.quads - solidRanges[2 * group];
            }
            int solid = writer.quads;
            for (int gz = 0; gz < groups; gz++) for (int gx = 0; gx < groups; gx++) {
                int group = gx + gz * groups;
                fluidRanges[2 * group] = writer.quads - solid;
                writer.fluid(gx * groupCells, gz * groupCells, groupCells);
                fluidRanges[2 * group + 1] = writer.quads - solid - fluidRanges[2 * group];
            }
            if (colored) {
                colors = MemoryUtil.memAlloc(LodTile.CELLS * LodTile.CELLS * 4);
                for (int j = 0; j < LodTile.CELLS; j++) for (int i = 0; i < LodTile.CELLS; i++) {
                    long value = tile.cell(i, j);
                    int rgb = LodCell.rgb(value);
                    colors.put((byte)(rgb >> 16)).put((byte)(rgb >> 8)).put((byte)rgb).put((byte)(LodCell.hasTerrain(value) ? 255 : 0));
                }
                colors.flip();
            }
            ByteBuffer vertices = writer.finish();
            return new LodMesh(vertices, solid, writer.quads - solid, solidRanges, fluidRanges, groups, colors,
                writer.quads == 0 ? 0 : writer.minY, writer.quads == 0 ? 0 : writer.maxY);
        } catch (RuntimeException | Error failure) {
            writer.release();
            if (colors != null) MemoryUtil.memFree(colors);
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
        // One bit per cell: marking a merged rectangle touches one integer per row.
        private final int[] used = new int[LodTile.CELLS];
        private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
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
            this.horizontal(i0, j0, size, true);
        }

        private void bottoms(int i0, int j0, int size) {
            this.horizontal(i0, j0, size, false);
        }

        /** Height, light and atlas coordinates remain identical across merged horizontal faces. */
        private void horizontal(int i0, int j0, int size, boolean topFace) {
            java.util.Arrays.fill(this.used, j0, j0 + size, 0);
            int color = this.gray(topFace ? 0 : 1);
            for (int j = j0; j < j0 + size; j++) for (int i = i0; i < i0 + size; i++) {
                if ((this.used[j] & 1 << i) != 0) continue;
                long value = this.tile.cell(i, j);
                if (!LodCell.hasTerrain(value)) continue;
                int height = topFace ? LodCell.top(value) : LodCell.bottom(value);
                if (!topFace && height <= this.mesher.worldMinY) continue;
                int light = LodCell.blockLight(value);
                int width = 1;
                while (i + width < i0 + size && (this.used[j] & 1 << (i + width)) == 0
                    && sameHeight(this.tile.cell(i + width, j), height, light, topFace)) width++;
                // A long shift handles a rectangle spanning all 32 columns.
                int mask = (int)((1L << width) - 1) << i;
                int depth = 1;
                rows:
                while (j + depth < j0 + size) {
                    if ((this.used[j + depth] & mask) != 0) break;
                    for (int k = 0; k < width; k++) {
                        if (!sameHeight(this.tile.cell(i + k, j + depth), height, light, topFace)) break rows;
                    }
                    depth++;
                }
                for (int dz = 0; dz < depth; dz++) this.used[j + dz] |= mask;
                float x0 = i * this.cell, x1 = (i + width) * this.cell, z0 = j * this.cell, z1 = (j + depth) * this.cell;
                float u0 = this.u(i), u1 = this.u(i + width), v0 = this.v(j), v1 = this.v(j + depth);
                if (topFace) {
                    this.quad(x0, height, z0, u0, v0, x0, height, z1, u0, v1, x1, height, z1, u1, v1, x1, height, z0, u1, v0, color, light);
                } else {
                    this.quad(x0, height, z1, u0, v1, x0, height, z0, u0, v0, x1, height, z0, u1, v0, x1, height, z1, u1, v1, color, light);
                }
            }
        }

        private static boolean sameHeight(long value, int height, int light, boolean topFace) {
            return LodCell.hasTerrain(value) && (topFace ? LodCell.top(value) : LodCell.bottom(value)) == height
                && LodCell.blockLight(value) == light;
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
            java.util.Arrays.fill(this.used, j0, j0 + size, 0);
            for (int j = j0; j < j0 + size; j++) for (int i = i0; i < i0 + size; i++) {
                if ((this.used[j] & 1 << i) != 0) continue;
                long value = this.tile.cell(i, j);
                if (!LodCell.hasFluid(value)) continue;
                int top = LodCell.fluidTop(value), color = this.tile.fluidColor(i, j);
                int width = 1;
                while (i + width < i0 + size && (this.used[j] & 1 << (i + width)) == 0 && this.sameFluid(i + width, j, top, color)) width++;
                int mask = (int)((1L << width) - 1) << i;
                int depth = 1;
                rows:
                while (j + depth < j0 + size) {
                    if ((this.used[j + depth] & mask) != 0) break;
                    for (int k = 0; k < width; k++) {
                        if (!this.sameFluid(i + k, j + depth, top, color)) break rows;
                    }
                    depth++;
                }
                for (int dz = 0; dz < depth; dz++) this.used[j + dz] |= mask;
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
            return this.mesher.grays[face];
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

        /**
         * One quad from per-vertex arrays (positions, UVs, ARGB) in vertex order, emitted from
         * vertex {@code first} so the quad splits along the diagonal its colours call for.
         */
        void quad(float[] xyz, float[] uv, int[] argb, int first, int light) {
            if (this.buffer.remaining() < 4 * LodMesh.VERTEX_BYTES) {
                this.buffer = MemoryUtil.memRealloc(this.buffer, this.buffer.capacity() * 2).order(ByteOrder.nativeOrder());
            }
            for (int n = 0; n < 4; n++) {
                int k = (n + first) & 3;
                this.vertex(xyz[3 * k], xyz[3 * k + 1], xyz[3 * k + 2], uv[2 * k], uv[2 * k + 1], argb[k], light);
                this.minY = Math.min(this.minY, (int)Math.floor(xyz[3 * k + 1]));
                this.maxY = Math.max(this.maxY, (int)Math.ceil(xyz[3 * k + 1]));
            }
            this.quads++;
        }

        private void vertex(float x, float y, float z, float u, float v, int argb, int light) {
            this.buffer.putFloat(x).putFloat(y).putFloat(z);
            int rgba = argb << 8 | argb >>> 24;
            this.buffer.putInt(LITTLE_ENDIAN ? Integer.reverseBytes(rgba) : rgba);
            this.buffer.putFloat(u).putFloat(v);
            this.buffer.putInt(LITTLE_ENDIAN ? 240 << 16 | light << 4 : light << 20 | 240);
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

    /**
     * Block-sized cells as native terrain looks from afar. Each cell gets its own top with the
     * block's top sprite (turned by a position hash, as vanilla turns grass and sand), and its
     * exposed walls are tiled one block per quad with the top block's side sprite, then the block
     * under it (grass over dirt) and, from {@link #SOIL_DEPTH} down, the rock beneath (stone under
     * dirt, sandstone under sand); very deep walls tile four blocks per quad. Vertex colours
     * carry the biome tint, the face shade and vanilla-style ambient occlusion from neighbouring
     * columns. Skirts below a neighbour's top are only ever seen through gaps, so they stay merged
     * with a stretched sprite.
     */
    private static final class Textured {
        private static final int TILED_BLOCKS = 16;
        private static final int DEEP_TILE = 4;
        /** Blocks of top block plus soil above the rock, as vanilla's surface rules lay them. */
        private static final int SOIL_DEPTH = 4;
        private static final int WHITE = 0xFFFFFFFF;
        private final Writer out;
        private final LodTile tile;
        private final LodBlockColors colors;
        private final LodBiomes biomes;
        private final float[] shade;
        private final float[] sprites;
        private final int fallback = net.minecraft.world.level.block.Block.getId(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        // One quad in vertex order.
        private final float[] xyz = new float[12];
        private final float[] uv = new float[8];
        private final float[] brightness = new float[4];
        private final int[] argb = new int[4];

        Textured(Writer out, LodTile tile, LodBlockColors colors, LodBiomes biomes) {
            this.out = out;
            this.tile = tile;
            this.colors = colors;
            this.biomes = biomes;
            this.shade = out.mesher.shade;
            this.sprites = colors.sprites;
        }

        void solid(int i0, int j0, int size) {
            for (int j = j0; j < j0 + size; j++) for (int i = i0; i < i0 + size; i++) this.top(i, j);
            for (int side = 0; side < 4; side++) this.walls(i0, j0, size, side);
            for (int j = j0; j < j0 + size; j++) for (int i = i0; i < i0 + size; i++) this.bottom(i, j);
        }

        private void top(int i, int j) {
            long value = this.tile.cell(i, j);
            if (!LodCell.hasTerrain(value)) return;
            int top = LodCell.top(value);
            long surface = this.tile.surface(i, j);
            int x = this.tile.originX + i, z = this.tile.originZ + j;
            int state = surface == 0 ? this.fallback : LodSurface.topState(surface);
            int tint = surface == 0 ? WHITE : this.colors.topTint(state, this.biomes.biome(LodSurface.biome(surface)), x, z);
            int sprite = state * LodBlockColors.SPRITE_FLOATS;
            int turn = turn(x, z);
            // Vertices (x0,z0), (x0,z1), (x1,z1), (x1,z0); texture corners follow them in the same cycle.
            for (int k = 0; k < 4; k++) {
                int dx = k < 2 ? -1 : 1, dz = k == 1 || k == 2 ? 1 : -1;
                this.position(k, dx < 0 ? i : i + 1, top, dz < 0 ? j : j + 1);
                int corner = (k + turn) & 3;
                this.uv[2 * k] = this.sprites[sprite + (corner < 2 ? 0 : 2)];
                this.uv[2 * k + 1] = this.sprites[sprite + (corner == 1 || corner == 2 ? 3 : 1)];
                this.brightness[k] = this.shade[0] * occlusion(this.filled(i + dx, j, top), this.filled(i, j + dz, top), this.filled(i + dx, j + dz, top));
            }
            this.emit(tint, LodCell.blockLight(value), true);
        }

        /** Side 0 north (-z), 1 south (+z), 2 west (-x), 3 east (+x), as in the flat mesher. */
        private void walls(int i0, int j0, int size, int side) {
            boolean alongX = side < 2;
            int dx = side == 2 ? -1 : side == 3 ? 1 : 0, dz = side == 0 ? -1 : side == 1 ? 1 : 0;
            int skirt = skirt(1);
            for (int line = 0; line < size; line++) {
                // Open run of merged skirt cells along this line.
                int runStart = 0, runLow = 0, runHigh = 0, runLight = 0;
                long runSurface = 0;
                boolean open = false;
                for (int along = 0; along <= size; along++) {
                    int low = 0, high = 0, light = 0;
                    long surface = 0;
                    boolean skirted = false;
                    if (along < size) {
                        int i = alongX ? i0 + along : i0 + line, j = alongX ? j0 + line : j0 + along;
                        long value = this.tile.cell(i, j);
                        if (LodCell.hasTerrain(value)) {
                            int top = LodCell.top(value), bottom = LodCell.bottom(value);
                            int ni = i + dx, nj = j + dz;
                            boolean edge = ni < i0 || nj < j0 || ni >= i0 + size || nj >= j0 + size;
                            long neighbor = this.tile.cell(ni, nj);
                            surface = this.tile.surface(i, j);
                            light = LodCell.blockLight(value);
                            // `visible` is where the neighbour stops covering the wall; below it only skirts.
                            int visible = bottom;
                            low = bottom;
                            if (LodCell.hasTerrain(neighbor)) {
                                int coverTop = LodCell.top(neighbor), coverBottom = LodCell.bottom(neighbor);
                                visible = Math.clamp(coverTop, bottom, top);
                                int skirtTop = edge ? Math.min(coverTop, top - skirt) : coverTop;
                                if (skirtTop > coverBottom) {
                                    low = Math.max(bottom, skirtTop);
                                    int under = Math.min(top, coverBottom);
                                    if (under > bottom) this.stretched(side, alongX ? i : j, alongX ? i + 1 : j + 1, plane(side, i, j), bottom, under, surface, i, j, light);
                                }
                            }
                            this.tiled(side, i, j, ni, nj, visible, top, surface, light);
                            high = Math.min(visible, top);
                            skirted = low < high;
                        }
                    }
                    if (open && (!skirted || low != runLow || high != runHigh || light != runLight)) {
                        int a0 = (alongX ? i0 : j0) + runStart, a1 = (alongX ? i0 : j0) + along;
                        int i = alongX ? i0 + runStart : i0 + line, j = alongX ? j0 + line : j0 + runStart;
                        this.stretched(side, a0, a1, plane(side, i, j), runLow, runHigh, runSurface, i, j, runLight);
                        open = false;
                    }
                    if (skirted && !open) {
                        open = true;
                        runStart = along;
                        runLow = low;
                        runHigh = high;
                        runLight = light;
                        runSurface = surface;
                    }
                }
            }
        }

        /** Wall blocks from {@code top} down to {@code low}, each with its own sprite and occlusion. */
        private void tiled(int side, int i, int j, int ni, int nj, int low, int top, long surface, int light) {
            if (low >= top) return;
            int x = this.tile.originX + i, z = this.tile.originZ + j;
            int sideState = surface == 0 ? this.fallback : LodSurface.sideState(surface);
            int underState = this.colors.under(sideState), deepState = this.colors.deep(underState);
            var biome = surface == 0 ? null : this.biomes.biome(LodSurface.biome(surface));
            int sideTint = biome == null ? WHITE : this.colors.sideTint(sideState, biome, x, z);
            int underTint = underState == sideState ? sideTint : biome == null ? WHITE : this.colors.sideTint(underState, biome, x, z);
            int deepTint = deepState == underState ? underTint : biome == null ? WHITE : this.colors.sideTint(deepState, biome, x, z);
            // Columns beside the one in front of the wall, to its left and right as seen from outside.
            int leftX = side == 0 ? 1 : side == 1 ? -1 : 0, leftZ = side == 3 ? 1 : side == 2 ? -1 : 0;
            int a = side < 2 ? i : j;
            float plane = plane(side, i, j);
            float faceShade = this.shade[side + 2];
            int y = top;
            for (int block = 0; y > low; block++) {
                int yHigh = y, yLow = Math.max(low, y - (block < TILED_BLOCKS ? 1 : DEEP_TILE));
                int state = block == 0 ? sideState : block < SOIL_DEPTH ? underState : deepState;
                this.wall(side, a, a + 1, plane, yLow, yHigh, state);
                boolean belowLeft = this.filled(ni + leftX, nj + leftZ, yLow - 1), belowRight = this.filled(ni - leftX, nj - leftZ, yLow - 1);
                boolean below = this.filled(ni, nj, yLow - 1), above = this.filled(ni, nj, yHigh);
                this.brightness[0] = faceShade * occlusion(below, this.filled(ni + leftX, nj + leftZ, yLow), belowLeft);
                this.brightness[1] = faceShade * occlusion(below, this.filled(ni - leftX, nj - leftZ, yLow), belowRight);
                this.brightness[2] = faceShade * occlusion(above, this.filled(ni - leftX, nj - leftZ, yHigh - 1), this.filled(ni - leftX, nj - leftZ, yHigh));
                this.brightness[3] = faceShade * occlusion(above, this.filled(ni + leftX, nj + leftZ, yHigh - 1), this.filled(ni + leftX, nj + leftZ, yHigh));
                this.emit(block == 0 ? sideTint : block < SOIL_DEPTH ? underTint : deepTint, light, true);
                y = yLow;
            }
        }

        /** One wall quad over {@code a0..a1} along the side with the under block's side sprite stretched over it. */
        private void stretched(int side, int a0, int a1, float plane, int low, int high, long surface, int i, int j, int light) {
            int sideState = surface == 0 ? this.fallback : LodSurface.sideState(surface);
            int state = this.colors.under(sideState);
            int tint = surface == 0 ? WHITE
                : this.colors.sideTint(state, this.biomes.biome(LodSurface.biome(surface)), this.tile.originX + i, this.tile.originZ + j);
            this.wall(side, a0, a1, plane, low, high, state);
            java.util.Arrays.fill(this.brightness, this.shade[side + 2]);
            this.emit(tint, light, false);
        }

        /**
         * Positions and UVs of a wall in vertex order: left-bottom, right-bottom, right-top,
         * left-top as seen from outside, which is the flat mesher's winding for each side.
         */
        private void wall(int side, int a0, int a1, float plane, float low, float high, int state) {
            float left = side == 0 || side == 3 ? a1 : a0, right = side == 0 || side == 3 ? a0 : a1;
            boolean alongX = side < 2;
            this.position(0, alongX ? left : plane, low, alongX ? plane : left);
            this.position(1, alongX ? right : plane, low, alongX ? plane : right);
            this.position(2, alongX ? right : plane, high, alongX ? plane : right);
            this.position(3, alongX ? left : plane, high, alongX ? plane : left);
            int sprite = state * LodBlockColors.SPRITE_FLOATS + 4;
            float u0 = this.sprites[sprite], v0 = this.sprites[sprite + 1], u1 = this.sprites[sprite + 2], v1 = this.sprites[sprite + 3];
            this.uv[0] = u0;
            this.uv[1] = v1;
            this.uv[2] = u1;
            this.uv[3] = v1;
            this.uv[4] = u1;
            this.uv[5] = v0;
            this.uv[6] = u0;
            this.uv[7] = v0;
        }

        /** Underside of floating terrain, with the under block's top sprite. */
        private void bottom(int i, int j) {
            long value = this.tile.cell(i, j);
            if (!LodCell.hasTerrain(value)) return;
            int bottom = LodCell.bottom(value);
            if (bottom <= this.out.mesher.worldMinY) return;
            long surface = this.tile.surface(i, j);
            int state = this.colors.under(surface == 0 ? this.fallback : LodSurface.sideState(surface));
            int sprite = state * LodBlockColors.SPRITE_FLOATS;
            float u0 = this.sprites[sprite], v0 = this.sprites[sprite + 1], u1 = this.sprites[sprite + 2], v1 = this.sprites[sprite + 3];
            this.position(0, i, bottom, j + 1);
            this.position(1, i, bottom, j);
            this.position(2, i + 1, bottom, j);
            this.position(3, i + 1, bottom, j + 1);
            this.uv[0] = u0;
            this.uv[1] = v1;
            this.uv[2] = u0;
            this.uv[3] = v0;
            this.uv[4] = u1;
            this.uv[5] = v0;
            this.uv[6] = u1;
            this.uv[7] = v1;
            java.util.Arrays.fill(this.brightness, this.shade[1]);
            int tint = surface == 0 ? WHITE
                : this.colors.sideTint(state, this.biomes.biome(LodSurface.biome(surface)), this.tile.originX + i, this.tile.originZ + j);
            this.emit(tint, LodCell.blockLight(value), false);
        }

        /** Whether column (i, j) is solid at block height y. */
        private boolean filled(int i, int j, int y) {
            long value = this.tile.cell(i, j);
            return LodCell.hasTerrain(value) && LodCell.bottom(value) <= y && LodCell.top(value) > y;
        }

        private void position(int vertex, float x, float y, float z) {
            this.xyz[3 * vertex] = x;
            this.xyz[3 * vertex + 1] = y;
            this.xyz[3 * vertex + 2] = z;
        }

        /** Writes the quad in the scratch arrays; occluded quads split along their darker diagonal, as vanilla's do. */
        private void emit(int tint, int light, boolean occluded) {
            float[] b = this.brightness;
            int first = occluded && b[0] + b[2] > b[1] + b[3] ? 1 : 0;
            for (int k = 0; k < 4; k++) this.argb[k] = scale(tint, b[k]);
            this.out.quad(this.xyz, this.uv, this.argb, first, light);
        }

        private static float plane(int side, int i, int j) {
            return switch (side) {
                case 0 -> j;
                case 1 -> j + 1;
                case 2 -> i;
                default -> i + 1;
            };
        }

        /**
         * Vanilla's smooth-lighting occlusion for one vertex: each solid block beside the face's
         * front block darkens it, and two solid sides hide the corner entirely.
         */
        static float occlusion(boolean side1, boolean side2, boolean corner) {
            if (side1 && side2) return 0.4F;
            return 1 - 0.2F * ((side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0));
        }

        /** Quarter turns of a top texture at a block, from a position hash. */
        private static int turn(int x, int z) {
            int hash = x * 0x2F0B3A49 ^ z * 0x61C88647;
            hash ^= hash >>> 16;
            return hash * 0x45D9F3B >>> 30;
        }

        private static int scale(int argb, float brightness) {
            int r = Math.round((argb >> 16 & 255) * brightness), g = Math.round((argb >> 8 & 255) * brightness);
            int b = Math.round((argb & 255) * brightness);
            return 0xFF000000 | r << 16 | g << 8 | b;
        }
    }
}
