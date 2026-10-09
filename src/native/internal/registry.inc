/**
 * The registry mapping Java handles to the Metal objects they name.
 *
 * <p>This was a dictionary keyed by boxed handles, which meant every command that touched an object
 * allocated an NSNumber, hashed a CFNumber, and probed a hash table - two or three times per call,
 * on the render thread, for what is really an array index. A slot table answers the same question
 * with a bounds check and a compare.
 *
 * <p>A handle packs a generation above a slot index, so a stale handle whose slot has since been
 * reused is rejected rather than silently resolving to whatever now lives there. The generation
 * advances on every release, and slot 0 is usable because the generation starts at one and is never
 * zero while a slot is live - so a valid handle is never zero, which is the value Java uses for
 * "closed".
 *
 * <p>Entries are plain structs rather than objects: the fields are read on every command, and
 * reaching them through property accessors on a heap object was another message send apiece.
 */
#define MC_SLOT_BITS 24
#define MC_SLOT_MASK ((1u << MC_SLOT_BITS) - 1u)
#define MC_MAX_SLOTS (1u << MC_SLOT_BITS)

typedef struct {
	/** Retained by the table; NULL marks a free slot. Bridged rather than __strong so it can live in realloc'd memory. */
	void *object;
	jlong ownerHandle;
	/**
	 * The device this object ultimately belongs to, resolved once at registration.
	 *
	 * <p>Ownership is fixed for an object's lifetime, so walking the owner chain on every command
	 * was re-deriving a constant. A profile of the render thread attributed 13% of its samples to
	 * that walk, most of it dictionary lookups and CFNumber hashing one level at a time.
	 */
	jlong rootDeviceHandle;
	/** Matches the generation encoded in the live handle for this slot. */
	uint64_t generation;
	/**
	 * How many registered objects name this one as their owner.
	 *
	 * <p>Release has to refuse an object that still owns children. It used to answer that by walking
	 * every value in the registry, which copies the whole value array and costs O(live objects) per
	 * release - and the renderer releases thousands of chunk buffers while streaming terrain. The
	 * count answers the same question in constant time; the scan survives only on the error path,
	 * where it still names the offending child's type.
	 */
	uint32_t childCount;
	MCObjectType type;
} MCSlot;

/**
 * Guards the slot table.
 *
 * <p>An os_unfair_lock rather than an NSLock, because this is taken and dropped on every command the
 * render thread issues, and the work inside it is now a bounds check and a few field reads. An
 * NSLock charges an objc_msgSend and a pthread_mutex round-trip for each of those, which is most of
 * what a lookup costs once the lookup itself is a slot index. This is an atomic compare-and-swap
 * when uncontended, and it needs no lazy construction - so the pthread_once that guarded the lock's
 * allocation is gone from the same path.
 *
 * <p>It is not recursive and does not want to be: nothing below calls another function that locks,
 * and everything that can throw, block, or run Objective-C teardown does so after unlocking.
 */
static os_unfair_lock mc_registry_lock = OS_UNFAIR_LOCK_INIT;
static MCSlot *mc_slots;
static uint32_t mc_slot_capacity;
static uint32_t mc_slot_count;
static uint32_t *mc_free_slots;
static uint32_t mc_free_count;

static jlong mc_make_handle(uint32_t slot, uint64_t generation) {
	return (jlong)((generation << MC_SLOT_BITS) | slot);
}

/** @return the live slot for this handle, or NULL if the handle is zero, stale, or out of range */
static MCSlot *mc_slot_locked(jlong handle) {
	if (handle <= 0) {
		return NULL;
	}
	uint32_t slot = (uint32_t)handle & MC_SLOT_MASK;
	uint64_t generation = (uint64_t)handle >> MC_SLOT_BITS;
	if (slot >= mc_slot_count) {
		return NULL;
	}
	MCSlot *entry = &mc_slots[slot];
	return entry->object != NULL && entry->generation == generation ? entry : NULL;
}

static void mc_throw_state(JNIEnv *env, NSString *message) {
	jclass exception = (*env)->FindClass(env, "java/lang/IllegalStateException");
	if (exception != NULL) {
		(*env)->ThrowNew(env, exception, message.UTF8String);
		(*env)->DeleteLocalRef(env, exception);
	}
}

