package dev.metalcraft.client.metal;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.shader.WorldGeometryAdapter;
import dev.metalcraft.client.shader.FrameBindings;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import dev.metalcraft.client.shader.WorldComposition;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;

/** CAMetalLayer-backed implementation of Minecraft's presentation interface. */
final class MetalGpuSurface implements GpuSurfaceBackend {
	private static final Logger LOGGER = LogUtils.getLogger();

	private final MetalGpuDevice device;
	private final long windowHandle;
	private MetalSurface metal;
	private MetalDrawable drawable;
	private boolean configured;
	private boolean displaySyncEnabled;

	MetalGpuSurface(final MetalGpuDevice device, final long windowHandle) {
		this.device = device;
		this.windowHandle = windowHandle;
	}

	@Override
	public void configure(final GpuSurface.Configuration config) throws SurfaceException {
		try {
			if (this.metal == null) {
				this.metal = this.device.metal().attachToGlfwWindow(this.windowHandle, config.width(), config.height());
			} else {
				this.metal.resize(config.width(), config.height());
			}
			// Logged because a benchmark that silently runs display-synced produces frame intervals
			// pinned to the refresh rate, which absorbs exactly the stalls the capture exists to
			// find and is indistinguishable from a healthy result unless the mode is recorded.
			// The unlocked option overrides the requested mode: macOS paces a display-synced
			// CAMetalLayer at the panel's refresh rate no matter what Minecraft's V-Sync option says,
			// so the only way to present unsynced is to turn the layer's own synchronization off.
			this.displaySyncEnabled = config.presentMode() != GpuSurface.PresentMode.IMMEDIATE
				&& !MetalCraftConfig.unlockedFrameRate();
			this.metal.setDisplaySyncEnabled(this.displaySyncEnabled);
			LOGGER.info("MetalCraft surface configured: {}x{} presentMode={} displaySync={} unlockedFrameRate={}",
				config.width(), config.height(), config.presentMode(), this.displaySyncEnabled,
				MetalCraftConfig.unlockedFrameRate());
			MetalSurfaceProbe.configured(config.width(), config.height());
			ShaderPackRuntime runtime = this.device.shaderPackRuntime();
			if (runtime != null) {
				runtime.resize(config.width(), config.height());
			}
			this.configured = true;
		} catch (RuntimeException error) {
			throw new SurfaceException("Metal could not configure the window surface: " + error.getMessage());
		}
	}

	/** Whether presentation is currently paced by the display, which caps every measured interval. */
	boolean isDisplaySyncEnabled() {
		return this.displaySyncEnabled;
	}

	@Override
	public boolean isSuboptimal() {
		return false;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (!this.configured || this.metal == null) {
			throw new SurfaceException("Metal surface is not configured");
		}
		// Timed because a blocked acquire is invisible to any CPU-side frame measurement.
		long acquireStartedNs = MetalStallProbe.begin();
		this.drawable = this.metal.acquireDrawable().orElseThrow(() -> new SurfaceException("Metal did not provide a drawable"));
		MetalStallProbe.end(MetalStallProbe.Source.ACQUIRE, acquireStartedNs);
		WorldGeometryAdapter adapter = WorldGeometryAdapter.active();
		if (adapter != null) {
			adapter.beginFrame();
		}
	}

	@Override
	public void blitFromTexture(final CommandEncoderBackend commandEncoder, final GpuTextureView textureView) {
		if (!(commandEncoder instanceof MetalCommandEncoder metalEncoder)) {
			throw new IllegalArgumentException("Command encoder does not belong to the direct Metal backend");
		}
		if (!(textureView instanceof MetalGpuTextureView metalView)) {
			throw new IllegalArgumentException("Presented texture does not belong to the direct Metal backend");
		}
		if (this.drawable == null) {
			throw new IllegalStateException("Metal surface has no acquired drawable");
		}
		MetalTexture scene = metalView.texture().metal();
		ShaderPackRuntime runtime = this.device.shaderPackRuntime();
		if (runtime != null && runtime.isActive()) {
			try {
				MetalCommandBuffer commands = metalEncoder.commands();
				runtime.resizeToScene(metalView.getWidth(0), metalView.getHeight(0));
				FrameBindings bindings = WorldComposition.present(
					scene, metalView.metal(), runtime.frameWidth(), runtime.frameHeight()
				);
				if (runtime.executor().orElseThrow().encode(commands, bindings)) {
					MetalTexture post = runtime.target("post_color");
					if (post != null) {
						metalEncoder.blitToDrawable(post, this.drawable);
						return;
					}
				}
			} catch (RuntimeException error) {
				LOGGER.error("MetalCraft shader pack failed to encode; presenting vanilla scene", error);
				runtime.markFailed(error.getMessage() == null ? error.toString() : error.getMessage(), error);
			}
		}
		metalEncoder.blitToDrawable(scene, this.drawable);
	}

	@Override
	public void present() {
		if (this.drawable == null) {
			throw new IllegalStateException("Metal surface has no acquired drawable");
		}
		// Also outside Minecraft's frame timer, and the counterpart to ACQUIRE: if presentation is
		// pacing the frame, the cost lands in one of the two.
		long startedNs = MetalStallProbe.begin();
		this.drawable.close();
		MetalStallProbe.end(MetalStallProbe.Source.PRESENT, startedNs);
		this.drawable = null;
	}

	@Override
	public void close() {
		if (this.drawable != null) {
			this.drawable.close();
			this.drawable = null;
		}
		if (this.metal != null) {
			this.metal.close();
			this.metal = null;
		}
		this.configured = false;
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}
}
