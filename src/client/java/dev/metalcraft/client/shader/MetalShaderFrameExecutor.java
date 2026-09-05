package dev.metalcraft.client.shader;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalCommandBuffer;
import dev.metalcraft.client.metal.MetalComputePass;
import dev.metalcraft.client.metal.MetalComputePipeline;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalFence;
import dev.metalcraft.client.metal.MetalPassCensus;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalRenderPipeline;
import dev.metalcraft.client.metal.MetalSampler;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Encodes fullscreen and compute pack passes. Does not present. */
final class MetalShaderFrameExecutor implements ShaderFrameExecutor, AutoCloseable {
	private final ShaderTargetAllocator allocator;
	private final Function<String, Object> optionValue;
	private final List<ShaderPack.Option> uniformOptions;
	private final MetalSampler filtered;
	private final MetalSampler unfiltered;
	private final UniformRing uniforms;
	private final List<ExecutablePass> passes;
	private final boolean readsDepth;
	private @Nullable MetalTexture boundScene;
	private @Nullable MetalTextureView boundSceneView;
	private @Nullable MetalTexture boundOutput;
	private @Nullable MetalTextureView boundOutputView;
	private boolean closed;

	private record ExecutablePass(
		ShaderGraphCompiler.CompiledPass compiled,
		@Nullable MetalRenderPipeline render,
		@Nullable MetalComputePipeline compute
	) implements AutoCloseable {
		ShaderPack.Pass declaration() {
			return this.compiled.declaration();
		}

		@Override
		public void close() {
			if (this.render != null) {
				this.render.close();
			}
			if (this.compute != null) {
				this.compute.close();
			}
		}
	}

