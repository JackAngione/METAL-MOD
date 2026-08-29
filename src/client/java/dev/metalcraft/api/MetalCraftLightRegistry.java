package dev.metalcraft.api;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/** Owns registered local-light providers and isolates each provider's output from every other one. */
public final class MetalCraftLightRegistry {
	private static final Logger LOGGER = LogUtils.getLogger();
	private final Map<Identifier, MetalCraftLightProvider> providers = new LinkedHashMap<>();
	private final Set<Identifier> failedProviders = new HashSet<>();

	public synchronized void register(final Identifier id, final MetalCraftLightProvider provider) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(provider, "provider");
		if (this.providers.putIfAbsent(id, provider) != null) {
			throw new IllegalArgumentException("A MetalCraft light provider is already registered as " + id);
		}
	}

	/**
	 * Returns one immutable frame collection. A failing provider contributes nothing to that frame,
	 * including values it emitted before throwing; other providers continue normally.
	 */
	public List<RegisteredLight> collect() {
		List<Map.Entry<Identifier, MetalCraftLightProvider>> providerSnapshot;
		synchronized (this) {
			providerSnapshot = List.copyOf(this.providers.entrySet());
		}
		List<RegisteredLight> collected = new ArrayList<>();
		for (Map.Entry<Identifier, MetalCraftLightProvider> entry : providerSnapshot) {
			this.collectProvider(entry.getKey(), entry.getValue(), collected);
		}
		return List.copyOf(collected);
	}

	private void collectProvider(
		final Identifier providerId,
		final MetalCraftLightProvider provider,
		final List<RegisteredLight> destination
	) {
		List<RegisteredLight> providerLights = new ArrayList<>();
		Set<Long> stableIds = new HashSet<>();
		try {
			provider.collectLights(light -> {
				Objects.requireNonNull(light, "A MetalCraft light provider emitted null");
				if (!stableIds.add(light.stableId())) {
					throw new IllegalArgumentException(
						"Light provider '" + providerId + "' emitted duplicate stable ID " + light.stableId()
					);
				}
				providerLights.add(new RegisteredLight(providerId, light));
			});
			destination.addAll(providerLights);
			this.noteRecovered(providerId);
		} catch (RuntimeException error) {
			this.noteFailure(providerId, error);
		}
	}

	private synchronized void noteFailure(final Identifier providerId, final RuntimeException error) {
		if (this.failedProviders.add(providerId)) {
			LOGGER.error("Local-light provider '{}' failed; discarding its frame output", providerId, error);
		}
	}

	private synchronized void noteRecovered(final Identifier providerId) {
		if (this.failedProviders.remove(providerId)) {
			LOGGER.info("Local-light provider '{}' recovered", providerId);
		}
	}

	/** A provider-scoped stable light returned by {@link #collect()}. */
	public record RegisteredLight(Identifier providerId, MetalCraftLocalLight light) {
		public RegisteredLight {
			Objects.requireNonNull(providerId, "providerId");
			Objects.requireNonNull(light, "light");
		}
	}
}
