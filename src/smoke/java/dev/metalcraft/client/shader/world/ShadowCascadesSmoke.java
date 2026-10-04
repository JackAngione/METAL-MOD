package dev.metalcraft.client.shader.world;

import java.util.List;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Geometric assertions independent of the GPU and of a loaded Minecraft world. */
public final class ShadowCascadesSmoke {
	private ShadowCascadesSmoke() {
	}

	public static void run() {
		var settings = new ShadowCascades.Settings(4, 2048, 0.05F, 256, 0.75F, 64);
		float fov = (float)Math.toRadians(70);
		float aspect = 16.0F / 9.0F;
		for (Vector3f sun : List.of(new Vector3f(0, 1, 0), new Vector3f(0.3F, 0.7F, -0.4F).normalize())) {
			for (Quaternionf rotation : List.of(new Quaternionf(), new Quaternionf().rotateYXZ(1.2F, -0.4F, 0.1F))) {
				List<ShadowCascades.Cascade> cascades = ShadowCascades.fit(settings,
					new Vector3d(29_000_000, 128, -29_000_000), rotation, fov, aspect, sun);
				float near = settings.near();
				float previousNear = 0;
				for (var cascade : cascades) {
					if (cascade.near() != near || cascade.far() <= near) throw new AssertionError("Cascade gap or overlap");
					float overlapNear = cascade == cascades.getFirst() ? near : near - (near - previousNear) * 0.1F;
					for (float distance : new float[]{overlapNear, cascade.near(), cascade.far()}) {
						for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) {
							float halfHeight = distance * (float)Math.tan(fov * 0.5);
							Vector3f corner = rotation.transform(new Vector3f(x * halfHeight * aspect, y * halfHeight, -distance));
							assertInside(cascade.cameraRelativeToShadow().transformPosition(new Vector3f(corner)));
							// A caster displaced toward the sun must remain inside the depth interval.
							corner.fma(settings.casterExtension(), sun);
							assertInside(cascade.cameraRelativeToShadow().transformPosition(corner));
						}
					}
					previousNear = cascade == cascades.getFirst() ? 0 : near;
					near = cascade.far();
				}
				if (near != settings.distance()) throw new AssertionError("Shadow distance was not covered");
			}
		}
		// Subtexel camera motion must not move a fixed world point in shadow XY. Use an
		// overhead sun and axis-aligned camera so the starting center is on the snapped grid.
		var sun = new Vector3f(0, 1, 0);
		var origin = new Vector3d(29_000_000, 128, -29_000_000);
		var first = ShadowCascades.fit(settings, origin, new Quaternionf(), fov, aspect, sun).getFirst();
		double movement = first.texelSize() * 0.01;
		var moved = ShadowCascades.fit(settings, new Vector3d(origin).add(movement, 0, 0),
			new Quaternionf(), fov, aspect, sun).getFirst();
		var before = first.cameraRelativeToShadow().transformPosition(new Vector3f(0, 0, -10));
		var after = moved.cameraRelativeToShadow().transformPosition(new Vector3f((float)-movement, 0, -10));
		if (Math.abs(before.x - after.x) > 1.0e-5F || Math.abs(before.y - after.y) > 1.0e-5F) {
			throw new AssertionError("Sun-shadow texels drift under subtexel camera translation");
		}
		// Live stabilization must retain world locking while avoiding absolute-position
		// amplification under a rotating sun. It must also reset across world changes.
		for (var position : List.of(new Vector3d(), new Vector3d(4702.5,216,546.5), origin)) {
			var stabilization = new ShadowCascades.Stabilization();
			var a = ShadowCascades.fit(settings, position, new Quaternionf(), fov, aspect, sun, stabilization).getFirst();
			var b = ShadowCascades.fit(settings, new Vector3d(position).add(movement,0,0),
				new Quaternionf(), fov, aspect, sun, stabilization).getFirst();
			var fixedBefore = a.cameraRelativeToShadow().transformPosition(new Vector3f(0,0,-10));
			var fixedAfter = b.cameraRelativeToShadow().transformPosition(new Vector3f((float)-movement,0,-10));
			if (Math.abs(fixedBefore.x-fixedAfter.x)>1e-5F || Math.abs(fixedBefore.y-fixedAfter.y)>1e-5F)
				throw new AssertionError("Local shadow stabilization lost camera translation locking");
			stabilization.reset();
			var reset = ShadowCascades.fit(settings, position, new Quaternionf(), fov, aspect, sun, stabilization).getFirst();
			if (!reset.cameraRelativeToShadow().equals(a.cameraRelativeToShadow()))
				throw new AssertionError("Shadow grid retained previous-world phase");
		}
		Vector3f previousRight = null;
		for (float angle : new float[]{-.15F,-.1416F,-.1415F,-.00002F,0,.00002F,.1415F,.1416F,.15F}) {
			var light = new Vector3f(-(float)Math.sin(angle),(float)Math.cos(angle),0);
			var matrix = ShadowCascades.fit(settings, origin, new Quaternionf(), fov, aspect, light,
				new ShadowCascades.Stabilization()).getFirst().cameraRelativeToShadow();
			var right = new Vector3f(matrix.m00(),matrix.m10(),matrix.m20()).normalize();
			if (previousRight != null && previousRight.dot(right)<.99999F)
				throw new AssertionError("Shadow grid changes orientation near noon");
			previousRight = right;
		}
		try {
			ShadowCascades.fit(settings, origin, new Quaternionf(), fov, aspect, new Vector3f());
			throw new AssertionError("Zero sun direction accepted");
		} catch (IllegalArgumentException expected) {
			// No singular matrices may enter the frame bindings.
		}
	}

	private static void assertInside(final Vector3f clip) {
		if (!clip.isFinite() || Math.abs(clip.x) > 1.0001F || Math.abs(clip.y) > 1.0001F
			|| clip.z < -0.0001F || clip.z > 1.0001F) {
			throw new AssertionError("Shadow cascade excludes receiver/caster: " + clip);
		}
	}
}
