package dev.metalcraft.client.metal;

import dev.metalcraft.api.MetalCraftLightRegistry;
import dev.metalcraft.api.MetalCraftLocalLight;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

/** Builds the bounded, camera-relative local-light snapshot consumed by one rendered frame. */
public final class MetalWorldLighting {
	public static final int MAX_LIGHTS = 256;
	private static final Comparator<RankedLight> STRONGEST_FIRST = Comparator
		.comparingDouble(RankedLight::impact).reversed()
		.thenComparing(light -> light.id().providerId().toString())
		.thenComparingLong(light -> light.id().stableId());

	private final MetalCraftLightRegistry registry;
	private volatile Snapshot current = Snapshot.EMPTY;

	public MetalWorldLighting(final MetalCraftLightRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry");
	}

	/** Collects, culls, ranks, and publishes all registered visual lights for this camera. */
	public Snapshot publish(final CameraRenderState camera) {
		Objects.requireNonNull(camera, "camera");
		Matrix4f viewProjection = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);
		FrustumIntersection frustum = new FrustumIntersection(viewProjection, true);
		List<RankedLight> ranked = new ArrayList<>();
		for (MetalCraftLightRegistry.RegisteredLight registered : this.registry.collect()) {
			MetalCraftLocalLight light = registered.light();
			float relativeX = (float)(light.position().x - camera.pos.x);
			float relativeY = (float)(light.position().y - camera.pos.y);
			float relativeZ = (float)(light.position().z - camera.pos.z);
			if (!frustum.testSphere(relativeX, relativeY, relativeZ, light.radius())) {
				continue;
			}
			LightId id = new LightId(registered.providerId(), light.stableId());
			double distanceSquared = (double)relativeX * relativeX
				+ (double)relativeY * relativeY + (double)relativeZ * relativeZ;
			double luminance = 0.2126 * light.red() + 0.7152 * light.green() + 0.0722 * light.blue();
			double impact = luminance * light.intensity() * light.radius() * light.radius()
				/ Math.max(distanceSquared, 1.0);
			ranked.add(new RankedLight(
				id, relativeX, relativeY, relativeZ, light.red(), light.green(), light.blue(),
				light.intensity(), light.radius(), light.shadowEligible(), impact
			));
		}
		ranked.sort(STRONGEST_FIRST);
		int retained = Math.min(MAX_LIGHTS, ranked.size());
		List<FrameLight> lights = new ArrayList<>(retained);
		for (int index = 0; index < retained; index++) {
			RankedLight light = ranked.get(index);
			lights.add(new FrameLight(
				light.id(), light.cameraX(), light.cameraY(), light.cameraZ(),
				light.red(), light.green(), light.blue(), light.intensity(), light.radius(),
				light.shadowEligible(), false
			));
		}
		Snapshot published = new Snapshot(lights, ranked.size() - retained);
		this.current = published;
		return published;
	}

	public Snapshot snapshot() {
		return this.current;
	}

	public record LightId(Identifier providerId, long stableId) {
		public LightId {
			Objects.requireNonNull(providerId, "providerId");
		}
	}

	public record FrameLight(
		LightId id,
		float cameraX,
		float cameraY,
		float cameraZ,
		float red,
		float green,
		float blue,
		float intensity,
		float radius,
		boolean shadowEligible,
		boolean usesBlockLightEnvelope
	) {
		public FrameLight {
			Objects.requireNonNull(id, "id");
		}
	}

	public record Snapshot(List<FrameLight> lights, int overflowCount) {
		private static final Snapshot EMPTY = new Snapshot(List.of(), 0);

		public Snapshot {
			lights = List.copyOf(lights);
			if (overflowCount < 0) {
				throw new IllegalArgumentException("overflowCount must not be negative");
			}
		}
	}

	private record RankedLight(
		LightId id,
		float cameraX,
		float cameraY,
		float cameraZ,
		float red,
		float green,
		float blue,
		float intensity,
		float radius,
		boolean shadowEligible,
		double impact
	) {
	}
}
