package dev.metalcraft.client.shader.world;

import java.util.concurrent.atomic.AtomicLong;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;

/** Reuse stored shadow depth only inside one world tick with unchanged camera, light and casters. */
public final class ShadowFrameReuse {
    private static final AtomicLong MESH_REVISION = new AtomicLong();
    private final Matrix4f projection = new Matrix4f();
    private final Quaternionf rotation = new Quaternionf();
    private Object world, sections, atlas, sampler;
    private long tick, dayTime, revision;
    private double x, y, z;
    private float sunAngle;
    private boolean valid;

    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("metalcraft.shadowFrameReuse", "true"));
    private static final boolean PROBE = Boolean.getBoolean("metalcraft.baselineLightingBenchmark");

    public static void meshChanged() { MESH_REVISION.incrementAndGet(); }
    public static long meshRevision() { return MESH_REVISION.get(); }

    public boolean matches(Object world, Object sections, Object atlas, Object sampler,
            long tick, long dayTime, long revision, double x, double y, double z,
            Quaternionfc rotation, Matrix4fc projection, float sunAngle, boolean animatedCasters) {
        return ENABLED && !(PROBE && Boolean.getBoolean("metalcraft.baselineDisableShadowReuse"))
            && !animatedCasters && this.valid && this.world == world && this.sections == sections
            && this.atlas == atlas && this.sampler == sampler && this.tick == tick && this.dayTime == dayTime
            && this.revision == revision && this.x == x && this.y == y && this.z == z
            && this.rotation.equals(rotation) && this.projection.equals(projection)
            // Celestial direction is interpolated every render frame. Reusing a map for
            // a merely nearby angle holds shadows and then jumps them on the next tick.
            // Only an identical light direction can reuse its depth and fitted matrices.
            && Float.isFinite(sunAngle) && this.sunAngle == sunAngle;
    }

    public void store(Object world, Object sections, Object atlas, Object sampler,
            long tick, long dayTime, long revision, double x, double y, double z,
            Quaternionfc rotation, Matrix4fc projection, float sunAngle) {
        this.world = world; this.sections = sections; this.atlas = atlas; this.sampler = sampler;
        this.tick = tick; this.dayTime = dayTime; this.revision = revision;
        this.x = x; this.y = y; this.z = z; this.sunAngle = sunAngle;
        this.rotation.set(rotation); this.projection.set(projection); this.valid = true;
    }

    public void invalidate() {
        this.valid = false;
        this.world = this.sections = this.atlas = this.sampler = null;
    }
}
