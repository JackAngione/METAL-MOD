package dev.metalcraft.client.metal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.ByteBuffer;
import java.util.Optional;

public final class MetalNative {
	private static final String RESOURCE = "/natives/macos-arm64/libmetalcraft.dylib";
	private static boolean loaded;
	private static Throwable loadFailure;

	private MetalNative() {
	}

	public static synchronized boolean load() {
		if (loaded) {
			return true;
		}
		if (loadFailure != null) {
			return false;
		}

		try (InputStream library = MetalNative.class.getResourceAsStream(RESOURCE)) {
			if (library == null) {
				throw new IOException("Missing native library resource " + RESOURCE);
			}
			Path extractionDirectory = Files.createTempDirectory("metalcraft-native-");
			Path extractedLibrary = extractionDirectory.resolve("libmetalcraft.dylib");
			Files.copy(library, extractedLibrary, StandardCopyOption.REPLACE_EXISTING);
			extractionDirectory.toFile().deleteOnExit();
			extractedLibrary.toFile().deleteOnExit();
			System.load(extractedLibrary.toAbsolutePath().toString());
			loaded = true;
			return true;
		} catch (IOException | LinkageError error) {
			loadFailure = error;
			return false;
		}
	}

	public static Optional<Throwable> loadFailure() {
		return Optional.ofNullable(loadFailure);
	}

	/** Whether the native bridge is loaded, without attempting to load it. */
	public static boolean isLoaded() {
		return loaded;
	}

	public static boolean isSupported() {
		return load() && nIsSupported();
	}

	public static Optional<MetalDevice> openDefaultDevice() {
		if (!load()) {
			return Optional.empty();
		}
		long handle = nCreateDefaultDevice();
		return handle == 0L ? Optional.empty() : Optional.of(new MetalDevice(handle));
	}

	static native String nDeviceName(long handle);

	static native long nRecommendedWorkingSet(long handle);
	static native long nCurrentAllocatedSize(long handle);
	static native void nStartAllocationProbe(long handle);
	static native void nStopAllocationProbe(long handle);
	static native long[] nAllocationProbe(long handle);

	static native long nCreateCommandQueue(long deviceHandle);

	static native long nCreateCommandBuffer(long queueHandle);

	/** Drains GPU busy time from completed command buffers into {@code destination} as {nanos, count}. */
	static native void nTakeGpuWork(long[] destination);
	static native void nBeginGpuFrameCapture();
	static native void nBeginGpuCaptureFrame();
	static native void nEndGpuCaptureFrame();
	static native long[] nEndGpuFrameCapture();
	static native long[] nProcessMemoryAndThermalState();

	/**
	 * Drains per-pass GPU time into {@code destination} as {nanos, count} for each pass kind.
	 *
	 * @see MetalPassCensus
	 */
	static native void nTakeGpuPassWork(long[] destination);

	/** The number of pass kinds {@link #nTakeGpuPassWork} reports, which the native ABI fixes. */
	static native int nGpuPassKinds();

	/**
	 * Whether this device can sample counters at encoder stage boundaries, which is what per-pass
	 * GPU timing is taken at. False leaves passes untimed rather than failing them.
	 */
	static native boolean nSupportsPassGpuTiming(long deviceHandle);

	static native long nCreateSurface(long deviceHandle, long cocoaViewHandle, int width, int height);

	static native void nResizeSurface(long handle, int width, int height);

	static native void nSetSurfaceDisplaySync(long handle, boolean enabled);

	static native long nAcquireDrawable(long surfaceHandle);

	static native void nPresentDrawable(long commandBufferHandle, long drawableHandle);

	static native void nCommitCommandBuffer(long handle);

	static native void nWaitForCommandBuffer(long handle);
	static native long nCreateCommandCompletion(long handle, long deviceHandle);
	static native boolean nPollCommandCompletion(long handle, boolean wait);
	static native void nReleaseCommandCompletion(long handle);

	static native long nCreateBuffer(long deviceHandle, long size, int storageMode);

	static native ByteBuffer nMappedBufferBytes(long bufferHandle, long offset, long length);

	static native void nCopyBuffer(long commandBufferHandle, long sourceHandle, long sourceOffset, long destinationHandle, long destinationOffset, long size);

