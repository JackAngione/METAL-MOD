package dev.metalcraft.client.shader;

import dev.metalcraft.client.metal.MetalCommandBuffer;
import dev.metalcraft.client.metal.MetalTexture;

/**
 * Encodes pack passes into a command buffer. Does not present and does not write a drawable.
 */
public interface ShaderFrameExecutor {
	/** Whether any executable pack pass needs the stored pre-hand world-depth snapshot. */
	default boolean requiresWorldDepth() {
		return true;
	}

	/**
	 * Encodes every executable fullscreen/compute pass into {@code commands} in compiled
	 * group order. Geometry, shadow, and merged-resolve groups are owned by the world adapter.
	 * Does not skip {@code enabled_by} passes.
	 *
	 * @return false unless pack target {@code post_color} exists and its width/height equal
	 *         {@code bindings.width/height}; caller then blits vanilla {@code scene}
	 */
	boolean encode(MetalCommandBuffer commands, FrameBindings bindings);

	/**
	 * Encodes the active pack so its first color write lands in {@code output}
	 * (test stand-in for {@code post_color}). Does not present.
	 *
	 * @return false unless {@code post_color} has been allocated, its size equals {@code scene},
	 *         and {@code output} is {@code BGRA8_UNORM} at that same size
	 */
	boolean encodeForTesting(MetalCommandBuffer commands, MetalTexture scene, MetalTexture output);
}
