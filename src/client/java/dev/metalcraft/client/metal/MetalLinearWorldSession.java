package dev.metalcraft.client.metal;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.metalcraft.client.shader.water.WaterFrameInputs;
import dev.metalcraft.client.shader.wind.WindAnimation;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * One fail-closed HDR world frame. Attachment routing is by captured main color/depth identity,
 * never by label, extent or format. Unsupported draws are suppressed rather than mixed with
 * legacy encoded color.
 */
public final class MetalLinearWorldSession implements AutoCloseable {
	public record Token(
		MetalGpuTextureView originalColor,
		GpuTexture originalColorTexture,
		MetalGpuTextureView originalDepth,
		GpuTexture originalDepthTexture,
		MetalGpuTextureView hdrColor,
		MetalGpuTextureView hdrDepth,
		int width,
		int height,
		long shaderGeneration,
		long nativeGeneration,
		boolean fabulous,
		ShaderSource shaderSource
	) {
		public Token {
			if (originalColor == null || originalColorTexture == null || originalDepth == null
				|| originalDepthTexture == null || hdrColor == null || hdrDepth == null
				|| shaderSource == null) {
				throw new NullPointerException("Linear world session token is incomplete");
			}
			if (width <= 0 || height <= 0) {
				throw new IllegalArgumentException("Linear world session extents must be positive");
			}
		}
	}

	public record PostContract(RenderPipeline pipeline, LinearWorldPostShaders.Semantic semantic) {
		public PostContract {
			if (pipeline == null || semantic == null) {
				throw new NullPointerException("A linear post contract needs a pipeline and semantic");
			}
		}
	}

	private final MetalGpuDevice device;
	private final Token token;
	private final Set<RenderPipeline> approved = Collections.newSetFromMap(new IdentityHashMap<>());
	private boolean poisoned;
	private @Nullable WaterFrameInputs waterFrameInputs;
	private @Nullable MetalBuffer waterFrameBuffer;
	private float windAnimationSeconds;
	private int waterDraws;
	private boolean preparedWater;
	/** Called as native and LOD translucent draws receive their immutable water metadata. */
	public void prepareWaterDraw() { this.requireOpen(); this.preparedWater = true; }
	boolean hasPreparedWater() { return this.preparedWater; }
	void recordWaterDraw() { this.waterDraws++; }
	int waterDraws() { return this.waterDraws; }
	private boolean closed;

	MetalLinearWorldSession(final MetalGpuDevice device, final Token token) {
		this.device = device;
		this.token = token;
	}

	public Token token() {
		return this.token;
	}

	public void waterFrameInputs(final @Nullable WaterFrameInputs inputs) {
		this.requireOpen();
		if (this.waterFrameBuffer != null) throw new IllegalStateException("Water frame inputs are already bound");
		this.waterFrameInputs = inputs;
	}

	public void windAnimationTime(final long gameTicks, final float partialTick) {
		this.requireOpen();
		this.windAnimationSeconds = WindAnimation.seconds(gameTicks, partialTick);
	}

	public float windAnimationSeconds() {
		return this.windAnimationSeconds;
	}

	@Nullable MetalBuffer waterFrameBuffer() {
		if (this.waterFrameInputs() == null) return null;
		if (this.waterFrameBuffer == null) {
			MetalBuffer next = this.device.metal().createBuffer(WaterFrameInputs.UNIFORM_BYTES, MetalBuffer.StorageMode.SHARED);
			try (MetalBuffer.Mapping mapping = next.map()) { this.waterFrameInputs.write(mapping.bytes()); }
			catch (RuntimeException error) { next.close(); throw error; }
			this.waterFrameBuffer = next;
		}
		return this.waterFrameBuffer;
	}

	public @Nullable WaterFrameInputs waterFrameInputs() {
		return this.closed || this.poisoned ? null : this.waterFrameInputs;
	}

	public boolean isPoisoned() {
		return this.poisoned;
	}

	public boolean isClosed() {
		return this.closed;
	}

	public MetalGpuTextureView hdrColor() {
		return this.token.hdrColor();
	}

	public MetalGpuTextureView hdrDepth() {
		return this.token.hdrDepth();
	}

	void approve(final RenderPipeline pipeline) {
		this.requireOpen();
		this.approved.add(pipeline);
	}

	boolean isApproved(final RenderPipeline pipeline) {
		return this.approved.contains(pipeline);
	}

	void poison() {
		this.poisoned = true;
		this.device.invalidateOpaqueWaterInputs();
	}

	boolean ownsTranslated(final @Nullable MetalGpuTextureView color, final @Nullable MetalGpuTextureView depth) {
		return color == this.token.hdrColor() || (color == null && depth == this.token.hdrDepth());
	}

	GpuTextureView translateView(final GpuTextureView view) {
		this.requireOpen();
		if (view == this.token.originalColor() || texture(view) == this.token.originalColorTexture()) {
			return this.token.hdrColor();
		}
		if (view == this.token.originalDepth() || texture(view) == this.token.originalDepthTexture()) {
			return this.token.hdrDepth();
		}
		return view;
	}

	GpuTexture translateTexture(final GpuTexture texture) {
		this.requireOpen();
		if (texture == this.token.originalColorTexture()) return this.token.hdrColor().texture();
		if (texture == this.token.originalDepthTexture()) return this.token.hdrDepth().texture();
		return texture;
	}

	private static @Nullable GpuTexture texture(final GpuTextureView view) {
		return view instanceof MetalGpuTextureView metal ? metal.texture() : null;
	}

	private void requireOpen() {
		if (this.closed) throw new IllegalStateException("Linear world session is closed");
	}

	@Override
	public void close() {
		if (this.closed) return;
		this.closed = true;
		this.device.endLinearWorld(this);
		if (this.waterFrameBuffer != null) this.waterFrameBuffer.close();
		this.waterFrameBuffer = null;
	}
}
