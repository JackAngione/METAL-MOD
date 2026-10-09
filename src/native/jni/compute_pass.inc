MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateComputePipeline(
	JNIEnv *env,
	jclass type,
	jlong deviceHandle,
	jstring sourceValue,
	jstring functionValue
) {
	@autoreleasepool {
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		if (device == nil) {
			return 0;
		}
		if (sourceValue == NULL || functionValue == NULL) {
			mc_throw_state(env, @"A Metal compute pipeline requires MSL source and a kernel function name");
			return 0;
		}
		const char *sourceCharacters = (*env)->GetStringUTFChars(env, sourceValue, NULL);
		const char *functionCharacters = (*env)->GetStringUTFChars(env, functionValue, NULL);
		if (sourceCharacters == NULL || functionCharacters == NULL) {
			if (sourceCharacters != NULL) {
				(*env)->ReleaseStringUTFChars(env, sourceValue, sourceCharacters);
			}
			if (functionCharacters != NULL) {
				(*env)->ReleaseStringUTFChars(env, functionValue, functionCharacters);
			}
			return 0;
		}
		NSString *source = [NSString stringWithUTF8String:sourceCharacters];
		NSString *functionName = [NSString stringWithUTF8String:functionCharacters];
		(*env)->ReleaseStringUTFChars(env, sourceValue, sourceCharacters);
		(*env)->ReleaseStringUTFChars(env, functionValue, functionCharacters);

		NSError *libraryError = nil;
		MCLibraryEntry *entry = mc_cached_library(device, source, &libraryError);
	id<MTLLibrary> library = entry.library;
		if (library == nil) {
			mc_throw_state(env, [NSString stringWithFormat:@"Metal compute-shader compilation failed: %@", libraryError.localizedDescription]);
			return 0;
		}
		id<MTLFunction> function = mc_cached_function(entry, functionName);
		if (function == nil) {
			mc_throw_state(env, [NSString stringWithFormat:
				@"Metal shader library does not contain the requested compute function %@; available functions %@",
				functionName,
				library.functionNames
			]);
			return 0;
		}
		NSError *pipelineError = nil;
		id<MTLComputePipelineState> pipeline = mc_cached_compute_pipeline(device, function, &pipelineError);
		if (pipeline == nil) {
			mc_throw_state(env, [NSString stringWithFormat:@"Metal compute-pipeline creation failed: %@", pipelineError.localizedDescription]);
			return 0;
		}
		return mc_register_object(pipeline, MCObjectTypeComputePipeline, deviceHandle);
	}
}

MC_EXPORT JNIEXPORT jint JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nComputePipelineMaxThreadsPerThreadgroup(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		id<MTLComputePipelineState> pipeline = (id<MTLComputePipelineState>)mc_get_object(env, handle, MCObjectTypeComputePipeline);
		return pipeline == nil ? 0 : (jint)pipeline.maxTotalThreadsPerThreadgroup;
	}
}

