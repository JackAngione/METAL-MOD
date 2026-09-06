package dev.metalcraft.client.metal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** Explicit Minecraft 26.2 compatibility variants. Unknown/resource-replaced sources fail closed. */
final class LinearWorldShaders {
	private LinearWorldShaders() { }

	// SHA-256 of expanded vanilla GLSL after removing comments/source locations and normalizing spacing.
	// Preserve token spacing and preprocessor line boundaries; ++ and + + must differ.
	// Keep preprocessor branches in the fingerprint; defines are injected only after verification.
	private static final Map<String, String> SOURCES = Map.ofEntries(
		Map.entry("core/terrain.vsh", "23c28fd76f9021495cfba280655300df16a6c91ed7ba0ba6e11f709ddb14726f"),
		Map.entry("core/terrain.fsh", "093f878011057b1617a154fea15aa312a3f615117d5da46d20662ecc2f8253e3"),
		Map.entry("core/block.vsh", "fced4536c6f9b67ab29d6c75b3d9f0f8837c400d824cf4b0d26e24c74b8991ef"),
		Map.entry("core/block.fsh", "bbd35129dde1bcfce0387702f4d6e678e6a4820d4d4ea5f83074f5e6d65a49dc"),
		Map.entry("core/entity.vsh", "c532d1a5d3f9afddb6f9a706c477497533dd13457ee359b05f0ed58eb28b38a7"),
		Map.entry("core/entity.fsh", "00ac44ca37b345b871773dbb4c615c77c9427d64d2c257b512f6a9fd1c0d03c7"),
		Map.entry("core/particle.vsh", "ef6f0ca7b3b20ec414d19d38180de66c2277ea4d1ed3f85f78f909614a0f2e42"),
		Map.entry("core/particle.fsh", "142e6e8f073f63c5d70c9347eb63f0b0082291d6843cdae1e341ddc6599a8710"),
		Map.entry("core/rendertype_clouds.vsh", "e3da9c0feb9c6739d9724a12d8a20859c9271222f3fb0c02df3a82f77619d5b2"),
		Map.entry("core/rendertype_clouds.fsh", "aa3ec839df78a5f2ec1a54a1942b00f4c16dfb09182384804455fb90c7610f21")
	);

	static void verify(final Identifier id, final String suffix, final String source) {
		String expected = id.getNamespace().equals("minecraft") ? SOURCES.get(id.getPath() + suffix) : null;
		if (expected == null || !expected.equals(fingerprint(source))) {
			throw new IllegalArgumentException("Unsupported linear world shader source: " + id + suffix);
		}
	}

	private static String fingerprint(final String source) {
		String canonical = source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", " ")
			.replaceAll("(?m)^\\s*#(?:version|line)[^\\r\\n]*", "")
			.replaceAll("(?m)(^[\\t ]*#[^\\r\\n]*)(?:\\r?\\n|$)", "$1\001")
			.replaceAll("\\s+", " ").strip();
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException error) {
			throw new AssertionError(error);
		}
	}

	private static String replaceOnce(final String source, final String from, final String to) {
		int start = source.indexOf(from);
		if (start < 0 || source.indexOf(from, start + from.length()) >= 0) {
			throw new IllegalArgumentException("Unsupported linear shader layout: " + from);
		}
		return source.substring(0, start) + to + source.substring(start + from.length());
	}

	static String fragment(final Identifier id, final String source) {
		verify(id, ".fsh", source);
		String adapted = source;
		if (id.getPath().equals("core/terrain")) {
			adapted = replaceOnce(adapted, "color = mix(FogColor * vec4(1, 1, 1, color.a), color, ChunkVisibility);",
				"color = mix(mc_linear_seed(FogColor) * vec4(1, 1, 1, color.a), mc_linear_seed(color), ChunkVisibility);");
		} else if (id.getPath().equals("core/rendertype_clouds")) {
			adapted = replaceOnce(adapted, "vec4 color = vertexColor;", "vec4 color = mc_linear_seed(vertexColor);");
		} else {
			adapted = replaceOnce(adapted, "fragColor = apply_fog(color,", "fragColor = apply_fog(mc_linear_seed(color),");
		}
		adapted = replaceOnce(adapted, "mix(inColor.rgb, fogColor.rgb,", "mix(inColor.rgb, mc_linear_seed(fogColor).rgb,");
		// Place helpers after #version and before expanded fog/other declarations.
		int line = adapted.indexOf('\n');
		return adapted.substring(0, line + 1) + """
			vec4 mc_linear_seed(vec4 encoded) {
				vec3 c = max(encoded.rgb, vec3(0.0));
				return vec4(mix(pow((c + 0.055) / 1.055, vec3(2.4)), c / 12.92,
					lessThanEqual(c, vec3(0.04045))), encoded.a);
			}
			""" + adapted.substring(line + 1);
	}
}
