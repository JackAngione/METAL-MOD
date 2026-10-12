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
		// Presentation copies already encoded RGB (including the hand and HUD).
		// Tag those bytes for the compositor; an sRGB attachment would encode twice
		// once the world grade supplies its explicit linear-to-sRGB transfer.
		CGColorSpaceRef presentationColorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
		_layer.colorspace = presentationColorSpace;
		CGColorSpaceRelease(presentationColorSpace);
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


/**
 * GPU time attributed to individual render passes rather than to a whole command buffer.
 *
 * <p>`mc_gpu_nanos` can say that the GPU was busy for 3.4 ms of a frame, but not which pass spent
 * it. That is the question that decides whether an attachment store or a pass merge is worth
 * doing, and it cannot be answered by a command-buffer total that already mixes every pass of the
 * frame together.
 *
 * <p>Metal answers it with counter samples taken at encoder boundaries: a render-pass descriptor
 * carries a sample buffer and a set of indices, and the GPU writes a timestamp as the pass's
 * vertex stage begins and as its fragment stage ends.
 *
 * <p><b>The sampling point is the whole difficulty.</b> Apple silicon supports
 * `MTLCounterSamplingPointAtStageBoundary` and nothing else - an M4 Max reports draw, blit and
 * dispatch boundaries as unavailable - so a timestamp can be taken at a pass's edges but never
 * part-way through it. That is what this samples, and it is also why `nWriteRenderTimestamp`,
 * which needs a draw boundary, can never succeed on the hardware this backend targets.
 *
 * <p>The resolved timestamps are nanoseconds on the same timebase as `MTLCommandBuffer`'s own
 * `GPUStartTime`: one pass measured both ways agrees to the nanosecond, so nothing here converts
 * units or calibrates against the CPU clock.
 *
 * <p>Passes are keyed by a small integer that the Java side interns from the Blaze3D pass label,
 * so the render path crosses JNI with an int rather than a string.
 */
#define MC_GPU_PASS_KINDS 32
/**
 * Passes the sample buffer can hold before its slots are reused, two timestamps each.
 *
 * <p>A slot is read once its command buffer completes, so the ring only has to outlast the passes
 * in flight - a few frames' worth, against the tens of passes per frame this scene records.
 *
 * <p>Headroom is not relied on, though. A slot reused before its old pass was resolved would hand
 * the old pass the new one's timestamps, and the result would be plausible rather than obviously
 * wrong - so each pass carries the sequence number it was allocated at, and resolving one that has
 * since been lapped drops it instead. That turns a silent wrong number into a missing one.
 */
#define MC_GPU_PASS_SLOTS 512

static _Atomic uint64_t mc_gpu_pass_nanos[MC_GPU_PASS_KINDS];
static _Atomic uint64_t mc_gpu_pass_counts[MC_GPU_PASS_KINDS];
static _Atomic uint64_t mc_gpu_pass_next_slot;
static os_unfair_lock mc_gpu_pass_lock = OS_UNFAIR_LOCK_INIT;
static id<MTLCounterSampleBuffer> mc_gpu_pass_samples;
static id<MTLDevice> mc_gpu_pass_device;
/** A device that cannot sample is recorded once, rather than retried on every pass. */
static BOOL mc_gpu_pass_unavailable;

static id<MTLCounterSet> mc_timestamp_counter_set(id<MTLDevice> device);

/** The shared pass sample buffer for {@code device}, creating it on first use, or nil. */
static id<MTLCounterSampleBuffer> mc_gpu_pass_sample_buffer(id<MTLDevice> device) {
	if (device == nil) {
		return nil;
	}
	os_unfair_lock_lock(&mc_gpu_pass_lock);
	if (mc_gpu_pass_device != device) {
		// A second device - or one opened after the first was released - starts over rather than
		// sampling into a buffer that belongs to a device it is not encoding on.
		mc_gpu_pass_samples = nil;
		mc_gpu_pass_device = nil;
		mc_gpu_pass_unavailable = NO;
	}
	if (mc_gpu_pass_samples == nil && !mc_gpu_pass_unavailable) {
		id<MTLCounterSet> timestampSet = mc_timestamp_counter_set(device);
		if (timestampSet != nil && [device supportsCounterSampling:MTLCounterSamplingPointAtStageBoundary]) {
			MTLCounterSampleBufferDescriptor *descriptor = [[MTLCounterSampleBufferDescriptor alloc] init];
			descriptor.counterSet = timestampSet;
			descriptor.label = @"Metal Mod pass GPU timing";
			descriptor.storageMode = MTLStorageModeShared;
			descriptor.sampleCount = MC_GPU_PASS_SLOTS * 2;
			NSError *error = nil;
			mc_gpu_pass_samples = [device newCounterSampleBufferWithDescriptor:descriptor error:&error];
		}
		mc_gpu_pass_unavailable = mc_gpu_pass_samples == nil;
		mc_gpu_pass_device = mc_gpu_pass_samples == nil ? nil : device;
	}
	id<MTLCounterSampleBuffer> samples = mc_gpu_pass_samples;
	os_unfair_lock_unlock(&mc_gpu_pass_lock);
	return samples;
}

