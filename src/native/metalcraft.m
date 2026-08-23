#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>
#import <jni.h>
#import <objc/runtime.h>
#import <os/lock.h>
#import <stdatomic.h>
#import <float.h>
#import <math.h>

#define MC_EXPORT __attribute__((visibility("default")))

/**
 * Shader stages a resource binding applies to, matching MetalRenderPass.STAGE_*.
 *
 * <p>Metal keeps one argument table per stage, so every binding call reaches exactly one of them.
 * These bindings used to be issued for both stages unconditionally because the backend had no way
 * to know which stage read a slot; the translated shaders do know, and pass it down here.
 */
typedef NS_OPTIONS(uint32_t, MCShaderStage) {
	MCShaderStageVertex = 1,
	MCShaderStageFragment = 2
};

typedef NS_ENUM(NSUInteger, MCObjectType) {
	MCObjectTypeDevice = 1,
	MCObjectTypeCommandQueue = 2,
	MCObjectTypeSurface = 3,
	MCObjectTypeDrawable = 4,
	MCObjectTypeCommandBuffer = 5,
	MCObjectTypeBuffer = 6,
	MCObjectTypeTexture = 7,
	MCObjectTypeTextureView = 8,
	MCObjectTypeSampler = 9,
	MCObjectTypeFence = 11,
	MCObjectTypeRenderPipeline = 12,
	MCObjectTypeRenderPass = 13,
	MCObjectTypeTimestampQueryPool = 14
};

/**
 * Texel-buffer views for one MTLBuffer, keyed by the range and format they view.
 *
 * <p>A view is a pure function of (buffer, offset, length, format) and stays valid as the buffer's
 * contents change, so rebuilding one per bind - which is what the render path used to do - creates
 * a Metal object per draw for no benefit. The cache is small and searched linearly because a buffer
 * is typically viewed through one or two ranges.
 */
#define MC_TEXEL_VIEW_CACHE_CAPACITY 8

@interface MCTexelViewCache : NSObject
- (nullable id<MTLTexture>)viewForOffset:(NSUInteger)offset length:(NSUInteger)length format:(MTLPixelFormat)format;
- (void)storeView:(id<MTLTexture>)view offset:(NSUInteger)offset length:(NSUInteger)length format:(MTLPixelFormat)format;
@end

@implementation MCTexelViewCache {
	NSUInteger _offsets[MC_TEXEL_VIEW_CACHE_CAPACITY];
	NSUInteger _lengths[MC_TEXEL_VIEW_CACHE_CAPACITY];
	MTLPixelFormat _formats[MC_TEXEL_VIEW_CACHE_CAPACITY];
	id<MTLTexture> _views[MC_TEXEL_VIEW_CACHE_CAPACITY];
	NSUInteger _count;
	NSUInteger _nextSlot;
}

- (nullable id<MTLTexture>)viewForOffset:(NSUInteger)offset length:(NSUInteger)length format:(MTLPixelFormat)format {
	for (NSUInteger slot = 0; slot < _count; slot++) {
		if (_offsets[slot] == offset && _lengths[slot] == length && _formats[slot] == format) {
			return _views[slot];
		}
	}
	return nil;
}

- (void)storeView:(id<MTLTexture>)view offset:(NSUInteger)offset length:(NSUInteger)length format:(MTLPixelFormat)format {
	NSUInteger slot;
	if (_count < MC_TEXEL_VIEW_CACHE_CAPACITY) {
		slot = _count++;
	} else {
		slot = _nextSlot;
		_nextSlot = (_nextSlot + 1) % MC_TEXEL_VIEW_CACHE_CAPACITY;
	}
	_offsets[slot] = offset;
	_lengths[slot] = length;
	_formats[slot] = format;
	_views[slot] = view;
}
@end

static const void *MCTexelViewCacheKey = &MCTexelViewCacheKey;

@interface MCMetalSurface : NSObject

@property(nonatomic, strong, readonly) CAMetalLayer *layer;
@property(nonatomic, strong, readonly) NSView *view;
@property(nonatomic, strong, readonly) CALayer *previousLayer;
@property(nonatomic, readonly) BOOL previousWantsLayer;

- (instancetype)initWithDevice:(id<MTLDevice>)device
	view:(NSView *)view
	width:(NSUInteger)width
	height:(NSUInteger)height;
- (void)resizeToWidth:(NSUInteger)width height:(NSUInteger)height;
- (void)detach;

@end


@implementation MCMetalSurface

- (instancetype)initWithDevice:(id<MTLDevice>)device
	view:(NSView *)view
	width:(NSUInteger)width
	height:(NSUInteger)height {
	self = [super init];
	if (self != nil) {
		_view = view;
		_previousWantsLayer = view.wantsLayer;
		_previousLayer = view.layer;
		_layer = [CAMetalLayer layer];
		_layer.device = device;
		_layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
		_layer.framebufferOnly = YES;
		_layer.opaque = YES;
		_layer.presentsWithTransaction = NO;
		_layer.allowsNextDrawableTimeout = YES;
		_layer.maximumDrawableCount = 3;
		_layer.contentsScale = view.window.backingScaleFactor;
		_layer.frame = view.bounds;
		_layer.drawableSize = CGSizeMake(width, height);
		view.wantsLayer = YES;
		view.layer = _layer;
	}
	return self;
}

- (void)resizeToWidth:(NSUInteger)width height:(NSUInteger)height {
	self.layer.contentsScale = self.view.window.backingScaleFactor;
	self.layer.frame = self.view.bounds;
	self.layer.drawableSize = CGSizeMake(width, height);
}

- (void)detach {
	if (self.view.layer == self.layer) {
		self.view.layer = self.previousLayer;
		self.view.wantsLayer = self.previousWantsLayer;
	}
}

@end


@interface MCMetalTimestampQueryPool : NSObject

@property(nonatomic, strong, readonly) id<MTLCounterSampleBuffer> sampleBuffer;
@property(nonatomic, readonly) NSUInteger size;

