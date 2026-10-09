package dev.metalcraft.client.lod;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import org.jspecify.annotations.Nullable;

/**
 * Terrain shape and biome of never-generated columns, read straight from the world generator.
 *
 * <p>Vanilla fills a chunk by evaluating its density function at cell corners (every 4 blocks
 * across, 8 up in the Overworld) and interpolating between them; a block is solid where the
 * interpolated density is positive. At a corner column the vertical profile is therefore linear
 * between corner heights, so the surface can be located exactly from a handful of corner
 * evaluations instead of generating the chunk. Columns between corners interpolate the four
 * surrounding profiles the same way vanilla does. Structures, carvers, aquifers and features are
 * not modelled; the surface palette stands in for their appearance.
 *
 * <p>Instances are immutable and shared by worker threads. Each thread evaluates its own copy of
 * the density graph, whose 2D caches hold the last column.
 */
final class LodGenerator {
    static final int VOID = Integer.MIN_VALUE;

    private final ChunkGenerator generator;
    private final RandomState random;
    private final BiomeSource biomes;
    private final Climate.Sampler climate;
    private final LevelHeightAccessor heights;
    final int seaLevel;
    final @Nullable BlockState fluid;
    final boolean floating;
    private final boolean noise;
    private final int cellWidth;
    private final int cellHeight;
    private final int bottomCorner;
    private final int topCorner;
    private final DensityFunction density;
    private final @Nullable DensityFunction preliminary;
    private final ThreadLocal<Sampler> samplers = ThreadLocal.withInitial(() -> new Sampler(this));

    LodGenerator(ChunkGenerator generator, RandomState random, LevelHeightAccessor heights, boolean floating) {
        this.generator = generator;
        this.random = random;
        this.biomes = generator.getBiomeSource();
        this.climate = random.sampler();
        this.heights = LevelHeightAccessor.create(heights.getMinY(), heights.getHeight());
        this.seaLevel = generator.getSeaLevel();
        this.floating = floating;
        if (generator instanceof NoiseBasedChunkGenerator noiseGenerator) {
            var settings = noiseGenerator.generatorSettings().value();
            var shape = settings.noiseSettings().clampToHeightAccessor(this.heights);
            this.noise = shape.height() > 0;
            this.cellWidth = shape.getCellWidth();
            this.cellHeight = shape.getCellHeight();
            this.bottomCorner = shape.minY();
            this.topCorner = shape.minY() + Math.floorDiv(shape.height(), this.cellHeight) * this.cellHeight;
            this.density = random.router().finalDensity();
            DensityFunction estimate = random.router().preliminarySurfaceLevel();
            this.preliminary = estimate.minValue() < estimate.maxValue() ? estimate : null;
            this.fluid = settings.defaultFluid().isAir() ? null : settings.defaultFluid();
        } else {
            this.noise = false;
            this.cellWidth = 4;
            this.cellHeight = 8;
            this.bottomCorner = heights.getMinY();
            this.topCorner = heights.getMaxY();
            this.density = DensityFunctions.zero();
            this.preliminary = null;
            this.fluid = null;
        }
    }

    static LodGenerator create(ServerLevel level) {
        var source = level.getChunkSource();
        return new LodGenerator(source.getGenerator(), source.randomState(), level, level.dimension() == Level.END);
    }

    int cellWidth() { return this.cellWidth; }

