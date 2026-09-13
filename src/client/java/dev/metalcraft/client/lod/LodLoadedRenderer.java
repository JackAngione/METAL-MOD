package dev.metalcraft.client.lod;

import dev.metalcraft.client.metal.MetalGpuDevice;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jspecify.annotations.Nullable;

/** Render-thread owner of opt-in loaded solid replacements. */
public final class LodLoadedRenderer {
    public static final boolean AVAILABLE = LodCapabilities.GEOMETRY_AVAILABLE;
    /** Explicit measurement overhead; ordinary disabled play does not census every terrain draw. */
    public static final boolean TERRAIN_CENSUS = Boolean.getBoolean("metalcraft.lodTerrainCensus");
    private static final double[] EXACT_SURFACE_ERRORS = {0, 0, 0, 0, 0};
    private static @Nullable LodLoadedRenderer active;
    private final MetalGpuDevice device;
    private final LodMeshResidency<LodWorldMesh> residency = new LodMeshResidency<>(128L << 20);
    private final Map<LodMeshResidency.Key, LodCapturedMesh> owners = new HashMap<>();
    private final Map<RenderSection, Draw> selected = new IdentityHashMap<>();
    private final Map<RenderSection, Draw> previous = new IdentityHashMap<>();
    private LodSettings settings = LodSettings.defaults();
    private long uploads, uploadedBytes, draws, originalTriangles, replacementTriangles, staleFallbacks, uploadFailures;
    private long frameUploadBytes, prepareNanos;
    private int frameSelected;
    private long tier1Draws, tier2Draws, tier3Draws, tier4Draws;
    private long shadowDraws, shadowOriginalTriangles, shadowReplacementTriangles;
    private long terrainDraws, terrainInputTriangles, terrainRenderedTriangles, distantDraws, distantInputTriangles, distantRenderedTriangles;
    private static volatile Stats published = new Stats(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);

    public record Stats(long uploads, long uploadedBytes, long draws, long originalTriangles, long replacementTriangles,
                        long staleFallbacks, long uploadFailures, long chargedBytes, int residentMeshes, int retiredMeshes,
                        long frameUploadBytes, long prepareNanos, int frameSelected,
                        long tier1Draws, long tier2Draws, long tier3Draws, long tier4Draws,
                        long shadowDraws, long shadowOriginalTriangles, long shadowReplacementTriangles,
                        long terrainDraws, long terrainInputTriangles, long terrainRenderedTriangles,
                        long distantDraws, long distantInputTriangles, long distantRenderedTriangles) { }

    private LodLoadedRenderer(MetalGpuDevice device) { this.device = device; }

    public static void beginFrame(@Nullable MetalGpuDevice device, LodSettings settings) {
        if (!AVAILABLE) return;
        if (!settings.enabled() && !TERRAIN_CENSUS) {
            if (active == null) return;
            if (active.device != device) {
                active.residency.close(); published = active.snapshot(); active = null;
            } else if (active.settings.enabled() || active.residency.chargedBytes() != 0) {
                // Revoke draw ownership immediately, then keep polling in-flight retirements.
                active.settings = settings;
                active.selected.clear(); active.previous.clear(); active.owners.clear();
                active.frameUploadBytes = 0; active.frameSelected = 0; active.prepareNanos = 0;
                active.residency.beginFrame(device.completedResourceSubmission());
                active.residency.invalidate(key -> true);
                published = active.snapshot();
            }
            return;
        }
        long started = System.nanoTime();
        try {
            if (active != null && active.device != device) { active.residency.close(); active = null; }
            if (device == null) return;
            if (active == null) active = new LodLoadedRenderer(device);
            published = active.snapshot();
            active.settings = settings;
            active.previous.clear();
            active.previous.putAll(active.selected);
            active.selected.clear();
            active.frameUploadBytes = 0;
            active.frameSelected = 0;
            active.prepareNanos = 0;
            active.residency.beginFrame(device.completedResourceSubmission());
            long workingSet = device.metal().recommendedWorkingSetBytes();
            long budget = settings.meshBudgetBytes(workingSet, device.metal().currentAllocatedBytes(), active.residency.chargedBytes());
            if (settings.enabled() && settings.diskCache() && settings.horizonChunks() > 16) budget = Math.max(1, budget / 2);
            active.residency.setBudget(budget);
            active.residency.invalidate(key -> !settings.enabled() || active.owners.get(key) == null
                    || active.owners.get(key).currentMesh() == null);
            active.owners.keySet().removeIf(key -> !active.residency.contains(key));
        } finally {
            long elapsed = System.nanoTime() - started;
            if (active != null) active.prepareNanos += elapsed;
            dev.metalcraft.client.metal.MetalStallProbe.record(
                    dev.metalcraft.client.metal.MetalStallProbe.Source.LOD_PREPARE, elapsed, 1, 0);
        }
    }

