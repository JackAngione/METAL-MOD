// The pack's last pass: copy the already-lit, forward-composited scene to the drawable.
//
// The engine compiles this file with MC_PASS_FINAL defined, MC_TEX_<TARGET> giving each declared
// read its texture index in the order the pass declared them, and MC_OPTION_<ID> carrying every
// recompile-mode option. Uniform-mode options arrive in buffer zero, in the order the manifest
// declares them.

#include <metal_stdlib>
using namespace metal;

#ifdef MC_PASS_FINAL

struct FinalVaryings {
    float4 position [[position]];
    float2 uv;
};

struct FinalOptions {
    int debugView;
    float exposure;
};

/// Must match the debug_view option's declared values, which is what the engine writes here.
#define MC_DEBUG_OFF      0
#define MC_DEBUG_ALBEDO   1
#define MC_DEBUG_NORMAL   2
#define MC_DEBUG_LIGHT    3
#define MC_DEBUG_MATERIAL 4
#define MC_DEBUG_DEPTH    5
#define MC_DEBUG_SHADOW0  6
#define MC_DEBUG_SHADOW1  7
#define MC_DEBUG_SHADOW2  8
#define MC_DEBUG_SHADOW3  9

vertex FinalVaryings final_vertex(uint vertexId [[vertex_id]]) {
    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
    float2 position = corners[vertexId % 3];
    return {float4(position, 0.0, 1.0), position * 0.5 + 0.5};
}

fragment float4 final_fragment(
    FinalVaryings in [[stage_in]],
    constant FinalOptions &options [[buffer(0)]],
    texture2d<float> scene [[texture(MC_TEX_SCENE)]],
    sampler sceneSampler [[sampler(MC_TEX_SCENE)]]
) {
    float3 color = scene.sample(sceneSampler, in.uv).rgb;

#if MC_OPTION_INVERT
    color = 1.0 - color;
#endif
    return float4(color * options.exposure, 1.0);
}

#endif // MC_PASS_FINAL