    Holder<Biome> biome(int x, int y, int z) {
        return this.biomes.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), this.climateSampler());
    }

    Climate.Sampler climateSampler() { return this.samplers.get().climate; }

    /** Clears the calling thread's interpolation cache; call once per tile. */
    void beginTile() {
        Sampler sampler = this.samplers.get();
        sampler.profiles.clear();
        sampler.tops.clear();
    }

    /**
     * Exclusive top of the terrain at a block column, or {@link #VOID}. {@code guess} is a
     * nearby known surface height, or {@link #VOID}.
     */
    int surface(int x, int z, int guess) {
        if (!this.noise) {
            int top = this.generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, this.heights, this.random);
            return top <= this.heights.getMinY() ? VOID : top;
        }
        Sampler sampler = this.samplers.get();
        if (Math.floorMod(x, this.cellWidth) == 0 && Math.floorMod(z, this.cellWidth) == 0) return this.cornerSurface(sampler, x, z, guess);
        return this.interpolatedSurface(sampler, x, z, guess);
    }

    /** Exclusive bottom of the top solid run for floating terrain; the world bottom otherwise. */
    int bottom(int x, int z, int top) {
        if (!this.floating || !this.noise || top == VOID) return this.heights.getMinY();
        Sampler sampler = this.samplers.get();
        int cx = Math.floorDiv(x, this.cellWidth) * this.cellWidth, cz = Math.floorDiv(z, this.cellWidth) * this.cellWidth;
        int y = Math.floorDiv(top - 1 - this.bottomCorner, this.cellHeight) * this.cellHeight + this.bottomCorner;
        double upper = sampler.density(cx, y, cz);
        if (upper <= 0) return top - 1;
        while (y > this.bottomCorner) {
            int below = y - this.cellHeight;
            double lower = sampler.density(cx, below, cz);
            if (lower <= 0) {
                // Lowest solid block above the zero crossing between `below` (air) and `y` (solid).
                double t = upper / (upper - lower) * this.cellHeight;
                return Math.clamp(y - (int)Math.ceil(t) + 1, below + 1, y);
            }
            y = below;
            upper = lower;
        }
        return this.heights.getMinY();
    }

    private int start(Sampler sampler, int x, int z, int guess) {
        int estimate = guess;
        if (this.preliminary != null) {
            int preliminary = (int)sampler.preliminary.compute(new DensityFunction.SinglePointContext(x, 0, z));
            estimate = estimate == VOID ? preliminary : Math.max(estimate, preliminary);
        }
        if (estimate == VOID) return this.topCorner;
        // Jagged peaks and overhangs rise above the smooth estimate; start three cells higher.
        int start = Math.floorDiv(estimate + 3 * this.cellHeight - this.bottomCorner, this.cellHeight) * this.cellHeight + this.bottomCorner;
        return Math.clamp(start, this.bottomCorner, this.topCorner);
    }

    private int cornerSurface(Sampler sampler, int x, int z, int guess) {
        int y = this.start(sampler, x, z, guess);
        double at = sampler.density(x, y, z);
        if (at > 0) {
            while (y < this.topCorner) {
                int above = y + this.cellHeight;
                double next = sampler.density(x, above, z);
                if (next <= 0) return crossing(y, at, above, next);
                y = above;
                at = next;
            }
            return this.topCorner;
        }
        // Without an estimate, step two cells at a time through open air and refine on contact.
        int step = this.preliminary == null && guess == VOID ? 2 * this.cellHeight : this.cellHeight;
        while (y > this.bottomCorner) {
            int below = Math.max(this.bottomCorner, y - step);
            double next = sampler.density(x, below, z);
            if (next > 0) {
                if (y - below > this.cellHeight) {
                    int middle = below + this.cellHeight;
                    double mid = sampler.density(x, middle, z);
                    return mid > 0 ? crossing(middle, mid, y, at) : crossing(below, next, middle, mid);
                }
                return crossing(below, next, y, at);
            }
            y = below;
            at = next;
        }
        return VOID;
    }

    /** Vanilla's per-block test between corner heights: solid where the linear density is positive. */
    private static int crossing(int solidY, double solid, int airY, double air) {
        double t = solid / (solid - air) * (airY - solidY);
        return Math.clamp(solidY + (int)Math.ceil(t), solidY + 1, airY);
    }

    private int interpolatedSurface(Sampler sampler, int x, int z, int guess) {
        int x0 = Math.floorDiv(x, this.cellWidth) * this.cellWidth, z0 = Math.floorDiv(z, this.cellWidth) * this.cellWidth;
        int x1 = x0 + this.cellWidth, z1 = z0 + this.cellWidth;
        double fx = (double)(x - x0) / this.cellWidth, fz = (double)(z - z0) / this.cellWidth;
        int highest = VOID;
        for (int corner = 0; corner < 4; corner++) {
            int cx = (corner & 1) == 0 ? x0 : x1, cz = (corner & 2) == 0 ? z0 : z1;
            highest = Math.max(highest, sampler.cornerTop(this, cx, cz, guess));
        }
        if (highest == VOID) return VOID;
        double[] a = sampler.profile(this, x0, z0), b = sampler.profile(this, x1, z0);
        double[] c = sampler.profile(this, x0, z1), d = sampler.profile(this, x1, z1);
        int index = Math.min(this.cornerIndex(this.topCorner), Math.floorDiv(highest - this.bottomCorner, this.cellHeight) + 1);
        double above = Double.NaN;
        for (; index >= 0; index--) {
            int y = this.bottomCorner + index * this.cellHeight;
            double value = lerp(fz, lerp(fx, sampler.value(this, a, x0, z0, index), sampler.value(this, b, x1, z0, index)),
                lerp(fx, sampler.value(this, c, x0, z1, index), sampler.value(this, d, x1, z1, index)));
            if (value > 0) return Double.isNaN(above) ? Math.min(this.topCorner, y + this.cellHeight) : crossing(y, value, y + this.cellHeight, above);
            above = value;
        }
        return VOID;
    }

    private int cornerIndex(int y) { return (y - this.bottomCorner) / this.cellHeight; }

    private static double lerp(double t, double a, double b) { return a + (b - a) * t; }

    /**
     * Per-thread density graph. {@code flat_cache} and {@code cache_2d} markers become one-column
     * caches (their inputs do not depend on height); every other marker evaluates its input
     * directly, since a single corner is exactly what vanilla's interpolators sample.
     */
    // Worker threads outlive sessions. A cached value must not retain its generator and
    // therefore its own ThreadLocal key, or an old world's graphs can never be collected.
    private static final class Sampler {
        final DensityFunction density;
        final DensityFunction preliminary;
        final Climate.Sampler climate;
        final Long2ObjectOpenHashMap<double[]> profiles = new Long2ObjectOpenHashMap<>();
        final Long2ObjectOpenHashMap<int[]> tops = new Long2ObjectOpenHashMap<>();

        Sampler(LodGenerator owner) {
            Map<DensityFunction, DensityFunction> mapped = new HashMap<>();
            DensityFunction.Visitor visitor = new DensityFunction.Visitor() {
                @Override
                public DensityFunction apply(DensityFunction function) {
                    return mapped.computeIfAbsent(function, Sampler::wrap);
                }
            };
            this.density = owner.density.mapAll(visitor);
            this.preliminary = owner.preliminary == null ? DensityFunctions.zero() : owner.preliminary.mapAll(visitor);
            // RandomState's biome sampler strips cache markers. Map the router's
            // climate branches with the same visitor instead, so their 2D inputs
            // share this thread's column caches with the surface density graph.
            // Climate samples quart corners, preserving FlatCache's exact inputs.
            var router = owner.random.router();
            this.climate = new Climate.Sampler(router.temperature().mapAll(visitor), router.vegetation().mapAll(visitor),
                    router.continents().mapAll(visitor), router.erosion().mapAll(visitor), router.depth().mapAll(visitor),
                    router.ridges().mapAll(visitor), owner.climate.spawnTarget());
        }

        private static DensityFunction wrap(DensityFunction function) {
            if (function instanceof DensityFunctions.HolderHolder holder) return holder.function().value();
            if (function instanceof DensityFunctions.MarkerOrMarked marker) {
                // Marker.Type is package-private; its constant names are stable across the router format.
                return switch (String.valueOf(marker.type())) {
                    case "FlatCache" -> new ColumnCache(marker.wrapped(), true);
                    case "Cache2D" -> new ColumnCache(marker.wrapped(), false);
                    default -> marker.wrapped();
                };
            }
            return function;
        }

        double density(int x, int y, int z) {
            return this.density.compute(new DensityFunction.SinglePointContext(x, y, z));
        }

        double[] profile(LodGenerator owner, int x, int z) {
            long key = (long)x << 32 | (z & 0xFFFFFFFFL);
            double[] values = this.profiles.get(key);
            if (values == null) {
                values = new double[owner.cornerIndex(owner.topCorner) + 1];
                Arrays.fill(values, Double.NaN);
                this.profiles.put(key, values);
            }
            return values;
        }

        double value(LodGenerator owner, double[] profile, int x, int z, int index) {
            double value = profile[index];
            if (Double.isNaN(value)) profile[index] = value = this.density(x, owner.bottomCorner + index * owner.cellHeight, z);
            return value;
        }

        int cornerTop(LodGenerator owner, int x, int z, int guess) {
            long key = (long)x << 32 | (z & 0xFFFFFFFFL);
            int[] top = this.tops.get(key);
            if (top == null) {
                top = new int[]{owner.cornerSurface(this, x, z, guess)};
                this.tops.put(key, top);
            }
            return top[0];
        }
    }

    /** One-column cache for a height-independent input. */
    private static final class ColumnCache implements DensityFunction {
        private final DensityFunction input;
        private final boolean quart;
        private int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        private double last;

        ColumnCache(DensityFunction input, boolean quart) {
            this.input = input;
            this.quart = quart;
        }

        @Override
        public double compute(FunctionContext context) {
            int x = context.blockX(), z = context.blockZ();
            if (this.quart) {
                // Vanilla's flat cache samples quart corners at y = 0 and holds them across the quart.
                x = QuartPos.toBlock(QuartPos.fromBlock(x));
                z = QuartPos.toBlock(QuartPos.fromBlock(z));
            }
            if (x != this.lastX || z != this.lastZ) {
                this.last = this.input.compute(this.quart ? new SinglePointContext(x, 0, z) : context);
                this.lastX = x;
                this.lastZ = z;
            }
            return this.last;
        }

        @Override
        public void fillArray(double[] output, ContextProvider provider) { provider.fillAllDirectly(output, this); }

        @Override
        public DensityFunction mapChildren(Visitor visitor) { return new ColumnCache(visitor.apply(this.input), this.quart); }

        @Override public double minValue() { return this.input.minValue(); }
        @Override public double maxValue() { return this.input.maxValue(); }
        @Override public net.minecraft.util.KeyDispatchDataCodec<? extends DensityFunction> codec() { return this.input.codec(); }
    }
}
