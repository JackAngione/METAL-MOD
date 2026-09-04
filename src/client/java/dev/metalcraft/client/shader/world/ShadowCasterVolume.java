package dev.metalcraft.client.shader.world;

import org.joml.Matrix4fc;

/** Conservative AABB test against an affine Metal clip volume (including z >= 0, not z >= -w). */
final class ShadowCasterVolume {
	private ShadowCasterVolume() { }

	static boolean intersects(final Matrix4fc m, final float x0, final float y0, final float z0,
		final float x1, final float y1, final float z1) {
		return maximum(m.m00(), m.m10(), m.m20(), m.m30() + 1, x0,y0,z0,x1,y1,z1) >= 0
			&& maximum(-m.m00(), -m.m10(), -m.m20(), 1 - m.m30(), x0,y0,z0,x1,y1,z1) >= 0
			&& maximum(m.m01(), m.m11(), m.m21(), m.m31() + 1, x0,y0,z0,x1,y1,z1) >= 0
			&& maximum(-m.m01(), -m.m11(), -m.m21(), 1 - m.m31(), x0,y0,z0,x1,y1,z1) >= 0
			&& maximum(m.m02(), m.m12(), m.m22(), m.m32(), x0,y0,z0,x1,y1,z1) >= 0
			&& maximum(-m.m02(), -m.m12(), -m.m22(), 1 - m.m32(), x0,y0,z0,x1,y1,z1) >= 0;
	}

	private static float maximum(final float a, final float b, final float c, final float d,
		final float x0, final float y0, final float z0, final float x1, final float y1, final float z1) {
		return a * (a >= 0 ? x1 : x0) + b * (b >= 0 ? y1 : y0) + c * (c >= 0 ? z1 : z0) + d;
	}
}