static NSString *mc_type_name(MCObjectType type) {
	switch (type) {
		case MCObjectTypeDevice:
			return @"Metal device";
		case MCObjectTypeCommandQueue:
			return @"Metal command queue";
		case MCObjectTypeSurface:
			return @"Metal surface";
		case MCObjectTypeDrawable:
			return @"Metal drawable";
		case MCObjectTypeCommandBuffer:
			return @"Metal command buffer";
		case MCObjectTypeBuffer:
			return @"Metal buffer";
		case MCObjectTypeTexture:
			return @"Metal texture";
		case MCObjectTypeTextureView:
			return @"Metal texture view";
		case MCObjectTypeSampler:
			return @"Metal sampler";
		case MCObjectTypeFence:
			return @"Metal fence";
		case MCObjectTypeRenderPipeline:
			return @"Metal render pipeline";
		case MCObjectTypeRenderPass:
			return @"Metal render pass";
		case MCObjectTypeTimestampQueryPool:
			return @"Metal timestamp query pool";
		case MCObjectTypeComputePipeline:
			return @"Metal compute pipeline";
		case MCObjectTypeComputePass:
			return @"Metal compute pass";
		case MCObjectTypeCommandCompletion:
			return @"Metal command completion";
	}
	return @"Metal object";
}

#include "format_mappings.inc"

/** Claims a slot for a new object. The registry lock must already be held. */
static uint32_t mc_claim_slot_locked(void) {
	if (mc_free_count > 0) {
		return mc_free_slots[--mc_free_count];
	}
	if (mc_slot_count == mc_slot_capacity) {
		uint32_t capacity = mc_slot_capacity == 0 ? 256 : mc_slot_capacity * 2;
		if (capacity > MC_MAX_SLOTS) {
			capacity = MC_MAX_SLOTS;
		}
		if (capacity == mc_slot_capacity) {
			return UINT32_MAX;
		}
		MCSlot *grown = realloc(mc_slots, (size_t)capacity * sizeof(MCSlot));
		uint32_t *grownFree = realloc(mc_free_slots, (size_t)capacity * sizeof(uint32_t));
		if (grown == NULL || grownFree == NULL) {
			// Keep whichever succeeded; the table stays consistent either way and the caller fails.
			if (grown != NULL) mc_slots = grown;
			if (grownFree != NULL) mc_free_slots = grownFree;
			return UINT32_MAX;
		}
		mc_slots = grown;
		mc_free_slots = grownFree;
		memset(&mc_slots[mc_slot_capacity], 0, (size_t)(capacity - mc_slot_capacity) * sizeof(MCSlot));
		mc_slot_capacity = capacity;
	}
	return mc_slot_count++;
}

static jlong mc_register_object(id object, MCObjectType type, jlong ownerHandle) {
	os_unfair_lock_lock(&mc_registry_lock);
	uint32_t slot = mc_claim_slot_locked();
	if (slot == UINT32_MAX) {
		os_unfair_lock_unlock(&mc_registry_lock);
		return 0;
	}
	MCSlot *entry = &mc_slots[slot];
	if (entry->generation == 0) {
		entry->generation = 1;
	}
	jlong handle = mc_make_handle(slot, entry->generation);
	// Resolved once here rather than on every command that touches the object.
	jlong rootDeviceHandle = 0;
	MCSlot *owner = mc_slot_locked(ownerHandle);
	if (type == MCObjectTypeDevice) {
		rootDeviceHandle = handle;
	} else if (owner != NULL) {
		rootDeviceHandle = owner->rootDeviceHandle;
		owner->childCount += 1;
	}
	entry->object = (__bridge_retained void *)object;
	entry->ownerHandle = ownerHandle;
	entry->rootDeviceHandle = rootDeviceHandle;
	entry->childCount = 0;
	entry->type = type;
	os_unfair_lock_unlock(&mc_registry_lock);
	return handle;
}

static id mc_get_object(JNIEnv *env, jlong handle, MCObjectType expectedType) {
	if (handle == 0) {
		mc_throw_state(env, [NSString stringWithFormat:@"%@ is closed", mc_type_name(expectedType)]);
		return nil;
	}

	os_unfair_lock_lock(&mc_registry_lock);
	MCSlot *entry = mc_slot_locked(handle);
	if (entry == NULL) {
		os_unfair_lock_unlock(&mc_registry_lock);
		mc_throw_state(env, [NSString stringWithFormat:@"Unknown or released %@ handle %lld", mc_type_name(expectedType), (long long)handle]);
		return nil;
	}
	// Copied out under the lock; the slot itself can be reused the moment it is dropped.
	MCObjectType actualType = entry->type;
	id object = (__bridge id)entry->object;
	os_unfair_lock_unlock(&mc_registry_lock);

	if (actualType != expectedType) {
		mc_throw_state(
			env,
			[NSString stringWithFormat:@"Handle %lld is a %@, not a %@", (long long)handle, mc_type_name(actualType), mc_type_name(expectedType)]
		);
		return nil;
	}
	return object;
}

static jlong mc_root_device_handle_locked(jlong handle) {
	MCSlot *entry = mc_slot_locked(handle);
	return entry == NULL ? 0 : entry->rootDeviceHandle;
}

