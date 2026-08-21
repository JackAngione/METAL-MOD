package dev.metalcraft.client.mixin;

import dev.metalcraft.client.MetalCraftClient;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
abstract class ShaderManagerMixin {
	@Inject(
		method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
		at = @At("TAIL")
	)
	private void metalcraft$recompileExtensionPipelines(
		final ShaderManager.Configs preparations,
		final ResourceManager resourceManager,
		final ProfilerFiller profiler,
		final CallbackInfo callback
	) {
		MetalCraftClient.precompileRegisteredShaders();
	}
}
