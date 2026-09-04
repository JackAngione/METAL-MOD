package dev.metalcraft.client.metal;

/**
 * Encodes a fullscreen resolve into a still-open G-buffer pass before that encoder ends.
 *
 * <p>The geometry adapter registers this; the command encoder does not mention packs or worlds.
 */
@FunctionalInterface
public interface DeferredResolveHook {
	/** @return true if a resolve draw was encoded into the still-open pass */
	boolean encodeMergedResolve(MetalRenderPass openPass);
}
