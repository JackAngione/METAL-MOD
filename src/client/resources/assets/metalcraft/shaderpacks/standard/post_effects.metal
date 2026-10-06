#include <metal_stdlib>
using namespace metal;
#include "shared/options.metal"
#include "shared/color.metal"
#include "shared/post_effects.metal"

struct PostVaryings { float4 position [[position]]; float2 uv; };
vertex PostVaryings post_vertex(uint id [[vertex_id]]) {
    float2 p = float2(id == 1 ? 3.0 : -1.0, id == 2 ? 3.0 : -1.0);
    return {float4(p, 0.0, 1.0), float2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5)};
}

static float3 post_linear(float3 color) {
#if !MC_SCENE_LINEAR_HDR
    color = mc_srgb_to_linear(color);
#endif
    return select(float3(0.0), clamp(color, 0.0, 65504.0), isfinite(color));
}

fragment float4 bloom_extract_fragment(PostVaryings in [[stage_in]],
    texture2d<float> scene [[texture(0)]], sampler filtered [[sampler(0)]]) {
    // Four taps cover the source footprint rather than letting one subpixel
    // highlight flicker as it crosses a quarter-resolution texel.
    float2 texel = 1.0 / float2(scene.get_width(), scene.get_height());
    float3 result = 0.0;
    for (int y = -1; y <= 1; y += 2) for (int x = -1; x <= 1; x += 2) {
        float3 color = post_linear(scene.sample(filtered, in.uv + float2(x, y) * texel).rgb);
        float luminance = dot(color, float3(0.2126, 0.7152, 0.0722));
        // Ordinary bright blocks contribute smoothly, while dark surfaces stay
        // below the knee and cannot produce a broad gray veil.
        float knee = clamp(luminance - 0.06, 0.0, 0.12);
        float bright = max(luminance - 0.12, knee * knee / 0.24);
        result += color * (bright / max(luminance, 1.0e-5));
    }
    return float4(min(result * 0.25, 65504.0), 1.0);
}

// Normalized discrete Gaussians, sigma 3 / 5 / 8 quarter-resolution texels,
// truncated at ceil(3*sigma). Each (weight, offset) joins adjacent integer taps
// through hardware bilinear filtering. Dense support avoids shifted copies of
// silhouettes when the halo grows; tables keep exp work out of each fragment.
constant float MC_BLOOM_CENTERS[3] = {0.1331759960, 0.0799404796, 0.0499767536};
constant int MC_BLOOM_PAIR_COUNTS[3] = {5, 8, 12};
constant float2 MC_BLOOM_PAIRS[3][12] = {
    {float2(0.2326180956, 1.4584295168), float2(0.1355256135, 3.4039848067),
        float2(0.0512311399, 5.3518057801), float2(0.0125577013, 7.3029407160),
        float2(0.0014794517, 9.0000000000), float2(0.0),
        float2(0.0), float2(0.0),
        float2(0.0), float2(0.0),
        float2(0.0), float2(0.0)},
    {float2(0.1521519155, 1.4850044984), float2(0.1248206036, 3.4650570548),
        float2(0.0873975606, 5.4452207649), float2(0.0522289844, 7.4255574832),
        float2(0.0266388844, 9.4061268971), float2(0.0115958766, 11.3869858239),
        float2(0.0043078765, 13.3681875823), float2(0.0008880585, 15.0000000000),
        float2(0.0), float2(0.0),
        float2(0.0), float2(0.0)},
    {float2(0.0980269620, 1.4941408932), float2(0.0906877868, 3.4863315314),
        float2(0.0788341890, 5.4785288375), float2(0.0643936182, 7.4707366066),
        float2(0.0494234786, 9.4629586132), float2(0.0356439502, 11.4551986043),
        float2(0.0241546147, 13.4474602919), float2(0.0153806911, 15.4397473464),
        float2(0.0092026449, 17.4320633892), float2(0.0051738061, 19.4244119867),
        float2(0.0027331774, 21.4167966432), float2(0.0013567042, 23.4092207951)}
};