	static native void nCopyBufferToTexture(long commandBufferHandle, long sourceHandle, long sourceOffset, long bytesPerRow, long textureHandle, int mipLevel);

	static native void nCopyTextureToBuffer(long commandBufferHandle, long textureHandle, int mipLevel, long destinationHandle, long destinationOffset, long bytesPerRow);

	static native void nCopyBufferToTextureRegion(
		long commandBufferHandle,
		long sourceHandle,
		long sourceOffset,
		long bytesPerRow,
		long textureHandle,
		int mipLevel,
		int arrayLayer,
		int destinationX,
		int destinationY,
		int width,
		int height
	);

	static native void nCopyTextureToBufferRegion(
		long commandBufferHandle,
		long textureHandle,
		int mipLevel,
		int sourceX,
		int sourceY,
		int width,
		int height,
		long destinationHandle,
		long destinationOffset,
		long bytesPerRow
	);

	static native void nCopyTexture(
		long commandBufferHandle,
		long sourceHandle,
		long destinationHandle,
		int mipLevel,
		int sourceX,
		int sourceY,
		int destinationX,
		int destinationY,
		int width,
		int height
	);

	static native void nBlitTextureToDrawable(long commandBufferHandle, long textureHandle, long drawableHandle);

	static native int nWindowPresentationState(long cocoaWindow);

	static native long nCreateFence(long deviceHandle);

	static native void nSignalFence(long commandBufferHandle, long fenceHandle, long value);

	static native void nWaitForFence(long commandBufferHandle, long fenceHandle, long value);

	static native long nFenceValue(long fenceHandle);

	static native boolean nSupportsTimestampQueries(long deviceHandle);

	static native boolean nSupportsRenderTimestampQueries(long deviceHandle);

	static native long nCreateTimestampQueryPool(long deviceHandle, int size);

	static native long[] nTimestampQueryValues(long poolHandle, int index, int count);

	static native void nWriteCommandTimestamp(long commandBufferHandle, long poolHandle, int index);

	static native void nWriteRenderTimestamp(long renderPassHandle, long poolHandle, int index);

	static native int nCommandBufferRetainedResourceCount(long commandBufferHandle);

	/** @param memoryless whether the texture lives only in tile memory, with no device allocation */
	static native long nCreateTexture(
		long deviceHandle,
		int format,
		int width,
		int height,
		int depthOrLayers,
		int mipLevels,
		int usage,
		boolean cubemap,
		boolean memoryless
	);

	static native long nCreateTextureView(long textureHandle, int baseMipLevel, int mipLevels);

	static native long nCreateSampler(
		long deviceHandle,
		int minFilter,
		int magFilter,
		int addressModeU,
		int addressModeV,
		int maxAnisotropy,
		double maxLod,
		double minLod
	);

	static native long nCreateRenderPipeline(
		long deviceHandle,
		String vertexSource,
		String vertexFunction,
		String fragmentSource,
		String fragmentFunction,
		int[] colorFormats,
		int[] colorWriteMasks,
		int[] blendEnabled,
		int[] sourceColorFactors,
		int[] destinationColorFactors,
		int[] colorOperations,
		int[] sourceAlphaFactors,
		int[] destinationAlphaFactors,
		int[] alphaOperations,
		int depthStencilFormat,
		boolean depthTestEnabled,
		boolean depthWriteEnabled,
		int depthCompareFunction,
		float depthBiasSlopeScale,
		float depthBiasConstant,
		int cullMode,
		int fillMode,
		int[] attributeLocations,
		int[] attributeBufferIndices,
		int[] attributeOffsets,
		int[] attributeFormats,
		int[] layoutBufferIndices,
		int[] layoutStrides,
		int[] layoutStepRates,
		int inputPrimitiveTopology
	);

	/**
	 * @param colorTargetHandles one handle per color attachment index; zero leaves that index empty
	 * @param colorFields {@code MetalRenderPass.COLOR_FIELDS} entries per index
	 * @param colorClearValues {@code MetalRenderPass.COLOR_CLEAR_COMPONENTS} entries per index
	 */
	static native long nBeginRenderPass(
		long commandBufferHandle,
		long[] colorTargetHandles,
		int[] colorFields,
		double[] colorClearValues,
		long depthTargetHandle,
		int depthMipLevel,
		int depthArraySlice,
		int depthLoadAction,
		int depthStoreAction,
		double clearDepth,
		int renderTargetArrayLength,
		int gpuTimingKind
	);

