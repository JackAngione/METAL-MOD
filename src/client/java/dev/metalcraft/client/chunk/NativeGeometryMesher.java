package dev.metalcraft.client.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded vertex clustering of the actual solid terrain surface, including its silhouette. */
public final class NativeGeometryMesher {
    private static final int EDGE = 17, GRID = EDGE * EDGE * EDGE;
    public record Reduced(byte[] vertices, int originalQuads, int quads, int movedVertices) { }
    private static final class Triangle {
        int winding;
        int positive = -1, negative = -1;
        void add(int orientation, int source) {
            winding += orientation;
            if (orientation > 0 && positive < 0) positive = source;
            if (orientation < 0 && negative < 0) negative = source;
        }
    }
    private NativeGeometryMesher() { }

    /** Non-solid layers are only read to protect their contact neighborhoods; never rewritten. */
    public static Reduced reduce(ByteBuffer source, NativeSurfaceMesher.Layout layout, int cell,
                                 List<ByteBuffer> protectedLayers) {
        if (cell != 2 && cell != 4 && cell != 8 && cell != 16) return null;
        int stride = layout.stride(), quadBytes = stride * 4, count = source.remaining() / quadBytes;
        if (source.remaining() % quadBytes != 0 || count < 2 || count > NativeSurfaceMesher.MAX_QUADS) return null;
        ByteBuffer data = source.slice().order(ByteOrder.nativeOrder());
        boolean[] candidates = new boolean[count], pinned = new boolean[GRID];
        for (int z = 0; z <= 16; z++) for (int y = 0; y <= 16; y++) for (int x = 0; x <= 16; x++)
            if (x == 0 || y == 0 || z == 0 || x == 16 || y == 16 || z == 16) pinned[id(x, y, z)] = true;
        for (int q = 0; q < count; q++) {
            candidates[q] = NativeSurfaceMesher.unitFace(data, q * quadBytes, layout);
            if (!candidates[q] && !protect(data, q * quadBytes, layout, pinned)) return null;
        }
        int protectedBytes = 0;
        for (ByteBuffer layer : protectedLayers) {
            protectedBytes += layer.remaining();
            if (protectedBytes > NativeSurfaceMesher.MAX_QUADS * quadBytes || layer.remaining() % quadBytes != 0) return null;
            ByteBuffer vertices = layer.slice().order(ByteOrder.nativeOrder());
            for (int offset = 0; offset < vertices.limit(); offset += quadBytes)
                if (!protect(vertices, offset, layout, pinned)) return null;
        }
        int[] mapped = new int[GRID];
        for (int z = 0; z <= 16; z++) for (int y = 0; y <= 16; y++) for (int x = 0; x <= 16; x++) {
            int vertex = id(x, y, z);
            mapped[vertex] = pinned[vertex] ? vertex : id(snap(x, cell), snap(y, cell), snap(z, cell));
        }
        int[] corners = new int[count * 4];
        byte[] rotation = new byte[count], retained = new byte[count];
        Map<Long, Triangle> triangles = new LinkedHashMap<>();
        int moved = 0;
        for (int q = 0; q < count; q++) {
            if (!candidates[q]) continue;
            for (int i = 0; i < 4; i++) {
                int offset = q * quadBytes + i * stride + layout.position();
                int vertex = id((int)data.getFloat(offset), (int)data.getFloat(offset + 4), (int)data.getFloat(offset + 8));
                corners[q * 4 + i] = mapped[vertex];
                if (vertex != mapped[vertex]) moved++;
            }
            // A deterministic diagonal makes opposite collapsed faces cancel even when
            // the native AO tessellator chose different diagonals for their source quads.
            int first = 0;
            for (int i = 1; i < 4; i++) if (corners[q * 4 + i] < corners[q * 4 + first]) first = i;
            rotation[q] = (byte)first;
            int a = corners[q * 4 + first], b = corners[q * 4 + ((first + 1) & 3)];
            int c = corners[q * 4 + ((first + 2) & 3)], d = corners[q * 4 + ((first + 3) & 3)];
            add(triangles, a, b, c, q * 2);
            add(triangles, c, d, a, q * 2 + 1);
        }
        if (moved == 0) return null;
        for (Triangle triangle : triangles.values()) {
            if (Math.abs(triangle.winding) > 1) return null; // Overlapping/nonmanifold models keep native topology.
            int sourceTriangle = triangle.winding > 0 ? triangle.positive : triangle.winding < 0 ? triangle.negative : -1;
            if (sourceTriangle >= 0) retained[sourceTriangle / 2] |= (byte)(1 << (sourceTriangle & 1));
        }
        byte[] bytes = new byte[data.remaining()];
        ByteBuffer output = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        for (int q = 0; q < count; q++) {
            if (!candidates[q]) { output.put(data.slice(q * quadBytes, quadBytes)); continue; }
            int mask = retained[q];
            if (mask == 0) continue;
            int first = rotation[q];
            // A single surviving triangle is encoded as a native quad with a degenerate
            // second triangle, preserving the native sequential-index draw contract.
            int[] order = mask == 3 ? new int[]{0, 1, 2, 3} : mask == 1 ? new int[]{0, 1, 2, 2} : new int[]{2, 3, 0, 0};
            for (int i : order) {
                int corner = (first + i) & 3, vertex = corners[q * 4 + corner], start = output.position();
                output.put(data.slice(q * quadBytes + corner * stride, stride));
                output.putFloat(start + layout.position(), x(vertex));
                output.putFloat(start + layout.position() + 4, y(vertex));
                output.putFloat(start + layout.position() + 8, z(vertex));
            }
        }
        int size = output.position();
        // Empty/cost-increasing replacements fall back to conservative surface merging.
        return size > 0 && size < bytes.length ? new Reduced(Arrays.copyOf(bytes, size), count, size / quadBytes, moved) : null;
    }