    /** Called before the dispatcher lock. Visible sections have already passed the game's culling. */
    public static void prepare(List<RenderSection> sections, CameraRenderState camera) {
        if (active == null || !active.settings.enabled()) return;
        long started = System.nanoTime(), beforeUploads = active.frameUploadBytes;
        try {
            if (active.device.preflightLodTerrain()) active.prepareVisible(sections, camera);
        } finally {
            long elapsed = System.nanoTime() - started;
            active.prepareNanos += elapsed;
            dev.metalcraft.client.metal.MetalStallProbe.record(
                    dev.metalcraft.client.metal.MetalStallProbe.Source.LOD_PREPARE, elapsed, 1,
                    active.frameUploadBytes - beforeUploads);
        }
    }

    private record Candidate(int index, RenderSection section, SectionMesh owner, LodCapturedMesh capture, double distance) { }

    private void prepareVisible(List<RenderSection> sections, CameraRenderState camera) {
        var candidates = new ArrayList<Candidate>();
        int count = sections.size();
        int[] desired = new int[count], before = new int[count], available = new int[count];
        java.util.Arrays.fill(available, 1);
        var positions = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(count);
        positions.defaultReturnValue(-1);
        double fov = 2 * Math.atan(1 / Math.abs(camera.projectionMatrix.m11()));
        int height = Minecraft.getInstance().gameRenderer.mainRenderTarget().height;
        var view = camera.viewRotationMatrix;
        for (int index = 0; index < count; index++) {
            RenderSection section = sections.get(index);
            positions.put(section.getSectionNode(), index);
            SectionMesh mesh = section.getSectionMesh();
            if (!(mesh instanceof LodMeshSource source)) continue;
            var capture = source.metalcraft$lodCandidate();
            if (capture == null || capture.currentMesh() == null) continue;
            var origin = section.getRenderOrigin();
            double x = origin.getX() + 8 - camera.pos.x, y = origin.getY() + 8 - camera.pos.y, z = origin.getZ() + 8 - camera.pos.z;
            double dx = Math.max(Math.abs(x) - 8, 0), dy = Math.max(Math.abs(y) - 8, 0), dz = Math.max(Math.abs(z) - 8, 0);
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double nearestDepth = -(x * view.m02() + y * view.m12() + z * view.m22())
                    - 8 * (Math.abs(view.m02()) + Math.abs(view.m12()) + Math.abs(view.m22()));
            Draw last = previous.get(section);
            before[index] = last != null && last.owner == mesh ? last.tier : 0;
            desired[index] = LodSelector.select(true, nearestDepth, distance, .05, height, fov, settings,
                    EXACT_SURFACE_ERRORS, before[index]);
            if (desired[index] > 0) candidates.add(new Candidate(index, section, mesh, capture, distance));
        }
        int[][] neighbors = new int[count][];
        for (int index = 0; index < count; index++) {
            long node = sections.get(index).getSectionNode();
            int x = net.minecraft.core.SectionPos.x(node), y = net.minecraft.core.SectionPos.y(node), z = net.minecraft.core.SectionPos.z(node);
            int[] adjacent = new int[3];
            int length = 0;
            for (int axis = 0; axis < 3; axis++) {
                int neighbor = positions.get(net.minecraft.core.SectionPos.asLong(x + (axis == 0 ? 1 : 0),
                        y + (axis == 1 ? 1 : 0), z + (axis == 2 ? 1 : 0)));
                if (neighbor >= 0) adjacent[length++] = neighbor;
            }
            neighbors[index] = java.util.Arrays.copyOf(adjacent, length);
        }
        var adjacency = LodSelector.adjacency(neighbors);
        int[] usefulTiers = LodSelector.balance(desired, adjacency);
        candidates.sort(Comparator.comparingDouble(Candidate::distance));
        long allowance = switch (settings.backgroundWork()) { case LOW -> 256L << 10; case BALANCED -> 1L << 20; case HIGH -> 2L << 20; };
        int remaining = switch (settings.backgroundWork()) { case LOW -> 2; case BALANCED -> 4; case HIGH -> 8; };
        for (var candidate : candidates) {
            var capture = candidate.capture();
            for (int tier = 1; tier <= usefulTiers[candidate.index()]; tier++) {
                var mesh = capture.mesh(tier);
                if (mesh == null || mesh.quads() == 0 || mesh.quads() >= mesh.originalQuads()) continue;
                var key = new LodMeshResidency.Key(capture.key(), capture.residencyTier(tier));
                if (!residency.contains(key) && remaining > 0) {
                    long bytes = LodWorldMesh.bytes(mesh);
                    if (bytes <= allowance - frameUploadBytes) {
                        try {
                            if (residency.upload(key, bytes, () -> LodWorldMesh.upload(device.metal(), mesh))) {
                                owners.put(key, capture);
                                uploads++; uploadedBytes += bytes; frameUploadBytes += bytes; remaining--;
                            }
                        } catch (RuntimeException failure) { uploadFailures++; remaining--; }
                    }
                }
            }
        }
        // Resolve availability after all admission/eviction, then relax every visible boundary.
        for (var candidate : candidates) for (int tier = 1; tier <= 4; tier++) {
            if (residency.contains(new LodMeshResidency.Key(candidate.capture().key(), candidate.capture().residencyTier(tier))))
                available[candidate.index()] |= 1 << tier;
        }
        int[] resolved = LodSelector.resolveLoaded(desired, before, available, adjacency, settings.smoothTransitions());
        for (int index = 0; index < count; index++) {
            if (resolved[index] > desired[index] || (available[index] & (1 << resolved[index])) == 0)
                throw new IllegalStateException("LOD selection exceeded available/error-safe tiers");
            for (int neighbor : neighbors[index]) if (Math.abs(resolved[index] - resolved[neighbor]) > 1)
                throw new IllegalStateException("LOD selection left an unbalanced visible boundary");
        }
        for (var candidate : candidates) {
            int tier = resolved[candidate.index()];
            if (tier > 0) {
                var key = new LodMeshResidency.Key(candidate.capture().key(), candidate.capture().residencyTier(tier));
                selected.put(candidate.section(), new Draw(this, candidate.section(), candidate.owner(), candidate.capture(), key, tier));
            }
        }
        // Admission may have evicted an earlier selection. It must retain its ordinary draw.
        selected.values().removeIf(draw -> !residency.contains(draw.key));
        owners.keySet().removeIf(key -> !residency.contains(key));
        frameSelected = selected.size();
    }

