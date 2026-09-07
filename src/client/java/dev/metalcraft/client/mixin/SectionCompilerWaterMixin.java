package dev.metalcraft.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.metalcraft.client.shader.world.WaterIdentityDebug;
import dev.metalcraft.client.shader.water.SectionCompilerResultsWater;
import dev.metalcraft.client.shader.water.WaterVertexMetadata;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Identify fluids before their vertices join shared sorted terrain batches. */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerWaterMixin {
	@Unique
	private final ThreadLocal<CompileCapture> metalcraft$waterCapture = new ThreadLocal<>();

	@WrapMethod(method = "compile")
	private SectionCompiler.Results metalcraft$captureWaterMetadata(final SectionPos sectionPos,
		final RenderSectionRegion region, final VertexSorting vertexSorting,
		final SectionBufferBuilderPack builders, final Operation<SectionCompiler.Results> original) {
		CompileCapture previous = this.metalcraft$waterCapture.get();
		CompileCapture capture = new CompileCapture();
		this.metalcraft$waterCapture.set(capture);
		try {
			SectionCompiler.Results results = original.call(sectionPos, region, vertexSorting, builders);
			try {
				((SectionCompilerResultsWater)(Object)results)
					.metalcraft$setWaterVertexMetadata(capture.finish(results));
				return results;
			} catch (RuntimeException | Error error) {
				results.release();
				throw error;
			}
		} finally {
			if (previous == null) {
				this.metalcraft$waterCapture.remove();
			} else {
				this.metalcraft$waterCapture.set(previous);
			}
		}
	}

	@Redirect(method = "compile", at = @At(value = "INVOKE", target =
		"Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate("
			+ "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
			+ "Lnet/minecraft/client/renderer/block/FluidRenderer$Output;"
			+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
	private void metalcraft$identifyWater(final FluidRenderer renderer, final BlockAndTintGetter level,
		final BlockPos pos, final FluidRenderer.Output output, final BlockState block, final FluidState fluid) {
		CompileCapture capture = this.metalcraft$waterCapture.get();
		if (capture == null || !fluid.is(FluidTags.WATER)) {
			renderer.tesselate(level, pos, WaterIdentityDebug.wrap(output, fluid), block, fluid);
			return;
		}
		Vec3 flow = fluid.getFlow(level, pos);
		FluidRenderer.Output metadataOutput = layer -> capture.wrap(layer, output.getBuilder(layer), flow);
		renderer.tesselate(level, pos, WaterIdentityDebug.wrap(metadataOutput, fluid), block, fluid);
	}

	@Unique
	private static final class CompileCapture {
		private final Map<ChunkSectionLayer, WaterVertexMetadata.Builder> layers =
			new EnumMap<>(ChunkSectionLayer.class);

		VertexConsumer wrap(final ChunkSectionLayer layer, final VertexConsumer output, final Vec3 flow) {
			if (!(output instanceof BufferBuilder builder)) {
				return output;
			}
			int firstVertex = ((BufferBuilderAccessor)builder).metalcraft$vertices();
			WaterVertexMetadata.Builder metadata = this.layers.computeIfAbsent(layer,
				ignored -> new WaterVertexMetadata.Builder());
			return new WaterVertices(output, metadata, firstVertex, (float)flow.x, (float)flow.y, (float)flow.z);
		}

		Map<ChunkSectionLayer, WaterVertexMetadata> finish(final SectionCompiler.Results results) {
			Map<ChunkSectionLayer, WaterVertexMetadata> finished = new EnumMap<>(ChunkSectionLayer.class);
			for (Map.Entry<ChunkSectionLayer, WaterVertexMetadata.Builder> entry : this.layers.entrySet()) {
				if (!entry.getValue().isPopulated()) {
					continue;
				}
				var mesh = results.renderedLayers.get(entry.getKey());
				if (mesh == null) {
					throw new IllegalStateException("Water metadata has no terrain mesh for " + entry.getKey());
				}
				finished.put(entry.getKey(), entry.getValue().build(mesh.drawState().vertexCount()));
			}
			return Map.copyOf(finished);
		}
	}

	@Unique
	private static final class WaterVertices implements VertexConsumer {
		private final VertexConsumer delegate;
		private final WaterVertexMetadata.Builder metadata;
		private final float[] positions = new float[12];
		private final float flowX;
		private final float flowY;
		private final float flowZ;
		private int nextVertex;

		WaterVertices(final VertexConsumer delegate, final WaterVertexMetadata.Builder metadata,
			final int firstVertex, final float flowX, final float flowY, final float flowZ) {
			this.delegate = delegate;
			this.metadata = metadata;
			this.nextVertex = firstVertex;
			this.flowX = flowX;
			this.flowY = flowY;
			this.flowZ = flowZ;
		}

		@Override
		public VertexConsumer addVertex(final float x, final float y, final float z) {
			this.delegate.addVertex(x, y, z);
			this.record(x, y, z);
			return this;
		}

		@Override
		public void addVertex(final float x, final float y, final float z, final int color,
			final float u, final float v, final int overlayCoords, final int lightCoords,
			final float nx, final float ny, final float nz) {
			this.delegate.addVertex(x, y, z, color, u, v, overlayCoords, lightCoords, nx, ny, nz);
			this.record(x, y, z);
		}

		private void record(final float x, final float y, final float z) {
			int quadVertex = this.nextVertex & 3;
			int offset = quadVertex * 3;
			this.positions[offset] = x;
			this.positions[offset + 1] = y;
			this.positions[offset + 2] = z;
			if (quadVertex == 3) {
				this.metadata.putQuad(this.nextVertex - 3, this.positions,
					this.flowX, this.flowY, this.flowZ);
			}
			this.nextVertex++;
		}

		@Override
		public VertexConsumer setColor(final int r, final int g, final int b, final int a) {
			this.delegate.setColor(r, g, b, a);
			return this;
		}

		@Override
		public VertexConsumer setColor(final int color) {
			this.delegate.setColor(color);
			return this;
		}

		@Override
		public VertexConsumer setUv(final float u, final float v) {
			this.delegate.setUv(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv1(final int u, final int v) {
			this.delegate.setUv1(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv2(final int u, final int v) {
			this.delegate.setUv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer setNormal(final float x, final float y, final float z) {
			this.delegate.setNormal(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(final float width) {
			this.delegate.setLineWidth(width);
			return this;
		}
	}
}
