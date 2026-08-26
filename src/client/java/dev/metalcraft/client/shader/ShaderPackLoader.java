package dev.metalcraft.client.shader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Discovers and loads directory, zip, and bundled shader packs into one representation. */
public final class ShaderPackLoader {
	private static final int MAX_TEXT_BYTES = 32 * 1024 * 1024;

	public enum Kind {
		DIRECTORY,
		ZIP
	}

	public record PackRef(String id, Path path, Kind kind) {
		public PackRef {
			if (id == null || id.isBlank()) {
				throw new IllegalArgumentException("A shader pack requires a non-blank ID");
			}
			path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
			Objects.requireNonNull(kind, "kind");
		}
	}

	/** A checked load failure whose message identifies the malformed pack declaration. */
	public static final class LoadException extends IOException {
		public LoadException(final String message) {
			super(message);
		}

		public LoadException(final String message, final Throwable cause) {
			super(message, cause);
		}
	}

	private ShaderPackLoader() {
	}

	/** Finds immediate child directories containing pack.json and immediate child .zip files. */
	public static List<PackRef> discover(final Path shaderpacksRoot) throws IOException {
		Objects.requireNonNull(shaderpacksRoot, "shaderpacksRoot");
		if (Files.notExists(shaderpacksRoot)) {
			return List.of();
		}
		if (!Files.isDirectory(shaderpacksRoot)) {
			throw new IOException("Shaderpacks root is not a directory: " + shaderpacksRoot);
		}

		List<PackRef> packs = new ArrayList<>();
		try (Stream<Path> children = Files.list(shaderpacksRoot)) {
			children.sorted(Comparator.comparing(path -> path.getFileName().toString())).forEach(path -> {
				String fileName = path.getFileName().toString();
				if (fileName.equals(".cache")) {
					return;
				}
				if (Files.isDirectory(path) && Files.isRegularFile(path.resolve("pack.json"))) {
					packs.add(new PackRef(fileName, path, Kind.DIRECTORY));
				} else if (Files.isRegularFile(path) && fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
					packs.add(new PackRef(fileName.substring(0, fileName.length() - 4), path, Kind.ZIP));
				}
			});
		}
		return List.copyOf(packs);
	}

	public static ShaderPack load(final PackRef pack) throws IOException {
		Objects.requireNonNull(pack, "pack");
		return pack.kind() == Kind.DIRECTORY
			? loadDirectory(pack.id(), pack.path())
			: loadZip(pack.id(), pack.path());
	}

	/** Loads a directory or .zip directly, deriving its stable pack ID from the file name. */
	public static ShaderPack load(final Path path) throws IOException {
		Objects.requireNonNull(path, "path");
		String fileName = Objects.requireNonNull(path.getFileName(), "path file name").toString();
		if (Files.isDirectory(path)) {
			return load(new PackRef(fileName, path, Kind.DIRECTORY));
		}
		if (fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
			return load(new PackRef(fileName.substring(0, fileName.length() - 4), path, Kind.ZIP));
		}
		throw new LoadException("Shader pack must be a directory or .zip: " + path);
	}

	/** Loads a resource directory containing pack.json from an exploded classpath or jar. */
	public static ShaderPack loadBundled(
		final ClassLoader classLoader,
		final String packId,
		final String resourceRoot
	) throws IOException {
		Objects.requireNonNull(classLoader, "classLoader");
		if (packId == null || packId.isBlank()) {
			throw new IllegalArgumentException("A shader pack requires a non-blank ID");
		}
		String root = normalizeResourceRoot(resourceRoot);
		URL manifestUrl = classLoader.getResource(root + "/pack.json");
		if (manifestUrl == null) {
			throw new LoadException("Bundled shader pack '" + packId + "' has no " + root + "/pack.json");
		}

		if (manifestUrl.getProtocol().equals("file")) {
			try {
				return loadDirectory(packId, Path.of(manifestUrl.toURI()).getParent());
			} catch (URISyntaxException error) {
				throw new LoadException("Invalid bundled shader-pack URL " + manifestUrl, error);
			}
		}
		if (manifestUrl.getProtocol().equals("jar")) {
			return loadJar(packId, root, manifestUrl);
		}
		throw new LoadException(
			"Bundled shader pack '" + packId + "' uses unsupported classpath protocol '"
				+ manifestUrl.getProtocol() + "'"
		);
	}

	private static ShaderPack loadDirectory(final String id, final Path root) throws IOException {
		Path manifestPath = root.resolve("pack.json");
		if (!Files.isRegularFile(manifestPath)) {
			throw new LoadException("Shader pack '" + id + "' has no pack.json at " + root);
		}
		ShaderPack.Manifest manifest;
		try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
			manifest = ShaderManifestParser.parse(id, reader);
		}

