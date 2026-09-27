package dev.metalcraft.client.lod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * What generated (never loaded) terrain looks like from a distance, per biome.
 *
 * <p>Generated columns know only the density surface and the biome, not surface rules or
 * features. This table stands in for both: the block that usually tops the biome, the floor
 * under its water, and how much of it a tree canopy covers. Unknown biomes fall back to grass.
 */
final class LodSurfacePalette {
    record Surface(BlockState top, BlockState floor, float treeCover, int canopyHeight, @Nullable BlockState leaves) { }

    private static final BlockState GRASS = Blocks.GRASS_BLOCK.defaultBlockState();
    private static final BlockState SAND = Blocks.SAND.defaultBlockState();
    private static final BlockState GRAVEL = Blocks.GRAVEL.defaultBlockState();
    private static final BlockState DIRT = Blocks.DIRT.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState SNOW = Blocks.SNOW_BLOCK.defaultBlockState();
    private static final Surface DEFAULT = new Surface(GRASS, SAND, 0.05F, 5, Blocks.OAK_LEAVES.defaultBlockState());
    private static final Map<String, Surface> VANILLA = Map.ofEntries(
        entry("plains", GRASS, SAND, 0.02F, 5, Blocks.OAK_LEAVES),
        entry("sunflower_plains", GRASS, SAND, 0.02F, 5, Blocks.OAK_LEAVES),
        entry("meadow", GRASS, SAND, 0.02F, 5, Blocks.OAK_LEAVES),
        entry("cherry_grove", GRASS, SAND, 0.35F, 6, Blocks.CHERRY_LEAVES),
        entry("forest", GRASS, SAND, 0.75F, 5, Blocks.OAK_LEAVES),
        entry("flower_forest", GRASS, SAND, 0.5F, 5, Blocks.OAK_LEAVES),
        entry("birch_forest", GRASS, SAND, 0.75F, 6, Blocks.BIRCH_LEAVES),
        entry("old_growth_birch_forest", GRASS, SAND, 0.75F, 8, Blocks.BIRCH_LEAVES),
        entry("dark_forest", GRASS, SAND, 0.95F, 6, Blocks.DARK_OAK_LEAVES),
        entry("pale_garden", GRASS, SAND, 0.9F, 6, Blocks.PALE_OAK_LEAVES),
        entry("taiga", GRASS, SAND, 0.6F, 7, Blocks.SPRUCE_LEAVES),
        entry("old_growth_pine_taiga", Blocks.PODZOL, SAND, 0.65F, 9, Blocks.SPRUCE_LEAVES),
        entry("old_growth_spruce_taiga", Blocks.PODZOL, SAND, 0.7F, 9, Blocks.SPRUCE_LEAVES),
        entry("snowy_taiga", SNOW, SAND, 0.5F, 7, Blocks.SPRUCE_LEAVES),
        entry("snowy_plains", SNOW, SAND, 0.02F, 6, Blocks.SPRUCE_LEAVES),
        entry("ice_spikes", SNOW, SAND, 0F, 0, null),
        entry("snowy_slopes", SNOW, STONE, 0F, 0, null),
        entry("grove", SNOW, STONE, 0.4F, 7, Blocks.SPRUCE_LEAVES),
        entry("frozen_peaks", SNOW, STONE, 0F, 0, null),
        entry("jagged_peaks", SNOW, STONE, 0F, 0, null),
        entry("stony_peaks", STONE, STONE, 0F, 0, null),
        entry("windswept_hills", GRASS, GRAVEL, 0.08F, 6, Blocks.SPRUCE_LEAVES),
        entry("windswept_gravelly_hills", GRAVEL, GRAVEL, 0.05F, 6, Blocks.SPRUCE_LEAVES),
        entry("windswept_forest", GRASS, GRAVEL, 0.5F, 6, Blocks.SPRUCE_LEAVES),
        entry("windswept_savanna", GRASS, SAND, 0.08F, 6, Blocks.ACACIA_LEAVES),
        entry("savanna", GRASS, SAND, 0.12F, 6, Blocks.ACACIA_LEAVES),
        entry("savanna_plateau", GRASS, SAND, 0.12F, 6, Blocks.ACACIA_LEAVES),
        entry("jungle", GRASS, SAND, 0.9F, 10, Blocks.JUNGLE_LEAVES),
        entry("sparse_jungle", GRASS, SAND, 0.4F, 8, Blocks.JUNGLE_LEAVES),
        entry("bamboo_jungle", Blocks.PODZOL, SAND, 0.8F, 9, Blocks.JUNGLE_LEAVES),
        entry("desert", SAND, SAND, 0F, 0, null),
        entry("beach", SAND, SAND, 0F, 0, null),
        entry("snowy_beach", SNOW, SAND, 0F, 0, null),
        entry("stony_shore", STONE, GRAVEL, 0F, 0, null),
        entry("badlands", Blocks.RED_SAND, Blocks.RED_SAND, 0F, 0, null),
        entry("eroded_badlands", Blocks.TERRACOTTA, Blocks.RED_SAND, 0F, 0, null),
        entry("wooded_badlands", Blocks.COARSE_DIRT, Blocks.RED_SAND, 0.15F, 5, Blocks.OAK_LEAVES),
        entry("swamp", GRASS, DIRT, 0.35F, 6, Blocks.OAK_LEAVES),
        entry("mangrove_swamp", Blocks.MUD, Blocks.MUD, 0.7F, 8, Blocks.MANGROVE_LEAVES),
        entry("mushroom_fields", Blocks.MYCELIUM, GRAVEL, 0.02F, 6, Blocks.RED_MUSHROOM_BLOCK),
        entry("river", GRASS, SAND, 0F, 0, null),
        entry("frozen_river", SNOW, SAND, 0F, 0, null),
        entry("warm_ocean", SAND, SAND, 0F, 0, null),
        entry("lukewarm_ocean", SAND, SAND, 0F, 0, null),
        entry("deep_lukewarm_ocean", SAND, SAND, 0F, 0, null),
        entry("ocean", GRAVEL, GRAVEL, 0F, 0, null),
        entry("deep_ocean", GRAVEL, GRAVEL, 0F, 0, null),
        entry("cold_ocean", GRAVEL, GRAVEL, 0F, 0, null),
        entry("deep_cold_ocean", GRAVEL, GRAVEL, 0F, 0, null),
        entry("frozen_ocean", GRAVEL, GRAVEL, 0F, 0, null),
        entry("deep_frozen_ocean", GRAVEL, GRAVEL, 0F, 0, null),
        entry("dripstone_caves", STONE, STONE, 0F, 0, null),
        entry("lush_caves", Blocks.MOSS_BLOCK, Blocks.CLAY, 0F, 0, null),
        entry("deep_dark", Blocks.DEEPSLATE, Blocks.DEEPSLATE, 0F, 0, null),
        entry("the_end", Blocks.END_STONE, Blocks.END_STONE, 0F, 0, null),
        entry("end_highlands", Blocks.END_STONE, Blocks.END_STONE, 0F, 0, null),
        entry("end_midlands", Blocks.END_STONE, Blocks.END_STONE, 0F, 0, null),
        entry("small_end_islands", Blocks.END_STONE, Blocks.END_STONE, 0F, 0, null),
        entry("end_barrens", Blocks.END_STONE, Blocks.END_STONE, 0F, 0, null)
    );
    private static final Map<Holder<Biome>, Surface> RESOLVED = new ConcurrentHashMap<>();

    private LodSurfacePalette() { }

    private static Map.Entry<String, Surface> entry(String biome, Object top, Object floor, float trees, int canopy, @Nullable Object leaves) {
        return Map.entry(biome, new Surface(state(top), state(floor), trees, canopy, leaves == null ? null : state(leaves)));
    }

    private static BlockState state(Object value) {
        return value instanceof BlockState state ? state : ((net.minecraft.world.level.block.Block)value).defaultBlockState();
    }

    static Surface surface(Holder<Biome> biome) {
        return RESOLVED.computeIfAbsent(biome, LodSurfacePalette::resolve);
    }

    private static Surface resolve(Holder<Biome> biome) {
        var key = biome.unwrapKey().orElse(null);
        if (key != null && "minecraft".equals(key.identifier().getNamespace())) {
            Surface known = VANILLA.get(key.identifier().getPath());
            if (known != null) return known;
        }
        // Modded biome: dry and warm reads as sand, everything else as grass with a light canopy.
        Biome value = biome.value();
        if (!value.hasPrecipitation() && value.getBaseTemperature() > 1.0F) return new Surface(SAND, SAND, 0F, 0, null);
        return DEFAULT;
    }
}
