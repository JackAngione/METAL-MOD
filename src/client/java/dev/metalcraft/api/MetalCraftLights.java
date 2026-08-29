package dev.metalcraft.api;

/** Entry point for registering dynamic local-light providers. */
public final class MetalCraftLights {
	private static final MetalCraftLightRegistry REGISTRY = new MetalCraftLightRegistry();

	private MetalCraftLights() {
	}

	public static MetalCraftLightRegistry registry() {
		return REGISTRY;
	}
}
