MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateBuffer(
	JNIEnv *env,
	jclass type,
	jlong deviceHandle,
	jlong size,
	jint storageMode
) {
	@autoreleasepool {
		if (size <= 0 || (storageMode != 0 && storageMode != 1)) {
			mc_throw_state(env, @"A Metal buffer requires a positive size and a supported storage mode");
			return 0;
		}
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		if (device == nil) {
			return 0;
		}
		MTLResourceOptions options = storageMode == 0
			? MTLResourceStorageModeShared
			: MTLResourceStorageModePrivate;
		id<MTLBuffer> buffer = [device newBufferWithLength:(NSUInteger)size options:options];
		if (buffer == nil) {
			mc_throw_state(env, @"Metal could not allocate the requested buffer");
			return 0;
		}
		mc_sample_allocation(device);
		buffer.label = @"Metal Mod buffer";
		return mc_register_object(buffer, MCObjectTypeBuffer, deviceHandle);
	}
}

MC_EXPORT JNIEXPORT jobject JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nMappedBufferBytes(
	JNIEnv *env,
	jclass type,
	jlong bufferHandle,
	jlong offset,
	jlong length
) {
	@autoreleasepool {
		id<MTLBuffer> buffer = (id<MTLBuffer>)mc_get_object(env, bufferHandle, MCObjectTypeBuffer);
		if (buffer == nil) {
			return NULL;
		}
		if (buffer.storageMode != MTLStorageModeShared) {
			mc_throw_state(env, @"Only shared Metal buffers can be mapped to Java memory");
			return NULL;
		}
		if (offset < 0 || length <= 0 || (NSUInteger)offset > buffer.length || (NSUInteger)length > buffer.length - (NSUInteger)offset) {
			mc_throw_state(env, @"Metal buffer mapping range is out of bounds");
			return NULL;
		}
		return (*env)->NewDirectByteBuffer(env, (uint8_t *)buffer.contents + (NSUInteger)offset, length);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyBuffer(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong sourceHandle,
	jlong sourceOffset,
	jlong destinationHandle,
	jlong destinationOffset,
	jlong size
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, sourceHandle, destinationHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeBuffer, MCObjectTypeBuffer};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) {
			return;
		}
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLBuffer> source = objects[1];
		id<MTLBuffer> destination = objects[2];
		if (sourceOffset < 0 || destinationOffset < 0 || size <= 0
			|| (NSUInteger)sourceOffset > source.length || (NSUInteger)size > source.length - (NSUInteger)sourceOffset
			|| (NSUInteger)destinationOffset > destination.length || (NSUInteger)size > destination.length - (NSUInteger)destinationOffset) {
			mc_throw_state(env, @"Metal buffer copy range is out of bounds");
			return;
		}
		[commandBuffer pin:source];
		[commandBuffer pin:destination];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromBuffer:source
			sourceOffset:(NSUInteger)sourceOffset
			toBuffer:destination
			destinationOffset:(NSUInteger)destinationOffset
			size:(NSUInteger)size];
	}
}