- (instancetype)initWithSampleBuffer:(id<MTLCounterSampleBuffer>)sampleBuffer size:(NSUInteger)size;
- (uint64_t)prepareSampleAtIndex:(NSUInteger)index;
- (void)markSampleAvailableAtIndex:(NSUInteger)index generation:(uint64_t)generation;
- (BOOL)getValue:(uint64_t *)value atIndex:(NSUInteger)index;

@end


@implementation MCMetalTimestampQueryPool {
	NSLock *_lock;
	uint64_t *_generations;
	uint8_t *_states;
}

- (instancetype)initWithSampleBuffer:(id<MTLCounterSampleBuffer>)sampleBuffer size:(NSUInteger)size {
	self = [super init];
	if (self != nil) {
		_sampleBuffer = sampleBuffer;
		_size = size;
		_lock = [[NSLock alloc] init];
		_generations = calloc(size, sizeof(uint64_t));
		_states = calloc(size, sizeof(uint8_t));
		if (_generations == NULL || _states == NULL) {
			free(_generations);
			free(_states);
			return nil;
		}
	}
	return self;
}

- (void)dealloc {
	free(_generations);
	free(_states);
}

- (uint64_t)prepareSampleAtIndex:(NSUInteger)index {
	[_lock lock];
	uint64_t generation = ++_generations[index];
	if (generation == 0) {
		generation = ++_generations[index];
	}
	_states[index] = 1;
	[_lock unlock];
	return generation;
}

- (void)markSampleAvailableAtIndex:(NSUInteger)index generation:(uint64_t)generation {
	[_lock lock];
	if (_generations[index] == generation) {
		_states[index] = 2;
	}
	[_lock unlock];
}

- (BOOL)getValue:(uint64_t *)value atIndex:(NSUInteger)index {
	[_lock lock];
	BOOL available = _states[index] == 2;
	[_lock unlock];
	if (!available) {
		return NO;
	}
	NSData *resolved = [self.sampleBuffer resolveCounterRange:NSMakeRange(index, 1)];
	if (resolved.length < sizeof(MTLCounterResultTimestamp)) {
		return NO;
	}
	uint64_t timestamp = ((const MTLCounterResultTimestamp *)resolved.bytes)->timestamp;
	if (timestamp == MTLCounterErrorValue) {
		return NO;
	}
	*value = timestamp;
	return YES;
}

@end


@interface MCInFlightResources : NSObject

- (void)pin:(id)object;
- (void)complete;
- (NSUInteger)count;

@end


@implementation MCInFlightResources {
	NSLock *_lock;
	NSMutableSet *_objects;
}

- (instancetype)init {
	self = [super init];
	if (self != nil) {
		_lock = [[NSLock alloc] init];
		_objects = [[NSMutableSet alloc] init];
	}
	return self;
}

- (void)pin:(id)object {
	if (object == nil) {
		return;
	}
	[_lock lock];
	[_objects addObject:object];
	[_lock unlock];
}

- (void)complete {
	[_lock lock];
	[_objects removeAllObjects];
	[_lock unlock];
}

- (NSUInteger)count {
	[_lock lock];
	NSUInteger count = _objects.count;
	[_lock unlock];
	return count;
}

@end


@interface MCMetalCommandBuffer : NSObject

@property(nonatomic, strong, readonly) id<MTLCommandBuffer> commandBuffer;

- (instancetype)initWithCommandBuffer:(id<MTLCommandBuffer>)commandBuffer;
- (void)pin:(id)object;
- (id<MTLBlitCommandEncoder>)blitEncoder;
- (void)endBlitEncoding;
- (void)recordTimestampInPool:(MCMetalTimestampQueryPool *)pool index:(NSUInteger)index;
- (NSUInteger)retainedResourceCount;

@end


/**
 * GPU busy time reported by completed command buffers, waiting to be drained by the render thread.
 *
 * <p>The stall probe can say a frame was not spent on the CPU, but not what the GPU was doing with
 * it, which leaves a whole class of runs describable only as "GPU-bound". Metal hands every command
 * buffer its own GPU start and end time once it completes, so the interval is free to collect here
 * rather than costing an encoder-level counter sample per pass.
 *
 * <p>Completion handlers run on Metal's own threads, so the counters are atomic and the render
 * thread drains them once per frame. What that yields is the GPU time of the command buffers that
 * *completed* during a frame, not the GPU time of that frame's own work - and command buffers can
 * overlap on the GPU, so the sum can exceed the wall clock. It answers "is the GPU busy", not "how
 * long did this frame take on the GPU".
 */
static _Atomic uint64_t mc_gpu_nanos;
static _Atomic uint64_t mc_gpu_command_buffers;

@implementation MCMetalCommandBuffer {
	MCInFlightResources *_resources;
	id<MTLBlitCommandEncoder> _blitEncoder;
}

- (instancetype)initWithCommandBuffer:(id<MTLCommandBuffer>)commandBuffer {
	self = [super init];
	if (self != nil) {
		_commandBuffer = commandBuffer;
		_resources = [[MCInFlightResources alloc] init];
		MCInFlightResources *resources = _resources;
		[commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> completed) {
			[resources complete];
			// Zero on a device or capture mode that does not report them, hence the ordering check
			// rather than a plain subtraction.
			CFTimeInterval elapsed = completed.GPUEndTime - completed.GPUStartTime;
			if (elapsed > 0.0) {
				atomic_fetch_add_explicit(&mc_gpu_nanos, (uint64_t)(elapsed * 1.0e9), memory_order_relaxed);
				atomic_fetch_add_explicit(&mc_gpu_command_buffers, 1, memory_order_relaxed);
			}
		}];
	}
	return self;
}

- (void)pin:(id)object {
	[_resources pin:object];
}

- (id<MTLBlitCommandEncoder>)blitEncoder {
	if (_blitEncoder == nil) {
		_blitEncoder = [self.commandBuffer blitCommandEncoder];
		_blitEncoder.label = @"MetalCraft batched transfers";
	}
	return _blitEncoder;
}

- (void)endBlitEncoding {
	if (_blitEncoder != nil) {
		[_blitEncoder endEncoding];
		_blitEncoder = nil;
	}
}

