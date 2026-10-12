package dev.metalcraft.client.shader;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** CPU-only parity checks for pack discovery, archive loading, and source expansion. */
public final class ShaderPackLoaderSmoke {
	private static final String MANIFEST = """
		{
		  "format": 2,
		  "name": "Loader parity",
		  "includes": ["shared/prelude.metal"],
		  "targets": {},
		  "passes": [{"id": "grade", "kind": "fullscreen", "source": "grade.metal", "writes": ["drawable"]}]
		}
		""";
	private static final Map<String, String> FILES = Map.of(
		"pack.json", MANIFEST,
		"shared/prelude.metal", "// prelude\n",
		"shared/body.metal", "// body: café",
		"grade.metal", "#include <metal_stdlib>\n#include \"shared/body.metal\"\n// grade",
		"ignored.txt", "not a Metal source"
	);

	private ShaderPackLoaderSmoke() {
	}

	public static void main(final String[] arguments) {
		run();
	}

	public static void run() {
		Path root = null;
		try {
			root = Files.createTempDirectory("metalcraft-loader-parity-");
			Path directory = root.resolve("directory");
			for (Map.Entry<String, String> entry : FILES.entrySet()) {
				Path file = directory.resolve(entry.getKey());
				Files.createDirectories(file.getParent());
				Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
			}
			ShaderPack expected = ShaderPackLoader.load(new ShaderPackLoader.PackRef(
				"parity", directory, ShaderPackLoader.Kind.DIRECTORY
			));
			assertSourceExpansion(expected);
			for (String prefix : List.of("", "wrapped/")) {
				Path archive = root.resolve(prefix.isEmpty() ? "root.zip" : "wrapped.zip");
				writeArchive(archive, prefix);
				ShaderPack actual = ShaderPackLoader.load(new ShaderPackLoader.PackRef(
					"parity", archive, ShaderPackLoader.Kind.ZIP
				));
				assertEquivalent(expected, actual);
			}
			try (URLClassLoader loader = new URLClassLoader(new URL[]{root.toUri().toURL()}, null)) {
				assertEquivalent(expected, ShaderPackLoader.loadBundled(loader, "parity", "/directory/"));
			}
			Path jar = root.resolve("bundled.jar");
			writeArchive(jar, "assets/test/shaderpack/");
			try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, null)) {
				assertEquivalent(expected, ShaderPackLoader.loadBundled(loader, "parity", "assets/test/shaderpack"));
			}
			List<String> discovered = ShaderPackLoader.discover(root).stream().map(ShaderPackLoader.PackRef::id).toList();
			if (!discovered.equals(List.of("directory", "root", "wrapped"))) {
				throw new AssertionError("Unexpected pack discovery order: " + discovered);
			}
		} catch (IOException error) {
			throw new AssertionError("Shader pack loader parity failed", error);
		} finally {
			if (root != null) {
				deleteTree(root);
			}
		}
		System.out.println("Shader pack loader: directory, ZIP, classpath, JAR and include parity passed");
	}

	private static void assertEquivalent(final ShaderPack expected, final ShaderPack actual) throws IOException {
		if (!expected.equals(actual)) {
			throw new AssertionError("Pack contents changed between loading formats");
		}
		if (!List.copyOf(actual.metalSources().keySet()).equals(
			List.of("grade.metal", "shared/body.metal", "shared/prelude.metal")
		)) {
			throw new AssertionError("Source ordering is not stable");
		}
		assertSourceExpansion(actual);
	}

	private static void assertSourceExpansion(final ShaderPack pack) throws IOException {
		String source = ShaderPackLoader.expandPassSource(pack, pack.manifest().passes().getFirst());
		String expected = "// prelude\n\n#include <metal_stdlib>\n// body: café\n// grade\n";
		if (!expected.equals(source)) {
			throw new AssertionError("Include order, UTF-8 text, or source line endings changed: " + source);
		}
	}

	private static void writeArchive(final Path path, final String prefix) throws IOException {
		try (ZipOutputStream archive = new ZipOutputStream(Files.newOutputStream(path))) {
			for (Map.Entry<String, String> entry : FILES.entrySet()) {
				archive.putNextEntry(new ZipEntry(prefix + entry.getKey()));
				archive.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				archive.closeEntry();
			}
			if (!prefix.isEmpty()) {
				archive.putNextEntry(new ZipEntry("unrelated.metal"));
				archive.write("// outside the pack".getBytes(StandardCharsets.UTF_8));
				archive.closeEntry();
			}
		}
	}

	private static void deleteTree(final Path root) {
		try (var files = Files.walk(root)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		} catch (IOException error) {
			throw new AssertionError("Could not remove shader pack loader fixture", error);
		}
	}
}
