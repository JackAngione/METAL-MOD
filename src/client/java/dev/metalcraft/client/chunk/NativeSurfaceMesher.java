package dev.metalcraft.client.chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Worker-only surface LOD. No world, atlas, renderer, or GPU state is retained. */
public final class NativeSurfaceMesher {
    public static final int MAX_QUADS = 8192;
    public record Layout(int stride, int position, int color, int uv, int light, int normal) { }
    public record Reduced(byte[] vertices, int originalQuads, int quads) { }
    private record Material(int u0, int v0, int u1, int v1, int normal) { }
    private record Plane(int axis, int sign, int coordinate, Material material) { }
    private record Face(Plane plane, int u, int v, int offset) { }

    private NativeSurfaceMesher() { }

    static boolean unitFace(ByteBuffer source, int offset, Layout layout) {
        return classify(source, offset, layout) != null;
    }

    /** Null means byte-for-byte native fallback. Only unindexed BLOCK quads are passed here. */
    public static Reduced reduce(ByteBuffer source, Layout layout, int cellSize) {
        if (cellSize != 2 && cellSize != 4 && cellSize != 8 && cellSize != 16) return null;
        int quadBytes = layout.stride * 4;
        if (source.remaining() % quadBytes != 0) return null;
        int count = source.remaining() / quadBytes;
        if (count < 2 || count > MAX_QUADS) return null;
        ByteBuffer data = source.slice().order(ByteOrder.nativeOrder());
        Face[] faces = new Face[count];
        // Both winding signs share occupancy: duplicate/overlay faces must never be merged.
        int[] occupancy = new int[3 * 17 * 256];
        for (int q = 0; q < count; q++) {
            Face face = classify(data, q * quadBytes, layout);
            faces[q] = face;
            if (face != null) occupancy[surface(face)]++;
        }
        Map<Plane, int[]> planes = new LinkedHashMap<>();
        List<Integer> untouched = new ArrayList<>();
        for (int q = 0; q < count; q++) {
            Face face = faces[q];
            if (face == null || occupancy[surface(face)] != 1 || face.u == 0 || face.v == 0
                    || face.u == 15 || face.v == 15) {
                untouched.add(q * quadBytes);
            } else {
                int[] cells = planes.computeIfAbsent(face.plane, ignored -> {
                    int[] empty = new int[256]; Arrays.fill(empty, -1); return empty;
                });
                cells[face.u + face.v * 16] = face.offset;
            }
        }
        byte[] bytes = new byte[data.remaining()];
        ByteBuffer output = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
        for (int offset : untouched) copy(data, offset, output, quadBytes);
        for (var entry : planes.entrySet()) {
            Plane plane = entry.getKey();
            int[] cells = entry.getValue();
            for (int v = 1; v < 15; v++) for (int u = 1; u < 15; u++) {
                int sourceOffset = cells[u + v * 16];
                if (sourceOffset < 0) continue;
                int endU = Math.min(15, (u / cellSize + 1) * cellSize);
                int endV = Math.min(15, (v / cellSize + 1) * cellSize);
                int width = 1, height = 1;
                while (u + width < endU && cells[u + width + v * 16] >= 0) width++;
                rows: while (v + height < endV) {
                    for (int x = 0; x < width; x++) if (cells[u + x + (v + height) * 16] < 0) break rows;
                    height++;
                }
                long[] color = new long[4];
                long blockLight = 0, skyLight = 0;
                for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                    int index = u + x + (v + y) * 16, offset = cells[index];
                    for (int vertex = 0; vertex < 4; vertex++) {
                        int base = offset + vertex * layout.stride;
                        int rgba = data.getInt(base + layout.color), light = data.getInt(base + layout.light);
                        for (int channel = 0; channel < 4; channel++) color[channel] += (rgba >>> (channel * 8)) & 255;
                        blockLight += light & 65535; skyLight += (light >>> 16) & 65535;
                    }
                    cells[index] = -1;
                }
                int start = output.position();
                copy(data, sourceOffset, output, quadBytes);
                if (width == 1 && height == 1) continue;
                int samples = width * height * 4, averageColor = 0;
                for (int channel = 0; channel < 4; channel++) averageColor |= (int)((color[channel] + samples / 2) / samples) << (channel * 8);
                int averageLight = (int)((blockLight + samples / 2) / samples)
                        | (int)((skyLight + samples / 2) / samples) << 16;
                int ua = (plane.axis + 1) % 3, va = (plane.axis + 2) % 3;
                for (int vertex = 0; vertex < 4; vertex++) {
                    int base = start + vertex * layout.stride;
                    float oldU = output.getFloat(base + layout.position + ua * 4);
                    float oldV = output.getFloat(base + layout.position + va * 4);
                    output.putFloat(base + layout.position + ua * 4, u + (oldU - u) * width);
                    output.putFloat(base + layout.position + va * 4, v + (oldV - v) * height);
                    output.putInt(base + layout.color, averageColor);
                    output.putInt(base + layout.light, averageLight);
                    // UVs stay inside the original sprite: one texture sample footprint per patch.
                }
            }
        }
        int size = output.position();
        return size < bytes.length ? new Reduced(Arrays.copyOf(bytes, size), count, size / quadBytes) : null;
    }

    private static int surface(Face face) {
        return (face.plane.axis * 17 + face.plane.coordinate) * 256 + face.v * 16 + face.u;
    }

    private static void copy(ByteBuffer source, int offset, ByteBuffer target, int bytes) {
        target.put(source.slice(offset, bytes));
    }

    private static Face classify(ByteBuffer data, int offset, Layout l) {
        float[][] positions = new float[4][3];
        float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        float minU = Float.POSITIVE_INFINITY, minV = minU, maxU = Float.NEGATIVE_INFINITY, maxV = maxU;
        int normal = l.normal < 0 ? 0 : normal(data, offset + l.normal);
        for (int i = 0; i < 4; i++) {
            int base = offset + i * l.stride;
            if ((data.getInt(base + l.color) >>> 24) != 255
                    || (l.normal >= 0 && normal(data, base + l.normal) != normal)) return null;
            for (int axis = 0; axis < 3; axis++) {
                float value = data.getFloat(base + l.position + axis * 4);
                if (!Float.isFinite(value) || value != Math.rint(value) || value < 0 || value > 16) return null;
                positions[i][axis] = value;
                min[axis] = Math.min(min[axis], value); max[axis] = Math.max(max[axis], value);
            }
            float u = data.getFloat(base + l.uv), v = data.getFloat(base + l.uv + 4);
            if (!Float.isFinite(u) || !Float.isFinite(v) || u < 0 || u > 1 || v < 0 || v > 1) return null;
            minU = Math.min(minU, u); maxU = Math.max(maxU, u);
            minV = Math.min(minV, v); maxV = Math.max(maxV, v);
        }
        int axis = -1;
        for (int a = 0; a < 3; a++) if (min[a] == max[a]) {
            if (axis >= 0) return null;
            axis = a;
        }
        if (axis < 0 || minU == maxU || minV == maxV) return null;
        int ua = (axis + 1) % 3, va = (axis + 2) % 3;
        if (max[ua] - min[ua] != 1 || max[va] - min[va] != 1) return null;
        int corners = 0, uvCorners = 0;
        int[] geometryOrder = new int[4], uvOrder = new int[4];
        for (int i = 0; i < 4; i++) {
            int corner = (int)(positions[i][ua] - min[ua]) + 2 * (int)(positions[i][va] - min[va]);
            if ((corners & (1 << corner)) != 0) return null;
            corners |= 1 << corner; geometryOrder[i] = corner;
            float u = data.getFloat(offset + i * l.stride + l.uv), v = data.getFloat(offset + i * l.stride + l.uv + 4);
            if ((u != minU && u != maxU) || (v != minV && v != maxV)) return null;
            int uvCorner = (u == minU ? 0 : 1) + (v == minV ? 0 : 2);
            if ((uvCorners & (1 << uvCorner)) != 0) return null;
            uvCorners |= 1 << uvCorner; uvOrder[i] = uvCorner;
        }
        for (int i = 0; i < 4; i++) if (Integer.bitCount(geometryOrder[i] ^ geometryOrder[(i + 1) & 3]) != 1
                || Integer.bitCount(uvOrder[i] ^ uvOrder[(i + 1) & 3]) != 1) return null;
        float cross = (positions[1][ua] - positions[0][ua]) * (positions[2][va] - positions[0][va])
                - (positions[1][va] - positions[0][va]) * (positions[2][ua] - positions[0][ua]);
        int sign = cross > 0 ? 1 : -1;
        // Custom smoothed/slanted normals cannot be represented by a flat patch.
        if (l.normal >= 0) for (int a = 0; a < 3; a++)
            if ((byte)(normal >>> (a * 8)) != (a == axis ? sign * 127 : 0)) return null;
        return new Face(new Plane(axis, sign, (int)min[axis], new Material(Float.floatToIntBits(minU),
                Float.floatToIntBits(minV), Float.floatToIntBits(maxU), Float.floatToIntBits(maxV), normal)),
                (int)min[ua], (int)min[va], offset);
    }

    private static int normal(ByteBuffer data, int offset) {
        return (data.get(offset) & 255) | (data.get(offset + 1) & 255) << 8 | (data.get(offset + 2) & 255) << 16;
    }
}
