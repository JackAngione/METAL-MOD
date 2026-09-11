package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceDescriptor;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.metal.MetalLinearWorldPostActivation;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Promotes the five Fabulous scene layers to HDR while a verified fabulous token is active. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererFabulousPromotionMixin {
	@ModifyArg(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;createInternal(Ljava/lang/String;Lcom/mojang/blaze3d/resource/ResourceDescriptor;)Lcom/mojang/blaze3d/resource/ResourceHandle;"
		),
		index = 1
	)
	private ResourceDescriptor<?> metalcraft$promoteFabulousLayers(final ResourceDescriptor<?> descriptor) {
		return descriptor instanceof RenderTargetDescriptor target
			? MetalLinearWorldPostActivation.promoteFabulousLayers(MetalGpuDevices.current(), target)
			: descriptor;
	}
}