    public static @Nullable Draw selected(RenderSection section, SectionMesh mesh) {
        if (active == null) return null;
        Draw draw = active.selected.get(section);
        return draw != null && draw.owner == mesh ? draw : null;
    }

    public static boolean trackingDraws() {
        return active != null && (active.settings.enabled() || TERRAIN_CENSUS);
    }

    public static boolean distant(RenderSection section, CameraRenderState camera) {
        if (active == null) return false;
        var origin = section.getRenderOrigin();
        double x = Math.max(Math.abs(origin.getX() + 8 - camera.pos.x) - 8, 0);
        double y = Math.max(Math.abs(origin.getY() + 8 - camera.pos.y) - 8, 0);
        double z = Math.max(Math.abs(origin.getZ() + 8 - camera.pos.z) - 8, 0);
        double radius = active.settings.fullDetailChunks() * 16.0;
        return x*x + y*y + z*z >= radius*radius;
    }

    /** Main-world terrain only; shadow draws have separate counters. */
    public static void encodedTerrain(int originalIndices, int renderedIndices, boolean distant) {
        if (active == null) return;
        active.terrainDraws++;
        active.terrainInputTriangles += originalIndices / 3;
        active.terrainRenderedTriangles += renderedIndices / 3;
        if (distant) {
            active.distantDraws++;
            active.distantInputTriangles += originalIndices / 3;
            active.distantRenderedTriangles += renderedIndices / 3;
        }
    }

