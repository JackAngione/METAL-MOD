package dev.metalcraft.api;

/** A discovered shader pack that can be selected in the MetalCraft runtime. */
public record MetalCraftShaderPackInfo(String id, String name) {
	public MetalCraftShaderPackInfo {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("A shader pack requires a non-blank ID");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("A shader pack requires a non-blank name");
		}
	}
}
