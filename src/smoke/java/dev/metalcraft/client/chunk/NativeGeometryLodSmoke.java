package dev.metalcraft.client.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Geometric acceptance: closed surfaces stay closed, while one-block steps actually disappear. */
public final class NativeGeometryLodSmoke {
    private static final NativeSurfaceMesher.Layout LAYOUT = new NativeSurfaceMesher.Layout(28, 0, 12, 16, 24, -1);
    private static final int STRIDE = 28, QUAD = 112;

    public static void run() {
        int changed = 0;
        for (int shape = 0; shape < 5; shape++) {
            ByteBuffer source = fixture(shape);
            check(edges(source).isEmpty(), "source closed fixture " + shape);
            byte[] original = bytes(source);
            for (int cell : new int[]{2, 4, 8, 16}) {
                var result = NativeGeometryMesher.reduce(source, LAYOUT, cell, List.of());
                if (result == null) continue;
                changed++;
                ByteBuffer output = ByteBuffer.wrap(result.vertices()).order(ByteOrder.nativeOrder());
                check(result.quads() < result.originalQuads() && result.movedVertices() > 0, "real displacement and reduced geometry");
                check(edges(output).isEmpty(), "closed clustered surface shape=" + shape + " cell=" + cell + " edges=" + edges(output).size());
                check(boundary(source).equals(boundary(output)), "section perimeter survives exactly");
                check(Arrays.equals(original, bytes(source)), "native source bytes unmodified");
                for (int offset = 0; offset < output.limit(); offset += STRIDE) for (int axis = 0; axis < 3; axis++) {
                    float value = output.getFloat(offset + axis * 4);
                    check(Float.isFinite(value) && value >= 0 && value <= 16, "cluster remains inside native section bounds");
                }
                System.out.println("Geometric LOD shape " + shape + " cell " + cell + ": " + result.originalQuads() + " -> " + result.quads() + " quads, " + result.movedVertices() + " moved vertices");
            }
        }
        check(changed >= 16, "clustering applies to representative stepped, cave, ridge and boundary fixtures");
        ByteBuffer stairs = fixture(0);
        var extreme = NativeGeometryMesher.reduce(stairs, LAYOUT, 8, List.of());
        check(extreme != null && extreme.quads() < extreme.originalQuads() / 2, "staircase loses most of its block-scale geometry");
        check(NativeGeometryMesher.reduce(stairs, LAYOUT, 1, List.of()) == null, "native tier unchanged");
        check(NativeGeometryMesher.reduce(stairs, LAYOUT, 3, List.of()) == null, "invalid grid rejected");
        check(NativeGeometryMesher.reduce(stairs, LAYOUT, 8, List.of(stairs)) == null, "protected contact geometry pins all shared vertices");
        check(NativeGeometryMesher.reduce(ByteBuffer.allocate((NativeSurfaceMesher.MAX_QUADS + 1) * QUAD), LAYOUT, 8, List.of()) == null, "worker input capped");
        System.out.println("Geometric LOD smoke passed: actual displacement, large staircase reduction, closed topology, native section perimeter, protected contacts and bounds");
    }

    private static ByteBuffer fixture(int shape) {
        boolean[][][] solid = new boolean[16][16][16];
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            boolean inside = x >= 1 && x < 15 && z >= 1 && z < 15 && y >= 1;
            solid[x][y][z] = switch (shape) {
                case 0 -> inside && y <= x;
                case 1 -> inside && y <= (x + z) / 2;
                case 2 -> inside && y < 14 && !(x >= 5 && x < 11 && y >= 5 && y < 11 && z >= 5 && z < 11);
                case 3 -> inside && y <= 14 - Math.abs(8 - x);
                default -> y >= 1 && y <= x && z >= 1 && z < 15; // Surface meets a section boundary.
            };
        }
        ByteBuffer data = ByteBuffer.allocate(8192 * QUAD).order(ByteOrder.nativeOrder());
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
            if (!solid[x][y][z]) continue;
            int[] p = {x, y, z};
            for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
                int[] next = p.clone(); next[axis] += sign;
                if (next[axis] >= 0 && next[axis] < 16 && solid[next[0]][next[1]][next[2]]) continue;
                int plane = p[axis] + (sign > 0 ? 1 : 0), ua = (axis + 1) % 3, va = (axis + 2) % 3;
                int[] order = sign > 0 ? new int[]{0, 1, 3, 2} : new int[]{0, 2, 3, 1};
                for (int corner : order) {
                    int[] v = p.clone(); v[axis] = plane; v[ua] += corner & 1; v[va] += corner >> 1;
                    data.putFloat(v[0]).putFloat(v[1]).putFloat(v[2]).putInt(0xffa0b0c0);
                    data.putFloat(.25f + (corner & 1) * .125f).putFloat(.25f + (corner >> 1) * .125f).putInt(0x00f000f0);
                }
            }
        }
        return data.flip();
    }

    private record Point(int x, int y, int z) { }
    private static Point point(ByteBuffer data, int offset) {
        return new Point((int)data.getFloat(offset), (int)data.getFloat(offset + 4), (int)data.getFloat(offset + 8));
    }
    private static Map<String, Integer> edges(ByteBuffer data) {
        Map<String, Integer> result = new HashMap<>();
        for (int offset = 0; offset < data.limit(); offset += QUAD) {
            Point a = point(data, offset), b = point(data, offset + STRIDE), c = point(data, offset + STRIDE * 2), d = point(data, offset + STRIDE * 3);
            edge(result, a, b); edge(result, b, c); edge(result, c, a);
            edge(result, c, d); edge(result, d, a); edge(result, a, c);
        }
        result.values().removeIf(value -> value == 0);
        return result;
    }
    private static void edge(Map<String, Integer> result, Point a, Point b) {
        int dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z, steps = gcd(gcd(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        if (steps == 0) return;
        for (int i = 0; i < steps; i++) {
            Point from = new Point(a.x + dx * i / steps, a.y + dy * i / steps, a.z + dz * i / steps);
            Point to = new Point(a.x + dx * (i + 1) / steps, a.y + dy * (i + 1) / steps, a.z + dz * (i + 1) / steps);
            String f = from.toString(), t = to.toString();
            boolean forward = f.compareTo(t) < 0;
            result.merge(forward ? f + ":" + t : t + ":" + f, forward ? 1 : -1, Integer::sum);
        }
    }
    private static int gcd(int a, int b) { while (b != 0) { int remainder = a % b; a = b; b = remainder; } return a; }
    private static Map<Point, Integer> boundary(ByteBuffer data) {
        // Compare boundary surface coverage, not duplicate vertex references after retessellation.
        Map<Point, Integer> result = new HashMap<>();
        for (int offset = 0; offset < data.limit(); offset += STRIDE) {
            Point p = point(data, offset);
            if (p.x == 0 || p.y == 0 || p.z == 0 || p.x == 16 || p.y == 16 || p.z == 16) result.put(p, 1);
        }
        return result;
    }
    private static byte[] bytes(ByteBuffer data) { byte[] bytes = new byte[data.remaining()]; data.duplicate().get(bytes); return bytes; }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