	MetalShaderFrameExecutor(
		final MetalDevice device,
		final ShaderPack pack,
		final ShaderGraphCompiler.CompiledGraph graph,
		final ShaderTargetAllocator allocator,
		final Function<String, Object> optionValue
	) throws ShaderPackLoader.LoadException {
		Objects.requireNonNull(graph, "graph");
		this.allocator = Objects.requireNonNull(allocator, "allocator");
		this.optionValue = Objects.requireNonNull(optionValue, "optionValue");
		this.uniformOptions = pack.manifest().options().stream()
			.filter(option -> option.apply() == ShaderPack.ApplyMode.UNIFORM)
			.toList();
		MetalSampler filteredSampler = null;
		MetalSampler unfilteredSampler = null;
		UniformRing uniformRing = null;
		List<ExecutablePass> compiled = new ArrayList<>();
		boolean needsDepth = false;
		try {
			filteredSampler = device.createSampler(new MetalSampler.Descriptor(
				MetalSampler.Filter.LINEAR, MetalSampler.Filter.LINEAR, MetalSampler.AddressMode.CLAMP_TO_EDGE
			));
			unfilteredSampler = device.createSampler(new MetalSampler.Descriptor(
				MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE
			));
			uniformRing = new UniformRing(device, Math.max(4, this.uniformOptions.size() * 4));
			Map<String, Object> values = pack.manifest().options().stream()
				.collect(java.util.stream.Collectors.toMap(ShaderPack.Option::id, option -> optionValue.apply(option.id())));
			Map<String, ShaderGraphCompiler.CompiledPass> byId = new java.util.LinkedHashMap<>();
			for (ShaderGraphCompiler.CompiledPass pass : graph.passes()) {
				byId.put(pass.declaration().id(), pass);
			}
			for (ShaderGraphCompiler.PassGroup group : graph.groups()) {
				if (worldOwned(group, byId)) {
					continue;
				}
				for (String passId : group.passes()) {
					ShaderGraphCompiler.CompiledPass pass = byId.get(passId);
					validateExecutable(pack, pass);
					if (pass.declaration().reads().contains("depth")) {
						needsDepth = true;
					}
					compiled.add(compilePass(device, pack, pass, values));
				}
			}
		} catch (RuntimeException | ShaderPackLoader.LoadException error) {
			compiled.forEach(ExecutablePass::close);
			if (uniformRing != null) {
				uniformRing.close();
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
		this.uniforms = uniformRing;
		this.passes = List.copyOf(compiled);
		this.readsDepth = needsDepth;
	}

	@Override
	public boolean encode(final MetalCommandBuffer commands, final FrameBindings bindings) {
		this.requireOpen();
		return this.encodeGraph(commands, bindings, null);
	}

	@Override
	public boolean encodeForTesting(final MetalCommandBuffer commands, final MetalTexture scene, final MetalTexture output) {
		this.requireOpen();
		FrameBindings bindings = new FrameBindings(
			scene,
			this.bindScene(scene),
			scene.descriptor().width(),
			scene.descriptor().height()
		);
		return this.encodeGraph(commands, bindings, output);
	}

	private boolean encodeGraph(
		final MetalCommandBuffer commands,
		final FrameBindings bindings,
		final @Nullable MetalTexture testingPost
	) {
		MetalTexture post = this.allocator.target("post_color");
		if (!matchesPostColor(post, bindings.width(), bindings.height())) {
			return false;
		}
		if (testingPost != null
			&& (testingPost.descriptor().format() != MetalTexture.Format.BGRA8_UNORM
				|| testingPost.descriptor().width() != bindings.width()
				|| testingPost.descriptor().height() != bindings.height())) {
			return false;
		}
		if (this.readsDepth && (bindings.worldDepth() == null || bindings.worldDepthView() == null)) {
			return false;
		}
		UniformBinding uniform = this.uniforms.write(this::packOptions);
		for (ExecutablePass pass : this.passes) {
			if (pass.compute() != null) {
				this.encodeCompute(commands, pass, bindings, testingPost, uniform);
			} else {
				this.encodeFullscreen(commands, pass, bindings, testingPost, uniform);
			}
		}
		this.uniforms.signal(commands, uniform);
		return true;
	}

	private void packOptions(final ByteBuffer bytes) {
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

	private void encodeFullscreen(
		final MetalCommandBuffer commands,
		final ExecutablePass pass,
		final FrameBindings bindings,
		final @Nullable MetalTexture testingPost,
		final UniformBinding uniform
	) {
		List<MetalRenderPass.ColorAttachment> colors = new ArrayList<>();
		for (ShaderGraphCompiler.WriteDecision write : pass.compiled().writes()) {
			MetalTexture target = this.textureFor(write.target(), bindings, testingPost);
			colors.add(new MetalRenderPass.ColorAttachment(
				target,
				loadAction(write.loadAction()),
				storeAction(target),
				0.0,
				0.0,
				0.0,
				1.0
			));
		}
		try (MetalRenderPass render = commands.beginRenderPass(
			new MetalRenderPass.Descriptor(colors, null, 0),
			MetalPassCensus.kindFor("MetalCraft shader: " + pass.declaration().id())
		)) {
			render.setPipeline(pass.render());
			int slot = 0;
			for (String read : pass.declaration().reads()) {
				MetalTextureView view = this.viewFor(read, bindings, testingPost);
				render.setTexture(slot, view, MetalRenderPass.STAGE_FRAGMENT);
				render.setSampler(slot, this.samplerFor(view), MetalRenderPass.STAGE_FRAGMENT);
				slot++;
			}
			render.setUniformBuffer(0, uniform.buffer(), uniform.offset(), MetalRenderPass.STAGE_FRAGMENT);
			render.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3, 1, 0);
		}
	}

	private void encodeCompute(
		final MetalCommandBuffer commands,
		final ExecutablePass pass,
		final FrameBindings bindings,
		final @Nullable MetalTexture testingPost,
		final UniformBinding uniform
	) {
		MetalComputePipeline pipeline = pass.compute();
		int[] threads = threadgroup2d(pipeline);
		MetalTexture coverage = this.textureFor(pass.declaration().writes().getFirst(), bindings, testingPost);
		try (MetalComputePass compute = commands.beginComputePass(
			MetalPassCensus.kindFor("MetalCraft shader: " + pass.declaration().id())
		)) {
			compute.setPipeline(pipeline);
			int slot = 0;
			for (String read : pass.declaration().reads()) {
				MetalTextureView view = this.viewFor(read, bindings, testingPost);
				compute.setTexture(slot, view);
				compute.setSampler(slot, this.samplerFor(view));
				slot++;
			}
			for (String write : pass.declaration().writes()) {
				compute.setTexture(slot++, this.viewFor(write, bindings, testingPost));
			}
			compute.setBuffer(0, uniform.buffer(), uniform.offset());
			compute.dispatchCovering(
				coverage.descriptor().width(),
				coverage.descriptor().height(),
				threads[0],
				threads[1]
			);
		}
	}

	private MetalTexture textureFor(
		final String target,
		final FrameBindings bindings,
		final @Nullable MetalTexture testingPost
	) {
		if ("scene".equals(target)) {
			return bindings.scene();
		}
		if ("depth".equals(target)) {
			return Objects.requireNonNull(bindings.worldDepth(), "worldDepth");
		}
		if ("post_color".equals(target) && testingPost != null) {
			return testingPost;
		}
		MetalTexture allocated = this.allocator.target(target);
		if (allocated == null) {
			throw new IllegalStateException("Pack target '" + target + "' is not allocated");
		}
		return allocated;
	}

	private MetalTextureView viewFor(
		final String target,
		final FrameBindings bindings,
		final @Nullable MetalTexture testingPost
	) {
		if ("scene".equals(target)) {
			return bindings.sceneView();
		}
		if ("depth".equals(target)) {
			return Objects.requireNonNull(bindings.worldDepthView(), "worldDepthView");
		}
		if ("post_color".equals(target) && testingPost != null) {
			return this.bindOutput(testingPost);
		}
		MetalTextureView view = this.allocator.sampleView(target);
		if (view == null) {
			throw new IllegalStateException("Pack target '" + target + "' has no sample view");
		}
		return view;
	}

	private MetalSampler samplerFor(final MetalTextureView view) {
		return view.texture().descriptor().format().hasDepthAspect() ? this.unfiltered : this.filtered;
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

	private MetalTextureView bindOutput(final MetalTexture output) {
		if (this.boundOutput == output && this.boundOutputView != null && !this.boundOutputView.isClosed()) {
			return this.boundOutputView;
		}
		if (this.boundOutputView != null) {
			this.boundOutputView.close();
		}
		this.boundOutput = output;
		this.boundOutputView = output.createView();
		return this.boundOutputView;
	}

	private static boolean worldOwned(
		final ShaderGraphCompiler.PassGroup group,
		final Map<String, ShaderGraphCompiler.CompiledPass> byId
	) {
		for (String passId : group.passes()) {
			ShaderPack.PassKind kind = byId.get(passId).declaration().kind();
			if (kind == ShaderPack.PassKind.GEOMETRY || kind == ShaderPack.PassKind.SHADOW) {
				return true;
			}
		}
		return false;
	}

	private static void validateExecutable(final ShaderPack pack, final ShaderGraphCompiler.CompiledPass compiled)
		throws ShaderPackLoader.LoadException {
		ShaderPack.Pass pass = compiled.declaration();
		if (pass.kind() != ShaderPack.PassKind.FULLSCREEN && pass.kind() != ShaderPack.PassKind.COMPUTE) {
			throw new ShaderPackLoader.LoadException(
				"Unsupported executable pass '" + pass.id() + "' of kind " + pass.kind()
			);
		}
		if (!pass.tileReads().isEmpty() || pass.mergeWith() != null) {
			throw new ShaderPackLoader.LoadException(
				"Pass '" + pass.id() + "' cannot merge or tile-read outside a world geometry group"
			);
		}
		Set<String> aliased = new HashSet<>(pass.reads());
		aliased.retainAll(pass.writes());
		if (!aliased.isEmpty()) {
			throw new ShaderPackLoader.LoadException(
				"Pass '" + pass.id() + "' samples and writes " + aliased + "; use explicit ping-pong targets"
			);
		}
		if (!pass.buffers().isEmpty()) {
			throw new ShaderPackLoader.LoadException(
				"Pass '" + pass.id() + "' declares host buffers " + pass.buffers()
					+ " that the frame executor cannot bind"
			);
		}
		for (String read : pass.reads()) {
			if (ShaderPack.EXTERNAL_TEXTURES.contains(read)) {
				throw new ShaderPackLoader.LoadException(
					"Pass '" + pass.id() + "' reads host texture '" + read + "' that the frame executor cannot bind"
				);
			}
			if ("drawable".equals(read)) {
				throw new ShaderPackLoader.LoadException("Pass '" + pass.id() + "' cannot sample drawable");
			}
			if (!"scene".equals(read) && !"depth".equals(read) && !pack.manifest().targets().containsKey(read)) {
				throw new ShaderPackLoader.LoadException(
					"Pass '" + pass.id() + "' reads unknown target '" + read + "'"
				);
			}
		}
		for (String write : pass.writes()) {
			if (ShaderGraphCompiler.RESERVED_TARGETS.contains(write) || ShaderPack.EXTERNAL_TEXTURES.contains(write)) {
				throw new ShaderPackLoader.LoadException(
					"Pass '" + pass.id() + "' cannot write host target '" + write + "'"
				);
			}
			if (!pack.manifest().targets().containsKey(write)) {
				throw new ShaderPackLoader.LoadException(
					"Pass '" + pass.id() + "' writes unknown target '" + write + "'"
				);
			}
			ShaderPack.Target declaration = pack.manifest().targets().get(write);
			if (pass.kind() != ShaderPack.PassKind.COMPUTE && !declaration.format().isColor()) {
				throw new ShaderPackLoader.LoadException(
					"Fullscreen pass '" + pass.id() + "' cannot write depth target '" + write + "'"
				);
			}
		}
	}

	private static ExecutablePass compilePass(
		final MetalDevice device,
		final ShaderPack pack,
		final ShaderGraphCompiler.CompiledPass pass,
		final Map<String, Object> values
	) throws ShaderPackLoader.LoadException {
		if (pass.declaration().kind() == ShaderPack.PassKind.COMPUTE) {
			return new ExecutablePass(pass, null, ShaderPassCompiler.compileCompute(device, pack, pass.declaration(), values));
		}
		List<MetalRenderPipeline.ColorTarget> colors = new ArrayList<>();
		for (String write : pass.declaration().writes()) {
			ShaderPack.Target target = pack.manifest().targets().get(write);
			colors.add(MetalRenderPipeline.ColorTarget.opaque(MetalTexture.Format.valueOf(target.format().name())));
		}
		return new ExecutablePass(
			pass,
			ShaderPassCompiler.compileFullscreen(device, pack, pass.declaration(), values, colors),
			null
		);
	}

	static int[] threadgroup2d(final MetalComputePipeline pipeline) {
		int simd = Math.max(1, pipeline.threadExecutionWidth());
		int max = Math.max(simd, pipeline.maxThreadsPerThreadgroup());
		int threadsX = Math.min(simd, max);
		int threadsY = Math.max(1, max / threadsX);
		if (threadsY > 8) {
			threadsY = 8;
		}
		while (threadsY > 1 && (long)threadsX * threadsY > max) {
			threadsY--;
		}
		if ((long)threadsX * threadsY > max) {
			threadsX = Math.max(1, Math.min(simd, max));
			threadsY = 1;
		}
		return new int[] {threadsX, threadsY};
	}

	private static MetalRenderPass.LoadAction loadAction(final ShaderGraphCompiler.LoadAction action) {
		return action == ShaderGraphCompiler.LoadAction.LOAD
			? MetalRenderPass.LoadAction.LOAD
			: MetalRenderPass.LoadAction.DONT_CARE;
	}

	private static MetalRenderPass.StoreAction storeAction(final MetalTexture target) {
		return target.isMemoryless()
			? MetalRenderPass.StoreAction.DONT_CARE
			: MetalRenderPass.StoreAction.STORE;
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
		if (this.boundOutputView != null) {
			this.boundOutputView.close();
			this.boundOutputView = null;
		}
		this.boundOutput = null;
		this.passes.forEach(ExecutablePass::close);
		this.uniforms.close();
		this.filtered.close();
		this.unfiltered.close();
	}

	private record UniformBinding(MetalBuffer buffer, long offset, int slot, boolean overflow) {
	}

	/**
	 * Completion-retired uniform slots. A shared buffer rewritten every encode is not safe while a
	 * previous GPU frame still reads it; a slot is reused only after its fence signals.
	 */
	private static final class UniformRing implements AutoCloseable {
		private static final int SLOTS = 3;
		private static final long ALIGNMENT = 256L;

		private final MetalDevice device;
		private final MetalBuffer buffer;
		private final MetalFence[] fences;
		private final long slotBytes;
		private final long[] inflight;
		private final List<Overflow> overflow = new ArrayList<>();
		private int cursor;

		private record Overflow(MetalBuffer buffer, MetalFence fence, long fenceValue) {
		}

		UniformRing(final MetalDevice device, final long payloadBytes) {
			this.device = device;
			this.slotBytes = Math.max(ALIGNMENT, align(payloadBytes, ALIGNMENT));
			this.buffer = device.createBuffer(Math.multiplyExact(this.slotBytes, SLOTS), MetalBuffer.StorageMode.SHARED);
			this.fences = new MetalFence[SLOTS];
			for (int index = 0; index < SLOTS; index++) {
				this.fences[index] = device.createFence();
			}
			this.inflight = new long[SLOTS];
		}

		UniformBinding write(final java.util.function.Consumer<ByteBuffer> packer) {
			this.retireOverflow();
			int slot = this.acquireSlot();
			if (slot >= 0) {
				long offset = slot * this.slotBytes;
				try (MetalBuffer.Mapping mapping = this.buffer.map(offset, this.slotBytes)) {
					packer.accept(mapping.bytes());
				}
				return new UniformBinding(this.buffer, offset, slot, false);
			}
			MetalBuffer extra = this.device.createBuffer(this.slotBytes, MetalBuffer.StorageMode.SHARED);
			try (MetalBuffer.Mapping mapping = extra.map()) {
				packer.accept(mapping.bytes());
			}
			return new UniformBinding(extra, 0L, -1, true);
		}

		void signal(final MetalCommandBuffer commands, final UniformBinding binding) {
			if (binding.overflow()) {
				MetalFence fence = this.device.createFence();
				long value = fence.reserveValue();
				commands.signal(fence, value);
				this.overflow.add(new Overflow(binding.buffer(), fence, value));
				return;
			}
			long value = this.fences[binding.slot()].reserveValue();
			commands.signal(this.fences[binding.slot()], value);
			this.inflight[binding.slot()] = value;
		}

		private int acquireSlot() {
			for (int index = 0; index < SLOTS; index++) {
				int slot = (this.cursor + index) % SLOTS;
				if (this.inflight[slot] == 0L || this.fences[slot].isSignaled(this.inflight[slot])) {
					this.inflight[slot] = 0L;
					this.cursor = (slot + 1) % SLOTS;
					return slot;
				}
			}
			return -1;
		}

		private void retireOverflow() {
			this.overflow.removeIf(entry -> {
				if (!entry.fence().isSignaled(entry.fenceValue())) {
					return false;
				}
				entry.fence().close();
				entry.buffer().close();
				return true;
			});
		}

		private static long align(final long value, final long alignment) {
			long remainder = value % alignment;
			return remainder == 0L ? value : value + alignment - remainder;
		}

		@Override
		public void close() {
			for (Overflow entry : this.overflow) {
				entry.fence().close();
				entry.buffer().close();
			}
			this.overflow.clear();
			for (MetalFence fence : this.fences) {
				fence.close();
			}
			this.buffer.close();
		}
	}
}
