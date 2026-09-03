package dev.metalcraft.client.metal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.lwjgl.glfw.GLFWNativeCocoa;

/**
 * An owned reference to an {@code MTLDevice}.
 *
 * <p>The device owns every queue, surface, resource, and fence created through it. Closing the
 * device recursively closes children first, and all operations reject use after close.</p>
 */
public final class MetalDevice implements AutoCloseable {
	private final Set<MetalCommandQueue> commandQueues = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<MetalSurface> surfaces = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<MetalBuffer> buffers = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<MetalTexture> textures = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<MetalSampler> samplers = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<MetalFence> fences = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<MetalTimestampQueryPool> timestampQueryPools = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<MetalRenderPipeline> renderPipelines = Collections.newSetFromMap(new IdentityHashMap<>());
	private long handle;
	private boolean closing;

	MetalDevice(final long handle) {
		if (handle == 0L) {
			throw new IllegalArgumentException("A Metal device handle cannot be zero");
		}
		this.handle = handle;
	}

	public synchronized String name() {
		String name = MetalNative.nDeviceName(this.requireOpenHandle());
		return name == null ? "Unnamed Metal device" : name;
	}

	public synchronized long recommendedWorkingSetBytes() {
		return MetalNative.nRecommendedWorkingSet(this.requireOpenHandle());
	}

	public synchronized MetalCommandQueue createCommandQueue() {
		long queueHandle = MetalNative.nCreateCommandQueue(this.requireOpenHandle());
		if (queueHandle == 0L) {
			throw new IllegalStateException("Metal did not return a command queue");
		}
		MetalCommandQueue commandQueue = new MetalCommandQueue(this, queueHandle);
		this.commandQueues.add(commandQueue);
		return commandQueue;
	}

	public synchronized MetalSurface attachToGlfwWindow(final long glfwWindow, final int width, final int height) {
		if (glfwWindow == 0L) {
			throw new IllegalArgumentException("A GLFW window handle cannot be zero");
		}
		long cocoaView = GLFWNativeCocoa.glfwGetCocoaView(glfwWindow);
		if (cocoaView == 0L) {
			throw new IllegalStateException("GLFW did not return a Cocoa view for the window");
		}
		long surfaceHandle = MetalNative.nCreateSurface(this.requireOpenHandle(), cocoaView, width, height);
		if (surfaceHandle == 0L) {
			throw new IllegalStateException("Metal did not create a surface for the GLFW window");
		}
		MetalSurface surface = new MetalSurface(this, surfaceHandle, width, height);
		this.surfaces.add(surface);
		return surface;
	}

	public synchronized MetalBuffer createBuffer(final long size, final MetalBuffer.StorageMode storageMode) {
		long startedNs = MetalStallProbe.begin();
		long bufferHandle = MetalNative.nCreateBuffer(this.requireOpenHandle(), size, storageMode.ordinal());
		MetalStallProbe.end(MetalStallProbe.Source.BUFFER_CREATE, startedNs, size);
		if (bufferHandle == 0L) {
			throw new IllegalStateException("Metal did not create the requested buffer");
		}
		MetalBuffer buffer = new MetalBuffer(this, bufferHandle, size, storageMode);
		this.buffers.add(buffer);
		return buffer;
	}

	public synchronized MetalTexture createTexture(final MetalTexture.Descriptor descriptor) {
		long startedNs = MetalStallProbe.begin();
		long textureHandle = MetalNative.nCreateTexture(
			this.requireOpenHandle(),
			descriptor.format().nativeCode(),
			descriptor.width(),
			descriptor.height(),
			descriptor.depthOrLayers(),
			descriptor.mipLevels(),
			descriptor.usage(),
			descriptor.cubemap()
		);
		MetalStallProbe.end(MetalStallProbe.Source.TEXTURE_CREATE, startedNs, descriptor.byteSize());
		if (textureHandle == 0L) {
			throw new IllegalStateException("Metal did not create the requested texture");
		}
		MetalTexture texture = new MetalTexture(this, textureHandle, descriptor);
		this.textures.add(texture);
		return texture;
	}

	public synchronized MetalSampler createSampler(final MetalSampler.Descriptor descriptor) {
		long samplerHandle = MetalNative.nCreateSampler(
			this.requireOpenHandle(),
			descriptor.minFilter().ordinal(),
			descriptor.magFilter().ordinal(),
			descriptor.addressModeU().ordinal(),
			descriptor.addressModeV().ordinal(),
			descriptor.maxAnisotropy(),
			descriptor.maxLod()
		);
		if (samplerHandle == 0L) {
			throw new IllegalStateException("Metal did not create the requested sampler");
		}
		MetalSampler sampler = new MetalSampler(this, samplerHandle, descriptor);
		this.samplers.add(sampler);
		return sampler;
	}

	public synchronized MetalFence createFence() {
		long fenceHandle = MetalNative.nCreateFence(this.requireOpenHandle());
		if (fenceHandle == 0L) {
			throw new IllegalStateException("Metal did not create a GPU fence");
		}
		MetalFence fence = new MetalFence(this, fenceHandle);
		this.fences.add(fence);
		return fence;
	}

