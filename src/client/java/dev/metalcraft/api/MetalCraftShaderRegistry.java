package dev.metalcraft.api;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.GpuDevice;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class MetalCraftShaderRegistry {
	private final Map<Identifier, Entry> entries = new LinkedHashMap<>();

	public synchronized void register(final RenderPipeline pipeline) {
		this.register(pipeline, null);
	}

	public synchronized void register(final RenderPipeline pipeline, final @Nullable ShaderSource shaderSource) {
		Objects.requireNonNull(pipeline, "pipeline");
		Identifier id = pipeline.getLocation();
		if (this.entries.putIfAbsent(id, new Entry(pipeline, shaderSource)) != null) {
			throw new IllegalArgumentException("A MetalCraft shader pipeline is already registered as " + id);
		}
	}

	public synchronized Optional<RenderPipeline> find(final Identifier id) {
		Entry entry = this.entries.get(id);
		return entry == null ? Optional.empty() : Optional.of(entry.pipeline());
	}

	public synchronized Collection<RenderPipeline> pipelines() {
		return this.entries.values().stream().map(Entry::pipeline).toList();
	}

	public synchronized int precompileAll(final GpuDevice device) {
		int valid = 0;
		for (Entry entry : this.entries.values()) {
			CompiledRenderPipeline compiled = entry.shaderSource() == null
				? device.precompilePipeline(entry.pipeline())
				: device.precompilePipeline(entry.pipeline(), entry.shaderSource());
			if (compiled.isValid()) {
				valid++;
			}
		}
		return valid;
	}

	private record Entry(RenderPipeline pipeline, @Nullable ShaderSource shaderSource) {
	}
}
