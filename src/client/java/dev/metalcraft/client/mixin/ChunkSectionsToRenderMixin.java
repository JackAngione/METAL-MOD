package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.metalcraft.client.metal.MetalWorldGeometry;
import dev.metalcraft.client.metal.MetalWorldShadow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes opaque chunk terrain into the shader pack's G-buffer.
 *
 * <p>Terrain draws through one render pass per layer group, so both halves of the substitution
 * happen here: the pass gains the pack's channels when it is created, and each layer's pipeline is
 * replaced by the stand-in that writes them. Redirects rather than an overwrite, because everything
 * between - the atlas and lightmap binds, the per-section uniform slices, the batched multi-draw -
 * is exactly what should keep running unchanged.
 *
 * <p>The translucent group is offered too and declines on its own terms: its pipeline blends, and a
 * blended draw cannot write a G-buffer.
 */
@Mixin(ChunkSectionsToRender.class)
abstract class ChunkSectionsToRenderMixin {
	@Inject(method = "renderGroup", at = @At("HEAD"))
	private void metalcraft$renderShadowsBeforeMain(
		final ChunkSectionLayerGroup group,
		final GpuSampler sampler,
		final CallbackInfo callback
	) {
		MetalWorldShadow.renderBeforeMain(group, sampler);
	}

	@Redirect(
		method = "renderGroup",
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
	private RenderPass metalcraft$beginTerrainPass(
		final CommandEncoder encoder,
		final Supplier<String> label,
		final GpuTextureView color,
		final Optional<Vector4fc> clearColor,
		final GpuTextureView depth,
		final OptionalDouble clearDepth,
		final ChunkSectionLayerGroup group,
		final GpuSampler sampler
	) {
		// The same choice renderGroup makes below, for the same reason it makes it: the wireframe
		// debug key replaces every layer's pipeline, and the pass has to be built for whichever
		// pipelines are actually going to be bound into it.
		boolean wireframe = SharedConstants.DEBUG_HOTKEYS && Minecraft.getInstance().wireframe;
		List<RenderPipeline> pipelines = new ArrayList<>(group.layers().length);
		for (ChunkSectionLayer layer : group.layers()) {
			pipelines.add(wireframe ? RenderPipelines.WIREFRAME : layer.pipeline());
		}
		return MetalWorldGeometry.beginWorldPass(encoder, label, color, clearColor, depth, clearDepth, pipelines);
	}

	@Redirect(
		method = "renderGroup",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
		)
	)
	private void metalcraft$setTerrainPipeline(final RenderPass pass, final RenderPipeline pipeline) {
		pass.setPipeline(MetalWorldGeometry.substitute(pass, pipeline));
	}
}
