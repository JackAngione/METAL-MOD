package dev.metalcraft.client.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;

/**
 * Full-resolution surface of one real (loaded or saved) chunk: 16×16 packed cells.
 *
 * <p>Immutable once built, so worker threads may read it while the client thread replaces it.
 * {@code minSurface}/{@code maxSurface} bound the surface blocks, which is where native rendering
 * must be compiled before it can take over from the distant model.
 */
record LodChunk(long[] cells, int @Nullable [] fluidColors, int minSurface, int maxSurface) {
    /** Column access shared by live chunks and saved chunk data; {@code x}/{@code z} are chunk-local. */
    interface Source {
        /** Exclusive top of blocks that block motion or hold fluid. */
        int motionTop(int x, int z);

        /** Exclusive top of any non-air block. */
        int surfaceTop(int x, int z);

        BlockState state(int x, int y, int z);

        Holder<Biome> biome(int x, int y, int z);
    }

    long cell(int x, int z) { return this.cells[(x & 15) | (z & 15) << 4]; }

    int fluidColor(int x, int z) { return this.fluidColors == null ? 0 : this.fluidColors[(x & 15) | (z & 15) << 4]; }

    /** Client thread: reads a loaded chunk through its client heightmaps. */
    static LodChunk capture(LevelChunk chunk, LodBlockColors colors, boolean floating) {
        var pos = new BlockPos.MutableBlockPos();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        return read(new Source() {
            @Override public int motionTop(int x, int z) { return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1; }
            @Override public int surfaceTop(int x, int z) { return chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1; }
            @Override public BlockState state(int x, int y, int z) { return chunk.getBlockState(pos.set(baseX + x, y, baseZ + z)); }
            @Override public Holder<Biome> biome(int x, int y, int z) {
                return chunk.getNoiseBiome(QuartPos.fromBlock(baseX + x), QuartPos.fromBlock(y), QuartPos.fromBlock(baseZ + z));
            }
        }, baseX, baseZ, chunk.getMinY(), floating, colors);
    }

    static LodChunk read(Source source, int baseX, int baseZ, int minY, boolean floating, LodBlockColors colors) {
        long[] cells = new long[256];
        int[] fluids = null;
        int minSurface = Integer.MAX_VALUE, maxSurface = Integer.MIN_VALUE;
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int top = source.motionTop(x, z);
            if (top <= minY) continue;
            BlockState state = source.state(x, top - 1, z);
            int fluidTop = minY;
            int fluidColor = 0;
            FluidState fluid = state.getFluidState();
            if (fluid.is(Fluids.WATER) || fluid.is(Fluids.FLOWING_WATER)) {
                // Look through water and water plants to the floor; the water keeps its own colour.
                fluidTop = top;
                Holder<Biome> biome = source.biome(x, top - 1, z);
                fluidColor = colors.color(Blocks.WATER.defaultBlockState(), biome.value(), baseX + x, baseZ + z);
                int y = top - 1;
                while (y > minY && isWaterBody(state)) state = source.state(x, --y, z);
                top = isWaterBody(state) ? minY : y + 1;
            }
            int surfaceTop = source.surfaceTop(x, z);
            // A single covering block (snow layer, carpet, short plants) colours the ground under it.
            BlockState shown = fluidTop == minY && surfaceTop == top + 1 ? source.state(x, top, z) : state;
            if (shown.isAir()) shown = state;
            int bottom = minY;
            if (floating && top > minY) {
                int y = top - 1;
                while (y > minY && !source.state(x, y - 1, z).isAir()) y--;
                bottom = y;
            }
            int rgb = 0;
            int light = 0;
            if (top > minY) {
                Holder<Biome> biome = source.biome(x, top - 1, z);
                rgb = colors.color(shown, biome.value(), baseX + x, baseZ + z) & 0xFFFFFF;
                light = Math.max(colors.light(shown), colors.light(state));
            }
            if (top <= minY && fluidTop == minY) continue;
            int index = x | z << 4;
            cells[index] = LodCell.pack(Math.max(top, bottom), bottom, fluidTop, rgb, light);
            if (fluidTop > top) {
                if (fluids == null) fluids = new int[256];
                fluids[index] = fluidColor;
            }
            int surface = Math.max(top, fluidTop) - 1;
            minSurface = Math.min(minSurface, surface);
            maxSurface = Math.max(maxSurface, surface);
        }
        if (minSurface > maxSurface) minSurface = maxSurface = minY;
        return new LodChunk(cells, fluids, minSurface, maxSurface);
    }

    private static boolean isWaterBody(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) return true;
        return !state.getFluidState().isEmpty() && !state.blocksMotion();
    }
}
