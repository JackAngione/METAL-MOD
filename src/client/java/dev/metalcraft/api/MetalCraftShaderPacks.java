package dev.metalcraft.api;

import dev.metalcraft.client.shader.ShaderPackRuntime;
import java.util.List;
import java.util.Optional;

/** Public discovery and selection surface for MetalCraft shader packs. */
public final class MetalCraftShaderPacks {
	private MetalCraftShaderPacks() {
	}

	public static void select(final String id) {
		runtime().selectPack(id);
	}

	public static List<MetalCraftShaderPackInfo> available() {
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		return runtime == null ? List.of() : runtime.availablePacks();
	}

	public static String selectedId() {
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		return runtime == null ? ShaderPackRuntime.NONE_ID : runtime.selectedPackId();
	}

	public static Optional<String> lastError() {
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		return runtime == null ? Optional.empty() : runtime.lastError();
	}

	private static ShaderPackRuntime runtime() {
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		if (runtime == null) {
			throw new IllegalStateException("MetalCraft shader packs are not available");
		}
		return runtime;
	}
}
