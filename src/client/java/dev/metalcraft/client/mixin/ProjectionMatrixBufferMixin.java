package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.metalcraft.client.metal.MetalWorldShadow;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures the world GPU projection Minecraft rasterizes, including view-bob and reversed-Z. */
@Mixin(ProjectionMatrixBuffer.class)
abstract class ProjectionMatrixBufferMixin {
	@Unique
	private String metalcraft$name;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void metalcraft$rememberName(final String name, final CallbackInfo callback) {
		this.metalcraft$name = name;
	}

	@Inject(method = "writeBuffer", at = @At("HEAD"))
	private void metalcraft$captureRasterProjection(
		final Matrix4f projectionMatrix,
		final CallbackInfoReturnable<GpuBufferSlice> callback
	) {
		if ("level".equals(this.metalcraft$name)) {
			MetalWorldShadow.captureRasterProjection(projectionMatrix);
		}
	}
}
