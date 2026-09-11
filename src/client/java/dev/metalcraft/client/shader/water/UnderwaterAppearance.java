package dev.metalcraft.client.shader.water;

import dev.metalcraft.client.metal.MetalGpuDevices;
import dev.metalcraft.client.shader.ShaderPackRuntime;
import net.minecraft.client.renderer.fog.FogData;

/** Tunes the existing water fog once, before its uniform upload and world clear. */
public final class UnderwaterAppearance {
	private UnderwaterAppearance() { }

	public static boolean enabled() {
		ShaderPackRuntime runtime = ShaderPackRuntime.active();
		return MetalGpuDevices.current() != null && runtime != null && runtime.isActive()
			&& ShaderPackRuntime.BUILTIN_ID.equals(runtime.selectedPackId())
			&& Boolean.TRUE.equals(runtime.optionValue("water_enabled"));
	}

	public static void apply(final FogData fog) {
		// Negative vanilla starts tint even geometry touching the camera. Keep
		// nearby objects readable and retain biome/vision differences at longer distances.
		float span = Math.max(1.0F, fog.environmentalEnd - fog.environmentalStart);
		fog.environmentalStart = Math.max(0.0F, fog.environmentalStart);
		fog.environmentalEnd = fog.environmentalStart + span * 1.6F;
		fog.skyEnd = fog.environmentalEnd;
		fog.cloudEnd = fog.environmentalEnd;
		// Preserve brightness and a little biome hue, replacing saturated royal blue with
		// muted blue-green haze. Work before upload so clear/opaque/forward all agree.
		float luminance = fog.color.x * 0.2126F + fog.color.y * 0.7152F + fog.color.z * 0.0722F;
		fog.color.x = fog.color.x * 0.25F + luminance * 0.75F * 0.82F;
		fog.color.y = fog.color.y * 0.25F + luminance * 0.75F * 1.04F;
		fog.color.z = fog.color.z * 0.25F + luminance * 0.75F * 1.08F;
	}
}
