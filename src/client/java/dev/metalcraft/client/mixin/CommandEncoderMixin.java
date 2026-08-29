package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.metalcraft.client.metal.MetalShaderEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Lets Blaze3D validate the engine-owned four-layer shadow attachment as one layered pass. */
@Mixin(CommandEncoder.class)
abstract class CommandEncoderMixin {
	@Redirect(
		method = "createRenderPass(Lcom/mojang/blaze3d/systems/RenderPassDescriptor;)Lcom/mojang/blaze3d/systems/RenderPass;",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/textures/GpuTexture;getDepthOrLayers()I")
	)
	private int metalcraft$allowLayeredShadowAttachment(final GpuTexture texture) {
		return MetalShaderEngine.isLayeredShadowAttachment(texture) ? 1 : texture.getDepthOrLayers();
	}
}
