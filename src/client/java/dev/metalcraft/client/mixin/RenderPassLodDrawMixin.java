package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.systems.RenderPass;
import dev.metalcraft.client.lod.LodDrawSource;
import dev.metalcraft.client.lod.LodTextureBinding;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderPass.Draw.class)
abstract class RenderPassLodDrawMixin implements LodDrawSource {
    @Unique private @Nullable LodTextureBinding metalcraft$lodTexture;
    @Unique private double metalcraft$sortDistance;

    @Override public @Nullable LodTextureBinding metalcraft$lodTexture() { return metalcraft$lodTexture; }
    @Override public void metalcraft$lodTexture(@Nullable LodTextureBinding binding) { metalcraft$lodTexture = binding; }
    @Override public double metalcraft$sortDistance() { return metalcraft$sortDistance; }
    @Override public void metalcraft$sortDistance(double distance) { metalcraft$sortDistance = distance; }
}
