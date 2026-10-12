MC_EXPORT JNIEXPORT jboolean JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nIsSupported(JNIEnv *env, jclass type) {
	@autoreleasepool {
		return MTLCreateSystemDefaultDevice() != nil ? JNI_TRUE : JNI_FALSE;
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateDefaultDevice(JNIEnv *env, jclass type, jstring cacheDirectory) {
	@autoreleasepool {
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device == nil) return 0;
        const char *characters = cacheDirectory == NULL ? NULL : (*env)->GetStringUTFChars(env, cacheDirectory, NULL);
        if (cacheDirectory != NULL && characters == NULL) return 0;
        NSString *directory = characters == NULL ? @"" : [NSString stringWithUTF8String:characters];
        if (characters != NULL) (*env)->ReleaseStringUTFChars(env, cacheDirectory, characters);
        @synchronized(device) {
            MCPipelineCache *cache = objc_getAssociatedObject(device, &MCPipelineCacheKey);
            if (cache == nil) {
                cache = [[MCPipelineCache alloc] initWithDevice:device directory:directory];
                objc_setAssociatedObject(device, &MCPipelineCacheKey, cache, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            }
            cache->owners++;
        }
        return mc_register_object(device, MCObjectTypeDevice, 0);
	}
}

MC_EXPORT JNIEXPORT jlongArray JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nPipelineCacheStats(JNIEnv *env, jclass type, jlong handle) {
    @autoreleasepool {
        id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, handle, MCObjectTypeDevice);
        if (device == nil) return NULL;
        MCPipelineCache *cache = objc_getAssociatedObject(device, &MCPipelineCacheKey);
        jlong values[5];
        @synchronized(cache) {
            values[0] = cache->libraryHits; values[1] = cache->libraryMisses;
            values[2] = cache->archiveHits; values[3] = cache->archiveAdds; values[4] = cache->archiveLoaded;
        }
        jlongArray result = (*env)->NewLongArray(env, 5);
        if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, 5, values);
        return result;
    }
}

MC_EXPORT JNIEXPORT jstring JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nDeviceName(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, handle, MCObjectTypeDevice);
		if (device == nil) {
			return NULL;
		}
		return (*env)->NewStringUTF(env, device.name.UTF8String);
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nRecommendedWorkingSet(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, handle, MCObjectTypeDevice);
		return device == nil ? 0 : (jlong)device.recommendedMaxWorkingSetSize;
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCurrentAllocatedSize(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, handle, MCObjectTypeDevice);
		return device == nil ? 0 : (jlong)device.currentAllocatedSize;
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nStartAllocationProbe(JNIEnv *env, jclass type, jlong handle) {
    @autoreleasepool {
        id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, handle, MCObjectTypeDevice);
        if (device == nil) return;
        objc_setAssociatedObject(device, &MCAllocationProbeKey, [MCAllocationProbe new], OBJC_ASSOCIATION_RETAIN);
        mc_sample_allocation(device);
    }
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nStopAllocationProbe(JNIEnv *env, jclass type, jlong handle) {
    @autoreleasepool {
        id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, handle, MCObjectTypeDevice);
        if (device != nil) objc_setAssociatedObject(device, &MCAllocationProbeKey, nil, OBJC_ASSOCIATION_RETAIN);
    }
}

