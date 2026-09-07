package dev.metalcraft.client.shader.water;

/** Render-time diagnostic of production metadata; does not modify or rebuild BLOCK vertices. */
public final class WaterRoutingDebug {
	private static volatile boolean enabled;
	private WaterRoutingDebug() { }
	public static boolean enabled() { return enabled; }
	public static void setEnabled(final boolean value) { enabled = value; }
}
