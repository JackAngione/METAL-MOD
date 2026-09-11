package dev.metalcraft.client.mixin;

import dev.metalcraft.client.shader.water.SectionCompilerResultsWater;
import dev.metalcraft.client.shader.water.WaterVertexMetadata;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(SectionCompiler.Results.class)
abstract class SectionCompilerResultsWaterMixin implements SectionCompilerResultsWater {
	@Unique
	private Map<ChunkSectionLayer, WaterVertexMetadata> metalcraft$waterVertexMetadata = Map.of();

	@Override
	public Map<ChunkSectionLayer, WaterVertexMetadata> metalcraft$waterVertexMetadata() {
		return this.metalcraft$waterVertexMetadata;
	}

	@Override
	public void metalcraft$setWaterVertexMetadata(final Map<ChunkSectionLayer, WaterVertexMetadata> metadata) {
		this.metalcraft$waterVertexMetadata = Map.copyOf(metadata);
	}
}
