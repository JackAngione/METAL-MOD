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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Encodes the identity/grade fullscreen pass into {@code post_color}. Does not present. */
final class MetalShaderFrameExecutor implements ShaderFrameExecutor, AutoCloseable {
	private static final long UNIFORM_BUFFER_BYTES = 256L;

	private final ShaderTargetAllocator allocator;
	private final Function<String, Object> optionValue;
	private final List<ShaderPack.Option> uniformOptions;
	private final MetalSampler filtered;
	private final MetalSampler unfiltered;
	private final MetalBuffer uniforms;
	private final List<FullscreenPass> passes;
	private @Nullable MetalTexture boundScene;
	private @Nullable MetalTextureView boundSceneView;
	private boolean closed;

	private record FullscreenPass(ShaderPack.Pass declaration, MetalRenderPipeline pipeline) implements AutoCloseable {
		@Override
		public void close() {
			this.pipeline.close();
		}
	}

	MetalShaderFrameExecutor(
		final MetalDevice device,
		final ShaderPack pack,
		final ShaderGraphCompiler.CompiledGraph graph,
		final ShaderTargetAllocator allocator,
		final Function<String, Object> optionValue
	) throws ShaderPackLoader.LoadException {
		this.allocator = Objects.requireNonNull(allocator, "allocator");
		this.optionValue = Objects.requireNonNull(optionValue, "optionValue");
		this.uniformOptions = pack.manifest().options().stream()
			.filter(option -> option.apply() == ShaderPack.ApplyMode.UNIFORM)
			.toList();
		MetalSampler filteredSampler = null;
		MetalSampler unfilteredSampler = null;
		MetalBuffer uniformBuffer = null;
		List<FullscreenPass> compiled = new ArrayList<>();
		try {
			filteredSampler = device.createSampler(new MetalSampler.Descriptor(
				MetalSampler.Filter.LINEAR, MetalSampler.Filter.LINEAR, MetalSampler.AddressMode.CLAMP_TO_EDGE
			));
			unfilteredSampler = device.createSampler(new MetalSampler.Descriptor(
				MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE
			));
			uniformBuffer = device.createBuffer(UNIFORM_BUFFER_BYTES, MetalBuffer.StorageMode.SHARED);
			Map<String, Object> values = pack.manifest().options().stream()
				.collect(java.util.stream.Collectors.toMap(ShaderPack.Option::id, option -> optionValue.apply(option.id())));
			for (ShaderGraphCompiler.CompiledPass pass : graph.passes()) {
				if (pass.declaration().kind() != ShaderPack.PassKind.FULLSCREEN) {
					continue;
				}
				compiled.add(new FullscreenPass(
					pass.declaration(),
					ShaderPassCompiler.compileFullscreen(
						device, pack, pass.declaration(), values, MetalTexture.Format.BGRA8_UNORM
					)
				));
			}
		} catch (RuntimeException | ShaderPackLoader.LoadException error) {
			compiled.forEach(FullscreenPass::close);
			if (uniformBuffer != null) {
				uniformBuffer.close();
			}
			if (filteredSampler != null) {
				filteredSampler.close();
			}
			if (unfilteredSampler != null) {
				unfilteredSampler.close();
			}
			throw error;
		}
		this.filtered = filteredSampler;
		this.unfiltered = unfilteredSampler;
		this.uniforms = uniformBuffer;
		this.passes = List.copyOf(compiled);
		this.writeUniforms();
	}

	@Override
	public boolean encode(final MetalCommandBuffer commands, final FrameBindings bindings) {
		this.requireOpen();
		MetalTexture post = this.allocator.target("post_color");
		if (!matchesPostColor(post, bindings.width(), bindings.height())) {
			return false;
		}
		this.encodePasses(commands, bindings.sceneView(), post);
		return true;
	}

