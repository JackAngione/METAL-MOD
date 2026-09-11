package dev.metalcraft.client.shader.water;

import java.nio.ByteBuffer;

/** World-only distortion; the tuned Minecraft fog owns distance attenuation. */
public record UnderwaterFrameInputs(float animationSeconds, float strength) {
	public static final UnderwaterFrameInputs NONE = new UnderwaterFrameInputs(0.0F, 0.0F);
	public static final int UNIFORM_BYTES = 16;

	public UnderwaterFrameInputs {
		if (!Float.isFinite(animationSeconds) || !Float.isFinite(strength)) {
			throw new IllegalArgumentException("Underwater frame values must be finite");
		}
		animationSeconds = (animationSeconds % 1024.0F + 1024.0F) % 1024.0F;
		strength = Math.clamp(strength, 0.0F, 1.0F);
	}

	/** The first quarter block below the fluid surface smoothly introduces distortion. */
	public static UnderwaterFrameInputs create(final float seconds, final boolean submerged,
		final double depthBelowSurface, final boolean enabled) {
		if (!submerged || !enabled || !Double.isFinite(depthBelowSurface)
			|| !Float.isFinite(seconds)) return NONE;
		float t = (float)Math.clamp(depthBelowSurface / 0.25, 0.0, 1.0);
		return new UnderwaterFrameInputs(seconds, t * t * (3.0F - 2.0F * t));
	}

	public void write(final ByteBuffer bytes) {
		bytes.putFloat(this.animationSeconds).putFloat(this.strength).putFloat(0.0F).putFloat(0.0F);
	}
}
