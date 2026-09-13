package dev.metalcraft.client.lod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable emitted geometry: final AO/tint/light and UVs, with no model or world references. */
public record LodBakedMesh(List<Quad> quads) {
    public LodBakedMesh { quads = List.copyOf(quads); }

    /** Color/light retain the source buffer's packed bits; no color-space conversion occurs here. */
    public record Vertex(float x, float y, float z, float u, float v, int color, int light) {
        public Vertex {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                    || !Float.isFinite(u) || !Float.isFinite(v)) throw new IllegalArgumentException("Nonfinite vertex");
        }
        float coordinate(int axis) { return axis == 0 ? x : axis == 1 ? y : z; }
    }

    public record Sprite(String name, float u0, float v0, float u1, float v1) {
        public Sprite {
            Objects.requireNonNull(name);
            if (!(u0 >= 0 && v0 >= 0 && u1 <= 1 && v1 <= 1 && u1 > u0 && v1 > v0))
                throw new IllegalArgumentException("Invalid atlas rectangle");
        }
    }

    public record Quad(Sprite sprite, List<Vertex> vertices) {
        public Quad {
            Objects.requireNonNull(sprite);
            vertices = List.copyOf(vertices);
            if (vertices.size() != 4) throw new IllegalArgumentException("Expected four vertices");
        }
    }

    /** Original winding and shrunken sprite UVs are retained for the upload adapter. */
    public record Rectangle(Quad source, int axis, int sign, int plane, int u, int v, int width, int height) { }
    public record Simplified(boolean supported, List<Rectangle> rectangles, List<Quad> unmerged, int originalQuads) {
        public Simplified { rectangles = List.copyOf(rectangles); unmerged = List.copyOf(unmerged); }
        public int quads() { return supported ? rectangles.size() + unmerged.size() : originalQuads; }
    }
    private record Appearance(Sprite sprite, int color, int light, List<Long> uv, List<Integer> winding) { }
    private record Plane(int axis, int sign, int plane, Appearance appearance) { }
    private record Face(int axis, int sign, int plane, int u, int v, Appearance appearance) { }
    private record Surface(int axis, int plane, int u, int v) { }

    /** Conservatively merges only unit faces with identical flat shading and UV orientation. */
    public Simplified simplify(int tier) {
        if (tier < 1 || tier > 4) throw new IllegalArgumentException("LOD tier must be 1–4");
        return classify().simplify(tier);
    }

    /** Classify once on the compiler worker; each tier owns its mutable merge cells. */
    public List<Simplified> simplifyTiers() {
        Classified classified = classify();
        return List.of(classified.simplify(1), classified.simplify(2),
                classified.simplify(3), classified.simplify(4));
    }

    Classified classify() {
        Map<Plane, Map<Integer, Quad>> planes = new LinkedHashMap<>();
        java.util.Set<Surface> occupied = new java.util.HashSet<>();
        List<Quad> unmerged = new ArrayList<>();
        // Reject custom/thin geometry as a whole section. Nonuniform lighting is preserved verbatim.
        for (Quad quad : quads) {
            Face face = face(quad);
            if (face == null) return new Classified(false, Map.of(), List.of(), quads.size());
            if (!occupied.add(new Surface(face.axis, face.plane, face.u, face.v)))
                return new Classified(false, Map.of(), List.of(), quads.size());
            if (face.appearance == null) { unmerged.add(quad); continue; }
            Plane plane = new Plane(face.axis, face.sign, face.plane, face.appearance);
            Map<Integer, Quad> cells = planes.computeIfAbsent(plane, ignored -> new java.util.HashMap<>());
            int cell = face.u + 16 * face.v;
            cells.put(cell, quad);
        }
        return new Classified(true, planes, unmerged, quads.size());
    }

    record Classified(boolean supported, Map<Plane, Map<Integer, Quad>> planes,
                              List<Quad> unmerged, int originalQuads) {
        Simplified simplify(int tier) {
            if (!supported) return new Simplified(false, List.of(), List.of(), originalQuads);
            List<Rectangle> rectangles = new ArrayList<>();
            int limit = 1 << tier;
            for (var entry : planes.entrySet()) {
                Plane plane = entry.getKey();
                Map<Integer, Quad> cells = new java.util.HashMap<>(entry.getValue());
                for (int v = 0; v < 16; v++) for (int u = 0; u < 16; u++) {
                    Quad source = cells.get(u + 16 * v);
                    if (source == null) continue;
                    int width = 1, height = 1;
                    if (u > 0 && v > 0 && u < 15 && v < 15) {
                        while (width < limit && u + width < 15 && cells.containsKey(u + width + 16 * v)) width++;
                        rows: while (height < limit && v + height < 15) {
                            for (int dx = 0; dx < width; dx++) if (!cells.containsKey(u + dx + 16 * (v + height))) break rows;
                            height++;
                        }
                    }
                    for (int dy = 0; dy < height; dy++) for (int dx = 0; dx < width; dx++) cells.remove(u + dx + 16 * (v + dy));
                    rectangles.add(new Rectangle(source, plane.axis, plane.sign, plane.plane, u, v, width, height));
                }
            }
            return new Simplified(true, rectangles, unmerged, originalQuads);
        }
    }

    private static Face face(Quad quad) {
        List<Vertex> vertices = quad.vertices;
        int axis = -1;
        for (int candidate = 0; candidate < 3; candidate++) {
            float value = vertices.getFirst().coordinate(candidate);
            boolean same = true;
            for (Vertex vertex : vertices) same &= vertex.coordinate(candidate) == value;
            if (same) { if (axis >= 0) return null; axis = candidate; }
        }
        if (axis < 0) return null;
        int ua = (axis + 1) % 3, va = (axis + 2) % 3;
        float plane = vertices.getFirst().coordinate(axis);
        float minU = Float.POSITIVE_INFINITY, minV = minU, maxU = Float.NEGATIVE_INFINITY, maxV = maxU;
        for (Vertex vertex : vertices) {
            float u = vertex.coordinate(ua), v = vertex.coordinate(va);
            minU = Math.min(minU, u); minV = Math.min(minV, v);
            maxU = Math.max(maxU, u); maxV = Math.max(maxV, v);
        }
        if (plane != Math.rint(plane) || plane < 0 || plane > 16 || minU != Math.rint(minU)
                || minV != Math.rint(minV) || minU < 0 || minV < 0 || maxU > 16 || maxV > 16
                || maxU - minU != 1 || maxV - minV != 1) return null;
        Long[] uv = new Long[4];
        List<Integer> winding = new ArrayList<>(4);
        boolean flat = true;
        Vertex first = vertices.getFirst();
        for (Vertex vertex : vertices) {
            float u = vertex.coordinate(ua) - minU, v = vertex.coordinate(va) - minV;
            if ((u != 0 && u != 1) || (v != 0 && v != 1)) return null;
            int corner = (int)u + 2 * (int)v;
            if (uv[corner] != null) return null;
            uv[corner] = (Integer.toUnsignedLong(Float.floatToIntBits(vertex.u)) << 32)
                    | Integer.toUnsignedLong(Float.floatToIntBits(vertex.v));
            winding.add(corner);
            flat &= vertex.color == first.color && vertex.light == first.light;
        }
        Vertex second = vertices.get(1), third = vertices.get(2);
        for (int i = 0; i < 4; i++) {
            int a = winding.get(i), b = winding.get((i + 1) % 4);
            if (Integer.bitCount(a ^ b) != 1) return null;
        }
        float cross = (second.coordinate(ua) - first.coordinate(ua)) * (third.coordinate(va) - first.coordinate(va))
                - (second.coordinate(va) - first.coordinate(va)) * (third.coordinate(ua) - first.coordinate(ua));
        if (Math.abs(cross) != 1) return null;
        // Non-affine texture maps need their original two triangles, even with flat shading.
        for (int component = 0; component < 2; component++) {
            double sum = 0;
            for (int i = 0; i < 4; i++) {
                float value = Float.intBitsToFloat(component == 0 ? (int)(uv[i] >>> 32) : (int)(long)uv[i]);
                sum += (i == 0 || i == 3 ? 1 : -1) * (double)value;
            }
            flat &= sum == 0;
        }
        // Canonical vertex order matters: retaining it also retains the original triangle diagonal.
        Appearance appearance = flat ? new Appearance(quad.sprite, first.color, first.light, List.of(uv), List.copyOf(winding)) : null;
        return new Face(axis, cross > 0 ? 1 : -1, (int)plane, (int)minU, (int)minV, appearance);
    }
}
