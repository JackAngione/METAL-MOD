package dev.metalcraft.client.shader;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalCommandBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalPassCensus;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalSampler;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import java.util.ArrayList;
import java.util.List;

/** Optional Standard-only GPU preparation; the ordinary pack graph remains unchanged. */
final class StandardPostEffects implements AutoCloseable {
	private final MetalDevice device;
	private final List<MetalRenderPipeline> pipelines = new ArrayList<>();
	private final MetalRenderPipeline extract, extractLinear, horizontal, vertical, autofocus,
		prepare, prepareLinear, blur;
	private Target bloomA, bloomB, dofA, dofB, focus;

	StandardPostEffects(final MetalDevice device, final ShaderPack pack) throws ShaderPackLoader.LoadException {
		this.device = device;
		ShaderPack.Pass sourcePass = new ShaderPack.Pass("standard_post_effects", ShaderPack.PassKind.FULLSCREEN,
			"post_effects.metal", List.of(), List.of(), List.of(), List.of(), null, null);
		String source = ShaderPackLoader.expandPassSource(pack, sourcePass);
		try {
			this.extract = this.pipeline(source, "bloom_extract_fragment", false, MetalTexture.Format.RGBA16_FLOAT);
			this.extractLinear = this.pipeline(source, "bloom_extract_fragment", true, MetalTexture.Format.RGBA16_FLOAT);
			this.horizontal = this.pipeline(source, "bloom_horizontal_fragment", true, MetalTexture.Format.RGBA16_FLOAT);
			this.vertical = this.pipeline(source, "bloom_vertical_fragment", true, MetalTexture.Format.RGBA16_FLOAT);
			this.autofocus = this.pipeline(source, "autofocus_fragment", true, MetalTexture.Format.R32_FLOAT);
			this.prepare = this.pipeline(source, "dof_prepare_fragment", false, MetalTexture.Format.RGBA16_FLOAT);
			this.prepareLinear = this.pipeline(source, "dof_prepare_fragment", true, MetalTexture.Format.RGBA16_FLOAT);
			this.blur = this.pipeline(source, "dof_blur_fragment", true, MetalTexture.Format.RGBA16_FLOAT);
		} catch (RuntimeException error) {
			this.pipelines.forEach(MetalRenderPipeline::close);
			throw error;
		}
	}

	private MetalRenderPipeline pipeline(final String source, final String fragment, final boolean linear,
		final MetalTexture.Format format) {
		String compiled = "#define MC_SCENE_LINEAR_HDR " + (linear ? 1 : 0) + "\n" + source;
		MetalRenderPipeline pipeline = this.device.createRenderPipeline(new MetalRenderPipeline.Descriptor(
			compiled, "post_vertex", compiled, fragment, List.of(MetalRenderPipeline.ColorTarget.opaque(format)),
			null, MetalRenderPipeline.VertexDescriptor.EMPTY, MetalRenderPipeline.DepthState.DISABLED,
			MetalRenderPipeline.RasterState.DEFAULT));
		this.pipelines.add(pipeline);
		return pipeline;
	}

