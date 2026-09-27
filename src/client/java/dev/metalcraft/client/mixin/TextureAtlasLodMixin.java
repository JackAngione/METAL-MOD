package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodSystem;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Distant terrain colours are averaged from block textures; a new block atlas invalidates them. */
@Mixin(TextureAtlas.class)
abstract class TextureAtlasLodMixin {
    @Shadow @Final private Identifier location;

    @Inject(method = "upload", at = @At("RETURN"))
    private void metalcraft$blockAtlasChanged(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(this.location)) LodSystem.resourcesChanged();
    }
}