	@Override
	public boolean encodeForTesting(final MetalCommandBuffer commands, final MetalTexture scene, final MetalTexture output) {
		this.requireOpen();
		MetalTexture post = this.allocator.target("post_color");
		if (!matchesPostColor(post, scene.descriptor().width(), scene.descriptor().height())
			|| output.descriptor().format() != MetalTexture.Format.BGRA8_UNORM
			|| output.descriptor().width() != scene.descriptor().width()
			|| output.descriptor().height() != scene.descriptor().height()) {
			return false;
		}
		this.encodePasses(commands, this.bindScene(scene), output);
		return true;
	}

	void writeUniforms() {
		this.requireOpen();
		try (MetalBuffer.Mapping mapping = this.uniforms.map()) {
			ByteBuffer bytes = mapping.bytes();
			bytes.clear();
			while (bytes.hasRemaining()) {
				bytes.put((byte)0);
			}
			bytes.rewind();
			for (ShaderPack.Option option : this.uniformOptions) {
				Object value = this.optionValue.apply(option.id());
				switch (option.type()) {
					case BOOL -> bytes.putInt(Boolean.TRUE.equals(value) ? 1 : 0);
					case INT -> bytes.putInt(((Number)value).intValue());
					case FLOAT -> bytes.putFloat(((Number)value).floatValue());
					case ENUM -> bytes.putInt(option.values().indexOf(value));
				}
			}
		}
	}

	private void encodePasses(
		final MetalCommandBuffer commands,
		final MetalTextureView sceneView,
		final MetalTexture colorTarget
	) {
		this.writeUniforms();
		for (FullscreenPass pass : this.passes) {
			this.encodeFullscreen(commands, pass, sceneView, colorTarget);
		}
	}

	private void encodeFullscreen(
		final MetalCommandBuffer commands,
		final FullscreenPass pass,
		final MetalTextureView sceneView,
		final MetalTexture colorTarget
	) {
		MetalRenderPass.Descriptor descriptor = new MetalRenderPass.Descriptor(
			List.of(new MetalRenderPass.ColorAttachment(
				colorTarget,
				MetalRenderPass.LoadAction.DONT_CARE,
				MetalRenderPass.StoreAction.STORE,
				0.0,
				0.0,
				0.0,
				1.0
			)),
			null,
			0
		);
		try (MetalRenderPass render = commands.beginRenderPass(
			descriptor,
			MetalPassCensus.kindFor("MetalCraft shader: " + pass.declaration().id())
		)) {
			render.setPipeline(pass.pipeline());
			int slot = 0;
			for (String read : pass.declaration().reads()) {
				MetalTextureView view = this.readView(read, sceneView);
				render.setTexture(slot, view, MetalRenderPass.STAGE_FRAGMENT);
				render.setSampler(
					slot,
					view.texture().descriptor().format().hasDepthAspect() ? this.unfiltered : this.filtered,
					MetalRenderPass.STAGE_FRAGMENT
				);
				slot++;
			}
			render.setUniformBuffer(0, this.uniforms, 0L, MetalRenderPass.STAGE_FRAGMENT);
			render.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
		}
	}

	private MetalTextureView readView(final String target, final MetalTextureView sceneView) {
		if ("scene".equals(target)) {
			return sceneView;
		}
		throw new IllegalStateException("Unsupported pack read '" + target + "'");
	}

	private MetalTextureView bindScene(final MetalTexture scene) {
		if (this.boundScene == scene && this.boundSceneView != null && !this.boundSceneView.isClosed()) {
			return this.boundSceneView;
		}
		if (this.boundSceneView != null) {
			this.boundSceneView.close();
		}
		this.boundScene = scene;
		this.boundSceneView = scene.createView();
		return this.boundSceneView;
	}

	private static boolean matchesPostColor(final @Nullable MetalTexture post, final int width, final int height) {
		return post != null
			&& post.descriptor().width() == width
			&& post.descriptor().height() == height;
	}

	private void requireOpen() {
		if (this.closed) {
			throw new IllegalStateException("Shader frame executor is closed");
		}
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		if (this.boundSceneView != null) {
			this.boundSceneView.close();
			this.boundSceneView = null;
		}
		this.boundScene = null;
		this.passes.forEach(FullscreenPass::close);
		this.uniforms.close();
		this.filtered.close();
		this.unfiltered.close();
	}
}
