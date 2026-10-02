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
#import <mach/mach.h>

#define MC_EXPORT __attribute__((visibility("default")))

// Opt-in driver observations at allocation/acquisition boundaries. Per MTLDevice,
// not logical LOD payload accounting; hidden driver-only transients can be missed.
@interface MCAllocationProbe : NSObject {
@public
    atomic_uint_fast64_t peak;
    atomic_uint_fast64_t samples;
}
@end
@implementation MCAllocationProbe
- (instancetype)init {
    self = [super init];
    if (self) { atomic_init(&peak, 0); atomic_init(&samples, 0); }
    return self;
}
@end
static char MCAllocationProbeKey;
static uint64_t mc_sample_allocation(id<MTLDevice> device) {
    MCAllocationProbe *probe = objc_getAssociatedObject(device, &MCAllocationProbeKey);
    if (probe == nil) return 0;
    uint64_t bytes = device.currentAllocatedSize;
    uint_fast64_t before = atomic_load_explicit(&probe->peak, memory_order_relaxed);
    while (before < bytes && !atomic_compare_exchange_weak_explicit(&probe->peak,
            &before, bytes, memory_order_relaxed, memory_order_relaxed)) { }
    atomic_fetch_add_explicit(&probe->samples, 1, memory_order_relaxed);
    return bytes;
}

// Cache expanded programs by exact source. Entry limits also bound retained native libraries.
@interface MCLibraryEntry : NSObject
@property(nonatomic, strong) id<MTLLibrary> library;
@property(nonatomic, strong) NSMutableDictionary<NSString *, id<MTLFunction>> *functions;
@end
@implementation MCLibraryEntry
@end

@interface MCPipelineCache : NSObject {
@public
    NSCache<NSString *, MCLibraryEntry *> *libraries;
    id<MTLBinaryArchive> archive;
    NSURL *archiveURL;
    NSUInteger owners, libraryHits, libraryMisses, archiveHits, archiveAdds;
    BOOL archiveLoaded, dirty;
}
- (instancetype)initWithDevice:(id<MTLDevice>)device directory:(NSString *)directory;
- (void)save;
@end
static char MCPipelineCacheKey;
static const unsigned long long MCPipelineArchiveLimit = 64ULL * 1024 * 1024;

@implementation MCPipelineCache
- (instancetype)initWithDevice:(id<MTLDevice>)device directory:(NSString *)directory {
    self = [super init];
    if (self) {
        libraries = [[NSCache alloc] init];
        libraries.countLimit = 256;
        libraries.totalCostLimit = 16 * 1024 * 1024;
        if (directory.length > 0) {
            NSFileManager *files = NSFileManager.defaultManager;
            NSURL *root = [NSURL fileURLWithPath:directory isDirectory:YES];
            if ([files createDirectoryAtURL:root withIntermediateDirectories:YES attributes:nil error:nil]) {
                NSString *name = [NSString stringWithFormat:@"%@-%llx.metalarc", NSProcessInfo.processInfo.operatingSystemVersionString,
                    (unsigned long long)device.registryID];
                archiveURL = [root URLByAppendingPathComponent:name];
                MTLBinaryArchiveDescriptor *descriptor = [[MTLBinaryArchiveDescriptor alloc] init];
                NSDictionary *attributes = [files attributesOfItemAtPath:archiveURL.path error:nil];
                if (attributes != nil && [attributes fileSize] <= MCPipelineArchiveLimit) descriptor.url = archiveURL;
                archive = [device newBinaryArchiveWithDescriptor:descriptor error:nil];
                archiveLoaded = archive != nil && descriptor.url != nil;
                if (archive == nil) {
                    descriptor.url = nil; // Stale or corrupt archives never prevent device startup.
                    archive = [device newBinaryArchiveWithDescriptor:descriptor error:nil];
                }
            }
        }
    }
    return self;
}
- (void)save {
    if (!dirty || archive == nil || archiveURL == nil) return;
    // Only device shutdown writes to disk. Atomic replacement tolerates concurrent processes.
    NSURL *temporary = [archiveURL URLByAppendingPathExtension:NSUUID.UUID.UUIDString];
    if ([archive serializeToURL:temporary error:nil]) {
        NSDictionary *attributes = [NSFileManager.defaultManager attributesOfItemAtPath:temporary.path error:nil];
        if (attributes != nil && [attributes fileSize] <= MCPipelineArchiveLimit)
            rename(temporary.fileSystemRepresentation, archiveURL.fileSystemRepresentation);
    }
    [NSFileManager.defaultManager removeItemAtURL:temporary error:nil];
    dirty = NO;
}
@end

