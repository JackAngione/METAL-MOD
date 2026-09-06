#include <metal_stdlib>
using namespace metal;

#if MC_SCENE_LINEAR_HDR
#include "shared/color.metal"
#endif

#ifdef MC_PASS_GRADE

struct GradeVaryings {
    float4 position [[position]];
    float2 uv;
};

vertex GradeVaryings grade_vertex(uint vertexId [[vertex_id]]) {
    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
    float2 p = corners[vertexId % 3];
    return {float4(p, 0.0, 1.0), float2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5)};
}

static float3 acesFitted(float3 x) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return saturate((x * (a * x + b)) / (x * (c * x + d) + e));
}

fragment float4 grade_fragment(
    GradeVaryings in [[stage_in]],
    texture2d<float> sceneTex [[texture(MC_TEX_SCENE)]],
    sampler sceneSampler [[sampler(MC_TEX_SCENE)]],
    constant PackOptions &options [[buffer(0)]]
) {
    float3 sampled = sceneTex.sample(sceneSampler, in.uv).rgb;
    if (options.debugView == 1) {
#if MC_SCENE_LINEAR_HDR
        return float4(mc_linear_to_srgb(sampled), 1.0);
#else
        return float4(sampled, 1.0);
#endif
    }
    float3 color = sampled * options.exposure;
#if MC_OPTION_INVERT && !MC_SCENE_LINEAR_HDR
    color = float3(1.0) - color;
#endif
    if (options.tonemap == 1) {
        color = acesFitted(color);
    }
#if MC_SCENE_LINEAR_HDR
    // Only enable after every world producer supplies linear scene color.
    // Hand/HUD and presentation consume encoded RGB after this one transfer.
    color = mc_linear_to_srgb(color);
#if MC_OPTION_INVERT
    color = float3(1.0) - color;
#endif
#endif
    return float4(color, 1.0);
}

#endif
