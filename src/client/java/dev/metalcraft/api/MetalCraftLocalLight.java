package dev.metalcraft.api;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

/** An immutable, world-space local light emitted by a registered provider. */
public record MetalCraftLocalLight(
	long stableId,
	Vec3 position,
	float red,
	float green,
	float blue,
	float intensity,
	float radius,
	boolean shadowEligible
) {
	public MetalCraftLocalLight {
		Objects.requireNonNull(position, "position");
		if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
			throw new IllegalArgumentException("Local-light position must be finite: " + position);
		}
		if (!finiteNonNegative(red) || !finiteNonNegative(green) || !finiteNonNegative(blue)
			|| red == 0.0F && green == 0.0F && blue == 0.0F) {
			throw new IllegalArgumentException("Local-light RGB must be finite, non-negative, and non-black");
		}
		if (!Float.isFinite(intensity) || intensity <= 0.0F) {
			throw new IllegalArgumentException("Local-light intensity must be finite and positive: " + intensity);
		}
		if (!Float.isFinite(radius) || radius <= 0.0F) {
			throw new IllegalArgumentException("Local-light radius must be finite and positive: " + radius);
		}
	}

	private static boolean finiteNonNegative(final float value) {
		return Float.isFinite(value) && value >= 0.0F;
	}
}