- (void)recordTimestampInPool:(MCMetalTimestampQueryPool *)pool index:(NSUInteger)index {
	uint64_t generation = [pool prepareSampleAtIndex:index];
	[self pin:pool];
	[self.commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> completed) {
		[pool markSampleAvailableAtIndex:index generation:generation];
	}];
}

- (NSUInteger)retainedResourceCount {
	return _resources.count;
}

@end


@interface MCMetalRenderPipeline : NSObject

@property(nonatomic, strong, readonly) id<MTLRenderPipelineState> pipelineState;
@property(nonatomic, strong, readonly) id<MTLDepthStencilState> depthStencilState;
@property(nonatomic, readonly) MTLCullMode cullMode;
@property(nonatomic, readonly) MTLTriangleFillMode fillMode;
@property(nonatomic, readonly) float depthBiasSlopeScale;
@property(nonatomic, readonly) float depthBiasConstant;

- (instancetype)initWithPipelineState:(id<MTLRenderPipelineState>)pipelineState
	depthStencilState:(id<MTLDepthStencilState>)depthStencilState
	cullMode:(MTLCullMode)cullMode
	fillMode:(MTLTriangleFillMode)fillMode
	depthBiasSlopeScale:(float)depthBiasSlopeScale
	depthBiasConstant:(float)depthBiasConstant;

@end


@implementation MCMetalRenderPipeline

- (instancetype)initWithPipelineState:(id<MTLRenderPipelineState>)pipelineState
	depthStencilState:(id<MTLDepthStencilState>)depthStencilState
	cullMode:(MTLCullMode)cullMode
	fillMode:(MTLTriangleFillMode)fillMode
	depthBiasSlopeScale:(float)depthBiasSlopeScale
	depthBiasConstant:(float)depthBiasConstant {
	self = [super init];
	if (self != nil) {
		_pipelineState = pipelineState;
		_depthStencilState = depthStencilState;
		_cullMode = cullMode;
		_fillMode = fillMode;
		_depthBiasSlopeScale = depthBiasSlopeScale;
		_depthBiasConstant = depthBiasConstant;
	}
	return self;
}

@end


@interface MCMetalRenderPass : NSObject

@property(nonatomic, strong, readonly) id<MTLRenderCommandEncoder> encoder;
@property(nonatomic, strong, readonly) MCMetalCommandBuffer *commandBuffer;
@property(nonatomic, readonly) NSUInteger width;
@property(nonatomic, readonly) NSUInteger height;
@property(nonatomic, readonly) BOOL ended;

- (instancetype)initWithEncoder:(id<MTLRenderCommandEncoder>)encoder
	commandBuffer:(MCMetalCommandBuffer *)commandBuffer
	width:(NSUInteger)width
	height:(NSUInteger)height;
- (void)end;

@end


@implementation MCMetalRenderPass

- (instancetype)initWithEncoder:(id<MTLRenderCommandEncoder>)encoder
	commandBuffer:(MCMetalCommandBuffer *)commandBuffer
	width:(NSUInteger)width
	height:(NSUInteger)height {
	self = [super init];
	if (self != nil) {
		_encoder = encoder;
		_commandBuffer = commandBuffer;
		_width = width;
		_height = height;
		_ended = NO;
	}
	return self;
}

- (void)end {
	if (!self.ended) {
		[self.encoder endEncoding];
		_ended = YES;
	}
}

@end

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
	}
	return @"Metal object";
}

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

static MTLPrimitiveType mc_primitive_type(JNIEnv *env, jint primitive) {
	switch (primitive) {
		case 0:
			return MTLPrimitiveTypePoint;
		case 1:
			return MTLPrimitiveTypeLine;
		case 2:
			return MTLPrimitiveTypeLineStrip;
		case 3:
			return MTLPrimitiveTypeTriangle;
		case 4:
			return MTLPrimitiveTypeTriangleStrip;
		default:
			mc_throw_state(env, [NSString stringWithFormat:@"Unsupported Metal primitive index %d", primitive]);
			return MTLPrimitiveTypeTriangle;
	}
}

static BOOL mc_require_main_thread(JNIEnv *env, NSString *operation) {
	if (NSThread.isMainThread) {
		return YES;
	}
	mc_throw_state(env, [NSString stringWithFormat:@"%@ must run on the AppKit main thread", operation]);
	return NO;
}

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
	}
}

