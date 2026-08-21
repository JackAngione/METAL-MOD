package dev.metalcraft.client;

import java.util.Locale;

public final class MetalCraftPlatform {
	private static final String DISABLE_PROPERTY = "metalcraft.disable";

	private MetalCraftPlatform() {
	}

	public static boolean isAppleSilicon() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		return (os.contains("mac") || os.contains("darwin")) && (arch.equals("aarch64") || arch.equals("arm64"));
	}

	public static boolean shouldUseDirectMetal() {
		return isAppleSilicon() && !Boolean.getBoolean(DISABLE_PROPERTY);
	}
}
