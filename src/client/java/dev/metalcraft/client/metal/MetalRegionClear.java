package dev.metalcraft.client.metal;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Clears a rectangle of a color/depth attachment pair.
 *
 * <p>Metal only clears whole attachments: a load action applies to the entire texture, and there is
 * no scissored equivalent of {@code vkCmdClearAttachments}. Blaze3D asks for a regional clear when
 * it recycles one slot of the GUI item atlas, so the region has to be painted instead of cleared —
 * a scissored oversized triangle over a pass that loads the existing contents, writing the clear
 * color and the clear depth directly.
 *
 * <p>The pipeline depends only on the attachment formats, so one is compiled per format pair and
 * reused. In practice that is a single pipeline, for the item atlas.
 */
final class MetalRegionClear implements AutoCloseable {
	/** Matches {@code mc_clear_params}: a {@code float4} followed by a {@code float}, padded to 16. */
	static final int PARAMETER_BYTES = 32;
	/** Deliberately coarse; a uniform binding offset must satisfy the strictest Metal alignment. */
	static final long PARAMETER_ALIGNMENT = 256L;

	private static final String SHADER_SOURCE = """
		#include <metal_stdlib>
		using namespace metal;

		struct mc_clear_params {
		    float4 color;
		    float depth;
		};

		vertex float4 metalcraft_clear_vertex(
		    uint vertexId [[vertex_id]],
		    constant mc_clear_params& params [[buffer(0)]]
		) {
		    // One oversized triangle covering the whole target; the scissor selects the region.
		    float2 corner = float2(float((vertexId << 1) & 2u), float(vertexId & 2u));
		    return float4(corner * 2.0f - 1.0f, params.depth, 1.0f);
		}

		fragment float4 metalcraft_clear_fragment(constant mc_clear_params& params [[buffer(0)]]) {
		    return params.color;
		}
		""";

	private record FormatKey(MetalTexture.Format colorFormat, MetalTexture.Format depthFormat) {
	}

	private final MetalDevice device;
	private final Map<FormatKey, MetalRenderPipeline> pipelines = new HashMap<>();

	MetalRegionClear(final MetalDevice device) {
		this.device = device;
	}

	/** Writes the shader parameters a {@link #clear} call binds. */
	static void writeParameters(
		final ByteBuffer destination,
		final float red,
		final float green,
		final float blue,
		final float alpha,
		final float depth
	) {
		destination.putFloat(red).putFloat(green).putFloat(blue).putFloat(alpha);
		destination.putFloat(depth);
		// The struct's tail padding is never read, but the buffer range still has to cover it.
		destination.putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
	}

	void clear(
		final MetalCommandBuffer commands,
		final MetalTexture color,
		final MetalTexture depth,
		final int regionX,
		final int regionY,
		final int regionWidth,
		final int regionHeight,
		final MetalBuffer parameters,
		final long parameterOffset
	) {
		MetalRenderPipeline pipeline = this.pipeline(color.descriptor().format(), depth.descriptor().format());
		try (MetalRenderPass pass = commands.beginRenderPass(new MetalRenderPass.Descriptor(
			new MetalRenderPass.ColorAttachment(
				color, MetalRenderPass.LoadAction.LOAD, MetalRenderPass.StoreAction.STORE, 0.0, 0.0, 0.0, 0.0
			),
			new MetalRenderPass.DepthAttachment(
				depth, MetalRenderPass.LoadAction.LOAD, MetalRenderPass.StoreAction.STORE, 0.0
			)
		))) {
			pass.setPipeline(pipeline);
			pass.setScissor(regionX, regionY, regionWidth, regionHeight);
			pass.setUniformBuffer(0, parameters, parameterOffset, MetalRenderPass.STAGE_ALL);
			pass.draw(MetalRenderPass.Primitive.TRIANGLE, 0, 3);
		}
	}

	private MetalRenderPipeline pipeline(final MetalTexture.Format colorFormat, final MetalTexture.Format depthFormat) {
		return this.pipelines.computeIfAbsent(new FormatKey(colorFormat, depthFormat), key -> this.device.createRenderPipeline(
			new MetalRenderPipeline.Descriptor(
				SHADER_SOURCE,
				"metalcraft_clear_vertex",
				SHADER_SOURCE,
				"metalcraft_clear_fragment",
				List.of(MetalRenderPipeline.ColorTarget.opaque(key.colorFormat())),
				key.depthFormat(),
				MetalRenderPipeline.VertexDescriptor.EMPTY,
				// ALWAYS so the written depth replaces whatever the slot held, exactly as a clear does.
				new MetalRenderPipeline.DepthState(true, true, MetalRenderPipeline.CompareFunction.ALWAYS, 0.0F, 0.0F),
				MetalRenderPipeline.RasterState.DEFAULT
			)
		));
	}

	@Override
	public void close() {
		this.pipelines.values().forEach(MetalRenderPipeline::close);
		this.pipelines.clear();
	}
}
