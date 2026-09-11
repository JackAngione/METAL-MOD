package dev.metalcraft.client.lod;

import java.util.List;
import java.util.Objects;

/** A section plus a one-block halo. No live world, model, lighting engine or GPU references. */
public final class TerrainSnapshot {
    public static final int SIZE = 16;
    public static final int PADDED_SIZE = SIZE + 2;
    public static final int VOLUME = PADDED_SIZE * PADDED_SIZE * PADDED_SIZE;
    public enum Policy { EMPTY, OPAQUE_CUBE, UNSUPPORTED }

    /** Material IDs are resource-generation scoped. Face UV mapping is owned by the upload adapter. */
    public record Material(int id, Policy policy, int tint, int packedLight) {
        public Material { Objects.requireNonNull(policy); }
    }

    public record Key(long session, String dimension, int x, int y, int z, long revision, long resources) {
        public Key { Objects.requireNonNull(dimension); }
    }

    private final Key key;
    private final List<Material> palette;
    private final int[] cells;

    public TerrainSnapshot(Key key, List<Material> palette, int[] cells) {
        this.key = Objects.requireNonNull(key);
        this.palette = List.copyOf(palette);
        if (cells.length != VOLUME || palette.isEmpty()) throw new IllegalArgumentException("Expected 18³ cells and a material palette");
        this.cells = cells.clone();
        for (int cell : this.cells) {
            if (cell < 0 || cell >= this.palette.size()) throw new IllegalArgumentException("Invalid palette index");
        }
    }

    public Key key() { return key; }

    /** Run on the source's owning thread. Only copied immutable material values reach the builder. */
    public static TerrainSnapshot capture(Key key, CellSource source) {
        java.util.Map<Material, Integer> indices = new java.util.HashMap<>();
        java.util.List<Material> palette = new java.util.ArrayList<>();
        int[] cells = new int[VOLUME];
        for (int z = -1; z <= SIZE; z++) for (int y = -1; y <= SIZE; y++) for (int x = -1; x <= SIZE; x++) {
            Material material = Objects.requireNonNull(source.at(x, y, z));
            int id = indices.computeIfAbsent(material, value -> { palette.add(value); return palette.size() - 1; });
            cells[index(x, y, z)] = id;
        }
        return new TerrainSnapshot(key, palette, cells);
    }

    @FunctionalInterface
    public interface CellSource { Material at(int localX, int localY, int localZ); }
    public long retainedBytes() { return (long)cells.length * Integer.BYTES + (long)palette.size() * 32; }
    public Material at(int x, int y, int z) { return palette.get(cells[index(x, y, z)]); }

    public static int index(int x, int y, int z) {
        if (x < -1 || x > SIZE || y < -1 || y > SIZE || z < -1 || z > SIZE) throw new IndexOutOfBoundsException();
        return (x + 1) + PADDED_SIZE * ((y + 1) + PADDED_SIZE * (z + 1));
    }
}
