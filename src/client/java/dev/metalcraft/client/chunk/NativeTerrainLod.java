package dev.metalcraft.client.chunk;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import dev.metalcraft.client.MetalCraftConfig;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.lwjgl.system.MemoryUtil;

/** One LOD mesh per native section; native worker queues and uploads own every replacement. */
public final class NativeTerrainLod {
    private record Frame(double x, double y, double z, double fov, boolean enabled, int reduction, int nativeDistance) { }
    private record Candidate(long node, SectionMesh mesh, int desired, double distance,
                             NativeLodRebuildQueue.Change change) { }
    private static final Comparator<Candidate> NEAREST_FIRST = Comparator.comparingDouble(Candidate::distance)
            .thenComparingLong(Candidate::node);
    private static Frame camera = new Frame(0, 0, 0, 70, false, NativeLodSelection.DEFAULT_REDUCTION, NativeLodSelection.DEFAULT_NATIVE_DISTANCE);
    private static final NativeLodRebuildQueue rebuilds = new NativeLodRebuildQueue();
    private static int cursor;
    private static final LongAdder builds = new LongAdder(), original = new LongAdder(), reduced = new LongAdder();
    private static final LongAdder buildNanos = new LongAdder();
    private static final LongAdder geometricBuilds = new LongAdder(), movedVertices = new LongAdder();
    private NativeTerrainLod() { }

    /** Extraction thread only. Existing full-detail geometry remains installed until upload completes. */
    public static void beginFrame(SectionUpdateTracker tracker, Camera view) {
        Minecraft client = Minecraft.getInstance();
        var pos = view.position();
        camera = new Frame(pos.x, pos.y, pos.z, view.getFov(), MetalCraftConfig.nativeTerrainLod()
                && "Metal".equals(RenderSystem.getDevice().getDeviceInfo().backendName()), MetalCraftConfig.nativeLodReduction(), MetalCraftConfig.nativeQualityDistance());
        long now = System.nanoTime();
        var area = client.levelRenderer.viewArea();
        rebuilds.prune(now, (node, mesh) -> {
            var section = area == null ? null : area.getRenderSectionAt(new BlockPos(
                    SectionPos.x(node) * 16, SectionPos.y(node) * 16, SectionPos.z(node) * 16));
            return section != null && section.getSectionNode() == node && section.getSectionMesh() == mesh;
        });
        var visible = client.levelRenderer.visibleSections();
        int size = visible.size();
        if (size == 0) return;
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
            boolean scanCoarsening = Math.floorMod(index - cursor, size) < 512;
            if (current == 1 && !scanCoarsening && !rebuilds.isPending(node)) continue;
            int desired = select(node, current);
            var change = rebuilds.change(node, mesh, current, desired);
            if (!rebuilds.hasCapacity(node, change)) continue;
            boolean refine = change == NativeLodRebuildQueue.Change.REFINE;
            if (!refine && !scanCoarsening) continue;
            var dirty = tracker.getDirtyState(node);
            // Ordinary block edits already produce a fresh camera-stamped snapshot.
            if (dirty == null || dirty.getSectionNode() != node || dirty.isDirty()) continue;
            double distance = NativeLodSelection.distance(camera.x, camera.y, camera.z,
                    SectionPos.x(node), SectionPos.y(node), SectionPos.z(node));
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
        }
    }

    /** Snapshot creation runs on the extraction thread, so workers never access live camera/world state. */
    public static int snapshotCellSize(long section) {
        var area = Minecraft.getInstance().levelRenderer.viewArea();
        var renderSection = area == null ? null : area.getRenderSectionAt(new BlockPos(
                SectionPos.x(section) * 16, SectionPos.y(section) * 16, SectionPos.z(section) * 16));
        int previous = renderSection != null && renderSection.getSectionMesh() instanceof NativeLodState state
                ? state.metalcraft$cellSize() : 1;
        return select(section, previous);
    }

    private static int select(long section, int previous) {
        return NativeLodSelection.select(NativeLodSelection.distance(camera.x, camera.y, camera.z,
                SectionPos.x(section), SectionPos.y(section), SectionPos.z(section)), camera.fov, previous, camera.enabled, camera.reduction, camera.nativeDistance);
    }

    public static void reset() {
        rebuilds.clear(); cursor = 0;
        camera = new Frame(0, 0, 0, 70, false, NativeLodSelection.DEFAULT_REDUCTION, NativeLodSelection.DEFAULT_NATIVE_DISTANCE);
    }

    /** Bounded CPU-only simplification on the already scheduled native compiler worker. */
    public static void compile(SectionCompiler.Results results, SectionBufferBuilderPack builders, int cellSize) {
        ((NativeLodState)(Object)results).metalcraft$cellSize(cellSize);
        MeshData source = results.renderedLayers.get(ChunkSectionLayer.SOLID);
        if (cellSize == 1 || source == null) return;
        var state = source.drawState();
        if (state.primitiveTopology() != PrimitiveTopology.QUADS || !state.format().equals(DefaultVertexFormat.BLOCK)
                || source.indexBuffer() != null || state.vertexCount() % 4 != 0) return;
        var format = state.format();
        var layout = new NativeSurfaceMesher.Layout(format.getVertexSize(), format.getElement("Position").offset(),
                format.getElement("Color").offset(), format.getElement("UV0").offset(),
                format.getElement("UV2").offset(), format.getElement("Normal") == null ? -1 : format.getElement("Normal").offset());
        long started = System.nanoTime();
        var contacts = new java.util.ArrayList<java.nio.ByteBuffer>();
        boolean compatible = true;
        for (var entry : results.renderedLayers.entrySet()) if (entry.getKey() != ChunkSectionLayer.SOLID) {
            compatible &= entry.getValue().drawState().format().equals(format);
            contacts.add(entry.getValue().vertexBuffer());
        }
        var geometry = compatible ? NativeGeometryMesher.reduce(source.vertexBuffer(), layout, cellSize, contacts) : null;
        var result = geometry == null ? NativeSurfaceMesher.reduce(source.vertexBuffer(), layout, cellSize) : null;
        byte[] vertices = geometry != null ? geometry.vertices() : result != null ? result.vertices() : null;
        int quads = geometry != null ? geometry.quads() : result != null ? result.quads() : state.vertexCount() / 4;
        builds.increment(); original.add(state.vertexCount() / 4);
        reduced.add(quads);
        buildNanos.add(System.nanoTime() - started);
        if (vertices == null) return;
        if (geometry != null) {
            geometricBuilds.increment(); movedVertices.add(geometry.movedVertices());
            // A removed small wall may reveal geometry hidden by native voxel occlusion.
            // Keep culling conservative for the coarsened section.
            results.visibilitySet.setAll(true);
        }
        // Use the native worker's own arena. No per-section GPU allocation or extra retained tier.
        var arena = builders.buffer(ChunkSectionLayer.SOLID);
        long pointer = arena.reserve(vertices.length);
        MemoryUtil.memByteBuffer(pointer, vertices.length).put(vertices);
        var replacement = new MeshData(arena.build(), new MeshData.DrawState(format, quads * 4,
                quads * 6, state.primitiveTopology(), state.indexType()));
        results.renderedLayers.put(ChunkSectionLayer.SOLID, replacement);
        source.close();
    }

    public record Stats(long builds, long originalQuads, long outputQuads, long workerNanos,
                        long geometricBuilds, long movedVertices) { }
    public static Stats stats() { return new Stats(builds.sum(), original.sum(), reduced.sum(), buildNanos.sum(),
            geometricBuilds.sum(), movedVertices.sum()); }
}