static MCLibraryEntry *mc_cached_library(id<MTLDevice> device, NSString *source, NSError **error) {
    MCPipelineCache *cache = objc_getAssociatedObject(device, &MCPipelineCacheKey);
    @synchronized(cache) {
        MCLibraryEntry *entry = [cache->libraries objectForKey:source];
        if (entry != nil) { cache->libraryHits++; return entry; }
        id<MTLLibrary> library = [device newLibraryWithSource:source options:nil error:error];
        if (library == nil) return nil;
        entry = [[MCLibraryEntry alloc] init];
        entry.library = library;
        entry.functions = [[NSMutableDictionary alloc] init];
        cache->libraryMisses++;
        [cache->libraries setObject:entry forKey:source cost:source.length * sizeof(unichar)];
        return entry;
    }
}

static id<MTLFunction> mc_cached_function(MCLibraryEntry *entry, NSString *name) {
    @synchronized(entry) {
        id<MTLFunction> function = entry.functions[name];
        if (function == nil) {
            function = [entry.library newFunctionWithName:name];
            if (function != nil) entry.functions[name] = function;
        }
        return function;
    }
}

static id<MTLRenderPipelineState> mc_cached_render_pipeline(id<MTLDevice> device, MTLRenderPipelineDescriptor *descriptor, NSError **error) {
    MCPipelineCache *cache = objc_getAssociatedObject(device, &MCPipelineCacheKey);
    @synchronized(cache) {
        if (cache->archive != nil) {
            descriptor.binaryArchives = @[cache->archive];
            id<MTLRenderPipelineState> cached = [device newRenderPipelineStateWithDescriptor:descriptor
                options:MTLPipelineOptionFailOnBinaryArchiveMiss reflection:nil error:nil];
            if (cached != nil) { cache->archiveHits++; return cached; }
        }
        id<MTLRenderPipelineState> pipeline = [device newRenderPipelineStateWithDescriptor:descriptor error:error];
        if (pipeline != nil && cache->archive != nil && cache->archiveAdds < 4096
            && [cache->archive addRenderPipelineFunctionsWithDescriptor:descriptor error:nil]) {
            cache->archiveAdds++; cache->dirty = YES;
        }
        return pipeline;
    }
}

static id<MTLComputePipelineState> mc_cached_compute_pipeline(id<MTLDevice> device, id<MTLFunction> function, NSError **error) {
    MCPipelineCache *cache = objc_getAssociatedObject(device, &MCPipelineCacheKey);
    @synchronized(cache) {
        MTLComputePipelineDescriptor *descriptor = [[MTLComputePipelineDescriptor alloc] init];
        descriptor.computeFunction = function;
        if (cache->archive != nil) {
            descriptor.binaryArchives = @[cache->archive];
            id<MTLComputePipelineState> cached = [device newComputePipelineStateWithDescriptor:descriptor
                options:MTLPipelineOptionFailOnBinaryArchiveMiss reflection:nil error:nil];
            if (cached != nil) { cache->archiveHits++; return cached; }
        }
        id<MTLComputePipelineState> pipeline = [device newComputePipelineStateWithDescriptor:descriptor options:0 reflection:nil error:error];
        if (pipeline != nil && cache->archive != nil && cache->archiveAdds < 4096
            && [cache->archive addComputePipelineFunctionsWithDescriptor:descriptor error:nil]) {
            cache->archiveAdds++; cache->dirty = YES;
        }
        return pipeline;
    }
}

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

