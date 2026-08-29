// The pack's last pass: spatially sample the graded scene into the drawable. When the selected
// output is larger and MetalFX is enabled, the runtime substitutes Apple's spatial scaler for this
// fallback draw; keeping this program makes equal-size output and unsupported devices deterministic.
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

vertex FinalVaryings final_vertex(uint vertexId [[vertex_id]]) {
    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
    float2 position = corners[vertexId % 3];
    return {float4(position, 0.0, 1.0), position * 0.5 + 0.5};
}

fragment float4 final_fragment(
    FinalVaryings in [[stage_in]],
    texture2d<float> graded [[texture(MC_TEX_POST_COLOR)]],
    sampler gradedSampler [[sampler(MC_TEX_POST_COLOR)]]
) {
    return float4(graded.sample(gradedSampler, in.uv).rgb, 1.0);
}

#endif // MC_PASS_FINAL
