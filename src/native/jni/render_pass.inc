// Attachment-array layout, mirrored by MetalRenderPass.COLOR_FIELD_* on the Java side.
#define MC_MAX_COLOR_ATTACHMENTS 8
#define MC_COLOR_FIELDS 5
#define MC_COLOR_FIELD_IS_DRAWABLE 0
#define MC_COLOR_FIELD_MIP_LEVEL 1
#define MC_COLOR_FIELD_LOAD_ACTION 2
#define MC_COLOR_FIELD_STORE_ACTION 3
#define MC_COLOR_FIELD_ARRAY_SLICE 4
#define MC_COLOR_CLEAR_COMPONENTS 4
#define MC_MAX_PASS_OBJECTS (1 + MC_MAX_COLOR_ATTACHMENTS + 1)

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nBeginRenderPass(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlongArray colorTargetHandlesValue,
	jintArray colorFieldsValue,
	jdoubleArray colorClearValuesValue,
	jlong depthTargetHandle,
	jint depthMipLevel,
	jint depthArraySlice,
	jint depthLoadAction,
	jint depthStoreAction,
	jdouble clearDepth,
	jint renderTargetArrayLength,
	jint gpuTimingKind
) {
	@autoreleasepool {
		if (colorTargetHandlesValue == NULL || colorFieldsValue == NULL || colorClearValuesValue == NULL) {
			mc_throw_state(env, @"Metal render-pass attachment arrays cannot be null");
			return 0;
		}
		jsize colorCount = (*env)->GetArrayLength(env, colorTargetHandlesValue);
		if (colorCount < 0 || colorCount > MC_MAX_COLOR_ATTACHMENTS
			|| (*env)->GetArrayLength(env, colorFieldsValue) != colorCount * MC_COLOR_FIELDS
			|| (*env)->GetArrayLength(env, colorClearValuesValue) != colorCount * MC_COLOR_CLEAR_COMPONENTS) {
			mc_throw_state(env, @"Metal render-pass attachment arrays have an invalid length");
			return 0;
		}

		jlong colorTargetHandles[MC_MAX_COLOR_ATTACHMENTS];
		jint colorFields[MC_MAX_COLOR_ATTACHMENTS * MC_COLOR_FIELDS];
		jdouble colorClearValues[MC_MAX_COLOR_ATTACHMENTS * MC_COLOR_CLEAR_COMPONENTS];
		if (colorCount > 0) {
			(*env)->GetLongArrayRegion(env, colorTargetHandlesValue, 0, colorCount, colorTargetHandles);
			(*env)->GetIntArrayRegion(env, colorFieldsValue, 0, colorCount * MC_COLOR_FIELDS, colorFields);
			(*env)->GetDoubleArrayRegion(env, colorClearValuesValue, 0, colorCount * MC_COLOR_CLEAR_COMPONENTS, colorClearValues);
			if ((*env)->ExceptionCheck(env)) {
				return 0;
			}
		}

		jlong handles[MC_MAX_PASS_OBJECTS];
		MCObjectType types[MC_MAX_PASS_OBJECTS];
		id objects[MC_MAX_PASS_OBJECTS];
		NSInteger colorObjectIndex[MC_MAX_COLOR_ATTACHMENTS];
		NSUInteger objectCount = 0;
		NSInteger depthIndex = -1;
		NSUInteger attachedColorCount = 0;
		handles[objectCount] = commandBufferHandle;
		types[objectCount] = MCObjectTypeCommandBuffer;
		objectCount++;
		for (jsize index = 0; index < colorCount; index++) {
			if (colorTargetHandles[index] == 0) {
				colorObjectIndex[index] = -1;
				continue;
			}
			colorObjectIndex[index] = (NSInteger)objectCount;
			handles[objectCount] = colorTargetHandles[index];
			types[objectCount] = colorFields[index * MC_COLOR_FIELDS + MC_COLOR_FIELD_IS_DRAWABLE] != 0
				? MCObjectTypeDrawable
				: MCObjectTypeTexture;
			objectCount++;
			attachedColorCount++;
		}
		if (attachedColorCount == 0 && depthTargetHandle == 0) {
			mc_throw_state(env, @"A Metal render pass requires at least one attachment");
			return 0;
		}
		if (depthTargetHandle != 0) {
			depthIndex = (NSInteger)objectCount;
			handles[objectCount] = depthTargetHandle;
			types[objectCount] = MCObjectTypeTexture;
			objectCount++;
		}
		if (!mc_get_objects_same_device(env, handles, types, objects, objectCount)) {
			return 0;
		}

		MCMetalCommandBuffer *commandBuffer = objects[0];
		[commandBuffer endBlitEncoding];
		id<MTLTexture> depthTexture = depthIndex < 0 ? nil : (id<MTLTexture>)objects[depthIndex];
		if (depthTexture != nil && (depthMipLevel < 0 || (NSUInteger)depthMipLevel >= depthTexture.mipmapLevelCount)) {
			mc_throw_state(env, @"Metal render-pass attachment mip level is out of bounds");
			return 0;
		}
		if (depthTexture != nil && !mc_pixel_format_has_depth(depthTexture.pixelFormat)) {
			mc_throw_state(env, @"A Metal depth attachment requires a depth texture");
			return 0;
		}
		if (depthTexture != nil && (depthArraySlice < 0 || (NSUInteger)depthArraySlice >= mc_texture_slice_count(depthTexture))) {
			mc_throw_state(env, @"Metal render-pass attachment array slice is out of bounds");
			return 0;
		}
		if (renderTargetArrayLength < 0) {
			mc_throw_state(env, @"A layered Metal render pass cannot have a negative layer count");
			return 0;
		}

		NSUInteger targetWidth = 0;
		NSUInteger targetHeight = 0;
		id<MTLTexture> colorTextures[MC_MAX_COLOR_ATTACHMENTS];
		for (jsize index = 0; index < colorCount; index++) {
			colorTextures[index] = nil;
			if (colorObjectIndex[index] < 0) {
				continue;
			}
			const jint *fields = &colorFields[index * MC_COLOR_FIELDS];
			BOOL isDrawable = fields[MC_COLOR_FIELD_IS_DRAWABLE] != 0;
			id<MTLTexture> texture = isDrawable
				? [(id<CAMetalDrawable>)objects[colorObjectIndex[index]] texture]
				: (id<MTLTexture>)objects[colorObjectIndex[index]];
			jint mipLevel = fields[MC_COLOR_FIELD_MIP_LEVEL];
			if (mipLevel < 0 || (NSUInteger)mipLevel >= texture.mipmapLevelCount || (isDrawable && mipLevel != 0)) {
				mc_throw_state(env, @"Metal render-pass attachment mip level is out of bounds");
				return 0;
			}
			jint arraySlice = fields[MC_COLOR_FIELD_ARRAY_SLICE];
			if (arraySlice < 0 || (NSUInteger)arraySlice >= mc_texture_slice_count(texture)) {
				mc_throw_state(env, @"Metal render-pass attachment array slice is out of bounds");
				return 0;
			}
			if (mc_pixel_format_has_depth(texture.pixelFormat) || mc_pixel_format_has_stencil(texture.pixelFormat)) {
				mc_throw_state(env, @"A depth/stencil texture cannot be used as a Metal color attachment");
				return 0;
			}
			if (texture.storageMode == MTLStorageModeMemoryless) {
				jint loadAction = fields[MC_COLOR_FIELD_LOAD_ACTION];
				jint storeAction = fields[MC_COLOR_FIELD_STORE_ACTION];
				if (loadAction == 0 || storeAction != 1) {
					mc_throw_state(env, @"A memoryless Metal attachment cannot load or store");
					return 0;
				}
			}
			NSUInteger width = MAX((NSUInteger)1, texture.width >> mipLevel);
			NSUInteger height = MAX((NSUInteger)1, texture.height >> mipLevel);
			if (targetWidth == 0) {
				targetWidth = width;
				targetHeight = height;
			} else if (width != targetWidth || height != targetHeight) {
				mc_throw_state(env, @"Metal render-pass attachments must have matching dimensions");
				return 0;
			}
			colorTextures[index] = texture;
		}

		NSUInteger depthWidth = depthTexture == nil ? 0 : MAX((NSUInteger)1, depthTexture.width >> depthMipLevel);
		NSUInteger depthHeight = depthTexture == nil ? 0 : MAX((NSUInteger)1, depthTexture.height >> depthMipLevel);
		if (depthTexture != nil) {
			if (targetWidth == 0) {
				targetWidth = depthWidth;
				targetHeight = depthHeight;
			} else if (depthWidth != targetWidth || depthHeight != targetHeight) {
				mc_throw_state(env, @"Metal render-pass attachments must have matching dimensions");
				return 0;
			}
			if (depthTexture.storageMode == MTLStorageModeMemoryless && (depthLoadAction == 0 || depthStoreAction != 1)) {
				mc_throw_state(env, @"A memoryless Metal attachment cannot load or store");
				return 0;
			}
		}

		MTLRenderPassDescriptor *descriptor = [MTLRenderPassDescriptor renderPassDescriptor];
		for (jsize index = 0; index < colorCount; index++) {
			if (colorTextures[index] == nil) {
				continue;
			}
			const jint *fields = &colorFields[index * MC_COLOR_FIELDS];
			const jdouble *clear = &colorClearValues[index * MC_COLOR_CLEAR_COMPONENTS];
			MTLLoadAction loadAction = mc_load_action(env, fields[MC_COLOR_FIELD_LOAD_ACTION]);
			MTLStoreAction storeAction = mc_store_action(env, fields[MC_COLOR_FIELD_STORE_ACTION]);
			if ((*env)->ExceptionCheck(env)) {
				return 0;
			}
			NSUInteger colorIndex = (NSUInteger)index;
			descriptor.colorAttachments[colorIndex].texture = colorTextures[index];
			descriptor.colorAttachments[colorIndex].level = (NSUInteger)fields[MC_COLOR_FIELD_MIP_LEVEL];
			descriptor.colorAttachments[colorIndex].slice = (NSUInteger)fields[MC_COLOR_FIELD_ARRAY_SLICE];
			descriptor.colorAttachments[colorIndex].loadAction = loadAction;
			descriptor.colorAttachments[colorIndex].storeAction = storeAction == MTLStoreActionStore ? MTLStoreActionUnknown : storeAction;
			descriptor.colorAttachments[colorIndex].clearColor = MTLClearColorMake(clear[0], clear[1], clear[2], clear[3]);
		}
		if (attachedColorCount == 0) {
			descriptor.renderTargetWidth = targetWidth;
			descriptor.renderTargetHeight = targetHeight;
		}
		if (renderTargetArrayLength > 0) {
			descriptor.renderTargetArrayLength = (NSUInteger)renderTargetArrayLength;
		}
		if (depthTexture != nil) {
			MTLLoadAction nativeDepthLoadAction = mc_load_action(env, depthLoadAction);
			MTLStoreAction nativeDepthStoreAction = mc_store_action(env, depthStoreAction);
			if ((*env)->ExceptionCheck(env)) {
				return 0;
			}
			descriptor.depthAttachment.texture = depthTexture;
			descriptor.depthAttachment.level = (NSUInteger)depthMipLevel;
			descriptor.depthAttachment.slice = (NSUInteger)depthArraySlice;
			descriptor.depthAttachment.loadAction = nativeDepthLoadAction;
			descriptor.depthAttachment.storeAction = nativeDepthStoreAction == MTLStoreActionStore ? MTLStoreActionUnknown : nativeDepthStoreAction;
			descriptor.depthAttachment.clearDepth = clearDepth;
			if (mc_pixel_format_has_stencil(depthTexture.pixelFormat)) {
				descriptor.stencilAttachment.texture = depthTexture;
				descriptor.stencilAttachment.level = (NSUInteger)depthMipLevel;
				descriptor.stencilAttachment.slice = (NSUInteger)depthArraySlice;
				descriptor.stencilAttachment.loadAction = nativeDepthLoadAction;
				descriptor.stencilAttachment.storeAction = nativeDepthStoreAction == MTLStoreActionStore ? MTLStoreActionUnknown : nativeDepthStoreAction;
				descriptor.stencilAttachment.clearStencil = 0;
			}
		}

		for (jsize index = 0; index < colorCount; index++) {
			if (colorObjectIndex[index] >= 0) {
				[commandBuffer pin:objects[colorObjectIndex[index]]];
			}
		}
		if (depthTexture != nil) {
			[commandBuffer pin:depthTexture];
		}
		if (gpuTimingKind >= 0 && gpuTimingKind < MC_GPU_PASS_KINDS) {
			id<MTLCounterSampleBuffer> samples = mc_gpu_pass_sample_buffer(commandBuffer.commandBuffer.device);
			if (samples != nil) {
				uint64_t sequence = atomic_fetch_add_explicit(&mc_gpu_pass_next_slot, 1, memory_order_relaxed);
				NSUInteger slot = (NSUInteger)(sequence % MC_GPU_PASS_SLOTS);
				MTLRenderPassSampleBufferAttachmentDescriptor *samplePoints = descriptor.sampleBufferAttachments[0];
				samplePoints.sampleBuffer = samples;
				samplePoints.startOfVertexSampleIndex = slot * 2;
				samplePoints.endOfVertexSampleIndex = MTLCounterDontSample;
				samplePoints.startOfFragmentSampleIndex = MTLCounterDontSample;
				samplePoints.endOfFragmentSampleIndex = slot * 2 + 1;
				[commandBuffer addTimedPassKind:(uint32_t)gpuTimingKind sequence:sequence sampleBuffer:samples];
			}
		}
		id<MTLRenderCommandEncoder> encoder = [commandBuffer.commandBuffer renderCommandEncoderWithDescriptor:descriptor];
		if (encoder == nil) {
			mc_throw_state(env, @"Metal did not create a render command encoder");
			return 0;
		}
		encoder.label = @"Metal Mod render pass";
		[encoder setViewport:(MTLViewport){0.0, 0.0, (double)targetWidth, (double)targetHeight, 0.0, 1.0}];
		[encoder setScissorRect:(MTLScissorRect){0, 0, targetWidth, targetHeight}];
		MCMetalRenderPass *renderPass = [[MCMetalRenderPass alloc]
			initWithEncoder:encoder
			commandBuffer:commandBuffer
			descriptor:descriptor
			width:targetWidth
			height:targetHeight
		];
		return mc_register_object(renderPass, MCObjectTypeRenderPass, commandBufferHandle);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nDiscardRenderAttachments(JNIEnv *env, jclass type, jlong handle, jint colorMask, jboolean depth) {
    @autoreleasepool {
        MCMetalRenderPass *pass = (MCMetalRenderPass *)mc_get_object(env, handle, MCObjectTypeRenderPass);
        if (pass == nil) return;
        if (pass.ended || colorMask < 0 || ((NSUInteger)colorMask & ~pass.mutableStoreMask) != 0 || (depth && !pass.mutableDepthStore)) {
            mc_throw_state(env, @"Cannot discard an absent or ended Metal attachment"); return;
        }
        pass.discardedColors |= (NSUInteger)colorMask;
        pass.discardedDepth |= depth;
    }
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetRenderPipeline(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jlong pipelineHandle
) {
	@autoreleasepool {
		jlong handles[] = {renderPassHandle, pipelineHandle};
		MCObjectType types[] = {MCObjectTypeRenderPass, MCObjectTypeRenderPipeline};
		id objects[2];
		if (mc_get_objects_same_device(env, handles, types, objects, 2)) {
			MCMetalRenderPass *renderPass = objects[0];
			MCMetalRenderPipeline *pipeline = objects[1];
			[renderPass.commandBuffer pin:pipeline];
			[renderPass.encoder setRenderPipelineState:pipeline.pipelineState];
			[renderPass.encoder setDepthStencilState:pipeline.depthStencilState];
			[renderPass.encoder setFrontFacingWinding:MTLWindingClockwise];
			[renderPass.encoder setCullMode:pipeline.cullMode];
			[renderPass.encoder setTriangleFillMode:pipeline.fillMode];
			[renderPass.encoder
				setDepthBias:pipeline.depthBiasConstant
				slopeScale:pipeline.depthBiasSlopeScale
				clamp:0.0F
			];
		}
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetScissor(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint x,
	jint y,
	jint width,
	jint height
) {
	@autoreleasepool {
		MCMetalRenderPass *renderPass = (MCMetalRenderPass *)mc_get_object(env, renderPassHandle, MCObjectTypeRenderPass);
		if (renderPass == nil) {
			return;
		}
		if (x < 0 || y < 0 || width <= 0 || height <= 0
			|| (NSUInteger)x > renderPass.width || (NSUInteger)width > renderPass.width - (NSUInteger)x
			|| (NSUInteger)y > renderPass.height || (NSUInteger)height > renderPass.height - (NSUInteger)y) {
			mc_throw_state(env, @"Metal scissor rectangle is outside the render target");
			return;
		}
		[renderPass.encoder setScissorRect:(MTLScissorRect){
			(NSUInteger)x,
			(NSUInteger)y,
			(NSUInteger)width,
			(NSUInteger)height
		}];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetVertexBuffer(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint index,
	jlong bufferHandle,
	jlong offset
) {
	@autoreleasepool {
		jlong handles[] = {renderPassHandle, bufferHandle};
		MCObjectType types[] = {MCObjectTypeRenderPass, MCObjectTypeBuffer};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) {
			return;
		}
		id<MTLBuffer> buffer = objects[1];
		if (index < 0 || index >= 31 || offset < 0 || (NSUInteger)offset >= buffer.length) {
			mc_throw_state(env, @"Metal vertex-buffer binding index or offset is invalid");
			return;
		}
		MCMetalRenderPass *renderPass = objects[0];
		[renderPass.commandBuffer pin:buffer];
		[renderPass.encoder setVertexBuffer:buffer offset:(NSUInteger)offset atIndex:(NSUInteger)index];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetUniformBuffer(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint index,
	jlong bufferHandle,
	jlong offset,
	jint stages
) {
	@autoreleasepool {
		jlong handles[] = {renderPassHandle, bufferHandle};
		MCObjectType types[] = {MCObjectTypeRenderPass, MCObjectTypeBuffer};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) return;
		id<MTLBuffer> buffer = objects[1];
		if (index < 0 || index >= 16 || offset < 0 || (NSUInteger)offset >= buffer.length) {
			mc_throw_state(env, @"Metal uniform-buffer binding index or offset is invalid");
			return;
		}
		MCMetalRenderPass *renderPass = objects[0];
		[renderPass.commandBuffer pin:buffer];
		if (stages & MCShaderStageVertex) {
			[renderPass.encoder setVertexBuffer:buffer offset:(NSUInteger)offset atIndex:(NSUInteger)index];
		}
		if (stages & MCShaderStageFragment) {
			[renderPass.encoder setFragmentBuffer:buffer offset:(NSUInteger)offset atIndex:(NSUInteger)index];
		}
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetTexelBuffer(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint index,
	jlong bufferHandle,
	jlong offset,
	jlong length,
	jint format,
	jint stages
) {
	@autoreleasepool {
		jlong handles[] = {renderPassHandle, bufferHandle};
		MCObjectType types[] = {MCObjectTypeRenderPass, MCObjectTypeBuffer};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) return;
		if (index < 0 || index >= 16 || offset < 0 || length <= 0) {
			mc_throw_state(env, @"Metal texel-buffer binding index or range is invalid");
			return;
		}

		MTLPixelFormat pixelFormat = mc_pixel_format(env, format);
		NSUInteger bytesPerPixel = mc_bytes_per_pixel(pixelFormat);
		if (pixelFormat == MTLPixelFormatInvalid || bytesPerPixel == 0
			|| mc_pixel_format_has_depth(pixelFormat) || mc_pixel_format_has_stencil(pixelFormat)
			|| (NSUInteger)length % bytesPerPixel != 0) {
			mc_throw_state(env, @"Metal texel buffers require an evenly sized color pixel format");
			return;
		}

		id<MTLBuffer> buffer = objects[1];
		NSUInteger alignment = [buffer.device minimumTextureBufferAlignmentForPixelFormat:pixelFormat];
		if (alignment == 0 || (NSUInteger)offset % alignment != 0) {
			mc_throw_state(env, @"Metal texel-buffer offset does not satisfy the pixel-format alignment");
			return;
		}
		NSUInteger logicalBytes = (NSUInteger)length;
		if (logicalBytes > NSUIntegerMax - (alignment - 1)) {
			mc_throw_state(env, @"Metal texel-buffer row size overflowed");
			return;
		}
		NSUInteger bytesPerRow = ((logicalBytes + alignment - 1) / alignment) * alignment;
		if ((NSUInteger)offset > buffer.length || bytesPerRow > buffer.length - (NSUInteger)offset) {
			mc_throw_state(env, @"Metal texel-buffer view exceeds its padded buffer allocation");
			return;
		}

		// Views are cached on the buffer they belong to, so their lifetime ends with it.
		MCTexelViewCache *viewCache = objc_getAssociatedObject(buffer, MCTexelViewCacheKey);
		if (viewCache == nil) {
			viewCache = [MCTexelViewCache new];
			objc_setAssociatedObject(buffer, MCTexelViewCacheKey, viewCache, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
		}
		id<MTLTexture> texture = [viewCache viewForOffset:(NSUInteger)offset length:logicalBytes format:pixelFormat];
		if (texture == nil) {
			MTLTextureDescriptor *descriptor = [MTLTextureDescriptor
				textureBufferDescriptorWithPixelFormat:pixelFormat
				width:logicalBytes / bytesPerPixel
				resourceOptions:buffer.resourceOptions
				usage:MTLTextureUsageShaderRead];
			texture = [buffer
				newTextureWithDescriptor:descriptor
				offset:(NSUInteger)offset
				bytesPerRow:bytesPerRow];
			if (texture == nil) {
				mc_throw_state(env, @"Metal could not create a texture-buffer view");
				return;
			}
			mc_sample_allocation(buffer.device);
			texture.label = @"Metal Mod texel-buffer view";
			[viewCache storeView:texture offset:(NSUInteger)offset length:logicalBytes format:pixelFormat];
		}
		MCMetalRenderPass *renderPass = objects[0];
		[renderPass.commandBuffer pin:buffer];
		[renderPass.commandBuffer pin:texture];
		if (stages & MCShaderStageVertex) {
			[renderPass.encoder setVertexTexture:texture atIndex:(NSUInteger)index];
		}
		if (stages & MCShaderStageFragment) {
			[renderPass.encoder setFragmentTexture:texture atIndex:(NSUInteger)index];
		}
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetTexture(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint index,
	jlong textureViewHandle,
	jint stages
) {
	@autoreleasepool {
		jlong handles[] = {renderPassHandle, textureViewHandle};
		MCObjectType types[] = {MCObjectTypeRenderPass, MCObjectTypeTextureView};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) return;
		if (index < 0 || index >= 16) {
			mc_throw_state(env, @"Metal texture binding index is invalid");
			return;
		}
		MCMetalRenderPass *renderPass = objects[0];
		id<MTLTexture> texture = objects[1];
		[renderPass.commandBuffer pin:texture];
		if (stages & MCShaderStageVertex) {
			[renderPass.encoder setVertexTexture:texture atIndex:(NSUInteger)index];
		}
		if (stages & MCShaderStageFragment) {
			[renderPass.encoder setFragmentTexture:texture atIndex:(NSUInteger)index];
		}
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetSampler(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint index,
	jlong samplerHandle,
	jint stages
) {
	@autoreleasepool {
		jlong handles[] = {renderPassHandle, samplerHandle};
		MCObjectType types[] = {MCObjectTypeRenderPass, MCObjectTypeSampler};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) return;
		if (index < 0 || index >= 16) {
			mc_throw_state(env, @"Metal sampler binding index is invalid");
			return;
		}
		MCMetalRenderPass *renderPass = objects[0];
		id<MTLSamplerState> sampler = objects[1];
		[renderPass.commandBuffer pin:sampler];
		if (stages & MCShaderStageVertex) {
			[renderPass.encoder setVertexSamplerState:sampler atIndex:(NSUInteger)index];
		}
		if (stages & MCShaderStageFragment) {
			[renderPass.encoder setFragmentSamplerState:sampler atIndex:(NSUInteger)index];
		}
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nDraw(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint primitive,
	jint vertexStart,
	jint vertexCount,
	jint instanceCount,
	jint baseInstance
) {
	@autoreleasepool {
		MCMetalRenderPass *renderPass = (MCMetalRenderPass *)mc_get_object(env, renderPassHandle, MCObjectTypeRenderPass);
		if (renderPass == nil) {
			return;
		}
		if (vertexStart < 0 || vertexCount <= 0 || instanceCount <= 0 || baseInstance < 0) {
			mc_throw_state(env, @"Metal draw ranges and counts are invalid");
			return;
		}
		MTLPrimitiveType primitiveType = mc_primitive_type(env, primitive);
		if ((*env)->ExceptionCheck(env)) {
			return;
		}
		[renderPass.encoder drawPrimitives:primitiveType
			vertexStart:(NSUInteger)vertexStart
			vertexCount:(NSUInteger)vertexCount
			instanceCount:(NSUInteger)instanceCount
			baseInstance:(NSUInteger)baseInstance];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nDrawIndexed(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint primitive,
	jlong indexBufferHandle,
	jlong indexBufferOffset,
	jint indexType,
	jint indexCount,
	jint instanceCount,
	jint baseVertex,
	jint baseInstance
) {
	@autoreleasepool {
		jlong handles[] = {renderPassHandle, indexBufferHandle};
		MCObjectType types[] = {MCObjectTypeRenderPass, MCObjectTypeBuffer};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) {
			return;
		}
		id<MTLBuffer> indexBuffer = objects[1];
		NSUInteger indexSize = indexType == 0 ? 2 : (indexType == 1 ? 4 : 0);
		if (indexSize == 0 || indexBufferOffset < 0 || indexCount <= 0 || instanceCount <= 0 || baseInstance < 0
			|| (NSUInteger)indexBufferOffset % indexSize != 0
			|| (NSUInteger)indexBufferOffset > indexBuffer.length
			|| (NSUInteger)indexCount > (indexBuffer.length - (NSUInteger)indexBufferOffset) / indexSize) {
			mc_throw_state(env, @"Metal indexed-draw buffer range or count is invalid");
			return;
		}
		MTLPrimitiveType primitiveType = mc_primitive_type(env, primitive);
		if ((*env)->ExceptionCheck(env)) {
			return;
		}
		MCMetalRenderPass *renderPass = objects[0];
		[renderPass.commandBuffer pin:indexBuffer];
		[renderPass.encoder drawIndexedPrimitives:primitiveType
			indexCount:(NSUInteger)indexCount
			indexType:indexType == 0 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32
			indexBuffer:indexBuffer
			indexBufferOffset:(NSUInteger)indexBufferOffset
			instanceCount:(NSUInteger)instanceCount
			baseVertex:(NSInteger)baseVertex
			baseInstance:(NSUInteger)baseInstance];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nMultiDraw(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint primitive,
	jintArray firstVerticesValue,
	jintArray vertexCountsValue,
	jint instanceCount,
	jint firstInstance
) {
	@autoreleasepool {
		if (firstVerticesValue == NULL || vertexCountsValue == NULL) {
			mc_throw_state(env, @"Metal multi-draw parameter arrays cannot be null");
			return;
		}
		jsize drawCount = (*env)->GetArrayLength(env, firstVerticesValue);
		if ((*env)->GetArrayLength(env, vertexCountsValue) != drawCount || instanceCount < 0 || firstInstance < 0) {
			mc_throw_state(env, @"Metal multi-draw arrays and instance parameters are invalid");
			return;
		}
		if (drawCount == 0 || instanceCount == 0) {
			return;
		}
		MCMetalRenderPass *renderPass = (MCMetalRenderPass *)mc_get_object(env, renderPassHandle, MCObjectTypeRenderPass);
		if (renderPass == nil) {
			return;
		}
		MTLPrimitiveType primitiveType = mc_primitive_type(env, primitive);
		if ((*env)->ExceptionCheck(env)) {
			return;
		}
		jint *firstVertices = (*env)->GetIntArrayElements(env, firstVerticesValue, NULL);
		jint *vertexCounts = (*env)->GetIntArrayElements(env, vertexCountsValue, NULL);
		if (firstVertices == NULL || vertexCounts == NULL) {
			if (firstVertices != NULL) {
				(*env)->ReleaseIntArrayElements(env, firstVerticesValue, firstVertices, JNI_ABORT);
			}
			if (vertexCounts != NULL) {
				(*env)->ReleaseIntArrayElements(env, vertexCountsValue, vertexCounts, JNI_ABORT);
			}
			return;
		}
		for (jsize draw = 0; draw < drawCount; draw++) {
			if (firstVertices[draw] < 0 || vertexCounts[draw] < 0) {
				(*env)->ReleaseIntArrayElements(env, firstVerticesValue, firstVertices, JNI_ABORT);
				(*env)->ReleaseIntArrayElements(env, vertexCountsValue, vertexCounts, JNI_ABORT);
				mc_throw_state(env, @"Metal multi-draw vertex ranges cannot be negative");
				return;
			}
		}
		for (jsize draw = 0; draw < drawCount; draw++) {
			if (vertexCounts[draw] != 0) {
				[renderPass.encoder drawPrimitives:primitiveType
					vertexStart:(NSUInteger)firstVertices[draw]
					vertexCount:(NSUInteger)vertexCounts[draw]
					instanceCount:(NSUInteger)instanceCount
					baseInstance:(NSUInteger)firstInstance];
			}
		}
		(*env)->ReleaseIntArrayElements(env, firstVerticesValue, firstVertices, JNI_ABORT);
		(*env)->ReleaseIntArrayElements(env, vertexCountsValue, vertexCounts, JNI_ABORT);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nMultiDrawIndexed(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint primitive,
	jlong indexBufferHandle,
	jint indexType,
	jlongArray indexBufferOffsetsValue,
	jintArray indexCountsValue,
	jintArray baseVerticesValue,
	jint instanceCount,
	jint firstInstance
) {
	@autoreleasepool {
		if (indexBufferOffsetsValue == NULL || indexCountsValue == NULL || baseVerticesValue == NULL) {
			mc_throw_state(env, @"Metal indexed multi-draw parameter arrays cannot be null");
			return;
		}
		jsize drawCount = (*env)->GetArrayLength(env, indexBufferOffsetsValue);
		if ((*env)->GetArrayLength(env, indexCountsValue) != drawCount
			|| (*env)->GetArrayLength(env, baseVerticesValue) != drawCount
			|| instanceCount < 0 || firstInstance < 0) {
			mc_throw_state(env, @"Metal indexed multi-draw arrays and instance parameters are invalid");
			return;
		}
		if (drawCount == 0 || instanceCount == 0) {
			return;
		}
		jlong handles[] = {renderPassHandle, indexBufferHandle};
		MCObjectType types[] = {MCObjectTypeRenderPass, MCObjectTypeBuffer};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) {
			return;
		}
		NSUInteger indexSize = indexType == 0 ? 2 : (indexType == 1 ? 4 : 0);
		if (indexSize == 0) {
			mc_throw_state(env, @"Unsupported Metal multi-draw index type");
			return;
		}
		MTLPrimitiveType primitiveType = mc_primitive_type(env, primitive);
		if ((*env)->ExceptionCheck(env)) {
			return;
		}

		jlong *indexBufferOffsets = (*env)->GetLongArrayElements(env, indexBufferOffsetsValue, NULL);
		jint *indexCounts = (*env)->GetIntArrayElements(env, indexCountsValue, NULL);
		jint *baseVertices = (*env)->GetIntArrayElements(env, baseVerticesValue, NULL);
		if (indexBufferOffsets == NULL || indexCounts == NULL || baseVertices == NULL) {
			if (indexBufferOffsets != NULL) {
				(*env)->ReleaseLongArrayElements(env, indexBufferOffsetsValue, indexBufferOffsets, JNI_ABORT);
			}
			if (indexCounts != NULL) {
				(*env)->ReleaseIntArrayElements(env, indexCountsValue, indexCounts, JNI_ABORT);
			}
			if (baseVertices != NULL) {
				(*env)->ReleaseIntArrayElements(env, baseVerticesValue, baseVertices, JNI_ABORT);
			}
			return;
		}

		id<MTLBuffer> indexBuffer = objects[1];
		for (jsize draw = 0; draw < drawCount; draw++) {
			if (indexBufferOffsets[draw] < 0 || (NSUInteger)indexBufferOffsets[draw] % indexSize != 0
				|| indexCounts[draw] < 0 || (NSUInteger)indexBufferOffsets[draw] > indexBuffer.length
				|| (NSUInteger)indexCounts[draw] > (indexBuffer.length - (NSUInteger)indexBufferOffsets[draw]) / indexSize) {
				(*env)->ReleaseLongArrayElements(env, indexBufferOffsetsValue, indexBufferOffsets, JNI_ABORT);
				(*env)->ReleaseIntArrayElements(env, indexCountsValue, indexCounts, JNI_ABORT);
				(*env)->ReleaseIntArrayElements(env, baseVerticesValue, baseVertices, JNI_ABORT);
				mc_throw_state(env, @"Metal indexed multi-draw buffer range is invalid");
				return;
			}
		}
		MCMetalRenderPass *renderPass = objects[0];
		[renderPass.commandBuffer pin:indexBuffer];
		for (jsize draw = 0; draw < drawCount; draw++) {
			if (indexCounts[draw] != 0) {
				[renderPass.encoder drawIndexedPrimitives:primitiveType
					indexCount:(NSUInteger)indexCounts[draw]
					indexType:indexType == 0 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32
					indexBuffer:indexBuffer
					indexBufferOffset:(NSUInteger)indexBufferOffsets[draw]
					instanceCount:(NSUInteger)instanceCount
					baseVertex:(NSInteger)baseVertices[draw]
					baseInstance:(NSUInteger)firstInstance];
			}
		}
		(*env)->ReleaseLongArrayElements(env, indexBufferOffsetsValue, indexBufferOffsets, JNI_ABORT);
		(*env)->ReleaseIntArrayElements(env, indexCountsValue, indexCounts, JNI_ABORT);
		(*env)->ReleaseIntArrayElements(env, baseVerticesValue, baseVertices, JNI_ABORT);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nDrawIndirect(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint primitive,
	jlong commandBufferHandle,
	jlong commandBufferOffset,
	jint drawCount
) {
	@autoreleasepool {
		jlong handles[] = {renderPassHandle, commandBufferHandle};
		MCObjectType types[] = {MCObjectTypeRenderPass, MCObjectTypeBuffer};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) {
			return;
		}
		id<MTLBuffer> commandBuffer = objects[1];
		if (commandBufferOffset < 0 || commandBufferOffset % 4 != 0 || drawCount <= 0
			|| (NSUInteger)commandBufferOffset > commandBuffer.length
			|| (NSUInteger)drawCount > (commandBuffer.length - (NSUInteger)commandBufferOffset) / 16) {
			mc_throw_state(env, @"Metal indirect-draw command range is invalid");
			return;
		}
		MTLPrimitiveType primitiveType = mc_primitive_type(env, primitive);
		if ((*env)->ExceptionCheck(env)) {
			return;
		}
		MCMetalRenderPass *renderPass = objects[0];
		[renderPass.commandBuffer pin:commandBuffer];
		for (jint draw = 0; draw < drawCount; draw++) {
			[renderPass.encoder drawPrimitives:primitiveType
				indirectBuffer:commandBuffer
				indirectBufferOffset:(NSUInteger)commandBufferOffset + (NSUInteger)draw * 16];
		}
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nDrawIndexedIndirect(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jint primitive,
	jlong indexBufferHandle,
	jint indexType,
	jlong commandBufferHandle,
	jlong commandBufferOffset,
	jint drawCount
) {
	@autoreleasepool {
		jlong handles[] = {renderPassHandle, indexBufferHandle, commandBufferHandle};
		MCObjectType types[] = {MCObjectTypeRenderPass, MCObjectTypeBuffer, MCObjectTypeBuffer};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) {
			return;
		}
		if (indexType != 0 && indexType != 1) {
			mc_throw_state(env, @"Unsupported Metal indirect-draw index type");
			return;
		}
		id<MTLBuffer> commandBuffer = objects[2];
		if (commandBufferOffset < 0 || commandBufferOffset % 4 != 0 || drawCount <= 0
			|| (NSUInteger)commandBufferOffset > commandBuffer.length
			|| (NSUInteger)drawCount > (commandBuffer.length - (NSUInteger)commandBufferOffset) / 20) {
			mc_throw_state(env, @"Metal indexed indirect-draw command range is invalid");
			return;
		}
		MTLPrimitiveType primitiveType = mc_primitive_type(env, primitive);
		if ((*env)->ExceptionCheck(env)) {
			return;
		}
		MCMetalRenderPass *renderPass = objects[0];
		[renderPass.commandBuffer pin:objects[1]];
		[renderPass.commandBuffer pin:commandBuffer];
		for (jint draw = 0; draw < drawCount; draw++) {
			[renderPass.encoder drawIndexedPrimitives:primitiveType
				indexType:indexType == 0 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32
				indexBuffer:(id<MTLBuffer>)objects[1]
				indexBufferOffset:0
				indirectBuffer:commandBuffer
				indirectBufferOffset:(NSUInteger)commandBufferOffset + (NSUInteger)draw * 20];
		}
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nWriteRenderTimestamp(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jlong poolHandle,
	jint index
) {
	@autoreleasepool {
		jlong handles[] = {renderPassHandle, poolHandle};
		MCObjectType types[] = {MCObjectTypeRenderPass, MCObjectTypeTimestampQueryPool};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) {
			return;
		}
		MCMetalRenderPass *renderPass = objects[0];
		MCMetalTimestampQueryPool *pool = objects[1];
		if (index < 0 || (NSUInteger)index >= pool.size) {
			mc_throw_state(env, @"Metal timestamp query index is out of bounds");
			return;
		}
		if (!mc_supports_render_timestamp_queries(renderPass.encoder.device)) {
			mc_throw_state(env, @"The selected Metal device samples counters only at encoder stage boundaries, "
				"so it cannot take a timestamp part-way through a render pass; whole-pass GPU time is "
				"measured instead, through MetalPassCensus");
			return;
		}
		[renderPass.commandBuffer recordTimestampInPool:pool index:(NSUInteger)index];
		[renderPass.encoder sampleCountersInBuffer:pool.sampleBuffer atSampleIndex:(NSUInteger)index withBarrier:YES];
	}
}