		Map<String, String> sources = new TreeMap<>();
		try (Stream<Path> files = Files.walk(root)) {
			for (Path file : files.filter(Files::isRegularFile).toList()) {
				Path relative = root.relativize(file);
				if (containsCacheComponent(relative) || !file.getFileName().toString().endsWith(".metal")) {
					continue;
				}
				String logicalPath = relative.toString().replace(file.getFileSystem().getSeparator(), "/");
				sources.put(logicalPath, readText(Files.newInputStream(file), id + "/" + logicalPath));
			}
		}
		return new ShaderPack(id, manifest, sources);
	}

	private static ShaderPack loadZip(final String id, final Path path) throws IOException {
		if (!Files.isRegularFile(path)) {
			throw new LoadException("Shader-pack zip does not exist: " + path);
		}
		try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
			String prefix = findPackPrefix(id, Collections.list(zip.entries()).stream()
				.filter(entry -> !entry.isDirectory())
				.map(ZipEntry::getName)
				.toList());
			ZipEntry manifestEntry = zip.getEntry(prefix + "pack.json");
			ShaderPack.Manifest manifest;
			try (Reader reader = new InputStreamReader(zip.getInputStream(manifestEntry), StandardCharsets.UTF_8)) {
				manifest = ShaderManifestParser.parse(id, reader);
			}

			Map<String, String> sources = new TreeMap<>();
			Enumeration<? extends ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = entries.nextElement();
				String logicalPath = logicalSourcePath(prefix, entry.getName(), entry.isDirectory());
				if (logicalPath == null) {
					continue;
				}
				try (InputStream input = zip.getInputStream(entry)) {
					putSource(sources, logicalPath, readText(input, id + "/" + logicalPath));
				}
			}
			return new ShaderPack(id, manifest, sources);
		}
	}

	private static ShaderPack loadJar(final String id, final String root, final URL manifestUrl) throws IOException {
		JarURLConnection connection = (JarURLConnection)manifestUrl.openConnection();
		connection.setUseCaches(false);
		String prefix = root + "/";
		try (JarFile jar = connection.getJarFile()) {
			JarEntry manifestEntry = jar.getJarEntry(prefix + "pack.json");
			if (manifestEntry == null) {
				throw new LoadException("Bundled shader pack '" + id + "' has no pack.json");
			}
			ShaderPack.Manifest manifest;
			try (Reader reader = new InputStreamReader(jar.getInputStream(manifestEntry), StandardCharsets.UTF_8)) {
				manifest = ShaderManifestParser.parse(id, reader);
			}

			Map<String, String> sources = new TreeMap<>();
			Enumeration<JarEntry> entries = jar.entries();
			while (entries.hasMoreElements()) {
				JarEntry entry = entries.nextElement();
				String logicalPath = logicalSourcePath(prefix, entry.getName(), entry.isDirectory());
				if (logicalPath == null) {
					continue;
				}
				try (InputStream input = jar.getInputStream(entry)) {
					putSource(sources, logicalPath, readText(input, id + "/" + logicalPath));
				}
			}
			return new ShaderPack(id, manifest, sources);
		}
	}

	private static String findPackPrefix(final String id, final List<String> entryNames) throws LoadException {
		if (entryNames.contains("pack.json")) {
			return "";
		}
		List<String> manifests = entryNames.stream()
			.filter(name -> name.endsWith("/pack.json"))
			.sorted()
			.toList();
		if (manifests.isEmpty()) {
			throw new LoadException("Shader-pack zip '" + id + "' has no pack.json");
		}
		if (manifests.size() > 1) {
			throw new LoadException("Shader-pack zip '" + id + "' has multiple pack.json files: " + manifests);
		}
		return manifests.getFirst().substring(0, manifests.getFirst().length() - "pack.json".length());
	}

	private static String logicalSourcePath(final String prefix, final String entryName, final boolean directory)
		throws LoadException {
		if (directory || !entryName.startsWith(prefix) || !entryName.endsWith(".metal")) {
			return null;
		}
		String logicalPath = entryName.substring(prefix.length());
		if (logicalPath.isBlank() || logicalPath.startsWith("/") || hasUnsafeComponent(logicalPath)) {
			throw new LoadException("Unsafe Metal source path '" + entryName + "'");
		}
		return logicalPath;
	}

	private static void putSource(final Map<String, String> sources, final String path, final String source)
		throws LoadException {
		if (sources.putIfAbsent(path, source) != null) {
			throw new LoadException("Duplicate Metal source path '" + path + "'");
		}
	}

	private static String readText(final InputStream input, final String description) throws IOException {
		try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int total = 0;
			int read;
			while ((read = input.read(buffer)) >= 0) {
				total += read;
				if (total > MAX_TEXT_BYTES) {
					throw new LoadException("Shader-pack text file exceeds 32 MiB: " + description);
				}
				output.write(buffer, 0, read);
			}
			return output.toString(StandardCharsets.UTF_8);
		}
	}

	private static boolean containsCacheComponent(final Path path) {
		for (Path component : path) {
			if (component.toString().equals(".cache")) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasUnsafeComponent(final String path) {
		for (String component : path.split("/", -1)) {
			if (component.isEmpty() || component.equals(".") || component.equals("..") || component.equals(".cache")) {
				return true;
			}
		}
		return false;
	}

	private static String normalizeResourceRoot(final String resourceRoot) {
		Objects.requireNonNull(resourceRoot, "resourceRoot");
		String root = resourceRoot;
		while (root.startsWith("/")) {
			root = root.substring(1);
		}
		while (root.endsWith("/")) {
			root = root.substring(0, root.length() - 1);
		}
		if (root.isBlank() || hasUnsafeComponent(root)) {
			throw new IllegalArgumentException("Invalid shader-pack resource root '" + resourceRoot + "'");
		}
		return root;
	}
}
