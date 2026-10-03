package dev.metalcraft.client.lod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.biome.Biome;

/**
 * Small ids for the biomes one session has seen, so a column's biome fits in its packed
 * {@link LodSurface}. Client and server biome instances simply get ids of their own; both tint
 * the same. Safe for any thread.
 */
final class LodBiomes {
    private final Map<Biome, Integer> ids = new ConcurrentHashMap<>();
    private volatile Biome[] biomes = new Biome[0];

    int id(Biome biome) {
        Integer id = this.ids.get(biome);
        return id != null ? id : this.register(biome);
    }

    private synchronized int register(Biome biome) {
        Integer id = this.ids.get(biome);
        if (id != null) return id;
        Biome[] grown = java.util.Arrays.copyOf(this.biomes, this.biomes.length + 1);
        grown[grown.length - 1] = biome;
        // Published before the id, so a reader holding an id always finds its biome.
        this.biomes = grown;
        this.ids.put(biome, grown.length - 1);
        return grown.length - 1;
    }

    Biome biome(int id) { return this.biomes[id]; }
}
