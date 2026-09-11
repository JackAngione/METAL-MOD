package dev.metalcraft.client.mixin;

import dev.metalcraft.client.lod.LodAtlas;
import dev.metalcraft.client.lod.LodBakedMesh;
import dev.metalcraft.client.lod.LodCompilerCapture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
abstract class TextureAtlasLodMixin {
    @Shadow @Final private Identifier location;

    @Inject(method = "upload", at = @At("RETURN"))
    private void metalcraft$publishLodAtlas(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        if (!LodCompilerCapture.ENABLED || !TextureAtlas.LOCATION_BLOCKS.equals(location)) return;
        try {
            if (preparations.regions().size() > 65536) { LodAtlas.clear(); return; }
            var sprites = preparations.regions().entrySet().stream().map(entry -> {
                var sprite = entry.getValue();
                return new LodBakedMesh.Sprite(entry.getKey().toString(), sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
            }).toList();
            LodAtlas.publish(new LodAtlas(sprites));
        }
        catch (IllegalArgumentException unsupported) { LodAtlas.clear(); }
    }

    @Inject(method = "clearTextureData", at = @At("HEAD"))
    private void metalcraft$clearLodAtlas(CallbackInfo ci) {
        if (LodCompilerCapture.ENABLED && TextureAtlas.LOCATION_BLOCKS.equals(location)) LodAtlas.clear();
    }
}
