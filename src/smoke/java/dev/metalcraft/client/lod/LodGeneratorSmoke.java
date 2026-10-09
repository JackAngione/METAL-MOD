package dev.metalcraft.client.lod;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Random;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/** Cached climate graphs must select exactly the same biomes as the native sampler. */
public final class LodGeneratorSmoke {
    private LodGeneratorSmoke() { }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var registries = VanillaRegistries.createLookup();
        for (boolean nether : new boolean[]{false, true}) {
            var settingsKey = nether ? NoiseGeneratorSettings.NETHER : NoiseGeneratorSettings.OVERWORLD;
            var presetKey = nether ? MultiNoiseBiomeSourceParameterLists.NETHER : MultiNoiseBiomeSourceParameterLists.OVERWORLD;
            var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(settingsKey);
            var preset = registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(presetKey);
            var source = MultiNoiseBiomeSource.createFromPreset(preset);
            var chunkGenerator = new NoiseBasedChunkGenerator(source, settings);
            var heights = nether ? LevelHeightAccessor.create(0,256) : LevelHeightAccessor.create(-64,384);
            for (long seed : new long[]{7_314_159L, -819_273L}) {
                var random = RandomState.create(registries,settingsKey,seed);
                var generator = new LodGenerator(chunkGenerator,random,heights,false);
                parity(generator,random.sampler(),source,heights);
            }
        }
        System.out.println("LOD generator: exact cached climate targets/biomes, repeated columns/heights, surface-cache interference and worker isolation passed");
    }

    private static void parity(LodGenerator generator, Climate.Sampler nativeSampler,
            MultiNoiseBiomeSource source, LevelHeightAccessor heights) throws Exception {
        int count=1024;
        var coordinates=new int[count][3];
        var targets=new Climate.TargetPoint[count];
        @SuppressWarnings("unchecked") Holder<Biome>[] biomes=new Holder[count];
        var random=new Random(98127);
        for(int i=0;i<count;i++) {
            // Include negative quart boundaries, and the same x/z at different
            // elevations: only truly height-independent inputs may be reused.
            int x=i%4==0 ? random.nextInt(9)-4 : random.nextInt(80_000)-40_000;
            int z=i%4==0 ? random.nextInt(9)-4 : random.nextInt(80_000)-40_000;
            if((i&1)!=0) { x=coordinates[i-1][0];z=coordinates[i-1][2]; }
            int y=heights.getMinY()+random.nextInt(heights.getHeight());
            coordinates[i]=new int[]{x,y,z};
            int qx=QuartPos.fromBlock(x),qy=QuartPos.fromBlock(y),qz=QuartPos.fromBlock(z);
            targets[i]=nativeSampler.sample(qx,qy,qz);
            biomes[i]=source.getNoiseBiome(qx,qy,qz,nativeSampler);
        }
        verify(generator,coordinates,targets,biomes);
        int workers=4;
        var barrier=new CyclicBarrier(workers);
        try(var pool=Executors.newFixedThreadPool(workers)) {
            var futures=new ArrayList<java.util.concurrent.Future<Climate.Sampler>>();
            for(int worker=0;worker<workers;worker++) futures.add(pool.submit(() -> {
                var sampler=generator.climateSampler();
                barrier.await();
                verify(generator,coordinates,targets,biomes);
                return sampler;
            }));
            var owners=new IdentityHashMap<Climate.Sampler,Boolean>();
            owners.put(generator.climateSampler(),true);
            for(var future:futures) check(owners.put(future.get(),true)==null,"worker climate cache has a unique owner");
        }
    }

    private static void verify(LodGenerator generator, int[][] coordinates,
            Climate.TargetPoint[] targets, Holder<Biome>[] biomes) {
        for(int i=0;i<coordinates.length;i++) {
            int x=coordinates[i][0],y=coordinates[i][1],z=coordinates[i][2];
            if(i%8==0) {
                generator.beginTile();
                generator.surface(x,z,LodGenerator.VOID);
            }
            var actual=generator.climateSampler().sample(QuartPos.fromBlock(x),QuartPos.fromBlock(y),QuartPos.fromBlock(z));
            check(actual.equals(targets[i]),"exact native climate target at "+x+","+y+","+z);
            check(generator.biome(x,y,z).equals(biomes[i]),"exact native biome at "+x+","+y+","+z);
        }
    }

    private static void check(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
    }
}