MC_EXPORT JNIEXPORT jlongArray JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nAllocationProbe(JNIEnv *env, jclass type, jlong handle) {
    @autoreleasepool {
        id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, handle, MCObjectTypeDevice);
        if (device == nil) return NULL;
        MCAllocationProbe *probe = objc_getAssociatedObject(device, &MCAllocationProbeKey);
        jlong values[3] = {0, 0, 0};
        if (probe != nil) {
            values[0] = (jlong)mc_sample_allocation(device);
            values[1] = (jlong)atomic_load_explicit(&probe->peak, memory_order_relaxed);
            values[2] = (jlong)atomic_load_explicit(&probe->samples, memory_order_relaxed);
        }
        jlongArray result = (*env)->NewLongArray(env, 3);
        if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, 3, values);
        return result;
    }
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateCommandQueue(JNIEnv *env, jclass type, jlong deviceHandle) {
	@autoreleasepool {
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		if (device == nil) {
			return 0;
		}
		id<MTLCommandQueue> commandQueue = [device newCommandQueue];
		if (commandQueue == nil) {
			mc_throw_state(env, @"Metal did not create a command queue for the selected device");
			return 0;
		}
		commandQueue.label = @"Metal Mod primary command queue";
		objc_setAssociatedObject(commandQueue, &MCCommandScratchKey, [[MCCommandScratch alloc] init], OBJC_ASSOCIATION_RETAIN_NONATOMIC);
		return mc_register_object(commandQueue, MCObjectTypeCommandQueue, deviceHandle);
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateCommandBuffer(JNIEnv *env, jclass type, jlong queueHandle) {
	@autoreleasepool {
		id<MTLCommandQueue> commandQueue = (id<MTLCommandQueue>)mc_get_object(env, queueHandle, MCObjectTypeCommandQueue);
		if (commandQueue == nil) {
			return 0;
		}
		id<MTLCommandBuffer> nativeCommandBuffer = [commandQueue commandBuffer];
		if (nativeCommandBuffer == nil) {
			mc_throw_state(env, @"Metal did not create a command buffer");
			return 0;
		}
		nativeCommandBuffer.label = @"Metal Mod frame command buffer";
		MCMetalCommandBuffer *commandBuffer = [[MCMetalCommandBuffer alloc] initWithCommandBuffer:nativeCommandBuffer];
		return mc_register_object(commandBuffer, MCObjectTypeCommandBuffer, queueHandle);
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateSurface(
	JNIEnv *env,
	jclass type,
	jlong deviceHandle,
	jlong cocoaViewHandle,
	jint width,
	jint height
) {
	@autoreleasepool {
		if (!mc_require_main_thread(env, @"Attaching a CAMetalLayer")) {
			return 0;
		}
		if (cocoaViewHandle == 0 || width <= 0 || height <= 0) {
			mc_throw_state(env, @"A Metal surface requires a Cocoa view and a positive drawable size");
			return 0;
		}
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		if (device == nil) {
			return 0;
		}
		NSView *view = (__bridge NSView *)(void *)cocoaViewHandle;
		if (![view isKindOfClass:NSView.class]) {
			mc_throw_state(env, @"GLFW did not return a valid Cocoa content view");
			return 0;
		}
		MCMetalSurface *surface = [[MCMetalSurface alloc]
			initWithDevice:device
			view:view
			width:(NSUInteger)width
			height:(NSUInteger)height
		];
		return mc_register_object(surface, MCObjectTypeSurface, deviceHandle);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nResizeSurface(JNIEnv *env, jclass type, jlong handle, jint width, jint height) {
	@autoreleasepool {
		if (!mc_require_main_thread(env, @"Resizing a CAMetalLayer")) {
			return;
		}
		if (width <= 0 || height <= 0) {
			mc_throw_state(env, @"A Metal surface requires a positive drawable size");
			return;
		}
		MCMetalSurface *surface = (MCMetalSurface *)mc_get_object(env, handle, MCObjectTypeSurface);
		[surface resizeToWidth:(NSUInteger)width height:(NSUInteger)height];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSetSurfaceDisplaySync(JNIEnv *env, jclass type, jlong handle, jboolean enabled) {
	@autoreleasepool {
		if (!mc_require_main_thread(env, @"Configuring CAMetalLayer display synchronization")) {
			return;
		}
		MCMetalSurface *surface = (MCMetalSurface *)mc_get_object(env, handle, MCObjectTypeSurface);
		if (surface != nil) {
			surface.layer.displaySyncEnabled = enabled == JNI_TRUE;
		}
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nAcquireDrawable(JNIEnv *env, jclass type, jlong surfaceHandle) {
	@autoreleasepool {
		MCMetalSurface *surface = (MCMetalSurface *)mc_get_object(env, surfaceHandle, MCObjectTypeSurface);
		if (surface == nil) {
			return 0;
		}
		id<CAMetalDrawable> drawable = [surface.layer nextDrawable];
		mc_sample_allocation(surface.layer.device);
		return drawable == nil ? 0 : mc_register_object(drawable, MCObjectTypeDrawable, surfaceHandle);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nPresentDrawable(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong drawableHandle
) {
	@autoreleasepool {
		MCMetalCommandBuffer *commandBuffer;
		id<CAMetalDrawable> drawable;
		if (mc_get_present_objects(env, commandBufferHandle, drawableHandle, &commandBuffer, &drawable)) {
			[commandBuffer endBlitEncoding];
			[commandBuffer pin:drawable];
			[commandBuffer.commandBuffer presentDrawable:drawable];
		}
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nTakeGpuWork(JNIEnv *env, jclass type, jlongArray destination) {
	if (destination == NULL || (*env)->GetArrayLength(env, destination) < 2) {
		mc_throw_state(env, @"Draining Metal GPU timing needs an array of at least two values");
		return;
	}
	jlong values[2];
	values[0] = (jlong)atomic_exchange_explicit(&mc_gpu_nanos, 0, memory_order_relaxed);
	values[1] = (jlong)atomic_exchange_explicit(&mc_gpu_command_buffers, 0, memory_order_relaxed);
	(*env)->SetLongArrayRegion(env, destination, 0, 2, values);
}

// Called only by the benchmark on the AppKit/render thread, never by normal frames.
MC_EXPORT JNIEXPORT jint JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nWindowPresentationState(JNIEnv *env, jclass type, jlong cocoaWindow) {
	if (cocoaWindow == 0 || !NSThread.isMainThread) return 0;
	NSWindow *window = (__bridge NSWindow *)(void *)(uintptr_t)cocoaWindow;
	return (NSApp.isActive ? 1 : 0) | (window.isVisible ? 2 : 0)
		| (!window.isMiniaturized ? 4 : 0)
		| ((window.occlusionState & NSWindowOcclusionStateVisible) != 0 ? 8 : 0);
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nBeginGpuFrameCapture(JNIEnv *env, jclass type) {
	os_unfair_lock_lock(&mc_gpu_capture_lock);
	mc_gpu_capture_epoch++;
	mc_gpu_capture_count = 0;
	memset(mc_gpu_capture_frames, 0, sizeof(mc_gpu_capture_frames));
	mc_gpu_capture_active = YES;
	mc_gpu_thread_epoch = mc_gpu_capture_epoch;
	mc_gpu_thread_frame = 0;
	os_unfair_lock_unlock(&mc_gpu_capture_lock);
}

MC_EXPORT JNIEXPORT jlongArray JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nProcessMemoryAndThermalState(JNIEnv *env, jclass type) {
	task_vm_info_data_t info;
	mach_msg_type_number_t count = TASK_VM_INFO_COUNT;
	jlong values[5] = {-1, -1, -1, -1, (jlong)NSProcessInfo.processInfo.thermalState};
	if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&info, &count) == KERN_SUCCESS) {
		values[0] = (jlong)info.resident_size;
		values[1] = (jlong)info.resident_size_peak;
		if (count >= TASK_VM_INFO_REV1_COUNT) values[2] = (jlong)info.phys_footprint;
		if (count >= TASK_VM_INFO_REV3_COUNT) values[3] = (jlong)info.ledger_phys_footprint_peak;
	}
	jlongArray result = (*env)->NewLongArray(env, 5);
	if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, 5, values);
	return result;
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nBeginGpuCaptureFrame(JNIEnv *env, jclass type) {
	os_unfair_lock_lock(&mc_gpu_capture_lock);
	if (mc_gpu_capture_active && mc_gpu_thread_epoch == mc_gpu_capture_epoch) {
		mc_gpu_thread_frame = ++mc_gpu_capture_count;
	}
	os_unfair_lock_unlock(&mc_gpu_capture_lock);
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nEndGpuCaptureFrame(JNIEnv *env, jclass type) {
	os_unfair_lock_lock(&mc_gpu_capture_lock);
	if (mc_gpu_capture_active && mc_gpu_thread_epoch == mc_gpu_capture_epoch
		&& mc_gpu_thread_frame > 0 && mc_gpu_thread_frame <= MC_GPU_CAPTURE_FRAMES) {
		mc_gpu_capture_frames[mc_gpu_thread_frame - 1].sealed = YES;
	}
	mc_gpu_thread_frame = 0;
	os_unfair_lock_unlock(&mc_gpu_capture_lock);
}

MC_EXPORT JNIEXPORT jlongArray JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nEndGpuFrameCapture(JNIEnv *env, jclass type) {
	// Bounded temporary allocation occurs only when the benchmark stops, outside its samples.
	jlong *values = calloc(5 + MC_GPU_CAPTURE_FRAMES * 2, sizeof(jlong));
	if (values == NULL) { mc_throw_state(env, @"Cannot allocate GPU capture report"); return NULL; }
	jsize length = 5;
	os_unfair_lock_lock(&mc_gpu_capture_lock);
	mc_gpu_capture_active = NO;
	mc_gpu_thread_frame = 0;
	values[0] = (jlong)mc_gpu_capture_count;
	values[4] = (jlong)(mc_gpu_capture_count > MC_GPU_CAPTURE_FRAMES ? mc_gpu_capture_count - MC_GPU_CAPTURE_FRAMES : 0);
	for (uint64_t i = 0; i < MIN(mc_gpu_capture_count, MC_GPU_CAPTURE_FRAMES); i++) {
		MCGpuCaptureFrame *frame = &mc_gpu_capture_frames[i];
		if (!frame->sealed || frame->pending > 0) values[1]++;
		else if (frame->invalid) values[2]++;
		else if (frame->submitted == 0 || frame->end <= frame->start) values[3]++;
		else {
			values[length++] = (jlong)(frame->end - frame->start);
			values[length++] = (jlong)frame->submitted;
		}
	}
	os_unfair_lock_unlock(&mc_gpu_capture_lock);
	jlongArray result = (*env)->NewLongArray(env, length);
	if (result != NULL) (*env)->SetLongArrayRegion(env, result, 0, length, values);
	free(values);
	return result;
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nTakeGpuPassWork(JNIEnv *env, jclass type, jlongArray destination) {
	jsize expected = MC_GPU_PASS_KINDS * 2;
	if (destination == NULL || (*env)->GetArrayLength(env, destination) < expected) {
		mc_throw_state(env, @"Draining Metal pass GPU timing needs an array of two values per kind");
		return;
	}
	jlong values[MC_GPU_PASS_KINDS * 2];
	for (int kind = 0; kind < MC_GPU_PASS_KINDS; kind++) {
		values[kind * 2] = (jlong)atomic_exchange_explicit(&mc_gpu_pass_nanos[kind], 0, memory_order_relaxed);
		values[kind * 2 + 1] = (jlong)atomic_exchange_explicit(&mc_gpu_pass_counts[kind], 0, memory_order_relaxed);
	}
	(*env)->SetLongArrayRegion(env, destination, 0, expected, values);
}

MC_EXPORT JNIEXPORT jint JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nGpuPassKinds(JNIEnv *env, jclass type) {
	return MC_GPU_PASS_KINDS;
}

MC_EXPORT JNIEXPORT jboolean JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSupportsPassGpuTiming(JNIEnv *env, jclass type, jlong deviceHandle) {
	@autoreleasepool {
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		return device != nil
			&& mc_timestamp_counter_set(device) != nil
			&& [device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]
			? JNI_TRUE : JNI_FALSE;
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCommitCommandBuffer(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		MCMetalCommandBuffer *commandBuffer = (MCMetalCommandBuffer *)mc_get_object(env, handle, MCObjectTypeCommandBuffer);
		[commandBuffer endBlitEncoding];
		mc_capture_command_buffer(commandBuffer.commandBuffer);
		[commandBuffer.commandBuffer commit];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nWaitForCommandBuffer(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		MCMetalCommandBuffer *commandBuffer = (MCMetalCommandBuffer *)mc_get_object(env, handle, MCObjectTypeCommandBuffer);
		[commandBuffer.commandBuffer waitUntilCompleted];
		if (commandBuffer.commandBuffer.status == MTLCommandBufferStatusError) {
			mc_throw_state(env, [NSString stringWithFormat:@"Metal command buffer failed: %@", commandBuffer.commandBuffer.error.localizedDescription]);
		}
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateCommandCompletion(JNIEnv *env, jclass type, jlong handle, jlong deviceHandle) {
    @autoreleasepool {
        jlong handles[] = {handle, deviceHandle};
        MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeDevice};
        id objects[2];
        if (!mc_get_objects_same_device(env, handles, types, objects, 2)) return 0;
        MCMetalCommandBuffer *commands = objects[0];
        return mc_register_object(commands.commandBuffer, MCObjectTypeCommandCompletion, deviceHandle);
    }
}

MC_EXPORT JNIEXPORT jboolean JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nPollCommandCompletion(JNIEnv *env, jclass type, jlong handle, jboolean wait) {
    @autoreleasepool {
        id<MTLCommandBuffer> commands = (id<MTLCommandBuffer>)mc_get_object(env, handle, MCObjectTypeCommandCompletion);
        if (commands == nil) return JNI_FALSE;
        if (wait) [commands waitUntilCompleted];
        if (commands.status == MTLCommandBufferStatusError) {
            mc_throw_state(env, [NSString stringWithFormat:@"Metal readback failed: %@", commands.error.localizedDescription]);
            return JNI_FALSE;
        }
        return commands.status == MTLCommandBufferStatusCompleted ? JNI_TRUE : JNI_FALSE;
    }
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseCommandCompletion(JNIEnv *env, jclass type, jlong handle) {
    @autoreleasepool { mc_release_object(env, handle, MCObjectTypeCommandCompletion); }
}
