MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateRenderPipeline(
	JNIEnv *env,
	jclass type,
	jlong deviceHandle,
	jstring vertexSourceValue,
	jstring vertexFunctionValue,
	jstring fragmentSourceValue,
	jstring fragmentFunctionValue,
	jintArray colorFormatsValue,
	jintArray colorWriteMasksValue,
	jintArray blendEnabledValue,
	jintArray sourceColorFactorsValue,
	jintArray destinationColorFactorsValue,
	jintArray colorOperationsValue,
	jintArray sourceAlphaFactorsValue,
	jintArray destinationAlphaFactorsValue,
	jintArray alphaOperationsValue,
	jint depthStencilFormat,
	jboolean depthTestEnabled,
	jboolean depthWriteEnabled,
	jint depthCompareFunction,
	jfloat depthBiasSlopeScale,
	jfloat depthBiasConstant,
	jint cullMode,
	jint fillMode,
	jintArray attributeLocationsValue,
	jintArray attributeBufferIndicesValue,
	jintArray attributeOffsetsValue,
	jintArray attributeFormatsValue,
	jintArray layoutBufferIndicesValue,
	jintArray layoutStridesValue,
	jintArray layoutStepRatesValue,
	jint inputPrimitiveTopology
) {
	@autoreleasepool {
		if (vertexSourceValue == NULL || vertexFunctionValue == NULL || fragmentSourceValue == NULL || fragmentFunctionValue == NULL) {
			mc_throw_state(env, @"A Metal render pipeline requires vertex/fragment sources and function names");
			return 0;
		}
		if (colorFormatsValue == NULL || attributeLocationsValue == NULL || layoutBufferIndicesValue == NULL) {
			mc_throw_state(env, @"A Metal render pipeline requires color, vertex attribute, and vertex layout arrays");
			return 0;
		}

		jsize colorCount = (*env)->GetArrayLength(env, colorFormatsValue);
		jsize attributeCount = (*env)->GetArrayLength(env, attributeLocationsValue);
		jsize layoutCount = (*env)->GetArrayLength(env, layoutBufferIndicesValue);
		if (colorCount < 1 || colorCount > 8 || attributeCount > 16 || layoutCount > 16) {
			mc_throw_state(env, @"Metal pipeline descriptor counts are out of range");
			return 0;
		}

		jint colorFormats[8] = {0};
		jint colorWriteMasks[8] = {0};
		jint blendEnabled[8] = {0};
		jint sourceColorFactors[8] = {0};
		jint destinationColorFactors[8] = {0};
		jint colorOperations[8] = {0};
		jint sourceAlphaFactors[8] = {0};
		jint destinationAlphaFactors[8] = {0};
		jint alphaOperations[8] = {0};
		jint attributeLocations[16] = {0};
		jint attributeBufferIndices[16] = {0};
		jint attributeOffsets[16] = {0};
		jint attributeFormats[16] = {0};
		jint layoutBufferIndices[16] = {0};
		jint layoutStrides[16] = {0};
		jint layoutStepRates[16] = {0};
		if (!mc_read_int_array(env, colorFormatsValue, colorCount, 8, colorFormats, @"color formats")
			|| !mc_read_int_array(env, colorWriteMasksValue, colorCount, 8, colorWriteMasks, @"color write masks")
			|| !mc_read_int_array(env, blendEnabledValue, colorCount, 8, blendEnabled, @"blend enables")
			|| !mc_read_int_array(env, sourceColorFactorsValue, colorCount, 8, sourceColorFactors, @"source color factors")
			|| !mc_read_int_array(env, destinationColorFactorsValue, colorCount, 8, destinationColorFactors, @"destination color factors")
			|| !mc_read_int_array(env, colorOperationsValue, colorCount, 8, colorOperations, @"color operations")
			|| !mc_read_int_array(env, sourceAlphaFactorsValue, colorCount, 8, sourceAlphaFactors, @"source alpha factors")
			|| !mc_read_int_array(env, destinationAlphaFactorsValue, colorCount, 8, destinationAlphaFactors, @"destination alpha factors")
			|| !mc_read_int_array(env, alphaOperationsValue, colorCount, 8, alphaOperations, @"alpha operations")
			|| !mc_read_int_array(env, attributeLocationsValue, attributeCount, 16, attributeLocations, @"attribute locations")
			|| !mc_read_int_array(env, attributeBufferIndicesValue, attributeCount, 16, attributeBufferIndices, @"attribute buffer indices")
			|| !mc_read_int_array(env, attributeOffsetsValue, attributeCount, 16, attributeOffsets, @"attribute offsets")
			|| !mc_read_int_array(env, attributeFormatsValue, attributeCount, 16, attributeFormats, @"attribute formats")
			|| !mc_read_int_array(env, layoutBufferIndicesValue, layoutCount, 16, layoutBufferIndices, @"layout buffer indices")
			|| !mc_read_int_array(env, layoutStridesValue, layoutCount, 16, layoutStrides, @"layout strides")
			|| !mc_read_int_array(env, layoutStepRatesValue, layoutCount, 16, layoutStepRates, @"layout step rates")) {
			return 0;
		}

		MTLPixelFormat colorPixelFormats[8] = {MTLPixelFormatInvalid};
		for (jsize index = 0; index < colorCount; index++) {
			if (colorFormats[index] >= 0) {
				colorPixelFormats[index] = mc_pixel_format(env, colorFormats[index]);
				if (mc_pixel_format_has_depth(colorPixelFormats[index]) || mc_pixel_format_has_stencil(colorPixelFormats[index])) {
					mc_throw_state(env, @"A Metal color target cannot use a depth/stencil pixel format");
					return 0;
				}
			}
		}
		MTLPixelFormat depthStencilPixelFormat = depthStencilFormat < 0 ? MTLPixelFormatInvalid : mc_pixel_format(env, depthStencilFormat);
		MTLCompareFunction nativeCompareFunction = mc_compare_function(env, depthCompareFunction);
		MTLCullMode nativeCullMode = mc_cull_mode(env, cullMode);
		MTLTriangleFillMode nativeFillMode = mc_fill_mode(env, fillMode);
		if ((*env)->ExceptionCheck(env)) {
			return 0;
		}
		if (depthTestEnabled && !mc_pixel_format_has_depth(depthStencilPixelFormat)) {
			mc_throw_state(env, @"Enabled Metal depth state requires a depth-capable pixel format");
			return 0;
		}

		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		if (device == nil) {
			return 0;
		}

		const char *vertexSourceCharacters = (*env)->GetStringUTFChars(env, vertexSourceValue, NULL);
		const char *vertexFunctionCharacters = (*env)->GetStringUTFChars(env, vertexFunctionValue, NULL);
		const char *fragmentSourceCharacters = (*env)->GetStringUTFChars(env, fragmentSourceValue, NULL);
		const char *fragmentFunctionCharacters = (*env)->GetStringUTFChars(env, fragmentFunctionValue, NULL);
		if (vertexSourceCharacters == NULL || vertexFunctionCharacters == NULL || fragmentSourceCharacters == NULL || fragmentFunctionCharacters == NULL) {
			if (vertexSourceCharacters != NULL) {
				(*env)->ReleaseStringUTFChars(env, vertexSourceValue, vertexSourceCharacters);
			}
			if (vertexFunctionCharacters != NULL) {
				(*env)->ReleaseStringUTFChars(env, vertexFunctionValue, vertexFunctionCharacters);
			}
			if (fragmentSourceCharacters != NULL) {
				(*env)->ReleaseStringUTFChars(env, fragmentSourceValue, fragmentSourceCharacters);
			}
			if (fragmentFunctionCharacters != NULL) {
				(*env)->ReleaseStringUTFChars(env, fragmentFunctionValue, fragmentFunctionCharacters);
			}
			return 0;
		}

		NSString *vertexSource = [NSString stringWithUTF8String:vertexSourceCharacters];
		NSString *vertexFunctionName = [NSString stringWithUTF8String:vertexFunctionCharacters];
		NSString *fragmentSource = [NSString stringWithUTF8String:fragmentSourceCharacters];
		NSString *fragmentFunctionName = [NSString stringWithUTF8String:fragmentFunctionCharacters];
		(*env)->ReleaseStringUTFChars(env, vertexSourceValue, vertexSourceCharacters);
		(*env)->ReleaseStringUTFChars(env, vertexFunctionValue, vertexFunctionCharacters);
		(*env)->ReleaseStringUTFChars(env, fragmentSourceValue, fragmentSourceCharacters);
		(*env)->ReleaseStringUTFChars(env, fragmentFunctionValue, fragmentFunctionCharacters);

		NSError *vertexLibraryError = nil;
		MCLibraryEntry *vertexEntry = mc_cached_library(device, vertexSource, &vertexLibraryError);
		id<MTLLibrary> vertexLibrary = vertexEntry.library;
		if (vertexLibrary == nil) {
			mc_throw_state(env, [NSString stringWithFormat:@"Metal vertex-shader compilation failed: %@", vertexLibraryError.localizedDescription]);
			return 0;
		}
		NSError *fragmentLibraryError = nil;
        MCLibraryEntry *fragmentEntry = mc_cached_library(device, fragmentSource, &fragmentLibraryError);
        id<MTLLibrary> fragmentLibrary = fragmentEntry.library;
		if (fragmentLibrary == nil) {
			mc_throw_state(env, [NSString stringWithFormat:@"Metal fragment-shader compilation failed: %@", fragmentLibraryError.localizedDescription]);
			return 0;
		}
		id<MTLFunction> vertexFunction = mc_cached_function(vertexEntry, vertexFunctionName);
		id<MTLFunction> fragmentFunction = mc_cached_function(fragmentEntry, fragmentFunctionName);
		if (vertexFunction == nil || fragmentFunction == nil) {
			mc_throw_state(env, [NSString stringWithFormat:
				@"Metal shader libraries do not contain the requested functions %@/%@; available vertex functions %@; available fragment functions %@",
				vertexFunctionName,
				fragmentFunctionName,
				vertexLibrary.functionNames,
				fragmentLibrary.functionNames
			]);
			return 0;
		}

		MTLRenderPipelineDescriptor *descriptor = [[MTLRenderPipelineDescriptor alloc] init];
		switch (inputPrimitiveTopology) {
			case 0: descriptor.inputPrimitiveTopology = MTLPrimitiveTopologyClassUnspecified; break;
			case 1: descriptor.inputPrimitiveTopology = MTLPrimitiveTopologyClassPoint; break;
			case 2: descriptor.inputPrimitiveTopology = MTLPrimitiveTopologyClassLine; break;
			case 3: descriptor.inputPrimitiveTopology = MTLPrimitiveTopologyClassTriangle; break;
			default:
				mc_throw_state(env, @"Unknown Metal pipeline input primitive topology");
				return 0;
		}
		descriptor.label = @"Metal Mod render pipeline";
		descriptor.vertexFunction = vertexFunction;
		descriptor.fragmentFunction = fragmentFunction;
		for (jsize index = 0; index < colorCount; index++) {
			MTLRenderPipelineColorAttachmentDescriptor *color = descriptor.colorAttachments[index];
			color.pixelFormat = colorPixelFormats[index];
			color.writeMask = mc_color_write_mask(env, colorWriteMasks[index]);
			if (blendEnabled[index] != 0) {
				color.blendingEnabled = YES;
				color.sourceRGBBlendFactor = mc_blend_factor(env, sourceColorFactors[index]);
				color.destinationRGBBlendFactor = mc_blend_factor(env, destinationColorFactors[index]);
				color.rgbBlendOperation = mc_blend_operation(env, colorOperations[index]);
				color.sourceAlphaBlendFactor = mc_blend_factor(env, sourceAlphaFactors[index]);
				color.destinationAlphaBlendFactor = mc_blend_factor(env, destinationAlphaFactors[index]);
				color.alphaBlendOperation = mc_blend_operation(env, alphaOperations[index]);
			}
		}
		if ((*env)->ExceptionCheck(env)) {
			return 0;
		}
		if (mc_pixel_format_has_depth(depthStencilPixelFormat)) {
			descriptor.depthAttachmentPixelFormat = depthStencilPixelFormat;
		}
		if (mc_pixel_format_has_stencil(depthStencilPixelFormat)) {
			descriptor.stencilAttachmentPixelFormat = depthStencilPixelFormat;
		}

		if (attributeCount > 0 || layoutCount > 0) {
			MTLVertexDescriptor *vertexDescriptor = [MTLVertexDescriptor vertexDescriptor];
			for (jsize index = 0; index < layoutCount; index++) {
				jint bufferIndex = layoutBufferIndices[index];
				if (bufferIndex < 0 || bufferIndex >= 31 || layoutStrides[index] <= 0 || layoutStepRates[index] < 0) {
					mc_throw_state(env, @"Metal vertex buffer layout is out of range");
					return 0;
				}
				MTLVertexBufferLayoutDescriptor *layout = vertexDescriptor.layouts[bufferIndex];
				layout.stride = (NSUInteger)layoutStrides[index];
				layout.stepFunction = layoutStepRates[index] > 0 ? MTLVertexStepFunctionPerInstance : MTLVertexStepFunctionPerVertex;
				layout.stepRate = layoutStepRates[index] > 0 ? (NSUInteger)layoutStepRates[index] : 1;
			}
			for (jsize index = 0; index < attributeCount; index++) {
				jint location = attributeLocations[index];
				jint bufferIndex = attributeBufferIndices[index];
				if (location < 0 || location >= 16 || bufferIndex < 0 || bufferIndex >= 31 || attributeOffsets[index] < 0) {
					mc_throw_state(env, @"Metal vertex attribute is out of range");
					return 0;
				}
				MTLVertexAttributeDescriptor *attribute = vertexDescriptor.attributes[location];
				attribute.format = mc_vertex_format(env, attributeFormats[index]);
				attribute.offset = (NSUInteger)attributeOffsets[index];
				attribute.bufferIndex = (NSUInteger)bufferIndex;
			}
			if ((*env)->ExceptionCheck(env)) {
				return 0;
			}
			descriptor.vertexDescriptor = vertexDescriptor;
		}
		NSError *pipelineError = nil;
		id<MTLRenderPipelineState> pipelineState = mc_cached_render_pipeline(device, descriptor, &pipelineError);
		if (pipelineState == nil) {
			mc_throw_state(env, [NSString stringWithFormat:@"Metal render-pipeline creation failed: %@", pipelineError.localizedDescription]);
			return 0;
		}

		MTLDepthStencilDescriptor *depthDescriptor = [[MTLDepthStencilDescriptor alloc] init];
		depthDescriptor.depthCompareFunction = depthTestEnabled ? nativeCompareFunction : MTLCompareFunctionAlways;
		depthDescriptor.depthWriteEnabled = depthTestEnabled && depthWriteEnabled;
		depthDescriptor.label = @"Metal Mod depth-stencil state";
		id<MTLDepthStencilState> depthStencilState = [device newDepthStencilStateWithDescriptor:depthDescriptor];
		if (depthStencilState == nil) {
			mc_throw_state(env, @"Metal could not create the requested depth-stencil state");
			return 0;
		}
		MCMetalRenderPipeline *pipeline = [[MCMetalRenderPipeline alloc]
			initWithPipelineState:pipelineState
			depthStencilState:depthStencilState
			cullMode:nativeCullMode
			fillMode:nativeFillMode
			depthBiasSlopeScale:depthBiasSlopeScale
			depthBiasConstant:depthBiasConstant
		];
		return mc_register_object(pipeline, MCObjectTypeRenderPipeline, deviceHandle);
	}
}
