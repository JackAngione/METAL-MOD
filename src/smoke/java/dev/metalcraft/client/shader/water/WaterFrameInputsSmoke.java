package dev.metalcraft.client.shader.water;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/** Focused CPU checks for frame validity, defensive copies, and long-running time precision. */
public final class WaterFrameInputsSmoke {
	private WaterFrameInputsSmoke() {
	}

	public static void main(final String[] args) {
		run();
	}

	public static void run() {
		Matrix4f projection = reverseZProjection();
		Vector3d camera = new Vector3d(29_999_999.875, -64.25, -29_999_999.625);
		var input = WaterFrameInputs.create(projection, camera, Long.MAX_VALUE, 0.75F, true)
			.orElseThrow(() -> new AssertionError("Valid reverse-Z frame rejected"));

		Matrix4f expectedProjection = new Matrix4f(projection);
		projection.zero();
		camera.zero();
		assertMatrixEquals(expectedProjection, input.projection(), "Constructor inputs escaped");
		if (!input.cameraWorldPosition().equals(29_999_999.875, -64.25, -29_999_999.625)) {
			throw new AssertionError("Double camera position lost precision");
		}
		if (!input.cameraSubmerged()) throw new AssertionError("Submerged state was not retained");
		if (input.refractionEnabled()) throw new AssertionError("Refraction was not fail-closed by default");
		if (!(input.animationSeconds() >= 0.0F
			&& input.animationSeconds() < WaterFrameInputs.ANIMATION_PERIOD_SECONDS)) {
			throw new AssertionError("Animation time escaped its periodic range");
		}

		Matrix4f escapedProjection = input.projection().zero();
		Matrix4f escapedInverse = input.inverseProjection().zero();
		Vector3d escapedCamera = input.cameraWorldPosition().zero();
		assertMatrixEquals(expectedProjection, input.projection(), "Projection getter leaked storage");
		if (!new Matrix4f(expectedProjection).invert().equals(input.inverseProjection(), 1.0e-6F)) {
			throw new AssertionError("Inverse getter leaked storage");
		}
		if (input.cameraWorldPosition().equals(escapedCamera)) {
			throw new AssertionError("Camera getter leaked storage");
		}
		if (!escapedProjection.equals(new Matrix4f().zero()) || !escapedInverse.equals(new Matrix4f().zero())) {
			throw new AssertionError("Smoke mutation did not execute");
		}
		assertGpuLayout(input, expectedProjection);
		var refracting = input.withRefraction(true);
		if (!refracting.refractionEnabled() || input.refractionEnabled()) {
			throw new AssertionError("Refraction gate was not immutable");
		}
		assertGpuRefractionFlag(refracting);
		assertSkyInputs();

		float last = WaterFrameInputs.create(reverseZProjection(), new Vector3d(),
			WaterFrameInputs.ANIMATION_PERIOD_TICKS - 1, 1.0F, false).orElseThrow().animationSeconds();
		float wrapped = WaterFrameInputs.create(reverseZProjection(), new Vector3d(),
			WaterFrameInputs.ANIMATION_PERIOD_TICKS, 0.0F, false).orElseThrow().animationSeconds();
		if (last != 0.0F || wrapped != 0.0F) throw new AssertionError("Periodic wrap is not exact");
		float negative = WaterFrameInputs.create(reverseZProjection(), new Vector3d(), -1L, 0.5F, false)
			.orElseThrow().animationSeconds();
		if (!(negative > 0.0F && negative < WaterFrameInputs.ANIMATION_PERIOD_SECONDS)) {
			throw new AssertionError("Negative world time was not bounded");
		}

		assertRejected(null, new Vector3d(), 0.0F, "Missing projection");
		assertRejected(reverseZProjection(), null, 0.0F, "Missing camera");
		assertRejected(new Matrix4f(), new Vector3d(), 0.0F, "Identity projection");
		assertRejected(new Matrix4f().zero(), new Vector3d(), 0.0F, "Singular projection");
		assertRejected(new Matrix4f().perspective((float)Math.toRadians(70), 16.0F / 9.0F,
			0.05F, 1024.0F, true), new Vector3d(), 0.0F, "Forward-Z projection");
		assertRejected(new Matrix4f().m00(Float.NaN), new Vector3d(), 0.0F, "Non-finite projection");
		assertRejected(reverseZProjection(), new Vector3d(Double.NaN, 0, 0), 0.0F, "Non-finite camera");
		assertRejected(reverseZProjection(), new Vector3d(Double.MAX_VALUE, 0, 0), 0.0F,
			"Camera outside split-float range");
		assertRejected(reverseZProjection(), new Vector3d(), Float.NaN, "Non-finite partial tick");
		assertRejected(reverseZProjection(), new Vector3d(), -0.01F, "Negative partial tick");
		assertRejected(reverseZProjection(), new Vector3d(), 1.01F, "Oversized partial tick");
	}