static float4 post_bloom_blur(float2 uv, float2 axis, texture2d<float> source,
    sampler filtered, constant PackOptions &options) {
    int tier = clamp(options.bloom - 1, 0, 2);
    float2 texel = axis / float2(source.get_width(), source.get_height());
    float3 color = source.sample(filtered, uv).rgb * MC_BLOOM_CENTERS[tier];
    for (int i = 0; i < MC_BLOOM_PAIR_COUNTS[tier]; i++) {
        float2 pair = MC_BLOOM_PAIRS[tier][i];
        float2 offset = texel * pair.y;
        color += (source.sample(filtered, uv + offset).rgb
            + source.sample(filtered, uv - offset).rgb) * pair.x;
    }
    return float4(color, 1.0);
}
fragment float4 bloom_horizontal_fragment(PostVaryings in [[stage_in]],
    texture2d<float> source [[texture(0)]], sampler filtered [[sampler(0)]],
    constant PackOptions &options [[buffer(0)]]) {
    return post_bloom_blur(in.uv, float2(1,0), source, filtered, options);
}
fragment float4 bloom_vertical_fragment(PostVaryings in [[stage_in]],
    texture2d<float> source [[texture(0)]], sampler filtered [[sampler(0)]],
    constant PackOptions &options [[buffer(0)]]) {
    return post_bloom_blur(in.uv, float2(0,1), source, filtered, options);
}

fragment float autofocus_fragment(PostVaryings in [[stage_in]],
    depth2d<float> depth [[texture(0)]], constant McPostFrame &frame [[buffer(1)]]) {
    int2 extent = int2(depth.get_width(), depth.get_height());
    int2 center = extent / 2;
    const int2 offsets[5] = {int2(0), int2(-2,0), int2(2,0), int2(0,-2), int2(0,2)};
    float values[5];
    for (int i = 0; i < 5; i++) {
        float d = depth.read(uint2(clamp(center + offsets[i], int2(0), extent - 1)));
        values[i] = d > 1.0e-7 ? mc_post_distance(d, frame) : 40.0;
    }
    // Median rejects single-pixel geometry and keeps the GPU autofocus bounded.
    for (int i = 1; i < 5; i++) for (int j = i; j > 0; j--)
        if (values[j] < values[j-1]) { float t = values[j]; values[j] = values[j-1]; values[j-1] = t; }
    return clamp(values[2], 0.25, 512.0);
}

fragment float4 dof_prepare_fragment(PostVaryings in [[stage_in]],
    texture2d<float> scene [[texture(0)]], depth2d<float> depth [[texture(1)]],
    texture2d<float> focus [[texture(2)]], sampler filtered [[sampler(0)]],
    constant PackOptions &options [[buffer(0)]], constant McPostFrame &frame [[buffer(1)]]) {
    uint2 position = min(uint2(in.uv * float2(depth.get_width(), depth.get_height())),
        uint2(depth.get_width() - 1, depth.get_height() - 1));
    float coc = mc_post_coc(mc_post_distance(depth.read(position), frame),
        focus.read(uint2(0)).r);
    return float4(post_linear(scene.sample(filtered, in.uv).rgb), coc);
}

fragment float4 dof_blur_fragment(PostVaryings in [[stage_in]],
    texture2d<float> source [[texture(0)]], sampler filtered [[sampler(0)]],
    constant PackOptions &options [[buffer(0)]]) {
    float4 center = source.sample(filtered, in.uv);
    if (center.a == 0.0) return center;
    const float2 disk[16] = {float2(1,0),float2(-1,0),float2(0,1),float2(0,-1),
        float2(0.707,0.707),float2(-0.707,0.707),float2(0.707,-0.707),float2(-0.707,-0.707),
        float2(0.462,0.191),float2(-0.462,0.191),float2(0.462,-0.191),float2(-0.462,-0.191),
        float2(0.191,0.462),float2(-0.191,0.462),float2(0.191,-0.462),float2(-0.191,-0.462)};
    // Radius and mix remain gentle independently. No minimum radius changes
    // pixels at the edge of the exact-sharp focus interval.
    float radius = 0.25 * mc_post_dof_radius(options.depthOfField) * abs(center.a);
    float2 texel = 1.0 / float2(source.get_width(), source.get_height());
    float3 color = center.rgb;
    float weight = 1.0;
    for (int i = 0; i < 16; i++) {
        float4 tap = source.sample(filtered, in.uv + disk[i] * texel * radius);
        // Signed CoC rejection prevents bright backgrounds crossing silhouettes
        // and near geometry bleeding into a focused or distant surface.
        float accept = 1.0 - smoothstep(0.04, 0.18, abs(tap.a - center.a));
        color += tap.rgb * accept;
        weight += accept;
    }
    return float4(color / weight, center.a);
}
