static MTLPixelFormat mc_pixel_format(JNIEnv *env, jint format) {
	switch (format) {
		case 0:
			return MTLPixelFormatBGRA8Unorm;
		case 1:
			return MTLPixelFormatR8Unorm;
		case 2:
			return MTLPixelFormatR8Snorm;
		case 3:
			return MTLPixelFormatR8Uint;
		case 4:
			return MTLPixelFormatR8Sint;
		case 5:
			return MTLPixelFormatRG8Unorm;
		case 6:
			return MTLPixelFormatRG8Snorm;
		case 7:
			return MTLPixelFormatRG8Uint;
		case 8:
			return MTLPixelFormatRG8Sint;
		case 9:
			return MTLPixelFormatRGBA8Unorm;
		case 10:
			return MTLPixelFormatRGBA8Snorm;
		case 11:
			return MTLPixelFormatRGBA8Uint;
		case 12:
			return MTLPixelFormatRGBA8Sint;
		case 13:
			return MTLPixelFormatR16Unorm;
		case 14:
			return MTLPixelFormatR16Snorm;
		case 15:
			return MTLPixelFormatR16Uint;
		case 16:
			return MTLPixelFormatR16Sint;
		case 17:
			return MTLPixelFormatR16Float;
		case 18:
			return MTLPixelFormatRG16Unorm;
		case 19:
			return MTLPixelFormatRG16Snorm;
		case 20:
			return MTLPixelFormatRG16Uint;
		case 21:
			return MTLPixelFormatRG16Sint;
		case 22:
			return MTLPixelFormatRG16Float;
		case 23:
			return MTLPixelFormatRGBA16Unorm;
		case 24:
			return MTLPixelFormatRGBA16Snorm;
		case 25:
			return MTLPixelFormatRGBA16Uint;
		case 26:
			return MTLPixelFormatRGBA16Sint;
		case 27:
			return MTLPixelFormatRGBA16Float;
		case 28:
			return MTLPixelFormatR32Uint;
		case 29:
			return MTLPixelFormatR32Sint;
		case 30:
			return MTLPixelFormatR32Float;
		case 31:
			return MTLPixelFormatRG32Uint;
		case 32:
			return MTLPixelFormatRG32Sint;
		case 33:
			return MTLPixelFormatRG32Float;
		case 34:
			return MTLPixelFormatRGBA32Uint;
		case 35:
			return MTLPixelFormatRGBA32Sint;
		case 36:
			return MTLPixelFormatRGBA32Float;
		case 37:
			return MTLPixelFormatRGB10A2Unorm;
		case 38:
			return MTLPixelFormatRGB10A2Uint;
		case 39:
			return MTLPixelFormatRG11B10Float;
		case 40:
			return MTLPixelFormatDepth16Unorm;
		case 41:
			return MTLPixelFormatDepth32Float;
		case 42:
			return MTLPixelFormatStencil8;
		case 43:
			return MTLPixelFormatDepth24Unorm_Stencil8;
		case 44:
			return MTLPixelFormatDepth32Float_Stencil8;
		default:
			mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal texture format index %d", format]);
			return MTLPixelFormatInvalid;
	}
}