    private static int snap(int coordinate, int cell) {
        // Keep interior clusters inside the section; only native boundary vertices lie on its boundary.
        return Math.clamp(Math.round((float)coordinate / cell) * cell, 1, 15);
    }
    private static int id(int x, int y, int z) { return x + EDGE * (y + EDGE * z); }
    private static int x(int vertex) { return vertex % EDGE; }
    private static int y(int vertex) { return vertex / EDGE % EDGE; }
    private static int z(int vertex) { return vertex / (EDGE * EDGE); }

    private static boolean protect(ByteBuffer source, int offset, NativeSurfaceMesher.Layout layout, boolean[] pinned) {
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int vertex = 0; vertex < 4; vertex++) for (int axis = 0; axis < 3; axis++) {
            float value = source.getFloat(offset + vertex * layout.stride() + layout.position() + axis * 4);
            if (!Float.isFinite(value)) return false;
            min[axis] = Math.min(min[axis], value); max[axis] = Math.max(max[axis], value);
        }
        // Pin the entire contact rectangle, including interiors of large custom quads.
        int x0 = (int)Math.max(0, Math.floor(min[0]) - 1), x1 = (int)Math.min(16, Math.ceil(max[0]) + 1);
        int y0 = (int)Math.max(0, Math.floor(min[1]) - 1), y1 = (int)Math.min(16, Math.ceil(max[1]) + 1);
        int z0 = (int)Math.max(0, Math.floor(min[2]) - 1), z1 = (int)Math.min(16, Math.ceil(max[2]) + 1);
        for (int iz = z0; iz <= z1; iz++) for (int iy = y0; iy <= y1; iy++) for (int ix = x0; ix <= x1; ix++)
            pinned[id(ix, iy, iz)] = true;
        return true;
    }

    private static void add(Map<Long, Triangle> triangles, int a, int b, int c, int source) {
        int abx = x(b) - x(a), aby = y(b) - y(a), abz = z(b) - z(a);
        int acx = x(c) - x(a), acy = y(c) - y(a), acz = z(c) - z(a);
        if (aby * acz == abz * acy && abz * acx == abx * acz && abx * acy == aby * acx) return;
        int inversions = (a > b ? 1 : 0) + (a > c ? 1 : 0) + (b > c ? 1 : 0);
        int lo = Math.min(a, Math.min(b, c)), hi = Math.max(a, Math.max(b, c)), middle = a + b + c - lo - hi;
        long key = (long)lo | (long)middle << 13 | (long)hi << 26;
        triangles.computeIfAbsent(key, ignored -> new Triangle()).add((inversions & 1) == 0 ? 1 : -1, source);
    }
}
