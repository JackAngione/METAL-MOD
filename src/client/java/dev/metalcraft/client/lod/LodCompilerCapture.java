package dev.metalcraft.client.lod;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import org.jspecify.annotations.Nullable;

/** Bounded final-mesh capture for experimental LOD and diagnostics, including Fabric renderer output. */
public final class LodCompilerCapture {
    public static final boolean ENABLED = Boolean.getBoolean("metalcraft.lodCompilerTest") || LodCapabilities.EXPERIMENTAL;
    private static final boolean DIAGNOSTIC = Boolean.getBoolean("metalcraft.lodCompilerTest");
    private static volatile boolean activeCapture = DIAGNOSTIC;
    public static final LodRevisionTracker REVISIONS = new LodRevisionTracker(32768);
    private static final int MAX_QUADS = 8192;
    // Copies, sparse merge maps and output lists overlap. Reserve before copying output.
    private static final long RESERVATION = MAX_QUADS * 2048L;
    private static final long BUDGET = 64L << 20;
    private static volatile long buildBudget = BUDGET;
    private static final AtomicLong reserved = new AtomicLong();
    private static final AtomicLong retained = new AtomicLong();
    private static final LongAdder retainedCount = new LongAdder(), transferred = new LongAdder(), closed = new LongAdder();
    private static final LongAdder stale = new LongAdder(), retentionMisses = new LongAdder();
    private static final LongAdder captured = new LongAdder(), supported = new LongAdder(), rejected = new LongAdder();
    private static final LongAdder input = new LongAdder(), output = new LongAdder(), bounded = new LongAdder();
    private static final LongAdder allInput = new LongAdder(), materialRejects = new LongAdder(), geometryRejects = new LongAdder();
    private static final LongAdder mixedSupported = new LongAdder();
    private static final LongAdder buildAttempts = new LongAdder(), buildNanos = new LongAdder();
    private static final AtomicLong maxBuildNanos = new AtomicLong(), peakReserved = new AtomicLong(), peakRetained = new AtomicLong();
    private record Watch(long section, AtomicLong count, java.util.concurrent.atomic.AtomicReference<LodRevisionTracker.Ticket> ticket) { }
    private static volatile Watch watched;

    public static void watch(long section) { watched = new Watch(section, new AtomicLong(), new java.util.concurrent.atomic.AtomicReference<>()); }
    public static long watchedCompiles() { Watch current = watched; return current == null ? 0 : current.count.get(); }
    public static LodRevisionTracker.@Nullable Ticket watchedTicket() { Watch current = watched; return current == null ? null : current.ticket.get(); }
    public static void transferred() { transferred.increment(); }

    private LodCompilerCapture() { }

    public static boolean capturing() { return ENABLED && activeCapture; }

    /** Frame-boundary adoption. Revocation also releases results from workers admitted before disable. */
    public static boolean configure(LodSettings settings) {
        buildBudget = RESERVATION * switch (settings.backgroundWork()) { case LOW -> 1; case BALANCED -> 2; case HIGH -> 4; };
        boolean next = DIAGNOSTIC || LodCapabilities.EXPERIMENTAL && settings.enabled();
        if (activeCapture == next) return false;
        activeCapture = next;
        REVISIONS.resources();
        return true;
    }

