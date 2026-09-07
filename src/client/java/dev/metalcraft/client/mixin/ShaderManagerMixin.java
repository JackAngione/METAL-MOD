package dev.metalcraft.client.mixin;

import dev.metalcraft.client.MetalCraftClient;
import dev.metalcraft.client.metal.MetalGpuDevice;
import dev.metalcraft.client.metal.MetalGpuDevices;
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
		MetalGpuDevice metal = MetalGpuDevices.current();
		if (metal != null) {
			// Public getShader always reads the compilation cache replaced by a successful apply.
			metal.setReloadShaderSource(((ShaderManager)(Object)this)::getShader);
		}
		MetalCraftClient.precompileRegisteredShaders();
		MetalCraftClient.reloadShaderPackRuntime();
	}

	@Inject(method = "close()V", at = @At("HEAD"))
	private void metalcraft$clearReloadShaderSource(final CallbackInfo callback) {
		MetalGpuDevice metal = MetalGpuDevices.current();
		if (metal == null) return;
		try {
			metal.setReloadShaderSource(null);
		} catch (IllegalStateException ignored) {
			// The Metal device may already be closed during shutdown.
		}
	}
}
