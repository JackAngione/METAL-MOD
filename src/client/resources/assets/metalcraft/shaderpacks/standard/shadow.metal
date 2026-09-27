// Terrain vertices stay in their original chunk-local format. Each draw has one instance per
// intersected cascade; baseInstance selects the section's camera-relative offset and cascade mask.
struct ShadowTerrainVertex {
    float3 position [[attribute(0)]];
    float4 color [[attribute(1)]];
    float2 uv [[attribute(2)]];
    short2 light [[attribute(3)]];
};
struct ShadowTerrainVaryings {
    float4 position [[position]];
    float2 uv;
    uint layer [[render_target_array_index]];
};
vertex ShadowTerrainVaryings shadow_terrain_vertex(
    ShadowTerrainVertex in [[stage_in]], uint instance [[instance_id]],
    constant MCShadowFrame& frame [[buffer(0)]], constant float4* sections [[buffer(1)]]) {
    uint ordinal = instance % frame.cascadeCount;
    uint section = instance / frame.cascadeCount;
    uint mask = as_type<uint>(sections[section].w);
    for (uint i = 0u; i < ordinal; i++) mask &= mask - 1u;
    uint layer = ctz(mask);
    float4 clip = frame.cameraRelativeToShadow[layer] * float4(in.position + sections[section].xyz, 1.0);
    return {float4(clip.x, -clip.y, clip.z, clip.w), in.uv, layer};
}
fragment void shadow_terrain_fragment(ShadowTerrainVaryings in [[stage_in]],
    texture2d<float> atlas [[texture(0)]], sampler atlasSampler [[sampler(0)]]) {
#if MC_HAS_ALPHA_CUTOUT
    if (atlas.sample(atlasSampler, in.uv).a < MC_ALPHA_CUTOUT) discard_fragment();
#endif
}
