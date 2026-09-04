package dev.metalcraft.client.shader;

import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;

/** Host-supplied scene attachments and the size last passed to {@link ShaderPackRuntime#resize}. */
public record FrameBindings(MetalTexture scene, MetalTextureView sceneView, int width, int height) {
	public FrameBindings {
		if (scene == null) {
			throw new NullPointerException("scene");
		}
		if (sceneView == null) {
			throw new NullPointerException("sceneView");
		}
	}
}
