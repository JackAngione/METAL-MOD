package dev.metalcraft.client.shader.world;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;

final class DistantShadowReuseSmoke {
    static void run() {
        var cache = new DistantShadowReuse();
        Object world = new Object(), sections = new Object(), atlas = new Object(), sampler = new Object();
        var capture = new Vector3d(1_000_000, 64, -1_000_000);
        var current = new Vector3d(capture).add(0.4, 0.1, -0.2);
        var rotation = new Quaternionf(); var projection = new Matrix4f().perspective(1, 1.5F, .05F, 2048, true);
        cache.store(world, sections, atlas, sampler, 100, 6000, 5, capture, rotation, projection, .3F, 0);
        if (!cache.matches(world, sections, atlas, sampler, 101, 6001, 5, current,
                new Quaternionf().rotateY(.02F), projection, .3001F, 20_000_000))
            throw new AssertionError("Normal camera motion did not reuse coarse shadows");
        for (int change = 0; change < 9; change++) {
            if (cache.matches(change == 0 ? new Object() : world, sections, atlas, sampler,
                    change == 1 ? 103 : 101, change == 2 ? 18000 : 6001, change == 3 ? 6 : 5,
                    change == 4 ? new Vector3d(capture).add(3, 0, 0) : current,
                    change == 5 ? new Quaternionf().rotateY(.3F) : rotation,
                    change == 6 ? new Matrix4f(projection).m00(2) : projection,
                    change == 7 ? .5F : .3001F, change == 8 ? 100_000_000 : 20_000_000))
                throw new AssertionError("Coarse invalidation missed " + change);
        }
        for (int i = 0; i < 6; i++) cache.reused();
        if (cache.matches(world, sections, atlas, sampler, 101, 6001, 5, current, rotation, projection, .3F, 20_000_000))
            throw new AssertionError("Frame cadence was unbounded");
        var light = new Matrix4f().rotateX(.7F).scale(.01F, .02F, .03F).translate(4, 5, 6);
        var oldRelative = new Vector3f(50, 10, -100);
        var currentRelative = new Vector3f(oldRelative).sub((float)(current.x - capture.x),
            (float)(current.y - capture.y), (float)(current.z - capture.z));
        var expected = light.transformPosition(new Vector3f(oldRelative));
        var actual = DistantShadowReuse.reproject(light, capture, current).transformPosition(currentRelative);
        if (actual.distance(expected) > .00001F) throw new AssertionError("Stored map swims with camera movement");
        cache.invalidate();
        if (cache.matches(world, sections, atlas, sampler, 101, 6001, 5, current, rotation, projection, .3F, 20_000_000))
            throw new AssertionError("Unload retained coarse shadows");
        System.out.println("Distant shadow cadence, invalidation and world-anchored reprojection passed");
    }
}
