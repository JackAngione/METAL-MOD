package dev.metalcraft.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.metalcraft.client.shader.WorldGeometryAdapter;
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

/** Routes opaque chunk terrain into the shader pack's G-buffer. */
@Mixin(ChunkSectionsToRender.class)
abstract class ChunkSectionsToRenderMixin {
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
		boolean wireframe = SharedConstants.DEBUG_HOTKEYS && Minecraft.getInstance().wireframe;
		List<RenderPipeline> pipelines = new ArrayList<>(group.layers().length);
		for (ChunkSectionLayer layer : group.layers()) {
			pipelines.add(wireframe ? RenderPipelines.WIREFRAME : layer.pipeline());
		}
		return WorldGeometryAdapter.beginWorldPass(encoder, label, color, clearColor, depth, clearDepth, pipelines);
	}

	@Redirect(
		method = "renderGroup",
		at = @At(
			value = "INVOKE",
			target = "Lcom/mojang/blaze3d/systems/RenderPass;setPipeline(Lcom/mojang/blaze3d/pipeline/RenderPipeline;)V"
		)
	)
	private void metalcraft$setTerrainPipeline(final RenderPass pass, final RenderPipeline pipeline) {
		pass.setPipeline(WorldGeometryAdapter.substitute(pass, pipeline));
	}
}
