package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import dev.metalcraft.client.MetalCraftPlatform;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Prevents Minecraft's DEFAULT option from probing Vulkan when Metal replaces that candidate. */
@Mixin(Minecraft.class)
abstract class MinecraftBackendAvailabilityMixin {
	@Redirect(
		method = "<init>",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vulkan/VulkanBackend;checkBackendAvailable()Lcom/mojang/blaze3d/systems/BackendCreationException;"
		)
	)
	private @Nullable BackendCreationException metalcraft$skipUnusedVulkanProbe() {
		return MetalCraftPlatform.shouldUseDirectMetal() ? null : VulkanBackend.checkBackendAvailable();
	}
}