static NSUInteger mc_bytes_per_pixel(MTLPixelFormat format) {
	switch (format) {
		case MTLPixelFormatR8Unorm:
		case MTLPixelFormatR8Snorm:
		case MTLPixelFormatR8Uint:
		case MTLPixelFormatR8Sint:
		case MTLPixelFormatStencil8:
			return 1;
		case MTLPixelFormatRG8Unorm:
		case MTLPixelFormatRG8Snorm:
		case MTLPixelFormatRG8Uint:
		case MTLPixelFormatRG8Sint:
		case MTLPixelFormatR16Unorm:
		case MTLPixelFormatR16Snorm:
		case MTLPixelFormatR16Uint:
		case MTLPixelFormatR16Sint:
		case MTLPixelFormatR16Float:
		case MTLPixelFormatDepth16Unorm:
			return 2;
		case MTLPixelFormatRGBA8Snorm:
		case MTLPixelFormatRGBA8Uint:
		case MTLPixelFormatRGBA8Sint:
		case MTLPixelFormatRG16Unorm:
		case MTLPixelFormatRG16Snorm:
		case MTLPixelFormatRG16Uint:
		case MTLPixelFormatRG16Sint:
		case MTLPixelFormatRG16Float:
		case MTLPixelFormatR32Uint:
		case MTLPixelFormatR32Sint:
		case MTLPixelFormatR32Float:
		case MTLPixelFormatRGB10A2Unorm:
		case MTLPixelFormatRGB10A2Uint:
		case MTLPixelFormatRG11B10Float:
		case MTLPixelFormatDepth24Unorm_Stencil8:
		case MTLPixelFormatBGRA8Unorm:
		case MTLPixelFormatRGBA8Unorm:
		case MTLPixelFormatDepth32Float:
			return 4;
		case MTLPixelFormatRGBA16Unorm:
		case MTLPixelFormatRGBA16Snorm:
		case MTLPixelFormatRGBA16Uint:
		case MTLPixelFormatRGBA16Sint:
		case MTLPixelFormatRGBA16Float:
		case MTLPixelFormatRG32Uint:
		case MTLPixelFormatRG32Sint:
		case MTLPixelFormatRG32Float:
		case MTLPixelFormatDepth32Float_Stencil8:
			return 8;
		case MTLPixelFormatRGBA32Uint:
		case MTLPixelFormatRGBA32Sint:
		case MTLPixelFormatRGBA32Float:
			return 16;
		default:
			return 0;
	}
}

static BOOL mc_pixel_format_has_depth(MTLPixelFormat format) {
	return format == MTLPixelFormatDepth16Unorm
		|| format == MTLPixelFormatDepth32Float
		|| format == MTLPixelFormatDepth24Unorm_Stencil8
		|| format == MTLPixelFormatDepth32Float_Stencil8;
}

static BOOL mc_pixel_format_has_stencil(MTLPixelFormat format) {
	return format == MTLPixelFormatStencil8
		|| format == MTLPixelFormatDepth24Unorm_Stencil8
		|| format == MTLPixelFormatDepth32Float_Stencil8;
}

static MTLVertexFormat mc_vertex_format(JNIEnv *env, jint format) {
	switch (format) {
		case 0: return MTLVertexFormatUChar;
		case 1: return MTLVertexFormatUChar2;
		case 2: return MTLVertexFormatUChar3;
		case 3: return MTLVertexFormatUChar4;
		case 4: return MTLVertexFormatChar;
		case 5: return MTLVertexFormatChar2;
		case 6: return MTLVertexFormatChar3;
		case 7: return MTLVertexFormatChar4;
		case 8: return MTLVertexFormatUCharNormalized;
		case 9: return MTLVertexFormatUChar2Normalized;
		case 10: return MTLVertexFormatUChar3Normalized;
		case 11: return MTLVertexFormatUChar4Normalized;
		case 12: return MTLVertexFormatCharNormalized;
		case 13: return MTLVertexFormatChar2Normalized;
		case 14: return MTLVertexFormatChar3Normalized;
		case 15: return MTLVertexFormatChar4Normalized;
		case 16: return MTLVertexFormatUShort;
		case 17: return MTLVertexFormatUShort2;
		case 18: return MTLVertexFormatUShort3;
		case 19: return MTLVertexFormatUShort4;
		case 20: return MTLVertexFormatShort;
		case 21: return MTLVertexFormatShort2;
		case 22: return MTLVertexFormatShort3;
		case 23: return MTLVertexFormatShort4;
		case 24: return MTLVertexFormatUShortNormalized;
		case 25: return MTLVertexFormatUShort2Normalized;
		case 26: return MTLVertexFormatUShort3Normalized;
		case 27: return MTLVertexFormatUShort4Normalized;
		case 28: return MTLVertexFormatShortNormalized;
		case 29: return MTLVertexFormatShort2Normalized;
		case 30: return MTLVertexFormatShort3Normalized;
		case 31: return MTLVertexFormatShort4Normalized;
		case 32: return MTLVertexFormatHalf;
		case 33: return MTLVertexFormatHalf2;
		case 34: return MTLVertexFormatHalf3;
		case 35: return MTLVertexFormatHalf4;
		case 36: return MTLVertexFormatUInt;
		case 37: return MTLVertexFormatUInt2;
		case 38: return MTLVertexFormatUInt3;
		case 39: return MTLVertexFormatUInt4;
		case 40: return MTLVertexFormatInt;
		case 41: return MTLVertexFormatInt2;
		case 42: return MTLVertexFormatInt3;
		case 43: return MTLVertexFormatInt4;
		case 44: return MTLVertexFormatFloat;
		case 45: return MTLVertexFormatFloat2;
		case 46: return MTLVertexFormatFloat3;
		case 47: return MTLVertexFormatFloat4;
		case 48: return MTLVertexFormatUInt1010102Normalized;
		case 49:
			if (@available(macOS 14.0, *)) {
				return MTLVertexFormatFloatRG11B10;
			}
			mc_throw_state(env, @"Metal RG11B10 vertex attributes require macOS 14 or newer");
			return MTLVertexFormatInvalid;
		default:
			mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal vertex format index %d", format]);
			return MTLVertexFormatInvalid;
	}
}

