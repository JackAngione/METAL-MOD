package dev.metalcraft.client.shader.world;

import java.nio.ByteBuffer;
import java.util.List;
import it.unimi.dsi.fastutil.longs.LongArrayList;

/** CPU/GPU ABI for nearby, shadowed local light. No source-count limit or block whitelist. */
public final class LocalLightVolume {
    public static final int SECTIONS = 7;
    public static final int SIZE = SECTIONS * 16;
    public static final int CELLS = SIZE * SIZE * SIZE;
    public static final int CLUSTER_SIZE = 8;
    public static final int CLUSTERS = SIZE / CLUSTER_SIZE;
    public static final int CLUSTER_COUNT = CLUSTERS * CLUSTERS * CLUSTERS;
    public static final float DISTANCE = 32;
    public static final int FRAME_BYTES = 32;
    public static final int LIGHT_BYTES = 32;
    public static final int FULL_CUBE = (16 << 5) | (16 << 15) | (16 << 25);

    private LocalLightVolume() { }

    /** Positions relative to the integer volume origin, never absolute floats. */
    public record Light(float x, float y, float z, float level, boolean dynamic) {
        public Light {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(level) || level <= 0 || level > 15) {
                throw new IllegalArgumentException("Invalid local light");
            }
        }

        public void write(ByteBuffer bytes) {
            bytes.putFloat(x).putFloat(y).putFloat(z).putFloat(level);
            bytes.putInt(dynamic ? 1 : 0).putInt(0).putInt(0).putInt(0);
        }
    }

    public static int index(int x, int y, int z) { return x + SIZE * (y + SIZE * z); }

    /** Six 5-bit bounds in sixteenths. Shape lists preserve stairs, slabs, doors and fences. */
    public static int box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return bound(minX, false) | bound(maxX, true) << 5
            | bound(minY, false) << 10 | bound(maxY, true) << 15
            | bound(minZ, false) << 20 | bound(maxZ, true) << 25;
    }

    private static int bound(double value, boolean upper) {
        return Math.clamp((int)(upper ? Math.ceil(value * 16) : Math.floor(value * 16)), 0, 16);
    }

    /**
     * Every light whose sphere intersects a cluster is retained. Entries sorted by an upper
     * bound let the shader stop once no remaining light can beat the visible contribution.
     * Max-composition matches Minecraft's light propagation and avoids blowing out lava fields.
     */
    public static int[] clusters(List<Light> lights) {
        LongArrayList[] cells = new LongArrayList[CLUSTER_COUNT];
        int entries = 0;
        for (int i = 0; i < lights.size(); i++) {
            Light light = lights.get(i);
            // Camera coordinates lie in [48,64). Only clusters [2,11] can contain a
            // receiver within 32 blocks; the outer sections are source/occluder halo.
            int x0 = Math.max(2, cluster(light.x - light.level)), x1 = Math.min(11, cluster(light.x + light.level));
            int y0 = Math.max(2, cluster(light.y - light.level)), y1 = Math.min(11, cluster(light.y + light.level));
            int z0 = Math.max(2, cluster(light.z - light.level)), z1 = Math.min(11, cluster(light.z + light.level));
            for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) {
                float dx = axisDistance(light.x, x), dy = axisDistance(light.y, y), dz = axisDistance(light.z, z);
                float energy = Math.max(0, (light.level - (float)Math.sqrt(dx * dx + dy * dy + dz * dz)) / 15);
                if (energy <= 0) continue;
                int cell = x + CLUSTERS * (y + CLUSTERS * z);
                if (cells[cell] == null) cells[cell] = new LongArrayList();
                // Positive float bits sort by magnitude. Primitive entries avoid allocating
                // one object for every lava-surface/cluster intersection.
                cells[cell].add((long)Float.floatToRawIntBits(energy * energy) << 32 | (0xffffffffL - i));
                entries++;
            }
        }
        // Header: one offset/count pair per cell. Payload: one light-index/upper-bound pair.
        int[] packed = new int[CLUSTER_COUNT * 2 + entries * 2];
        int cursor = CLUSTER_COUNT * 2;
        for (int cell = 0; cell < cells.length; cell++) {
            packed[cell * 2] = cursor;
            if (cells[cell] == null) continue;
            java.util.Arrays.sort(cells[cell].elements(), 0, cells[cell].size());
            packed[cell * 2 + 1] = cells[cell].size();
            for (int i = cells[cell].size() - 1; i >= 0; i--) {
                long candidate = cells[cell].getLong(i);
                packed[cursor++] = (int)(0xffffffffL - (candidate & 0xffffffffL));
                packed[cursor++] = (int)(candidate >>> 32);
            }
        }
        return packed;
    }

    private static int cluster(float position) { return Math.clamp((int)Math.floor(position / CLUSTER_SIZE), 0, CLUSTERS - 1); }
    private static float axisDistance(float position, int cluster) {
        return Math.max(0, Math.max(cluster * CLUSTER_SIZE - position, position - (cluster + 1) * CLUSTER_SIZE));
    }
}
