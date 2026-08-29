package dev.metalcraft.api;

import java.util.function.Consumer;

/** Supplies the complete set of this provider's dynamic visual lights for the current frame. */
@FunctionalInterface
public interface MetalCraftLightProvider {
	/** Called on the render thread; emitted stable IDs are scoped to this registered provider. */
	void collectLights(Consumer<MetalCraftLocalLight> output);
}
