package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.MetalBuffer;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalRenderPass;
import dev.metalcraft.client.metal.MetalSampler;
import dev.metalcraft.client.metal.MetalTexture;
import dev.metalcraft.client.metal.MetalTextureView;
import java.nio.ByteBuffer;
import java.util.List;
import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Owns the sun's stored depth array. Recreate on shadow settings changes, not window resize.
 * All shadow writes and reads must be ordered on the same command queue.
 * Terrain collection and the world render hook live in {@link WorldTerrainShadows}.
 */
public final class WorldShadowModule implements AutoCloseable {
	public static final String FRAME_BUFFER_NAME = "shadow_frame";
	public static final String DEPTH_TEXTURE_NAME = "shadow_map";
	/** Matches shared/shadows.metal: four matrices, two camera matrices, three 16-byte vectors. */
	public static final int FRAME_BYTES = 432;
	private final ShadowCascades.Settings settings;
	private final MetalTexture depth;
	private final MetalTextureView depthView;
	private final MetalSampler sampler;
	private boolean closed;

	public WorldShadowModule(final MetalDevice device, final ShadowCascades.Settings settings) {
		this.settings = java.util.Objects.requireNonNull(settings, "settings");
		// MetalTexture's single-layer descriptor is a texture2d. Keep even one logical
		// cascade a depth2d_array without changing the generic texture ABI.
		this.depth = device.createTexture(MetalTexture.Descriptor.array(
			MetalTexture.Format.DEPTH32_FLOAT, settings.resolution(), settings.resolution(),
			Math.max(2, settings.count()), MetalTexture.USAGE_RENDER_TARGET | MetalTexture.USAGE_SHADER_READ));
		MetalTextureView view = null;
		try {
			view = this.depth.createView();
			this.sampler = device.createSampler(new MetalSampler.Descriptor(
				MetalSampler.Filter.NEAREST, MetalSampler.Filter.NEAREST, MetalSampler.AddressMode.CLAMP_TO_EDGE));
			this.depthView = view;
		} catch (RuntimeException error) {
			if (view != null) view.close();
			this.depth.close();
			throw error;
		}
	}

	/** Clears every physical layer to unoccluded depth; stores it for later resolve sampling. */
	public MetalRenderPass.Descriptor depthPass() {
		this.requireOpen();
		return new MetalRenderPass.Descriptor(List.of(), new MetalRenderPass.DepthAttachment(
			this.depth, MetalRenderPass.LoadAction.CLEAR, MetalRenderPass.StoreAction.STORE, 1.0),
			this.depth.descriptor().sliceCount());
	}

	/**
	 * An immutable upload per frame: later CPU frames cannot overwrite uniforms still in flight.
	 * Close after encoding all consumers; native command buffers retain encoded resources.
	 * inverseProjection maps Metal clip coordinates (depth [0,1]) to view space;
	 * viewToCameraRelative is the camera rotation only, with no absolute-world translation.
	 */
	public Frame prepareFrame(final Vector3dc cameraPosition, final Quaternionfc cameraRotation,
		final float verticalFovRadians, final float aspect, final Vector3fc directionToSun,
		final Matrix4fc inverseProjection) {
		this.requireOpen();
		if (!inverseProjection.isFinite() || !Float.isFinite(inverseProjection.determinant())
			|| inverseProjection.determinant() == 0) {
			throw new IllegalArgumentException("Invalid inverse camera projection");
		}
		List<ShadowCascades.Cascade> cascades = ShadowCascades.fit(this.settings, cameraPosition,
			cameraRotation, verticalFovRadians, aspect, directionToSun);
		MetalBuffer buffer = this.depth.device().createBuffer(FRAME_BYTES, MetalBuffer.StorageMode.SHARED);
		try (MetalBuffer.Mapping mapping = buffer.map()) {
			ByteBuffer bytes = mapping.bytes();
			for (int i = 0; i < FRAME_BYTES; i++) bytes.put(i, (byte)0);
			for (int i = 0; i < cascades.size(); i++) {
				cascades.get(i).cameraRelativeToShadow().get(i * 64, bytes);
				bytes.putFloat(384 + i * 4, cascades.get(i).far());
			}
			inverseProjection.get(256, bytes);
			new org.joml.Matrix4f().rotation(new org.joml.Quaternionf(cameraRotation).normalize()).get(320, bytes);
			Vector3f sun = new Vector3f(directionToSun).normalize();
			bytes.putFloat(400, sun.x).putFloat(404, sun.y).putFloat(408, sun.z);
			bytes.putInt(416, this.settings.count());
			bytes.putFloat(420, 1.0F / this.settings.resolution());
			bytes.putFloat(424, this.settings.distance());
			bytes.putFloat(428, this.settings.casterExtension());
		} catch (RuntimeException error) {
			buffer.close();
			throw error;
		}
		return new Frame(buffer, cascades);
	}

