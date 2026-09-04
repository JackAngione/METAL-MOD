package dev.metalcraft.client.shader;

import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalGpuTextureView;
import dev.metalcraft.client.metal.MetalTexture;
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
		for (Map.Entry<String, ShaderGraphCompiler.TargetInfo> entry : graph.targets().entrySet()) {
			String id = entry.getKey();
			if (ShaderGraphCompiler.RESERVED_TARGETS.contains(id)) {
				continue;
			}
			ShaderGraphCompiler.TargetInfo info = entry.getValue();
			MetalTexture existing = this.textures.get(id);
			if (existing != null
				&& existing.descriptor().width() == width
				&& existing.descriptor().height() == height
				&& existing.isMemoryless() == info.memoryless()) {
				nextTextures.put(id, existing);
				MetalGpuTextureView view = this.views.get(id);
				if (view != null) {
					nextViews.put(id, view);
				}
				continue;
			}
			MetalTexture created = this.device.createTexture(descriptor(info, width, height));
			nextTextures.put(id, created);
			if (this.gpuDevice != null) {
				nextViews.put(id, this.gpuDevice.wrapAttachment(created, "metalcraft/" + id));
			}
		}
		for (Map.Entry<String, MetalTexture> previous : this.textures.entrySet()) {
			if (!nextTextures.containsKey(previous.getKey()) || nextTextures.get(previous.getKey()) != previous.getValue()) {
				MetalGpuTextureView view = this.views.get(previous.getKey());
				if (view != null) {
					view.close();
				}
				previous.getValue().close();
			}
		}
		this.textures.clear();
		this.textures.putAll(nextTextures);
		this.views.clear();
		this.views.putAll(nextViews);
	}

	@Nullable MetalTexture target(final String id) {
		return this.textures.get(id);
	}

	@Nullable MetalGpuTextureView view(final String id) {
		return this.views.get(id);
	}

	void release() {
		this.views.values().forEach(MetalGpuTextureView::close);
		this.views.clear();
		this.textures.values().forEach(MetalTexture::close);
		this.textures.clear();
	}

	@Override
	public void close() {
		this.release();
	}

	private static MetalTexture.Descriptor descriptor(
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
		return new MetalTexture.Descriptor(
			format, scaledWidth, scaledHeight, 1, MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_RENDER_TARGET
		);
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
