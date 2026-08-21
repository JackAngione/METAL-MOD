package dev.metalcraft.api;

/**
 * Fabric entrypoint contract for renderer add-ons using the {@code metalcraft-shaders} key.
 * Implementations register backend-neutral Blaze3D render pipelines here.
 */
@FunctionalInterface
public interface MetalCraftShaderExtension {
	void registerShaders(MetalCraftShaderContext context);
}
