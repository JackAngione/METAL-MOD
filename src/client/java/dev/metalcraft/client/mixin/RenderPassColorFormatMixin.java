package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import dev.metalcraft.client.metal.MetalLinearWorldPostActivation;
import java.util.List;
import java.util.Optional;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Matches pipeline color-0 format to the live pass attachment, including Fabulous HDR layers. */
@Mixin(RenderPass.class)
abstract class RenderPassColorFormatMixin {
	@Shadow
	@Final
	private List<RenderPassDescriptor.@Nullable Attachment<Optional<Vector4fc>>> colorAttachments;

	@ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
	private RenderPipeline metalcraft$matchColorFormat(final RenderPipeline pipeline) {
		if (pipeline == null || this.colorAttachments == null || this.colorAttachments.isEmpty()) {
			return pipeline;
		}
		RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment = this.colorAttachments.getFirst();
		if (attachment == null) return pipeline;
		return MetalLinearWorldPostActivation.withColorFormat(pipeline, attachment.textureView());
	}
}