MC_EXPORT JNIEXPORT jboolean JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nIsSupported(JNIEnv *env, jclass type) {
	@autoreleasepool {
		return MTLCreateSystemDefaultDevice() != nil ? JNI_TRUE : JNI_FALSE;
	}
}

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCreateDefaultDevice(JNIEnv *env, jclass type) {
	@autoreleasepool {
		id<MTLDevice> device = MTLCreateSystemDefaultDevice();
		return device == nil ? 0 : mc_register_object(device, MCObjectTypeDevice, 0);
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
		commandQueue.label = @"MetalCraft primary command queue";
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
		nativeCommandBuffer.label = @"MetalCraft frame command buffer";
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

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCommitCommandBuffer(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		MCMetalCommandBuffer *commandBuffer = (MCMetalCommandBuffer *)mc_get_object(env, handle, MCObjectTypeCommandBuffer);
		[commandBuffer endBlitEncoding];
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
Java_dev_metalcraft_client_metal_MetalNative_nCreateBuffer(
	JNIEnv *env,
	jclass type,
	jlong deviceHandle,
	jlong size,
	jint storageMode
) {
	@autoreleasepool {
		if (size <= 0 || (storageMode != 0 && storageMode != 1)) {
			mc_throw_state(env, @"A Metal buffer requires a positive size and a supported storage mode");
			return 0;
		}
		id<MTLDevice> device = (id<MTLDevice>)mc_get_object(env, deviceHandle, MCObjectTypeDevice);
		if (device == nil) {
			return 0;
		}
		MTLResourceOptions options = storageMode == 0
			? MTLResourceStorageModeShared
			: MTLResourceStorageModePrivate;
		id<MTLBuffer> buffer = [device newBufferWithLength:(NSUInteger)size options:options];
		if (buffer == nil) {
			mc_throw_state(env, @"Metal could not allocate the requested buffer");
			return 0;
		}
		buffer.label = @"MetalCraft buffer";
		return mc_register_object(buffer, MCObjectTypeBuffer, deviceHandle);
	}
}

MC_EXPORT JNIEXPORT jobject JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nMappedBufferBytes(
	JNIEnv *env,
	jclass type,
	jlong bufferHandle,
	jlong offset,
	jlong length
) {
	@autoreleasepool {
		id<MTLBuffer> buffer = (id<MTLBuffer>)mc_get_object(env, bufferHandle, MCObjectTypeBuffer);
		if (buffer == nil) {
			return NULL;
		}
		if (buffer.storageMode != MTLStorageModeShared) {
			mc_throw_state(env, @"Only shared Metal buffers can be mapped to Java memory");
			return NULL;
		}
		if (offset < 0 || length <= 0 || (NSUInteger)offset > buffer.length || (NSUInteger)length > buffer.length - (NSUInteger)offset) {
			mc_throw_state(env, @"Metal buffer mapping range is out of bounds");
			return NULL;
		}
		return (*env)->NewDirectByteBuffer(env, (uint8_t *)buffer.contents + (NSUInteger)offset, length);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyBuffer(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong sourceHandle,
	jlong sourceOffset,
	jlong destinationHandle,
	jlong destinationOffset,
	jlong size
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, sourceHandle, destinationHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeBuffer, MCObjectTypeBuffer};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) {
			return;
		}
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLBuffer> source = objects[1];
		id<MTLBuffer> destination = objects[2];
		if (sourceOffset < 0 || destinationOffset < 0 || size <= 0
			|| (NSUInteger)sourceOffset > source.length || (NSUInteger)size > source.length - (NSUInteger)sourceOffset
			|| (NSUInteger)destinationOffset > destination.length || (NSUInteger)size > destination.length - (NSUInteger)destinationOffset) {
			mc_throw_state(env, @"Metal buffer copy range is out of bounds");
			return;
		}
		[commandBuffer pin:source];
		[commandBuffer pin:destination];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromBuffer:source
			sourceOffset:(NSUInteger)sourceOffset
			toBuffer:destination
			destinationOffset:(NSUInteger)destinationOffset
			size:(NSUInteger)size];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyBufferToTexture(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong sourceHandle,
	jlong sourceOffset,
	jlong bytesPerRow,
	jlong textureHandle,
	jint mipLevel
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, sourceHandle, textureHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeBuffer, MCObjectTypeTexture};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) {
			return;
		}
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLBuffer> source = objects[1];
		id<MTLTexture> texture = objects[2];
		if (mipLevel < 0 || (NSUInteger)mipLevel >= texture.mipmapLevelCount) {
			mc_throw_state(env, @"Metal texture upload mip level is out of bounds");
			return;
		}
		NSUInteger width = MAX((NSUInteger)1, texture.width >> mipLevel);
		NSUInteger height = MAX((NSUInteger)1, texture.height >> mipLevel);
		NSUInteger bytesPerPixel = mc_bytes_per_pixel(texture.pixelFormat);
		if (sourceOffset < 0 || bytesPerRow < (jlong)(width * bytesPerPixel) || bytesPerRow % 256 != 0
			|| (NSUInteger)sourceOffset > source.length || (NSUInteger)(bytesPerRow * (jlong)height) > source.length - (NSUInteger)sourceOffset) {
			mc_throw_state(env, @"Metal texture upload staging range or row pitch is invalid");
			return;
		}
		[commandBuffer pin:source];
		[commandBuffer pin:texture];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromBuffer:source
			sourceOffset:(NSUInteger)sourceOffset
			sourceBytesPerRow:(NSUInteger)bytesPerRow
			sourceBytesPerImage:(NSUInteger)bytesPerRow * height
			sourceSize:MTLSizeMake(width, height, 1)
			toTexture:texture
			destinationSlice:0
			destinationLevel:(NSUInteger)mipLevel
			destinationOrigin:MTLOriginMake(0, 0, 0)];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyTextureToBuffer(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong textureHandle,
	jint mipLevel,
	jlong destinationHandle,
	jlong destinationOffset,
	jlong bytesPerRow
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, textureHandle, destinationHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeTexture, MCObjectTypeBuffer};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) {
			return;
		}
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLTexture> texture = objects[1];
		id<MTLBuffer> destination = objects[2];
		if (mipLevel < 0 || (NSUInteger)mipLevel >= texture.mipmapLevelCount) {
			mc_throw_state(env, @"Metal texture readback mip level is out of bounds");
			return;
		}
		NSUInteger width = MAX((NSUInteger)1, texture.width >> mipLevel);
		NSUInteger height = MAX((NSUInteger)1, texture.height >> mipLevel);
		NSUInteger bytesPerPixel = mc_bytes_per_pixel(texture.pixelFormat);
		if (destinationOffset < 0 || bytesPerRow < (jlong)(width * bytesPerPixel) || bytesPerRow % 256 != 0
			|| (NSUInteger)destinationOffset > destination.length || (NSUInteger)(bytesPerRow * (jlong)height) > destination.length - (NSUInteger)destinationOffset) {
			mc_throw_state(env, @"Metal texture readback staging range or row pitch is invalid");
			return;
		}
		[commandBuffer pin:texture];
		[commandBuffer pin:destination];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromTexture:texture
			sourceSlice:0
			sourceLevel:(NSUInteger)mipLevel
			sourceOrigin:MTLOriginMake(0, 0, 0)
			sourceSize:MTLSizeMake(width, height, 1)
			toBuffer:destination
			destinationOffset:(NSUInteger)destinationOffset
			destinationBytesPerRow:(NSUInteger)bytesPerRow
			destinationBytesPerImage:(NSUInteger)bytesPerRow * height];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyBufferToTextureRegion(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong sourceHandle,
	jlong sourceOffset,
	jlong bytesPerRow,
	jlong textureHandle,
	jint mipLevel,
	jint arrayLayer,
	jint destinationX,
	jint destinationY,
	jint width,
	jint height
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, sourceHandle, textureHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeBuffer, MCObjectTypeTexture};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) return;
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLBuffer> source = objects[1];
		id<MTLTexture> texture = objects[2];
		NSUInteger sliceCount = texture.textureType == MTLTextureTypeCube ? 6 : 1;
		if (mipLevel < 0 || (NSUInteger)mipLevel >= texture.mipmapLevelCount
			|| arrayLayer < 0 || (NSUInteger)arrayLayer >= sliceCount
			|| destinationX < 0 || destinationY < 0 || width <= 0 || height <= 0) {
			mc_throw_state(env, @"Metal texture upload region is invalid");
			return;
		}
		NSUInteger mipWidth = MAX((NSUInteger)1, texture.width >> mipLevel);
		NSUInteger mipHeight = MAX((NSUInteger)1, texture.height >> mipLevel);
		NSUInteger bytesPerPixel = mc_bytes_per_pixel(texture.pixelFormat);
		if ((NSUInteger)destinationX > mipWidth || (NSUInteger)width > mipWidth - (NSUInteger)destinationX
			|| (NSUInteger)destinationY > mipHeight || (NSUInteger)height > mipHeight - (NSUInteger)destinationY
			|| sourceOffset < 0 || bytesPerRow < (jlong)((NSUInteger)width * bytesPerPixel) || bytesPerRow % 256 != 0
			|| (NSUInteger)sourceOffset > source.length || (NSUInteger)(bytesPerRow * (jlong)height) > source.length - (NSUInteger)sourceOffset) {
			mc_throw_state(env, @"Metal texture upload staging range or row pitch is invalid");
			return;
		}
		[commandBuffer pin:source];
		[commandBuffer pin:texture];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromBuffer:source
			sourceOffset:(NSUInteger)sourceOffset
			sourceBytesPerRow:(NSUInteger)bytesPerRow
			sourceBytesPerImage:(NSUInteger)bytesPerRow * (NSUInteger)height
			sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
			toTexture:texture
			destinationSlice:(NSUInteger)arrayLayer
			destinationLevel:(NSUInteger)mipLevel
			destinationOrigin:MTLOriginMake((NSUInteger)destinationX, (NSUInteger)destinationY, 0)];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyTextureToBufferRegion(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong textureHandle,
	jint mipLevel,
	jint sourceX,
	jint sourceY,
	jint width,
	jint height,
	jlong destinationHandle,
	jlong destinationOffset,
	jlong bytesPerRow
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, textureHandle, destinationHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeTexture, MCObjectTypeBuffer};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) return;
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLTexture> texture = objects[1];
		id<MTLBuffer> destination = objects[2];
		if (mipLevel < 0 || (NSUInteger)mipLevel >= texture.mipmapLevelCount || sourceX < 0 || sourceY < 0 || width <= 0 || height <= 0) {
			mc_throw_state(env, @"Metal texture readback region is invalid");
			return;
		}
		NSUInteger mipWidth = MAX((NSUInteger)1, texture.width >> mipLevel);
		NSUInteger mipHeight = MAX((NSUInteger)1, texture.height >> mipLevel);
		NSUInteger bytesPerPixel = mc_bytes_per_pixel(texture.pixelFormat);
		if ((NSUInteger)sourceX > mipWidth || (NSUInteger)width > mipWidth - (NSUInteger)sourceX
			|| (NSUInteger)sourceY > mipHeight || (NSUInteger)height > mipHeight - (NSUInteger)sourceY
			|| destinationOffset < 0 || bytesPerRow < (jlong)((NSUInteger)width * bytesPerPixel) || bytesPerRow % 256 != 0
			|| (NSUInteger)destinationOffset > destination.length || (NSUInteger)(bytesPerRow * (jlong)height) > destination.length - (NSUInteger)destinationOffset) {
			mc_throw_state(env, @"Metal texture readback staging range or row pitch is invalid");
			return;
		}
		[commandBuffer pin:texture];
		[commandBuffer pin:destination];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromTexture:texture
			sourceSlice:0
			sourceLevel:(NSUInteger)mipLevel
			sourceOrigin:MTLOriginMake((NSUInteger)sourceX, (NSUInteger)sourceY, 0)
			sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
			toBuffer:destination
			destinationOffset:(NSUInteger)destinationOffset
			destinationBytesPerRow:(NSUInteger)bytesPerRow
			destinationBytesPerImage:(NSUInteger)bytesPerRow * (NSUInteger)height];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nCopyTexture(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong sourceHandle,
	jlong destinationHandle,
	jint mipLevel,
	jint sourceX,
	jint sourceY,
	jint destinationX,
	jint destinationY,
	jint width,
	jint height
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, sourceHandle, destinationHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeTexture, MCObjectTypeTexture};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) return;
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLTexture> source = objects[1];
		id<MTLTexture> destination = objects[2];
		if (source.pixelFormat != destination.pixelFormat || mipLevel < 0
			|| (NSUInteger)mipLevel >= source.mipmapLevelCount || (NSUInteger)mipLevel >= destination.mipmapLevelCount
			|| sourceX < 0 || sourceY < 0 || destinationX < 0 || destinationY < 0 || width <= 0 || height <= 0) {
			mc_throw_state(env, @"Metal texture copy format, mip level, or region is invalid");
			return;
		}
		NSUInteger sourceWidth = MAX((NSUInteger)1, source.width >> mipLevel);
		NSUInteger sourceHeight = MAX((NSUInteger)1, source.height >> mipLevel);
		NSUInteger destinationWidth = MAX((NSUInteger)1, destination.width >> mipLevel);
		NSUInteger destinationHeight = MAX((NSUInteger)1, destination.height >> mipLevel);
		if ((NSUInteger)sourceX > sourceWidth || (NSUInteger)width > sourceWidth - (NSUInteger)sourceX
			|| (NSUInteger)sourceY > sourceHeight || (NSUInteger)height > sourceHeight - (NSUInteger)sourceY
			|| (NSUInteger)destinationX > destinationWidth || (NSUInteger)width > destinationWidth - (NSUInteger)destinationX
			|| (NSUInteger)destinationY > destinationHeight || (NSUInteger)height > destinationHeight - (NSUInteger)destinationY) {
			mc_throw_state(env, @"Metal texture copy region is out of bounds");
			return;
		}
		[commandBuffer pin:source];
		[commandBuffer pin:destination];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromTexture:source
			sourceSlice:0
			sourceLevel:(NSUInteger)mipLevel
			sourceOrigin:MTLOriginMake((NSUInteger)sourceX, (NSUInteger)sourceY, 0)
			sourceSize:MTLSizeMake((NSUInteger)width, (NSUInteger)height, 1)
			toTexture:destination
			destinationSlice:0
			destinationLevel:(NSUInteger)mipLevel
			destinationOrigin:MTLOriginMake((NSUInteger)destinationX, (NSUInteger)destinationY, 0)];
	}
}

