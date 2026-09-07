package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.systems.RenderPass;
import dev.metalcraft.client.shader.water.WaterDrawSource;
import dev.metalcraft.client.shader.water.WaterMeshBinding;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderPass.Draw.class)
abstract class RenderPassWaterDrawMixin implements WaterDrawSource {
	@Unique private @Nullable WaterMeshBinding metalcraft$water;
	@Override public @Nullable WaterMeshBinding metalcraft$waterMesh() { return this.metalcraft$water; }
	@Override public void metalcraft$waterMesh(final @Nullable WaterMeshBinding binding) { this.metalcraft$water = binding; }
}
