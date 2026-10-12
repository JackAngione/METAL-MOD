/**
 * The Metal object a command names, or 0 for an opcode this build does not know.
 *
 * <p>Every opcode in the batch ABI names exactly one object, which is what lets the resolved
 * objects be a plain array parallel to the records.
 */
static MCObjectType mc_command_operand_type(int32_t opcode) {
	switch (opcode) {
		case MCCommandSetVertexBuffer:
		case MCCommandSetUniformBuffer:
		case MCCommandDrawIndexed:
			return MCObjectTypeBuffer;
		case MCCommandSetTexture:
			return MCObjectTypeTextureView;
		case MCCommandSetSampler:
			return MCObjectTypeSampler;
		case MCCommandSetPipeline:
			return MCObjectTypeRenderPipeline;
		default:
			return (MCObjectType)0;
	}
}

static NSUInteger mc_command_index_size(int32_t indexType) {
	return indexType == 0 ? 2 : (indexType == 1 ? 4 : 0);
}

/**
 * Checks everything a record can be judged on by itself.
 *
 * <p>This runs on the coarse path too. It is integer comparisons over a buffer already in cache, and
 * what it keeps out is a slot index or a primitive type that would make Metal raise an Objective-C
 * exception through a JNI frame that cannot catch it. What it deliberately does not do is read the
 * length of the buffer a record names: that is a message send per command, and it is the recorder's
 * job - see MetalCommandStream. MC_COMMAND_STREAM_CHECKED asks for it anyway.
 */
static BOOL mc_validate_command(JNIEnv *env, const MCCommand *command, int32_t index) {
	NSString *problem = nil;
	if (command->handle <= 0 || command->reserved != 0) {
		problem = @"resource handle or reserved field";
	} else {
		switch (command->opcode) {
			case MCCommandSetPipeline:
				if (command->slot != 0 || command->stages != 0 || command->offset != 0
					|| command->count != 0 || command->instanceCount != 0 || command->baseVertex != 0 || command->baseInstance != 0) {
					problem = @"pipeline command fields";
				}
				break;
			case MCCommandSetVertexBuffer:
				if (command->slot < 0 || command->slot >= 31 || command->offset < 0) {
					problem = @"vertex-buffer binding index or offset";
				}
				break;
			case MCCommandSetUniformBuffer:
				if (command->slot < 0 || command->slot >= 16 || command->offset < 0
					|| command->stages == 0 || (command->stages & ~(MCShaderStageVertex | MCShaderStageFragment)) != 0) {
					problem = @"uniform-buffer binding index, offset, or stage mask";
				}
				break;
			case MCCommandSetTexture:
			case MCCommandSetSampler:
				if (command->slot < 0 || command->slot >= 16
					|| command->stages == 0 || (command->stages & ~(MCShaderStageVertex | MCShaderStageFragment)) != 0) {
					problem = @"texture or sampler binding index or stage mask";
				}
				break;
			case MCCommandDrawIndexed: {
				MTLPrimitiveType primitiveType;
				NSUInteger indexSize = mc_command_index_size(command->stages);
				if (!mc_primitive_type_for(command->slot, &primitiveType) || indexSize == 0
					|| command->count <= 0 || command->instanceCount <= 0 || command->baseInstance < 0
					|| command->offset < 0 || (NSUInteger)command->offset % indexSize != 0) {
					problem = @"indexed draw";
				}
				break;
			}
			default:
				problem = @"opcode";
				break;
		}
	}
	if (problem == nil) {
		return YES;
	}
	mc_throw_state(env, [NSString stringWithFormat:@"Metal command %d in this batch has an invalid %@", index, problem]);
	return NO;
}

/** The ranges the coarse path leaves to the recorder, re-derived from the objects themselves. */
static BOOL mc_check_command_range(JNIEnv *env, const MCCommand *command, id __unsafe_unretained object, int32_t index) {
	NSString *problem = nil;
	switch (command->opcode) {
		case MCCommandSetVertexBuffer:
		case MCCommandSetUniformBuffer: {
			id<MTLBuffer> buffer = object;
			if ((NSUInteger)command->offset >= buffer.length) {
				problem = @"binds past the end of its buffer";
			}
			break;
		}
		case MCCommandDrawIndexed: {
			id<MTLBuffer> indexBuffer = object;
			NSUInteger indexSize = mc_command_index_size(command->stages);
			if ((NSUInteger)command->offset > indexBuffer.length
				|| (NSUInteger)command->count > (indexBuffer.length - (NSUInteger)command->offset) / indexSize) {
				problem = @"reads indices past the end of its buffer";
			}
			break;
		}
		default:
			break;
	}
	if (problem == nil) {
		return YES;
	}
	mc_throw_state(env, [NSString stringWithFormat:@"Metal command %d in this batch %@", index, problem]);
	return NO;
}

