// The pack's last pass: composite to the drawable, or show one G-buffer channel on its own.
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

vertex FinalVaryings final_vertex(uint vertexId [[vertex_id]]) {
    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
    float2 position = corners[vertexId % 3];
    return {float4(position, 0.0, 1.0), position * 0.5 + 0.5};
}

/// The inverse of the G-buffer's octahedral encoding, so the debug view shows a direction rather
/// than the two numbers that stand for one.
static inline float3 decode_normal(float2 encoded) {
    float2 f = encoded * 2.0 - 1.0;
    float3 n = float3(f, 1.0 - abs(f.x) - abs(f.y));
    float t = saturate(-n.z);
    n.xy += float2(n.x >= 0.0 ? -t : t, n.y >= 0.0 ? -t : t);
    return normalize(n);
}

/// A distinct colour per material class, so a wrong classification is visible rather than subtle.
/// The argument is the stored alpha byte, which is one past the class so that zero means absent.
static inline float3 material_color(int stored) {
    switch (stored) {
        case 0: return float3(0.0, 0.0, 0.0);     // nothing drew here
        case 1: return float3(0.55, 0.55, 0.55);  // solid
        case 2: return float3(0.20, 0.85, 0.25);  // foliage
        case 3: return float3(0.15, 0.45, 0.95);  // water
        case 4: return float3(0.95, 0.55, 0.15);  // entity
        case 5: return float3(0.95, 0.90, 0.25);  // emissive
        default: return float3(1.0, 0.0, 1.0);    // a class this pack does not know
    }
}

fragment float4 final_fragment(
    FinalVaryings in [[stage_in]],
    constant FinalOptions &options [[buffer(0)]],
    texture2d<float> scene [[texture(MC_TEX_SCENE)]],
    sampler sceneSampler [[sampler(MC_TEX_SCENE)]],
    texture2d<float> albedo [[texture(MC_TEX_GBUFFER_ALBEDO)]],
    sampler albedoSampler [[sampler(MC_TEX_GBUFFER_ALBEDO)]],
    texture2d<float> normal [[texture(MC_TEX_GBUFFER_NORMAL)]],
    sampler normalSampler [[sampler(MC_TEX_GBUFFER_NORMAL)]],
    texture2d<float> light [[texture(MC_TEX_GBUFFER_LIGHT)]],
    sampler lightSampler [[sampler(MC_TEX_GBUFFER_LIGHT)]],
    depth2d<float> depth [[texture(MC_TEX_DEPTH)]],
    sampler depthSampler [[sampler(MC_TEX_DEPTH)]]
) {
    float3 color;
    switch (options.debugView) {
        case MC_DEBUG_ALBEDO:
            color = albedo.sample(albedoSampler, in.uv).rgb;
            break;
        case MC_DEBUG_NORMAL:
            color = decode_normal(normal.sample(normalSampler, in.uv).rg) * 0.5 + 0.5;
            break;
        case MC_DEBUG_LIGHT: {
            float4 packed = light.sample(lightSampler, in.uv);
            // Block light red, sky light green, emissive blue: three channels, three readings.
            color = float3(packed.r, packed.g, packed.b);
            break;
        }
        case MC_DEBUG_MATERIAL:
            color = material_color(int(round(albedo.sample(albedoSampler, in.uv).a * 255.0)));
            break;
        case MC_DEBUG_DEPTH: {
            // Reversed-Z: near is one. Shown as a reciprocal so the near field is not a flat white.
            float sampled = depth.sample(depthSampler, in.uv);
            color = float3(saturate(1.0 - pow(sampled, 0.25)));
            break;
        }
        default:
            color = scene.sample(sceneSampler, in.uv).rgb;
            break;
    }

#if MC_OPTION_INVERT
    color = 1.0 - color;
#endif
    return float4(color * options.exposure, 1.0);
}

#endif // MC_PASS_FINAL
