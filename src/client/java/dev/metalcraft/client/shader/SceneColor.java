package dev.metalcraft.client.shader;

import org.joml.Vector4f;
import org.joml.Vector4fc;

/**
 * Host sRGB RGB transfer matching {@code standard/shared/color.metal}. Alpha, material IDs,
 * normals and depth must not pass through these functions. Negative RGB clips to zero; values
 * above 1 are preserved and are not tone-mapped.
 */
public final class SceneColor {
	private SceneColor() {
	}

	public static float srgbToLinear(final float encoded) {
		float x = Math.max(encoded, 0.0F);
		return x <= 0.04045F ? x / 12.92F : (float)Math.pow((x + 0.055F) / 1.055F, 2.4);
	}

	public static float linearToSrgb(final float linear) {
		float x = Math.max(linear, 0.0F);
		return x <= 0.0031308F ? 12.92F * x : 1.055F * (float)Math.pow(x, 1.0 / 2.4) - 0.055F;
	}

	/** Decodes RGB; alpha is unchanged coverage. */
	public static Vector4f decodeRgb(final Vector4fc encoded) {
		if (encoded == null) throw new NullPointerException("encoded");
		return new Vector4f(
			srgbToLinear(encoded.x()),
			srgbToLinear(encoded.y()),
			srgbToLinear(encoded.z()),
			encoded.w());
	}
}