    /** Caller owns MeshData; only a bounded immutable copy with extraction identity can escape. */
    public static @Nullable LodCapturedMesh capture(long section, MeshData solid, boolean onlySolid,
            LodRevisionTracker.@Nullable Ticket ticket) {
        if (!capturing()) return null;
        captured.increment();
        Watch current = watched;
        if (current != null && current.section == section) { current.ticket.set(ticket); current.count.incrementAndGet(); }
        if (solid == null) { if (!onlySolid) { rejected.increment(); materialRejects.increment(); } return null; }
        var state = solid.drawState();
        int count = state.vertexCount() / 4;
        allInput.add(count);
        // Layers have independent buffers and draws. A valid solid batch can be replaced
        // while cutout/translucent companions retain their exact ordinary meshes/order.
        if (state.primitiveTopology() != PrimitiveTopology.QUADS || state.vertexCount() % 4 != 0
                || !state.format().equals(DefaultVertexFormat.BLOCK) || count > MAX_QUADS) {
            materialRejects.increment(); rejected.increment(); return null;
        }
        long bytes;
        do {
            bytes = reserved.get();
            if (RESERVATION > buildBudget - bytes) { bounded.increment(); return null; }
        } while (!reserved.compareAndSet(bytes, bytes + RESERVATION));
        peakReserved.accumulateAndGet(bytes + RESERVATION, Math::max);
        long started = System.nanoTime();
        try {
            LodAtlas atlas = LodAtlas.current();
            var format = state.format();
            int stride = format.getVertexSize();
            int position = format.getElement("Position").offset(), color = format.getElement("Color").offset();
            int uv = format.getElement("UV0").offset(), light = format.getElement("UV2").offset();
            var data = solid.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
            int start = data.position();
            List<LodBakedMesh.Quad> quads = new ArrayList<>(count);
            for (int q = 0; q < count; q++) {
                List<LodBakedMesh.Vertex> vertices = new ArrayList<>(4);
                float u0 = Float.POSITIVE_INFINITY, v0 = u0, u1 = Float.NEGATIVE_INFINITY, v1 = u1;
                for (int i = 0; i < 4; i++) {
                    int offset = start + (q * 4 + i) * stride;
                    float u = data.getFloat(offset + uv), v = data.getFloat(offset + uv + 4);
                    vertices.add(new LodBakedMesh.Vertex(data.getFloat(offset + position), data.getFloat(offset + position + 4),
                            data.getFloat(offset + position + 8), u, v, data.getInt(offset + color), data.getInt(offset + light)));
                    u0 = Math.min(u0, u); u1 = Math.max(u1, u); v0 = Math.min(v0, v); v1 = Math.max(v1, v);
                }
                if (!(u0 >= 0 && v0 >= 0 && u1 <= 1 && v1 <= 1 && u1 > u0 && v1 > v0)) {
                    materialRejects.increment(); rejected.increment(); return null;
                }
                var sprite = atlas.resolve(u0, v0, u1, v1);
                if (sprite == null) { materialRejects.increment(); rejected.increment(); return null; }
                quads.add(new LodBakedMesh.Quad(sprite, vertices));
            }
            var baked = new LodBakedMesh(quads);
            var simplified = baked.simplify(4);
            if (!simplified.supported()) { geometryRejects.increment(); rejected.increment(); return null; }
            supported.increment(); input.add(simplified.originalQuads()); output.add(simplified.quads());
            if (!onlySolid) mixedSupported.increment();
            if (simplified.quads() >= simplified.originalQuads()) return null;
            if (atlas != LodAtlas.current() || ticket == null || !ticket.current() || net.minecraft.core.SectionPos.asLong(
                    ticket.key().x(), ticket.key().y(), ticket.key().z()) != section) {
                stale.increment(); return null;
            }
            // All construction stays on the admitted compiler worker. Uploading and
            // selection never rerun simplification while the renderer holds a lock.
            var tiers = LodCapabilities.EXPERIMENTAL
                    ? List.of(baked.simplify(1), baked.simplify(2), baked.simplify(3), simplified)
                    : java.util.Collections.nCopies(4, simplified);
            // Retained graph: four source vertices/quad, immutable quad/list, at most
            // four rectangles and four list references. 1 KiB per input quad bounds
            // this even with uncompressed references; temporary merge maps are charged
            // separately by the larger build reservation and do not escape the worker.
            long retainedBytes = 4096L + count * 1024L;
            long used;
            do {
                used = retained.get();
                if (retainedBytes > BUDGET - used) { retentionMisses.increment(); return null; }
            } while (!retained.compareAndSet(used, used + retainedBytes));
            peakRetained.accumulateAndGet(used + retainedBytes, Math::max);
            retainedCount.increment();
            try {
                var candidate = new LodCapturedMesh(ticket, tiers, atlas, () -> {
                    retained.addAndGet(-retainedBytes);
                    retainedCount.decrement();
                    closed.increment();
                });
                return candidate;
            } catch (RuntimeException | Error error) {
                retained.addAndGet(-retainedBytes);
                retainedCount.decrement();
                throw error;
            }
        } finally {
            long elapsed = System.nanoTime() - started;
            buildAttempts.increment();
            buildNanos.add(elapsed);
            maxBuildNanos.accumulateAndGet(elapsed, Math::max);
            reserved.addAndGet(-RESERVATION);
        }
    }

    public record Stats(long sections, long supportedSections, long rejectedSections, long originalQuads,
                        long simplifiedQuads, long budgetMisses, long reservedBytes, long allSolidQuads,
                        long materialRejects, long geometryRejects, long retainedBytes, long retainedCandidates,
                        long transferredCandidates, long closedCandidates, long staleCandidates, long retentionMisses,
                        int trackedSections, long mixedSupportedSections, long buildAttempts, long buildNanos,
                        long maxBuildNanos, long peakReservedBytes, long peakRetainedBytes) { }
    public static Stats stats() {
        return new Stats(captured.sum(), supported.sum(), rejected.sum(), input.sum(), output.sum(), bounded.sum(), reserved.get(), allInput.sum(),
                materialRejects.sum(), geometryRejects.sum(), retained.get(), retainedCount.sum(), transferred.sum(), closed.sum(),
                stale.sum(), retentionMisses.sum(), REVISIONS.trackedSections(), mixedSupported.sum(),
                buildAttempts.sum(), buildNanos.sum(), maxBuildNanos.get(), peakReserved.get(), peakRetained.get());
    }
}
