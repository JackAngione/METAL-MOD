package dev.metalcraft.client.metal;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.renderer.PostChainConfig;

/** Resource graph mutations must not inherit an approved shader's color contract. */
final class LinearWorldTransparencyConfigSmoke {
	static void run() {
		try (var stream = LinearWorldTransparencyConfigSmoke.class.getClassLoader()
			.getResourceAsStream("assets/minecraft/post_effect/transparency.json")) {
			if (stream == null) throw new AssertionError("Missing vanilla transparency config");
			var original = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
			LinearWorldTransparencyConfig.verify(PostChainConfig.CODEC.parse(JsonOps.INSTANCE, original).getOrThrow());
			for (int mutation = 0; mutation < 8; mutation++) {
				var changed = original.deepCopy();
				var passes = changed.getAsJsonArray("passes");
				var first = passes.get(0).getAsJsonObject();
				var copy = passes.get(1).getAsJsonObject();
				switch (mutation) {
					case 0 -> changed.getAsJsonObject("targets").getAsJsonObject("final").addProperty("persistent", true);
					case 1 -> changed.getAsJsonObject("targets").getAsJsonObject("final").addProperty("width", 1);
					case 2 -> first.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("target", "minecraft:entity_outline");
					case 3 -> first.getAsJsonArray("inputs").get(1).getAsJsonObject().addProperty("use_depth_buffer", false);
					case 4 -> copy.getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("target", "minecraft:main");
					case 5 -> copy.getAsJsonObject("uniforms").getAsJsonArray("BlitConfig").get(0).getAsJsonObject()
						.getAsJsonArray("value").set(0, new com.google.gson.JsonPrimitive(0.5));
					case 6 -> passes.add(copy.deepCopy());
					case 7 -> first.addProperty("fragment_shader", "other:post/transparency");
				}
				var config = PostChainConfig.CODEC.parse(JsonOps.INSTANCE, changed).getOrThrow();
				try {
					LinearWorldTransparencyConfig.verify(config);
					throw new AssertionError("Accepted changed transparency graph: " + mutation);
				} catch (IllegalArgumentException expected) { }
			}
		} catch (java.io.IOException error) {
			throw new AssertionError(error);
		}
		System.out.println("Linear transparency config: vanilla graph accepted; 8 resource graph mutations rejected");
	}
}
