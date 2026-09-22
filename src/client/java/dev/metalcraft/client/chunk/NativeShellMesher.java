package dev.metalcraft.client.chunk;

/** A bounded exterior height envelope, not a mesh of occupied blocks or cave walls. */
public final class NativeShellMesher {
    /** The immutable source includes a one-block neighbor halo (-1 through 16). */
    @FunctionalInterface public interface Occupancy { boolean occupied(int x, int y, int z); }
    @FunctionalInterface public interface Output {
        /** Axis/sign determine outward winding; sample is a real occupied source block. */
        void face(int axis, int sign, int plane, int u0, int v0, int u1, int v1,
                  int sampleX, int sampleY, int sampleZ);
    }
    private NativeShellMesher() { }

    public static int grid(int tier) {
        if (tier != 2 && tier != 4 && tier != 8 && tier != 16) throw new IllegalArgumentException("Shell tier");
        return Math.max(4, tier);
    }

    /** At most 16 columns and 160 quads, independent of block-model complexity.
     * Cavities between a tile's lowest and highest block are deliberately absent.
     * Section edges are closed, so differing tiers never leave an open shell. */
    public static int build(Occupancy source, int tier, Output output) {
        int[] count = {0};
        buildEnvelope(source, tier, (axis, sign, plane, u0, v0, u1, v1, sx, sy, sz) -> {
            if (plane == (sign > 0 ? 16 : 0)) {
                boolean hidden = true;
                int outside = sign > 0 ? 16 : -1;
                for (int v = v0; hidden && v < v1; v++) for (int u = u0; u < u1; u++) {
                    int x = axis == 0 ? outside : axis == 1 ? v : u;
                    int y = axis == 0 ? u : axis == 1 ? outside : v;
                    int z = axis == 0 ? v : axis == 1 ? u : outside;
                    if (!source.occupied(x, y, z)) { hidden = false; break; }
                }
                if (hidden) return;
            }
            output.face(axis, sign, plane, u0, v0, u1, v1, sx, sy, sz);
            count[0]++;
        });
        return count[0];
    }

    private static void buildEnvelope(Occupancy source, int tier, Output output) {
        int cell = grid(tier), width = 16 / cell, count = width * width;
        int[] low = new int[count], high = new int[count], bottom = new int[count], top = new int[count];
        java.util.Arrays.fill(low, 16);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int tile = x / cell + z / cell * width;
            for (int y = 0; y < 16; y++) if (source.occupied(x, y, z)) {
                int sample = x | y << 4 | z << 8;
                if (y < low[tile]) { low[tile] = y; bottom[tile] = sample; }
                if (y + 1 > high[tile]) { high[tile] = y + 1; top[tile] = sample; }
            }
        }
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            int tile = x + z * width, lo = low[tile], hi = high[tile];
            if (lo >= hi) continue;
            int x0 = x * cell, x1 = x0 + cell, z0 = z * cell, z1 = z0 + cell;
            // Cyclic axes: Y faces use (Z,X), X faces (Y,Z), Z faces (X,Y).
            emit(output, 1, 1, hi, z0, x0, z1, x1, top[tile]);
            emit(output, 1, -1, lo, z0, x0, z1, x1, bottom[tile]);
            for (int side = 0; side < 4; side++) {
                int nx = x + (side == 0 ? -1 : side == 1 ? 1 : 0);
                int nz = z + (side == 2 ? -1 : side == 3 ? 1 : 0);
                int neighbor = nx < 0 || nz < 0 || nx >= width || nz >= width ? -1 : nx + nz * width;
                int nlo = neighbor < 0 ? 16 : low[neighbor], nhi = neighbor < 0 ? 0 : high[neighbor];
                if (nlo >= nhi || nhi <= lo || nlo >= hi) {
                    wall(output, side, x0, x1, z0, z1, lo, hi, top[tile]);
                } else {
                    if (lo < nlo) wall(output, side, x0, x1, z0, z1, lo, nlo, bottom[tile]);
                    if (hi > nhi) wall(output, side, x0, x1, z0, z1, nhi, hi, top[tile]);
                }
            }
        }
    }

    private static void wall(Output out, int side, int x0, int x1, int z0, int z1, int lo, int hi, int sample) {
        if (side < 2) emit(out, 0, side == 0 ? -1 : 1, side == 0 ? x0 : x1, lo, z0, hi, z1, sample);
        else emit(out, 2, side == 2 ? -1 : 1, side == 2 ? z0 : z1, x0, lo, x1, hi, sample);
    }
    private static void emit(Output out, int axis, int sign, int plane, int u0, int v0, int u1, int v1, int sample) {
        out.face(axis, sign, plane, u0, v0, u1, v1, sample & 15, (sample >> 4) & 15, sample >> 8);
    }
}
