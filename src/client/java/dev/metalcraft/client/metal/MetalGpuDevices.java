package dev.metalcraft.client.metal;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.metalcraft.client.mixin.GpuDeviceAccessor;
import org.jspecify.annotations.Nullable;

/** Resolves the live Metal backend from Blaze3D's GpuDevice wrapper. */
public final class MetalGpuDevices {
	private MetalGpuDevices() {
	}

	public static @Nullable MetalGpuDevice current() {
		GpuDevice device = RenderSystem.tryGetDevice();
		return device instanceof GpuDeviceAccessor accessor
			&& accessor.metalcraft$backend() instanceof MetalGpuDevice metal ? metal : null;
	}
}
