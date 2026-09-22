package dev.metalcraft.client.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.MetalCraftConfig;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import dev.metalcraft.client.mixin.ViewAreaAccessor;
import net.minecraft.core.SectionPos;

/** One LOD mesh per native section; native worker queues and uploads own every replacement. */
public final class NativeTerrainLod {
    private record Frame(double x, double y, double z, double fov, boolean enabled, int reduction, int nativeDistance,
                         NativeLodSelection.Policy policy) { }
    private record Candidate(long node, SectionMesh mesh, int desired, double distance,
                             NativeLodRebuildQueue.Change change) { }
    private static final Comparator<Candidate> NEAREST_FIRST = Comparator.comparingDouble(Candidate::distance)
            .thenComparingLong(Candidate::node);
    private static volatile Frame camera = initialFrame();
    private static final NativeLodRebuildQueue rebuilds = new NativeLodRebuildQueue();
    private static int cursor;
    private static long frames, requests;
    private static final LongAdder builds = new LongAdder();
    private static final LongAdder buildNanos = new LongAdder();
    private static final LongAdder shellBuilds = new LongAdder(), shellBlocks = new LongAdder(), shellQuads = new LongAdder();
    private static final LongAdder shellFluidBlocks = new LongAdder(), shellFluidQuads = new LongAdder();
    private NativeTerrainLod() { }

    private static Frame initialFrame() {
        return new Frame(0,0,0,70,false,NativeLodSelection.DEFAULT_REDUCTION,NativeLodSelection.DEFAULT_NATIVE_DISTANCE,
                NativeLodSelection.policy(70,false,NativeLodSelection.DEFAULT_REDUCTION,NativeLodSelection.DEFAULT_NATIVE_DISTANCE));
    }

    /** Extraction thread only. Existing full-detail geometry remains installed until upload completes. */
    public static void beginFrame(SectionUpdateTracker tracker, Camera view) {
        frames++;
        Minecraft client = Minecraft.getInstance();
        var pos = view.position();
        boolean enabled=MetalCraftConfig.nativeTerrainLod() && "Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName());
        int reduction=MetalCraftConfig.nativeLodReduction(), nativeDistance=MetalCraftConfig.nativeQualityDistance();
        double fov=view.getFov();
        Frame previous=camera;
        var policy=previous.fov==fov && previous.enabled==enabled && previous.reduction==reduction && previous.nativeDistance==nativeDistance
                ? previous.policy : NativeLodSelection.policy(fov,enabled,reduction,nativeDistance);
        camera = new Frame(pos.x,pos.y,pos.z,fov,enabled,reduction,nativeDistance,policy);
        long now = System.nanoTime();
        var area = client.levelRenderer.viewArea();
        rebuilds.prune(now, (node, mesh) -> {
            var section = area == null ? null : ((ViewAreaAccessor)area).metalcraft$sections().getValue(node);
            return section != null && section.getSectionNode() == node && section.getSectionMesh() == mesh;
        });
        var visible = client.levelRenderer.visibleSections();
        int size = visible.size();
        if (size == 0) return;
        cursor = Math.floorMod(cursor, size);
        // Visibility order can be stale after movement. Inspect all installed coarse
        // sections for refinement, keeping only the nearest eight eligible candidates.
        // Coarsening remains a rotating, bounded scan and has separate queue capacity.
        var refinements = new PriorityQueue<Candidate>(8, NEAREST_FIRST.reversed());
        var coarsenings = new PriorityQueue<Candidate>(4, NEAREST_FIRST.reversed());
        for (int index = 0; index < size; index++) {
            var section = visible.get(index);
            long node = section.getSectionNode();
            SectionMesh mesh = section.getSectionMesh();
            if (!(mesh instanceof NativeLodState state) || mesh == CompiledSectionMesh.UNCOMPILED) continue;
            int current = state.metalcraft$cellSize();
            // Empty native sections need no proxy. With horizontal selection, many
            // sky/underground sections tie at the nearest distance and can otherwise
            // monopolize the bounded coarsening queue. Empty shells still refine:
            // their removed cave geometry may become visible on approach.
            if (current == 1 && !mesh.hasRenderableLayers() && mesh.getRenderableBlockEntities().isEmpty()) continue;
            int scanOffset = index - cursor;
            if (scanOffset < 0) scanOffset += size;
            boolean scanCoarsening = scanOffset < 512;
            if (current == 1 && !scanCoarsening && !rebuilds.isPending(node)) continue;
            double distance = distanceSquared(camera,node);
            int desired = camera.policy.selectSquared(distance,current);
            var change = rebuilds.change(node, mesh, current, desired);
            if (!rebuilds.hasCapacity(node, change)) continue;
            boolean refine = change == NativeLodRebuildQueue.Change.REFINE;
            if (!refine && !scanCoarsening) continue;
            var dirty = tracker.getDirtyState(node);
            // Ordinary block edits already produce a fresh camera-stamped snapshot.
            if (dirty == null || dirty.getSectionNode() != node || dirty.isDirty()) continue;
            var candidates = refine ? refinements : coarsenings;
            int budget = refine ? 8 : 4;
            if (candidates.size() == budget && distance >= candidates.peek().distance) continue;
            if (candidates.size() == budget) candidates.poll();
            candidates.add(new Candidate(node, mesh, desired, distance, change));
        }
        schedule(refinements, tracker, now);
        schedule(coarsenings, tracker, now);
        cursor = Math.floorMod(cursor + 512, size);
    }

