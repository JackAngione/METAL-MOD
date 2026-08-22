package dev.metalcraft.client.test;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Picks a ground-level camera site for the realistic benchmark.
 *
 * <p>Reading the generator's surface noise directly lets the site be chosen before any chunk is
 * generated, so the benchmark never has to trust a hand-picked coordinate to stay on dense land
 * across seeds or worldgen changes. The chosen site is inland, above sea level, and surrounded by
 * the roughest terrain found in the search area, which is what a player actually stands in.
 */
public final class MetalBenchmarkScene {
	/** Half-width of the terrain sample window around a candidate, in blocks. */
	private static final int SAMPLE_RADIUS = 48;
	private static final int SAMPLE_STEP = 24;
	/** Ground must clear sea level by this much everywhere in the window, rejecting oceans and beaches. */
	private static final int MINIMUM_FREEBOARD = 6;
	/** How far the view probe walks the surface when choosing a heading, in blocks. */
	private static final int VIEW_PROBE_DISTANCE = 256;

	private MetalBenchmarkScene() {
	}

	/**
	 * @param searchRadius half-width of the candidate search area around the origin, in blocks
	 * @param candidateStep spacing between candidate sites, in blocks
	 */
	public static Site choose(final ServerLevel level, final int originX, final int originZ,
			final int searchRadius, final int candidateStep) {
		ChunkGenerator generator = level.getChunkSource().getGenerator();
		RandomState randomState = level.getChunkSource().randomState();
		int seaLevel = level.getSeaLevel();

		Site best = null;
		for (int x = originX - searchRadius; x <= originX + searchRadius; x += candidateStep) {
			for (int z = originZ - searchRadius; z <= originZ + searchRadius; z += candidateStep) {
				Site candidate = score(generator, randomState, level, seaLevel, x, z);
				if (candidate != null && (best == null || candidate.score() > best.score())) {
					best = candidate;
				}
			}
		}
		if (best == null) {
			throw new AssertionError("No inland benchmark site within " + searchRadius
				+ " blocks of " + originX + "," + originZ + "; the search area is all ocean");
		}
		return best;
	}

	private static Site score(final ChunkGenerator generator, final RandomState randomState,
			final ServerLevel level, final int seaLevel, final int centerX, final int centerZ) {
		int samples = 0;
		long total = 0L;
		long totalSquares = 0L;
		int minimum = Integer.MAX_VALUE;

		for (int dx = -SAMPLE_RADIUS; dx <= SAMPLE_RADIUS; dx += SAMPLE_STEP) {
			for (int dz = -SAMPLE_RADIUS; dz <= SAMPLE_RADIUS; dz += SAMPLE_STEP) {
				int x = centerX + dx;
				int z = centerZ + dz;
				// OCEAN_FLOOR_WG is the solid surface, so water columns report the seabed and fail
				// the freeboard test below instead of masquerading as flat land.
				int height = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
				if (height < seaLevel + MINIMUM_FREEBOARD) {
					return null;
				}
				samples++;
				total += height;
				totalSquares += (long)height * height;
				minimum = Math.min(minimum, height);
			}
		}

		double mean = (double)total / samples;
		double variance = Math.max(0.0, (double)totalSquares / samples - mean * mean);
		double roughness = Math.sqrt(variance);
		// Rough terrain fills the frame with geometry; elevation above sea level adds draw distance
		// without the flat water surface that makes an ocean view cheap.
		double score = roughness + 0.25 * (mean - seaLevel);

		int groundY = generator.getBaseHeight(centerX, centerZ, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
		float yaw = openViewYaw(generator, randomState, level, centerX, centerZ, groundY);
		return new Site(centerX, groundY, centerZ, yaw, roughness, minimum, score);
	}

	/**
	 * Chooses the heading with the least obstructed view.
	 *
	 * <p>Aiming at the highest nearby terrain buries the camera in a hillside: an earlier run stood
	 * on a snow peak facing a wall of blocks two metres away, so the 32-chunk render distance drew
	 * almost nothing. This instead walks the surface outward along each candidate heading and takes
	 * the one whose terrain rises least above the camera, which from high ground points down the
	 * slope and gives the long view that actually exercises the render distance.
	 */
	private static float openViewYaw(final ChunkGenerator generator, final RandomState randomState,
			final ServerLevel level, final int centerX, final int centerZ, final int cameraY) {
		int headings = 16;
		float bestYaw = 0.0F;
		double bestElevation = Double.MAX_VALUE;
		for (int heading = 0; heading < headings; heading++) {
			double radians = 2.0 * Math.PI * heading / headings;
			double stepX = -Math.sin(radians);
			double stepZ = Math.cos(radians);
			double highestElevation = -Math.PI;
			for (int distance = 8; distance <= VIEW_PROBE_DISTANCE; distance += 8) {
				int x = centerX + (int)Math.round(stepX * distance);
				int z = centerZ + (int)Math.round(stepZ * distance);
				int height = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
				highestElevation = Math.max(highestElevation, Math.atan2(height - cameraY, distance));
			}
			if (highestElevation < bestElevation) {
				bestElevation = highestElevation;
				// Minecraft yaw: 0 faces +Z, and yaw increases clockwise when viewed from above.
				bestYaw = (float)Math.toDegrees(radians);
			}
		}
		return bestYaw;
	}

	/**
	 * @param groundY surface height at the site
	 * @param yaw heading toward the highest sampled terrain
	 * @param roughness standard deviation of sampled surface heights, in blocks
	 * @param minimumGroundY lowest sampled surface height, used to prove the site is inland
	 */
	public record Site(int x, int groundY, int z, float yaw, double roughness, int minimumGroundY, double score) {
	}
}
