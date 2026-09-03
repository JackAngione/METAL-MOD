package dev.metalcraft.api;

import com.mojang.blaze3d.systems.DeviceInfo;

public record MetalCraftShaderContext(DeviceInfo deviceInfo, MetalCraftShaderRegistry registry) {
	public boolean isMetal() {
		return "Metal".equalsIgnoreCase(this.deviceInfo.backendName());
	}

	public boolean isVulkan() {
		return "Vulkan".equalsIgnoreCase(this.deviceInfo.backendName());
	}
}
