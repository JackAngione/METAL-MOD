package dev.metalcraft.client.lod;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Cell samples for one quadtree node: 32×32 cells plus a one-cell border taken from the
 * neighbouring nodes at the same level, so walls along node edges face real neighbours.
 * Textured samples also carry each cell's {@link LodSurface}.
 */
final class LodTile {
    static final int CELLS = 32;
    static final int STRIDE = CELLS + 2;
    private static final BlockState SNOW = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final BlockState ICE = Blocks.ICE.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final BlockState SNOWY_GRASS = Blocks.GRASS_BLOCK.defaultBlockState()
        .setValue(net.minecraft.world.level.block.SnowyBlock.SNOWY, true);

    final long[] cells = new long[STRIDE * STRIDE];
    /** Present for textured samples only. */
    long @Nullable [] surfaces;
    /** World block position of cell (0, 0)'s corner. */
    int originX, originZ;
    int @Nullable [] fluidColors;
    /** Chunks this tile generated rather than read; empty unless requested by the sampler call. */
    long[] generatedChunks = new long[0];
    int minY = Integer.MAX_VALUE;
    int maxY = Integer.MIN_VALUE;

    static int index(int i, int j) { return i + 1 + (j + 1) * STRIDE; }

    long cell(int i, int j) { return this.cells[index(i, j)]; }

    int fluidColor(int i, int j) { return this.fluidColors == null ? 0 : this.fluidColors[index(i, j)]; }

    long surface(int i, int j) { return this.surfaces == null ? 0 : this.surfaces[index(i, j)]; }

    /** Samples node cells: captured real chunks where available, generated terrain elsewhere. */
    static final class Sampler {
        private final LodGenerator generator;
        private final Map<Long, LodChunk> real;
        private final LodBiomes biomes;
        private final int worldMinY;

        Sampler(LodGenerator generator, Map<Long, LodChunk> real, LodBiomes biomes, int worldMinY) {
            this.generator = generator;
            this.real = real;
            this.biomes = biomes;
            this.worldMinY = worldMinY;
        }

        /**
         * @param trackGenerated record which chunks had no real data, so saved copies can be looked up
         * @param textured also record what each cell's textured faces show
         */
        LodTile sample(int level, int nodeX, int nodeZ, LodBlockColors colors, boolean trackGenerated, boolean textured) {
            int cell = 1 << level;
            int originX = nodeX * CELLS * cell, originZ = nodeZ * CELLS * cell;
            int offset = cell / 2;
            int corner = this.generator.cellWidth();
            LodTile tile = new LodTile();
            tile.originX = originX;
            tile.originZ = originZ;
            int count = STRIDE * STRIDE;
            if (textured) tile.surfaces = new long[count];
            // Generated columns are finished in a second pass, once neighbouring heights are known.
            int[] ground = new int[count];
            @SuppressWarnings("unchecked") Holder<Biome>[] biomes = new Holder[count];
            int[] sampleX = new int[count], sampleZ = new int[count];
            this.generator.beginTile();
            it.unimi.dsi.fastutil.longs.LongOpenHashSet generated = trackGenerated ? new it.unimi.dsi.fastutil.longs.LongOpenHashSet() : null;
            int guess = LodGenerator.VOID;
            for (int j = -1; j <= CELLS; j++) {
                for (int i = -1; i <= CELLS; i++) {
                    int index = index(i, j);
                    int x = originX + i * cell + offset, z = originZ + j * cell + offset;
                    LodChunk chunk = this.real.get(ChunkPos.pack(x >> 4, z >> 4));
                    if (chunk != null) {
                        tile.cells[index] = chunk.cell(x, z);
                        if (tile.surfaces != null) tile.surfaces[index] = chunk.surface(x, z);
                        if (LodCell.hasFluid(tile.cells[index])) tile.fluid(index, chunk.fluidColor(x, z));
                        ground[index] = Integer.MIN_VALUE;
                        continue;
                    }
                    if (generated != null) generated.add(ChunkPos.pack(x >> 4, z >> 4));
                    if (cell >= corner) {
                        x = Math.floorDiv(x, corner) * corner;
                        z = Math.floorDiv(z, corner) * corner;
                    }
                    int top = this.generator.surface(x, z, guess);
                    guess = top;
                    ground[index] = top;
                    sampleX[index] = x;
                    sampleZ[index] = z;
                    if (top != LodGenerator.VOID) {
                        int fluidTop = this.generator.fluid != null && top < this.generator.seaLevel ? this.generator.seaLevel : top;
                        biomes[index] = this.generator.biome(x, fluidTop - 1, z);
                    }
                }
                guess = LodGenerator.VOID;
            }
            var pos = new BlockPos.MutableBlockPos();
            for (int j = -1; j <= CELLS; j++) {
                for (int i = -1; i <= CELLS; i++) {
                    int index = index(i, j);
                    int top = ground[index];
                    if (top == Integer.MIN_VALUE || top == LodGenerator.VOID) continue;
                    tile.cells[index] = this.generated(tile, index, i, j, top, ground, biomes[index], sampleX[index], sampleZ[index], cell, colors, pos);
                }
            }
            if (generated != null) tile.generatedChunks = generated.toLongArray();
            for (int j = 0; j < CELLS; j++) for (int i = 0; i < CELLS; i++) {
                long value = tile.cells[index(i, j)];
                if (LodCell.hasTerrain(value)) {
                    tile.minY = Math.min(tile.minY, LodCell.bottom(value) == this.worldMinY ? LodCell.top(value) : LodCell.bottom(value));
                    tile.maxY = Math.max(tile.maxY, LodCell.top(value));
                }
                if (LodCell.hasFluid(value)) {
                    tile.minY = Math.min(tile.minY, LodCell.fluidTop(value));
                    tile.maxY = Math.max(tile.maxY, LodCell.fluidTop(value));
                }
            }
            return tile;
        }

