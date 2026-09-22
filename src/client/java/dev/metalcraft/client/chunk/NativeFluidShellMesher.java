package dev.metalcraft.client.chunk;

/** Fluid envelopes share the solid shell's complexity bound, including fractional flow heights. */
public final class NativeFluidShellMesher {
    @FunctionalInterface public interface Height { float height(int x, int y, int z); }
    @FunctionalInterface public interface Output {
        /** Positions are four cyclic vertices; the array is borrowed for this call only. */
        void face(float[] positions, int axis, int sign, int sampleX, int sampleY, int sampleZ);
    }
    private NativeFluidShellMesher() { }

    public static int build(NativeShellMesher.Occupancy source, Height heights, int tier,
                            boolean doubleSided, Output output) {
        int cell = NativeShellMesher.grid(tier), width = 16 / cell, count = width * width;
        float[] low = new float[count], high = new float[count];
        int[] bottom = new int[count], top = new int[count];
        java.util.Arrays.fill(low, 16);
        for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
            int tile = x / cell + z / cell * width;
            for (int y = 0; y < 16; y++) if (source.occupied(x, y, z)) {
                float height = heights.height(x, y, z);
                if (!Float.isFinite(height) || height <= 0 || height > 1) throw new IllegalArgumentException("Fluid height");
                int sample = x | y << 4 | z << 8;
                if (y < low[tile]) { low[tile] = y; bottom[tile] = sample; }
                if (y + height > high[tile]) { high[tile] = y + height; top[tile] = sample; }
            }
        }
        var emitter = new Emitter(source, heights, doubleSided, output);
        for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
            int tile = x + z * width;
            float lo = low[tile], hi = high[tile];
            if (lo >= hi) continue;
            int x0 = x * cell, x1 = x0 + cell, z0 = z * cell, z1 = z0 + cell;
            emitter.face(1, 1, hi, z0, x0, z1, x1, top[tile]);
            emitter.face(1, -1, lo, z0, x0, z1, x1, bottom[tile]);
            for (int side = 0; side < 4; side++) {
                int nx = x + (side == 0 ? -1 : side == 1 ? 1 : 0);
                int nz = z + (side == 2 ? -1 : side == 3 ? 1 : 0);
                int neighbor = nx < 0 || nz < 0 || nx >= width || nz >= width ? -1 : nx + nz * width;
                float nlo = neighbor < 0 ? 16 : low[neighbor], nhi = neighbor < 0 ? 0 : high[neighbor];
                if (nlo >= nhi || nhi <= lo || nlo >= hi) emitter.wall(side, x0, x1, z0, z1, lo, hi, top[tile]);
                else {
                    if (lo < nlo) emitter.wall(side, x0, x1, z0, z1, lo, nlo, bottom[tile]);
                    if (hi > nhi) emitter.wall(side, x0, x1, z0, z1, nhi, hi, top[tile]);
                }
            }
        }
        return emitter.count;
    }

    private static final class Emitter {
        final NativeShellMesher.Occupancy source;
        final Height heights;
        final boolean doubleSided;
        final Output output;
        final float[] positions = new float[12];
        int count;
        Emitter(NativeShellMesher.Occupancy source, Height heights, boolean doubleSided, Output output) {
            this.source = source; this.heights = heights; this.doubleSided = doubleSided; this.output = output;
        }
        void wall(int side, int x0, int x1, int z0, int z1, float lo, float hi, int sample) {
            if (side < 2) face(0, side == 0 ? -1 : 1, side == 0 ? x0 : x1, lo, z0, hi, z1, sample);
            else face(2, side == 2 ? -1 : 1, side == 2 ? z0 : z1, x0, lo, x1, hi, sample);
        }
        void face(int axis, int sign, float plane, float u0, float v0, float u1, float v1, int sample) {
            if (plane == (sign > 0 ? 16 : 0) && hidden(axis, sign, u0, v0, u1, v1)) return;
            for (int side = 0; side < (doubleSided ? 2 : 1); side++) {
                int winding = side == 0 ? sign : -sign;
                for (int i = 0; i < 4; i++) {
                    int corner = winding > 0 ? i : 3 - i;
                    float u = corner == 1 || corner == 2 ? u1 : u0;
                    float v = corner >= 2 ? v1 : v0;
                    positions[i * 3] = axis == 0 ? plane : axis == 1 ? v : u;
                    positions[i * 3 + 1] = axis == 0 ? u : axis == 1 ? plane : v;
                    positions[i * 3 + 2] = axis == 0 ? v : axis == 1 ? u : plane;
                }
                output.face(positions, axis, winding, sample & 15, (sample >> 4) & 15, sample >> 8);
                count++;
            }
        }
        boolean hidden(int axis, int sign, float u0, float v0, float u1, float v1) {
            int outside = sign > 0 ? 16 : -1;
            for (int v = (int)v0; v < v1; v++) for (int u = (int)u0; u < u1; u++) {
                int x = axis == 0 ? outside : axis == 1 ? v : u;
                int y = axis == 0 ? u : axis == 1 ? outside : v;
                int z = axis == 0 ? v : axis == 1 ? u : outside;
                if (!source.occupied(x,y,z)) return false;
                float required = axis == 1 ? (sign > 0 ? 0 : 1) : Math.min(1, (axis == 0 ? u1 : v1) - y);
                if (heights.height(x,y,z) + 1e-6f < required) return false;
            }
            return true;
        }
    }
}
