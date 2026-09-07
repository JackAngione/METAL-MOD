package dev.metalcraft.client.shader.water;

/**
 * Render-time diagnostic of production metadata and bound opaque depth.
 *
 * <p>Modes are per-draw uniforms. They do not modify BLOCK vertices or require a terrain rebuild.
 */
public final class WaterRoutingDebug {
	public enum Mode {
		OFF(0),
		IDENTITY(1),
		SURFACE_DEPTH(2),
		OPAQUE_DEPTH(3),
		BASELINE(4),
		NORMALS(5);

		public final int gpuValue;

		Mode(final int gpuValue) {
			this.gpuValue = gpuValue;
		}
	}

	private static volatile Mode mode = Mode.OFF;

	private WaterRoutingDebug() { }

	public static Mode mode() {
		return mode;
	}

	public static void setMode(final Mode value) {
		mode = value == null ? Mode.OFF : value;
	}

	public static boolean enabled() {
		return mode == Mode.IDENTITY;
	}

	public static void setEnabled(final boolean value) {
		mode = value ? Mode.IDENTITY : Mode.OFF;
	}
}
