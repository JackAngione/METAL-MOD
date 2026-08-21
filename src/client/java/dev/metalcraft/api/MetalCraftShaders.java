package dev.metalcraft.api;

public final class MetalCraftShaders {
	private static final MetalCraftShaderRegistry REGISTRY = new MetalCraftShaderRegistry();

	private MetalCraftShaders() {
	}

	public static MetalCraftShaderRegistry registry() {
		return REGISTRY;
	}
}
