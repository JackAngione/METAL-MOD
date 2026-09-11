package dev.metalcraft.client.lod;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in final-mesh diagnostic, covering vanilla and Fabric renderer output alike. */
public final class LodCompilerCapture {
    public static final boolean ENABLED = Boolean.getBoolean("metalcraft.lodCompilerTest");
    private static final int MAX_QUADS = 8192;
    // Copies, sparse merge maps and output lists overlap. Reserve before copying output.
    private static final long RESERVATION = MAX_QUADS * 2048L;
    private static final long BUDGET = 64L << 20;
    private static final AtomicLong reserved = new AtomicLong();
    private static final LongAdder captured = new LongAdder(), supported = new LongAdder(), rejected = new LongAdder();
    private static final LongAdder input = new LongAdder(), output = new LongAdder(), bounded = new LongAdder();
    private static final LongAdder allInput = new LongAdder(), materialRejects = new LongAdder(), geometryRejects = new LongAdder();
    private record Watch(long section, AtomicLong count) { }
    private static volatile Watch watched;

    public static void watch(long section) { watched = new Watch(section, new AtomicLong()); }
    public static long watchedCompiles() { Watch current = watched; return current == null ? 0 : current.count.get(); }

    private LodCompilerCapture() { }

    /** Caller owns MeshData. No buffers, sprites or world references escape this call. */
    public static void capture(long section, MeshData solid, boolean onlySolid) {
        captured.increment();
        Watch current = watched;
        if (current != null && current.section == section) current.count.incrementAndGet();
        if (solid == null) { if (!onlySolid) { rejected.increment(); materialRejects.increment(); } return; }
        var state = solid.drawState();
        int count = state.vertexCount() / 4;
        allInput.add(count);
        if (!onlySolid || state.primitiveTopology() != PrimitiveTopology.QUADS || state.vertexCount() % 4 != 0
                || !state.format().equals(DefaultVertexFormat.BLOCK) || count > MAX_QUADS) {
            materialRejects.increment(); rejected.increment(); return;
        }
        long bytes;
        do {
            bytes = reserved.get();
            if (RESERVATION > BUDGET - bytes) { bounded.increment(); return; }
        } while (!reserved.compareAndSet(bytes, bytes + RESERVATION));
        try {
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
                    materialRejects.increment(); rejected.increment(); return;
                }
                // Emitted UV footprint, not a sprite identifier/gutter. Its resource generation
                // belongs to the compiled mesh; these coordinates must never survive reload.
                var footprint = new LodBakedMesh.Sprite("emitted-atlas-region", u0, v0, u1, v1);
                quads.add(new LodBakedMesh.Quad(footprint, vertices));
            }
            var simplified = new LodBakedMesh(quads).simplify(4);
            if (!simplified.supported()) { geometryRejects.increment(); rejected.increment(); return; }
            supported.increment(); input.add(simplified.originalQuads()); output.add(simplified.quads());
        } finally { reserved.addAndGet(-RESERVATION); }
    }

    public record Stats(long sections, long supportedSections, long rejectedSections, long originalQuads,
                        long simplifiedQuads, long budgetMisses, long reservedBytes, long allSolidQuads,
                        long materialRejects, long geometryRejects) { }
    public static Stats stats() {
        return new Stats(captured.sum(), supported.sum(), rejected.sum(), input.sum(), output.sum(), bounded.sum(), reserved.get(), allInput.sum(),
                materialRejects.sum(), geometryRejects.sum());
    }
}
