package dev.metalcraft.client.lod;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import dev.metalcraft.client.mixin.ViewAreaAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Distant terrain for one client level: node selection, background builds, GPU residency and
 * draw emission. Everything except the build jobs runs on the client (render) thread.
 */
final class LodSession implements AutoCloseable {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int MAX_LEVEL = 7;
    /** Nodes at or below this level are meshed per chunk and may overlap the native radius. */
    static final int SPLIT_LEVEL = 2;
    /** A draw-list key of its own, so distant solids stay together after native section groups. */
    private static final int DRAW_GROUP = Integer.MIN_VALUE + 1;
    /** Textured solids share the block atlas binding; keeping them together avoids rebinding it. */
    private static final int TEXTURED_DRAW_GROUP = Integer.MIN_VALUE + 2;
    /** Key bit of a level-0 node meshed with block textures rather than the colour atlas. */
    static final long TEXTURED = 1L << 63;
    private static final long EVICT_AFTER_NANOS = 10_000_000_000L;
    private static final long UPLOAD_BUDGET_NANOS = 2_000_000L;
    /** Coalesces edits: a changed node rebuilds at most this often. */
    private static final long REBUILD_INTERVAL_NANOS = 250_000_000L;
    private static final long RETRY_FAILED_NANOS = 2_000_000_000L;
    /** Longer than eviction, so nodes dropped by the last reduction are gone before judging again. */
    private static final long TEXTURE_REDUCTION_INTERVAL_NANOS = 15_000_000_000L;
    /** Real chunks kept for distant models (about 5 KiB each). */
    private static final int MAX_CAPTURED = 32768;
    private static final int[] NO_RANGES = new int[0];

    final ClientLevel level;
    private final Executor workers;
    private final int maxInFlight;
    private final LodTile.Sampler sampler;
    private final LodSavedChunks saved;
    private final LodMesher mesher;
    private final LodBlockColors colors;
    private final LodAtlas atlas = new LodAtlas();
    private final LodBiomes biomes = new LodBiomes();
    private final boolean floating;
    private final int seaLevel;
    final Map<Long, LodChunk> real = new ConcurrentHashMap<>();
    private final LongOpenHashSet dirtyChunks = new LongOpenHashSet();
    private final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap readyUntil = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
    private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();
    private final ConcurrentLinkedQueue<Built> built = new ConcurrentLinkedQueue<>();
    private final List<Emit> emits = new ArrayList<>();
    private final LodBuildQueue<Node> requests;
    private final long gpuBudget;
    private volatile boolean closed;
    private long frame;
    private long now;
    private long ticks;
    private int inFlight;
    private int pending, residentNodes;
    private long gpuBytes;
    private int maxQuadsPerDraw;
    // Frame inputs, set by prepare().
    private double cameraX, cameraY, cameraZ;
    private int cameraChunkX, cameraChunkZ;
    private int nativeDistance;
    private double horizon;
    private double levelDistance;
    private double textureDistance;
    /** Share of the texture distance in use; lowered in quarters while over the GPU budget. */
    private double textureShare = 1;
    private long textureReducedAt;
    // Settings the selection was planned for, and the update a change of them started.
    private int plannedDetail = -1, plannedNative = -1, plannedRender = -1;
    private boolean updating;
    private long updateStartedAt, updateBuilds;
    private double heightBias;
    private net.minecraft.client.renderer.culling.Frustum frustum;
    private final LodStats stats = new LodStats();

    LodSession(ClientLevel level, ServerLevel server, LodBlockColors colors, Executor workers, int threads, long gpuBudget) {
        this.level = level;
        this.colors = colors;
        this.workers = workers;
        this.maxInFlight = Math.max(2, threads * 2);
        this.requests = new LodBuildQueue<>(this.maxInFlight);
        this.gpuBudget = gpuBudget;
        LodGenerator generator = LodGenerator.create(server);
        this.floating = generator.floating;
        this.seaLevel = generator.seaLevel;
        this.sampler = new LodTile.Sampler(generator, this.real, this.biomes, level.getMinY());
        this.saved = new LodSavedChunks(server, colors, this.biomes, workers, this.floating);
        var lighting = level.cardinalLighting();
        float[] shade = {
            lighting.byFace(Direction.UP), lighting.byFace(Direction.DOWN), lighting.byFace(Direction.NORTH),
            lighting.byFace(Direction.SOUTH), lighting.byFace(Direction.WEST), lighting.byFace(Direction.EAST)
        };
        this.mesher = new LodMesher(shade, LodAtlas.SIZE, LodAtlas.SIZE, LodAtlas.WHITE_UV, LodAtlas.WHITE_UV, level.getMinY());
    }

    // ---- Real terrain ------------------------------------------------------------------------------

