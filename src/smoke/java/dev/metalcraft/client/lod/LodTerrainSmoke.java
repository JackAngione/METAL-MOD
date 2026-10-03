package dev.metalcraft.client.lod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Headless checks for distant terrain: cell packing, surface parity with vanilla's noise
 * generator, mesh coverage and layout, and sampling throughput (reported, never a gate).
 */
public final class LodTerrainSmoke {
    private static final long SEED = 7_314_159L;
    private static final LevelHeightAccessor OVERWORLD = LevelHeightAccessor.create(-64, 384);

    private LodTerrainSmoke() { }

    public static void main(String[] args) throws Exception {
        packing();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = VanillaRegistries.createLookup();
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var preset = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD);
        var chunkGenerator = new NoiseBasedChunkGenerator(MultiNoiseBiomeSource.createFromPreset(preset), settings);
        var random = RandomState.create(registries, NoiseGeneratorSettings.OVERWORLD, SEED);
        var generator = new LodGenerator(chunkGenerator, random, OVERWORLD, false);
        parity(generator, chunkGenerator, random);
        meshing(generator);
        if (args.length == 0 || !args[0].equals("quick")) throughput(generator);
        System.out.println("Distant terrain smoke passed");
    }

    private static void packing() {
        int[][] cases = {{-64, -64, -64, 0x123456, 0}, {320, -64, 330, 0xFFFFFF, 15}, {-2048, -2048, -2048, 0, 7}, {2047, 100, -5, 0xABCDEF, 3}};
        for (int[] c : cases) {
            long cell = LodCell.pack(c[0], c[1], c[2], c[3], c[4]);
            check(LodCell.top(cell) == c[0] && LodCell.bottom(cell) == c[1] && LodCell.fluidTop(cell) == c[2]
                && LodCell.rgb(cell) == c[3] && LodCell.blockLight(cell) == c[4], "cell round trip " + java.util.Arrays.toString(c));
        }
        check(!LodCell.hasTerrain(LodCell.EMPTY) && !LodCell.hasFluid(LodCell.EMPTY), "empty cell");
        check(LodCell.hasFluid(LodCell.pack(40, -64, 63, 0, 0)) && !LodCell.hasFluid(LodCell.pack(70, -64, 63, 0, 0)), "fluid above terrain only");
        System.out.println("Cell packing passed");
    }

    /** Corner and interpolated columns against vanilla's own noise-only surface for the same seed. */
    private static void parity(LodGenerator generator, NoiseBasedChunkGenerator vanilla, RandomState random) {
        Random rng = new Random(42);
        for (boolean corner : new boolean[]{true, false}) {
            int samples = corner ? 2000 : 600, exact = 0, near = 0, worst = 0;
            for (int sample = 0; sample < samples; sample++) {
                int x = rng.nextInt(40_000) - 20_000, z = rng.nextInt(40_000) - 20_000;
                if (corner) {
                    x &= ~3;
                    z &= ~3;
                } else if ((x & 3) == 0 && (z & 3) == 0) {
                    x++;
                }
                generator.beginTile();
                int ours = generator.surface(x, z, LodGenerator.VOID);
                int expected = vanilla.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, OVERWORLD, random);
                int difference = Math.abs(ours - expected);
                if (difference == 0) exact++;
                if (difference <= 2) near++;
                worst = Math.max(worst, difference);
            }
            double exactShare = exact / (double)samples, nearShare = near / (double)samples;
            System.out.printf("Surface parity (%s columns): exact %.1f%%, within 2 blocks %.1f%%, worst %d%n",
                corner ? "corner" : "interpolated", exactShare * 100, nearShare * 100, worst);
            // Noodle caves, cave openings and overhangs are outside the interpolated model.
            check(nearShare >= 0.97, "surface parity within 2 blocks for " + (corner ? "corner" : "interpolated") + " columns");
        }
    }

    private static void meshing(LodGenerator generator) {
        Map<Long, LodChunk> real = new ConcurrentHashMap<>();
        LodBiomes biomes = new LodBiomes();
        var sampler = new LodTile.Sampler(generator, real, biomes, OVERWORLD.getMinY());
        LodBlockColors colors = LodBlockColors.uniform(0xFF7FB238);
        LodMesher mesher = new LodMesher(new float[]{1, 0.5F, 0.8F, 0.8F, 0.6F, 0.6F}, LodAtlas.SIZE, LodAtlas.SIZE,
            LodAtlas.WHITE_UV, LodAtlas.WHITE_UV, OVERWORLD.getMinY());
        for (int level = 0; level <= 6; level += 2) {
            LodTile tile = sampler.sample(level, 3, -5, colors, false, false);
            for (boolean split : new boolean[]{false, true}) {
                if (split && level > LodSession.SPLIT_LEVEL) continue;
                try (LodMesh mesh = mesher.mesh(tile, level, split, 32, 64)) {
                    verify(mesh, tile, level, split);
                    if (!split) System.out.printf("Level %d mesh: %d solid + %d fluid quads for %d cells (%.2f quads/cell)%n", level,
                        mesh.solidQuads, mesh.fluidQuads, LodTile.CELLS * LodTile.CELLS,
                        (mesh.solidQuads + mesh.fluidQuads) / (double)(LodTile.CELLS * LodTile.CELLS));
                }
            }
        }
        // Textured level-0 nodes: the same coverage, one top per cell, sprite UVs, per-chunk groups.
        for (int[] node : new int[][]{{3, -5}, {-40, 22}, {17, 61}}) {
            LodTile tile = sampler.sample(0, node[0], node[1], colors, false, true);
            try (LodMesh mesh = mesher.meshTextured(tile, colors, biomes)) {
                verify(mesh, tile, 0, true);
                check(mesh.colors() == null, "textured mesh has no colour block");
                int terrain = 0;
                for (int j = 0; j < LodTile.CELLS; j++) for (int i = 0; i < LodTile.CELLS; i++) {
                    if (LodCell.hasTerrain(tile.cell(i, j))) {
                        terrain++;
                        check(tile.surface(i, j) != 0, "textured cell has a surface at " + i + "," + j);
                    }
                }
                texturedCells(mesh, terrain);
                try (LodMesh flat = mesher.mesh(tile, 0, true, 32, 64)) {
                    System.out.printf("Level 0 textured mesh %d,%d: %d solid quads (%.2f quads/cell) vs %d flat%n", node[0], node[1],
                        mesh.solidQuads, mesh.solidQuads / (double)(LodTile.CELLS * LodTile.CELLS), flat.solidQuads);
                }
            }
        }
        // A flat node merges to a single top: colour lives in the atlas, not in vertices.
        LodTile flat = new LodTile();
        java.util.Arrays.fill(flat.cells, LodCell.pack(70, -64, -64, 0x7FB238, 0));
        try (LodMesh mesh = mesher.mesh(flat, 3, false, 0, 0)) {
            check(mesh.solidQuads == 1 + 4 * 1, "flat node is one top plus one skirt run per edge, was " + mesh.solidQuads);
        }
        System.out.println("Mesh coverage and layout passed");
    }

    /** Textured tops are one quad per terrain cell; every solid UV lies inside a sprite (0..1 for uniform colours). */
    private static void texturedCells(LodMesh mesh, int terrainCells) {
        var buffer = mesh.vertices().duplicate().order(java.nio.ByteOrder.nativeOrder());
        int tops = 0;
        for (int quad = 0; quad < mesh.solidQuads; quad++) {
            float[] y = new float[4];
            float[][] p = new float[4][];
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = (quad * 4 + vertex) * LodMesh.VERTEX_BYTES;
                p[vertex] = new float[]{buffer.getFloat(base), buffer.getFloat(base + 4), buffer.getFloat(base + 8)};
                y[vertex] = p[vertex][1];
                float u = buffer.getFloat(base + 16), v = buffer.getFloat(base + 20);
                check(u >= 0 && u <= 1 && v >= 0 && v <= 1, "textured UV inside the sprite");
                check((buffer.get(base + 15) & 255) == 255, "opaque vertex colour");
            }
            if (y[0] == y[1] && y[1] == y[2] && y[2] == y[3] && upward(p)) {
                float width = Math.abs(p[2][0] - p[0][0]), depth = Math.abs(p[2][2] - p[0][2]);
                check(width == 1 && depth == 1, "textured top spans one cell");
                tops++;
            }
        }
        check(tops == terrainCells, "one textured top per terrain cell: " + tops + " vs " + terrainCells);
    }

    /** Counter-clockwise from above, as the upward faces of both meshers wind. */
    private static boolean upward(float[][] p) {
        float ax = p[1][0] - p[0][0], az = p[1][2] - p[0][2], bx = p[2][0] - p[0][0], bz = p[2][2] - p[0][2];
        return az * bx - ax * bz > 0;
    }

    /** Tops cover each terrain cell exactly once; groups tile the quad list; vertices are well formed. */
    private static void verify(LodMesh mesh, LodTile tile, int level, boolean split) {
        int cell = 1 << level;
        var buffer = mesh.vertices().duplicate().order(java.nio.ByteOrder.nativeOrder());
        check(buffer.remaining() == (mesh.solidQuads + mesh.fluidQuads) * 4 * LodMesh.VERTEX_BYTES, "vertex byte count");
        int[] coverage = new int[LodTile.CELLS * LodTile.CELLS];
        int[] fluidCoverage = new int[LodTile.CELLS * LodTile.CELLS];
        for (int quad = 0; quad < mesh.solidQuads + mesh.fluidQuads; quad++) {
            float[] x = new float[4], y = new float[4], z = new float[4];
            for (int vertex = 0; vertex < 4; vertex++) {
                int base = (quad * 4 + vertex) * LodMesh.VERTEX_BYTES;
                x[vertex] = buffer.getFloat(base);
                y[vertex] = buffer.getFloat(base + 4);
                z[vertex] = buffer.getFloat(base + 8);
                check(Float.isFinite(x[vertex]) && Float.isFinite(y[vertex]) && Float.isFinite(z[vertex]), "finite vertex");
                check(buffer.getShort(base + 26) == 240, "full sky light");
            }
            boolean horizontal = y[0] == y[1] && y[1] == y[2] && y[2] == y[3];
            if (!horizontal) continue;
            // Upward faces wind (x0,z0),(x0,z1),(x1,z1),(x1,z0), possibly starting at another corner; downward faces reverse it.
            if (!upward(new float[][]{{x[0], y[0], z[0]}, {x[1], y[1], z[1]}, {x[2], y[2], z[2]}})) continue;
            int i0 = Math.round(Math.min(x[0], x[2]) / cell), i1 = Math.round(Math.max(x[0], x[2]) / cell);
            int j0 = Math.round(Math.min(z[0], z[2]) / cell), j1 = Math.round(Math.max(z[0], z[2]) / cell);
            for (int j = j0; j < j1; j++) for (int i = i0; i < i1; i++) {
                if (quad < mesh.solidQuads) coverage[i + j * LodTile.CELLS]++;
                else fluidCoverage[i + j * LodTile.CELLS]++;
            }
        }
        for (int j = 0; j < LodTile.CELLS; j++) for (int i = 0; i < LodTile.CELLS; i++) {
            long value = tile.cell(i, j);
            check(coverage[i + j * LodTile.CELLS] == (LodCell.hasTerrain(value) ? 1 : 0), "top coverage at " + i + "," + j);
            check(fluidCoverage[i + j * LodTile.CELLS] == (LodCell.hasFluid(value) ? 1 : 0), "fluid coverage at " + i + "," + j);
        }
        int groups = mesh.groupsPerSide * mesh.groupsPerSide;
        check(mesh.groupsPerSide == (split ? LodTile.CELLS * cell / 16 : 1), "group count");
        int solid = 0, fluid = 0;
        for (int group = 0; group < groups; group++) {
            check(mesh.solidRanges[2 * group] == solid && mesh.fluidRanges[2 * group] == fluid, "contiguous groups");
            solid += mesh.solidRanges[2 * group + 1];
            fluid += mesh.fluidRanges[2 * group + 1];
        }
        check(solid == mesh.solidQuads && fluid == mesh.fluidQuads, "groups cover every quad");
    }

    private static void throughput(LodGenerator generator) throws Exception {
        Map<Long, LodChunk> real = new ConcurrentHashMap<>();
        LodBiomes biomes = new LodBiomes();
        var sampler = new LodTile.Sampler(generator, real, biomes, OVERWORLD.getMinY());
        LodBlockColors colors = LodBlockColors.uniform(0xFF7FB238);
        LodMesher mesher = new LodMesher(new float[]{1, 0.5F, 0.8F, 0.8F, 0.6F, 0.6F}, LodAtlas.SIZE, LodAtlas.SIZE,
            LodAtlas.WHITE_UV, LodAtlas.WHITE_UV, OVERWORLD.getMinY());
        for (int warm = 0; warm < 20; warm++) sampler.sample(4, warm, 7, colors, false, false);
        for (int level : new int[]{0, 2, 4, 6}) {
            int tiles = 24;
            long started = System.nanoTime(), meshNanos = 0;
            for (int tile = 0; tile < tiles; tile++) {
                LodTile sampled = sampler.sample(level, tile * 3 - 30, tile * 5 - 40, colors, false, false);
                long meshStart = System.nanoTime();
                try (LodMesh ignored = mesher.mesh(sampled, level, level <= LodSession.SPLIT_LEVEL, 32, 32)) {
                    meshNanos += System.nanoTime() - meshStart;
                }
            }
            double millis = (System.nanoTime() - started) / 1e6 / tiles;
            System.out.printf("Level %d: %.2f ms per node on one thread (%.1f µs per column, mesh %.2f ms)%n", level, millis,
                millis * 1000 / (LodTile.STRIDE * LodTile.STRIDE), meshNanos / 1e6 / tiles);
        }
        {
            int tiles = 24;
            long started = System.nanoTime(), meshNanos = 0;
            for (int tile = 0; tile < tiles; tile++) {
                LodTile sampled = sampler.sample(0, tile * 3 - 30, tile * 5 - 40, colors, false, true);
                long meshStart = System.nanoTime();
                try (LodMesh ignored = mesher.meshTextured(sampled, colors, biomes)) {
                    meshNanos += System.nanoTime() - meshStart;
                }
            }
            System.out.printf("Level 0 textured: %.2f ms per node on one thread (mesh %.2f ms)%n",
                (System.nanoTime() - started) / 1e6 / tiles, meshNanos / 1e6 / tiles);
        }
        int threads = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 8);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            int tiles = 96;
            long started = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (int tile = 0; tile < tiles; tile++) {
                int index = tile;
                futures.add(pool.submit(() -> {
                    try (LodMesh ignored = mesher.mesh(sampler.sample(4, index % 12 - 60, index / 12 + 50, colors, false, false), 4, false, 32, 32)) { }
                }));
            }
            for (Future<?> future : futures) future.get();
            double seconds = (System.nanoTime() - started) / 1e9;
            System.out.printf("%d threads: %.0f level-4 nodes per second%n", threads, tiles / seconds);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
