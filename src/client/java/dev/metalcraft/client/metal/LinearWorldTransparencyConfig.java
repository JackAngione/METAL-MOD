package dev.metalcraft.client.metal;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.UniformValue;
import net.minecraft.resources.Identifier;
import org.joml.Vector4f;

/** Validates the vanilla Fabulous graph separately from shader source verification. */
public final class LinearWorldTransparencyConfig {
	private LinearWorldTransparencyConfig() { }

	/**
	 * Checks the currently loaded configuration, not a cached resource name. Future activation
	 * must additionally verify its actual pipelines and promote every scene target to HDR.
	 * This is not an activation token: callers must recheck after any configuration change.
	 */
	public static void verify(final PostChainConfig config) {
		var expectedTarget = new PostChainConfig.InternalTarget(Optional.empty(), Optional.empty(), false, 0);
		if (!config.internalTargets().equals(Map.of(id("final"), expectedTarget)) || config.passes().size() != 2) {
			throw unsupported();
		}
		var composition = config.passes().getFirst();
		var inputs = List.of(input("Main", "main", false), input("MainDepth", "main", true),
			input("Translucent", "translucent", false), input("TranslucentDepth", "translucent", true),
			input("ItemEntity", "item_entity", false), input("ItemEntityDepth", "item_entity", true),
			input("Particles", "particles", false), input("ParticlesDepth", "particles", true),
			input("Clouds", "clouds", false), input("CloudsDepth", "clouds", true),
			input("Weather", "weather", false), input("WeatherDepth", "weather", true));
		if (!composition.vertexShaderId().equals(id("core/screenquad"))
			|| !composition.fragmentShaderId().equals(id("post/transparency"))
			|| !composition.outputTarget().equals(id("final"))
			|| !composition.inputs().equals(inputs) || !composition.uniforms().isEmpty()) {
			throw unsupported();
		}
		var copy = config.passes().getLast();
		if (!copy.vertexShaderId().equals(id("core/screenquad"))
			|| !copy.fragmentShaderId().equals(id("post/blit"))
			|| !copy.outputTarget().equals(id("main"))
			|| !copy.inputs().equals(List.of(input("In", "final", false)))
			|| !copy.uniforms().equals(Map.of("BlitConfig", List.of(new UniformValue.Vec4Uniform(new Vector4f(1)))))) {
			throw unsupported();
		}
	}

	private static PostChainConfig.TargetInput input(String sampler, String target, boolean depth) {
		return new PostChainConfig.TargetInput(sampler, id(target), depth, false);
	}

	private static Identifier id(String path) { return Identifier.withDefaultNamespace(path); }

	private static IllegalArgumentException unsupported() {
		return new IllegalArgumentException("Unsupported linear world transparency configuration");
	}
}