/** Resolve each distinct handle once, retaining it across registry-lock strides.
 * The scratch owns references until pinAll transfers GPU lifetime to the submission.
 * The handle includes its generation; differently typed aliases are always rejected.
 */
static BOOL mc_resolve_command_operands(
    JNIEnv *env, const MCCommand *commands,
    int32_t count, MCCommandScratch *scratch, jlong rootDevice
) {
    for (int32_t index = 0; index < count;) {
        int32_t end = (int32_t)MIN((int64_t)index + MC_COMMAND_RESOLVE_STRIDE, (int64_t)count);
        os_unfair_lock_lock(&mc_registry_lock);
        for (; index < end; index++) {
            jlong handle = commands[index].handle;
            MCObjectType expectedType = mc_command_operand_type(commands[index].opcode);
            uint64_t hash = (uint64_t)handle;
            hash ^= hash >> 33; hash *= UINT64_C(0xff51afd7ed558ccd); hash ^= hash >> 33;
            NSUInteger bucket = hash & (scratch->bucketCount - 1);
            while (scratch->buckets[bucket] != 0
                && scratch->handles[scratch->buckets[bucket] - 1] != handle)
                bucket = (bucket + 1) & (scratch->bucketCount - 1);
            uint32_t operand = scratch->buckets[bucket];
            if (operand != 0) {
                if (scratch->types[operand - 1] != expectedType) {
                    os_unfair_lock_unlock(&mc_registry_lock);
                    mc_throw_state(env, @"Metal batch reuses a resource with an incorrect type");
                    return NO;
                }
                scratch->operands[index] = operand - 1;
                continue;
            }
            MCSlot *entry = mc_slot_locked(handle);
            if (entry == NULL || entry->type != expectedType || entry->rootDeviceHandle != rootDevice) {
                os_unfair_lock_unlock(&mc_registry_lock);
                mc_throw_state(env, @"Metal batch names an unknown, released, incorrectly typed, or foreign resource");
                return NO;
            }
            operand = (uint32_t)scratch->used++;
            scratch->objects[operand] = (__bridge id)entry->object;
            scratch->handles[operand] = handle;
            scratch->types[operand] = expectedType;
            scratch->buckets[bucket] = operand + 1;
            scratch->occupied[operand] = (uint32_t)bucket;
            scratch->operands[index] = operand;
        }
        os_unfair_lock_unlock(&mc_registry_lock);
    }
    return YES;
}

/**
 * Issues the batch's Metal calls in the order they were recorded.
 *
 * <p>Nothing here locks and nothing here looks anything up. A checked run that rejects a command
 * leaves the encoder holding the commands before it - there is no undoing an encoder call - so the
 * exception that follows is fatal to the frame, which is what a batch ABI violation should be.
 */
