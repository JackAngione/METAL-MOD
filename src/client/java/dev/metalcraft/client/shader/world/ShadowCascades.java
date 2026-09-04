package dev.metalcraft.client.shader.world;

import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Fits sun-shadow cascades without depending on Minecraft or owning GPU resources.
 *
 * <p>Input camera rotation maps view-space axes to world axes; view forward is -Z.
 * Output matrices take camera-relative, world-oriented positions into Metal clip space
 * (XY in [-1,1], depth in [0,1], closer to the sun is zero). Camera position is double
 * precision only for world-locked texel snapping; large world coordinates never reach MSL.
 */
public final class ShadowCascades {
	private ShadowCascades() {
	}

	public record Settings(int count, int resolution, float near, float distance,
		float splitWeight, float casterExtension) {
		public Settings {
			if (count < 1 || count > 4 || resolution < 4
				|| !Float.isFinite(near) || !Float.isFinite(distance) || near <= 0 || distance <= near
				|| !Float.isFinite(splitWeight) || splitWeight < 0 || splitWeight > 1
				|| !Float.isFinite(casterExtension) || casterExtension < 0) {
				throw new IllegalArgumentException("Invalid sun-shadow cascade settings");
			}
		}
	}

	public record Cascade(float near, float far, float texelSize, Matrix4fc cameraRelativeToShadow) {
	}

	/** Returns cascades in increasing positive view-depth order. No jitter enters this calculation. */
	public static List<Cascade> fit(final Settings settings, final Vector3dc cameraPosition,
		final Quaternionfc cameraRotation, final float verticalFovRadians, final float aspect,
		final Vector3fc directionToSun) {
		if (!cameraPosition.isFinite() || !Float.isFinite(verticalFovRadians)
			|| verticalFovRadians <= 0 || verticalFovRadians >= Math.PI
			|| !Float.isFinite(aspect) || aspect <= 0 || !directionToSun.isFinite()
			|| directionToSun.lengthSquared() < 1.0e-12F) {
			throw new IllegalArgumentException("Invalid camera or sun direction");
		}
		Quaternionf rotation = new Quaternionf(cameraRotation);
		if (!Float.isFinite(rotation.lengthSquared()) || rotation.lengthSquared() < 1.0e-12F) {
			throw new IllegalArgumentException("Invalid camera rotation");
		}
		rotation.normalize();
		Vector3f forward = rotation.transform(new Vector3f(0, 0, -1));
		Vector3f sun = new Vector3f(directionToSun).normalize();
		// Avoid a degenerate basis at noon, when the sun is parallel to world up.
		Vector3f reference = Math.abs(sun.y) > 0.99F ? new Vector3f(0, 0, 1) : new Vector3f(0, 1, 0);
		Vector3f right = reference.cross(sun).normalize();
		Vector3f up = new Vector3f(sun).cross(right).normalize();
		double tanHalfFov = Math.tan(verticalFovRadians * 0.5);
		double diagonal = tanHalfFov * tanHalfFov * (1.0 + (double)aspect * aspect);
		List<Cascade> result = new ArrayList<>(settings.count());
		float near = settings.near();
		for (int index = 1; index <= settings.count(); index++) {
			double fraction = (double)index / settings.count();
			double logarithmic = settings.near() * Math.pow(settings.distance() / settings.near(), fraction);
			double uniform = settings.near() + (settings.distance() - settings.near()) * fraction;
			float far = index == settings.count() ? settings.distance()
				: (float)(settings.splitWeight() * logarithmic + (1.0 - settings.splitWeight()) * uniform);
			double middle = (near + (double)far) * 0.5;
			double halfDepth = (far - (double)near) * 0.5;
			// A rotation-invariant bounding sphere around all eight slice corners. Quantization
			// keeps the footprint stable; one texel of guard covers the snapping displacement.
			double radius = Math.ceil(Math.sqrt(far * (double)far * diagonal + halfDepth * halfDepth) * 16.0) / 16.0;
			double extent = radius * settings.resolution() / (settings.resolution() - 2.0);
			double texel = 2.0 * extent / settings.resolution();
			Vector3d center = new Vector3d(forward).mul(middle);
			double centerX = snappedRelativeCenter(cameraPosition, center, right, texel);
			double centerY = snappedRelativeCenter(cameraPosition, center, up, texel);
			double centerZ = dot(center, sun);
			double depthRange = 2.0 * radius + settings.casterExtension();
			Matrix4f matrix = new Matrix4f()
				.m00((float)(right.x / extent)).m10((float)(right.y / extent)).m20((float)(right.z / extent))
				.m01((float)(up.x / extent)).m11((float)(up.y / extent)).m21((float)(up.z / extent))
				.m02((float)(-sun.x / depthRange)).m12((float)(-sun.y / depthRange)).m22((float)(-sun.z / depthRange))
				.m30((float)(-centerX / extent)).m31((float)(-centerY / extent))
				.m32((float)((centerZ + radius + settings.casterExtension()) / depthRange));
			if (!matrix.isFinite()) throw new IllegalArgumentException("Shadow projection exceeds finite range");
			result.add(new Cascade(near, far, (float)texel, matrix));
			near = far;
		}
		return List.copyOf(result);
	}

	private static double snappedRelativeCenter(final Vector3dc camera, final Vector3dc center,
		final Vector3fc axis, final double texel) {
		double origin = dot(camera, axis);
		return Math.rint((origin + dot(center, axis)) / texel) * texel - origin;
	}

	private static double dot(final Vector3dc position, final Vector3fc axis) {
		return position.x() * axis.x() + position.y() * axis.y() + position.z() * axis.z();
	}
}
