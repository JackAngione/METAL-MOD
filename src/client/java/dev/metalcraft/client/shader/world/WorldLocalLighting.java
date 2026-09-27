package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalRenderPass;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.squid.GlowSquid;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.cubemob.MagmaCube;
import net.minecraft.world.entity.projectile.hurtingprojectile.Fireball;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jspecify.annotations.Nullable;

/** Cached world-space blockers and lights, consumed by the Standard pack's tile resolve. */
public final class WorldLocalLighting implements AutoCloseable {
    private static @Nullable WorldLocalLighting active;
    private final MetalDevice device;
    private final Map<Long, Section> sections = new HashMap<>();
    private final Map<BlockState, Integer> shapes = new IdentityHashMap<>();
    private final Map<IntArrayList, Integer> shapeOffsets = new HashMap<>();
    private final IntArrayList shapeWords = new IntArrayList();
    private @Nullable ClientLevel level;
    private @Nullable MetalBuffer scene, lights, clusters, moving, frame;
    private List<LocalLightVolume.Light> staticLights = List.of();
    private List<LocalLightVolume.Light> movingLights = List.of();
    private int originX, originY, originZ;
    private boolean rebuild = true;
    private long updates;
    private static final boolean PROFILE = Boolean.getBoolean("metalcraft.localLightingBenchmark");
    private long prepareCalls, prepareNanos, captureCount;
    private record Emitter(int x, int y, int z, int level) { }
    private record Section(@Nullable LevelChunk chunk, @Nullable LevelChunkSection storage, int[] cells, List<Emitter> lights) { }

    public WorldLocalLighting(MetalDevice device) {
        this.device = device;
        // Cell value zero transmits; offset CELLS names the shared full-cube shape.
        this.shapeWords.add(1);
        this.shapeWords.add(LocalLightVolume.FULL_CUBE);
        active = this;
    }

    /** Section notifications include neighboring shape changes (fences, doors, etc.). */
    public static void dirty(int x, int y, int z) {
        if (active != null && active.sections.remove(SectionPos.asLong(x, y, z)) != null) active.rebuild = true;
    }

    public static void reset() {
        if (active != null) {
            active.sections.clear();
            active.shapes.clear();
            active.shapeOffsets.clear();
            active.shapeWords.clear();
            active.shapeWords.add(1);
            active.shapeWords.add(LocalLightVolume.FULL_CUBE);
            active.rebuild = true;
        }
    }

    public void prepare(CameraRenderState camera) {
        if (!PROFILE) { this.prepareWorld(camera); return; }
        long start = System.nanoTime();
        try { this.prepareWorld(camera); }
        finally { this.prepareCalls++; this.prepareNanos += System.nanoTime() - start; }
    }

