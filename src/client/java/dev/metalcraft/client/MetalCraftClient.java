package dev.metalcraft.client;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import dev.metalcraft.api.MetalCraftShaderContext;
import dev.metalcraft.api.MetalCraftShaderExtension;
import dev.metalcraft.api.MetalCraftShaderRegistry;
import dev.metalcraft.api.MetalCraftShaders;
import dev.metalcraft.api.MetalCraftLights;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public final class MetalCraftClient implements ClientModInitializer {
	private static final Logger LOGGER = LogUtils.getLogger();
	public static final String SHADER_ENTRYPOINT = "metalcraft-shaders";

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> initializeRendererExtensions());
	}

	private static void initializeRendererExtensions() {
		GpuDevice device = RenderSystem.getDevice();
		DeviceInfo info = device.getDeviceInfo();
		boolean metalPath = MetalCraftPlatform.isAppleSilicon() && "Metal".equalsIgnoreCase(info.backendName());
		if (metalPath) {
			LOGGER.info("MetalCraft direct renderer active: {} / {} using {}", info.vendorName(), info.name(), info.backendName());
		} else {
			LOGGER.warn("MetalCraft Metal path is not active; current backend is {} on {} / {}", info.backendName(), info.vendorName(), info.name());
		}

		MetalCraftShaderRegistry registry = MetalCraftShaders.registry();
		MetalCraftShaderContext context = new MetalCraftShaderContext(info, registry, MetalCraftLights.registry());
		List<MetalCraftShaderExtension> extensions = FabricLoader.getInstance().getEntrypoints(SHADER_ENTRYPOINT, MetalCraftShaderExtension.class);
		for (MetalCraftShaderExtension extension : extensions) {
			extension.registerShaders(context);
		}

		precompileRegisteredShaders();
	}

	public static void precompileRegisteredShaders() {
		MetalCraftShaderRegistry registry = MetalCraftShaders.registry();
		int total = registry.pipelines().size();
		if (total == 0) {
			return;
		}

		GpuDevice device = RenderSystem.getDevice();
		int valid = registry.precompileAll(device);
		if (total > 0) {
			LOGGER.info("Precompiled {}/{} MetalCraft shader pipelines for the {} backend", valid, total, device.getDeviceInfo().backendName());
		}
	}
}
