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
    NSError *serializationError = nil;
    if ([archive serializeToURL:temporary error:&serializationError]) {
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
        // Honour [[invariant]] position outputs across shader variants. Coplanar
        // block overlays must compare at exactly the same depth when their mesh
        // shares the wind pipeline with plants. Unmarked shaders keep fast math.
        MTLCompileOptions *options = [[MTLCompileOptions alloc] init];
        options.preserveInvariance = YES;
        id<MTLLibrary> library = [device newLibraryWithSource:source options:options error:error];
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
        // Harvest from source IR, never from a descriptor referring back to its archive.
        descriptor.binaryArchives = nil;
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
        descriptor.binaryArchives = nil;
        id<MTLComputePipelineState> pipeline = [device newComputePipelineStateWithDescriptor:descriptor options:0 reflection:nil error:error];
        if (pipeline != nil && cache->archive != nil && cache->archiveAdds < 4096
            && [cache->archive addComputePipelineFunctionsWithDescriptor:descriptor error:nil]) {
            cache->archiveAdds++; cache->dirty = YES;
        }
        return pipeline;
    }
}
