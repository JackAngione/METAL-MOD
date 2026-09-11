#import <Foundation/Foundation.h>
#import <Metal/Metal.h>

// Isolated P6 feasibility probe. Consumes the production-source fixture and uniform
// bytes exported by LodShadingBenchmark; nothing links this into the game runtime.
static id<MTLDevice> device;
static id<MTLCommandQueue> queue;
static id<MTLRenderPipelineState> geometry, resolve, tile, reconstruct, overlay, shadowPipeline;
static id<MTLDepthStencilState> writeDepth, readDepth, noDepth, alwaysDepth;
static id<MTLTexture> scene, albedo, normalTarget, light, depth, shadowTarget;
static id<MTLBuffer> options, frame, camera, fog;
static id<MTLSamplerState> sampler;
static NSUInteger width, height;
static BOOL packed;
static NSString *directory;

static void require(BOOL condition, NSString *message) {
    if (!condition) { fprintf(stderr, "%s\n", message.UTF8String); exit(1); }
}
static id<MTLTexture> texture(MTLPixelFormat format, NSUInteger w, NSUInteger h) {
    MTLTextureDescriptor *d = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:format width:w height:h mipmapped:NO];
    d.storageMode = MTLStorageModeShared;
    d.usage = MTLTextureUsageRenderTarget | MTLTextureUsageShaderRead;
    id<MTLTexture> result = [device newTextureWithDescriptor:d];
    require(result != nil, @"Texture allocation failed"); return result;
}
static id<MTLBuffer> buffer(NSString *name) {
    NSData *bytes = [NSData dataWithContentsOfFile:[directory stringByAppendingPathComponent:name]];
    require(bytes != nil, [@"Missing fixture input: " stringByAppendingString:name]);
    return [device newBufferWithBytes:bytes.bytes length:bytes.length options:MTLResourceStorageModeShared];
}
static id<MTLRenderPipelineState> pipeline(id<MTLLibrary> library, NSString *name, BOOL gbuffer, BOOL color) {
    MTLRenderPipelineDescriptor *d = [MTLRenderPipelineDescriptor new];
    d.vertexFunction = [library newFunctionWithName:@"resolve_vertex"];
    d.fragmentFunction = [library newFunctionWithName:name];
    if (color) d.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
    if (gbuffer) for (NSUInteger i = 1; i < 4; i++) d.colorAttachments[i].pixelFormat = MTLPixelFormatRGBA8Unorm;
    d.depthAttachmentPixelFormat = MTLPixelFormatDepth32Float;
    NSError *error = nil;
    id<MTLRenderPipelineState> result = [device newRenderPipelineStateWithDescriptor:d error:&error];
    require(result != nil, error.description);
    return result;
}
static id<MTLDepthStencilState> depthState(MTLCompareFunction compare, BOOL write) {
    MTLDepthStencilDescriptor *d = [MTLDepthStencilDescriptor new];
    d.depthCompareFunction = compare; d.depthWriteEnabled = write;
    return [device newDepthStencilStateWithDescriptor:d];
}
static void bind(id<MTLRenderCommandEncoder> encoder, BOOL tiles, float motion) {
    NSArray *buffers = @[options, frame, camera, fog];
    for (NSUInteger i = 0; i < buffers.count; i++) {
        if (tiles) [encoder setTileBuffer:buffers[i] offset:0 atIndex:i];
        else [encoder setFragmentBuffer:buffers[i] offset:0 atIndex:i];
    }
    if (tiles) {
        [encoder setTileTexture:shadowTarget atIndex:0]; [encoder setTileSamplerState:sampler atIndex:0];
    } else {
        [encoder setFragmentTexture:shadowTarget atIndex:0]; [encoder setFragmentSamplerState:sampler atIndex:0];
        float parameters[] = {(float)width, (float)height, motion, 0};
        [encoder setFragmentBytes:parameters length:sizeof(parameters) atIndex:4];
    }
}
static double draw(BOOL tiles, int motion) {
    id<MTLCommandBuffer> commands = [queue commandBuffer];
    MTLRenderPassDescriptor *d = [MTLRenderPassDescriptor new];
    NSArray *colors = @[scene, albedo, normalTarget, light];
    for (NSUInteger i = 0; i < colors.count; i++) {
        d.colorAttachments[i].texture = colors[i];
        d.colorAttachments[i].loadAction = MTLLoadActionClear;
        d.colorAttachments[i].storeAction = i == 0 ? MTLStoreActionStore : MTLStoreActionDontCare;
    }
    d.depthAttachment.texture = depth;
    d.depthAttachment.loadAction = MTLLoadActionClear;
    d.depthAttachment.storeAction = MTLStoreActionStore;
    d.depthAttachment.clearDepth = 0;
    // The comparison uses the same tile dimensions for both variants.
    NSUInteger tileSize = packed ? 32 : 16;
    d.tileWidth = tileSize; d.tileHeight = tileSize;
    if (tiles) d.threadgroupMemoryLength = (packed ? 320 : 256) * 16;
    id<MTLRenderCommandEncoder> e = [commands renderCommandEncoderWithDescriptor:d];
    require(e != nil, @"Could not create coverage/resolve encoder (tile memory budget)");
    bind(e, NO, motion);
    [e setRenderPipelineState:geometry]; [e setDepthStencilState:writeDepth];
    [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    if (tiles) {
        bind(e, YES, motion);
        [e setRenderPipelineState:tile];
        [e setThreadgroupMemoryLength:(packed ? 320 : 256) * 16 offset:0 atIndex:0];
        [e dispatchThreadsPerTile:packed ? MTLSizeMake(16, 20, 1) : MTLSizeMake(16, 16, 1)];
        if (packed) {
            [e setRenderPipelineState:reconstruct];
            [e dispatchThreadsPerTile:MTLSizeMake(32, 32, 1)];
        }
    } else {
        [e setRenderPipelineState:resolve]; [e setDepthStencilState:noDepth];
        [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
    }
    [e endEncoding];
    MTLRenderPassDescriptor *after = [MTLRenderPassDescriptor new];
    after.colorAttachments[0].texture = scene;
    after.colorAttachments[0].loadAction = MTLLoadActionLoad;
    after.colorAttachments[0].storeAction = MTLStoreActionStore;
    after.depthAttachment.texture = depth;
    after.depthAttachment.loadAction = MTLLoadActionLoad;
    after.depthAttachment.storeAction = MTLStoreActionStore;
    e = [commands renderCommandEncoderWithDescriptor:after];
    require(e != nil, @"Could not create depth-consumer encoder");
    bind(e, NO, motion); [e setRenderPipelineState:overlay]; [e setDepthStencilState:readDepth];
    [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3]; [e endEncoding];
    [commands commit]; [commands waitUntilCompleted];
    require(commands.status == MTLCommandBufferStatusCompleted, commands.error.description);
    double elapsed = (commands.GPUEndTime - commands.GPUStartTime) * 1000.0;
    require(isfinite(elapsed) && elapsed > 0, @"Missing completed GPU interval"); return elapsed;
}
static NSArray *quantiles(NSMutableArray *values) {
    [values sortUsingSelector:@selector(compare:)];
    return @[values[values.count / 2], values[values.count * 95 / 100], values[values.count * 99 / 100]];
}
int main(int argc, const char *argv[]) { @autoreleasepool {
    require(argc == 2 || (argc == 3 && strcmp(argv[2], "packed") == 0), @"Usage: lod-shading-tile build/reports/lod-shading [packed]");
    packed = argc == 3;
    directory = @(argv[1]); device = MTLCreateSystemDefaultDevice();
    require(device != nil, @"No Metal device available"); queue = [device newCommandQueue];
    NSString *source = [NSString stringWithContentsOfFile:[directory stringByAppendingPathComponent:@"fixture.metal"] encoding:NSUTF8StringEncoding error:nil];
    NSString *kernel = [NSString stringWithContentsOfFile:@"src/smoke/resources/lod-shading-tile.metal" encoding:NSUTF8StringEncoding error:nil];
    require(source && kernel, @"Missing fixture source");
    NSError *error = nil;
    id<MTLLibrary> library = [device newLibraryWithSource:[source stringByAppendingString:kernel] options:nil error:&error];
    require(library != nil, error.description);
    geometry = pipeline(library, @"fixture_geometry", YES, YES);
    resolve = pipeline(library, @"resolve_fragment", YES, YES);
    overlay = pipeline(library, @"fixture_overlay", NO, YES);
    shadowPipeline = pipeline(library, @"fixture_shadow", NO, NO);
    MTLTileRenderPipelineDescriptor *td = [MTLTileRenderPipelineDescriptor new];
    td.tileFunction = [library newFunctionWithName:packed ? @"fixture_tile_coarse" : @"fixture_tile"];
    td.threadgroupSizeMatchesTileSize = !packed;
    td.colorAttachments[0].pixelFormat = MTLPixelFormatRGBA16Float;
    for (NSUInteger i = 1; i < 4; i++) td.colorAttachments[i].pixelFormat = MTLPixelFormatRGBA8Unorm;
    tile = [device newRenderPipelineStateWithTileDescriptor:td options:MTLPipelineOptionNone reflection:nil error:&error];
    require(tile != nil, error.description);
    td.tileFunction = [library newFunctionWithName:@"fixture_tile_reconstruct"]; td.threadgroupSizeMatchesTileSize = YES;
    reconstruct = [device newRenderPipelineStateWithTileDescriptor:td options:MTLPipelineOptionNone reflection:nil error:&error];
    require(reconstruct != nil, error.description);
    writeDepth = depthState(MTLCompareFunctionGreater, YES); readDepth = depthState(MTLCompareFunctionGreater, NO);
    noDepth = depthState(MTLCompareFunctionAlways, NO); alwaysDepth = depthState(MTLCompareFunctionAlways, YES);
    options = buffer(@"options.bin"); fog = buffer(@"fog.bin");
    MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
    sd.minFilter = MTLSamplerMinMagFilterNearest; sd.magFilter = MTLSamplerMinMagFilterNearest;
    sd.sAddressMode = MTLSamplerAddressModeClampToEdge; sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
    sampler = [device newSamplerStateWithDescriptor:sd];
    MTLTextureDescriptor *shadowDesc = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatDepth32Float width:64 height:64 mipmapped:NO];
    shadowDesc.textureType = MTLTextureType2DArray; shadowDesc.arrayLength = 4;
    shadowDesc.usage = MTLTextureUsageShaderRead | MTLTextureUsageRenderTarget;
    shadowTarget = [device newTextureWithDescriptor:shadowDesc];
    id<MTLCommandBuffer> setup = [queue commandBuffer];
    for (NSUInteger i = 0; i < 4; i++) {
        MTLRenderPassDescriptor *d = [MTLRenderPassDescriptor new];
        d.depthAttachment.texture = shadowTarget; d.depthAttachment.slice = i;
        d.depthAttachment.loadAction = MTLLoadActionClear; d.depthAttachment.storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> e = [setup renderCommandEncoderWithDescriptor:d];
        require(e != nil, @"Could not create shadow fixture encoder");
        [e setRenderPipelineState:shadowPipeline]; [e setDepthStencilState:alwaysDepth];
        [e drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3]; [e endEncoding];
    }
    [setup commit]; [setup waitUntilCompleted];
    NSMutableArray *results = [NSMutableArray new];
    for (NSNumber *shadowDistance in @[@96, @256]) for (NSArray *size in @[@[@1279, @719], @[@1920, @1080], @[@3840, @2160]]) { @autoreleasepool {
        frame = buffer([NSString stringWithFormat:@"frame-%@.bin", shadowDistance]);
        width = [size[0] unsignedIntegerValue]; height = [size[1] unsignedIntegerValue];
        camera = buffer([NSString stringWithFormat:@"camera-%lu.bin", width]);
        scene = texture(MTLPixelFormatRGBA16Float, width, height); depth = texture(MTLPixelFormatDepth32Float, width, height);
        albedo = texture(MTLPixelFormatRGBA8Unorm, width, height); normalTarget = texture(MTLPixelFormatRGBA8Unorm, width, height);
        light = texture(MTLPixelFormatRGBA8Unorm, width, height);
        for (int repeat = 0; repeat < 3; repeat++) {
        for (int i = 0; i < 80; i++) draw(i % 2, 0);
        NSMutableArray *a = [NSMutableArray new], *b = [NSMutableArray new];
        for (int i = 0; i < 180; i++) for (int j = 0; j < 2; j++) {
            BOOL tiles = (i + j + repeat) % 2; [(tiles ? b : a) addObject:@(draw(tiles, i % 16))];
        }
        NSArray *rawA = [a copy], *rawB = [b copy];
        NSArray *qa = quantiles(a), *qb = quantiles(b);
        NSMutableDictionary *row = [@{@"width": @(width), @"height": @(height), @"shadowDistance": shadowDistance, @"repeat": @(repeat + 1),
            @"mergedMs": qa, @"tileMs": qb, @"mergedSamplesMs":rawA, @"tileSamplesMs":rawB,
            @"netGpuImprovement": @([qb[0] doubleValue] < [qa[0] doubleValue]),
            @"medianChangePercent": @(100.0 * ([qb[0] doubleValue] / [qa[0] doubleValue] - 1.0))} mutableCopy];
        if (width == 1279 && repeat == 0) {
            NSUInteger pixels = width * height, depthDifferences = 0, nearDifferences = 0, differences = 0;
            NSMutableData *reference = [NSMutableData dataWithLength:pixels * 8], *actual = [NSMutableData dataWithLength:pixels * 8];
            NSMutableData *refDepth = [NSMutableData dataWithLength:pixels * 4], *gotDepth = [NSMutableData dataWithLength:pixels * 4];
            float maximumError = 0;
            for (int motion = 0; motion < 16; motion++) {
                draw(NO, motion);
                [scene getBytes:reference.mutableBytes bytesPerRow:width * 8 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];
                [depth getBytes:refDepth.mutableBytes bytesPerRow:width * 4 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];
                draw(YES, motion);
                [scene getBytes:actual.mutableBytes bytesPerRow:width * 8 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];
                [depth getBytes:gotDepth.mutableBytes bytesPerRow:width * 4 fromRegion:MTLRegionMake2D(0,0,width,height) mipmapLevel:0];
                for (NSUInteger p = 0; p < pixels; p++) {
                    if (((uint32_t *)refDepth.bytes)[p] != ((uint32_t *)gotDepth.bytes)[p]) depthDifferences++;
                    float delta = 0;
                    for (NSUInteger c = 0; c < 4; c++) {
                        float expected = ((__fp16 *)reference.bytes)[p*4+c], got = ((__fp16 *)actual.bytes)[p*4+c];
                        require(isfinite(expected) && isfinite(got), @"Nonfinite tile channel");
                        delta = fmaxf(delta, fabsf(expected - got));
                    }
                    require(isfinite(delta), @"Nonfinite tile output"); maximumError = fmaxf(maximumError, delta);
                    if (delta > .02f) { differences++; if (((float *)refDepth.bytes)[p] > .1f / 64) nearDifferences++; }
                }
            }
            row[@"quality"] = @{@"depthBitDifferences": @(depthDifferences), @"nearPixelsOver002": @(nearDifferences),
                @"pixelsOver002": @(differences), @"maximumChannelError": @(maximumError), @"motionSteps": @16, @"imageBudgetPassed": @(differences == 0)};
            require(depthDifferences == 0 && nearDifferences == 0, @"Tile depth/near ownership changed");
        }
        [results addObject:row];
        fprintf(stdout, "P6 tile %s shadows=%d %lux%lu repeat=%d GPU medians merged=%.4f tile=%.4f ms\n",
            packed ? "packed" : "direct", shadowDistance.intValue, width, height, repeat + 1, [qa[0] doubleValue], [qb[0] doubleValue]);
        }
    }}
    NSData *json = [NSJSONSerialization dataWithJSONObject:@{@"device":device.name, @"scope":@"Isolated tile-stage feasibility; not runtime acceptance", @"mode":packed ? @"packed" : @"direct",
        @"samplesPerVariantPerRepeat":@180, @"repeats":@3, @"warmupSubmissionsPerRepeat":@80, @"tileSize":@(packed ? 32 : 16),
        @"quantileOrder":@[@"median",@"p95",@"p99"], @"results":results} options:NSJSONWritingPrettyPrinted error:&error];
    require([json writeToFile:[directory stringByAppendingPathComponent:packed ? @"tile-packed-metrics.json" : @"tile-direct-metrics.json"] atomically:YES], @"Could not write tile results");
    return 0;
}}
