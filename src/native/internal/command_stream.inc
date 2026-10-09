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
    uint32_t *occupied;
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
        uint32_t *newOccupied = calloc(next, sizeof(uint32_t));
        if (!newObjects || !newHandles || !newTypes || !newOperands || !newBuckets || !newOccupied) {
            free(newObjects); free(newHandles); free(newTypes); free(newOperands); free(newBuckets); free(newOccupied);
            return NO;
        }
        free(objects); free(handles); free(types); free(operands); free(buckets); free(occupied);
        objects = newObjects; handles = newHandles; types = newTypes;
        operands = newOperands; buckets = newBuckets; occupied = newOccupied;
        capacity = next; bucketCount = next * 2;
    }
    return YES;
}
- (void)clear {
    for (NSUInteger i = 0; i < used; i++) { objects[i] = nil; buckets[occupied[i]] = 0; }
    used = 0;
}
- (void)dealloc {
    [self clear];
    free(objects); free(handles); free(types); free(operands); free(buckets); free(occupied);
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
