package dev.metalcraft.client.metal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import net.minecraft.resources.Identifier;

/** Source-verified Minecraft 26.2 post shaders whose inputs and output are linear world color. */
public final class LinearWorldPostShaders {
	public enum Semantic {
		/** Depth-sorts and composites the five premultiplied Fabulous layers over main color. */
		FABULOUS_TRANSPARENCY("post/transparency", "767612c69f6a4e8f70e7ac92863450d3029083258870c128788129cb67cc3a7e"),
		/** Copies an already-linear post target, preserving its values except for explicit modulation. */
		LINEAR_COPY("post/blit", "55778eedf1f8970fd47ffc70807cda66f1228c11bcc760ba62937abc8e95be48");

		private final String fragmentPath;
		private final String fragmentFingerprint;

		Semantic(final String fragmentPath, final String fragmentFingerprint) {
			this.fragmentPath = fragmentPath;
			this.fragmentFingerprint = fragmentFingerprint;
		}
	}

	private static final String SCREENQUAD_PATH = "core/screenquad";
	private static final String SCREENQUAD_FINGERPRINT = "5178da332de99d1bdde19a91b8bda36ec9130fa87c9ab22297e26f69a605ed5f";

	private LinearWorldPostShaders() { }

	static String vertex(final Semantic semantic, final Identifier id, final String source) {
		if (semantic == null) throw new NullPointerException("semantic");
		verify(id, SCREENQUAD_PATH, ".vsh", SCREENQUAD_FINGERPRINT, source);
		return source;
	}

	static String fragment(final Semantic semantic, final Identifier id, final String source) {
		if (semantic == null) throw new NullPointerException("semantic");
		verify(id, semantic.fragmentPath, ".fsh", semantic.fragmentFingerprint, source);
		// Both contracts consume linear textures and already preserve HDR values. Decoding here
		// would corrupt Fabulous premultiplied RGB and the subsequent copy.
		return source;
	}

	private static void verify(final Identifier id, final String path, final String suffix,
		final String expectedFingerprint, final String source) {
		if (!id.getNamespace().equals("minecraft") || !id.getPath().equals(path)
			|| !expectedFingerprint.equals(fingerprint(source))) {
			throw new IllegalArgumentException("Unsupported linear world post shader source: " + id + suffix);
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
}