/**
 * One recorded render command, laid out exactly as MetalCommandStream writes it.
 *
 * <p>Records are fixed width and every field is named for its widest use, so decoding a batch is an
 * array index rather than a parse. That costs a few unused bytes per record and buys a decoder that
 * cannot lose sync, plus an ABI check that is one comparison: the stream header carries the record
 * size Java believes in, and a build whose struct has drifted from it fails on the first batch
 * instead of encoding garbage into a render pass.
 *
 * <p>`slot` is a binding index, or a primitive type for a draw. `stages` is a MCShaderStage mask,
 * or an index type for a draw. `reserved` is zero on every record.
 */
typedef struct {
	int32_t opcode;
	int32_t slot;
	int32_t stages;
	int32_t count;
	int32_t instanceCount;
	int32_t baseVertex;
	int32_t baseInstance;
	int32_t reserved;
	int64_t handle;
	int64_t offset;
} MCCommand;

typedef struct {
	int32_t magic;
	int32_t commandSize;
	int32_t commandCount;
	int32_t flags;
} MCCommandStreamHeader;

_Static_assert(sizeof(MCCommand) == 48, "MCCommand must match MetalCommandStream.COMMAND_BYTES");
_Static_assert(sizeof(MCCommandStreamHeader) == 16, "MCCommandStreamHeader must match MetalCommandStream.HEADER_BYTES");

enum {
	MCCommandSetVertexBuffer = 1,
	MCCommandSetUniformBuffer = 2,
	MCCommandSetTexture = 3,
	MCCommandSetSampler = 4,
	MCCommandDrawIndexed = 5,
	MCCommandSetPipeline = 6
};

/** 'MCMD'. */
#define MC_COMMAND_STREAM_MAGIC 0x4D434D44
/** Re-derive every range from the Metal objects a batch names, rather than trusting the recorder. */
#define MC_COMMAND_STREAM_CHECKED 1
/**
 * Commands resolved per acquisition of the registry lock.
 *
 * <p>Batching exists to stop the render thread taking this lock once per command, but resolving a
 * whole batch under one acquisition would hold a lock that chunk-meshing threads need for as long
 * as the batch is large - and batches are as large as the visible world. Resolving in strides
 * bounds the hold to a few microseconds while still cutting the render thread's acquisitions by
 * this factor.
 */
#define MC_COMMAND_RESOLVE_STRIDE 256

/** Queue-owned CPU scratch, reused only during synchronous command decoding.
 * Resources are retained once per distinct handle until the command buffer pins them.
 * Different queues do not contend; a queue's lock also permits concurrent callers safely.
 */
@interface MCCommandScratch : NSObject {
@public
    NSLock *lock;
    id __strong *objects;
    jlong *handles;
    NSUInteger *types;
    uint32_t *operands;
    uint32_t *buckets;
    NSUInteger capacity, bucketCount, used;
}
- (BOOL)prepare:(NSUInteger)count;
- (void)clear;
@end
@implementation MCCommandScratch
- (instancetype)init {
    self = [super init];
    if (self) lock = [[NSLock alloc] init];
    return self;
}
- (BOOL)prepare:(NSUInteger)count {
    if (count > capacity) {
        NSUInteger next = MAX((NSUInteger)1024, capacity);
        while (next < count) next *= 2;
        id __strong *newObjects = (id __strong *)calloc(next, sizeof(id));
        jlong *newHandles = calloc(next, sizeof(jlong));
        NSUInteger *newTypes = calloc(next, sizeof(NSUInteger));
        uint32_t *newOperands = calloc(next, sizeof(uint32_t));
        uint32_t *newBuckets = calloc(next * 2, sizeof(uint32_t));
        if (!newObjects || !newHandles || !newTypes || !newOperands || !newBuckets) {
            free(newObjects); free(newHandles); free(newTypes); free(newOperands); free(newBuckets);
            return NO;
        }
        free(objects); free(handles); free(types); free(operands); free(buckets);
        objects = newObjects; handles = newHandles; types = newTypes;
        operands = newOperands; buckets = newBuckets;
        capacity = next; bucketCount = next * 2;
    }
    memset(buckets, 0, bucketCount * sizeof(uint32_t));
    return YES;
}
- (void)clear {
    for (NSUInteger i = 0; i < used; i++) objects[i] = nil;
    used = 0;
}
- (void)dealloc {
    [self clear];
    free(objects); free(handles); free(types); free(operands); free(buckets);
}
@end
static char MCCommandScratchKey;

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
	MCObjectTypeTimestampQueryPool = 14,
	MCObjectTypeComputePipeline = 15,
	MCObjectTypeComputePass = 16,
	MCObjectTypeCommandCompletion = 17
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
			descriptor.label = @"MetalCraft pass GPU timing";
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
		commandQueue.label = @"MetalCraft primary command queue";
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
		mc_sample_allocation(device);
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

