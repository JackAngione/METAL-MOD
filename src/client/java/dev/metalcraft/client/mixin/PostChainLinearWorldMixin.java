package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceDescriptor;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.metal.MetalLinearWorldPostActivation;
import java.util.Set;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Verifies the live transparency graph, promotes only its nonpersistent final target, and
 * registers Fabulous composition/copy contracts. Outline and spectator chains stay encoded.
 */
@Mixin(PostChain.class)
abstract class PostChainLinearWorldMixin {
	@Unique
	private boolean metalcraft$verifiedTransparency;

	@Inject(method = "load", at = @At("HEAD"))
	private static void metalcraft$beginLoad(
		final PostChainConfig config,
		final TextureManager textureManager,
		final Set<Identifier> allowedExternalTargets,
		final Identifier id,
		final Projection projection,
		final ProjectionMatrixBuffer projectionMatrixBuffer,
		final CallbackInfoReturnable<PostChain> callback
	) {
		MetalLinearWorldPostActivation.beginChainLoad(id, config);
	}

	@Inject(method = "load", at = @At("RETURN"))
	private static void metalcraft$finishLoad(
		final PostChainConfig config,
		final TextureManager textureManager,
		final Set<Identifier> allowedExternalTargets,
		final Identifier id,
		final Projection projection,
		final ProjectionMatrixBuffer projectionMatrixBuffer,
		final CallbackInfoReturnable<PostChain> callback
	) {
		boolean verified = MetalLinearWorldPostActivation.finishChainLoad();
		PostChain chain = callback.getReturnValue();
		if (chain != null) {
			((PostChainLinearWorldMixin)(Object)chain).metalcraft$verifiedTransparency = verified;
		}
	}

	@Redirect(
		method = "createPass",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/GpuDevice;precompilePipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)Lcom/mojang/blaze3d/pipeline/CompiledRenderPipeline;"
		)
	)
	private static CompiledRenderPipeline metalcraft$registerAndPrecompile(
		final GpuDevice device,
		final RenderPipeline pipeline
	) {
		MetalLinearWorldPostActivation.registerCreatedPass(MetalGpuDevices.current(), pipeline);
		return device.precompilePipeline(pipeline);
	}

	@ModifyArg(
		method = "addToFrame",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;createInternal(Ljava/lang/String;Lcom/mojang/blaze3d/resource/ResourceDescriptor;)Lcom/mojang/blaze3d/resource/ResourceHandle;"
		),
		index = 1
	)
	private ResourceDescriptor<?> metalcraft$promoteTransparencyFinal(final ResourceDescriptor<?> descriptor) {
		return descriptor instanceof RenderTargetDescriptor target
			? MetalLinearWorldPostActivation.promoteTransparencyInternal(
				MetalGpuDevices.current(), this.metalcraft$verifiedTransparency, target)
			: descriptor;
	}
}