static id<MTLRenderPipelineState> mc_presentation_pipeline(JNIEnv *env, id<MTLDevice> device, MTLPixelFormat destinationFormat) {
	static NSLock *lock;
	static id<MTLDevice> cachedDevice;
	static MTLPixelFormat cachedFormat;
	static id<MTLRenderPipelineState> cachedPipeline;
	static dispatch_once_t onceToken;
	dispatch_once(&onceToken, ^{ lock = [[NSLock alloc] init]; });
	[lock lock];
	if (cachedPipeline != nil && cachedDevice == device && cachedFormat == destinationFormat) {
		id<MTLRenderPipelineState> result = cachedPipeline;
		[lock unlock];
		return result;
	}
	NSString *source = @"#include <metal_stdlib>\n"
		"using namespace metal;\n"
		"struct PresentOut { float4 position [[position]]; float2 uv; };\n"
		"vertex PresentOut metalcraft_present_vertex(uint id [[vertex_id]]) {\n"
		"  const float2 positions[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};\n"
		"  const float2 uvs[3] = {float2(0.0, 0.0), float2(2.0, 0.0), float2(0.0, 2.0)};\n"
		"  PresentOut out; out.position = float4(positions[id], 0.0, 1.0); out.uv = uvs[id]; return out;\n"
		"}\n"
		"fragment float4 metalcraft_present_fragment(PresentOut in [[stage_in]], texture2d<float> source [[texture(0)]]) {\n"
		"  constexpr sampler presentSampler(coord::normalized, address::clamp_to_edge, filter::linear);\n"
		"  return source.sample(presentSampler, in.uv);\n"
		"}\n";
	NSError *libraryError = nil;
	id<MTLLibrary> library = [device newLibraryWithSource:source options:nil error:&libraryError];
	if (library == nil) {
		[lock unlock];
		mc_throw_state(env, [NSString stringWithFormat:@"Metal presentation shader compilation failed: %@", libraryError.localizedDescription]);
		return nil;
	}
	MTLRenderPipelineDescriptor *descriptor = [[MTLRenderPipelineDescriptor alloc] init];
	descriptor.vertexFunction = [library newFunctionWithName:@"metalcraft_present_vertex"];
	descriptor.fragmentFunction = [library newFunctionWithName:@"metalcraft_present_fragment"];
	descriptor.colorAttachments[0].pixelFormat = destinationFormat;
	NSError *pipelineError = nil;
	id<MTLRenderPipelineState> pipeline = [device newRenderPipelineStateWithDescriptor:descriptor error:&pipelineError];
	if (pipeline == nil) {
		[lock unlock];
		mc_throw_state(env, [NSString stringWithFormat:@"Metal presentation pipeline creation failed: %@", pipelineError.localizedDescription]);
		return nil;
	}
	cachedDevice = device;
	cachedFormat = destinationFormat;
	cachedPipeline = pipeline;
	[lock unlock];
	return pipeline;
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nBlitTextureToDrawable(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong textureHandle,
	jlong drawableHandle
) {
	@autoreleasepool {
		jlong handles[] = {commandBufferHandle, textureHandle, drawableHandle};
		MCObjectType types[] = {MCObjectTypeCommandBuffer, MCObjectTypeTexture, MCObjectTypeDrawable};
		id objects[3];
		if (!mc_get_objects_same_device(env, handles, types, objects, 3)) return;
		MCMetalCommandBuffer *commandBuffer = objects[0];
		id<MTLTexture> source = objects[1];
		id<CAMetalDrawable> drawable = objects[2];
		id<MTLTexture> destination = drawable.texture;
		[commandBuffer pin:source];
		[commandBuffer pin:drawable];
		if (source.pixelFormat == destination.pixelFormat && source.width == destination.width && source.height == destination.height) {
			id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
			[blit copyFromTexture:source toTexture:destination];
		} else {
			[commandBuffer endBlitEncoding];
			id<MTLRenderPipelineState> pipeline = mc_presentation_pipeline(env, commandBuffer.commandBuffer.device, destination.pixelFormat);
			if (pipeline == nil) return;
			MTLRenderPassDescriptor *descriptor = [MTLRenderPassDescriptor renderPassDescriptor];
			descriptor.colorAttachments[0].texture = destination;
			descriptor.colorAttachments[0].loadAction = MTLLoadActionDontCare;
			descriptor.colorAttachments[0].storeAction = MTLStoreActionStore;
			id<MTLRenderCommandEncoder> encoder = [commandBuffer.commandBuffer renderCommandEncoderWithDescriptor:descriptor];
			[encoder setRenderPipelineState:pipeline];
			[encoder setFragmentTexture:source atIndex:0];
			[encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
			[encoder endEncoding];
		}
	}
}

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
		event.label = @"MetalCraft GPU fence";
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
		descriptor.label = @"MetalCraft timestamp queries";
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
			mc_throw_state(env, @"MetalCraft could not allocate timestamp availability tracking");
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
			mc_throw_state(env, @"MetalCraft could not allocate timestamp query results");
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
	jboolean cubemap
) {
	@autoreleasepool {
		BOOL isCubemap = cubemap == JNI_TRUE;
		BOOL validShape = isCubemap ? width == height && depthOrLayers == 6 : depthOrLayers == 1;
		if (width <= 0 || height <= 0 || mipLevels <= 0 || !validShape || (usage & ~7) != 0) {
			mc_throw_state(env, @"A Metal texture requires positive dimensions, mip levels, and valid usage bits");
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
		MTLTextureDescriptor *descriptor = [[MTLTextureDescriptor alloc] init];
		descriptor.textureType = isCubemap ? MTLTextureTypeCube : MTLTextureType2D;
		descriptor.pixelFormat = pixelFormat;
		descriptor.width = (NSUInteger)width;
		descriptor.height = (NSUInteger)height;
		descriptor.depth = 1;
		descriptor.mipmapLevelCount = (NSUInteger)mipLevels;
		descriptor.arrayLength = 1;
		descriptor.sampleCount = 1;
		descriptor.storageMode = MTLStorageModePrivate;
		descriptor.usage = MTLTextureUsageUnknown;
		if ((usage & 1) != 0) descriptor.usage |= MTLTextureUsageShaderRead;
		if ((usage & 2) != 0) descriptor.usage |= MTLTextureUsageShaderWrite;
		if ((usage & 4) != 0) descriptor.usage |= MTLTextureUsageRenderTarget;
		id<MTLTexture> texture = [device newTextureWithDescriptor:descriptor];
		if (texture == nil) {
			mc_throw_state(env, @"Metal could not allocate the requested texture");
			return 0;
		}
		texture.label = @"MetalCraft texture";
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
		NSUInteger sliceCount = texture.textureType == MTLTextureTypeCube ? 6 : 1;
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
		textureView.label = @"MetalCraft texture view";
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
	jdouble maxLod
) {
	@autoreleasepool {
		if ((minFilter != 0 && minFilter != 1) || (magFilter != 0 && magFilter != 1)
			|| addressModeU < 0 || addressModeU > 2 || addressModeV < 0 || addressModeV > 2
			|| maxAnisotropy < 1 || maxAnisotropy > 16 || isnan(maxLod) || maxLod < 0.0) {
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
		descriptor.label = @"MetalCraft sampler";
		id<MTLSamplerState> sampler = [device newSamplerStateWithDescriptor:descriptor];
		if (sampler == nil) {
			mc_throw_state(env, @"Metal could not create the requested sampler");
			return 0;
		}
		return mc_register_object(sampler, MCObjectTypeSampler, deviceHandle);
	}
}

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
	jintArray layoutStepRatesValue
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
		id<MTLLibrary> vertexLibrary = [device newLibraryWithSource:vertexSource options:nil error:&vertexLibraryError];
		if (vertexLibrary == nil) {
			mc_throw_state(env, [NSString stringWithFormat:@"Metal vertex-shader compilation failed: %@", vertexLibraryError.localizedDescription]);
			return 0;
		}
		NSError *fragmentLibraryError = nil;
		id<MTLLibrary> fragmentLibrary = [device newLibraryWithSource:fragmentSource options:nil error:&fragmentLibraryError];
		if (fragmentLibrary == nil) {
			mc_throw_state(env, [NSString stringWithFormat:@"Metal fragment-shader compilation failed: %@", fragmentLibraryError.localizedDescription]);
			return 0;
		}
		id<MTLFunction> vertexFunction = [vertexLibrary newFunctionWithName:vertexFunctionName];
		id<MTLFunction> fragmentFunction = [fragmentLibrary newFunctionWithName:fragmentFunctionName];
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
		descriptor.label = @"MetalCraft render pipeline";
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
		id<MTLRenderPipelineState> pipelineState = [device newRenderPipelineStateWithDescriptor:descriptor error:&pipelineError];
		if (pipelineState == nil) {
			mc_throw_state(env, [NSString stringWithFormat:@"Metal render-pipeline creation failed: %@", pipelineError.localizedDescription]);
			return 0;
		}

		MTLDepthStencilDescriptor *depthDescriptor = [[MTLDepthStencilDescriptor alloc] init];
		depthDescriptor.depthCompareFunction = depthTestEnabled ? nativeCompareFunction : MTLCompareFunctionAlways;
		depthDescriptor.depthWriteEnabled = depthTestEnabled && depthWriteEnabled;
		depthDescriptor.label = @"MetalCraft depth-stencil state";
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

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nBeginRenderPass(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlong colorTargetHandle,
	jboolean colorTargetIsDrawable,
	jint colorMipLevel,
	jint colorLoadAction,
	jint colorStoreAction,
	jdouble clearRed,
	jdouble clearGreen,
	jdouble clearBlue,
	jdouble clearAlpha,
	jlong depthTargetHandle,
	jint depthMipLevel,
	jint depthLoadAction,
	jint depthStoreAction,
	jdouble clearDepth
) {
	@autoreleasepool {
		// A pass may omit either attachment. A depth-only pass is how a depth clear is expressed
		// without inventing a full-size colour target for the encoder to ignore.
		if (colorTargetHandle == 0 && depthTargetHandle == 0) {
			mc_throw_state(env, @"A Metal render pass requires at least one attachment");
			return 0;
		}
		jlong handles[3];
		MCObjectType types[3];
		id objects[3];
		NSUInteger objectCount = 0;
		NSInteger colorIndex = -1;
		NSInteger depthIndex = -1;
		handles[objectCount] = commandBufferHandle;
		types[objectCount] = MCObjectTypeCommandBuffer;
		objectCount++;
		if (colorTargetHandle != 0) {
			colorIndex = (NSInteger)objectCount;
			handles[objectCount] = colorTargetHandle;
			types[objectCount] = colorTargetIsDrawable ? MCObjectTypeDrawable : MCObjectTypeTexture;
			objectCount++;
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
		id<MTLTexture> colorTexture = nil;
		if (colorIndex >= 0) {
			colorTexture = colorTargetIsDrawable
				? [(id<CAMetalDrawable>)objects[colorIndex] texture]
				: (id<MTLTexture>)objects[colorIndex];
		}
		id<MTLTexture> depthTexture = depthIndex < 0 ? nil : (id<MTLTexture>)objects[depthIndex];
		if (colorTexture != nil
			&& (colorMipLevel < 0 || (NSUInteger)colorMipLevel >= colorTexture.mipmapLevelCount
				|| colorTargetIsDrawable && colorMipLevel != 0)) {
			mc_throw_state(env, @"Metal render-pass attachment mip level is out of bounds");
			return 0;
		}
		if (depthTexture != nil && (depthMipLevel < 0 || (NSUInteger)depthMipLevel >= depthTexture.mipmapLevelCount)) {
			mc_throw_state(env, @"Metal render-pass attachment mip level is out of bounds");
			return 0;
		}
		if (colorTexture != nil
			&& (mc_pixel_format_has_depth(colorTexture.pixelFormat) || mc_pixel_format_has_stencil(colorTexture.pixelFormat))) {
			mc_throw_state(env, @"A depth/stencil texture cannot be used as a Metal color attachment");
			return 0;
		}
		if (depthTexture != nil && !mc_pixel_format_has_depth(depthTexture.pixelFormat)) {
			mc_throw_state(env, @"A Metal depth attachment requires a depth texture");
			return 0;
		}
		NSUInteger colorWidth = colorTexture == nil ? 0 : MAX((NSUInteger)1, colorTexture.width >> colorMipLevel);
		NSUInteger colorHeight = colorTexture == nil ? 0 : MAX((NSUInteger)1, colorTexture.height >> colorMipLevel);
		NSUInteger depthWidth = depthTexture == nil ? 0 : MAX((NSUInteger)1, depthTexture.width >> depthMipLevel);
		NSUInteger depthHeight = depthTexture == nil ? 0 : MAX((NSUInteger)1, depthTexture.height >> depthMipLevel);
		if (colorTexture != nil && depthTexture != nil && (depthWidth != colorWidth || depthHeight != colorHeight)) {
			mc_throw_state(env, @"Metal render-pass attachments must have matching dimensions");
			return 0;
		}
		if (colorTexture == nil) {
			colorWidth = depthWidth;
			colorHeight = depthHeight;
		}

		MTLLoadAction nativeColorLoadAction = colorTexture == nil ? MTLLoadActionDontCare : mc_load_action(env, colorLoadAction);
		MTLStoreAction nativeColorStoreAction = colorTexture == nil ? MTLStoreActionDontCare : mc_store_action(env, colorStoreAction);
		MTLLoadAction nativeDepthLoadAction = depthTexture == nil ? MTLLoadActionDontCare : mc_load_action(env, depthLoadAction);
		MTLStoreAction nativeDepthStoreAction = depthTexture == nil ? MTLStoreActionDontCare : mc_store_action(env, depthStoreAction);
		if ((*env)->ExceptionCheck(env)) {
			return 0;
		}

		MTLRenderPassDescriptor *descriptor = [MTLRenderPassDescriptor renderPassDescriptor];
		if (colorTexture != nil) {
			descriptor.colorAttachments[0].texture = colorTexture;
			descriptor.colorAttachments[0].level = (NSUInteger)colorMipLevel;
			descriptor.colorAttachments[0].loadAction = nativeColorLoadAction;
			descriptor.colorAttachments[0].storeAction = nativeColorStoreAction;
			descriptor.colorAttachments[0].clearColor = MTLClearColorMake(clearRed, clearGreen, clearBlue, clearAlpha);
		} else {
			// Without a colour attachment Metal cannot infer the pass dimensions from one.
			descriptor.renderTargetWidth = colorWidth;
			descriptor.renderTargetHeight = colorHeight;
		}
		if (depthTexture != nil) {
			descriptor.depthAttachment.texture = depthTexture;
			descriptor.depthAttachment.level = (NSUInteger)depthMipLevel;
			descriptor.depthAttachment.loadAction = nativeDepthLoadAction;
			descriptor.depthAttachment.storeAction = nativeDepthStoreAction;
			descriptor.depthAttachment.clearDepth = clearDepth;
			if (mc_pixel_format_has_stencil(depthTexture.pixelFormat)) {
				descriptor.stencilAttachment.texture = depthTexture;
				descriptor.stencilAttachment.level = (NSUInteger)depthMipLevel;
				descriptor.stencilAttachment.loadAction = nativeDepthLoadAction;
				descriptor.stencilAttachment.storeAction = nativeDepthStoreAction;
				descriptor.stencilAttachment.clearStencil = 0;
			}
		}

		if (colorIndex >= 0) {
			[commandBuffer pin:objects[colorIndex]];
		}
		if (depthTexture != nil) {
			[commandBuffer pin:depthTexture];
		}
		id<MTLRenderCommandEncoder> encoder = [commandBuffer.commandBuffer renderCommandEncoderWithDescriptor:descriptor];
		if (encoder == nil) {
			mc_throw_state(env, @"Metal did not create a render command encoder");
			return 0;
		}
		encoder.label = @"MetalCraft render pass";
		[encoder setViewport:(MTLViewport){0.0, 0.0, colorWidth, colorHeight, 0.0, 1.0}];
		[encoder setScissorRect:(MTLScissorRect){0, 0, colorWidth, colorHeight}];
		MCMetalRenderPass *renderPass = [[MCMetalRenderPass alloc]
			initWithEncoder:encoder
			commandBuffer:commandBuffer
			width:colorWidth
			height:colorHeight
		];
		return mc_register_object(renderPass, MCObjectTypeRenderPass, commandBufferHandle);
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
			texture.label = @"MetalCraft texel-buffer view";
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
			mc_throw_state(env, @"The selected Metal device does not support timestamps within a render pass");
			return;
		}
		[renderPass.commandBuffer recordTimestampInPool:pool index:(NSUInteger)index];
		[renderPass.encoder sampleCountersInBuffer:pool.sampleBuffer atSampleIndex:(NSUInteger)index withBarrier:YES];
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nEndRenderPass(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeRenderPass);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nReleaseDevice(JNIEnv *env, jclass type, jlong handle) {
	@autoreleasepool {
		mc_release_object(env, handle, MCObjectTypeDevice);
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
