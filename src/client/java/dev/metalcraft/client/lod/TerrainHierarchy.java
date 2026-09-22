package dev.metalcraft.client.lod;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Conservative first builder: only exact homogeneous cells collapse; no cave or silhouette loss. */
public final class TerrainHierarchy {
    public record Node(int x, int y, int z, int size, TerrainSnapshot.Material material, List<Node> children) {
        public Node { children = List.copyOf(children); }
        public boolean leaf() { return children.isEmpty(); }
    }

    /** Axis normal, sign and integer rectangle. Textures must repeat per block, never stretch. */
    public record Face(int axis, int sign, int plane, int u, int v, int width, int height,
                       TerrainSnapshot.Material material) { }

    public record Mesh(TerrainSnapshot.Key key, int tier, boolean supported, Node root, List<Face> faces,
                       int exposedUnitFaces, double worldError) {
        public Mesh { faces = List.copyOf(faces); }
        public int triangles() { return faces.size() * 2; }
        /** Upload reservation matching the prototype's six 40-byte vertices per rectangle. */
        public long estimatedBytes() { return faces.size() * (6L * 40); }
    }

    private TerrainHierarchy() { }

    public static Mesh build(TerrainSnapshot snapshot, int tier) {
        if (tier < 1 || tier > 4) throw new IllegalArgumentException("LOD tier must be 1–4");
        // A mixed unsupported section stays entirely on the ordinary mesh. The halo is also
        // conservative: custom boundary occlusion cannot be inferred from a cube policy.
        for (int z = -1; z <= 16; z++) for (int y = -1; y <= 16; y++) for (int x = -1; x <= 16; x++) {
            if (snapshot.at(x, y, z).policy() == TerrainSnapshot.Policy.UNSUPPORTED) {
                return new Mesh(snapshot.key(), tier, false, null, List.of(), 0, 0);
            }
        }
        Node root = node(snapshot, 0, 0, 0, 16);
        List<Face> faces = new ArrayList<>();
        int exposed = 0;
        int limit = 1 << tier;
        for (int axis = 0; axis < 3; axis++) for (int sign : new int[] {-1, 1}) {
            for (int slice = 0; slice < 16; slice++) {
                TerrainSnapshot.Material[] mask = new TerrainSnapshot.Material[256];
                for (int v = 0; v < 16; v++) for (int u = 0; u < 16; u++) {
                    TerrainSnapshot.Material here = at(snapshot, axis, slice, u, v);
                    TerrainSnapshot.Material next = at(snapshot, axis, slice + sign, u, v);
                    if (here.policy() == TerrainSnapshot.Policy.OPAQUE_CUBE && next.policy() == TerrainSnapshot.Policy.EMPTY) {
                        mask[u + 16 * v] = here;
                        exposed++;
                    }
                }
                // Keep section-edge strips at unit tessellation. Adjacent sections/tiers share
                // the same boundary vertices, including around cave mouths; no skirts are used.
                for (int v = 0; v < 16; v++) for (int u = 0; u < 16; u++) {
                    TerrainSnapshot.Material material = mask[u + 16 * v];
                    if (material == null) continue;
                    boolean boundary = u == 0 || v == 0 || u == 15 || v == 15;
                    int width = 1;
                    int height = 1;
                    if (!boundary) {
                        while (width < limit && u + width < 15 && material.equals(mask[u + width + 16 * v])) width++;
                        rows: while (height < limit && v + height < 15) {
                            for (int dx = 0; dx < width; dx++) {
                                if (!material.equals(mask[u + dx + 16 * (v + height)])) break rows;
                            }
                            height++;
                        }
                    }
                    for (int dy = 0; dy < height; dy++) for (int dx = 0; dx < width; dx++) mask[u + dx + 16 * (v + dy)] = null;
                    faces.add(new Face(axis, sign, slice + (sign > 0 ? 1 : 0), u, v, width, height, material));
                }
            }
        }
        return new Mesh(snapshot.key(), tier, true, root, faces, exposed, 0.0);
    }

    private static TerrainSnapshot.Material at(TerrainSnapshot s, int axis, int depth, int u, int v) {
        return switch (axis) { case 0 -> s.at(depth, u, v); case 1 -> s.at(v, depth, u); default -> s.at(u, v, depth); };
    }

    private static Node node(TerrainSnapshot s, int x, int y, int z, int size) {
        if (size == 1) return new Node(x, y, z, size, s.at(x, y, z), List.of());
        int half = size / 2;
        List<Node> children = new ArrayList<>(8);
        for (int dz = 0; dz < size; dz += half) for (int dy = 0; dy < size; dy += half) for (int dx = 0; dx < size; dx += half) {
            children.add(node(s, x + dx, y + dy, z + dz, half));
        }
        TerrainSnapshot.Material material = children.getFirst().material();
        boolean same = children.stream().allMatch(n -> n.leaf() && Objects.equals(material, n.material()));
        return new Node(x, y, z, size, same ? material : null, same ? List.of() : children);
    }
}