static MTLBlendFactor mc_blend_factor(JNIEnv *env, jint factor) {
	switch (factor) {
		case 0: return MTLBlendFactorZero;
		case 1: return MTLBlendFactorOne;
		case 2: return MTLBlendFactorSourceColor;
		case 3: return MTLBlendFactorOneMinusSourceColor;
		case 4: return MTLBlendFactorSourceAlpha;
		case 5: return MTLBlendFactorOneMinusSourceAlpha;
		case 6: return MTLBlendFactorDestinationColor;
		case 7: return MTLBlendFactorOneMinusDestinationColor;
		case 8: return MTLBlendFactorDestinationAlpha;
		case 9: return MTLBlendFactorOneMinusDestinationAlpha;
		case 10: return MTLBlendFactorSourceAlphaSaturated;
		case 11: return MTLBlendFactorBlendColor;
		case 12: return MTLBlendFactorOneMinusBlendColor;
		case 13: return MTLBlendFactorBlendAlpha;
		case 14: return MTLBlendFactorOneMinusBlendAlpha;
		default:
			mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal blend factor index %d", factor]);
			return MTLBlendFactorZero;
	}
}

static MTLBlendOperation mc_blend_operation(JNIEnv *env, jint operation) {
	switch (operation) {
		case 0: return MTLBlendOperationAdd;
		case 1: return MTLBlendOperationSubtract;
		case 2: return MTLBlendOperationReverseSubtract;
		case 3: return MTLBlendOperationMin;
		case 4: return MTLBlendOperationMax;
		default:
			mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal blend operation index %d", operation]);
			return MTLBlendOperationAdd;
	}
}

static MTLCompareFunction mc_compare_function(JNIEnv *env, jint function) {
	switch (function) {
		case 0: return MTLCompareFunctionNever;
		case 1: return MTLCompareFunctionLess;
		case 2: return MTLCompareFunctionEqual;
		case 3: return MTLCompareFunctionLessEqual;
		case 4: return MTLCompareFunctionGreater;
		case 5: return MTLCompareFunctionNotEqual;
		case 6: return MTLCompareFunctionGreaterEqual;
		case 7: return MTLCompareFunctionAlways;
		default:
			mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal compare function index %d", function]);
			return MTLCompareFunctionAlways;
	}
}

static MTLCullMode mc_cull_mode(JNIEnv *env, jint mode) {
	switch (mode) {
		case 0: return MTLCullModeNone;
		case 1: return MTLCullModeFront;
		case 2: return MTLCullModeBack;
		default:
			mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal cull mode index %d", mode]);
			return MTLCullModeNone;
	}
}

static MTLTriangleFillMode mc_fill_mode(JNIEnv *env, jint mode) {
	switch (mode) {
		case 0: return MTLTriangleFillModeFill;
		case 1: return MTLTriangleFillModeLines;
		default:
			mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal fill mode index %d", mode]);
			return MTLTriangleFillModeFill;
	}
}

