// P6 experiment: reuse shaded samples without ending the memoryless G-buffer pass.
// SIMD layout is not a spatial contract. Locate a band's top-left sample by ballot,
// verify its coordinates, and fall back when the sample is outside this SIMD group.
fragment ResolveTargets fixture_fused(ResolveVaryings in [[stage_in]], ResolveTargets previous,
    constant PackOptions &options [[buffer(0)]],
    constant MCShadowFrame &shadowFrame [[buffer(1)]],
    constant MCResolveCamera &camera [[buffer(2)]],
    constant McFog &fog [[buffer(3)]],
    depth2d_array<float> shadowMap [[texture(0)]], sampler shadowSampler [[sampler(0)]],
    ushort lane [[thread_index_in_simdgroup]]) {
    uint2 p = uint2(in.position.xy);
    float z = mc_unpack_view_depth(previous.normal, previous.light);
    if (simd_all(z < 64.0 || z >= shadowFrame.shadowDistance))
        return shade(in, previous, options, shadowFrame, camera, fog, shadowMap, shadowSampler);
    uint scale = z < 64.0 ? 1u : (z < 128.0 ? 2u : 4u);
    uint2 origin = p & ~(scale - 1u);
    // Every potential band origin participates, including inactive material pixels.
    // No early return or discard is allowed before the converged shuffle operations.
    ulong candidates = ulong(simd_ballot(all((p & 1u) == 0u)));
    ushort owner = lane;
    while (candidates != 0ul) {
        ushort candidate = ushort(ctz(candidates));
        uint2 q = simd_shuffle(p, candidate);
        if (all(q == origin)) owner = candidate;
        candidates &= candidates - 1ul;
    }
    float4 a = simd_shuffle(previous.albedo, owner);
    float4 n = simd_shuffle(previous.normal, owner);
    float4 l = simd_shuffle(previous.light, owner);
    float4 s = simd_shuffle(previous.scene, owner);
    float ownerZ = mc_unpack_view_depth(n, l);
    uint ownerScale = ownerZ < 64.0 ? 1u : (ownerZ < 128.0 ? 2u : 4u);
    bool reuse = scale > 1u && ownerScale == scale && previous.albedo.a > 0.0
        && abs(ownerZ - z) < .02 && all(abs(n.rg - previous.normal.rg) < .001)
        && all(abs(a - previous.albedo) < .002) && all(abs(s - previous.scene) < .002)
        && all(abs(l.rg - previous.light.rg) < .001);
    float4 shaded = float4(0.0);
    if (!reuse || owner == lane)
        shaded = shade(in, previous, options, shadowFrame, camera, fog, shadowMap, shadowSampler).scene;
    float4 coarse = simd_shuffle(shaded, owner);
    ResolveTargets out = previous;
    out.scene = reuse ? coarse : shaded;
    out.albedo.a = 0.0;
    return out;
}

// Half-linear-only control experiment: a quad cannot represent a 4x4 sample footprint.
fragment ResolveTargets fixture_quad(ResolveVaryings in [[stage_in]], ResolveTargets previous,
    constant PackOptions &options [[buffer(0)]],
    constant MCShadowFrame &shadowFrame [[buffer(1)]],
    constant MCResolveCamera &camera [[buffer(2)]],
    constant McFog &fog [[buffer(3)]],
    depth2d_array<float> shadowMap [[texture(0)]], sampler shadowSampler [[sampler(0)]],
    ushort lane [[thread_index_in_quadgroup]]) {
    float z = mc_unpack_view_depth(previous.normal, previous.light);
    if (quad_all(z < 64.0 || z >= shadowFrame.shadowDistance))
        return shade(in, previous, options, shadowFrame, camera, fog, shadowMap, shadowSampler);
    uint scale = z < 64.0 ? 1u : (z < 128.0 ? 2u : 4u);
    ushort owner = 0;
    float4 a = quad_shuffle(previous.albedo, owner);
    float4 n = quad_shuffle(previous.normal, owner);
    float4 l = quad_shuffle(previous.light, owner);
    float4 s = quad_shuffle(previous.scene, owner);
    float ownerZ = mc_unpack_view_depth(n, l);
    uint ownerScale = ownerZ < 64.0 ? 1u : (ownerZ < 128.0 ? 2u : 4u);
    bool reuse = scale > 1u && ownerScale == scale && previous.albedo.a > 0.0
        && abs(ownerZ - z) < .02 && all(abs(n.rg - previous.normal.rg) < .001)
        && all(abs(a - previous.albedo) < .002) && all(abs(s - previous.scene) < .002)
        && all(abs(l.rg - previous.light.rg) < .001);
    float4 shaded = float4(0.0);
    if (!reuse || owner == lane)
        shaded = shade(in, previous, options, shadowFrame, camera, fog, shadowMap, shadowSampler).scene;
    float4 coarse = quad_shuffle(shaded, owner);
    ResolveTargets out = previous;
    out.scene = reuse ? coarse : shaded;
    out.albedo.a = 0.0;
    return out;
}
