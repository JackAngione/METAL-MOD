package dev.metalcraft.client.shader.world;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** The reuse contract rejects every input that could leave stale caster depth on screen. */
final class ShadowFrameReuseSmoke {
    static void run() {
        var cache = new ShadowFrameReuse();
        Object world = new Object(), sections = new Object(), atlas = new Object(), sampler = new Object();
        var rotation = new Quaternionf();
        var projection = new Matrix4f().perspective(1, 1.5F, 0.05F, 1024, true);
        long revision = ShadowFrameReuse.meshRevision();
        cache.store(world, sections, atlas, sampler, 100, 6000, revision, 10, 20, 30, rotation, projection, 0.3F);
        if (!cache.matches(world, sections, atlas, sampler, 100, 6000, revision, 10, 20, 30, rotation, projection, 0.3F, false))
            throw new AssertionError("Stable within-tick shadow frame was not reusable");
        // Render frames interpolate the celestial direction between world ticks. Even a
        // single representable step must invalidate the map: holding these frames and
        // catching up on the next tick turns smooth shadow motion into a visible jump.
        for (float light : new float[]{Math.nextUp(0.3F), Math.nextDown(0.3F), 0.3001F, 0.3009F}) {
            if (cache.matches(world, sections, atlas, sampler, 100, 6000, revision,
                    10, 20, 30, rotation, projection, light, false))
                throw new AssertionError("Interpolated celestial movement reused stale shadows: " + light);
        }
        for (int change = 0; change < 12; change++) {
            boolean match = cache.matches(change == 0 ? new Object() : world, change == 1 ? new Object() : sections,
                change == 2 ? new Object() : atlas, change == 3 ? new Object() : sampler,
                change == 4 ? 101 : 100, change == 5 ? 18000 : 6000, change == 6 ? revision + 1 : revision,
                change == 7 ? 10.0001 : 10, 20, 30,
                change == 8 ? new Quaternionf().rotateY(0.001F) : rotation,
                change == 9 ? new Matrix4f(projection).m00(2) : projection,
                change == 10 ? 0.5F : 0.3F, change == 11);
            if (match) throw new AssertionError("Stale shadow reuse accepted input change " + change);
        }
        rotation.rotateY(0.5F); projection.zero();
        if (cache.matches(world, sections, atlas, sampler, 100, 6000, revision, 10, 20, 30, rotation, projection, 0.3F, false))
            throw new AssertionError("Mutable camera inputs escaped the cache");
        ShadowFrameReuse.meshChanged();
        if (ShadowFrameReuse.meshRevision() == revision) throw new AssertionError("Mesh publication did not invalidate shadows");
        cache.invalidate();
        if (cache.matches(world, sections, atlas, sampler, 100, 6000, revision, 10, 20, 30,
            new Quaternionf(), new Matrix4f().perspective(1, 1.5F, 0.05F, 1024, true), 0.3F, false))
            throw new AssertionError("Invalidated shadow frame was reused");
        System.out.println("Shadow reuse: tick, clock, camera, world, storage, atlas, sampler, mesh publication and wind invalidation passed");
    }
}