// Apple GPU blits require pixel-aligned offsets/pitches, not a 256-byte row.
// Only the last row's actual pixels must fit; a subregion can end within a larger row.
static BOOL mc_valid_texture_transfer(jlong offset, jlong row, NSUInteger width,
    NSUInteger height, NSUInteger pixelBytes, NSUInteger bufferLength) {
    if (offset < 0 || row <= 0 || pixelBytes == 0 || height == 0
        || (NSUInteger)offset % pixelBytes != 0 || (NSUInteger)row % pixelBytes != 0
        || width > NSUIntegerMax / pixelBytes || (NSUInteger)row < width * pixelBytes
        || (NSUInteger)offset > bufferLength) return NO;
    NSUInteger available = bufferLength - (NSUInteger)offset;
    NSUInteger lastRow = width * pixelBytes;
    return lastRow <= available && height - 1 <= (available - lastRow) / (NSUInteger)row;
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
		if (!mc_valid_texture_transfer(sourceOffset, bytesPerRow, width, height, bytesPerPixel, source.length)) {
			mc_throw_state(env, @"Metal texture upload staging range or row pitch is invalid");
			return;
		}
		[commandBuffer pin:source];
		[commandBuffer pin:texture];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromBuffer:source
			sourceOffset:(NSUInteger)sourceOffset
			sourceBytesPerRow:(NSUInteger)bytesPerRow
			sourceBytesPerImage:0
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
		if (!mc_valid_texture_transfer(destinationOffset, bytesPerRow, width, height, bytesPerPixel, destination.length)) {
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
			destinationBytesPerImage:0];
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
		NSUInteger sliceCount = mc_texture_slice_count(texture);
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
			|| !mc_valid_texture_transfer(sourceOffset, bytesPerRow, width, height, bytesPerPixel, source.length)) {
			mc_throw_state(env, @"Metal texture upload staging range or row pitch is invalid");
			return;
		}
		[commandBuffer pin:source];
		[commandBuffer pin:texture];
		id<MTLBlitCommandEncoder> blit = [commandBuffer blitEncoder];
		[blit copyFromBuffer:source
			sourceOffset:(NSUInteger)sourceOffset
			sourceBytesPerRow:(NSUInteger)bytesPerRow
			sourceBytesPerImage:0
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
			|| !mc_valid_texture_transfer(destinationOffset, bytesPerRow, width, height, bytesPerPixel, destination.length)) {
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
			destinationBytesPerImage:0];
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
	MCLibraryEntry *entry = mc_cached_library(device, source, &libraryError);
	id<MTLLibrary> library = entry.library;
	if (library == nil) {
		[lock unlock];
		mc_throw_state(env, [NSString stringWithFormat:@"Metal presentation shader compilation failed: %@", libraryError.localizedDescription]);
		return nil;
	}
	MTLRenderPipelineDescriptor *descriptor = [[MTLRenderPipelineDescriptor alloc] init];
	descriptor.vertexFunction = mc_cached_function(entry, @"metalcraft_present_vertex");
	descriptor.fragmentFunction = mc_cached_function(entry, @"metalcraft_present_fragment");
	descriptor.colorAttachments[0].pixelFormat = destinationFormat;
	NSError *pipelineError = nil;
	id<MTLRenderPipelineState> pipeline = mc_cached_render_pipeline(device, descriptor, &pipelineError);
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
		id<MTLRenderPipelineState> pipelineState = mc_cached_render_pipeline(device, descriptor, &pipelineError);
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