MC_EXPORT JNIEXPORT jint JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nComputePipelineThreadExecutionWidth(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		id<MTLComputePipelineState> pipeline = (id<MTLComputePipelineState>)mc_get_object(env, handle, MCObjectTypeComputePipeline);
		return pipeline == nil ? 0 : (jint)pipeline.threadExecutionWidth;
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseComputePipeline(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeComputePipeline);
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nBeginComputePass(
	JNIEnv *env, jclass type, jlong commandBufferHandle, jint gpuTimingKind
) {
	@autoreleasepool {
		MCMetalCommandBuffer *commandBuffer = (MCMetalCommandBuffer *)mc_get_object(env, commandBufferHandle, MCObjectTypeCommandBuffer);
		if (commandBuffer == nil) {
			return 0;
		}
		[commandBuffer endBlitEncoding];
		MTLComputePassDescriptor *descriptor = [MTLComputePassDescriptor computePassDescriptor];
		descriptor.dispatchType = MTLDispatchTypeSerial;
		if (gpuTimingKind >= 0 && gpuTimingKind < MC_GPU_PASS_KINDS) {
			id<MTLCounterSampleBuffer> samples = mc_gpu_pass_sample_buffer(commandBuffer.commandBuffer.device);
			if (samples != nil) {
				uint64_t sequence = atomic_fetch_add_explicit(&mc_gpu_pass_next_slot, 1, memory_order_relaxed);
				NSUInteger slot = (NSUInteger)(sequence % MC_GPU_PASS_SLOTS);
				MTLComputePassSampleBufferAttachmentDescriptor *samplePoints = descriptor.sampleBufferAttachments[0];
				samplePoints.sampleBuffer = samples;
				samplePoints.startOfEncoderSampleIndex = slot * 2;
				samplePoints.endOfEncoderSampleIndex = slot * 2 + 1;
				[commandBuffer addTimedPassKind:(uint32_t)gpuTimingKind sequence:sequence sampleBuffer:samples];
			}
		}
		id<MTLComputeCommandEncoder> encoder = [commandBuffer.commandBuffer
			computeCommandEncoderWithDescriptor:descriptor];
		if (encoder == nil) {
			mc_throw_state(env, @"Metal did not create a compute command encoder");
			return 0;
		}
		encoder.label = @"Metal Mod compute pass";
		MCMetalComputePass *pass = [[MCMetalComputePass alloc] initWithEncoder:encoder commandBuffer:commandBuffer];
		return mc_register_object(pass, MCObjectTypeComputePass, commandBufferHandle);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetComputePipeline(JNIEnv *env, jclass type, jlong passHandle, jlong pipelineHandle) {
	@autoreleasepool {
		jlong handles[] = {passHandle, pipelineHandle};
		MCObjectType types[] = {MCObjectTypeComputePass, MCObjectTypeComputePipeline};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) return;
		MCMetalComputePass *pass = objects[0];
		id<MTLComputePipelineState> pipeline = objects[1];
		[pass.commandBuffer pin:pipeline];
		[pass.encoder setComputePipelineState:pipeline];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetComputeBuffer(
	JNIEnv *env,
	jclass type,
	jlong passHandle,
	jint index,
	jlong bufferHandle,
	jlong offset
) {
	@autoreleasepool {
		jlong handles[] = {passHandle, bufferHandle};
		MCObjectType types[] = {MCObjectTypeComputePass, MCObjectTypeBuffer};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) return;
		MCMetalComputePass *pass = objects[0];
		id<MTLBuffer> buffer = objects[1];
		if (index < 0 || index >= 16 || offset < 0 || (NSUInteger)offset >= buffer.length) {
			mc_throw_state(env, @"Metal compute buffer binding index or offset is invalid");
			return;
		}
		[pass.commandBuffer pin:buffer];
		[pass.encoder setBuffer:buffer offset:(NSUInteger)offset atIndex:(NSUInteger)index];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetComputeTexture(JNIEnv *env, jclass type, jlong passHandle, jint index, jlong textureViewHandle) {
	@autoreleasepool {
		jlong handles[] = {passHandle, textureViewHandle};
		MCObjectType types[] = {MCObjectTypeComputePass, MCObjectTypeTextureView};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) return;
		if (index < 0 || index >= 16) {
			mc_throw_state(env, @"Metal compute texture binding index is invalid");
			return;
		}
		MCMetalComputePass *pass = objects[0];
		id<MTLTexture> texture = objects[1];
		[pass.commandBuffer pin:texture];
		[pass.encoder setTexture:texture atIndex:(NSUInteger)index];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetComputeSampler(JNIEnv *env, jclass type, jlong passHandle, jint index, jlong samplerHandle) {
	@autoreleasepool {
		jlong handles[] = {passHandle, samplerHandle};
		MCObjectType types[] = {MCObjectTypeComputePass, MCObjectTypeSampler};
		id objects[2];
		if (!mc_get_objects_same_device(env, handles, types, objects, 2)) return;
		if (index < 0 || index >= 16) {
			mc_throw_state(env, @"Metal compute sampler binding index is invalid");
			return;
		}
		MCMetalComputePass *pass = objects[0];
		id<MTLSamplerState> sampler = objects[1];
		[pass.commandBuffer pin:sampler];
		[pass.encoder setSamplerState:sampler atIndex:(NSUInteger)index];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nDispatchThreadgroups(
	JNIEnv *env,
	jclass type,
	jlong passHandle,
	jint groupsX,
	jint groupsY,
	jint groupsZ,
	jint threadsX,
	jint threadsY,
	jint threadsZ
) {
	@autoreleasepool {
		MCMetalComputePass *pass = (MCMetalComputePass *)mc_get_object(env, passHandle, MCObjectTypeComputePass);
		if (pass == nil) {
			return;
		}
		if (groupsX <= 0 || groupsY <= 0 || groupsZ <= 0 || threadsX <= 0 || threadsY <= 0 || threadsZ <= 0) {
			mc_throw_state(env, @"A Metal dispatch requires positive threadgroup and thread counts");
			return;
		}
		[pass.encoder
			dispatchThreadgroups:MTLSizeMake((NSUInteger)groupsX, (NSUInteger)groupsY, (NSUInteger)groupsZ)
			threadsPerThreadgroup:MTLSizeMake((NSUInteger)threadsX, (NSUInteger)threadsY, (NSUInteger)threadsZ)];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nEndComputePass(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeComputePass);
	}
}
