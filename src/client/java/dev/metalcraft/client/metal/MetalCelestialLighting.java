package dev.metalcraft.client.metal;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType.Skybox;
import org.joml.Vector3f;

/** Derives the directional light from the same celestial transform that draws Minecraft's sky. */
final class MetalCelestialLighting {
	private static final float HORIZON_EPSILON = 1.0E-6F;

	enum Source {
		NONE(0.0F),
		SUN(1.0F),
		MOON(2.0F);

		private final float shaderValue;

		Source(final float shaderValue) {
			this.shaderValue = shaderValue;
		}

		float shaderValue() {
			return this.shaderValue;
		}
	}

	record State(
		Source source,
		Vector3f worldDirection,
		Vector3f viewDirection,
		float elevation,
		float intensity
	) {
		State {
			worldDirection = new Vector3f(worldDirection);
			viewDirection = new Vector3f(viewDirection);
		}

		boolean active() {
			return this.source != Source.NONE && this.intensity > 1.0E-4F;
		}
	}

	private MetalCelestialLighting() {
	}

	/**
	 * Minecraft draws both discs from +Y after {@code Y(-90 degrees) * X(angle)}. Applying that
	 * transform gives {@code (-sin(angle), cos(angle), 0)} in world space.
	 */
	static Vector3f directionFromSkyTransform(final float angle) {
		return new Vector3f(-(float)Math.sin(angle), (float)Math.cos(angle), 0.0F).normalize();
	}

	static State derive(final SkyRenderState sky, final CameraRenderState camera) {
		if (sky.skybox != Skybox.OVERWORLD) {
			return disabled();
		}

		Vector3f sun = directionFromSkyTransform(sky.sunAngle);
		Vector3f moon = directionFromSkyTransform(sky.moonAngle);
		Source source;
		Vector3f worldDirection;
		if (sun.y >= moon.y && sun.y >= -HORIZON_EPSILON) {
			source = Source.SUN;
			worldDirection = sun;
		} else if (moon.y >= -HORIZON_EPSILON) {
			source = Source.MOON;
			worldDirection = moon;
		} else {
			return disabled();
		}

		Vector3f viewDirection = camera.viewRotationMatrix
			.transformDirection(worldDirection, new Vector3f())
			.normalize();
		float intensity = Math.max(0.0F, Math.min(1.0F, sky.rainBrightness));
		return new State(source, worldDirection, viewDirection, worldDirection.y, intensity);
	}

	private static State disabled() {
		return new State(Source.NONE, new Vector3f(), new Vector3f(), 0.0F, 0.0F);
	}
}
