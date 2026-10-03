package dev.metalcraft.client.mixin;

import dev.metalcraft.client.shader.wind.SectionCompilerResultsWind;
import dev.metalcraft.client.shader.wind.WindVertexMetadata;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionCompiler.Results.class)
abstract class SectionCompilerResultsWindMixin implements SectionCompilerResultsWind {
	@Unique
	private Map<ChunkSectionLayer, WindVertexMetadata> metalcraft$windVertexMetadata = Map.of();

	@Override
	public Map<ChunkSectionLayer, WindVertexMetadata> metalcraft$windVertexMetadata() {
		return this.metalcraft$windVertexMetadata;
	}

	@Override
	public void metalcraft$setWindVertexMetadata(final Map<ChunkSectionLayer, WindVertexMetadata> metadata) {
		this.metalcraft$windVertexMetadata = Map.copyOf(metadata);
	}
}
