package dev.metalcraft.client.shader.world;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;

/** Mesh-build diagnostic: magenta water, with original UVs, alpha, light and sorting. */
public final class WaterIdentityDebug {
	private static volatile boolean enabled = Boolean.getBoolean("metalcraft.waterIdentityDebug");

	private WaterIdentityDebug() {
	}

	public static boolean enabled() {
		return enabled;
	}

	/** Changing this flag requires a terrain rebuild; it is not a shader uniform. */
	public static void setEnabled(final boolean value) {
		enabled = value;
	}

	public static FluidRenderer.Output wrap(final FluidRenderer.Output output, final FluidState fluid) {
		if (!enabled || !fluid.is(FluidTags.WATER)) {
			return output;
		}
		return layer -> new WaterVertices(output.getBuilder(layer));
	}

	private record WaterVertices(VertexConsumer delegate) implements VertexConsumer {
		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			this.delegate.addVertex(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			this.delegate.setColor(255, 0, 255, a);
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			this.delegate.setColor((color & 0xff000000) | 0x00ff00ff);
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			this.delegate.setUv(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			this.delegate.setUv1(u, v);
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			this.delegate.setUv2(u, v);
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			this.delegate.setNormal(x, y, z);
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			this.delegate.setLineWidth(width);
			return this;
		}
	}
}