	public synchronized boolean supportsTimestampQueries() {
		return MetalNative.nSupportsTimestampQueries(this.requireOpenHandle());
	}

	public synchronized boolean supportsRenderTimestampQueries() {
		return MetalNative.nSupportsRenderTimestampQueries(this.requireOpenHandle());
	}

	/** @see MetalPassCensus */
	public synchronized boolean supportsPassGpuTiming() {
		return MetalNative.nSupportsPassGpuTiming(this.requireOpenHandle());
	}

	public synchronized MetalTimestampQueryPool createTimestampQueryPool(final int size) {
		if (size <= 0 || size > 4096) {
			throw new IllegalArgumentException("A Metal timestamp query pool size must be between 1 and 4096");
		}
		if (!MetalNative.nSupportsTimestampQueries(this.requireOpenHandle())) {
			throw new UnsupportedOperationException("The selected Metal device does not support timestamp queries");
		}
		long poolHandle = MetalNative.nCreateTimestampQueryPool(this.handle, size);
		if (poolHandle == 0L) {
			throw new IllegalStateException("Metal did not create the requested timestamp query pool");
		}
		MetalTimestampQueryPool pool = new MetalTimestampQueryPool(this, poolHandle, size);
		this.timestampQueryPools.add(pool);
		return pool;
	}

	public synchronized MetalRenderPipeline createRenderPipeline(final MetalRenderPipeline.Descriptor descriptor) {
		List<MetalRenderPipeline.ColorTarget> colorTargets = descriptor.colorTargets();
		int[] colorFormats = new int[colorTargets.size()];
		int[] colorWriteMasks = new int[colorTargets.size()];
		int[] blendEnabled = new int[colorTargets.size()];
		int[] sourceColorFactors = new int[colorTargets.size()];
		int[] destinationColorFactors = new int[colorTargets.size()];
		int[] colorOperations = new int[colorTargets.size()];
		int[] sourceAlphaFactors = new int[colorTargets.size()];
		int[] destinationAlphaFactors = new int[colorTargets.size()];
		int[] alphaOperations = new int[colorTargets.size()];
		for (int index = 0; index < colorTargets.size(); index++) {
			MetalRenderPipeline.ColorTarget target = colorTargets.get(index);
			colorFormats[index] = target.format() == null ? -1 : target.format().nativeCode();
			colorWriteMasks[index] = target.writeMask();
			MetalRenderPipeline.BlendState blend = target.blendState();
			if (blend != null) {
				blendEnabled[index] = 1;
				sourceColorFactors[index] = blend.sourceColor().nativeCode();
				destinationColorFactors[index] = blend.destinationColor().nativeCode();
				colorOperations[index] = blend.colorOperation().nativeCode();
				sourceAlphaFactors[index] = blend.sourceAlpha().nativeCode();
				destinationAlphaFactors[index] = blend.destinationAlpha().nativeCode();
				alphaOperations[index] = blend.alphaOperation().nativeCode();
			}
		}

		List<MetalRenderPipeline.VertexAttribute> attributes = descriptor.vertexDescriptor().attributes();
		int[] attributeLocations = new int[attributes.size()];
		int[] attributeBufferIndices = new int[attributes.size()];
		int[] attributeOffsets = new int[attributes.size()];
		int[] attributeFormats = new int[attributes.size()];
		for (int index = 0; index < attributes.size(); index++) {
			MetalRenderPipeline.VertexAttribute attribute = attributes.get(index);
			attributeLocations[index] = attribute.location();
			attributeBufferIndices[index] = attribute.bufferIndex();
			attributeOffsets[index] = attribute.offset();
			attributeFormats[index] = attribute.format().nativeCode();
		}

		List<MetalRenderPipeline.VertexBufferLayout> layouts = descriptor.vertexDescriptor().layouts();
		int[] layoutBufferIndices = new int[layouts.size()];
		int[] layoutStrides = new int[layouts.size()];
		int[] layoutStepRates = new int[layouts.size()];
		for (int index = 0; index < layouts.size(); index++) {
			MetalRenderPipeline.VertexBufferLayout layout = layouts.get(index);
			layoutBufferIndices[index] = layout.bufferIndex();
			layoutStrides[index] = layout.stride();
			layoutStepRates[index] = layout.stepRate();
		}

		MetalRenderPipeline.DepthState depth = descriptor.depthState();
		long startedNs = MetalStallProbe.begin();
		long pipelineHandle = MetalNative.nCreateRenderPipeline(
			this.requireOpenHandle(),
			descriptor.vertexSource(),
			descriptor.vertexFunction(),
			descriptor.fragmentSource(),
			descriptor.fragmentFunction(),
			colorFormats,
			colorWriteMasks,
			blendEnabled,
			sourceColorFactors,
			destinationColorFactors,
			colorOperations,
			sourceAlphaFactors,
			destinationAlphaFactors,
			alphaOperations,
			descriptor.depthStencilFormat() == null ? -1 : descriptor.depthStencilFormat().nativeCode(),
			depth.testEnabled(),
			depth.writeEnabled(),
			depth.compareFunction().nativeCode(),
			depth.biasSlopeScale(),
			depth.biasConstant(),
			descriptor.rasterState().cullMode().nativeCode(),
			descriptor.rasterState().fillMode().nativeCode(),
			attributeLocations,
			attributeBufferIndices,
			attributeOffsets,
			attributeFormats,
			layoutBufferIndices,
			layoutStrides,
			layoutStepRates
		);
		MetalStallProbe.end(MetalStallProbe.Source.PIPELINE_CREATE, startedNs);
		if (pipelineHandle == 0L) {
			throw new IllegalStateException("Metal did not create the requested render pipeline");
		}
		MetalRenderPipeline pipeline = new MetalRenderPipeline(this, pipelineHandle, descriptor);
		this.renderPipelines.add(pipeline);
		return pipeline;
	}