/**
 * Drops the sample buffer, so it cannot outlive the device that created it.
 *
 * <p>Called when any device is released rather than only the owning one, because the registry has
 * no lookup that tolerates an already-closed handle. Dropping one device's buffer because another
 * closed costs a recreation on the next timed pass and nothing else.
 */
static void mc_gpu_pass_reset(void) {
	os_unfair_lock_lock(&mc_gpu_pass_lock);
	mc_gpu_pass_samples = nil;
	mc_gpu_pass_device = nil;
	mc_gpu_pass_unavailable = NO;
	os_unfair_lock_unlock(&mc_gpu_pass_lock);
}

/**
 * The timed passes one command buffer holds, resolved together when it completes.
 *
 * <p>Held by an object of its own rather than by the command buffer, for the same reason
 * {@link MCInFlightResources} is: the completion handler has to retain what it reads, and a block
 * that captured the command buffer would keep it alive through its own handler.
 */
@interface MCGpuPassSamples : NSObject

- (instancetype)initWithSampleBuffer:(id<MTLCounterSampleBuffer>)sampleBuffer;
- (void)addKind:(uint32_t)kind sequence:(uint64_t)sequence;
- (void)resolve;

@end


/** One timed pass, and the ring position it was sampled into. */
typedef struct {
	uint64_t sequence;
	uint32_t kind;
	uint32_t reserved;
} MCGpuPassEntry;


@implementation MCGpuPassSamples {
	NSLock *_lock;
	id<MTLCounterSampleBuffer> _sampleBuffer;
	NSMutableData *_entries;
}

- (instancetype)initWithSampleBuffer:(id<MTLCounterSampleBuffer>)sampleBuffer {
	self = [super init];
	if (self != nil) {
		_lock = [[NSLock alloc] init];
		_sampleBuffer = sampleBuffer;
		_entries = [NSMutableData data];
	}
	return self;
}

- (void)addKind:(uint32_t)kind sequence:(uint64_t)sequence {
	MCGpuPassEntry entry = {.sequence = sequence, .kind = kind, .reserved = 0};
	[_lock lock];
	[_entries appendBytes:&entry length:sizeof(entry)];
	[_lock unlock];
}

- (void)resolve {
	[_lock lock];
	NSData *entries = [_entries copy];
	[_entries setLength:0];
	id<MTLCounterSampleBuffer> sampleBuffer = _sampleBuffer;
	[_lock unlock];

	uint64_t allocated = atomic_load_explicit(&mc_gpu_pass_next_slot, memory_order_relaxed);
	NSUInteger count = entries.length / sizeof(MCGpuPassEntry);
	const MCGpuPassEntry *values = (const MCGpuPassEntry *)entries.bytes;
	for (NSUInteger index = 0; index < count; index++) {
		uint32_t kind = values[index].kind;
		// Lapped: another pass has taken this slot since, so whatever is in it is not this pass.
		if (kind >= MC_GPU_PASS_KINDS || allocated - values[index].sequence >= MC_GPU_PASS_SLOTS) {
			continue;
		}
		NSUInteger slot = (NSUInteger)(values[index].sequence % MC_GPU_PASS_SLOTS);
		NSData *resolved = [sampleBuffer resolveCounterRange:NSMakeRange(slot * 2, 2)];
		if (resolved.length < 2 * sizeof(MTLCounterResultTimestamp)) {
			continue;
		}
		const MTLCounterResultTimestamp *timestamps = (const MTLCounterResultTimestamp *)resolved.bytes;
		uint64_t start = timestamps[0].timestamp;
		uint64_t end = timestamps[1].timestamp;
		// A pass Metal declined to sample reports the error value. Dropped rather than folded in,
		// so a bad sample costs a missing pass instead of a wrong total.
		if (start == MTLCounterErrorValue || end == MTLCounterErrorValue || end <= start) {
			continue;
		}
		atomic_fetch_add_explicit(&mc_gpu_pass_nanos[kind], end - start, memory_order_relaxed);
		atomic_fetch_add_explicit(&mc_gpu_pass_counts[kind], 1, memory_order_relaxed);
	}
}