static MTLColorWriteMask mc_color_write_mask(JNIEnv *env, jint mask) {
	if ((mask & ~15) != 0) {
		mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal color write mask %d", mask]);
		return MTLColorWriteMaskNone;
	}
	MTLColorWriteMask result = MTLColorWriteMaskNone;
	if ((mask & 1) != 0) result |= MTLColorWriteMaskRed;
	if ((mask & 2) != 0) result |= MTLColorWriteMaskGreen;
	if ((mask & 4) != 0) result |= MTLColorWriteMaskBlue;
	if ((mask & 8) != 0) result |= MTLColorWriteMaskAlpha;
	return result;
}

/** @return the slices a texture addresses: a cubemap's six faces, or its array length. */
static NSUInteger mc_texture_slice_count(id<MTLTexture> texture) {
	return texture.textureType == MTLTextureTypeCube ? 6 : texture.arrayLength;
}

static BOOL mc_read_int_array(
	JNIEnv *env,
	jintArray array,
	jsize expectedLength,
	jsize maximumLength,
	jint *values,
	NSString *name
) {
	if (array == NULL || (*env)->GetArrayLength(env, array) != expectedLength || expectedLength > maximumLength) {
		mc_throw_state(env, [NSString stringWithFormat:@"Metal pipeline %@ has an invalid length", name]);
		return NO;
	}
	if (expectedLength > 0) {
		(*env)->GetIntArrayRegion(env, array, 0, expectedLength, values);
	}
	return !(*env)->ExceptionCheck(env);
}

static id<MTLCounterSet> mc_timestamp_counter_set(id<MTLDevice> device) {
	for (id<MTLCounterSet> counterSet in device.counterSets) {
		if (![counterSet.name isEqualToString:MTLCommonCounterSetTimestamp]) {
			continue;
		}
		for (id<MTLCounter> counter in counterSet.counters) {
			if ([counter.name isEqualToString:MTLCommonCounterTimestamp]) {
				return counterSet;
			}
		}
	}
	return nil;
}

static BOOL mc_supports_timestamp_queries(id<MTLDevice> device) {
	return mc_timestamp_counter_set(device) != nil
		&& ([device supportsCounterSampling:MTLCounterSamplingPointAtBlitBoundary]
			|| [device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]);
}

static BOOL mc_supports_render_timestamp_queries(id<MTLDevice> device) {
	return mc_timestamp_counter_set(device) != nil
		&& [device supportsCounterSampling:MTLCounterSamplingPointAtDrawBoundary];
}

static MTLLoadAction mc_load_action(JNIEnv *env, jint action) {
	switch (action) {
		case 0:
			return MTLLoadActionLoad;
		case 1:
			return MTLLoadActionClear;
		case 2:
			return MTLLoadActionDontCare;
		default:
			mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal load action index %d", action]);
			return MTLLoadActionDontCare;
	}
}

static MTLStoreAction mc_store_action(JNIEnv *env, jint action) {
	switch (action) {
		case 0:
			return MTLStoreActionStore;
		case 1:
			return MTLStoreActionDontCare;
		default:
			mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal store action index %d", action]);
			return MTLStoreActionDontCare;
	}
}

/** @return NO for an unknown primitive index, without touching the JNI environment */
static BOOL mc_primitive_type_for(jint primitive, MTLPrimitiveType *result) {
	switch (primitive) {
		case 0:
			*result = MTLPrimitiveTypePoint;
			return YES;
		case 1:
			*result = MTLPrimitiveTypeLine;
			return YES;
		case 2:
			*result = MTLPrimitiveTypeLineStrip;
			return YES;
		case 3:
			*result = MTLPrimitiveTypeTriangle;
			return YES;
		case 4:
			*result = MTLPrimitiveTypeTriangleStrip;
			return YES;
		default:
			return NO;
	}
}

static MTLPrimitiveType mc_primitive_type(JNIEnv *env, jint primitive) {
	MTLPrimitiveType result;
	if (!mc_primitive_type_for(primitive, &result)) {
		mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal primitive index %d", primitive]);
		return MTLPrimitiveTypeTriangle;
	}
	return result;
}

static BOOL mc_require_main_thread(JNIEnv *env, NSString *operation) {
	if (NSThread.isMainThread) {
		return YES;
	}
	mc_throw_state(env, [NSString stringWithFormat:@"%@ must run on the AppKit main thread", operation]);
	return NO;
}