	private static void assertSkyInputs() {
		var base = WaterFrameInputs.create(reverseZProjection(), new Vector3d(), 0L, 0.0F, false).orElseThrow();
		var sky = new net.minecraft.client.renderer.state.level.SkyRenderState();
		sky.skybox = net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD;
		sky.skyColor = 0xff80a0ff;
		sky.sunAngle = 0.0F;
		sky.rainBrightness = 1.0F;
		var noon = base.withSky(sky);
		ByteBuffer bytes = ByteBuffer.allocate(WaterFrameInputs.UNIFORM_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		noon.write(bytes);
		if (bytes.getFloat(180) != 1.0F || bytes.getFloat(188) != 1.0F || bytes.getFloat(204) != 1.0F
			|| Math.abs(bytes.getFloat(192) - dev.metalcraft.client.shader.SceneColor.srgbToLinear(128 / 255.0F)) > 1e-6F) {
			throw new AssertionError("Extracted noon lighting GPU layout/linear color incorrect");
		}
		sky.rainBrightness = 0.0F;
		bytes.clear(); base.withSky(sky).write(bytes);
		if (bytes.getFloat(188) != 0.0F) throw new AssertionError("Rain did not suppress sun");
		sky.sunAngle = (float)Math.PI;
		sky.rainBrightness = 1.0F;
		bytes.clear(); base.withSky(sky).write(bytes);
		if (bytes.getFloat(188) != 0.0F || bytes.getFloat(200) > 0.021F) {
			throw new AssertionError("Night retained daytime lighting");
		}
		sky.skybox = net.minecraft.world.level.dimension.DimensionType.Skybox.END;
		bytes.clear(); base.withSky(sky).write(bytes);
		for (int offset = 176; offset < 208; offset += 4) {
			if (bytes.getFloat(offset) != 0.0F) throw new AssertionError("Nonstandard sky retained normal lighting");
		}
		bytes.clear(); noon.write(bytes);
		if (bytes.getFloat(188) != 1.0F) throw new AssertionError("Mutable sky escaped immutable frame capture");
		bytes.clear(); noon.withRefraction(true).write(bytes);
		if (bytes.getFloat(188) != 1.0F || bytes.getFloat(204) != 1.0F || bytes.getInt(168) != 1) {
			throw new AssertionError("Refraction gate dropped immutable sky lighting");
		}
		bytes.clear(); base.write(bytes);
		if (bytes.getFloat(188) != 0.0F) throw new AssertionError("withSky mutated source frame");
	}

	private static Matrix4f reverseZProjection() {
		return new Matrix4f().perspective((float)Math.toRadians(70), 16.0F / 9.0F,
			1024.0F, 0.05F, true);
	}

	private static void assertRejected(final Matrix4f projection, final Vector3d camera,
		final float partialTick, final String name) {
		if (WaterFrameInputs.create(projection, camera, 0L, partialTick, false).isPresent()) {
			throw new AssertionError(name + " was accepted");
		}
	}

	private static void assertMatrixEquals(final Matrix4f expected, final Matrix4f actual,
		final String message) {
		if (!expected.equals(actual, 0.0F)) throw new AssertionError(message);
	}

	private static void assertGpuLayout(final WaterFrameInputs input, final Matrix4f expectedProjection) {
		ByteBuffer bytes = ByteBuffer.allocate(WaterFrameInputs.UNIFORM_BYTES + 8).order(ByteOrder.LITTLE_ENDIAN);
		bytes.position(8);
		input.write(bytes);
		if (bytes.position() != 8 + WaterFrameInputs.UNIFORM_BYTES) {
			throw new AssertionError("Uniform writer advanced by the wrong byte count");
		}
		Matrix4f encodedProjection = readMatrix(bytes, 8);
		Matrix4f encodedInverse = readMatrix(bytes, 72);
		assertMatrixEquals(expectedProjection, encodedProjection, "GPU projection layout changed");
		if (!new Matrix4f(expectedProjection).invert().equals(encodedInverse, 0.0F)) {
			throw new AssertionError("GPU inverse projection layout changed");
		}
		Vector3d camera = input.cameraWorldPosition();
		for (int axis = 0; axis < 3; axis++) {
			double reconstructed = (double)bytes.getFloat(8 + 128 + axis * 4)
				+ bytes.getFloat(8 + 144 + axis * 4);
			if (Math.abs(reconstructed - camera.get(axis)) > 1.0e-7) {
				throw new AssertionError("GPU split camera lost precision on axis " + axis);
			}
		}
		if (bytes.getFloat(8 + 160) != input.animationSeconds() || bytes.getInt(8 + 164) != 1
			|| bytes.getInt(8 + 168) != 0 || bytes.getInt(8 + 172) != 0) {
			throw new AssertionError("GPU frame flags/padding layout changed");
		}
		try {
			input.write(ByteBuffer.allocate(WaterFrameInputs.UNIFORM_BYTES - 1));
			throw new AssertionError("Undersized uniform destination was accepted");
		} catch (IllegalArgumentException expected) {
			// Missing GPU storage must disable binding rather than truncate a frame.
		}
	}

	private static void assertGpuRefractionFlag(final WaterFrameInputs input) {
		ByteBuffer bytes = ByteBuffer.allocate(WaterFrameInputs.UNIFORM_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		input.write(bytes);
		if (bytes.getInt(168) != 1 || bytes.getInt(172) != 0) {
			throw new AssertionError("Refraction gate/padding GPU layout changed");
		}
	}

	private static Matrix4f readMatrix(final ByteBuffer bytes, final int offset) {
		return new Matrix4f(
			bytes.getFloat(offset), bytes.getFloat(offset + 4), bytes.getFloat(offset + 8), bytes.getFloat(offset + 12),
			bytes.getFloat(offset + 16), bytes.getFloat(offset + 20), bytes.getFloat(offset + 24), bytes.getFloat(offset + 28),
			bytes.getFloat(offset + 32), bytes.getFloat(offset + 36), bytes.getFloat(offset + 40), bytes.getFloat(offset + 44),
			bytes.getFloat(offset + 48), bytes.getFloat(offset + 52), bytes.getFloat(offset + 56), bytes.getFloat(offset + 60));
	}
}
