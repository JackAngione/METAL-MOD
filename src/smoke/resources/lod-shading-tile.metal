// P6 tile-stage experiment. No attachment stores or sampled G-buffer textures.
kernel void fixture_tile(threadgroup float4 *shaded [[threadgroup(0)]], imageblock<ResolveTargets, imageblock_layout_implicit> block,
    ushort2 tid [[thread_position_in_threadgroup]], uint2 group [[threadgroup_position_in_grid]], ushort2 extent [[threads_per_threadgroup]],
    constant PackOptions &options [[buffer(0)]], constant MCShadowFrame &shadowFrame [[buffer(1)]],
    constant MCResolveCamera &camera [[buffer(2)]], constant McFog &fog [[buffer(3)]],
    depth2d_array<float> shadowMap [[texture(0)]], sampler shadowSampler [[sampler(0)]]) {
    uint2 p = group * uint2(extent) + uint2(tid);
    ResolveTargets previous = block.read(tid);
    float z = mc_unpack_view_depth(previous.normal, previous.light);
    uint scale = z < 64.0 ? 1u : (z < 128.0 ? 2u : 4u);
    ushort2 origin = tid & ushort(~(scale - 1u));
    ResolveTargets sample = block.read(origin);
    float sampleDepth = mc_unpack_view_depth(sample.normal, sample.light);
    uint sampleScale = sampleDepth < 64.0 ? 1u : (sampleDepth < 128.0 ? 2u : 4u);
    bool reuse = scale > 1u && scale == sampleScale && previous.albedo.a > 0.0
        && abs(sampleDepth - z) < .02 && all(abs(sample.normal.rg - previous.normal.rg) < .001)
        && all(abs(sample.albedo - previous.albedo) < .002)
        && all(abs(sample.scene - previous.scene) < .002)
        && all(abs(sample.light.rg - previous.light.rg) < .001);
    uint index = tid.y * extent.x + tid.x;
    if (!reuse || all(tid == origin)) {
        ResolveVaryings in = {float4(float2(p) + .5, 0, 1), float2(0)};
        shaded[index] = shade(in, previous, options, shadowFrame, camera, fog, shadowMap, shadowSampler).scene;
    }
    threadgroup_barrier(mem_flags::mem_threadgroup | mem_flags::mem_threadgroup_imageblock);
    previous.scene = shaded[reuse ? origin.y * extent.x + origin.x : index];
    previous.albedo.a = 0.0;
    block.write(previous, tid);
}

// Pack coarse work into active lanes before reconstruction. Masking sixteen of every
// seventeen fragment lanes cannot reduce SIMD arithmetic, even with fewer texture reads.
kernel void fixture_tile_coarse(threadgroup float4 *shaded [[threadgroup(0)]],
    imageblock<ResolveTargets, imageblock_layout_implicit> block,
    ushort index [[thread_index_in_threadgroup]], uint2 group [[threadgroup_position_in_grid]],
    constant PackOptions &options [[buffer(0)]], constant MCShadowFrame &shadowFrame [[buffer(1)]],
    constant MCResolveCamera &camera [[buffer(2)]], constant McFog &fog [[buffer(3)]],
    depth2d_array<float> shadowMap [[texture(0)]], sampler shadowSampler [[sampler(0)]]) {
    uint scale = index < 256 ? 2u : 4u;
    uint i = index < 256 ? index : index - 256;
    uint stride = 32u / scale;
    ushort2 local = ushort2(i % stride, i / stride) * ushort(scale);
    uint2 p = group * 32u + uint2(local);
    ResolveTargets previous = block.read(local);
    float z = mc_unpack_view_depth(previous.normal, previous.light);
    if (all(p < uint2(camera.screenSize)) && (scale == 2u ? (z >= 64 && z < 128) : z >= 128)) {
        ResolveVaryings in = {float4(float2(p) + .5, 0, 1), float2(0)};
        shaded[index] = shade(in, previous, options, shadowFrame, camera, fog, shadowMap, shadowSampler).scene;
    } else shaded[index] = float4(0);
    threadgroup_barrier(mem_flags::mem_threadgroup);
}

kernel void fixture_tile_reconstruct(threadgroup float4 *shaded [[threadgroup(0)]],
    imageblock<ResolveTargets, imageblock_layout_implicit> block,
    ushort2 tid [[thread_position_in_threadgroup]], uint2 group [[threadgroup_position_in_grid]],
    constant PackOptions &options [[buffer(0)]], constant MCShadowFrame &shadowFrame [[buffer(1)]],
    constant MCResolveCamera &camera [[buffer(2)]], constant McFog &fog [[buffer(3)]],
    depth2d_array<float> shadowMap [[texture(0)]], sampler shadowSampler [[sampler(0)]]) {
    uint2 p = group * 32u + uint2(tid);
    ResolveTargets previous = block.read(tid);
    float z = mc_unpack_view_depth(previous.normal, previous.light);
    uint scale = z < 64.0 ? 1u : (z < 128.0 ? 2u : 4u);
    ushort2 origin = tid & ushort(~(scale - 1u));
    ResolveTargets sample = block.read(origin);
    float sampleDepth = mc_unpack_view_depth(sample.normal, sample.light);
    uint sampleScale = sampleDepth < 64.0 ? 1u : (sampleDepth < 128.0 ? 2u : 4u);
    bool reuse = scale > 1u && scale == sampleScale && previous.albedo.a > 0.0
        && abs(sampleDepth - z) < .02 && all(abs(sample.normal.rg - previous.normal.rg) < .001)
        && all(abs(sample.albedo - previous.albedo) < .002)
        && all(abs(sample.scene - previous.scene) < .002)
        && all(abs(sample.light.rg - previous.light.rg) < .001);
    ResolveVaryings in = {float4(float2(p) + .5, 0, 1), float2(0)};
    float4 color = reuse ? shaded[(scale == 2u ? 0 : 256) + (origin.y / scale) * (32u / scale) + origin.x / scale]
        : shade(in, previous, options, shadowFrame, camera, fog, shadowMap, shadowSampler).scene;
    // All cross-pixel imageblock reads must finish before any lane overwrites them.
    threadgroup_barrier(mem_flags::mem_threadgroup_imageblock);
    previous.scene = color; previous.albedo.a = 0;
    block.write(previous, tid);
}
