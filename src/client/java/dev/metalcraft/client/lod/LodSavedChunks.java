package dev.metalcraft.client.lod;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.SimpleBitStorage;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.jspecify.annotations.Nullable;

/**
 * Real surfaces of chunks saved in earlier play, read through the server's own region I/O
 * worker (which also returns chunks still waiting to be written). Only finished chunks from the
 * current data version are used; anything else stays generated. A few reads are in flight at a
 * time so the server's own chunk loading keeps priority.
 */
final class LodSavedChunks {
    private static final int MAX_READS = 4;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final SimpleRegionStorage storage;
    private final PalettedContainerFactory containers;
    private final Executor workers;
    private final LodBlockColors colors;
    private final int minY;
    private final int minSection;
    private final int sections;
    private final int heightBits;
    private final boolean floating;
    private final int dataVersion = SharedConstants.getCurrentVersion().dataVersion().version();
    private final LongOpenHashSet attempted = new LongOpenHashSet();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final ConcurrentLinkedQueue<Read> results = new ConcurrentLinkedQueue<>();
    private int reading;
    long found;
    long missing;

    record Read(long key, @Nullable LodChunk chunk) { }

    LodSavedChunks(ServerLevel level, LodBlockColors colors, Executor workers, boolean floating) {
        this.storage = level.getChunkSource().chunkMap;
        this.containers = level.palettedContainerFactory();
        this.workers = workers;
        this.colors = colors;
        this.minY = level.getMinY();
        this.minSection = level.getMinSectionY();
        this.sections = level.getSectionsCount();
        this.heightBits = Mth.ceillog2(level.getHeight() + 1);
        this.floating = floating;
    }

    /** Client thread: queue a chunk once; chunks already attempted are never read again. */
    void want(long key) {
        if (this.attempted.add(key)) this.queue.enqueue(key);
    }

    /** Client thread: start reads up to the in-flight limit and return finished ones. */
    @Nullable Read poll() {
        while (this.reading < MAX_READS && !this.queue.isEmpty()) {
            long key = this.queue.dequeueLong();
            this.reading++;
            this.storage.read(new ChunkPos(ChunkPos.getX(key), ChunkPos.getZ(key))).handleAsync((tag, error) -> {
                LodChunk chunk = null;
                try {
                    if (error == null && tag.isPresent()) chunk = this.parse(tag.get());
                } catch (RuntimeException unreadable) {
                    chunk = null;
                }
                this.results.add(new Read(key, chunk));
                return null;
            }, this.workers);
        }
        Read read = this.results.poll();
        if (read != null) {
            this.reading--;
            if (read.chunk != null) this.found++;
            else this.missing++;
        }
        return read;
    }

    /** Forget attempts outside a square around a chunk, so returning there reads again. */
    void forgetBeyond(int chunkX, int chunkZ, int radius) {
        this.attempted.removeIf(key -> Math.max(Math.abs(ChunkPos.getX(key) - chunkX), Math.abs(ChunkPos.getZ(key) - chunkZ)) > radius);
    }

    private @Nullable LodChunk parse(CompoundTag tag) {
        if (!"minecraft:full".equals(tag.getString("Status").orElse("")) || NbtUtils.getDataVersion(tag, -1) != this.dataVersion) return null;
        CompoundTag heightmaps = tag.getCompound("Heightmaps").orElse(null);
        if (heightmaps == null) return null;
        SimpleBitStorage motion = this.heightmap(heightmaps, "MOTION_BLOCKING"), surface = this.heightmap(heightmaps, "WORLD_SURFACE");
        if (motion == null || surface == null) return null;
        CompoundTag[] sectionTags = new CompoundTag[this.sections];
        var list = tag.getListOrEmpty("sections");
        for (int index = 0; index < list.size(); index++) {
            CompoundTag section = list.getCompound(index).orElse(null);
            if (section == null) continue;
            int y = section.getByteOr("Y", (byte)0) - this.minSection;
            if (y >= 0 && y < this.sections) sectionTags[y] = section;
        }
        @SuppressWarnings("unchecked") PalettedContainerRO<BlockState>[] states = new PalettedContainerRO[this.sections];
        @SuppressWarnings("unchecked") PalettedContainerRO<Holder<Biome>>[] biomes = new PalettedContainerRO[this.sections];
        int baseX = tag.getIntOr("xPos", 0) << 4, baseZ = tag.getIntOr("zPos", 0) << 4;
        return LodChunk.read(new LodChunk.Source() {
            @Override public int motionTop(int x, int z) { return motion.get(x + z * 16) + LodSavedChunks.this.minY; }
            @Override public int surfaceTop(int x, int z) { return surface.get(x + z * 16) + LodSavedChunks.this.minY; }

            @Override
            public BlockState state(int x, int y, int z) {
                int index = (y >> 4) - LodSavedChunks.this.minSection;
                if (index < 0 || index >= sectionTags.length || sectionTags[index] == null) return AIR;
                if (states[index] == null) {
                    states[index] = sectionTags[index].getCompound("block_states")
                        .flatMap(container -> LodSavedChunks.this.containers.blockStatesContainerCodec().parse(NbtOps.INSTANCE, container).result())
                        .map(container -> (PalettedContainerRO<BlockState>)container)
                        .orElseGet(LodSavedChunks.this.containers::createForBlockStates);
                }
                return states[index].get(x, y & 15, z);
            }

            @Override
            public Holder<Biome> biome(int x, int y, int z) {
                int index = Math.clamp((y >> 4) - LodSavedChunks.this.minSection, 0, sectionTags.length - 1);
                if (sectionTags[index] == null) return LodSavedChunks.this.containers.defaultBiome();
                if (biomes[index] == null) {
                    biomes[index] = sectionTags[index].getCompound("biomes")
                        .flatMap(container -> LodSavedChunks.this.containers.biomeContainerCodec().parse(NbtOps.INSTANCE, container).result())
                        .orElseGet(LodSavedChunks.this.containers::createForBiomes);
                }
                return biomes[index].get(x >> 2, (y & 15) >> 2, z >> 2);
            }
        }, baseX, baseZ, this.minY, this.floating, this.colors);
    }

    private @Nullable SimpleBitStorage heightmap(CompoundTag heightmaps, String name) {
        long[] data = heightmaps.getLongArray(name).orElse(null);
        if (data == null) return null;
        try {
            return new SimpleBitStorage(this.heightBits, 256, data);
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
