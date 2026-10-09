MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseDevice(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
        id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, handle, MCObjectTypeDevice);
        if (device == nil) return;
        mc_release_object(env, handle, MCObjectTypeDevice);
        if ((*env)->ExceptionCheck(env)) return;
        @synchronized(device) {
            MCPipelineCache *cache = objc_getAssociatedObject(device, &MCPipelineCacheKey);
            if (cache != nil && --cache->owners == 0) {
                @synchronized(cache) { [cache save]; }
                // Break library/archive -> device ownership cycles at the last wrapper close.
                objc_setAssociatedObject(device, &MCPipelineCacheKey, nil, OBJC_ASSOCIATION_RETAIN_NONATOMIC);
            }
        }
        mc_gpu_pass_reset();
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseCommandQueue(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeCommandQueue);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseCommandBuffer(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		MCMetalCommandBuffer *commandBuffer = (MCMetalCommandBuffer *)mc_get_object(env, handle, MCObjectTypeCommandBuffer);
		[commandBuffer endBlitEncoding];
		mc_release_object(env, handle, MCObjectTypeCommandBuffer);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseSurface(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		if (mc_require_main_thread(env, @"Detaching a CAMetalLayer")) {
			mc_release_object(env, handle, MCObjectTypeSurface);
		}
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseDrawable(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeDrawable);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseBuffer(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeBuffer);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseTexture(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeTexture);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseTextureView(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeTextureView);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseSampler(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeSampler);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseFence(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeFence);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseTimestampQueryPool(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeTimestampQueryPool);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseRenderPipeline(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeRenderPipeline);
	}
}