    private void prepareWorld(CameraRenderState camera) {
        var client = Minecraft.getInstance();
        if (client.level != this.level) {
            reset();
            this.level = client.level;
        }
        if (this.level == null || !camera.initialized) {
            this.writeFrame(0, 0, 0, false);
            return;
        }
        int ox = (Math.floorDiv(camera.blockPos.getX(), 16) - 3) * 16;
        int oy = (Math.floorDiv(camera.blockPos.getY(), 16) - 3) * 16;
        int oz = (Math.floorDiv(camera.blockPos.getZ(), 16) - 3) * 16;
        if (ox != this.originX || oy != this.originY || oz != this.originZ) this.rebuild = true;
        this.originX = ox; this.originY = oy; this.originZ = oz;
        var retained = new HashSet<Long>();
        for (int z = 0; z < LocalLightVolume.SECTIONS; z++) for (int x = 0; x < LocalLightVolume.SECTIONS; x++) {
            int sx = (ox >> 4) + x, sz = (oz >> 4) + z;
            LevelChunk chunk = this.level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
            for (int y = 0; y < LocalLightVolume.SECTIONS; y++) {
                int sy = (oy >> 4) + y;
                long key = SectionPos.asLong(sx, sy, sz);
                retained.add(key);
                int sectionIndex = this.level.getSectionIndexFromSectionY(sy);
                LevelChunkSection storage = chunk != null && sectionIndex >= 0 && sectionIndex < chunk.getSections().length
                    ? chunk.getSection(sectionIndex) : null;
                Section cached = this.sections.get(key);
                if (cached == null || cached.chunk != chunk || cached.storage != storage) {
                    this.sections.put(key, this.capture(chunk, storage, sx * 16, sy * 16, sz * 16));
                    this.rebuild = true;
                }
            }
        }
        this.sections.keySet().retainAll(retained);
        if (this.rebuild) this.uploadScene();
        ArrayList<LocalLightVolume.Light> dynamic = new ArrayList<>();
        float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        for (Entity entity : this.level.entitiesForRendering()) {
            if (entity.isRemoved() || entity.isSpectator()) continue;
            int emission = entityEmission(entity);
            if (emission == 0) continue;
            var pos = entity.getPosition(partial);
            double px = pos.x, py = pos.y + entity.getBbHeight() * 0.5, pz = pos.z;
            if (entity instanceof LivingEntity && !entity.isOnFire()) py = pos.y + entity.getEyeHeight() * 0.8;
            double dx = px - camera.pos.x, dy = py - camera.pos.y, dz = pz - camera.pos.z;
            if (dx * dx + dy * dy + dz * dz > 48 * 48) continue;
            dynamic.add(new LocalLightVolume.Light((float)(px - ox), (float)(py - oy), (float)(pz - oz), emission, true));
        }
        if (this.moving == null || !dynamic.equals(this.movingLights)) {
            MetalBuffer next = this.uploadLights(dynamic);
            if (this.moving != null) this.moving.close();
            this.moving = next;
            this.movingLights = List.copyOf(dynamic);
        }
        this.writeFrame((float)(camera.pos.x - ox), (float)(camera.pos.y - oy), (float)(camera.pos.z - oz), true);
    }

