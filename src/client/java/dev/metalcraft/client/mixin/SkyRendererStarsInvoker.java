package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Retains the vanilla star mesh without also drawing the vanilla sun/moon quads. */
@Mixin(SkyRenderer.class)
public interface SkyRendererStarsInvoker {
    @Invoker("renderStars")
    void metalcraft$renderStars(float brightness, PoseStack pose);
}