static void mc_encode_commands(
	JNIEnv *env,
	MCMetalRenderPass *renderPass,
	const MCCommand *commands,
	int32_t count,
	MCCommandScratch *scratch,
	BOOL checked
) {
	id<MTLRenderCommandEncoder> encoder = renderPass.encoder;
	for (int32_t index = 0; index < count; index++) {
		const MCCommand *command = &commands[index];
		id __unsafe_unretained object = scratch->objects[scratch->operands[index]];
		if (checked && !mc_check_command_range(env, command, object, index)) {
			return;
		}
		NSUInteger slot = (NSUInteger)command->slot;
		switch (command->opcode) {
			case MCCommandSetPipeline: {
				MCMetalRenderPipeline *pipeline = object;
				[encoder setRenderPipelineState:pipeline.pipelineState];
				[encoder setDepthStencilState:pipeline.depthStencilState];
				[encoder setFrontFacingWinding:MTLWindingClockwise];
				[encoder setCullMode:pipeline.cullMode];
				[encoder setTriangleFillMode:pipeline.fillMode];
				[encoder setDepthBias:pipeline.depthBiasConstant slopeScale:pipeline.depthBiasSlopeScale clamp:0.0F];
				break;
			}
			case MCCommandSetVertexBuffer:
				[encoder setVertexBuffer:object offset:(NSUInteger)command->offset atIndex:slot];
				break;
			case MCCommandSetUniformBuffer:
				if (command->stages & MCShaderStageVertex) {
					[encoder setVertexBuffer:object offset:(NSUInteger)command->offset atIndex:slot];
				}
				if (command->stages & MCShaderStageFragment) {
					[encoder setFragmentBuffer:object offset:(NSUInteger)command->offset atIndex:slot];
				}
				break;
			case MCCommandSetTexture:
				if (command->stages & MCShaderStageVertex) {
					[encoder setVertexTexture:object atIndex:slot];
				}
				if (command->stages & MCShaderStageFragment) {
					[encoder setFragmentTexture:object atIndex:slot];
				}
				break;
			case MCCommandSetSampler:
				if (command->stages & MCShaderStageVertex) {
					[encoder setVertexSamplerState:object atIndex:slot];
				}
				if (command->stages & MCShaderStageFragment) {
					[encoder setFragmentSamplerState:object atIndex:slot];
				}
				break;
			case MCCommandDrawIndexed: {
				MTLPrimitiveType primitiveType;
				mc_primitive_type_for(command->slot, &primitiveType);
				[encoder drawIndexedPrimitives:primitiveType
					indexCount:(NSUInteger)command->count
					indexType:command->stages == 0 ? MTLIndexTypeUInt16 : MTLIndexTypeUInt32
					indexBuffer:object
					indexBufferOffset:(NSUInteger)command->offset
					instanceCount:(NSUInteger)command->instanceCount
					baseVertex:(NSInteger)command->baseVertex
					baseInstance:(NSUInteger)command->baseInstance];
				break;
			}
			default:
				break;
		}
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nSubmitCommandStream(
	JNIEnv *env,
	jclass type,
	jlong renderPassHandle,
	jobject stream,
	jint byteCount
) {
	@autoreleasepool {
		void *base = stream == NULL ? NULL : (*env)->GetDirectBufferAddress(env, stream);
		jlong capacity = stream == NULL ? -1 : (*env)->GetDirectBufferCapacity(env, stream);
		if (base == NULL || capacity < 0) {
			mc_throw_state(env, @"A Metal command batch must be a direct buffer");
			return;
		}
		if (byteCount < (jint)sizeof(MCCommandStreamHeader) || (jlong)byteCount > capacity) {
			mc_throw_state(env, @"Metal command batch length is outside its buffer");
			return;
		}
		const MCCommandStreamHeader *header = base;
		if (header->magic != MC_COMMAND_STREAM_MAGIC || header->commandSize != (int32_t)sizeof(MCCommand)) {
			mc_throw_state(
				env,
				[NSString stringWithFormat:@"Metal command batch ABI does not match this native build: magic %d, record size %d against %d",
					header->magic, header->commandSize, (int)sizeof(MCCommand)]
			);
			return;
		}
		int32_t count = header->commandCount;
		if (count < 0
			|| (int64_t)byteCount != (int64_t)sizeof(MCCommandStreamHeader) + (int64_t)count * (int64_t)sizeof(MCCommand)) {
			mc_throw_state(env, @"Metal command batch length does not match the commands it declares");
			return;
		}
		if (count == 0) {
			return;
		}
		BOOL checked = (header->flags & MC_COMMAND_STREAM_CHECKED) != 0;
		const MCCommand *commands = (const MCCommand *)((const uint8_t *)base + sizeof(MCCommandStreamHeader));
		for (int32_t index = 0; index < count; index++) {
			if (!mc_validate_command(env, &commands[index], index)) {
				return;
			}
		}

        MCMetalRenderPass *renderPass = (MCMetalRenderPass *)mc_get_object(env, renderPassHandle, MCObjectTypeRenderPass);
        if (renderPass == nil) return;
        os_unfair_lock_lock(&mc_registry_lock);
        MCSlot *passSlot = mc_slot_locked(renderPassHandle);
        jlong rootDevice = passSlot == NULL ? 0 : passSlot->rootDeviceHandle;
        os_unfair_lock_unlock(&mc_registry_lock);
        if (rootDevice == 0) { mc_throw_state(env, @"Metal batch pass was released"); return; }
        MCCommandScratch *scratch = objc_getAssociatedObject(renderPass.commandBuffer.commandBuffer.commandQueue, &MCCommandScratchKey);
        if (scratch == nil) { mc_throw_state(env, @"Metal command queue has no scratch owner"); return; }
        [scratch->lock lock];
        @try {
            if (![scratch prepare:(NSUInteger)count]) {
                mc_throw_state(env, @"Could not grow Metal command scratch storage");
                return;
            }
            if (mc_resolve_command_operands(env, commands, count, scratch, rootDevice)) {
                [renderPass.commandBuffer pinAll:(id __unsafe_unretained const *)(void *)scratch->objects count:scratch->used];
                mc_encode_commands(env, renderPass, commands, count, scratch, checked);
            }
        } @finally {
            [scratch clear];
            [scratch->lock unlock];
        }
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nEndRenderPass(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeRenderPass);
	}
}
