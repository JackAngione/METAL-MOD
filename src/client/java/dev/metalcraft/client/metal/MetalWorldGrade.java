package dev.metalcraft.client.metal;

import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.WorldComposition;
import java.util.List;

/** Owns the stored world-depth snapshot and the grade-to-main copy at the world seam. */
final class MetalWorldGrade implements AutoCloseable {
	private MetalTexture depth;
	private MetalTextureView depthView;
	private MetalRenderPipeline copy;
	private MetalTexture.Format format;

	void encode(final MetalGpuDevice device, final MetalCommandBuffer commands,
		final ShaderPackRuntime runtime, final MetalGpuTextureView scene, final MetalGpuTextureView worldDepth) {
		int width = scene.getWidth(0), height = scene.getHeight(0);
		this.prepare(device.metal(), width, height, scene.attachment().descriptor().format());
		runtime.resizeToScene(width, height);
		commands.copyTexture(worldDepth.attachment(), this.depth, 0, 0, 0, 0, 0, width, height);
		if (!runtime.executor().orElseThrow().encode(commands, WorldComposition.world(
			scene.attachment(), scene.metal(), width, height, this.depth, this.depthView, null))) {
			throw new IllegalStateException("World grade inputs are unavailable");
		}
		MetalTexture post = runtime.target("post_color");
		try (MetalTextureView source = post.createView();
			 MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
				 new MetalRenderPass.ColorAttachment(scene.attachment(), MetalRenderPass.LoadAction.DONT_CARE,
					 MetalRenderPass.StoreAction.STORE, 0, 0, 0, 0)))) {
			pass.setPipeline(this.copy);
			pass.setTexture(0, source, MetalRenderPass.STAGE_FRAGMENT);
			pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
		}
	}

	private void prepare(final MetalDevice device, final int width, final int height, final MetalTexture.Format nextFormat) {
		if (this.depth == null || this.depth.descriptor().width() != width || this.depth.descriptor().height() != height) {
			if (this.depthView != null) this.depthView.close();
			if (this.depth != null) this.depth.close();
			this.depth = device.createTexture(new MetalTexture.Descriptor(MetalTexture.Format.DEPTH32_FLOAT, width, height, 1));
			this.depthView = this.depth.createView();
		}
		if (this.copy == null || this.format != nextFormat) {
			if (this.copy != null) this.copy.close();
			String source = """
				#include <metal_stdlib>
				using namespace metal;
				vertex float4 vs(uint id [[vertex_id]]) {
				    return float4(id == 1 ? 3.0 : -1.0, id == 2 ? 3.0 : -1.0, 0, 1);
				}
				fragment float4 fs(float4 position [[position]], texture2d<float> source [[texture(0)]]) {
				    return source.read(uint2(position.xy));
				}
				""";
			this.copy = device.createRenderPipeline(new MetalRenderPipeline.Descriptor(source, "vs", source, "fs",
				List.of(MetalRenderPipeline.ColorTarget.opaque(nextFormat)), null,
				MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
				MetalRenderPipeline.RasterState.DEFAULT));
			this.format = nextFormat;
		}
	}

	@Override
	public void close() {
		if (this.copy != null) this.copy.close();
		if (this.depthView != null) this.depthView.close();
		if (this.depth != null) this.depth.close();
	}
}
