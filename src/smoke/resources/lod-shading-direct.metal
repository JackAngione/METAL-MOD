// P6 probe: one coordinate-verified shuffle instead of a ballot/search loop.
// Morton ordering is only a candidate address, never a correctness assumption.
// A GPU with another SIMD layout shades unmatched pixels at full resolution.
fragment ResolveTargets fixture_direct(ResolveVaryings in [[stage_in]], ResolveTargets previous,
    constant PackOptions &options [[buffer(0)]],
    constant MCShadowFrame &shadowFrame [[buffer(1)]],
    constant MCResolveCamera &camera [[buffer(2)]],
    constant McFog &fog [[buffer(3)]],
    depth2d_array<float> shadowMap [[texture(0)]], sampler shadowSampler [[sampler(0)]],
    ushort lane [[thread_index_in_simdgroup]]) {
    float z = mc_unpack_view_depth(previous.normal, previous.light);
    if (simd_all(z < 64.0 || z >= shadowFrame.shadowDistance))
        return shade(in, previous, options, shadowFrame, camera, fog, shadowMap, shadowSampler);
    uint2 p = uint2(in.position.xy);
    uint scale = z < 64.0 ? 1u : (z < 128.0 ? 2u : 4u);
    uint2 local = p & (scale - 1u);
    ushort offset = ushort((local.x & 1u) | ((local.y & 1u) << 1u)
        | ((local.x & 2u) << 1u) | ((local.y & 2u) << 2u));
    ushort owner = lane ^ offset;
    uint2 actualOrigin = simd_shuffle(p, owner);
    float4 a = simd_shuffle(previous.albedo, owner);
    float4 n = simd_shuffle(previous.normal, owner);
    float4 l = simd_shuffle(previous.light, owner);
    float4 s = simd_shuffle(previous.scene, owner);
    float ownerZ = mc_unpack_view_depth(n, l);
    uint ownerScale = ownerZ < 64.0 ? 1u : (ownerZ < 128.0 ? 2u : 4u);
    bool reuse = scale > 1u && ownerScale == scale && all(actualOrigin == (p & ~(scale - 1u)))
        && previous.albedo.a > 0.0 && abs(ownerZ - z) < .02
        && all(abs(n.rg - previous.normal.rg) < .001) && all(abs(a - previous.albedo) < .002)
        && all(abs(s - previous.scene) < .002) && all(abs(l.rg - previous.light.rg) < .001);
    float4 shaded = float4(0.0);
    if (!reuse || owner == lane)
        shaded = shade(in, previous, options, shadowFrame, camera, fog, shadowMap, shadowSampler).scene;
    float4 coarse = simd_shuffle(shaded, owner);
    ResolveTargets out = previous;
    out.scene = reuse ? coarse : shaded;
    out.albedo.a = 0.0;
    return out;
}
