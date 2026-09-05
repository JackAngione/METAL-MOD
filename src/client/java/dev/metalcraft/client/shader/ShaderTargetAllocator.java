package dev.metalcraft.client.shader;

import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalGpuTextureView;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Allocates pack-owned render targets from a compiled graph. */
public final class ShaderTargetAllocator implements AutoCloseable {
	private final MetalDevice device;
	private final @Nullable MetalGpuDevice gpuDevice;
	private final Map<String, MetalTexture> textures = new LinkedHashMap<>();
	private final Map<String, MetalGpuTextureView> views = new LinkedHashMap<>();
	private final Map<String, MetalTextureView> sampleViews = new LinkedHashMap<>();
	private ShaderGraphCompiler.@Nullable CompiledGraph graph;
	private int width;
	private int height;

	ShaderTargetAllocator(final MetalDevice device) {
		this(device, null);
	}

	ShaderTargetAllocator(final MetalDevice device, final @Nullable MetalGpuDevice gpuDevice) {
		this.device = Objects.requireNonNull(device, "device");
		this.gpuDevice = gpuDevice;
	}

	void resize(final int width, final int height) {
		this.resize(this.graph, width, height);
	}

	void resize(final ShaderGraphCompiler.@Nullable CompiledGraph graph, final int width, final int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Shader target dimensions must be positive");
		}
		this.graph = graph;
		this.width = width;
		this.height = height;
		if (graph == null) {
			this.release();
			return;
		}
		Map<String, MetalTexture> nextTextures = new LinkedHashMap<>();
		Map<String, MetalGpuTextureView> nextViews = new LinkedHashMap<>();
		Map<String, MetalTextureView> nextSampleViews = new LinkedHashMap<>();
		for (Map.Entry<String, ShaderGraphCompiler.TargetInfo> entry : graph.targets().entrySet()) {
			String id = entry.getKey();
			if (ShaderGraphCompiler.RESERVED_TARGETS.contains(id)) {
				continue;
			}
			ShaderGraphCompiler.TargetInfo info = entry.getValue();
			MetalTexture.Descriptor wanted = descriptor(graph, id, info, width, height);
			MetalTexture existing = this.textures.get(id);
			if (existing != null && existing.descriptor().equals(wanted)) {
				nextTextures.put(id, existing);
				MetalGpuTextureView view = this.views.get(id);
				if (view != null) {
					nextViews.put(id, view);
				}
				MetalTextureView sample = this.sampleViews.get(id);
				if (sample != null) {
					nextSampleViews.put(id, sample);
				}
				continue;
			}
			MetalTexture created = this.device.createTexture(wanted);
			nextTextures.put(id, created);
			if (!created.isMemoryless()) {
				nextSampleViews.put(id, created.createView());
			}
			if (this.gpuDevice != null && (wanted.usage() & MetalTexture.USAGE_RENDER_TARGET) != 0) {
				nextViews.put(id, this.gpuDevice.wrapAttachment(created, "metalcraft/" + id));
			}
		}
		for (Map.Entry<String, MetalTexture> previous : this.textures.entrySet()) {
			if (!nextTextures.containsKey(previous.getKey()) || nextTextures.get(previous.getKey()) != previous.getValue()) {
				MetalGpuTextureView view = this.views.get(previous.getKey());
				if (view != null) {
					view.close();
				}
				MetalTextureView sample = this.sampleViews.get(previous.getKey());
				if (sample != null) {
					sample.close();
				}
				previous.getValue().close();
			}
		}
		this.textures.clear();
		this.textures.putAll(nextTextures);
		this.views.clear();
		this.views.putAll(nextViews);
		this.sampleViews.clear();
		this.sampleViews.putAll(nextSampleViews);
	}

	@Nullable MetalTexture target(final String id) {
		return this.textures.get(id);
	}

	@Nullable MetalGpuTextureView view(final String id) {
		return this.views.get(id);
	}

	@Nullable MetalTextureView sampleView(final String id) {
		return this.sampleViews.get(id);
	}

	void release() {
		this.views.values().forEach(MetalGpuTextureView::close);
		this.views.clear();
		this.sampleViews.values().forEach(MetalTextureView::close);
		this.sampleViews.clear();
		this.textures.values().forEach(MetalTexture::close);
		this.textures.clear();
	}

	@Override
	public void close() {
		this.release();
	}

	static int usage(final ShaderGraphCompiler.CompiledGraph graph, final String targetId) {
		int usage = 0;
		for (ShaderGraphCompiler.CompiledPass pass : graph.passes()) {
			if (pass.declaration().reads().contains(targetId)) {
				usage |= MetalTexture.USAGE_SHADER_READ;
			}
			if (pass.declaration().writes().contains(targetId)) {
				if (pass.declaration().kind() == ShaderPack.PassKind.COMPUTE) {
					usage |= MetalTexture.USAGE_SHADER_WRITE;
				} else {
					usage |= MetalTexture.USAGE_RENDER_TARGET;
				}
			}
		}
		if (usage == 0) {
			return MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_RENDER_TARGET;
		}
		usage |= MetalTexture.USAGE_SHADER_READ;
		return usage;
	}

	private static MetalTexture.Descriptor descriptor(
		final ShaderGraphCompiler.CompiledGraph graph,
		final String id,
		final ShaderGraphCompiler.TargetInfo info,
		final int width,
		final int height
	) {
		MetalTexture.Format format = MetalTexture.Format.valueOf(info.declaration().format().name());
		int scaledWidth = extent(info.declaration().extent(), width);
		int scaledHeight = extent(info.declaration().extent(), height);
		if (info.memoryless()) {
			return MetalTexture.Descriptor.memoryless(format, scaledWidth, scaledHeight);
		}
		int layers = Math.max(1, info.declaration().layers());
		int usage = usage(graph, id);
		if (layers > 1) {
			return MetalTexture.Descriptor.array(format, scaledWidth, scaledHeight, layers, usage);
		}
		return new MetalTexture.Descriptor(format, scaledWidth, scaledHeight, 1, usage);
	}

	private static int extent(final ShaderPack.Extent extent, final int full) {
		if (extent instanceof ShaderPack.FixedSize fixed) {
			return fixed.value();
		}
		if (extent instanceof ShaderPack.Scale scale) {
			return Math.max(1, (int)Math.round(full * scale.value()));
		}
		return full;
	}
}
