#ifndef MC_LOD_METAL
#define MC_LOD_METAL
// Vertex-side metadata is indexed from zero in the replacement's own BLOCK buffer.
struct McLodVertex { float4 mapU; float4 mapV; float4 bounds; };

static inline float4 mc_lod_level(texture2d<float> atlas, sampler s, float2 uv, float4 bounds, float lod) {
    float2 extent = float2(atlas.get_width(), atlas.get_height());
    float2 inset = min(0.5 * exp2(lod) / extent, (bounds.zw - bounds.xy) * 0.5);
    return atlas.sample(s, clamp(uv, bounds.xy + inset, bounds.zw - inset), level(lod));
}

static inline float4 mc_lod_sample(texture2d<float> atlas, sampler s, float2 repeatUV,
                                  float4 mapU, float4 mapV, float4 bounds) {
    // Derivatives precede fract: a block-repeat seam must not select an unrelated coarse mip.
    float2 continuous = mapU.xy + repeatUV.x * mapU.zw + repeatUV.y * mapV.xy;
    float2 dx = dfdx(continuous), dy = dfdy(continuous);
    float2 extent = float2(atlas.get_width(), atlas.get_height());
    float footprint = sqrt(max(length(dx * extent) * length(dy * extent), 1.0));
    float2 tileSize = (bounds.zw - bounds.xy) * extent;
    float maxLod = min(float(atlas.get_num_mip_levels() - 1), floor(log2(max(min(tileSize.x, tileSize.y), 1.0))));
    float lod = clamp(log2(footprint), 0.0, maxLod);
    float2 local = fract(repeatUV);
    float2 uv = mapU.xy + local.x * mapU.zw + local.y * mapV.xy;
    return mix(mc_lod_level(atlas, s, uv, bounds, floor(lod)),
               mc_lod_level(atlas, s, uv, bounds, min(ceil(lod), maxLod)), fract(lod));
}
#endif
