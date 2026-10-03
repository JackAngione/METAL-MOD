package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.systems.RenderPass;
import dev.metalcraft.client.shader.wind.WindDrawSource;
import dev.metalcraft.client.shader.wind.WindMeshBinding;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderPass.Draw.class)
abstract class RenderPassWindDrawMixin implements WindDrawSource {
	@Unique private @Nullable WindMeshBinding metalcraft$wind;
	@Override public @Nullable WindMeshBinding metalcraft$windMesh() { return this.metalcraft$wind; }
	@Override public void metalcraft$windMesh(final @Nullable WindMeshBinding binding) { this.metalcraft$wind = binding; }
}