    private static void schedule(PriorityQueue<Candidate> candidates, SectionUpdateTracker tracker, long now) {
        var ordered = candidates.toArray(Candidate[]::new);
        java.util.Arrays.sort(ordered, NEAREST_FIRST);
        for (var candidate : ordered) {
            if (!rebuilds.hasCapacity(candidate.node, candidate.change)) continue;
            tracker.getDirtyState(candidate.node).setDirty(false);
            rebuilds.requested(candidate.node, candidate.mesh, candidate.desired, now);
            requests++;
        }
    }

    /** Snapshot creation runs on the extraction thread, so workers never access live camera/world state. */
    public static int snapshotCellSize(long section) {
        var area = Minecraft.getInstance().levelRenderer.viewArea();
        var renderSection = area == null ? null : ((ViewAreaAccessor)area).metalcraft$sections().getValue(section);
        int previous = renderSection != null && renderSection.getSectionMesh() instanceof NativeLodState state
                ? state.metalcraft$cellSize() : 1;
        return select(section, previous);
    }

    private static int select(long section, int previous) {
        Frame frame=camera;
        return frame.policy.selectSquared(distanceSquared(frame,section),previous);
    }

    private static double distanceSquared(Frame frame,long section) {
        // Native quality is a horizontal chunk radius, including terrain above/below the camera.
        return NativeLodSelection.distanceSquared(frame.x,SectionPos.y(section)*16.0+8,frame.z,
                SectionPos.x(section),SectionPos.y(section),SectionPos.z(section));
    }

    /** Restore texture detail immediately on approach/zoom while native mesh refinement is queued. */
    public static int textureMip(long section, int installedCell) {
        if (installedCell<=1) return 0;
        int cell=Math.min(installedCell,select(section,installedCell));
        return cell>=4 ? 2 : cell>=2 ? 1 : 0;
    }

    public static void reset() {
        rebuilds.clear(); cursor = 0;
        camera = initialFrame();
    }

    public static void recordShell(int blocks, int quads, int fluidBlocks, int fluidQuads, long nanos) {
        builds.increment(); buildNanos.add(nanos);
        shellBuilds.increment(); shellBlocks.add(blocks); shellQuads.add(quads);
        shellFluidBlocks.add(fluidBlocks); shellFluidQuads.add(fluidQuads);
    }

    public record Stats(long builds, long workerNanos, long shellBuilds, long shellBlocks, long shellQuads,
                        long selectionFrames, long rebuildRequests, long shellFluidBlocks, long shellFluidQuads) { }
    public static Stats stats() { return new Stats(builds.sum(), buildNanos.sum(), shellBuilds.sum(), shellBlocks.sum(), shellQuads.sum(), frames, requests,
            shellFluidBlocks.sum(), shellFluidQuads.sum()); }
}
