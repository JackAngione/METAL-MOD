package dev.metalcraft.client.metal;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import dev.metalcraft.client.MetalCraftConfig;
import dev.metalcraft.client.shader.WorldGeometryAdapter;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.util.Collection;
import java.util.List;
import org.slf4j.Logger;

/** CAMetalLayer-backed implementation of Minecraft's presentation interface. */
final class MetalGpuSurface implements GpuSurfaceBackend {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final boolean LATE_DRAWABLE = Boolean.parseBoolean(System.getProperty("metalcraft.lateDrawable", "true"));

	private final MetalGpuDevice device;
	private final long windowHandle;
	private MetalSurface metal;
	private MetalDrawable drawable;
	private boolean configured;
	private boolean displaySyncEnabled;
	private boolean framePending;
	private @org.jspecify.annotations.Nullable SurfaceException acquisitionFailure;

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
			LOGGER.info("Metal Mod surface configured: {}x{} presentMode={} displaySync={} unlockedFrameRate={}",
				config.width(), config.height(), config.presentMode(), this.displaySyncEnabled,
				MetalCraftConfig.unlockedFrameRate());
			MetalSurfaceProbe.configured(config.width(), config.height());
			ShaderPackRuntime runtime = this.device.shaderPackRuntime();
			if (runtime != null) {
				runtime.resize(config.width(), config.height());
			}
			this.configured = true;
			this.acquisitionFailure = null;
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
		return this.acquisitionFailure != null;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (!this.configured || this.metal == null) {
			throw new SurfaceException("Metal surface is not configured");
		}
		if (this.framePending) throw new SurfaceException("Metal surface already has a pending frame");
		if (this.acquisitionFailure != null) throw this.acquisitionFailure;
		if (!LATE_DRAWABLE) this.acquireDrawable();
		this.framePending = true;
		// This hook owns shader/LOD frame state and must still run before extraction/rendering.
		// Only CAMetalLayer acquisition moves; offscreen rendering needs no drawable.
		WorldGeometryAdapter adapter = WorldGeometryAdapter.active();
		if (adapter != null) {
			adapter.beginFrame();
		}
	}

	private void acquireDrawable() throws SurfaceException {
		long startedNs = MetalStallProbe.begin();
		try {
			this.drawable = this.metal.acquireDrawable().orElseThrow(() -> new SurfaceException("Metal did not provide a drawable"));
		} finally {
			MetalStallProbe.end(MetalStallProbe.Source.ACQUIRE, startedNs);
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
		if (!this.framePending) throw new IllegalStateException("Metal surface has no pending frame");
		if (this.drawable == null) {
			try {
				this.acquireDrawable();
			} catch (SurfaceException | RuntimeException error) {
				// Blaze3D's blit API cannot throw SurfaceException. Drop only presentation,
				// submit the offscreen work normally, and request surface recovery next frame.
				this.acquisitionFailure = new SurfaceException("Late Metal drawable acquisition failed: " + error.getMessage());
				LOGGER.warn("{}", this.acquisitionFailure.getMessage());
				return;
			}
		}
		MetalTexture scene = metalView.texture().metal();
		metalEncoder.blitToDrawable(scene, this.drawable);
	}

	@Override
	public void present() {
		if (!this.framePending) throw new IllegalStateException("Metal surface has no pending frame");
		// Also outside Minecraft's frame timer, and the counterpart to ACQUIRE: if presentation is
		// pacing the frame, the cost lands in one of the two.
		long startedNs = MetalStallProbe.begin();
		if (this.drawable != null) this.drawable.close();
		MetalStallProbe.end(MetalStallProbe.Source.PRESENT, startedNs);
		this.drawable = null;
		this.framePending = false;
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
		this.framePending = false;
		this.acquisitionFailure = null;
	}

	@Override
	public Collection<GpuSurface.PresentMode> supportedPresentModes() {
		return List.of(GpuSurface.PresentMode.IMMEDIATE, GpuSurface.PresentMode.FIFO);
	}
}