	public MetalRenderPipeline createRenderPipeline(final MetalRenderPipeline.GlslDescriptor descriptor) {
		MetalShaderTranslator.PipelineTranslation translated = MetalShaderTranslator.translatePipeline(
			descriptor.vertexSource(),
			descriptor.vertexSourceName(),
			descriptor.fragmentSource(),
			descriptor.fragmentSourceName()
		);
		return this.createRenderPipeline(new MetalRenderPipeline.Descriptor(
			translated.vertex().metalSource(),
			translated.vertex().entryPoint(),
			translated.fragment().metalSource(),
			translated.fragment().entryPoint(),
			descriptor.colorFormat(),
			descriptor.depthFormat()
		));
	}

	public synchronized boolean isClosed() {
		return this.handle == 0L;
	}

	@Override
	public void close() {
		List<MetalCommandQueue> ownedQueues;
		List<MetalSurface> ownedSurfaces;
		List<MetalBuffer> ownedBuffers;
		List<MetalTexture> ownedTextures;
		List<MetalSampler> ownedSamplers;
		List<MetalFence> ownedFences;
		List<MetalTimestampQueryPool> ownedTimestampQueryPools;
		List<MetalRenderPipeline> ownedRenderPipelines;
		synchronized (this) {
			if (this.handle == 0L || this.closing) {
				return;
			}
			this.closing = true;
			ownedQueues = new ArrayList<>(this.commandQueues);
			ownedSurfaces = new ArrayList<>(this.surfaces);
			ownedBuffers = new ArrayList<>(this.buffers);
			ownedTextures = new ArrayList<>(this.textures);
			ownedSamplers = new ArrayList<>(this.samplers);
			ownedFences = new ArrayList<>(this.fences);
			ownedTimestampQueryPools = new ArrayList<>(this.timestampQueryPools);
			ownedRenderPipelines = new ArrayList<>(this.renderPipelines);
		}

		for (MetalCommandQueue commandQueue : ownedQueues) {
			commandQueue.close();
		}
		for (MetalSurface surface : ownedSurfaces) {
			surface.close();
		}
		for (MetalBuffer buffer : ownedBuffers) {
			buffer.close();
		}
		for (MetalTexture texture : ownedTextures) {
			texture.close();
		}
		for (MetalSampler sampler : ownedSamplers) {
			sampler.close();
		}
		for (MetalFence fence : ownedFences) {
			fence.close();
		}
		for (MetalTimestampQueryPool pool : ownedTimestampQueryPools) {
			pool.close();
		}
		for (MetalRenderPipeline renderPipeline : ownedRenderPipelines) {
			renderPipeline.close();
		}

		synchronized (this) {
			MetalNative.nReleaseDevice(this.handle);
			this.handle = 0L;
			this.commandQueues.clear();
			this.surfaces.clear();
			this.buffers.clear();
			this.textures.clear();
			this.samplers.clear();
			this.fences.clear();
			this.timestampQueryPools.clear();
			this.renderPipelines.clear();
			this.closing = false;
		}
	}

	synchronized void forget(final MetalCommandQueue commandQueue) {
		this.commandQueues.remove(commandQueue);
	}

	synchronized void forget(final MetalSurface surface) {
		this.surfaces.remove(surface);
	}

	synchronized void forget(final MetalBuffer buffer) {
		this.buffers.remove(buffer);
	}

	synchronized void forget(final MetalTexture texture) {
		this.textures.remove(texture);
	}

	synchronized void forget(final MetalSampler sampler) {
		this.samplers.remove(sampler);
	}

	synchronized void forget(final MetalFence fence) {
		this.fences.remove(fence);
	}

	synchronized void forget(final MetalTimestampQueryPool pool) {
		this.timestampQueryPools.remove(pool);
	}

	synchronized void forget(final MetalRenderPipeline renderPipeline) {
		this.renderPipelines.remove(renderPipeline);
	}

	private long requireOpenHandle() {
		if (this.handle == 0L || this.closing) {
			throw new IllegalStateException("Metal device is closed");
		}
		return this.handle;
	}
}