static BOOL mc_get_objects_same_device(
	JNIEnv *env,
	const jlong *handles,
	const MCObjectType *types,
	id __strong *objects,
	NSUInteger count
) {
	os_unfair_lock_lock(&mc_registry_lock);
	jlong rootDevice = 0;
	for (NSUInteger index = 0; index < count; index++) {
		MCSlot *entry = mc_slot_locked(handles[index]);
		if (entry == NULL || entry->type != types[index]) {
			os_unfair_lock_unlock(&mc_registry_lock);
			mc_throw_state(env, [NSString stringWithFormat:@"Unknown, released, or incorrectly typed %@", mc_type_name(types[index])]);
			return NO;
		}
		jlong candidateRoot = entry->rootDeviceHandle;
		if (candidateRoot == 0 || (rootDevice != 0 && candidateRoot != rootDevice)) {
			os_unfair_lock_unlock(&mc_registry_lock);
			mc_throw_state(env, @"Metal objects used by one command must belong to the same device");
			return NO;
		}
		rootDevice = candidateRoot;
		objects[index] = (__bridge id)entry->object;
	}
	os_unfair_lock_unlock(&mc_registry_lock);
	return YES;
}

static BOOL mc_get_present_objects(
	JNIEnv *env,
	jlong commandBufferHandle,
	jlong drawableHandle,
	MCMetalCommandBuffer **commandBuffer,
	id<CAMetalDrawable> *drawable
) {
	os_unfair_lock_lock(&mc_registry_lock);
	MCSlot *commandBufferEntry = mc_slot_locked(commandBufferHandle);
	MCSlot *drawableEntry = mc_slot_locked(drawableHandle);
	if (commandBufferEntry == NULL || commandBufferEntry->type != MCObjectTypeCommandBuffer) {
		os_unfair_lock_unlock(&mc_registry_lock);
		mc_throw_state(env, @"Cannot present with an unknown, released, or incorrectly typed Metal command buffer");
		return NO;
	}
	if (drawableEntry == NULL || drawableEntry->type != MCObjectTypeDrawable) {
		os_unfair_lock_unlock(&mc_registry_lock);
		mc_throw_state(env, @"Cannot present an unknown, released, or incorrectly typed Metal drawable");
		return NO;
	}
	if (commandBufferEntry->rootDeviceHandle != drawableEntry->rootDeviceHandle) {
		os_unfair_lock_unlock(&mc_registry_lock);
		mc_throw_state(env, @"A command buffer cannot present a drawable created by another Metal device");
		return NO;
	}
	*commandBuffer = (__bridge id)commandBufferEntry->object;
	*drawable = (__bridge id)drawableEntry->object;
	os_unfair_lock_unlock(&mc_registry_lock);
	return YES;
}

static void mc_release_object(JNIEnv *env, jlong handle, MCObjectType expectedType) {
	if (handle == 0) {
		return;
	}

	os_unfair_lock_lock(&mc_registry_lock);
	MCSlot *entry = mc_slot_locked(handle);
	if (entry == NULL) {
		os_unfair_lock_unlock(&mc_registry_lock);
		mc_throw_state(env, [NSString stringWithFormat:@"Unknown or already released %@ handle %lld", mc_type_name(expectedType), (long long)handle]);
		return;
	}
	if (entry->type != expectedType) {
		MCObjectType actualType = entry->type;
		os_unfair_lock_unlock(&mc_registry_lock);
		mc_throw_state(
			env,
			[NSString stringWithFormat:@"Cannot release handle %lld as a %@ because it is a %@", (long long)handle, mc_type_name(expectedType), mc_type_name(actualType)]
		);
		return;
	}

	if (entry->childCount != 0) {
		// Only here is the scan worth its cost, and only to name the child in the message.
		MCObjectType childType = expectedType;
		for (uint32_t slot = 0; slot < mc_slot_count; slot++) {
			if (mc_slots[slot].object != NULL && mc_slots[slot].ownerHandle == handle) {
				childType = mc_slots[slot].type;
				break;
			}
		}
		os_unfair_lock_unlock(&mc_registry_lock);
		mc_throw_state(env, [NSString stringWithFormat:@"Cannot release a %@ while it still owns a %@", mc_type_name(expectedType), mc_type_name(childType)]);
		return;
	}

	// Handed back to ARC, then released outside the lock along with the slot it vacated.
	id object = (__bridge_transfer id)entry->object;
	entry->object = NULL;
	entry->generation += 1;
	MCSlot *owner = mc_slot_locked(entry->ownerHandle);
	if (owner != NULL && owner->childCount != 0) {
		owner->childCount -= 1;
	}
	entry->ownerHandle = 0;
	entry->rootDeviceHandle = 0;
	mc_free_slots[mc_free_count++] = (uint32_t)handle & MC_SLOT_MASK;
	os_unfair_lock_unlock(&mc_registry_lock);

	if (expectedType == MCObjectTypeSurface) {
		[(MCMetalSurface *)object detach];
	} else if (expectedType == MCObjectTypeRenderPass) {
		[(MCMetalRenderPass *)object end];
	} else if (expectedType == MCObjectTypeComputePass) {
		[(MCMetalComputePass *)object end];
	}
}
