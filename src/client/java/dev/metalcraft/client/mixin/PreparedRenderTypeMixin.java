package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.metalcraft.client.metal.MetalWorldGeometry;
import dev.metalcraft.client.metal.MetalWorldShadow;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes entities, block entities, and every other feature draw into the shader pack's G-buffer.
 *
 * <p>{@code PreparedRenderType.drawFromBuffer} is where all of it converges: one render pass, one
 * pipeline, one indexed draw, for every render type the level renderer submits. So this single pair
 * of redirects covers entities, block entities, items, armour, and anything a mod adds - and leaves
 * alone, on the substitution's own terms, the ones that do not belong in a G-buffer.
 *
 * <p>The pipeline is read from the record itself rather than from the redirected call, because the
 * pass has to be built before the pipeline is bound, and the two decisions have to agree.
 */
@Mixin(PreparedRenderType.class)
abstract class PreparedRenderTypeMixin {
	@Inject(
		method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void metalcraft$skipUnsupportedShadowFeature(
		final com.mojang.blaze3d.buffers.GpuBuffer vertexBuffer,
		final com.mojang.blaze3d.buffers.GpuBuffer indexBuffer,
		final com.mojang.blaze3d.IndexType indexType,
		final int baseVertex,
		final int firstIndex,
		final int indexCount,
		final CallbackInfo callback
	) {
		if (MetalWorldShadow.shouldSkip(((PreparedRenderType)(Object)this).pipeline())) {
			callback.cancel();
		}
	}

	@Redirect(
		method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/CommandEncoder;createRenderPass("
				+ "Ljava/util/function/Supplier;"
				+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
				+ "Ljava/util/Optional;"
				+ "Lcom/mojang/blaze3d/textures/GpuTextureView;"
				+ "Ljava/util/OptionalDouble;"
				+ ")Lcom/mojang/blaze3d/systems/RenderPass;"
		)
	)
	private RenderPass metalcraft$beginFeaturePass(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView color,
		final Optional<Vector4fc> clearColor,
		final GpuTextureView depth,
		final OptionalDouble clearDepth
	) {
		return MetalWorldGeometry.beginWorldPass(
			encoder, label, color, clearColor, depth, clearDepth,
			List.of(((PreparedRenderType)(Object)this).pipeline())
		);
	}

	@Redirect(
		method = "drawFromBuffer(Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/buffers/GpuBuffer;Lcom/mojang/blaze3d/IndexType;III)V",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
		)
	)
	private void metalcraft$setFeaturePipeline(final RenderPass pass, final RenderPipeline pipeline) {
		pass.setPipeline(MetalWorldGeometry.substitute(pass, pipeline));
	}
}
