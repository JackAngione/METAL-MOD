package dev.metalcraft.client;

import com.mojang.logging.LogUtils;
import dev.metalcraft.client.metal.MetalCommandQueue;
import dev.metalcraft.client.metal.MetalDevice;
import dev.metalcraft.client.metal.MetalNative;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;

public final class MetalCraftPreLaunch implements PreLaunchEntrypoint {
	private static final Logger LOGGER = LogUtils.getLogger();

	@Override
	public void onPreLaunch() {
		if (MetalCraftPlatform.shouldUseDirectMetal()) {
			if (!MetalNative.load()) {
				LOGGER.error("MetalCraft could not load its direct Metal bridge", MetalNative.loadFailure().orElse(null));
			} else {
				MetalNative.openDefaultDevice().ifPresentOrElse(MetalCraftPreLaunch::probeDeviceAndQueue, () ->
					LOGGER.error("MetalCraft loaded its direct bridge, but Metal did not provide a default device")
				);
			}
			LOGGER.warn("Direct Metal backend selection is active and experimental; Minecraft's OpenGL crash recovery remains available");
		} else if (MetalCraftPlatform.isAppleSilicon()) {
			LOGGER.info("MetalCraft is disabled by the -Dmetalcraft.disable=true JVM property");
		} else {
			LOGGER.info("MetalCraft is inactive because this machine is not an Apple silicon Mac");
		}
	}

	private static void probeDeviceAndQueue(final MetalDevice device) {
		try (device; MetalCommandQueue commandQueue = device.createCommandQueue()) {
			LOGGER.info(
				"MetalCraft direct Metal bridge opened an owned device and command queue: {} (recommended working set: {} MiB)",
				device.name(),
				device.recommendedWorkingSetBytes() / 1024L / 1024L
			);
		} catch (RuntimeException error) {
			LOGGER.error("MetalCraft could not initialize its direct Metal device ownership layer", error);
		}
	}
}