	void encode(final MetalCommandBuffer commands, final FrameBindings bindings, final int bloom, final boolean dof,
		final MetalSampler filtered, final MetalBuffer options, final long optionsOffset,
		final MetalBuffer frame, final long frameOffset) {
		int width = Math.max(1, (bindings.width() + 3) / 4), height = Math.max(1, (bindings.height() + 3) / 4);
		boolean linear = bindings.colorEncoding() == FrameBindings.ColorEncoding.LINEAR_SRGB;
		if (bloom > 0) {
			this.bloomA = this.target(this.bloomA, width, height, MetalTexture.Format.RGBA16_FLOAT);
			this.bloomB = this.target(this.bloomB, width, height, MetalTexture.Format.RGBA16_FLOAT);
			this.draw(commands, this.bloomA, linear ? this.extractLinear : this.extract, "bloom extract", filtered,
				options, optionsOffset, frame, frameOffset, bindings.sceneView());
			this.draw(commands, this.bloomB, this.horizontal, "bloom horizontal", filtered,
				options, optionsOffset, frame, frameOffset, this.bloomA.view());
			this.draw(commands, this.bloomA, this.vertical, "bloom vertical", filtered,
				options, optionsOffset, frame, frameOffset, this.bloomB.view());
		} else {
			close(this.bloomA); close(this.bloomB); this.bloomA = this.bloomB = null;
		}
		if (dof) {
			this.dofA = this.target(this.dofA, width, height, MetalTexture.Format.RGBA16_FLOAT);
			this.dofB = this.target(this.dofB, width, height, MetalTexture.Format.RGBA16_FLOAT);
			this.focus = this.target(this.focus, 1, 1, MetalTexture.Format.R32_FLOAT);
			this.draw(commands, this.focus, this.autofocus, "DOF autofocus", filtered,
				options, optionsOffset, frame, frameOffset, bindings.worldDepthView());
			this.draw(commands, this.dofA, linear ? this.prepareLinear : this.prepare, "DOF prepare", filtered,
				options, optionsOffset, frame, frameOffset, bindings.sceneView(), bindings.worldDepthView(), this.focus.view());
			this.draw(commands, this.dofB, this.blur, "DOF blur", filtered,
				options, optionsOffset, frame, frameOffset, this.dofA.view());
		} else {
			close(this.dofA); close(this.dofB); close(this.focus); this.dofA = this.dofB = this.focus = null;
		}
	}

	MetalTextureView bloomView(final FrameBindings bindings) {
		return this.bloomA == null ? bindings.sceneView() : this.bloomA.view();
	}
	MetalTextureView dofView(final FrameBindings bindings) {
		return this.dofB == null ? bindings.sceneView() : this.dofB.view();
	}
	MetalTextureView focusView() { return this.focus.view(); }

	private Target target(final Target previous, final int width, final int height, final MetalTexture.Format format) {
		if (previous != null && previous.texture().descriptor().width() == width
			&& previous.texture().descriptor().height() == height) return previous;
		close(previous);
		MetalTexture texture = this.device.createTexture(new MetalTexture.Descriptor(format, width, height, 1));
		try { return new Target(texture, texture.createView()); }
		catch (RuntimeException error) { texture.close(); throw error; }
	}

	private void draw(final MetalCommandBuffer commands, final Target destination, final MetalRenderPipeline pipeline,
		final String label, final MetalSampler sampler, final MetalBuffer options, final long optionsOffset,
		final MetalBuffer frame, final long frameOffset, final MetalTextureView... textures) {
		try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
			new MetalRenderPass.ColorAttachment(destination.texture(), MetalRenderPass.LoadAction.DONT_CARE,
				MetalRenderPass.StoreAction.STORE, 0, 0, 0, 0)), MetalPassCensus.kindFor("Metal Mod " + label))) {
			pass.setPipeline(pipeline);
			for (int slot = 0; slot < textures.length; slot++)
				pass.setTexture(slot, textures[slot], MetalRenderPass.STAGE_FRAGMENT);
			pass.setSampler(0, sampler, MetalRenderPass.STAGE_FRAGMENT);
			pass.setUniformBuffer(0, options, optionsOffset, MetalRenderPass.STAGE_FRAGMENT);
			pass.setUniformBuffer(1, frame, frameOffset, MetalRenderPass.STAGE_FRAGMENT);
			pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
		}
	}

	private static void close(final Target target) { if (target != null) target.close(); }
	private record Target(MetalTexture texture, MetalTextureView view) implements AutoCloseable {
		@Override public void close() { this.view.close(); this.texture.close(); }
	}
	@Override public void close() {
		close(this.bloomA); close(this.bloomB); close(this.dofA); close(this.dofB); close(this.focus);
		this.bloomA = this.bloomB = this.dofA = this.dofB = this.focus = null;
		this.pipelines.forEach(MetalRenderPipeline::close);
		this.pipelines.clear();
	}
}
