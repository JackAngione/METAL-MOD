package dev.metalcraft.client.shader.water;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Focused CPU checks for underwater transition state and its immutable GPU layout. */
public final class UnderwaterFrameInputsSmoke {
	private static final float EPSILON = 1.0e-6F;

	private UnderwaterFrameInputsSmoke() {
	}

	public static void main(final String[] args) {
		run();
	}

	public static void run() {
		var fog = new net.minecraft.client.renderer.fog.FogData();
		fog.environmentalStart = -8.0F;
		fog.environmentalEnd = 24.0F;
		fog.color.set(0.02F, 0.12F, 0.8F, 1.0F);
		UnderwaterAppearance.apply(fog);
		if (fog.environmentalStart < 0.0F || fog.environmentalEnd <= 24.0F
			|| fog.skyEnd != fog.environmentalEnd || fog.cloudEnd != fog.environmentalEnd
			|| fog.color.z - fog.color.x >= 0.4F || fog.color.w != 1.0F) {
			throw new AssertionError("Clear water must preserve near visibility and reduce saturated blue haze");
		}
		fog.color.set(0.0F, 0.0F, 0.0F, 1.0F);
		UnderwaterAppearance.apply(fog);
		if (fog.color.x != 0.0F || fog.color.y != 0.0F || fog.color.z != 0.0F) {
			throw new AssertionError("Underwater haze must not add light in darkness");
		}
		assertIdentity(UnderwaterFrameInputs.NONE, "missing frame");
		assertIdentity(UnderwaterFrameInputs.create(12.0F, false, 1.0, true), "water exit");
		assertIdentity(UnderwaterFrameInputs.create(12.0F, true, 1.0, false), "disabled distortion");
		assertIdentity(UnderwaterFrameInputs.create(12.0F, true, Double.NaN, true), "invalid depth");
		assertIdentity(UnderwaterFrameInputs.create(Float.NaN, true, 1.0, true), "invalid time");
		assertIdentity(UnderwaterFrameInputs.create(12.0F, true, -1.0, true), "negative depth");

		UnderwaterFrameInputs quarter = UnderwaterFrameInputs.create(12.0F, true, 0.0625, true);
		UnderwaterFrameInputs midpoint = UnderwaterFrameInputs.create(12.0F, true, 0.125, true);
		UnderwaterFrameInputs threeQuarter = UnderwaterFrameInputs.create(12.0F, true, 0.1875, true);
		UnderwaterFrameInputs full = UnderwaterFrameInputs.create(12.0F, true, 0.25, true);
		if (!(quarter.strength() > 0.0F && quarter.strength() < midpoint.strength()
			&& midpoint.strength() < threeQuarter.strength() && threeQuarter.strength() < full.strength())) {
			throw new AssertionError("Underwater entry did not fade smoothly and monotonically");
		}
		assertClose(midpoint.strength(), 0.5F, "half-depth smoothstep");
		assertClose(full.strength(), 1.0F, "full-depth strength");
		assertClose(UnderwaterFrameInputs.create(12.0F, true, 10.0, true).strength(), 1.0F,
			"deep-water strength clamp");

		UnderwaterFrameInputs wrapped = new UnderwaterFrameInputs(1031.25F, 2.0F);
		assertClose(wrapped.animationSeconds(), 7.25F, "positive time period");
		assertClose(new UnderwaterFrameInputs(-1.0F, 1.0F).animationSeconds(), 1023.0F,
			"negative time period");
		assertClose(wrapped.strength(), 1.0F, "constructor strength clamp");
		assertGpuLayout(wrapped);
		assertInvalidConstructor(Float.NaN, 1.0F, "invalid animation time");
		assertInvalidConstructor(0.0F, Float.NaN, "invalid strength");
	}

	private static void assertIdentity(final UnderwaterFrameInputs input, final String label) {
		if (input.strength() != 0.0F) {
			throw new AssertionError(label + " did not select exact identity: " + input.strength());
		}
	}

	private static void assertGpuLayout(final UnderwaterFrameInputs input) {
		ByteBuffer bytes = ByteBuffer.allocate(UnderwaterFrameInputs.UNIFORM_BYTES + 4)
			.order(ByteOrder.LITTLE_ENDIAN);
		bytes.position(4);
		input.write(bytes);
		if (bytes.position() != 4 + UnderwaterFrameInputs.UNIFORM_BYTES
			|| bytes.getFloat(4) != input.animationSeconds() || bytes.getFloat(8) != input.strength()
			|| bytes.getFloat(12) != 0.0F || bytes.getFloat(16) != 0.0F) {
			throw new AssertionError("Underwater GPU frame layout changed");
		}
	}

	private static void assertInvalidConstructor(final float seconds, final float strength,
		final String label) {
		try {
			new UnderwaterFrameInputs(seconds, strength);
			throw new AssertionError(label + " was accepted");
		} catch (IllegalArgumentException expected) {
			// Invalid state must fail before it can be uploaded.
		}
	}

	private static void assertClose(final float actual, final float expected, final String label) {
		if (!Float.isFinite(actual) || Math.abs(actual - expected) > EPSILON) {
			throw new AssertionError(label + " expected " + expected + ", got " + actual);
		}
	}
}