    void captured(LevelChunk chunk) {
        var pos = chunk.getPos();
        this.real.put(pos.pack(), LodChunk.capture(chunk, this.colors, this.biomes, this.floating));
        this.dirtyChunks.remove(pos.pack());
        this.stats.captures++;
        this.invalidate(pos.x(), pos.z());
    }

    void unloaded(LevelChunk chunk) {
        this.readyUntil.remove(chunk.getPos().pack());
        if (this.dirtyChunks.contains(chunk.getPos().pack())) this.captured(chunk);
    }

    /** Native sections were all reset (reload, F3+A): stand in again until they recompile. */
    void nativeReset() {
        this.readyUntil.clear();
    }

    void sectionDirty(int chunkX, int chunkZ) {
        this.dirtyChunks.add(ChunkPos.pack(chunkX, chunkZ));
    }

    /**
     * Refreshes edited chunks that stay loaded, a few at a time, so their distant model stays
     * current; forgets captured chunks well beyond the view (about 3 KiB each).
     */
    void tick() {
        if (++this.ticks % 200 == 0) {
            if (this.real.size() > MAX_CAPTURED / 4) this.forgetDistantCaptures();
            this.readyUntil.long2LongEntrySet().removeIf(entry -> entry.getLongValue() < this.frame);
        }
        if (this.ticks % 40 != 0 || this.dirtyChunks.isEmpty()) return;
        var iterator = this.dirtyChunks.iterator();
        for (int count = 0; iterator.hasNext() && count < 16; count++) {
            long key = iterator.nextLong();
            iterator.remove();
            LevelChunk chunk = this.level.getChunkSource().getChunk(ChunkPos.getX(key), ChunkPos.getZ(key), false);
            if (chunk != null) {
                this.real.put(key, LodChunk.capture(chunk, this.colors, this.biomes, this.floating));
                this.stats.captures++;
                this.invalidate(ChunkPos.getX(key), ChunkPos.getZ(key));
            }
        }
    }

    private void forgetDistantCaptures() {
        int limit = (int)(this.horizon / 16) + 8;
        this.real.keySet().removeIf(key -> Math.max(Math.abs(ChunkPos.getX(key) - this.cameraChunkX),
            Math.abs(ChunkPos.getZ(key) - this.cameraChunkZ)) > limit);
        this.saved.forgetBeyond(this.cameraChunkX, this.cameraChunkZ, limit);
        if (this.real.size() <= MAX_CAPTURED) return;
        // Still over budget inside the view: keep the nearest.
        List<Long> keys = new ArrayList<>(this.real.keySet());
        keys.sort(Comparator.comparingLong(key -> -(long)Math.max(Math.abs(ChunkPos.getX(key) - this.cameraChunkX),
            Math.abs(ChunkPos.getZ(key) - this.cameraChunkZ))));
        for (int index = 0; index < keys.size() - MAX_CAPTURED; index++) this.real.remove(keys.get(index));
    }

    /** Saved chunks replace generated terrain where they exist; live captures always win. */
    private void readSaved() {
        LodSavedChunks.Read read;
        while ((read = this.saved.poll()) != null) {
            if (read.chunk() != null && this.real.putIfAbsent(read.key(), read.chunk()) == null) {
                this.invalidate(ChunkPos.getX(read.key()), ChunkPos.getZ(read.key()));
            }
        }
        this.stats.savedChunks = this.saved.found;
    }

    /** Marks every node whose samples (including its one-cell border) read the chunk. */
    private void invalidate(int chunkX, int chunkZ) {
        int minX = chunkX << 4, minZ = chunkZ << 4;
        for (int level = 0; level <= MAX_LEVEL; level++) {
            int cell = 1 << level, size = LodTile.CELLS << level;
            for (int nx = Math.floorDiv(minX - cell, size); nx <= Math.floorDiv(minX + 15 + cell, size); nx++) {
                for (int nz = Math.floorDiv(minZ - cell, size); nz <= Math.floorDiv(minZ + 15 + cell, size); nz++) {
                    if (!samples(nx * size, minX, cell) || !samples(nz * size, minZ, cell)) continue;
                    Node node = this.nodes.get(key(level, nx, nz));
                    if (node != null) node.revision++;
                    Node textured = level == 0 ? this.nodes.get(key(level, nx, nz) | TEXTURED) : null;
                    if (textured != null) textured.revision++;
                }
            }
        }
    }

    /** Whether a node starting at {@code origin} places a sample in the 16 blocks from {@code start}. */
    private static boolean samples(int origin, int start, int cell) {
        int first = origin - cell + cell / 2;
        int index = Math.max(0, Math.ceilDiv(start - first, cell));
        int position = first + index * cell;
        return index <= LodTile.CELLS + 1 && position <= start + 15;
    }

