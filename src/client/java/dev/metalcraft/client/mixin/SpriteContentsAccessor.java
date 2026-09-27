package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Reads a sprite's retained source pixels to average its colour for distant terrain. */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("originalImage") NativeImage metalcraft$originalImage();
}