@end

@interface MCInFlightResources : NSObject

- (void)pin:(id)object;
- (void)pinAll:(id __unsafe_unretained const *)objects count:(NSUInteger)count;
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

/**
 * Retains a whole batch's resources under one acquisition.
 *
 * <p>Pinning per command takes this lock per command, which is a second lock round-trip on top of
 * the registry's for every bind and draw a frame issues. The set still hashes each object, and
 * duplicates - the index buffer every draw in a batch shares, most obviously - are absorbed by the
 * set rather than filtered here.
 */
- (void)pinAll:(id __unsafe_unretained const *)objects count:(NSUInteger)count {
	[_lock lock];
	for (NSUInteger index = 0; index < count; index++) {
		if (objects[index] != nil) {
			[_objects addObject:objects[index]];
		}
	}
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
- (void)pinAll:(id __unsafe_unretained const *)objects count:(NSUInteger)count;
- (id<MTLBlitCommandEncoder>)blitEncoder;
- (void)endBlitEncoding;
- (void)recordTimestampInPool:(MCMetalTimestampQueryPool *)pool index:(NSUInteger)index;
- (void)addTimedPassKind:(uint32_t)kind sequence:(uint64_t)sequence sampleBuffer:(id<MTLCounterSampleBuffer>)sampleBuffer;
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

// Benchmark-only frame aggregation. Slots are phase-owned; late callbacks cannot
// contaminate the next capture. Disabled rendering allocates nothing and takes no lock.
#define MC_GPU_CAPTURE_FRAMES 32768
typedef struct {
	uint64_t start, end, submitted, pending;
	BOOL sealed, invalid;
} MCGpuCaptureFrame;
static MCGpuCaptureFrame mc_gpu_capture_frames[MC_GPU_CAPTURE_FRAMES];
static os_unfair_lock mc_gpu_capture_lock = OS_UNFAIR_LOCK_INIT;
static uint64_t mc_gpu_capture_epoch, mc_gpu_capture_count;
static BOOL mc_gpu_capture_active;
static _Thread_local uint64_t mc_gpu_thread_epoch, mc_gpu_thread_frame;

static void mc_capture_command_buffer(id<MTLCommandBuffer> buffer) {
	if (mc_gpu_thread_frame == 0) return;
	uint64_t epoch = mc_gpu_thread_epoch, index = mc_gpu_thread_frame - 1;
	os_unfair_lock_lock(&mc_gpu_capture_lock);
	BOOL admitted = mc_gpu_capture_active && epoch == mc_gpu_capture_epoch && index < MC_GPU_CAPTURE_FRAMES;
	if (admitted) {
		mc_gpu_capture_frames[index].submitted++;
		mc_gpu_capture_frames[index].pending++;
	}
	os_unfair_lock_unlock(&mc_gpu_capture_lock);
	if (!admitted) return;
	[buffer addCompletedHandler:^(id<MTLCommandBuffer> completed) {
		CFTimeInterval start = completed.GPUStartTime, end = completed.GPUEndTime;
		BOOL valid = completed.status == MTLCommandBufferStatusCompleted
			&& isfinite(start) && isfinite(end) && start > 0 && end > start;
		os_unfair_lock_lock(&mc_gpu_capture_lock);
		if (mc_gpu_capture_active && epoch == mc_gpu_capture_epoch) {
			MCGpuCaptureFrame *frame = &mc_gpu_capture_frames[index];
			frame->pending--;
			if (!valid) frame->invalid = YES;
			else {
				uint64_t startNs = (uint64_t)(start * 1e9), endNs = (uint64_t)(end * 1e9);
				frame->start = frame->start == 0 ? startNs : MIN(frame->start, startNs);
				frame->end = MAX(frame->end, endNs);
			}
		}
		os_unfair_lock_unlock(&mc_gpu_capture_lock);
	}];
}

@implementation MCMetalCommandBuffer {
	MCInFlightResources *_resources;
	id<MTLBlitCommandEncoder> _blitEncoder;
	MCGpuPassSamples *_gpuPasses;
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

- (void)pinAll:(id __unsafe_unretained const *)objects count:(NSUInteger)count {
	[_resources pinAll:objects count:count];
}

- (id<MTLBlitCommandEncoder>)blitEncoder {
	if (_blitEncoder == nil) {
		_blitEncoder = [self.commandBuffer blitCommandEncoder];
		_blitEncoder.label = @"Metal Mod batched transfers";
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

/**
 * Notes that this command buffer carries one pass, sampled at ring position {@code sequence}.
 *
 * <p>The completion handler is added on the first timed pass rather than in the initialiser, so a
 * command buffer encoded while nothing is being measured pays for neither the handler nor the
 * object. Every pass is encoded before the buffer is committed, so there is always a commit left
 * to add it to.
 */
- (void)addTimedPassKind:(uint32_t)kind sequence:(uint64_t)sequence sampleBuffer:(id<MTLCounterSampleBuffer>)sampleBuffer {
	if (_gpuPasses == nil) {
		_gpuPasses = [[MCGpuPassSamples alloc] initWithSampleBuffer:sampleBuffer];
		MCGpuPassSamples *passes = _gpuPasses;
		[self.commandBuffer addCompletedHandler:^(id<MTLCommandBuffer> completed) {
			[passes resolve];
		}];
	}
	[_gpuPasses addKind:kind sequence:sequence];
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
@property(nonatomic, readonly) NSUInteger colorMask;
@property(nonatomic, readonly) BOOL hasDepth;
@property(nonatomic, readonly) BOOL hasStencil;
@property(nonatomic, readonly) NSUInteger mutableStoreMask;
@property(nonatomic, readonly) BOOL mutableDepthStore;
@property(nonatomic, readonly) BOOL mutableStencilStore;
@property(nonatomic) NSUInteger discardedColors;
@property(nonatomic) BOOL discardedDepth;

- (instancetype)initWithEncoder:(id<MTLRenderCommandEncoder>)encoder
	commandBuffer:(MCMetalCommandBuffer *)commandBuffer
	descriptor:(MTLRenderPassDescriptor *)descriptor
	width:(NSUInteger)width
	height:(NSUInteger)height;
- (void)end;

@end


@implementation MCMetalRenderPass

- (instancetype)initWithEncoder:(id<MTLRenderCommandEncoder>)encoder
	commandBuffer:(MCMetalCommandBuffer *)commandBuffer
	descriptor:(MTLRenderPassDescriptor *)descriptor
	width:(NSUInteger)width
	height:(NSUInteger)height {
	self = [super init];
	if (self != nil) {
		_encoder = encoder;
		_commandBuffer = commandBuffer;
		_width = width;
		_height = height;
		_ended = NO;
		for (NSUInteger i = 0; i < 8; i++) {
            if (descriptor.colorAttachments[i].texture != nil) _colorMask |= 1u << i;
            if (descriptor.colorAttachments[i].texture != nil && descriptor.colorAttachments[i].storeAction == MTLStoreActionUnknown)
                _mutableStoreMask |= 1u << i;
        }
        _mutableDepthStore = descriptor.depthAttachment.texture != nil && descriptor.depthAttachment.storeAction == MTLStoreActionUnknown;
        _mutableStencilStore = descriptor.stencilAttachment.texture != nil && descriptor.stencilAttachment.storeAction == MTLStoreActionUnknown;
		_hasDepth = descriptor.depthAttachment.texture != nil;
		_hasStencil = descriptor.stencilAttachment.texture != nil;
	}
	return self;
}

- (void)end {
	if (!self.ended) {
        // Metal only allows late store decisions on attachments begun with Unknown.
        // Every exit path resolves them, including direct native passes and shutdown.
        for (NSUInteger i = 0; i < 8; i++) if (self.mutableStoreMask & (1u << i))
            [self.encoder setColorStoreAction:(self.discardedColors & (1u << i)) ? MTLStoreActionDontCare : MTLStoreActionStore atIndex:i];
        if (self.mutableDepthStore) [self.encoder setDepthStoreAction:self.discardedDepth ? MTLStoreActionDontCare : MTLStoreActionStore];
        if (self.mutableStencilStore) [self.encoder setStencilStoreAction:self.discardedDepth ? MTLStoreActionDontCare : MTLStoreActionStore];
		[self.encoder endEncoding];
		_ended = YES;
	}
}

@end


@interface MCMetalComputePass : NSObject

@property(nonatomic, strong, readonly) id<MTLComputeCommandEncoder> encoder;
@property(nonatomic, strong, readonly) MCMetalCommandBuffer *commandBuffer;
@property(nonatomic, readonly) BOOL ended;

- (instancetype)initWithEncoder:(id<MTLComputeCommandEncoder>)encoder commandBuffer:(MCMetalCommandBuffer *)commandBuffer;
- (void)end;

@end


@implementation MCMetalComputePass

- (instancetype)initWithEncoder:(id<MTLComputeCommandEncoder>)encoder commandBuffer:(MCMetalCommandBuffer *)commandBuffer {
	self = [super init];
	if (self != nil) {
		_encoder = encoder;
		_commandBuffer = commandBuffer;
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
