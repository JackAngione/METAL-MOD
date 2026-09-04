package dev.metalcraft.client.shader;

import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalTexture;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Allocates pack targets. The identity/grade pack owns a single private {@code post_color}.
 */
public final class ShaderTargetAllocator implements AutoCloseable {
	private final MetalDevice device;
	private @Nullable MetalTexture postColor;

	ShaderTargetAllocator(final MetalDevice device) {
		this.device = Objects.requireNonNull(device, "device");
	}

	/**
	 * Creates or replaces private 2D {@code BGRA8_UNORM} {@code post_color} at {@code width × height}.
	 */
	void resize(final int width, final int height) {
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Shader target dimensions must be positive");
		}
		if (this.postColor != null
			&& this.postColor.descriptor().width() == width
			&& this.postColor.descriptor().height() == height) {
			return;
		}
		MetalTexture replacement = this.device.createTexture(new MetalTexture.Descriptor(
			MetalTexture.Format.BGRA8_UNORM,
			width,
			height,
			1,
			MetalTexture.USAGE_SHADER_READ | MetalTexture.USAGE_RENDER_TARGET
		));
		if (this.postColor != null) {
			this.postColor.close();
		}
		this.postColor = replacement;
	}

	@Nullable MetalTexture target(final String id) {
		return "post_color".equals(id) ? this.postColor : null;
	}

	void release() {
		if (this.postColor != null) {
			this.postColor.close();
			this.postColor = null;
		}
	}

	@Override
	public void close() {
		this.release();
	}
}
