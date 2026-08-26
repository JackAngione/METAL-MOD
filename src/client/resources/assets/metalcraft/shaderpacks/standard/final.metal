#include <metal_stdlib>
using namespace metal;

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
    texture2d<float, access::sample> scene [[texture(0)]],
    sampler sceneSampler [[sampler(0)]],
    constant float &exposure [[buffer(0)]]) {
    float4 color = scene.sample(sceneSampler, in.uv);
#if MC_OPTION_INVERT
    color.rgb = 1.0 - color.rgb;
#endif
    color.rgb *= exposure;
    return color;
}