	/**
	 * A defined unoccluded frame: cascade count is zero, so sampling returns 1 without reading
	 * yesterday's depth. Bind this when there is no sun, no world, or no current caster list.
	 */
	public Frame prepareUnoccludedFrame() {
		this.requireOpen();
		MetalBuffer buffer = this.depth.device().createBuffer(FRAME_BYTES, MetalBuffer.StorageMode.SHARED);
		try (MetalBuffer.Mapping mapping = buffer.map()) {
			ByteBuffer bytes = mapping.bytes();
			for (int i = 0; i < FRAME_BYTES; i++) bytes.put(i, (byte)0);
		} catch (RuntimeException error) {
			buffer.close();
			throw error;
		}
		return new Frame(buffer, List.of());
	}

	/** Borrowed bindings, valid while this module and frame are open. Slots come from the caller's layout. */
	public final class Frame implements AutoCloseable {
		private final MetalBuffer uniforms;
		private final List<ShadowCascades.Cascade> cascades;

		private Frame(final MetalBuffer uniforms, final List<ShadowCascades.Cascade> cascades) {
			this.cascades = cascades;
			this.uniforms = uniforms;
		}

		/** Conservative union of the fitted Metal clip volumes, including the caster extension. */
		public boolean intersects(final float minX, final float minY, final float minZ,
			final float maxX, final float maxY, final float maxZ) {
			return this.cascadeMask(minX, minY, minZ, maxX, maxY, maxZ) != 0;
		}

		/** Per-cascade conservative caster membership, including each fitted overlap/extension. */
		public int cascadeMask(final float minX, final float minY, final float minZ,
			final float maxX, final float maxY, final float maxZ) {
			int mask = 0;
			for (int i = 0; i < this.cascades.size(); i++) {
				if (ShadowCasterVolume.intersects(this.cascades.get(i).cameraRelativeToShadow(),
					minX, minY, minZ, maxX, maxY, maxZ)) mask |= 1 << i;
			}
			return mask;
		}

		public int cascadeCount() {
			return this.cascades.size();
		}

		public void bindUniforms(final MetalRenderPass pass, final int bufferSlot, final int stages) {
			WorldShadowModule.this.requireOpen();
			pass.setUniformBuffer(bufferSlot, this.uniforms, 0, stages);
		}

		public void bindDepth(final MetalRenderPass pass, final int textureSlot) {
			WorldShadowModule.this.requireOpen();
			if (this.uniforms.isClosed()) throw new IllegalStateException("Shadow frame is closed");
			pass.setTexture(textureSlot, WorldShadowModule.this.depthView, MetalRenderPass.STAGE_FRAGMENT);
			pass.setSampler(textureSlot, WorldShadowModule.this.sampler, MetalRenderPass.STAGE_FRAGMENT);
		}

		@Override
		public void close() {
			this.uniforms.close();
		}
	}

	private void requireOpen() {
		if (this.closed) throw new IllegalStateException("World shadow module is closed");
	}

	@Override
	public void close() {
		if (this.closed) return;
		this.closed = true;
		this.sampler.close();
		this.depthView.close();
		this.depth.close();
	}
}
