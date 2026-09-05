package dev.metalcraft.client.mixin;

import dev.metalcraft.client.shader.world.WaterIdentityDebug;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Identify fluids before their vertices join shared sorted terrain batches. */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerWaterMixin {
	@Redirect(method = "compile", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate("
			+ "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/client/renderer/block/FluidRenderer$Output;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
	private void metalcraft$identifyWater(final FluidRenderer renderer, final BlockAndTintGetter level,
		final BlockPos pos, final FluidRenderer.Output output, final BlockState block, final FluidState fluid) {
		renderer.tesselate(level, pos, WaterIdentityDebug.wrap(output, fluid), block, fluid);
	}
}
