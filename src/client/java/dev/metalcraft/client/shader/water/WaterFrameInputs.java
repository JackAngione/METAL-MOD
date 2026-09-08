package dev.metalcraft.client.shader.water;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * Immutable scene values captured together for one water-rendering frame.
 *
 * <p>The projection is the final raster projection after view bob and screen effects. Device
 * depth is zero-to-one reverse Z: depth 1 reconstructs the near point and depth 0 reconstructs
 * the far point. Missing, non-finite, singular, or forward-Z inputs reject the complete frame;
 * callers must keep the original water path when {@link #create} returns empty.
 *
 * <p>{@link #animationSeconds()} repeats every {@value #ANIMATION_PERIOD_SECONDS} seconds. Water
 * animation consuming it must be periodic across that boundary. Bounding the value keeps useful
 * float precision after a world has run for a long time.
 */
public final class WaterFrameInputs {
	public static final float TICKS_PER_SECOND = 20.0F;
	public static final float ANIMATION_PERIOD_SECONDS = 1024.0F;
	public static final long ANIMATION_PERIOD_TICKS = 20_480L;
	/**
	 * GPU layout in bytes: projection mat4 at 0, inverse projection mat4 at 64,
	 * camera-world high float4 at 128, camera-world residual float4 at 144, animation
	 * seconds float at 160, submerged uint at 164, refraction-enabled uint at 168,
	 * four padding bytes at 172,
	 * sun direction/energy float4 at 176, linear sky RGB/validity float4 at 192.
	 */
	public static final int UNIFORM_BYTES = 208;

	private static final float MIN_HOMOGENEOUS_W = 1.0e-7F;

	private final Matrix4f projection;
	private final Matrix4f inverseProjection;
	private final double cameraX;
	private final double cameraY;
	private final double cameraZ;
	private final float animationSeconds;
	private final boolean cameraSubmerged;
	private final boolean refractionEnabled;
	private float sunX, sunY, sunEnergy, environmentR, environmentG, environmentB, normalSky;

	private WaterFrameInputs(final Matrix4f projection, final Matrix4f inverseProjection,
		final Vector3dc cameraWorldPosition, final float animationSeconds, final boolean cameraSubmerged,
		final boolean refractionEnabled) {
		this.projection = projection;
		this.inverseProjection = inverseProjection;
		this.cameraX = cameraWorldPosition.x();
		this.cameraY = cameraWorldPosition.y();
		this.cameraZ = cameraWorldPosition.z();
		this.animationSeconds = animationSeconds;
		this.cameraSubmerged = cameraSubmerged;
		this.refractionEnabled = refractionEnabled;
	}

	/**
	 * Captures validated copies of one extracted frame. Partial tick accepts the renderer's closed
	 * interval [0, 1]; exactly 1 advances to the next bounded tick.
	 */
	public static Optional<WaterFrameInputs> create(final @Nullable Matrix4fc projection,
		final @Nullable Vector3dc cameraWorldPosition, final long gameTimeTicks,
		final float partialTick, final boolean cameraSubmerged) {
		if (projection == null || cameraWorldPosition == null || !cameraWorldPosition.isFinite()
			|| !hasFiniteSplit(cameraWorldPosition.x()) || !hasFiniteSplit(cameraWorldPosition.y())
			|| !hasFiniteSplit(cameraWorldPosition.z())
			|| !Float.isFinite(partialTick) || partialTick < 0.0F || partialTick > 1.0F) {
			return Optional.empty();
		}

		Matrix4f projectionCopy = new Matrix4f(projection);
		if (!projectionCopy.isFinite()) return Optional.empty();
		float determinant = projectionCopy.determinant();
		if (!Float.isFinite(determinant) || determinant == 0.0F) return Optional.empty();

		Matrix4f inverse = new Matrix4f(projectionCopy).invert();
		if (!inverse.isFinite() || !isReverseZ(inverse)) return Optional.empty();

		long boundedTicks = Math.floorMod(gameTimeTicks, ANIMATION_PERIOD_TICKS);
		double seconds = (boundedTicks + (double)partialTick) / TICKS_PER_SECOND;
		if (seconds >= ANIMATION_PERIOD_SECONDS) seconds -= ANIMATION_PERIOD_SECONDS;
		return Optional.of(new WaterFrameInputs(projectionCopy, inverse, cameraWorldPosition,
			(float)seconds, cameraSubmerged, false));
	}

	/** Copies extracted sky values; unavailable/nonstandard skies have no invented sun or sky. */
	public WaterFrameInputs withSky(final net.minecraft.client.renderer.state.level.SkyRenderState sky) {
		WaterFrameInputs copy = new WaterFrameInputs(new Matrix4f(this.projection),
			new Matrix4f(this.inverseProjection), this.cameraWorldPosition(), this.animationSeconds,
			this.cameraSubmerged, this.refractionEnabled);
		if (sky.skybox != net.minecraft.world.level.dimension.DimensionType.Skybox.OVERWORLD
			|| !Float.isFinite(sky.sunAngle) || !Float.isFinite(sky.rainBrightness)) return copy;
		copy.normalSky = 1.0F;
		copy.sunX = -(float)Math.sin(sky.sunAngle);
		copy.sunY = (float)Math.cos(sky.sunAngle);
		float daylight = Math.clamp(copy.sunY * 4.0F, 0.0F, 1.0F);
		copy.sunEnergy = daylight * Math.clamp(sky.rainBrightness, 0.0F, 1.0F);
		float environmentScale = 0.02F + 0.98F * daylight;
		copy.environmentR = dev.metalcraft.client.shader.SceneColor.srgbToLinear(((sky.skyColor >>> 16) & 255) / 255.0F) * environmentScale;
		copy.environmentG = dev.metalcraft.client.shader.SceneColor.srgbToLinear(((sky.skyColor >>> 8) & 255) / 255.0F) * environmentScale;
		copy.environmentB = dev.metalcraft.client.shader.SceneColor.srgbToLinear((sky.skyColor & 255) / 255.0F) * environmentScale;
		return copy;
	}

	/** Returns a fresh mutable copy; the frame's stored projection cannot escape. */
	public Matrix4f projection() {
		return new Matrix4f(this.projection);
	}

	/** Returns a fresh mutable copy; the frame's stored inverse cannot escape. */
	public Matrix4f inverseProjection() {
		return new Matrix4f(this.inverseProjection);
	}

	/** Retains full world-coordinate precision and returns a fresh value on every call. */
	public Vector3d cameraWorldPosition() {
		return new Vector3d(this.cameraX, this.cameraY, this.cameraZ);
	}

	public float animationSeconds() {
		return this.animationSeconds;
	}

	public boolean cameraSubmerged() {
		return this.cameraSubmerged;
	}

	/** Enables opaque-snapshot replacement only for a composition mode that has proved it safe. */
	public WaterFrameInputs withRefraction(final boolean enabled) {
		WaterFrameInputs copy = new WaterFrameInputs(new Matrix4f(this.projection), new Matrix4f(this.inverseProjection),
			this.cameraWorldPosition(), this.animationSeconds, this.cameraSubmerged, enabled);
		copy.sunX = this.sunX;
		copy.sunY = this.sunY;
		copy.sunEnergy = this.sunEnergy;
		copy.environmentR = this.environmentR;
		copy.environmentG = this.environmentG;
		copy.environmentB = this.environmentB;
		copy.normalSky = this.normalSky;
		return copy;
	}

	public boolean refractionEnabled() {
		return this.refractionEnabled;
	}

	/**
	 * Writes the explicit little-endian GPU layout at the destination's current position and
	 * advances it by {@link #UNIFORM_BYTES}. Camera coordinates use a float high part plus a
	 * float residual. Shaders must keep both parts separate: for periodic world-locked waves,
	 * reduce the high part and camera-relative surface coordinate by the wave period before
	 * adding the residual. Recombining high and residual first loses the residual at large
	 * world coordinates.
	 */
	public void write(final ByteBuffer destination) {
		if (destination.remaining() < UNIFORM_BYTES) {
			throw new IllegalArgumentException("Water frame uniform destination is too small");
		}
		int start = destination.position();
		ByteBuffer bytes = destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		putMatrix(bytes, start, this.projection);
		putMatrix(bytes, start + 64, this.inverseProjection);
		putSplitCoordinate(bytes, start + 128, start + 144, this.cameraX);
		putSplitCoordinate(bytes, start + 132, start + 148, this.cameraY);
		putSplitCoordinate(bytes, start + 136, start + 152, this.cameraZ);
		bytes.putFloat(start + 140, 0.0F);
		bytes.putFloat(start + 156, 0.0F);
		bytes.putFloat(start + 160, this.animationSeconds);
		bytes.putInt(start + 164, this.cameraSubmerged ? 1 : 0);
		bytes.putInt(start + 168, this.refractionEnabled ? 1 : 0);
		bytes.putInt(start + 172, 0);
		bytes.putFloat(start + 176, this.sunX).putFloat(start + 180, this.sunY)
			.putFloat(start + 184, 0.0F).putFloat(start + 188, this.sunEnergy);
		bytes.putFloat(start + 192, this.environmentR).putFloat(start + 196, this.environmentG)
			.putFloat(start + 200, this.environmentB).putFloat(start + 204, this.normalSky);
		destination.position(start + UNIFORM_BYTES);
	}

	private static boolean isReverseZ(final Matrix4fc inverse) {
		Vector4f near = inverse.transform(new Vector4f(0.0F, 0.0F, 1.0F, 1.0F));
		Vector4f far = inverse.transform(new Vector4f(0.0F, 0.0F, 0.0F, 1.0F));
		if (!near.isFinite() || !far.isFinite()
			|| Math.abs(near.w) <= MIN_HOMOGENEOUS_W || Math.abs(far.w) <= MIN_HOMOGENEOUS_W) {
			return false;
		}
		near.div(near.w);
		far.div(far.w);
		double nearDistanceSquared = lengthSquared(near);
		double farDistanceSquared = lengthSquared(far);
		return Double.isFinite(nearDistanceSquared) && Double.isFinite(farDistanceSquared)
			&& farDistanceSquared > nearDistanceSquared;
	}

	private static double lengthSquared(final Vector4f point) {
		return (double)point.x * point.x + (double)point.y * point.y + (double)point.z * point.z;
	}

	private static void putSplitCoordinate(final ByteBuffer bytes, final int highOffset,
		final int residualOffset, final double coordinate) {
		float high = (float)coordinate;
		bytes.putFloat(highOffset, high);
		bytes.putFloat(residualOffset, (float)(coordinate - (double)high));
	}

	private static boolean hasFiniteSplit(final double coordinate) {
		float high = (float)coordinate;
		return Float.isFinite(high) && Float.isFinite((float)(coordinate - (double)high));
	}

	private static void putMatrix(final ByteBuffer bytes, final int offset, final Matrix4fc matrix) {
		bytes.putFloat(offset, matrix.m00()).putFloat(offset + 4, matrix.m01())
			.putFloat(offset + 8, matrix.m02()).putFloat(offset + 12, matrix.m03())
			.putFloat(offset + 16, matrix.m10()).putFloat(offset + 20, matrix.m11())
			.putFloat(offset + 24, matrix.m12()).putFloat(offset + 28, matrix.m13())
			.putFloat(offset + 32, matrix.m20()).putFloat(offset + 36, matrix.m21())
			.putFloat(offset + 40, matrix.m22()).putFloat(offset + 44, matrix.m23())
			.putFloat(offset + 48, matrix.m30()).putFloat(offset + 52, matrix.m31())
			.putFloat(offset + 56, matrix.m32()).putFloat(offset + 60, matrix.m33());
	}
}
