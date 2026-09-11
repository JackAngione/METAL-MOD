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
		Map.entry("core/rendertype_clouds.fsh", "aa3ec839df78a5f2ec1a54a1942b00f4c16dfb09182384804455fb90c7610f21"),
		Map.entry("core/glint.vsh", "2f9c0e2a8e8cbdefbf333ddfd80d37a081734dea60ecfb9b26502edf5e8e0e67"),
		Map.entry("core/glint.fsh", "037e34f02dffb90301559ef5267df24b9fbdf37f0b8d32324558cd99d348789e"),
		Map.entry("core/position.vsh", "eb027fdc03280b71901affda98190b8ffefc6c09a0564abe2f0e8abd3b814168"),
		Map.entry("core/position.fsh", "5f8a5ab70e5e962866909f9df13e65bdbb1b11bebc1c7048a5d513b536c869ae"),
		Map.entry("core/sky.vsh", "eb027fdc03280b71901affda98190b8ffefc6c09a0564abe2f0e8abd3b814168"),
		Map.entry("core/sky.fsh", "3f31faf09ae7fb0c1d9fbcb38e477046d694111fd360fb7c4031619a85f74318"),
		Map.entry("core/stars.vsh", "82453c13cf2624e65438204d358833c26740f7f5a6b59df3d3d9bcfce2b9cf5e"),
		Map.entry("core/stars.fsh", "6e07bf972a251b2a3759b20536c18d537211af5633969166647ceb7982a1a96a"),
		Map.entry("core/position_color.vsh", "1f586ff0f8313541fd67e14c5ac1987c3a55a9821b7a5607bc1ad508c9eaaf62"),
		Map.entry("core/position_color.fsh", "5f8c2bd465497b120f88e01441ce6303f1735493839aeb795725887413105ef9"),
		Map.entry("core/position_tex_color.vsh", "6e92b3af9338d9851cab907ddda3a1ea75efa5737d7659b9f780652d48be1361"),
		Map.entry("core/position_tex_color.fsh", "b4b81e27104fd790f143e92c13e1ed9e7d7dba6544bfeae2998147b97c0cbda5"),
		Map.entry("core/position_tex.vsh", "bcef910aadd2c9b65b4bd52dbc44100a9709d67fa4a203791571137b123dc3fd"),
		Map.entry("core/position_tex.fsh", "10dda447cbdf3267560f70796fa82bf3297a173e60e65dc479485efeaec95594"),
		Map.entry("core/rendertype_lightning.vsh", "305777bdf3bde6acb461ced202f2c53cb19692196461b9e8b3a2e151b3b428eb"),
		Map.entry("core/rendertype_lightning.fsh", "7cb46ac5ac745314cdc6eaeeda988ef5f7dfe83824e4581ea1b200d31f4bee9a"),
		Map.entry("core/rendertype_world_border.vsh", "bc2a78477f6ef39977a05414c63993a0345a5ccf8602838a97d5a835a3c42ef9"),
		Map.entry("core/rendertype_world_border.fsh", "10dda447cbdf3267560f70796fa82bf3297a173e60e65dc479485efeaec95594"),
		Map.entry("core/rendertype_beacon_beam.vsh", "8d018959d1f2de30601dbc91e57dbbd92da5d69f3e3732d64e0729256dfedd59"),
		Map.entry("core/rendertype_beacon_beam.fsh", "d6d2d7ed21531d2c71eec9ac883d2ed389b0104dbaa3be585263e8cedf63e7ac"),
		Map.entry("core/rendertype_crumbling.vsh", "eaf9d050878f4bbaee9da0b1b0e650b053ecf1fa31183813ad9e2d9196de1f31"),
		Map.entry("core/rendertype_crumbling.fsh", "b464ded51914f061a72d9186486d5ba3bd9c24f24a39ffec5dcb6fefb2a1aad5"),
		Map.entry("core/rendertype_entity_shadow.vsh", "a3f683c4767b47695c02183f00883eebddb56e3229f35885f23595ea087de737"),
		Map.entry("core/rendertype_entity_shadow.fsh", "19d10ee6783bed0b5bb5e3374639766bea19e26e41271d356d4eed6167edb114"),
		Map.entry("core/rendertype_lines.vsh", "38c3ee6a1fbeeee35d8ff12bb69a56d3dc596d45cd661e3eb0301c569ddd3d61"),
		Map.entry("core/rendertype_lines.fsh", "b78dabc94abc086b2f945892aa24ef8af3acb9e99d359e4c95b91b551c48af0c"),
		Map.entry("core/rendertype_leash.vsh", "36828049caac7ff1e3049dba712dbf702459578845142dd6dfbb8b3a3da87259"),
		Map.entry("core/rendertype_leash.fsh", "bb99f2ce4fa25b6e24283191d2138b1dfe3482c260734c01542b540cb67e21bf"),
		Map.entry("core/rendertype_end_portal.vsh", "bbf5bd3ff0e8e2b83c7c3636ffd2e7b3bb0ac1ea99cd4bd42054d5648378acb1"),
		Map.entry("core/rendertype_end_portal.fsh", "d311d9f94860ce54fea45cd8211f824aebf9557f85a14a2eafb554c9ed016dff"),
		Map.entry("core/text.vsh", "2a95d848e6a0dde6ee2f97442b0c6014d9da815e320eb1484bff7624f6371b9d"),
		Map.entry("core/text.fsh", "34703a3dbc2403c831073a24c321178d2ca1086e0ddd730446f8709306274df0"),
		Map.entry("core/text_background.vsh", "f141123c9a5576441c859c835c5583cf3ae91e0ea85b7ed3d8d2f28bbc4da806"),
		Map.entry("core/text_background.fsh", "10f15cea0ea4d66f94dfc04ffe72de7253fbc544b20f33d3fbbb8796187c83e3"),
		Map.entry("core/debug_point.vsh", "74f021f482bcfa527f489928ade9c4d250da826654a61cc756a74ef2930e6ff7"),
		Map.entry("core/item.vsh", "bbcb23ac04a149d43789bc9976d258acecbab0b949537dfd4120caf3ac3aeebf"),
		Map.entry("core/item.fsh", "cd2a5586b00a9bcf8157e1222756807eeec4a41adc2b0f28030215e036629f5c")
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
		} else if (id.getPath().equals("core/glint")) {
			// Glint's RGB-only fade is radiometric attenuation, not part of the encoded seed.
			adapted = replaceOnce(adapted, "vec4(color.rgb * fade, color.a)",
				"vec4(mc_linear_seed(color).rgb * fade, color.a)");
		} else if (id.getPath().equals("core/rendertype_lightning")) {
			// Preserve vanilla's attenuation of both RGB and coverage after decoding the seed.
			adapted = replaceOnce(adapted, "fragColor = vertexColor * ColorModulator *",
				"fragColor = mc_linear_seed(vertexColor * ColorModulator) *");
		} else if (id.getPath().equals("core/sky") || id.getPath().equals("core/position")) {
			adapted = replaceOnce(adapted, "fragColor = apply_fog(ColorModulator,",
				"fragColor = apply_fog(mc_linear_seed(ColorModulator),");
		} else if (id.getPath().equals("core/rendertype_leash")) {
			adapted = replaceOnce(adapted, "fragColor = apply_fog(vertexColor,",
				"fragColor = apply_fog(mc_linear_seed(vertexColor),");
		} else if (id.getPath().equals("core/rendertype_end_portal")) {
			// Portal layer accumulation is an artistic compatibility seed, decoded once before fog.
			adapted = replaceOnce(adapted, "fragColor = apply_fog(vec4(color, 1.0),",
				"fragColor = apply_fog(mc_linear_seed(vec4(color, 1.0)),");
		} else if (id.getPath().equals("core/text") || id.getPath().equals("core/text_background")) {
			// Keep all preprocessor branches and their distinct discard/modulator ordering.
			adapted = replaceOnce(adapted, "fragColor = color * ColorModulator;",
				"fragColor = mc_linear_seed(color * ColorModulator);");
			if (id.getPath().equals("core/text")) {
				adapted = replaceOnce(adapted, "fragColor = color;", "fragColor = mc_linear_seed(color);");
			}
			adapted = replaceOnce(adapted, "fragColor = apply_fog(color,", "fragColor = apply_fog(mc_linear_seed(color),");
		} else if (id.getPath().equals("core/stars")) {
			adapted = replaceOnce(adapted, "fragColor = ColorModulator;", "fragColor = mc_linear_seed(ColorModulator);");
		} else if (id.getPath().equals("core/position_color") || id.getPath().equals("core/position_tex_color")
			|| id.getPath().equals("core/position_tex") || id.getPath().equals("core/rendertype_world_border")) {
			adapted = replaceOnce(adapted, "fragColor = color * ColorModulator;",
				"fragColor = mc_linear_seed(color * ColorModulator);");
		} else {
			adapted = replaceOnce(adapted, "fragColor = apply_fog(color,", "fragColor = apply_fog(mc_linear_seed(color),");
		}
		if (adapted.contains("mix(inColor.rgb, fogColor.rgb,")) {
			adapted = replaceOnce(adapted, "mix(inColor.rgb, fogColor.rgb,", "mix(inColor.rgb, mc_linear_seed(fogColor).rgb,");
		}
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
