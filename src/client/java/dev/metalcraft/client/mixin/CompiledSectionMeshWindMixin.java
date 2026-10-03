package dev.metalcraft.client.mixin;

import dev.metalcraft.client.shader.wind.SectionCompilerResultsWind;
import dev.metalcraft.client.shader.wind.WindMeshBinding;
import dev.metalcraft.client.shader.wind.WindMeshSource;
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
abstract class CompiledSectionMeshWindMixin implements WindMeshSource {
	@Unique private final Map<ChunkSectionLayer, WindMeshBinding> metalcraft$wind = new EnumMap<>(ChunkSectionLayer.class);

	@Inject(method = "<init>", at = @At("RETURN"))
	private void metalcraft$copyWind(final TranslucencyPointOfView pointOfView, final SectionCompiler.Results results, final CallbackInfo callback) {
		((SectionCompilerResultsWind)(Object)results).metalcraft$windVertexMetadata()
			.forEach((layer, metadata) -> this.metalcraft$wind.put(layer, new WindMeshBinding(metadata)));
	}

	@Override public @Nullable WindMeshBinding metalcraft$windMesh(final ChunkSectionLayer layer) {
		return this.metalcraft$wind.get(layer);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void metalcraft$closeWind(final CallbackInfo callback) {
		this.metalcraft$wind.values().forEach(WindMeshBinding::close);
		this.metalcraft$wind.clear();
	}
}