// Apple GPU blits require pixel-aligned offsets/pitches, not a 256-byte row.
// Only the last row's actual pixels must fit; a subregion can end within a larger row.
static BOOL mc_valid_texture_transfer(jlong offset, jlong row, NSUInteger width,
    NSUInteger height, NSUInteger pixelBytes, NSUInteger bufferLength) {
    if (offset < 0 || row <= 0 || pixelBytes == 0 || height == 0
        || (NSUInteger)offset % pixelBytes != 0 || (NSUInteger)row % pixelBytes != 0
        || width > NSUIntegerMax / pixelBytes || (NSUInteger)row < width * pixelBytes
        || (NSUInteger)offset > bufferLength) return NO;
    NSUInteger available = bufferLength - (NSUInteger)offset;
    NSUInteger lastRow = width * pixelBytes;
    return lastRow <= available && height - 1 <= (available - lastRow) / (NSUInteger)row;
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyBufferToTexture(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong sourceHandle,
	jlong sourceOffset,
	jlong bytesPerRow,
	jlong textureHandle,
	jint mipLevel
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, sourceHandle, textureHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeBuffer, MCObjectTypeTexture};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) {
			return;
		}
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLBuffer> source = objects[1];
		id<MTLTexture> texture = objects[2];
		if (mipLevel < 0 || (NSUInteger)mipLevel >= texture.mipmapLevelCount) {
			mc_throw_state(env, @"Metal texture upload mip level is out of bounds");
			return;
		}
		NSUInteger width = MAX((NSUInteger)1, texture.width >> mipLevel);
		NSUInteger height = MAX((NSUInteger)1, texture.height >> mipLevel);
		NSUInteger bytesPerPixel = mc_bytes_per_pixel(texture.pixelFormat);
		if (!mc_valid_texture_transfer(sourceOffset, bytesPerRow, width, height, bytesPerPixel, source.length)) {
			mc_throw_state(env, @"Metal texture upload staging range or row pitch is invalid");
			return;
		}
		[commandBuffer pin:source];
		[commandBuffer pin:texture];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromBuffer:source
			sourceOffset:(NSUInteger)sourceOffset
			sourceBytesPerRow:(NSUInteger)bytesPerRow
			sourceBytesPerImage:0
			sourceSize:MTLSizeMake(width, height, 1)
			toTexture:texture
			destinationSlice:0
			destinationLevel:(NSUInteger)mipLevel
			destinationOrigin:MTLOriginMake(0, 0, 0)];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyTextureToBuffer(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong textureHandle,
	jint mipLevel,
	jlong destinationHandle,
	jlong destinationOffset,
	jlong bytesPerRow
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, textureHandle, destinationHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeTexture, MCObjectTypeBuffer};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) {
			return;
		}
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLTexture> texture = objects[1];
		id<MTLBuffer> destination = objects[2];
		if (mipLevel < 0 || (NSUInteger)mipLevel >= texture.mipmapLevelCount) {
			mc_throw_state(env, @"Metal texture readback mip level is out of bounds");
			return;
		}
		NSUInteger width = MAX((NSUInteger)1, texture.width >> mipLevel);
		NSUInteger height = MAX((NSUInteger)1, texture.height >> mipLevel);
		NSUInteger bytesPerPixel = mc_bytes_per_pixel(texture.pixelFormat);
		if (!mc_valid_texture_transfer(destinationOffset, bytesPerRow, width, height, bytesPerPixel, destination.length)) {
			mc_throw_state(env, @"Metal texture readback staging range or row pitch is invalid");
			return;
		}
		[commandBuffer pin:texture];
		[commandBuffer pin:destination];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromTexture:texture
			sourceSlice:0
			sourceLevel:(NSUInteger)mipLevel
			sourceOrigin:MTLOriginMake(0, 0, 0)
			sourceSize:MTLSizeMake(width, height, 1)
			toBuffer:destination
			destinationOffset:(NSUInteger)destinationOffset
			destinationBytesPerRow:(NSUInteger)bytesPerRow
			destinationBytesPerImage:0];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyBufferToTextureRegion(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong sourceHandle,
	jlong sourceOffset,
	jlong bytesPerRow,
	jlong textureHandle,
	jint mipLevel,
	jint arrayLayer,
	jint destinationX,
	jint destinationY,
	jint width,
	jint height
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, sourceHandle, textureHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeBuffer, MCObjectTypeTexture};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) return;
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLBuffer> source = objects[1];
		id<MTLTexture> texture = objects[2];
		NSUInteger sliceCount = mc_texture_slice_count(texture);
		if (mipLevel < 0 || (NSUInteger)mipLevel >= texture.mipmapLevelCount
			|| arrayLayer < 0 || (NSUInteger)arrayLayer >= sliceCount
			|| destinationX < 0 || destinationY < 0 || width <= 0 || height <= 0) {
			mc_throw_state(env, @"Metal texture upload region is invalid");
			return;
		}
		NSUInteger mipWidth = MAX((NSUInteger)1, texture.width >> mipLevel);
		NSUInteger mipHeight = MAX((NSUInteger)1, texture.height >> mipLevel);
		NSUInteger bytesPerPixel = mc_bytes_per_pixel(texture.pixelFormat);
		if ((NSUInteger)destinationX > mipWidth || (NSUInteger)width > mipWidth - (NSUInteger)destinationX
			|| (NSUInteger)destinationY > mipHeight || (NSUInteger)height > mipHeight - (NSUInteger)destinationY
			|| !mc_valid_texture_transfer(sourceOffset, bytesPerRow, width, height, bytesPerPixel, source.length)) {
			mc_throw_state(env, @"Metal texture upload staging range or row pitch is invalid");
			return;
		}
		[commandBuffer pin:source];
		[commandBuffer pin:texture];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromBuffer:source
			sourceOffset:(NSUInteger)sourceOffset
			sourceBytesPerRow:(NSUInteger)bytesPerRow
			sourceBytesPerImage:0
			sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
			toTexture:texture
			destinationSlice:(NSUInteger)arrayLayer
			destinationLevel:(NSUInteger)mipLevel
			destinationOrigin:MTLOriginMake((NSUInteger)destinationX, (NSUInteger)destinationY, 0)];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyTextureToBufferRegion(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong textureHandle,
	jint mipLevel,
	jint sourceX,
	jint sourceY,
	jint width,
	jint height,
	jlong destinationHandle,
	jlong destinationOffset,
	jlong bytesPerRow
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, textureHandle, destinationHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeTexture, MCObjectTypeBuffer};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) return;
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLTexture> texture = objects[1];
		id<MTLBuffer> destination = objects[2];
		if (mipLevel < 0 || (NSUInteger)mipLevel >= texture.mipmapLevelCount || sourceX < 0 || sourceY < 0 || width <= 0 || height <= 0) {
			mc_throw_state(env, @"Metal texture readback region is invalid");
			return;
		}
		NSUInteger mipWidth = MAX((NSUInteger)1, texture.width >> mipLevel);
		NSUInteger mipHeight = MAX((NSUInteger)1, texture.height >> mipLevel);
		NSUInteger bytesPerPixel = mc_bytes_per_pixel(texture.pixelFormat);
		if ((NSUInteger)sourceX > mipWidth || (NSUInteger)width > mipWidth - (NSUInteger)sourceX
			|| (NSUInteger)sourceY > mipHeight || (NSUInteger)height > mipHeight - (NSUInteger)sourceY
			|| !mc_valid_texture_transfer(destinationOffset, bytesPerRow, width, height, bytesPerPixel, destination.length)) {
			mc_throw_state(env, @"Metal texture readback staging range or row pitch is invalid");
			return;
		}
		[commandBuffer pin:texture];
		[commandBuffer pin:destination];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromTexture:texture
			sourceSlice:0
			sourceLevel:(NSUInteger)mipLevel
			sourceOrigin:MTLOriginMake((NSUInteger)sourceX, (NSUInteger)sourceY, 0)
			sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
			toBuffer:destination
			destinationOffset:(NSUInteger)destinationOffset
			destinationBytesPerRow:(NSUInteger)bytesPerRow
			destinationBytesPerImage:0];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyTexture(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong sourceHandle,
	jlong destinationHandle,
	jint mipLevel,
	jint sourceX,
	jint sourceY,
	jint destinationX,
	jint destinationY,
	jint width,
	jint height
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, sourceHandle, destinationHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeTexture, MCObjectTypeTexture};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) return;
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLTexture> source = objects[1];
		id<MTLTexture> destination = objects[2];
		if (source.pixelFormat != destination.pixelFormat || mipLevel < 0
			|| (NSUInteger)mipLevel >= source.mipmapLevelCount || (NSUInteger)mipLevel >= destination.mipmapLevelCount
			|| sourceX < 0 || sourceY < 0 || destinationX < 0 || destinationY < 0 || width <= 0 || height <= 0) {
			mc_throw_state(env, @"Metal texture copy format, mip level, or region is invalid");
			return;
		}
		NSUInteger sourceWidth = MAX((NSUInteger)1, source.width >> mipLevel);
		NSUInteger sourceHeight = MAX((NSUInteger)1, source.height >> mipLevel);
		NSUInteger destinationWidth = MAX((NSUInteger)1, destination.width >> mipLevel);
		NSUInteger destinationHeight = MAX((NSUInteger)1, destination.height >> mipLevel);
		if ((NSUInteger)sourceX > sourceWidth || (NSUInteger)width > sourceWidth - (NSUInteger)sourceX
			|| (NSUInteger)sourceY > sourceHeight || (NSUInteger)height > sourceHeight - (NSUInteger)sourceY
			|| (NSUInteger)destinationX > destinationWidth || (NSUInteger)width > destinationWidth - (NSUInteger)destinationX
			|| (NSUInteger)destinationY > destinationHeight || (NSUInteger)height > destinationHeight - (NSUInteger)destinationY) {
			mc_throw_state(env, @"Metal texture copy region is out of bounds");
			return;
		}
		[commandBuffer pin:source];
		[commandBuffer pin:destination];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromTexture:source
			sourceSlice:0
			sourceLevel:(NSUInteger)mipLevel
			sourceOrigin:MTLOriginMake((NSUInteger)sourceX, (NSUInteger)sourceY, 0)
			sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
			toTexture:destination
			destinationSlice:0
			destinationLevel:(NSUInteger)mipLevel
			destinationOrigin:MTLOriginMake((NSUInteger)destinationX, (NSUInteger)destinationY, 0)];
	}
}

