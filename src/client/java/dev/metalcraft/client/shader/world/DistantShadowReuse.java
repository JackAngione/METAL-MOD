package dev.metalcraft.client.shader.world;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

/** Bounded reuse of the coarse depth map; matrices stay anchored to its capture position. */
public final class DistantShadowReuse {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("metalcraft.distantShadowReuse", "true"));
    private Object world, sections, atlas, sampler;
    private long revision, tick, dayTime, capturedNanos;
    private int reused;
    private double x, y, z;
    private float angle;
    private final Quaternionf rotation = new Quaternionf();
    private final Matrix4f projection = new Matrix4f();
    private boolean valid;

    public boolean matches(Object world, Object sections, Object atlas, Object sampler, long tick,
            long dayTime, long revision, Vector3dc position, Quaternionfc rotation, Matrix4fc projection,
            float angle, long now) {
        double dx = position.x() - x, dy = position.y() - y, dz = position.z() - z;
        float lightDelta = Math.abs((float)Math.atan2(Math.sin(angle - this.angle), Math.cos(angle - this.angle)));
        return ENABLED && valid && this.world == world && this.sections == sections && this.atlas == atlas
            && this.sampler == sampler && this.revision == revision && this.projection.equals(projection)
            && tick >= this.tick && tick - this.tick <= 2 && dayTime >= this.dayTime && dayTime - this.dayTime <= 2
            && now >= capturedNanos && now - capturedNanos < 100_000_000L && reused < 6
            && dx * dx + dy * dy + dz * dz <= 4 && Math.abs((this.rotation.x * rotation.x() + this.rotation.y * rotation.y() + this.rotation.z * rotation.z() + this.rotation.w * rotation.w())) >= 0.9961947F
            && Float.isFinite(angle) && lightDelta <= 0.002F
            && (Math.cos(angle) < 0) == (Math.cos(this.angle) < 0);
    }
    public void reused() { reused++; }
    public void store(Object world, Object sections, Object atlas, Object sampler, long tick,
            long dayTime, long revision, Vector3dc position, Quaternionfc rotation, Matrix4fc projection,
            float angle, long now) {
        this.world = world; this.sections = sections; this.atlas = atlas; this.sampler = sampler;
        this.tick = tick; this.dayTime = dayTime; this.revision = revision; this.capturedNanos = now;
        x = position.x(); y = position.y(); z = position.z(); this.rotation.set(rotation);
        this.projection.set(projection); this.angle = angle; reused = 0; valid = true;
    }
    public Vector3d capturePosition() { return new Vector3d(x, y, z); }
    public static Matrix4f reproject(Matrix4fc captured, Vector3dc capture, Vector3dc current) {
        return new Matrix4f(captured).translate((float)(current.x() - capture.x()),
            (float)(current.y() - capture.y()), (float)(current.z() - capture.z()));
    }
    public void invalidate() { valid = false; world = sections = atlas = sampler = null; }
}
