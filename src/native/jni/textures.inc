MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateTexture(
	JNIEnv *env,
	jclass type,
	jlong deviceHandle,
	jint format,
	jint width,
	jint height,
	jint depthOrLayers,
	jint mipLevels,
	jint usage,
	jboolean cubemap,
	jboolean memoryless
) {
	@autoreleasepool {
		BOOL isCubemap = cubemap == JNI_TRUE;
		BOOL isMemoryless = memoryless == JNI_TRUE;
		BOOL validShape = isCubemap ? width == height && depthOrLayers == 6 : depthOrLayers >= 1;
		if (width <= 0 || height <= 0 || mipLevels <= 0 || !validShape || (usage & ~7) != 0) {
			mc_throw_state(env, @"A Metal texture requires positive dimensions, mip levels, and valid usage bits");
			return 0;
		}
		if (isMemoryless && (usage != 4 || mipLevels != 1 || isCubemap || depthOrLayers != 1)) {
			mc_throw_state(env, @"A memoryless Metal texture must be a single-level two-dimensional render target");
			return 0;
		}
		MTLPixelFormat pixelFormat = mc_pixel_format(env, format);
		if (pixelFormat == MTLPixelFormatInvalid) {
			return 0;
		}
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		if (device == nil) {
			return 0;
		}
		if (isMemoryless && ![device supportsFamily:MTLGPUFamilyApple1]) {
			mc_throw_state(env, @"This Metal device has no tile memory, so it cannot allocate a memoryless texture");
			return 0;
		}
		BOOL isArray = !isCubemap && depthOrLayers > 1;
		MTLTextureDescriptor *descriptor = [[MTLTextureDescriptor alloc] init];
		descriptor.textureType = isCubemap ? MTLTextureTypeCube : (isArray ? MTLTextureType2DArray : MTLTextureType2D);
		descriptor.pixelFormat = pixelFormat;
		descriptor.width = (NSUInteger)width;
		descriptor.height = (NSUInteger)height;
		descriptor.depth = 1;
		descriptor.mipmapLevelCount = (NSUInteger)mipLevels;
		descriptor.arrayLength = isCubemap ? 1 : (NSUInteger)depthOrLayers;
		descriptor.sampleCount = 1;
		descriptor.storageMode = isMemoryless ? MTLStorageModeMemoryless : MTLStorageModePrivate;
		descriptor.usage = MTLTextureUsageUnknown;
		if ((usage & 1) != 0) descriptor.usage |= MTLTextureUsageShaderRead;
		if ((usage & 2) != 0) descriptor.usage |= MTLTextureUsageShaderWrite;
		if ((usage & 4) != 0) descriptor.usage |= MTLTextureUsageRenderTarget;
		id<MTLTexture> texture = [device newTextureWithDescriptor:descriptor];
		if (texture == nil) {
			mc_throw_state(env, @"Metal could not allocate the requested texture");
			return 0;
		}
		mc_sample_allocation(device);
		texture.label = @"Metal Mod texture";
		return mc_register_object(texture, MCObjectTypeTexture, deviceHandle);
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateTextureView(
	JNIEnv *env,
	jclass type,
	jlong textureHandle,
	jint baseMipLevel,
	jint mipLevels
) {
	@autoreleasepool {
		id<MTLTexture> texture = (id<MTLTexture>)mc_get_object(env, textureHandle, MCObjectTypeTexture);
		if (texture == nil) {
			return 0;
		}
		if (baseMipLevel < 0 || mipLevels <= 0 || (NSUInteger)baseMipLevel > texture.mipmapLevelCount
			|| (NSUInteger)mipLevels > texture.mipmapLevelCount - (NSUInteger)baseMipLevel) {
			mc_throw_state(env, @"Metal texture-view mip range is out of bounds");
			return 0;
		}
		NSUInteger sliceCount = texture.textureType == MTLTextureTypeCube ? 6 : texture.arrayLength;
		id<MTLTexture> textureView = [texture
			newTextureViewWithPixelFormat:texture.pixelFormat
			textureType:texture.textureType
			levels:NSMakeRange((NSUInteger)baseMipLevel, (NSUInteger)mipLevels)
			slices:NSMakeRange(0, sliceCount)
		];
		if (textureView == nil) {
			mc_throw_state(env, @"Metal could not create a texture view");
			return 0;
		}
		mc_sample_allocation(texture.device);
		textureView.label = @"Metal Mod texture view";
		return mc_register_object(textureView, MCObjectTypeTextureView, textureHandle);
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateSampler(
	JNIEnv *env,
	jclass type,
	jlong deviceHandle,
	jint minFilter,
	jint magFilter,
	jint addressModeU,
	jint addressModeV,
	jint maxAnisotropy,
	jdouble maxLod,
	jdouble minLod
) {
	@autoreleasepool {
		if ((minFilter != 0 && minFilter != 1) || (magFilter != 0 && magFilter != 1)
			|| addressModeU < 0 || addressModeU > 2 || addressModeV < 0 || addressModeV > 2
			|| maxAnisotropy < 1 || maxAnisotropy > 16 || isnan(maxLod) || maxLod < 0.0
			|| !isfinite(minLod) || minLod < 0.0 || minLod > maxLod) {
			mc_throw_state(env, @"Unsupported Metal sampler configuration");
			return 0;
		}
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		if (device == nil) {
			return 0;
		}
		MTLSamplerDescriptor *descriptor = [[MTLSamplerDescriptor alloc] init];
		descriptor.minFilter = minFilter == 0 ? MTLSamplerMinMagFilterNearest : MTLSamplerMinMagFilterLinear;
		descriptor.magFilter = magFilter == 0 ? MTLSamplerMinMagFilterNearest : MTLSamplerMinMagFilterLinear;
		MTLSamplerAddressMode nativeAddressModeU = addressModeU == 0
			? MTLSamplerAddressModeClampToEdge
			: (addressModeU == 1 ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeMirrorRepeat);
		MTLSamplerAddressMode nativeAddressModeV = addressModeV == 0
			? MTLSamplerAddressModeClampToEdge
			: (addressModeV == 1 ? MTLSamplerAddressModeRepeat : MTLSamplerAddressModeMirrorRepeat);
		descriptor.sAddressMode = nativeAddressModeU;
		descriptor.tAddressMode = nativeAddressModeV;
		descriptor.rAddressMode = nativeAddressModeV;
		descriptor.mipFilter = maxLod > 0.25 ? MTLSamplerMipFilterLinear : MTLSamplerMipFilterNearest;
		descriptor.maxAnisotropy = (NSUInteger)maxAnisotropy;
		descriptor.lodMaxClamp = isinf(maxLod) ? FLT_MAX : (float)maxLod;
		descriptor.lodMinClamp = (float)minLod;
		descriptor.label = @"Metal Mod sampler";
		id<MTLSamplerState> sampler = [device newSamplerStateWithDescriptor:descriptor];
		if (sampler == nil) {
			mc_throw_state(env, @"Metal could not create the requested sampler");
			return 0;
		}
		return mc_register_object(sampler, MCObjectTypeSampler, deviceHandle);
	}
}
