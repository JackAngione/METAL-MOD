package dev.metalcraft.client.shader.world;

import dev.metalcraft.client.metal.MetalBuffer;
import java.nio.ByteBuffer;
import org.joml.Vector4fc;

/**
 * Vanilla lightmap lighting contract for deferred resolve. No occupancy volume, point-light
 * list, or extraBuffers bag.
 *
 * <p>G-buffer (see {@code shared/lighting.metal}):
 * <ul>
 *   <li>{@code scene} — albedo * RGB lightmap (* overlays), then vanilla fog. Emissive skips the lightmap.</li>
 *   <li>{@code albedo.rgb} — surface before light; {@code albedo.a = (materialId + 1) / 255}.</li>
 *   <li>{@code normal.rg} — octahedral view-space unit normal; {@code normal.b} is roughness.</li>
 *   <li>{@code light.rg} — UV2 block/sky in {@code [0, 1]}.</li>
 *   <li>{@code normal.a} + {@code light.ba} — 24-bit linear view depth over {@code [0, 1024]}.</li>
 * </ul>
 *
 * <p>Materials match {@code WorldGeometryAdapter.Material}: solid, foliage, water, entity,
 * emissive. Emission and damage overlays stay in the seed; they are not multiplied by sun
 * visibility. The RGB lightmap mixes sky and block energy, so it is not an isolated sun term.
 * Direct sun is the sky-weighted fraction of recovered (unfogged) lighting times {@code N·L},
 * then cascaded shadow visibility. Indirect is the remainder. {@code visibility = 1} is an
 * identity on the unfogged seed. Fog is decoded and reapplied after lighting.
 *
 * <p>{@link #FRAME_BYTES} matches {@code sizeof(McFog)} in MSL (40-byte members, 16-byte
 * aligned). Uncaptured fog uses starts/ends beyond any world distance so the original fog
 * function yields zero.
 */
public final class WorldLightingModule {
	public static final int FRAME_BYTES = 48;
	/** Larger than any packed view depth, so {@code distance <= start} disables fog. */
	public static final float DISABLED_FOG_DISTANCE = 1.0e10F;

	private WorldLightingModule() {
	}

	public static void writeIdentity(final MetalBuffer buffer) {
		write(buffer, 0.0F, 0.0F, 0.0F, 0.0F,
			DISABLED_FOG_DISTANCE, DISABLED_FOG_DISTANCE, DISABLED_FOG_DISTANCE, DISABLED_FOG_DISTANCE,
			DISABLED_FOG_DISTANCE, DISABLED_FOG_DISTANCE);
	}

	public static void write(final MetalBuffer buffer, final Vector4fc fogColor,
		final float environmentalStart, final float environmentalEnd,
		final float renderDistanceStart, final float renderDistanceEnd,
		final float skyEnd, final float cloudsEnd) {
		write(buffer, fogColor.x(), fogColor.y(), fogColor.z(), fogColor.w(),
			environmentalStart, environmentalEnd, renderDistanceStart, renderDistanceEnd, skyEnd, cloudsEnd);
	}

	public static void write(final MetalBuffer buffer, final float fogRed, final float fogGreen,
		final float fogBlue, final float fogAlpha,
		final float environmentalStart, final float environmentalEnd,
		final float renderDistanceStart, final float renderDistanceEnd,
		final float skyEnd, final float cloudsEnd) {
		if (buffer.size() < FRAME_BYTES) {
			throw new IllegalArgumentException("Lighting frame buffer is smaller than " + FRAME_BYTES);
		}
		try (MetalBuffer.Mapping mapping = buffer.map()) {
			ByteBuffer bytes = mapping.bytes();
			for (int index = 0; index < FRAME_BYTES; index++) {
				bytes.put(index, (byte)0);
			}
			bytes.putFloat(0, fogRed);
			bytes.putFloat(4, fogGreen);
			bytes.putFloat(8, fogBlue);
			bytes.putFloat(12, fogAlpha);
			bytes.putFloat(16, environmentalStart);
			bytes.putFloat(20, environmentalEnd);
			bytes.putFloat(24, renderDistanceStart);
			bytes.putFloat(28, renderDistanceEnd);
			bytes.putFloat(32, skyEnd);
			bytes.putFloat(36, cloudsEnd);
		}
	}
}