	static native long nCreateComputePipeline(long deviceHandle, String source, String functionName);

	static native int nComputePipelineMaxThreadsPerThreadgroup(long pipelineHandle);

	static native int nComputePipelineThreadExecutionWidth(long pipelineHandle);

	static native void nReleaseComputePipeline(long pipelineHandle);

	static native long nBeginComputePass(long commandBufferHandle, int gpuTimingKind);

	static native void nSetComputePipeline(long passHandle, long pipelineHandle);

	static native void nSetComputeBuffer(long passHandle, int index, long bufferHandle, long offset);

	static native void nSetComputeTexture(long passHandle, int index, long textureViewHandle);

	static native void nSetComputeSampler(long passHandle, int index, long samplerHandle);

	static native void nDispatchThreadgroups(
		long passHandle,
		int groupsX,
		int groupsY,
		int groupsZ,
		int threadsX,
		int threadsY,
		int threadsZ
	);

	static native void nEndComputePass(long passHandle);

	static native void nSetRenderPipeline(long renderPassHandle, long pipelineHandle);

	static native void nSetScissor(long renderPassHandle, int x, int y, int width, int height);

	static native void nSetVertexBuffer(long renderPassHandle, int index, long bufferHandle, long offset);

	static native void nSetUniformBuffer(long renderPassHandle, int index, long bufferHandle, long offset, int stages);

	static native void nSetTexelBuffer(long renderPassHandle, int index, long bufferHandle, long offset, long length, int format, int stages);

	static native void nSetTexture(long renderPassHandle, int index, long textureViewHandle, int stages);

	static native void nSetSampler(long renderPassHandle, int index, long samplerHandle, int stages);

	static native void nDraw(
		long renderPassHandle,
		int primitive,
		int vertexStart,
		int vertexCount,
		int instanceCount,
		int baseInstance
	);

	static native void nDrawIndexed(
		long renderPassHandle,
		int primitive,
		long indexBufferHandle,
		long indexBufferOffset,
		int indexType,
		int indexCount,
		int instanceCount,
		int baseVertex,
		int baseInstance
	);

	static native void nMultiDraw(
		long renderPassHandle,
		int primitive,
		int[] firstVertices,
		int[] vertexCounts,
		int instanceCount,
		int firstInstance
	);

	static native void nMultiDrawIndexed(
		long renderPassHandle,
		int primitive,
		long indexBufferHandle,
		int indexType,
		long[] indexBufferOffsets,
		int[] indexCounts,
		int[] baseVertices,
		int instanceCount,
		int firstInstance
	);

	static native void nDrawIndirect(
		long renderPassHandle,
		int primitive,
		long commandBufferHandle,
		long commandBufferOffset,
		int drawCount
	);

	static native void nDrawIndexedIndirect(
		long renderPassHandle,
		int primitive,
		long indexBufferHandle,
		int indexType,
		long commandBufferHandle,
		long commandBufferOffset,
		int drawCount
	);

	/**
	 * Replays a recorded batch of binds and draws.
	 *
	 * @param commands a direct buffer holding a {@link MetalCommandStream} header and its records
	 * @param byteCount how much of {@code commands} the batch occupies
	 */
	static native void nSubmitCommandStream(long renderPassHandle, java.nio.ByteBuffer commands, int byteCount);

	static native void nEndRenderPass(long handle);

	static native void nReleaseDevice(long handle);

	static native void nReleaseCommandQueue(long handle);

	static native void nReleaseCommandBuffer(long handle);

	static native void nReleaseSurface(long handle);

	static native void nReleaseDrawable(long handle);

	static native void nReleaseBuffer(long handle);

	static native void nReleaseTexture(long handle);

	static native void nReleaseTextureView(long handle);

	static native void nReleaseSampler(long handle);

	static native void nReleaseFence(long handle);

	static native void nReleaseTimestampQueryPool(long handle);

	static native void nReleaseRenderPipeline(long handle);

	private static native boolean nIsSupported();

	private static native long nCreateDefaultDevice();
}
