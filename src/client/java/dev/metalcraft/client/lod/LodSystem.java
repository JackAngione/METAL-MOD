package dev.metalcraft.client.lod;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.chunk.NativeChunkDistance;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.metal.MetalStallProbe;
import dev.metalcraft.client.shader.water.WaterMeshBinding;
import dev.metalcraft.client.shader.water.WaterVertexMetadata;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jspecify.annotations.Nullable;

/**
 * Entry point for distant terrain: settings policy, lifecycle and the render hooks.
 *
 * <p>With LOD enabled in single-player on Metal, Minecraft's render distance becomes the total
 * view and chunk loading (server, client and native meshing) stops at the native distance.
 * Dimensions with a ceiling keep that native limit but draw no distant terrain.
 */
public final class LodSystem {
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();
    private static volatile boolean metal;
    private static @Nullable Status loggedStatus;
    private static @Nullable LodSession session;
    private static @Nullable ExecutorService workers;
    private static int workerThreads;
    private static @Nullable WaterMeshBinding water;

    private LodSystem() { }

    public static void initialize() {
        net.minecraft.client.gui.components.debug.DebugScreenEntries.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("metalcraft", "distant_terrain"), new LodDebugEntry());
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> metal = "Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            reset();
            if (workers != null) workers.shutdownNow();
            workers = null;
        });
        ClientTickEvents.END_CLIENT_TICK.register(LodSystem::tick);
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            LodSession current = session;
            if (current != null && current.level == level) current.captured(chunk);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            LodSession current = session;
            if (current != null && current.level == level) current.unloaded(chunk);
        });
    }

    /**
     * Whether chunk loading is capped at the native distance. Read by the client and the
     * integrated server thread, so it depends only on settings shared by both.
     */
    public static boolean limitsNativeDistance() {
        Minecraft client = Minecraft.getInstance();
        return metal && MetalCraftConfig.lodEnabled() && client.getSingleplayerServer() != null
            && client.options.renderDistance().get() > MetalCraftConfig.lodNativeDistance();
    }

    /**
     * Distance for ordinary chunk loading and meshing, given the requested render distance.
     * Without distant terrain it never exceeds {@link NativeChunkDistance#MAX}, even though the
     * option allows more.
     */
    public static int nativeDistance(int requested) {
        return Math.min(requested, limitsNativeDistance() ? MetalCraftConfig.lodNativeDistance() : NativeChunkDistance.MAX);
    }

    /** Whether distant terrain is being drawn for the current level. */
    public static boolean active() {
        return session != null && limitsNativeDistance();
    }

    /** Total view distance in chunks while distant terrain is drawn, otherwise zero. */
    public static int horizon() {
        return active() ? Minecraft.getInstance().options.renderDistance().get() : 0;
    }

    /** Why distant terrain is or is not running, for the settings screen and the log. */
    public enum Status { ACTIVE, READY, DISABLED, NOT_METAL, RENDER_DISTANCE, MULTIPLAYER, CEILING }

    public static Status status() {
        Minecraft client = Minecraft.getInstance();
        if (!MetalCraftConfig.lodEnabled()) return Status.DISABLED;
        if (!metal) return Status.NOT_METAL;
        if (client.options.renderDistance().get() <= MetalCraftConfig.lodNativeDistance()) return Status.RENDER_DISTANCE;
        if (client.level == null) return Status.READY;
        if (client.getSingleplayerServer() == null) return Status.MULTIPLAYER;
        if (client.level.dimensionType().hasCeiling()) return Status.CEILING;
        return session != null ? Status.ACTIVE : Status.READY;
    }

    public static LodStats stats() {
        LodSession current = session;
        return current == null ? new LodStats() : current.stats().copy();
    }

    private static void tick(Minecraft client) {
        tick(client, null);
    }

    private static void tick(Minecraft client, @Nullable LodSession previous) {
        logStatus(client);
        var level = client.level;
        var server = client.getSingleplayerServer();
        boolean wanted = limitsNativeDistance() && level != null && server != null && !level.dimensionType().hasCeiling()
            && MetalGpuDevices.current() != null;
        if (!wanted) {
            if (session != null) reset();
            return;
        }
        if (session != null && session.level != level) reset();
        if (session == null) {
            var serverLevel = server.getLevel(level.dimension());
            if (serverLevel == null) return;
            LodBlockColors colors = LodBlockColors.current();
            if (colors == null) colors = LodBlockColors.rebuild(client);
            session = new LodSession(level, serverLevel, colors, workers(), workerThreads, gpuBudget(MetalCraftConfig.lodDetail()),
                LodAtlas.requiredSize(MetalCraftConfig.lodDetail(), client.options.renderDistance().get(), MetalCraftConfig.lodNativeDistance()), previous);
        }
        session.tick();
    }

    /** Logs each change of status while in a world, so an inactive feature is explained in the log. */
    private static void logStatus(Minecraft client) {
        Status current = client.level == null ? null : status();
        if (current == loggedStatus) return;
        loggedStatus = current;
        if (current == null || current == Status.READY) return;
        switch (current) {
            case NOT_METAL -> LOGGER.warn("Metal Mod distant terrain is inactive: it needs the Metal graphics backend, but this session uses {}. "
                + "Set Video Settings > Graphics API to Default and restart.", RenderSystem.getDevice().getDeviceInfo().backendName());
            case RENDER_DISTANCE -> LOGGER.info("Metal Mod distant terrain is inactive: Render Distance {} is not larger than the native distance {}",
                client.options.renderDistance().get(), MetalCraftConfig.lodNativeDistance());
            default -> LOGGER.info("Metal Mod distant terrain: {}", current.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '));
        }
    }

    private static ExecutorService workers() {
        if (workers == null) {
            workerThreads = Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 8);
            AtomicInteger count = new AtomicInteger();
            workers = Executors.newFixedThreadPool(workerThreads, runnable -> {
                Thread thread = new Thread(runnable, "Metal Mod distant terrain " + count.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
        }
        return workers;
    }

    /** A bounded share of Metal's recommended working set; a cap, not an allocation. */
    private static long gpuBudget(int detail) {
        MetalGpuDevice device = MetalGpuDevices.current();
        long workingSet = device == null ? 0 : device.metal().recommendedWorkingSetBytes();
        return gpuBudget(workingSet, detail);
    }

    static long gpuBudget(long workingSet, int detail) {
        boolean maximum = LodSettings.clampDetail(detail) == LodSettings.MAX_DETAIL;
        if (workingSet <= 0) return (maximum ? 1024L : 512L) << 20;
        return Math.clamp(workingSet / (maximum ? 8 : 16), 256L << 20, (maximum ? 3072L : 1536L) << 20);
    }

    /** Discards distant terrain; it rebuilds on the next tick if still enabled. */
    public static void reset() {
        LodSession current = session;
        session = null;
        if (current != null) current.close();
    }

    /** Block colours depend on resource packs; rebuild everything after a reload. */
    public static void resourcesChanged() {
        LodBlockColors.invalidate();
        reset();
    }

    public static void levelChanged() { reset(); }

    public static void nativeReset() {
        LodSession current = session;
        if (current != null) current.nativeReset();
    }

    public static void sectionDirty(int chunkX, int chunkZ) {
        LodSession current = session;
        if (current != null) current.sectionDirty(chunkX, chunkZ);
    }

    public static void deviceClosed(MetalGpuDevice device) {
        reset();
        if (water != null) water.close();
        water = null;
    }

    /** Start of chunk-render preparation: upload finished nodes, select, schedule builds. */
    public static void prepare(CameraRenderState camera) {
        LodSession current = session;
        if (current == null || !limitsNativeDistance()) return;
        Minecraft client = Minecraft.getInstance();
        int atlasSize = LodAtlas.requiredSize(MetalCraftConfig.lodDetail(), client.options.renderDistance().get(), MetalCraftConfig.lodNativeDistance());
        if (!current.atlasFits(atlasSize)) {
            reset();
            tick(client, current);
            current = session;
            if (current == null) return;
        }
        long started = MetalStallProbe.begin();
        current.setGpuBudget(gpuBudget(MetalCraftConfig.lodDetail()));
        current.prepare(camera, MetalCraftConfig.lodNativeDistance(), Minecraft.getInstance().options.renderDistance().get(),
            MetalCraftConfig.lodDetail());
        MetalStallProbe.end(MetalStallProbe.Source.LOD_PREPARE, started, 0);
    }

    /** End of chunk-render preparation: add this frame's distant draws. */
    public static ChunkSectionsToRender append(ChunkSectionsToRender original, CameraRenderState camera) {
        LodSession current = session;
        return current == null || !limitsNativeDistance() ? original : current.append(original, camera);
    }

    /**
     * One sidecar for every distant water draw. Distant fluids emit only flat surfaces, so every
     * vertex shares the same upward normal and still-water flow, whatever the draw's base vertex.
     */
    static WaterMeshBinding waterMetadata() {
        if (water == null) {
            var builder = new WaterVertexMetadata.Builder();
            float[] up = {0, 0, 0, 0, 0, 1, 1, 0, 1, 1, 0, 0};
            int vertices = LodTile.CELLS * LodTile.CELLS * 4;
            for (int vertex = 0; vertex < vertices; vertex += 4) builder.putQuad(vertex, up, 0, 0, 0);
            water = new WaterMeshBinding(builder.build(vertices));
        }
        return water;
    }
}