    private Section capture(@Nullable LevelChunk chunk, @Nullable LevelChunkSection storage, int x0, int y0, int z0) {
        this.captureCount++;
        int[] cells = new int[4096];
        ArrayList<Emitter> emitters = new ArrayList<>();
        if (storage == null) {
            // Unloaded space must not leak light. Above/below the world's build limits is empty.
            if (chunk == null && y0 >= this.level.getMinY() && y0 < this.level.getMaxY())
                java.util.Arrays.fill(cells, LocalLightVolume.CELLS);
        } else if (!storage.hasOnlyAir()) {
            var pos = new BlockPos.MutableBlockPos();
            for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                BlockState state = storage.getBlockState(x, y, z);
                if (state.isAir()) continue;
                pos.set(x0 + x, y0 + y, z0 + z);
                cells[x + 16 * (y + 16 * z)] = this.shape(state, pos);
                int emission = state.getLightEmission();
                if (emission > 0) emitters.add(new Emitter(x0 + x, y0 + y, z0 + z, emission));
            }
        }
        return new Section(chunk, storage, cells, List.copyOf(emitters));
    }

    private int shape(BlockState state, BlockPos pos) {
        // Luminous fluids are opaque emitting volumes. Their filled interior cannot
        // illuminate a surface, and treating it as air would retain every voxel in a lava sea.
        boolean luminousFluid = state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock
            && state.getLightEmission() > 0 && !state.getFluidState().isEmpty();
        boolean dynamicShape = luminousFluid || state.getBlock().hasDynamicShape();
        Integer known = dynamicShape ? null : this.shapes.get(state);
        if (known != null) return known;
        // Transparent model materials transmit, while tinted glass follows its opaque light rule.
        if (state.getLightDampening() < 15 && Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state)
            .hasMaterialFlag(net.minecraft.client.resources.model.geometry.BakedQuad.FLAG_TRANSLUCENT)) {
            if (!dynamicShape) this.shapes.put(state, 0);
            return 0;
        }
        var shape = luminousFluid ? net.minecraft.world.phys.shapes.Shapes.box(0, 0, 0, 1, state.getFluidState().getHeight(this.level, pos), 1)
            : state.getLightDampening() >= 15 ? state.getShape(this.level, pos)
            : state.getVisualShape(this.level, pos, CollisionContext.empty());
        List<net.minecraft.world.phys.AABB> boxes = shape.toAabbs();
        int result = 0;
        if (!boxes.isEmpty()) {
            if (boxes.size() == 1 && boxes.getFirst().minX == 0 && boxes.getFirst().minY == 0
                && boxes.getFirst().minZ == 0 && boxes.getFirst().maxX == 1 && boxes.getFirst().maxY == 1 && boxes.getFirst().maxZ == 1) {
                result = LocalLightVolume.CELLS;
            } else {
                IntArrayList packed = new IntArrayList(boxes.size());
                for (var box : boxes) packed.add(LocalLightVolume.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
                Integer offset = this.shapeOffsets.get(packed);
                if (offset == null) {
                    offset = LocalLightVolume.CELLS + this.shapeWords.size();
                    this.shapeOffsets.put(packed, offset);
                    this.shapeWords.add(packed.size());
                    this.shapeWords.addAll(packed);
                }
                result = offset;
            }
        }
        if (!dynamicShape) this.shapes.put(state, result);
        return result;
    }

    private void uploadScene() {
        int[] cells = new int[LocalLightVolume.CELLS + this.shapeWords.size()];
        ArrayList<LocalLightVolume.Light> sources = new ArrayList<>();
        for (var entry : this.sections.entrySet()) {
            int x0 = SectionPos.x(entry.getKey()) * 16 - this.originX;
            int y0 = SectionPos.y(entry.getKey()) * 16 - this.originY;
            int z0 = SectionPos.z(entry.getKey()) * 16 - this.originZ;
            Section section = entry.getValue();
            for (int z = 0; z < 16; z++) for (int y = 0; y < 16; y++)
                System.arraycopy(section.cells, 16 * (y + 16 * z), cells, LocalLightVolume.index(x0, y0 + y, z0 + z), 16);
            for (Emitter emitter : section.lights) sources.add(new LocalLightVolume.Light(
                emitter.x - this.originX + 0.5F, emitter.y - this.originY + 0.5F,
                emitter.z - this.originZ + 0.5F, emitter.level, false));
        }
        // Opaque emitters completely enclosed by full blocks cannot illuminate a receiver.
        sources.removeIf(light -> {
            int x = (int)light.x(), y = (int)light.y(), z = (int)light.z();
            return x > 0 && y > 0 && z > 0 && x < LocalLightVolume.SIZE - 1 && y < LocalLightVolume.SIZE - 1 && z < LocalLightVolume.SIZE - 1
                && cells[LocalLightVolume.index(x - 1, y, z)] == LocalLightVolume.CELLS
                && cells[LocalLightVolume.index(x + 1, y, z)] == LocalLightVolume.CELLS
                && cells[LocalLightVolume.index(x, y - 1, z)] == LocalLightVolume.CELLS
                && cells[LocalLightVolume.index(x, y + 1, z)] == LocalLightVolume.CELLS
                && cells[LocalLightVolume.index(x, y, z - 1)] == LocalLightVolume.CELLS
                && cells[LocalLightVolume.index(x, y, z + 1)] == LocalLightVolume.CELLS;
        });
        this.shapeWords.getElements(0, cells, LocalLightVolume.CELLS, this.shapeWords.size());
        MetalBuffer nextScene = null, nextLights = null, nextClusters = null;
        try {
            nextScene = this.upload(cells);
            nextLights = this.uploadLights(sources);
            nextClusters = this.upload(LocalLightVolume.clusters(sources));
        } catch (RuntimeException failure) {
            if (nextScene != null) nextScene.close();
            if (nextLights != null) nextLights.close();
            if (nextClusters != null) nextClusters.close();
            throw failure;
        }
        if (this.scene != null) this.scene.close();
        if (this.lights != null) this.lights.close();
        if (this.clusters != null) this.clusters.close();
        this.scene = nextScene; this.lights = nextLights; this.clusters = nextClusters;
        this.staticLights = List.copyOf(sources);
        this.rebuild = false;
        this.updates++;
    }

    private MetalBuffer upload(int[] values) {
        MetalBuffer buffer = this.device.createBuffer(Math.max(4L, values.length * 4L), MetalBuffer.StorageMode.SHARED);
        try (var mapping = buffer.map()) { mapping.bytes().asIntBuffer().put(values); }
        catch (RuntimeException error) { buffer.close(); throw error; }
        return buffer;
    }

    private MetalBuffer uploadLights(List<LocalLightVolume.Light> values) {
        MetalBuffer buffer = this.device.createBuffer(Math.max(1, values.size()) * (long)LocalLightVolume.LIGHT_BYTES, MetalBuffer.StorageMode.SHARED);
        try (var mapping = buffer.map()) { for (var value : values) value.write(mapping.bytes()); }
        catch (RuntimeException error) { buffer.close(); throw error; }
        return buffer;
    }

    private void writeFrame(float x, float y, float z, boolean enabled) {
        if (this.frame != null) this.frame.close();
        this.frame = this.device.createBuffer(LocalLightVolume.FRAME_BYTES, MetalBuffer.StorageMode.SHARED);
        try (var mapping = this.frame.map()) {
            var bytes = mapping.bytes();
            bytes.putFloat(x).putFloat(y).putFloat(z).putFloat(LocalLightVolume.DISTANCE);
            bytes.putInt(LocalLightVolume.SIZE).putInt(this.staticLights.size()).putInt(this.movingLights.size()).putInt(enabled ? 1 : 0);
        }
    }

    /** Buffer bindings use immutable uploads; encoded native command buffers retain old resources. */
    public void bind(MetalRenderPass pass, int firstSlot) {
        if (this.frame == null) this.writeFrame(0, 0, 0, false);
        if (this.scene == null) this.scene = this.upload(new int[]{0});
        if (this.lights == null) this.lights = this.uploadLights(List.of());
        if (this.clusters == null) this.clusters = this.upload(new int[]{0});
        if (this.moving == null) this.moving = this.uploadLights(List.of());
        MetalBuffer[] buffers = {this.frame, this.scene, this.lights, this.clusters, this.moving};
        for (int i = 0; i < buffers.length; i++) pass.setUniformBuffer(firstSlot + i, buffers[i], 0, MetalRenderPass.STAGE_FRAGMENT);
    }

    public static int itemEmission(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (stack.getItem() instanceof BlockItem block) {
            BlockState state = block.getBlock().defaultBlockState();
            var properties = stack.get(DataComponents.BLOCK_STATE);
            return (properties == null ? state : properties.apply(state)).getLightEmission();
        }
        if (stack.is(Items.LAVA_BUCKET)) return 15;
        if (stack.is(Items.BLAZE_ROD) || stack.is(Items.BLAZE_POWDER)) return 10;
        if (stack.is(Items.GLOWSTONE_DUST) || stack.is(Items.GLOW_INK_SAC)) return 8;
        return 0;
    }

    public static int entityEmission(Entity entity) {
        int emission = entity.isOnFire() || entity instanceof Fireball ? 15 : 0;
        if (entity instanceof Blaze || entity instanceof MagmaCube) emission = Math.max(emission, 10);
        if (entity instanceof GlowSquid squid && squid.getDarkTicksRemaining() == 0) emission = Math.max(emission, 12);
        if (entity instanceof GlowItemFrame) emission = Math.max(emission, 8);
        if (entity instanceof ItemEntity item) emission = Math.max(emission, itemEmission(item.getItem()));
        if (entity instanceof LivingEntity living) {
            emission = Math.max(emission, itemEmission(living.getMainHandItem()));
            emission = Math.max(emission, itemEmission(living.getItemBySlot(EquipmentSlot.OFFHAND)));
        }
        return emission;
    }

    public int staticLightCount() { return this.staticLights.size(); }
    public int dynamicLightCount() { return this.movingLights.size(); }
    public long sceneUpdates() { return this.updates; }
    public record Profile(long calls, long nanos, long captures, long uploads, int sources, int moving) { }
    public Profile profile() { return new Profile(this.prepareCalls, this.prepareNanos, this.captureCount, this.updates,
        this.staticLights.size(), this.movingLights.size()); }
    public boolean hasPlacedLight(BlockPos pos, int emission) {
        return this.staticLights.contains(new LocalLightVolume.Light(pos.getX() - this.originX + 0.5F,
            pos.getY() - this.originY + 0.5F, pos.getZ() - this.originZ + 0.5F, emission, false));
    }

    @Override public void close() {
        for (MetalBuffer buffer : new MetalBuffer[]{this.scene, this.lights, this.clusters, this.moving, this.frame})
            if (buffer != null) buffer.close();
        this.scene = this.lights = this.clusters = this.moving = this.frame = null;
        this.sections.clear(); this.shapes.clear(); this.shapeOffsets.clear(); this.shapeWords.clear(); this.level = null;
        if (active == this) active = null;
    }
}
