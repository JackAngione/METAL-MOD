package dev.metalcraft.client.metal;

import com.mojang.logging.LogUtils;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;

/** A content-addressed persistent cache of Metal render and compute pipeline states. */
public final class MetalPipelineCache {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int CACHE_FORMAT = 1;
	private static final String ARCHIVE_FILE = "pipelines.bin";

	private final MetalDevice device;
	private final Path root;
	private int compilationCount;

	public MetalPipelineCache(final MetalDevice device, final Path root) {
		if (device == null || root == null) {
			throw new NullPointerException("Metal pipeline cache device and root cannot be null");
		}
		this.device = device;
		this.root = root.toAbsolutePath().normalize();
	}

	public MetalRenderPipeline createRenderPipeline(final MetalRenderPipeline.Descriptor descriptor) {
		Path archive = this.archivePath(renderHash(descriptor));
		boolean warm = Files.isRegularFile(archive);
		this.prepare(archive, warm);
		try {
			MetalRenderPipeline pipeline = this.device.createRenderPipeline(descriptor, archive, warm);
			if (!warm) {
				this.compilationCount++;
			}
			return pipeline;
		} catch (IllegalStateException error) {
			if (!warm) {
				throw error;
			}
			this.discardInvalidArchive(archive, error);
			MetalRenderPipeline pipeline = this.device.createRenderPipeline(descriptor, archive, false);
			this.compilationCount++;
			return pipeline;
		}
	}

	public MetalComputePipeline createComputePipeline(final MetalComputePipeline.Descriptor descriptor) {
		Path archive = this.archivePath(computeHash(descriptor));
		boolean warm = Files.isRegularFile(archive);
		this.prepare(archive, warm);
		try {
			MetalComputePipeline pipeline = this.device.createComputePipeline(descriptor, archive, warm);
			if (!warm) {
				this.compilationCount++;
			}
			return pipeline;
		} catch (IllegalStateException error) {
			if (!warm) {
				throw error;
			}
			this.discardInvalidArchive(archive, error);
			MetalComputePipeline pipeline = this.device.createComputePipeline(descriptor, archive, false);
			this.compilationCount++;
			return pipeline;
		}
	}

	/** Pipeline states compiled because no matching persistent archive existed in this process. */
	public int compilationCount() {
		return this.compilationCount;
	}

	public Path root() {
		return this.root;
	}

	static String renderHash(final MetalRenderPipeline.Descriptor descriptor) {
		return hash(output -> {
			output.writeUTF("render");
			writeString(output, descriptor.vertexSource());
			writeString(output, descriptor.vertexFunction());
			writeString(output, descriptor.fragmentSource());
			writeString(output, descriptor.fragmentFunction());
			output.writeInt(descriptor.colorTargets().size());
			for (MetalRenderPipeline.ColorTarget target : descriptor.colorTargets()) {
				output.writeInt(target.format() == null ? -1 : target.format().ordinal());
				output.writeInt(target.writeMask());
				MetalRenderPipeline.BlendState blend = target.blendState();
				output.writeBoolean(blend != null);
				if (blend != null) {
					output.writeInt(blend.sourceColor().ordinal());
					output.writeInt(blend.destinationColor().ordinal());
					output.writeInt(blend.colorOperation().ordinal());
					output.writeInt(blend.sourceAlpha().ordinal());
					output.writeInt(blend.destinationAlpha().ordinal());
					output.writeInt(blend.alphaOperation().ordinal());
				}
			}
			output.writeInt(descriptor.depthStencilFormat() == null ? -1 : descriptor.depthStencilFormat().ordinal());
			MetalRenderPipeline.DepthState depth = descriptor.depthState();
			output.writeBoolean(depth.testEnabled());
			output.writeBoolean(depth.writeEnabled());
			output.writeInt(depth.compareFunction().ordinal());
			output.writeFloat(depth.biasSlopeScale());
			output.writeFloat(depth.biasConstant());
			MetalRenderPipeline.RasterState raster = descriptor.rasterState();
			output.writeInt(raster.cullMode().ordinal());
			output.writeInt(raster.fillMode().ordinal());
			output.writeInt(raster.topologyClass().ordinal());
			output.writeInt(descriptor.vertexDescriptor().attributes().size());
			for (MetalRenderPipeline.VertexAttribute attribute : descriptor.vertexDescriptor().attributes()) {
				output.writeInt(attribute.location());
				output.writeInt(attribute.bufferIndex());
				output.writeInt(attribute.offset());
				output.writeInt(attribute.format().ordinal());
			}
			output.writeInt(descriptor.vertexDescriptor().layouts().size());
			for (MetalRenderPipeline.VertexBufferLayout layout : descriptor.vertexDescriptor().layouts()) {
				output.writeInt(layout.bufferIndex());
				output.writeInt(layout.stride());
				output.writeInt(layout.stepRate());
			}
		});
	}

	static String computeHash(final MetalComputePipeline.Descriptor descriptor) {
		return hash(output -> {
			output.writeUTF("compute");
			writeString(output, descriptor.source());
			writeString(output, descriptor.functionName());
		});
	}

	private Path archivePath(final String hash) {
		return this.root.resolve(hash).resolve(ARCHIVE_FILE);
	}

	private void prepare(final Path archive, final boolean warm) {
		if (warm) {
			return;
		}
		try {
			Files.createDirectories(archive.getParent());
		} catch (IOException error) {
			throw new IllegalStateException("Could not create Metal pipeline cache directory " + archive.getParent(), error);
		}
	}

	private void discardInvalidArchive(final Path archive, final IllegalStateException original) {
		try {
			LOGGER.warn("Discarding invalid Metal pipeline archive {} and rebuilding it: {}", archive, original.getMessage());
			Files.deleteIfExists(archive);
		} catch (IOException error) {
			original.addSuppressed(error);
			throw original;
		}
	}

	private static String hash(final HashWriter writer) {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes)) {
				output.writeInt(CACHE_FORMAT);
				writer.write(output);
			}
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
		} catch (IOException error) {
			throw new AssertionError("Writing a pipeline hash to memory failed", error);
		} catch (NoSuchAlgorithmException error) {
			throw new AssertionError("Java does not provide SHA-256", error);
		}
	}

	private static void writeString(final DataOutputStream output, final String value) throws IOException {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		output.writeInt(bytes.length);
		output.write(bytes);
	}

	@FunctionalInterface
	private interface HashWriter {
		void write(DataOutputStream output) throws IOException;
	}
}