// Attachment-array layout, mirrored by MetalRenderPass.COLOR_FIELD_* on the Java side.
#define MC_MAX_COLOR_ATTACHMENTS 8
#define MC_COLOR_FIELDS 5
#define MC_COLOR_FIELD_IS_DRAWABLE 0
#define MC_COLOR_FIELD_MIP_LEVEL 1
#define MC_COLOR_FIELD_LOAD_ACTION 2
#define MC_COLOR_FIELD_STORE_ACTION 3
#define MC_COLOR_FIELD_ARRAY_SLICE 4
#define MC_COLOR_CLEAR_COMPONENTS 4
#define MC_MAX_PASS_OBJECTS (1 + MC_MAX_COLOR_ATTACHMENTS + 1)

MC_EXPORT JNIEXPORT jlong JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nBeginRenderPass(
	JNIEnv *env,
	jclass type,
	jlong commandBufferHandle,
	jlongArray colorTargetHandlesValue,
	jintArray colorFieldsValue,
	jdoubleArray colorClearValuesValue,
	jlong depthTargetHandle,
	jint depthMipLevel,
	jint depthArraySlice,
	jint depthLoadAction,
	jint depthStoreAction,
	jdouble clearDepth,
	jint renderTargetArrayLength,
	jint gpuTimingKind
) {
	@autoreleasepool {
		if (colorTargetHandlesValue == NULL || colorFieldsValue == NULL || colorClearValuesValue == NULL) {
			mc_throw_state(env, @"Metal render-pass attachment arrays cannot be null");
			return 0;
		}
		jsize colorCount = (*env)->GetArrayLength(env, colorTargetHandlesValue);
		if (colorCount < 0 || colorCount > MC_MAX_COLOR_ATTACHMENTS
			|| (*env)->GetArrayLength(env, colorFieldsValue) != colorCount * MC_COLOR_FIELDS
			|| (*env)->GetArrayLength(env, colorClearValuesValue) != colorCount * MC_COLOR_CLEAR_COMPONENTS) {
			mc_throw_state(env, @"Metal render-pass attachment arrays have an invalid length");
			return 0;
		}

		jlong colorTargetHandles[MC_MAX_COLOR_ATTACHMENTS];
		jint colorFields[MC_MAX_COLOR_ATTACHMENTS * MC_COLOR_FIELDS];
		jdouble colorClearValues[MC_MAX_COLOR_ATTACHMENTS * MC_COLOR_CLEAR_COMPONENTS];
		if (colorCount > 0) {
			(*env)->GetLongArrayRegion(env, colorTargetHandlesValue, 0, colorCount, colorTargetHandles);
			(*env)->GetIntArrayRegion(env, colorFieldsValue, 0, colorCount * MC_COLOR_FIELDS, colorFields);
			(*env)->GetDoubleArrayRegion(env, colorClearValuesValue, 0, colorCount * MC_COLOR_CLEAR_COMPONENTS, colorClearValues);
			if ((*env)->ExceptionCheck(env)) {
				return 0;
			}
		}

		jlong handles[MC_MAX_PASS_OBJECTS];
		MCObjectType types[MC_MAX_PASS_OBJECTS];
		id objects[MC_MAX_PASS_OBJECTS];
		NSInteger colorObjectIndex[MC_MAX_COLOR_ATTACHMENTS];
		NSUInteger objectCount = 0;
		NSInteger depthIndex = -1;
		NSUInteger attachedColorCount = 0;
		handles[objectCount] = commandBufferHandle;
		types[objectCount] = MCObjectTypeCommandBuffer;
		objectCount++;
		for (jsize index = 0; index < colorCount; index++) {
			if (colorTargetHandles[index] == 0) {
				colorObjectIndex[index] = -1;
				continue;
			}
			colorObjectIndex[index] = (NSInteger)objectCount;
			handles[objectCount] = colorTargetHandles[index];
			types[objectCount] = colorFields[index * MC_COLOR_FIELDS + MC_COLOR_FIELD_IS_DRAWABLE] != 0
				? MCObjectTypeDrawable
				: MCObjectTypeTexture;
			objectCount++;
			attachedColorCount++;
		}
		if (attachedColorCount == 0 && depthTargetHandle == 0) {
			mc_throw_state(env, @"A Metal render pass requires at least one attachment");
			return 0;
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
		id<MTLTexture> depthTexture = depthIndex < 0 ? nil : (id<MTLTexture>)objects[depthIndex];
		if (depthTexture != nil && (depthMipLevel < 0 || (NSUInteger)depthMipLevel >= depthTexture.mipmapLevelCount)) {
			mc_throw_state(env, @"Metal render-pass attachment mip level is out of bounds");
			return 0;
		}
		if (depthTexture != nil && !mc_pixel_format_has_depth(depthTexture.pixelFormat)) {
			mc_throw_state(env, @"A Metal depth attachment requires a depth texture");
			return 0;
		}
		if (depthTexture != nil && (depthArraySlice < 0 || (NSUInteger)depthArraySlice >= mc_texture_slice_count(depthTexture))) {
			mc_throw_state(env, @"Metal render-pass attachment array slice is out of bounds");
			return 0;
		}
		if (renderTargetArrayLength < 0) {
			mc_throw_state(env, @"A layered Metal render pass cannot have a negative layer count");
			return 0;
		}

		NSUInteger targetWidth = 0;
		NSUInteger targetHeight = 0;
		id<MTLTexture> colorTextures[MC_MAX_COLOR_ATTACHMENTS];
		for (jsize index = 0; index < colorCount; index++) {
			colorTextures[index] = nil;
			if (colorObjectIndex[index] < 0) {
				continue;
			}
			const jint *fields = &colorFields[index * MC_COLOR_FIELDS];
			BOOL isDrawable = fields[MC_COLOR_FIELD_IS_DRAWABLE] != 0;
			id<MTLTexture> texture = isDrawable
				? [(id<CAMetalDrawable>)objects[colorObjectIndex[index]] texture]
				: (id<MTLTexture>)objects[colorObjectIndex[index]];
			jint mipLevel = fields[MC_COLOR_FIELD_MIP_LEVEL];
			if (mipLevel < 0 || (NSUInteger)mipLevel >= texture.mipmapLevelCount || (isDrawable && mipLevel != 0)) {
				mc_throw_state(env, @"Metal render-pass attachment mip level is out of bounds");
				return 0;
			}
			jint arraySlice = fields[MC_COLOR_FIELD_ARRAY_SLICE];
			if (arraySlice < 0 || (NSUInteger)arraySlice >= mc_texture_slice_count(texture)) {
				mc_throw_state(env, @"Metal render-pass attachment array slice is out of bounds");
				return 0;
			}
			if (mc_pixel_format_has_depth(texture.pixelFormat) || mc_pixel_format_has_stencil(texture.pixelFormat)) {
				mc_throw_state(env, @"A depth/stencil texture cannot be used as a Metal color attachment");
				return 0;
			}
			if (texture.storageMode == MTLStorageModeMemoryless) {
				jint loadAction = fields[MC_COLOR_FIELD_LOAD_ACTION];
				jint storeAction = fields[MC_COLOR_FIELD_STORE_ACTION];
				if (loadAction == 0 || storeAction != 1) {
					mc_throw_state(env, @"A memoryless Metal attachment cannot load or store");
					return 0;
				}
			}
			NSUInteger width = MAX((NSUInteger)1, texture.width >> mipLevel);
			NSUInteger height = MAX((NSUInteger)1, texture.height >> mipLevel);
			if (targetWidth == 0) {
				targetWidth = width;
				targetHeight = height;
			} else if (width != targetWidth || height != targetHeight) {
				mc_throw_state(env, @"Metal render-pass attachments must have matching dimensions");
				return 0;
			}
			colorTextures[index] = texture;
		}

		NSUInteger depthWidth = depthTexture == nil ? 0 : MAX((NSUInteger)1, depthTexture.width >> depthMipLevel);
		NSUInteger depthHeight = depthTexture == nil ? 0 : MAX((NSUInteger)1, depthTexture.height >> depthMipLevel);
		if (depthTexture != nil) {
			if (targetWidth == 0) {
				targetWidth = depthWidth;
				targetHeight = depthHeight;
			} else if (depthWidth != targetWidth || depthHeight != targetHeight) {
				mc_throw_state(env, @"Metal render-pass attachments must have matching dimensions");
				return 0;
			}
			if (depthTexture.storageMode == MTLStorageModeMemoryless && (depthLoadAction == 0 || depthStoreAction != 1)) {
				mc_throw_state(env, @"A memoryless Metal attachment cannot load or store");
				return 0;
			}
		}

		MTLRenderPassDescriptor *descriptor = [MTLRenderPassDescriptor renderPassDescriptor];
		for (jsize index = 0; index < colorCount; index++) {
			if (colorTextures[index] == nil) {
				continue;
			}
			const jint *fields = &colorFields[index * MC_COLOR_FIELDS];
			const jdouble *clear = &colorClearValues[index * MC_COLOR_CLEAR_COMPONENTS];
			MTLLoadAction loadAction = mc_load_action(env, fields[MC_COLOR_FIELD_LOAD_ACTION]);
			MTLStoreAction storeAction = mc_store_action(env, fields[MC_COLOR_FIELD_STORE_ACTION]);
			if ((*env)->ExceptionCheck(env)) {
				return 0;
			}
			NSUInteger colorIndex = (NSUInteger)index;
			descriptor.colorAttachments[colorIndex].texture = colorTextures[index];
			descriptor.colorAttachments[colorIndex].level = (NSUInteger)fields[MC_COLOR_FIELD_MIP_LEVEL];
			descriptor.colorAttachments[colorIndex].slice = (NSUInteger)fields[MC_COLOR_FIELD_ARRAY_SLICE];
			descriptor.colorAttachments[colorIndex].loadAction = loadAction;
			descriptor.colorAttachments[colorIndex].storeAction = storeAction == MTLStoreActionStore ? MTLStoreActionUnknown : storeAction;
			descriptor.colorAttachments[colorIndex].clearColor = MTLClearColorMake(clear[0], clear[1], clear[2], clear[3]);
		}
		if (attachedColorCount == 0) {
			descriptor.renderTargetWidth = targetWidth;
			descriptor.renderTargetHeight = targetHeight;
		}
		if (renderTargetArrayLength > 0) {
			descriptor.renderTargetArrayLength = (NSUInteger)renderTargetArrayLength;
		}
		if (depthTexture != nil) {
			MTLLoadAction nativeDepthLoadAction = mc_load_action(env, depthLoadAction);
			MTLStoreAction nativeDepthStoreAction = mc_store_action(env, depthStoreAction);
			if ((*env)->ExceptionCheck(env)) {
				return 0;
			}
			descriptor.depthAttachment.texture = depthTexture;
			descriptor.depthAttachment.level = (NSUInteger)depthMipLevel;
			descriptor.depthAttachment.slice = (NSUInteger)depthArraySlice;
			descriptor.depthAttachment.loadAction = nativeDepthLoadAction;
			descriptor.depthAttachment.storeAction = nativeDepthStoreAction == MTLStoreActionStore ? MTLStoreActionUnknown : nativeDepthStoreAction;
			descriptor.depthAttachment.clearDepth = clearDepth;
			if (mc_pixel_format_has_stencil(depthTexture.pixelFormat)) {
				descriptor.stencilAttachment.texture = depthTexture;
				descriptor.stencilAttachment.level = (NSUInteger)depthMipLevel;
				descriptor.stencilAttachment.slice = (NSUInteger)depthArraySlice;
				descriptor.stencilAttachment.loadAction = nativeDepthLoadAction;
				descriptor.stencilAttachment.storeAction = nativeDepthStoreAction == MTLStoreActionStore ? MTLStoreActionUnknown : nativeDepthStoreAction;
				descriptor.stencilAttachment.clearStencil = 0;
			}
		}

		for (jsize index = 0; index < colorCount; index++) {
			if (colorObjectIndex[index] >= 0) {
				[commandBuffer pin:objects[colorObjectIndex[index]]];
			}
		}
		if (depthTexture != nil) {
			[commandBuffer pin:depthTexture];
		}
		if (gpuTimingKind >= 0 && gpuTimingKind < MC_GPU_PASS_KINDS) {
			id<MTLCounterSampleBuffer> samples = mc_gpu_pass_sample_buffer(commandBuffer.commandBuffer.device);
			if (samples != nil) {
				uint64_t sequence = atomic_fetch_add_explicit(&mc_gpu_pass_next_slot, 1, memory_order_relaxed);
				NSUInteger slot = (NSUInteger)(sequence % MC_GPU_PASS_SLOTS);
				MTLRenderPassSampleBufferAttachmentDescriptor *samplePoints = descriptor.sampleBufferAttachments[0];
				samplePoints.sampleBuffer = samples;
				samplePoints.startOfVertexSampleIndex = slot * 2;
				samplePoints.endOfVertexSampleIndex = MTLCounterDontSample;
				samplePoints.startOfFragmentSampleIndex = MTLCounterDontSample;
				samplePoints.endOfFragmentSampleIndex = slot * 2 + 1;
				[commandBuffer addTimedPassKind:(uint32_t)gpuTimingKind sequence:sequence sampleBuffer:samples];
			}
		}
		id<MTLRenderCommandEncoder> encoder = [commandBuffer.commandBuffer renderCommandEncoderWithDescriptor:descriptor];
		if (encoder == nil) {
			mc_throw_state(env, @"Metal did not create a render command encoder");
			return 0;
		}
		encoder.label = @"MetalCraft render pass";
		[encoder setViewport:(MTLViewport){0.0, 0.0, (double)targetWidth, (double)targetHeight, 0.0, 1.0}];
		[encoder setScissorRect:(MTLScissorRect){0, 0, targetWidth, targetHeight}];
		MCMetalRenderPass *renderPass = [[MCMetalRenderPass alloc]
			initWithEncoder:encoder
			commandBuffer:commandBuffer
			descriptor:descriptor
			width:targetWidth
			height:targetHeight
		];
		return mc_register_object(renderPass, MCObjectTypeRenderPass, commandBufferHandle);
	}
}

MC_EXPORT JNIEXPORT void JNICALL
Java_dev_metalcraft_client_metal_MetalNative_nDiscardRenderAttachments(JNIEnv *env, jclass type, jlong handle, jint colorMask, jboolean depth) {
    @autoreleasepool {
        MCMetalRenderPass *pass = (MCMetalRenderPass *)mc_get_object(env, handle, MCObjectTypeRenderPass);
        if (pass == nil) return;
        if (pass.ended || colorMask < 0 || ((NSUInteger)colorMask & ~pass.mutableStoreMask) != 0 || (depth && !pass.mutableDepthStore)) {
            mc_throw_state(env, @"Cannot discard an absent or ended Metal attachment"); return;
        }
        pass.discardedColors |= (NSUInteger)colorMask;
        pass.discardedDepth |= depth;
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
			mc_sample_allocation(buffer.device);
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
			mc_throw_state(env, @"The selected Metal device samples counters only at encoder stage boundaries, "
				"so it cannot take a timestamp part-way through a render pass; whole-pass GPU time is "
				"measured instead, through MetalPassCensus");
			return;
		}
		[renderPass.commandBuffer recordTimestampInPool:pool index:(NSUInteger)index];
		[renderPass.encoder sampleCountersInBuffer:pool.sampleBuffer atSampleIndex:(NSUInteger)index withBarrier:YES];
	}
}

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
		encoder.label = @"MetalCraft compute pass";
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
