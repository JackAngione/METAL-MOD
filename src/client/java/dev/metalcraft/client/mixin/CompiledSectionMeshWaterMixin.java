package dev.metalcraft.client.mixin;

import dev.metalcraft.client.shader.water.SectionCompilerResultsWater;
import dev.metalcraft.client.shader.water.WaterMeshBinding;
import dev.metalcraft.client.shader.water.WaterMeshSource;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CompiledSectionMesh.class)
abstract class CompiledSectionMeshWaterMixin implements WaterMeshSource {
	@Unique private final Map<ChunkSectionLayer, WaterMeshBinding> metalcraft$water = new EnumMap<>(ChunkSectionLayer.class);

	@Inject(method = "<init>", at = @At("RETURN"))
	private void metalcraft$copyWater(final TranslucencyPointOfView pointOfView, final SectionCompiler.Results results, final CallbackInfo callback) {
		((SectionCompilerResultsWater)(Object)results).metalcraft$waterVertexMetadata()
			.forEach((layer, metadata) -> this.metalcraft$water.put(layer, new WaterMeshBinding(metadata)));
	}

	@Override public @Nullable WaterMeshBinding metalcraft$waterMesh(final ChunkSectionLayer layer) {
		return this.metalcraft$water.get(layer);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void metalcraft$closeWater(final CallbackInfo callback) {
		this.metalcraft$water.values().forEach(WaterMeshBinding::close);
		this.metalcraft$water.clear();
	}
}