static id<MTLRenderPipelineState> mc_presentation_pipeline(JNIEnv *env, id<MTLDevice> device, MTLPixelFormat destinationFormat) {
	static NSLock *lock;
	static id<MTLDevice> cachedDevice;
	static MTLPixelFormat cachedFormat;
	static id<MTLRenderPipelineState> cachedPipeline;
	static dispatch_once_t onceToken;
	dispatch_once(&onceToken, ^{ lock = [[NSLock alloc] init]; });
	[lock lock];
	if (cachedPipeline != nil && cachedDevice == device && cachedFormat == destinationFormat) {
		id<MTLRenderPipelineState> result = cachedPipeline;
		[lock unlock];
		return result;
	}
	NSString *source = @"#include <metal_stdlib>\n"
		"using namespace metal;\n"
		"struct PresentOut { float4 position [[position]]; float2 uv; };\n"
		"vertex PresentOut metalcraft_present_vertex(uint id [[vertex_id]]) {\n"
		"  const float2 positions[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};\n"
		"  const float2 uvs[3] = {float2(0.0, 0.0), float2(2.0, 0.0), float2(0.0, 2.0)};\n"
		"  PresentOut out; out.position = float4(positions[id], 0.0, 1.0); out.uv = uvs[id]; return out;\n"
		"}\n"
		"fragment float4 metalcraft_present_fragment(PresentOut in [[stage_in]], texture2d<float> source [[texture(0)]]) {\n"
		"  constexpr sampler presentSampler(coord::normalized, address::clamp_to_edge, filter::linear);\n"
		"  return source.sample(presentSampler, in.uv);\n"
		"}\n";
	NSError *libraryError = nil;
	MCLibraryEntry *entry = mc_cached_library(device, source, &libraryError);
	id<MTLLibrary> library = entry.library;
	if (library == nil) {
		[lock unlock];
		mc_throw_state(env, [NSString stringWithFormat:@"Metal presentation shader compilation failed: %@", libraryError.localizedDescription]);
		return nil;
	}
	MTLRenderPipelineDescriptor *descriptor = [[MTLRenderPipelineDescriptor alloc] init];
	descriptor.vertexFunction = mc_cached_function(entry, @"metalcraft_present_vertex");
	descriptor.fragmentFunction = mc_cached_function(entry, @"metalcraft_present_fragment");
	descriptor.colorAttachments[0].pixelFormat = destinationFormat;
	NSError *pipelineError = nil;
	id<MTLRenderPipelineState> pipeline = mc_cached_render_pipeline(device, descriptor, &pipelineError);
	if (pipeline == nil) {
		[lock unlock];
		mc_throw_state(env, [NSString stringWithFormat:@"Metal presentation pipeline creation failed: %@", pipelineError.localizedDescription]);
		return nil;
	}
	cachedDevice = device;
	cachedFormat = destinationFormat;
	cachedPipeline = pipeline;
	[lock unlock];
	return pipeline;
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nBlitTextureToDrawable(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong textureHandle,
	jlong drawableHandle
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, textureHandle, drawableHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeTexture, MCObjectTypeDrawable};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) return;
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLTexture> source = objects[1];
		id<CAMetalDrawable> drawable = objects[2];
		id<MTLTexture> destination = drawable.texture;
		[commandBuffer pin:source];
		[commandBuffer pin:drawable];
		if (source.pixelFormat == destination.pixelFormat && source.width == destination.width && source.height == destination.height) {
			id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
			[blit copyFromTexture:source toTexture:destination];
		} else {
			[commandBuffer endBlitEncoding];
			id<MTLRenderPipelineState> pipeline = mc_presentation_pipeline(env, commandBuffer.commandBuffer.device, destination.pixelFormat);
			if (pipeline == nil) return;
			MTLRenderPassDescriptor *descriptor = [MTLRenderPassDescriptor renderPassDescriptor];
			descriptor.colorAttachments[0].texture = destination;
			descriptor.colorAttachments[0].loadAction = MTLLoadActionDontCare;
			descriptor.colorAttachments[0].storeAction = MTLStoreActionStore;
			id<MTLRenderCommandEncoder> encoder = [commandBuffer.commandBuffer renderCommandEncoderWithDescriptor:descriptor];
			[encoder setRenderPipelineState:pipeline];
			[encoder setFragmentTexture:source atIndex:0];
			[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
			[encoder endEncoding];
		}
	}
}
