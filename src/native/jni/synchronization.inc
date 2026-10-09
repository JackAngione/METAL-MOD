MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateFence(JNIEnv *env, jclass type, jlong deviceHandle) {
	@autoreleasepool {
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		if (device == nil) {
			return 0;
		}
		id<MTLSharedEvent> event = [device newSharedEvent];
		if (event == nil) {
			mc_throw_state(env, @"Metal could not create a shared-event fence");
			return 0;
		}
		event.label = @"Metal Mod GPU fence";
		return mc_register_object(event, MCObjectTypeFence, deviceHandle);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSignalFence(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong fenceHandle,
	jlong value
) {
	@autoreleasepool {
		if (value <= 0) {
			mc_throw_state(env, @"A Metal fence signal value must be positive");
			return;
		}
		jlong handles[] = {commandBufferHandle, fenceHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeFence};
		id objects[2];
		if (mc_get_objects_same_device(env, handles, types, objects, 2)) {
			MCMetalCommandBuffer *commandBuffer = objects[0];
			[commandBuffer endBlitEncoding];
			[commandBuffer pin:objects[1]];
			[commandBuffer.commandBuffer encodeSignalEvent:(id<MTLSharedEvent>)objects[1] value:(uint64_t)value];
		}
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nWaitForFence(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong fenceHandle,
	jlong value
) {
	@autoreleasepool {
		if (value <= 0) {
			mc_throw_state(env, @"A Metal fence wait value must be positive");
			return;
		}
		jlong handles[] = {commandBufferHandle, fenceHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeFence};
		id objects[2];
		if (mc_get_objects_same_device(env, handles, types, objects, 2)) {
			MCMetalCommandBuffer *commandBuffer = objects[0];
			[commandBuffer endBlitEncoding];
			[commandBuffer pin:objects[1]];
			[commandBuffer.commandBuffer encodeWaitForEvent:(id<MTLSharedEvent>)objects[1] value:(uint64_t)value];
		}
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nFenceValue(JNIEnv *env, jclass type, jlong fenceHandle) {
	@autoreleasepool {
		id<MTLSharedEvent> event = (id<MTLSharedEvent>)mc_get_object(env, fenceHandle, MCObjectTypeFence);
		return event == nil ? 0 : (jlong)event.signaledValue;
	}
}

MC_EXPORT JNIEXPORT jboolean JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSupportsTimestampQueries(JNIEnv *env, jclass type, jlong deviceHandle) {
	@autoreleasepool {
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		return device != nil && mc_supports_timestamp_queries(device) ? JNI_TRUE : JNI_FALSE;
	}
}

MC_EXPORT JNIEXPORT jboolean JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSupportsRenderTimestampQueries(JNIEnv *env, jclass type, jlong deviceHandle) {
	@autoreleasepool {
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		return device != nil && mc_supports_render_timestamp_queries(device) ? JNI_TRUE : JNI_FALSE;
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateTimestampQueryPool(
	JNIEnv *env,
	jclass type,
	jlong deviceHandle,
	jint size
) {
	@autoreleasepool {
		if (size <= 0 || size > 4096) {
			mc_throw_state(env, @"A Metal timestamp query pool size must be between 1 and 4096");
			return 0;
		}
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		if (device == nil) {
			return 0;
		}
		id<MTLCounterSet> timestampSet = mc_timestamp_counter_set(device);
		if (timestampSet == nil || !mc_supports_timestamp_queries(device)) {
			mc_throw_state(env, @"The selected Metal device does not support command timestamp sampling");
			return 0;
		}
		MTLCounterSampleBufferDescriptor *descriptor = [[MTLCounterSampleBufferDescriptor alloc] init];
		descriptor.counterSet = timestampSet;
		descriptor.label = @"Metal Mod timestamp queries";
		descriptor.storageMode = MTLStorageModeShared;
		descriptor.sampleCount = (NSUInteger)size;
		NSError *error = nil;
		id<MTLCounterSampleBuffer> sampleBuffer = [device newCounterSampleBufferWithDescriptor:descriptor error:&error];
		if (sampleBuffer == nil) {
			mc_throw_state(env, [NSString stringWithFormat:@"Metal could not create a timestamp query pool: %@", error.localizedDescription]);
			return 0;
		}
		MCMetalTimestampQueryPool *pool = [[MCMetalTimestampQueryPool alloc]
			initWithSampleBuffer:sampleBuffer
			size:(NSUInteger)size
		];
		if (pool == nil) {
			mc_throw_state(env, @"Metal Mod could not allocate timestamp availability tracking");
			return 0;
		}
		return mc_register_object(pool, MCObjectTypeTimestampQueryPool, deviceHandle);
	}
}

MC_EXPORT JNIEXPORT jlongArray JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nTimestampQueryValues(
	JNIEnv *env,
	jclass type,
	jlong poolHandle,
	jint index,
	jint count
) {
	@autoreleasepool {
		MCMetalTimestampQueryPool *pool = (MCMetalTimestampQueryPool *)mc_get_object(env, poolHandle, MCObjectTypeTimestampQueryPool);
		if (pool == nil) {
			return NULL;
		}
		if (index < 0 || count < 0 || (NSUInteger)index > pool.size || (NSUInteger)count > pool.size - (NSUInteger)index) {
			mc_throw_state(env, @"Metal timestamp query read range is out of bounds");
			return NULL;
		}
		jlongArray result = (*env)->NewLongArray(env, (jsize)(count * 2));
		if (result == NULL || count == 0) {
			return result;
		}
		jlong *values = calloc((NSUInteger)count * 2, sizeof(jlong));
		if (values == NULL) {
			mc_throw_state(env, @"Metal Mod could not allocate timestamp query results");
			return NULL;
		}
		for (jint offset = 0; offset < count; offset++) {
			uint64_t timestamp = 0;
			if ([pool getValue:&timestamp atIndex:(NSUInteger)(index + offset)]) {
				values[offset * 2] = (jlong)timestamp;
				values[offset * 2 + 1] = 1;
			}
		}
		(*env)->SetLongArrayRegion(env, result, 0, (jsize)(count * 2), values);
		free(values);
		return result;
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nWriteCommandTimestamp(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong poolHandle,
	jint index
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, poolHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeTimestampQueryPool};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) {
			return;
		}
		MCMetalCommandBuffer *commandBuffer = objects[0];
		MCMetalTimestampQueryPool *pool = objects[1];
		[commandBuffer endBlitEncoding];
		if (index < 0 || (NSUInteger)index >= pool.size) {
			mc_throw_state(env, @"Metal timestamp query index is out of bounds");
			return;
		}
		id<MTLDevice> device = commandBuffer.commandBuffer.device;
		id<MTLBlitCommandEncoder> blit;
		if ([device supportsCounterSampling:MTLCounterSamplingPointAtBlitBoundary]) {
			blit = [commandBuffer.commandBuffer blitCommandEncoder];
			[blit sampleCountersInBuffer:pool.sampleBuffer atSampleIndex:(NSUInteger)index withBarrier:YES];
		} else if ([device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) {
			MTLBlitPassDescriptor *descriptor = [MTLBlitPassDescriptor blitPassDescriptor];
			MTLBlitPassSampleBufferAttachmentDescriptor *attachment = descriptor.sampleBufferAttachments[0];
			attachment.sampleBuffer = pool.sampleBuffer;
			attachment.startOfEncoderSampleIndex = MTLCounterDontSample;
			attachment.endOfEncoderSampleIndex = (NSUInteger)index;
			blit = [commandBuffer.commandBuffer blitCommandEncoderWithDescriptor:descriptor];
		} else {
			mc_throw_state(env, @"The selected Metal device does not support command timestamp sampling");
			return;
		}
		if (blit == nil) {
			mc_throw_state(env, @"Metal could not create a timestamp sampling encoder");
			return;
		}
		[commandBuffer recordTimestampInPool:pool index:(NSUInteger)index];
		[blit endEncoding];
	}
}

MC_EXPORT JNIEXPORT jint JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCommandBufferRetainedResourceCount(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle
) {
	@autoreleasepool {
		MCMetalCommandBuffer *commandBuffer = (MCMetalCommandBuffer *)mc_get_object(env, commandBufferHandle, MCObjectTypeCommandBuffer);
		return commandBuffer == nil ? 0 : (jint)commandBuffer.retainedResourceCount;
	}
}