    /** Exact-position opaque casters can use any resident tier inside independently culled light volumes.
     * Never allocates/uploads here: shadow collection owns the dispatcher lock. */
    public static @Nullable LodWorldMesh borrowShadow(MetalGpuDevice device, RenderSection section, SectionMesh mesh) {
        if (active == null || active.device != device || !active.settings.enabled()
                || !(mesh instanceof LodMeshSource source)) return null;
        var capture = source.metalcraft$lodCandidate();
        if (capture == null || capture.currentMesh() == null) return null;
        for (int tier = 4; tier > 0; tier--) {
            var key = new LodMeshResidency.Key(capture.key(), capture.residencyTier(tier));
            if (active.residency.contains(key)) return new Draw(active, section, mesh, capture, key, tier).borrow(device);
        }
        return null;
    }

    public static void encodedShadow(MetalGpuDevice device, LodWorldMesh mesh) {
        if (active != null && active.device == device) {
            active.shadowDraws++;
            active.shadowOriginalTriangles += mesh.originalIndexCount() / 3;
            active.shadowReplacementTriangles += mesh.indexCount() / 3;
        }
    }

    public static final class Draw {
        private final LodLoadedRenderer renderer;
        private final RenderSection section;
        private final SectionMesh owner;
        private final LodCapturedMesh capture;
        private final LodMeshResidency.Key key;
        private final int tier;
        private Draw(LodLoadedRenderer renderer, RenderSection section, SectionMesh owner, LodCapturedMesh capture, LodMeshResidency.Key key, int tier) {
            this.renderer = renderer; this.section = section; this.owner = owner; this.capture = capture; this.key = key; this.tier = tier;
        }

        /** Pipeline/binding preflight precedes this final identity check and timeline reservation. */
        public @Nullable LodWorldMesh borrow(MetalGpuDevice device) {
            var origin = section.getRenderOrigin();
            var terrain = key.terrain();
            if (renderer != active || renderer.device != device || !renderer.settings.enabled()
                    || section.getSectionMesh() != owner || capture.currentMesh() == null
                    || origin.getX() != terrain.x() * 16 || origin.getY() != terrain.y() * 16 || origin.getZ() != terrain.z() * 16
                    || !renderer.residency.contains(key)) {
                renderer.staleFallbacks++;
                return null;
            }
            return renderer.residency.use(key, device.reserveResourceSubmission());
        }

        public void encoded(LodWorldMesh mesh) {
            encodedTerrain(mesh.originalIndexCount(), mesh.indexCount(), true);
            renderer.draws++;
            switch (tier) { case 1 -> renderer.tier1Draws++; case 2 -> renderer.tier2Draws++;
                case 3 -> renderer.tier3Draws++; case 4 -> renderer.tier4Draws++; }
            renderer.originalTriangles += mesh.originalIndexCount() / 3;
            renderer.replacementTriangles += mesh.indexCount() / 3;
        }
    }

    public static Stats stats() {
        return published;
    }

    /** Point-in-time benchmark sample; caller must be on the render thread. */
    public static Stats sample() { return active == null ? published : active.snapshot(); }

    private Stats snapshot() {
        return new Stats(uploads,uploadedBytes,draws,originalTriangles,replacementTriangles,staleFallbacks,uploadFailures,
                residency.chargedBytes(),residency.residentCount(),residency.retiredCount(),frameUploadBytes,prepareNanos,frameSelected,
                tier1Draws,tier2Draws,tier3Draws,tier4Draws,shadowDraws,shadowOriginalTriangles,shadowReplacementTriangles,
                terrainDraws,terrainInputTriangles,terrainRenderedTriangles,distantDraws,distantInputTriangles,distantRenderedTriangles);
    }

    public static void close(MetalGpuDevice device) {
        if (active != null && active.device == device) { active.residency.close(); active = null; }
    }
}
