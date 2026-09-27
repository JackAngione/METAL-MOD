package dev.metalcraft.client;

import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import dev.metalcraft.api.MetalCraftShaderContext;
import dev.metalcraft.api.MetalCraftShaderExtension;
import dev.metalcraft.api.MetalCraftShaderRegistry;
import dev.metalcraft.api.MetalCraftShaders;
import dev.metalcraft.client.shader.ShaderPackRuntime;
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
		dev.metalcraft.client.lod.LodSystem.initialize();
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> initializeRendererExtensions());
		if (Integer.getInteger("metalcraft.memoryProbeSeconds", 0) > 0)
			dev.metalcraft.client.test.MetalMemoryProbe.register();
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
		MetalCraftShaderContext context = new MetalCraftShaderContext(info, registry);
		List<MetalCraftShaderExtension> extensions = FabricLoader.getInstance().getEntrypoints(SHADER_ENTRYPOINT, MetalCraftShaderExtension.class);
		registerExtensions(context, extensions);

		precompileRegisteredShaders();
	}

	/**
	 * Registers each {@code metalcraft-shaders} extension independently. One implementation's
	 * exception or error skips that extension and leaves the rest of the list to load.
	 */
	public static void registerExtensions(final MetalCraftShaderContext context, final List<MetalCraftShaderExtension> extensions) {
		for (MetalCraftShaderExtension extension : extensions) {
			try {
				extension.registerShaders(context);
			} catch (RuntimeException | Error error) {
				LOGGER.error(
					"MetalCraft shader extension {} failed to register; other extensions will still load",
					extension.getClass().getName(),
					error
				);
			}
		}
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

	/** Re-reads the selected pack after a resource reload. No-ops when no runtime exists. */
	public static void reloadShaderPackRuntime() {
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		if (runtime != null) {
			runtime.reload();
		}
	}
}
