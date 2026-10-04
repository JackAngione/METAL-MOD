package dev.metalcraft.client.shader.water;

import org.jspecify.annotations.Nullable;

/** The same frame's prepared draw list determines whether opaque copies have a consumer. */
public final class WaterSnapshotPolicy {
	private WaterSnapshotPolicy() { }

	public static boolean shadeWater(boolean enabled, WaterRoutingDebug.Mode mode) {
		return enabled || mode != WaterRoutingDebug.Mode.OFF;
	}

	public static boolean capture(boolean preparedWater, boolean enabled,
		WaterRoutingDebug.Mode mode, @Nullable WaterFrameInputs inputs) {
		if (!preparedWater || inputs == null) return false;
		if (mode == WaterRoutingDebug.Mode.SURFACE_DEPTH || mode == WaterRoutingDebug.Mode.OPAQUE_DEPTH) return true;
		if (!enabled || inputs.cameraSubmerged() || !inputs.refractionEnabled()) return false;
		return mode == WaterRoutingDebug.Mode.OFF || mode == WaterRoutingDebug.Mode.REFRACTION_OFF
			|| mode == WaterRoutingDebug.Mode.FOAM_OFF || mode == WaterRoutingDebug.Mode.UNDERWATER_DISTORTION_OFF;
	}
}
