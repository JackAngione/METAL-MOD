package dev.metalcraft.client.metal;

/**
 * Observes named Blaze3D uniforms bound while a world G-buffer pass is open.
 *
 * <p>The geometry adapter registers this; the render-pass backend does not mention packs.
 */
@FunctionalInterface
public interface WorldUniformCapture {
	void capture(String name, MetalBuffer buffer, long offset, long size);
}