        private long generated(LodTile tile, int index, int i, int j, int top, int[] ground, Holder<Biome> biome,
                               int x, int z, int cell, LodBlockColors colors, BlockPos.MutableBlockPos pos) {
            Biome value = biome.value();
            LodSurfacePalette.Surface surface = LodSurfacePalette.surface(biome);
            int sea = this.generator.seaLevel;
            int bottom = this.generator.bottom(x, z, top);
            if (this.generator.fluid != null && top < sea) {
                if (this.generator.fluid.is(Blocks.WATER) && value.coldEnoughToSnow(pos.set(x, sea - 1, z), sea)) {
                    this.surface(tile, index, ICE, ICE, value);
                    return LodCell.pack(sea, bottom, this.worldMinY, colors.color(ICE, value, x, z), 0);
                }
                int rgb = colors.color(surface.floor(), value, x, z);
                tile.fluid(index, colors.color(this.generator.fluid.is(Blocks.WATER) ? WATER : this.generator.fluid, value, x, z));
                this.surface(tile, index, surface.floor(), surface.floor(), value);
                return LodCell.pack(top, bottom, sea, rgb, 0);
            }
            BlockState shown = surface.top();
            boolean snowy = value.hasPrecipitation() && value.coldEnoughToSnow(pos.set(x, top, z), sea);
            if (snowy) shown = SNOW;
            if (steep(ground, i, j, cell) && erodes(shown)) shown = STONE;
            // Snow over soil is a snowy grass block; over rock (peaks, slopes) it stays snow.
            BlockState side = shown == SNOW && surface.floor() != STONE ? SNOWY_GRASS : shown;
            int rgb = colors.color(shown, value, x, z);
            BlockState leaves = surface.leaves();
            if (leaves != null && surface.treeCover() > 0 && !(shown == STONE)) {
                int foliage = colors.color(leaves, value, x, z);
                if (snowy) foliage = blend(foliage, colors.color(SNOW, value, x, z), 0.5F);
                if (cell >= 8) {
                    // A coarse cell spans many trees: raise it by the average canopy and blend its colour.
                    top += Math.round(surface.treeCover() * surface.canopyHeight());
                    rgb = blend(rgb, foliage, surface.treeCover());
                } else if (canopy(Math.floorDiv(x, 4), Math.floorDiv(z, 4)) < surface.treeCover()) {
                    top += surface.canopyHeight();
                    rgb = foliage;
                    shown = side = leaves;
                }
            }
            this.surface(tile, index, shown, side, value);
            return LodCell.pack(top, bottom, this.worldMinY, rgb & 0xFFFFFF, 0);
        }

        private void surface(LodTile tile, int index, BlockState top, BlockState side, Biome biome) {
            if (tile.surfaces != null) tile.surfaces[index] = LodSurface.pack(Block.getId(top), Block.getId(side), this.biomes.id(biome));
        }

        /** Vanilla's surface rules expose rock where the ground rises two blocks per block. */
        private static boolean steep(int[] ground, int i, int j, int cell) {
            int west = height(ground, i - 1, j), east = height(ground, i + 1, j);
            int north = height(ground, i, j - 1), south = height(ground, i, j + 1);
            int span = 2 * cell * 2;
            return west != Integer.MIN_VALUE && east != Integer.MIN_VALUE && Math.abs(east - west) >= span
                || north != Integer.MIN_VALUE && south != Integer.MIN_VALUE && Math.abs(south - north) >= span;
        }

        private static int height(int[] ground, int i, int j) {
            if (i < -1 || j < -1 || i > CELLS || j > CELLS) return Integer.MIN_VALUE;
            int value = ground[index(i, j)];
            return value == LodGenerator.VOID ? Integer.MIN_VALUE : value;
        }

        private static boolean erodes(BlockState state) {
            return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.GRAVEL) || state.is(Blocks.MUD) || state.is(Blocks.MOSS_BLOCK);
        }

        private static float canopy(int x, int z) {
            int hash = x * 0x27d4eb2d ^ z * 0x165667b1;
            hash ^= hash >>> 15;
            hash *= 0x2c1b3c6d;
            hash ^= hash >>> 12;
            return (hash & 0xFFFF) / 65536.0F;
        }

        private static int blend(int a, int b, float t) {
            int r = Math.round((a >> 16 & 255) + ((b >> 16 & 255) - (a >> 16 & 255)) * t);
            int g = Math.round((a >> 8 & 255) + ((b >> 8 & 255) - (a >> 8 & 255)) * t);
            int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
            return 0xFF000000 | r << 16 | g << 8 | bl;
        }
    }

    private void fluid(int index, int argb) {
        if (this.fluidColors == null) this.fluidColors = new int[STRIDE * STRIDE];
        this.fluidColors[index] = argb;
    }
}