    // ---- Frame -------------------------------------------------------------------------------------

    void prepare(CameraRenderState camera, int nativeDistance, int renderDistance, int detail) {
        this.frame++;
        this.now = System.nanoTime();
        this.cameraX = camera.pos.x;
        this.cameraY = camera.pos.y;
        this.cameraZ = camera.pos.z;
        this.cameraChunkX = SectionPos.blockToSectionCoord(camera.pos.x);
        this.cameraChunkZ = SectionPos.blockToSectionCoord(camera.pos.z);
        this.nativeDistance = nativeDistance;
        this.horizon = renderDistance * 16.0;
        this.levelDistance = LodSettings.levelDistance(detail);
        if (detail != this.plannedDetail || nativeDistance != this.plannedNative || renderDistance != this.plannedRender) {
            this.settingsChanged(detail, nativeDistance, renderDistance);
        }
        this.reduceTexturesOverBudget();
        this.textureDistance = this.colors.textured() ? LodSettings.textureDistance(detail) * this.textureShare : 0;
        this.heightBias = Math.max(0, this.cameraY - (this.seaLevel + 64));
        this.frustum = camera.cullFrustum;
        this.upload();
        this.readSaved();
        this.emits.clear();
        this.pending = 0;
        this.requests.reset(this.maxInFlight - this.inFlight);
        int rootSize = LodTile.CELLS << MAX_LEVEL;
        int minX = Math.floorDiv((int)Math.floor(this.cameraX - this.horizon), rootSize);
        int maxX = Math.floorDiv((int)Math.ceil(this.cameraX + this.horizon), rootSize);
        int minZ = Math.floorDiv((int)Math.floor(this.cameraZ - this.horizon), rootSize);
        int maxZ = Math.floorDiv((int)Math.ceil(this.cameraZ + this.horizon), rootSize);
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) this.visit(MAX_LEVEL, x, z);
        this.schedule();
        this.evict();
        if (this.updating && this.pending == 0 && this.inFlight == 0) this.finishUpdate();
        this.stats.updating = this.updating;
        this.stats.drawnNodes = this.emits.size();
        this.stats.texturedNodes = 0;
        this.stats.drawnBytes = this.stats.texturedBytes = 0;
        for (Emit emit : this.emits) {
            this.stats.drawnBytes += emit.node.bytes;
            if (!emit.node.textured) continue;
            this.stats.texturedNodes++;
            this.stats.texturedBytes += emit.node.bytes;
        }
        this.stats.residentNodes = this.residentNodes;
        this.stats.gpuBytes = this.gpuBytes;
        this.stats.inFlight = this.inFlight;
        this.stats.pending = this.pending;
        this.stats.capturedChunks = this.real.size();
    }

    /**
     * Detail, native distance or total distance changed. The selection follows the settings from
     * this frame on, so the new view starts building at once (even while a settings screen pauses
     * the game); this makes the rest of the change take effect with it. The texture budget is judged
     * afresh for the new settings, and once the new view is complete the nodes only the old settings
     * used are released together instead of each after the eviction delay, so they neither hold
     * memory nor count against the budget.
     */
    private void settingsChanged(int detail, int nativeDistance, int renderDistance) {
        boolean first = this.plannedDetail < 0;
        this.plannedDetail = detail;
        this.plannedNative = nativeDistance;
        this.plannedRender = renderDistance;
        if (first) return;
        this.textureShare = 1;
        this.textureReducedAt = this.now;
        this.updating = true;
        this.updateStartedAt = this.now;
        this.updateBuilds = this.stats.builds;
    }

    /** The view for new settings is complete: release every node it does not use. */
    private void finishUpdate() {
        this.updating = false;
        long released = 0;
        var iterator = this.nodes.values().iterator();
        while (iterator.hasNext()) {
            Node node = iterator.next();
            if (node.lastUsed == this.frame || node.building) continue;
            released += node.bytes;
            this.release(node);
            iterator.remove();
        }
        LOGGER.info("Distant terrain updated for detail {}, native distance {}, render distance {} in {} s: {} areas built, {} MiB of the previous view released",
            this.plannedDetail, this.plannedNative, this.plannedRender, String.format(java.util.Locale.ROOT, "%.1f", (this.now - this.updateStartedAt) / 1e9),
            this.stats.builds - this.updateBuilds, released >> 20);
    }

    /**
     * Textured nodes cost the most GPU memory, and the budget cannot evict nodes in view, so a view
     * that does not fit textures less of it: a quarter less each time, once the previous reduction's
     * nodes have been released, for the rest of the session.
     */
    private void reduceTexturesOverBudget() {
        if (this.gpuBytes <= this.gpuBudget || this.textureShare == 0 || this.now - this.textureReducedAt < TEXTURE_REDUCTION_INTERVAL_NANOS) return;
        this.textureShare = Math.max(0, this.textureShare - 0.25);
        this.textureReducedAt = this.now;
        LOGGER.info("Distant terrain uses {} MiB of its {} MiB GPU budget; block textures now reach {}% of their distance",
            this.gpuBytes >> 20, this.gpuBudget >> 20, Math.round(this.textureShare * 100));
    }

    /** Returns whether the node's area is fully covered by what this call emitted. */
    private boolean visit(int level, int x, int z) {
        int size = LodTile.CELLS << level;
        double minX = (double)x * size, minZ = (double)z * size;
        double dx = Math.max(0, Math.max(minX - this.cameraX, this.cameraX - (minX + size)));
        double dz = Math.max(0, Math.max(minZ - this.cameraZ, this.cameraZ - (minZ + size)));
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal > this.horizon) return true;
        int chunkX0 = x * size >> 4, chunkZ0 = z * size >> 4, chunks = size >> 4;
        boolean overlap = this.overlapsNative(chunkX0, chunkZ0, chunks);
        double distance = Math.sqrt(horizontal * horizontal + this.heightBias * this.heightBias);
        boolean split = level > 0 && (level > this.desiredLevel(distance) || overlap && level > SPLIT_LEVEL);
        long key = key(level, x, z);
        if (split) {
            int start = this.emits.size();
            boolean complete = true;
            for (int child = 0; child < 4; child++) complete &= this.visit(level - 1, 2 * x + (child & 1), 2 * z + (child >> 1));
            if (complete) return true;
            // Stand in for incomplete children with this coarser node, if it can respect native terrain.
            if (overlap && level > SPLIT_LEVEL) return false;
            Node node = this.node(key, level, x, z);
            if (node.resident) {
                while (this.emits.size() > start) this.emits.removeLast();
                this.emit(node, overlap);
                return true;
            }
            this.request(node, horizontal, size);
            return false;
        }
        // Block-sized cells near the camera keep their block textures. A node wholly inside the
        // native radius only stands in while native chunks compile, so it stays flat and cheap.
        if (level == 0 && distance < this.textureDistance && !this.insideNative(chunkX0, chunkZ0, chunks)) key |= TEXTURED;
        Node node = this.node(key, level, x, z);
        if (node.resident) {
            this.emit(node, overlap);
            if (node.builtRevision != node.revision && this.now - node.builtAt > REBUILD_INTERVAL_NANOS) this.request(node, horizontal, size);
            return true;
        }
        this.request(node, horizontal, size);
        if (level == 0) {
            // The same cells in the other form (textured or flat) cover the area until this one is built.
            Node other = this.nodes.get(key ^ TEXTURED);
            if (other == null || !other.resident) return false;
            this.emit(other, overlap);
            return true;
        }
        // Children still resident from a closer view keep the area covered until this node is built.
        Node[] children = new Node[4];
        for (int child = 0; child < 4; child++) {
            children[child] = this.resident(level - 1, 2 * x + (child & 1), 2 * z + (child >> 1));
            if (children[child] == null) return false;
        }
        for (Node child : children) {
            child.lastUsed = this.frame;
            child.lastUsedAt = this.now;
            int childChunks = chunks >> 1;
            this.emit(child, overlap && this.overlapsNative(child.x * (size >> 1) >> 4, child.z * (size >> 1) >> 4, childChunks));
        }
        return true;
    }

    /** The resident node for an area, in either form at level 0. */
    private @Nullable Node resident(int level, int x, int z) {
        Node node = this.nodes.get(key(level, x, z));
        if (node != null && node.resident) return node;
        if (level > 0) return null;
        node = this.nodes.get(key(level, x, z) | TEXTURED);
        return node != null && node.resident ? node : null;
    }

    private int desiredLevel(double distance) {
        double ratio = distance / this.levelDistance;
        if (ratio < 2) return 0;
        return Math.min(MAX_LEVEL, 31 - Integer.numberOfLeadingZeros((int)Math.min(ratio, 1 << 30)));
    }

    /** Vanilla's render test for a chunk: its (buffered) distance from the camera chunk is under the radius. */
    private boolean nativeChunk(int chunkX, int chunkZ, int radius) {
        long dx = Math.max(0, Math.abs(chunkX - this.cameraChunkX) - 1), dz = Math.max(0, Math.abs(chunkZ - this.cameraChunkZ) - 1);
        return dx * dx + dz * dz < (long)radius * radius;
    }

    /** Whether every chunk of the square is inside the native radius. */
    private boolean insideNative(int chunkX0, int chunkZ0, int chunks) {
        int farthestX = this.cameraChunkX - chunkX0 > chunkX0 + chunks - 1 - this.cameraChunkX ? chunkX0 : chunkX0 + chunks - 1;
        int farthestZ = this.cameraChunkZ - chunkZ0 > chunkZ0 + chunks - 1 - this.cameraChunkZ ? chunkZ0 : chunkZ0 + chunks - 1;
        return this.nativeChunk(farthestX, farthestZ, this.nativeDistance);
    }

    private boolean overlapsNative(int chunkX0, int chunkZ0, int chunks) {
        int nearestX = Math.clamp(this.cameraChunkX, chunkX0, chunkX0 + chunks - 1);
        int nearestZ = Math.clamp(this.cameraChunkZ, chunkZ0, chunkZ0 + chunks - 1);
        return this.nativeChunk(nearestX, nearestZ, this.nativeDistance);
    }

    /**
     * Whether native rendering already draws this chunk's surface. Until it does (joining, teleports,
     * chunks entering the radius, reloads), the distant model stands in for it. Vanilla compiles
     * only sections in view, so a surface section outside the view counts as drawn: standing in for
     * it would draw the whole distant chunk over native terrain already on screen, where tree
     * columns (solid down to the ground in the distant model) would show under real canopies.
     * Full readiness is re-verified every few frames; it is lost only when a chunk unloads or its
     * sections reset.
     */
    private boolean nativeCovers(int chunkX, int chunkZ) {
        if (!this.nativeChunk(chunkX, chunkZ, this.nativeDistance)) return false;
        long key = ChunkPos.pack(chunkX, chunkZ);
        if (this.readyUntil.get(key) >= this.frame) return true;
        Surface surface = this.surfaceCompiled(chunkX, chunkZ);
        if (surface == Surface.COMPILED) this.readyUntil.put(key, this.frame + 16 + (key & 15));
        else this.readyUntil.remove(key);
        return surface != Surface.MISSING;
    }

    private enum Surface { COMPILED, OUT_OF_VIEW, MISSING }

    private Surface surfaceCompiled(int chunkX, int chunkZ) {
        if (!this.level.getChunkSource().hasChunk(chunkX, chunkZ)) return Surface.MISSING;
        var area = Minecraft.getInstance().levelRenderer.viewArea();
        if (area == null) return Surface.MISSING;
        LodChunk chunk = this.real.get(ChunkPos.pack(chunkX, chunkZ));
        if (chunk == null) {
            // Loaded before this session began (settings changed in-game): capture it now.
            LevelChunk loaded = this.level.getChunkSource().getChunk(chunkX, chunkZ, false);
            if (loaded == null) return Surface.MISSING;
            this.captured(loaded);
            chunk = this.real.get(ChunkPos.pack(chunkX, chunkZ));
        }
        var sections = ((ViewAreaAccessor)area).metalcraft$sections();
        Surface result = Surface.COMPILED;
        for (int y = SectionPos.blockToSectionCoord(chunk.minSurface()); y <= SectionPos.blockToSectionCoord(chunk.maxSurface()); y++) {
            long node = SectionPos.asLong(chunkX, y, chunkZ);
            var section = sections.getValue(node);
            if (section == null || section.getSectionNode() != node) return Surface.MISSING;
            if (section.getSectionMesh() != CompiledSectionMesh.UNCOMPILED) continue;
            int minX = chunkX << 4, minY = y << 4, minZ = chunkZ << 4;
            if (this.frustum.isVisible(new AABB(minX, minY, minZ, minX + 16, minY + 16, minZ + 16))) return Surface.MISSING;
            result = Surface.OUT_OF_VIEW;
        }
        return result;
    }

    private void emit(Node node, boolean overlap) {
        node.lastUsed = this.frame;
        node.lastUsedAt = this.now;
        if (node.solidQuads + node.fluidQuads == 0) return;
        int size = LodTile.CELLS << node.level;
        double minX = (double)node.x * size, minZ = (double)node.z * size;
        if (!this.frustum.isVisible(new AABB(minX, node.minY, minZ, minX + size, node.maxY + 1, minZ + size))) return;
        int[] solid, fluid;
        if (!overlap || node.groups == 1) {
            solid = node.allSolid;
            fluid = node.allFluid;
        } else {
            int chunkX0 = node.x * size >> 4, chunkZ0 = node.z * size >> 4;
            boolean[] included = new boolean[node.groups * node.groups];
            for (int gz = 0; gz < node.groups; gz++) for (int gx = 0; gx < node.groups; gx++) {
                included[gx + gz * node.groups] = !this.nativeCovers(chunkX0 + gx, chunkZ0 + gz);
            }
            solid = runs(node.solidRanges, included);
            fluid = runs(node.fluidRanges, included);
            // Native terrain covers the whole node: nothing to draw, no uniform to write.
            if (solid.length == 0 && fluid.length == 0) return;
        }
        this.emits.add(new Emit(node, solid, fluid, (minX + size / 2.0 - this.cameraX) * (minX + size / 2.0 - this.cameraX)
            + (minZ + size / 2.0 - this.cameraZ) * (minZ + size / 2.0 - this.cameraZ)));
    }

    /** Coalesces included groups (contiguous in the mesh) into (first quad, count) runs. */
    private static int[] runs(int[] ranges, boolean[] included) {
        int[] result = new int[ranges.length];
        int length = 0;
        for (int group = 0; group < included.length; group++) {
            int count = ranges[2 * group + 1];
            if (!included[group] || count == 0) continue;
            int first = ranges[2 * group];
            if (length > 0 && result[length - 2] + result[length - 1] == first) result[length - 1] += count;
            else {
                result[length++] = first;
                result[length++] = count;
            }
        }
        return java.util.Arrays.copyOf(result, length);
    }

    ChunkSectionsToRender append(ChunkSectionsToRender original, CameraRenderState camera) {
        if (this.emits.isEmpty()) return original;
        var view = new Matrix4f(camera.viewRotationMatrix);
        // Textured solids sample the block atlas, so their uniform carries its size; every fluid,
        // and every flat node, samples the colour atlas. A textured node with fluid needs both.
        int blockAtlasWidth = original.textureView().getWidth(0), blockAtlasHeight = original.textureView().getHeight(0);
        List<DynamicUniforms.ChunkSectionInfo> infos = new ArrayList<>(this.emits.size());
        int[] solidInfo = new int[this.emits.size()], fluidInfo = new int[this.emits.size()];
        for (int index = 0; index < this.emits.size(); index++) {
            Emit emit = this.emits.get(index);
            Node node = emit.node;
            int size = LodTile.CELLS << node.level;
            fluidInfo[index] = solidInfo[index] = infos.size();
            if (node.textured) {
                infos.add(new DynamicUniforms.ChunkSectionInfo(view, node.x * size, 0, node.z * size, 1.0F, blockAtlasWidth, blockAtlasHeight));
                if (emit.fluid.length == 0) continue;
                fluidInfo[index] = infos.size();
            }
            infos.add(new DynamicUniforms.ChunkSectionInfo(view, node.x * size, 0, node.z * size, 1.0F, LodAtlas.SIZE, LodAtlas.SIZE));
        }
        GpuBufferSlice[] uniforms = RenderSystem.getDynamicUniforms().writeChunkSections(infos.toArray(DynamicUniforms.ChunkSectionInfo[]::new));
        var layers = original.drawGroupsPerLayer();
        List<RenderPass.Draw<GpuBufferSlice[]>> flat = layers.get(ChunkSectionLayer.SOLID).computeIfAbsent(DRAW_GROUP, ignored -> new ArrayList<>());
        List<RenderPass.Draw<GpuBufferSlice[]>> textured = layers.get(ChunkSectionLayer.SOLID).computeIfAbsent(TEXTURED_DRAW_GROUP, ignored -> new ArrayList<>());
        List<RenderPass.Draw<GpuBufferSlice[]>> translucent = new ArrayList<>();
        int maxIndices = original.maxIndicesRequired();
        int draws = 0;
        for (int index = 0; index < this.emits.size(); index++) {
            Emit emit = this.emits.get(index);
            List<RenderPass.Draw<GpuBufferSlice[]>> solid = emit.node.textured ? textured : flat;
            LodTextureBinding solidTexture = emit.node.textured ? null : this.atlas.binding;
            for (int run = 0; run < emit.solid.length; run += 2) {
                solid.add(this.draw(emit.node, uniforms[solidInfo[index]], solidTexture, emit.solid[run], emit.solid[run + 1], emit.distance, false));
                maxIndices = Math.max(maxIndices, emit.solid[run + 1] * 6);
                draws++;
            }
            for (int run = 0; run < emit.fluid.length; run += 2) {
                translucent.add(this.draw(emit.node, uniforms[fluidInfo[index]], this.atlas.binding, emit.node.solidQuads + emit.fluid[run],
                    emit.fluid[run + 1], emit.distance, true));
                maxIndices = Math.max(maxIndices, emit.fluid[run + 1] * 6);
                draws++;
            }
        }
        if (flat.isEmpty()) layers.get(ChunkSectionLayer.SOLID).remove(DRAW_GROUP);
        if (textured.isEmpty()) layers.get(ChunkSectionLayer.SOLID).remove(TEXTURED_DRAW_GROUP);
        if (!translucent.isEmpty()) {
            // Native rendering reverses each translucent list. Sort all of it by distance so distant
            // water composites before nearer native water, whatever order the map iterates in.
            var groups = layers.get(ChunkSectionLayer.TRANSLUCENT);
            groups.values().forEach(translucent::addAll);
            translucent.sort(Comparator.comparingDouble(draw -> ((LodDrawSource)(Object)draw).metalcraft$sortDistance()));
            groups.clear();
            groups.put(Integer.MIN_VALUE, translucent);
        }
        this.stats.draws = draws;
        this.maxQuadsPerDraw = maxIndices / 6;
        return new ChunkSectionsToRender(original.textureView(), layers, maxIndices, original.chunkSectionInfos());
    }

    /** @param texture the colour atlas, or null for textured solids, which sample the block atlas bound for native sections */
    private RenderPass.Draw<GpuBufferSlice[]> draw(Node node, GpuBufferSlice uniform, @Nullable LodTextureBinding texture, int firstQuad, int quads,
                                                   double distance, boolean fluid) {
        var draw = new RenderPass.Draw<GpuBufferSlice[]>(0, node.vertices, null, null, 0, quads * 6, firstQuad * 4,
            (ignored, uploader) -> uploader.upload("ChunkSection", uniform));
        LodDrawSource source = (LodDrawSource)(Object)draw;
        source.metalcraft$lodTexture(texture);
        source.metalcraft$sortDistance(distance);
        if (fluid) ((dev.metalcraft.client.shader.water.WaterDrawSource)(Object)draw).metalcraft$waterMesh(LodSystem.waterMetadata());
        return draw;
    }

    // ---- Builds ------------------------------------------------------------------------------------

    private Node node(long key, int level, int x, int z) {
        Node node = this.nodes.get(key);
        if (node == null) {
            node = new Node(key, level, x, z);
            this.nodes.put(key, node);
        }
        node.lastUsed = this.frame;
        node.lastUsedAt = this.now;
        return node;
    }

    private void request(Node node, double distance, int size) {
        node.lastUsed = this.frame;
        node.lastUsedAt = this.now;
        if (node.building || node.failedAt != 0 && this.now - node.failedAt < RETRY_FAILED_NANOS) return;
        // Coarse and near nodes first: large nodes give the whole horizon quickly, then refine.
        this.pending++;
        this.requests.offer(node, distance / size);
    }

    private void schedule() {
        if (this.pending == 0 || this.inFlight >= this.maxInFlight) return;
        LodBlockColors colors = this.colors;
        Node node;
        while (this.inFlight < this.maxInFlight && (node = this.requests.poll()) != null) {
            if (node.building) continue;
            // Textured nodes take their colours from block textures and need no colour-atlas slot.
            int slot = node.textured ? 0 : this.atlas.allocate();
            if (slot < 0) {
                this.evictLeastRecent(64);
                slot = this.atlas.allocate();
                if (slot < 0) break;
            }
            node.building = true;
            this.inFlight++;
            int revision = node.revision, level = node.level, x = node.x, z = node.z, targetSlot = slot;
            long key = node.key;
            boolean textured = node.textured;
            try {
                this.workers.execute(() -> this.build(key, revision, level, x, z, textured, targetSlot, colors));
            } catch (RuntimeException rejected) {
                node.building = false;
                this.inFlight--;
                this.atlas.release(slot);
                break;
            }
        }
    }

    /** Worker thread. */
    private void build(long key, int revision, int level, int x, int z, boolean textured, int slot, LodBlockColors colors) {
        if (this.closed) {
            this.built.add(new Built(key, revision, null, slot, 0, true, new long[0]));
            return;
        }
        long started = System.nanoTime();
        try {
            // Nodes near the player look up saved copies of the chunks they had to generate.
            LodTile tile = this.sampler.sample(level, x, z, colors, level <= SPLIT_LEVEL, textured);
            LodMesh mesh = textured ? this.mesher.meshTextured(tile, colors, this.biomes)
                : this.mesher.mesh(tile, level, level <= SPLIT_LEVEL, LodAtlas.slotX(slot), LodAtlas.slotY(slot));
            this.built.add(new Built(key, revision, mesh, slot, System.nanoTime() - started, false, tile.generatedChunks));
            // A session closed meanwhile never polls again; free the native mesh here instead.
            if (this.closed) this.drainClosed();
        } catch (RuntimeException | Error failure) {
            LOGGER.warn("Distant terrain build failed for level {} node {},{}", level, x, z, failure);
            this.built.add(new Built(key, revision, null, slot, System.nanoTime() - started, true, new long[0]));
        }
    }

    private void upload() {
        long deadline = System.nanoTime() + UPLOAD_BUDGET_NANOS;
        Built result;
        while (System.nanoTime() < deadline && (result = this.built.poll()) != null) {
            this.inFlight--;
            Node node = this.nodes.get(result.key);
            if (node == null || this.closed || result.mesh == null) {
                if (result.mesh != null) result.mesh.close();
                this.atlas.release(result.slot);
                if (node != null) {
                    node.building = false;
                    if (result.failed) node.failedAt = this.now;
                }
                continue;
            }
            try (LodMesh mesh = result.mesh) {
                GpuBuffer vertices = null;
                if (mesh.solidQuads + mesh.fluidQuads > 0) {
                    vertices = RenderSystem.getDevice().createBuffer(() -> "Metal Mod distant terrain", GpuBuffer.USAGE_VERTEX, mesh.vertices());
                }
                if (mesh.colors() != null) this.atlas.upload(result.slot, mesh.colors());
                this.release(node);
                node.vertices = vertices;
                node.slot = result.slot;
                node.bytes = mesh.bytes();
                node.solidQuads = mesh.solidQuads;
                node.fluidQuads = mesh.fluidQuads;
                node.allSolid = mesh.solidQuads == 0 ? NO_RANGES : new int[]{0, mesh.solidQuads};
                node.allFluid = mesh.fluidQuads == 0 ? NO_RANGES : new int[]{0, mesh.fluidQuads};
                node.solidRanges = mesh.solidRanges;
                node.fluidRanges = mesh.fluidRanges;
                node.groups = mesh.groupsPerSide;
                node.minY = mesh.minY;
                node.maxY = mesh.maxY;
                node.resident = true;
                this.residentNodes++;
                node.building = false;
                node.builtRevision = result.revision;
                node.builtAt = this.now;
                this.gpuBytes += node.bytes;
                this.stats.builds++;
                this.stats.buildNanos += result.nanos;
                this.stats.quads += mesh.solidQuads + mesh.fluidQuads;
                for (long chunk : result.generatedChunks) this.saved.want(chunk);
            }
        }
    }

    private void release(Node node) {
        if (node.resident) this.residentNodes--;
        if (node.vertices != null) node.vertices.close();
        node.vertices = null;
        if (node.slot > 0) this.atlas.release(node.slot);
        node.slot = -1;
        this.gpuBytes -= node.bytes;
        node.bytes = 0;
        node.resident = false;
    }

    private void evict() {
        var iterator = this.nodes.values().iterator();
        while (iterator.hasNext()) {
            Node node = iterator.next();
            if (node.lastUsed < this.frame && this.now - node.lastUsedAt > EVICT_AFTER_NANOS && !node.building) {
                this.release(node);
                iterator.remove();
            }
        }
        if (this.gpuBytes > this.gpuBudget) this.evictLeastRecent(Integer.MAX_VALUE);
    }

    /** Frees resident nodes not used this frame, oldest first, until the budget and slots recover. */
    private void evictLeastRecent(int slots) {
        List<Node> candidates = new ArrayList<>();
        for (Node node : this.nodes.values()) if (node.resident && node.lastUsed < this.frame) candidates.add(node);
        candidates.sort(Comparator.comparingLong(node -> node.lastUsed));
        int freed = 0;
        for (Node node : candidates) {
            if (this.gpuBytes <= this.gpuBudget && freed >= slots) break;
            this.release(node);
            if (!node.building) this.nodes.remove(node.key);
            freed++;
        }
    }

    LodStats stats() { return this.stats; }

    @Override
    public void close() {
        this.closed = true;
        for (Node node : this.nodes.values()) this.release(node);
        this.nodes.clear();
        Built result;
        while ((result = this.built.poll()) != null) if (result.mesh != null) result.mesh.close();
        this.atlas.close();
    }

    /** Drains results of jobs that finish after close; their meshes own native memory. */
    void drainClosed() {
        Built result;
        while ((result = this.built.poll()) != null) if (result.mesh != null) result.mesh.close();
    }

    static long key(int level, int x, int z) {
        return (long)level << 60 | (long)(x & 0x3FFFFFFF) << 30 | (z & 0x3FFFFFFF);
    }

    private static final class Node {
        final long key;
        final int level, x, z;
        final boolean textured;
        @Nullable GpuBuffer vertices;
        boolean resident, building;
        int slot = -1;
        long bytes;
        int solidQuads, fluidQuads, groups = 1;
        int[] solidRanges = {0, 0}, fluidRanges = {0, 0};
        int[] allSolid = NO_RANGES, allFluid = NO_RANGES;
        int minY, maxY;
        int revision, builtRevision = -1;
        long builtAt, lastUsed, lastUsedAt, failedAt;

        Node(long key, int level, int x, int z) {
            this.key = key;
            this.level = level;
            this.x = x;
            this.z = z;
            this.textured = (key & TEXTURED) != 0;
        }
    }

    private record Emit(Node node, int[] solid, int[] fluid, double distance) { }

    private record Built(long key, int revision, @Nullable LodMesh mesh, int slot, long nanos, boolean failed, long[] generatedChunks) { }
}
