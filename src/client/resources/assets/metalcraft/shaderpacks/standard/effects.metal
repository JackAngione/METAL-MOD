// Phase 5 post effects. Compute kernels are one translation unit; MC_PASS_* selects the entry
// point being compiled and MC_TEX_*/MC_IMAGE_* are emitted from each pass declaration.

#include <metal_stdlib>
using namespace metal;

struct EffectOptions {
    int debugView;
    float exposure;
    int shadowDistance;
    float shadowNormalOffset;
    int ssao;
    float ssaoStrength;
    int bloom;
    float bloomStrength;
    int volumetrics;
    float volumetricStrength;
    int tonemap;
    float saturation;
    float contrast;
    float temperature;
};

struct EffectShadowUniforms {
    float4x4 cascade[4];
    float4 splits;
    float4 lightDirectionAndNormalOffset;
    float4 mapSize;
    float4 celestial;
    float4x4 rasterProjection;
    float4x4 inverseRasterProjection;
};

static inline float2 mc_clamped_uv(uint2 gid, uint2 size) {
    return (float2(min(gid, size - 1)) + 0.5) / float2(size);
}

static inline float3 mc_view_position(float2 uv, float deviceDepth, constant EffectShadowUniforms &shadow) {
    float2 ndc = uv * 2.0 - 1.0;
    ndc.y = -ndc.y;
    float4 view = shadow.inverseRasterProjection * float4(ndc, deviceDepth, 1.0);
    return view.xyz / max(abs(view.w), 1e-8);
}

#ifdef MC_PASS_SSAO

kernel void ssao_kernel(
    depth2d<float> depth [[texture(MC_TEX_DEPTH)]],
    texture2d<float, access::write> output [[texture(MC_IMAGE_SSAO)]],
    constant EffectOptions &options [[buffer(0)]],
    constant EffectShadowUniforms &shadow [[buffer(1)]],
    uint2 gid [[thread_position_in_grid]]
) {
    uint2 size(output.get_width(), output.get_height());
    if (any(gid >= size)) return;

    float2 uv = mc_clamped_uv(gid, size);
    constexpr sampler nearestSampler(coord::normalized, address::clamp_to_edge, filter::nearest);
    float centerRaw = depth.sample(nearestSampler, uv);
    if (centerRaw <= 1e-5 || centerRaw >= 0.99999) {
        output.write(float4(1.0), gid);
        return;
    }

    // Reconstruct a local normal from depth neighbours, then reject samples in front of the
    // surface. The eight-tap ring runs at half resolution and is bilateral by construction: a
    // depth discontinuity cannot darken the foreground side of an edge.
    float2 texel = 1.0 / float2(size);
    float left = depth.sample(nearestSampler, uv - float2(texel.x, 0.0));
    float right = depth.sample(nearestSampler, uv + float2(texel.x, 0.0));
    float up = depth.sample(nearestSampler, uv - float2(0.0, texel.y));
    float down = depth.sample(nearestSampler, uv + float2(0.0, texel.y));
    float3 dx = mc_view_position(uv + float2(texel.x, 0.0), right, shadow)
        - mc_view_position(uv - float2(texel.x, 0.0), left, shadow);
    float3 dy = mc_view_position(uv + float2(0.0, texel.y), down, shadow)
        - mc_view_position(uv - float2(0.0, texel.y), up, shadow);
    float3 normal = normalize(cross(dx, dy));
    float3 centerPosition = mc_view_position(uv, centerRaw, shadow);

    constexpr float2 ring[8] = {
        float2(1.0, 0.0), float2(-1.0, 0.0), float2(0.0, 1.0), float2(0.0, -1.0),
        float2(0.707, 0.707), float2(-0.707, 0.707), float2(0.707, -0.707), float2(-0.707, -0.707)
    };
    float occlusion = 0.0;
    for (uint index = 0; index < 8; ++index) {
        float2 sampleUv = uv + ring[index] * texel * 4.0;
        float sampleDepth = depth.sample(nearestSampler, sampleUv);
        float3 delta = mc_view_position(sampleUv, sampleDepth, shadow) - centerPosition;
        float distanceWeight = saturate(1.0 - length(delta) / 3.0);
        occlusion += step(0.025, dot(normal, normalize(delta))) * distanceWeight;
    }
    float ao = 1.0 - (occlusion / 8.0) * options.ssaoStrength;
    output.write(float4(saturate(ao)), gid);
}

#endif

#if defined(MC_PASS_BLOOM_DOWN_HALF) || defined(MC_PASS_BLOOM_DOWN_QUARTER)

#ifdef MC_PASS_BLOOM_DOWN_HALF
#define MC_BLOOM_SOURCE MC_TEX_SCENE
#define MC_BLOOM_OUTPUT MC_IMAGE_BLOOM_HALF
#define MC_BLOOM_ENTRY bloom_down_half_kernel
#else
#define MC_BLOOM_SOURCE MC_TEX_BLOOM_HALF
#define MC_BLOOM_OUTPUT MC_IMAGE_BLOOM_QUARTER
#define MC_BLOOM_ENTRY bloom_down_quarter_kernel
#endif

kernel void MC_BLOOM_ENTRY(
    texture2d<float> source [[texture(MC_BLOOM_SOURCE)]],
    texture2d<float, access::write> output [[texture(MC_BLOOM_OUTPUT)]],
    constant EffectOptions &options [[buffer(0)]],
    uint2 gid [[thread_position_in_grid]]
) {
    uint2 size(output.get_width(), output.get_height());
    if (any(gid >= size)) return;
    float2 uv = mc_clamped_uv(gid, size);
    float2 texel = 1.0 / float2(source.get_width(), source.get_height());
    constexpr sampler linearSampler(coord::normalized, address::clamp_to_edge, filter::linear);
    float3 color = source.sample(linearSampler, uv + texel * float2(-0.5, -0.5)).rgb;
    color += source.sample(linearSampler, uv + texel * float2(0.5, -0.5)).rgb;
    color += source.sample(linearSampler, uv + texel * float2(-0.5, 0.5)).rgb;
    color += source.sample(linearSampler, uv + texel * float2(0.5, 0.5)).rgb;
    color *= 0.25;
#ifdef MC_PASS_BLOOM_DOWN_HALF
    color = max(color - 0.72, 0.0) / max(color, 0.001);
#endif
    output.write(float4(color, 1.0), gid);
}

#undef MC_BLOOM_SOURCE
#undef MC_BLOOM_OUTPUT
#undef MC_BLOOM_ENTRY
#endif

#ifdef MC_PASS_BLOOM_UP

kernel void bloom_up_kernel(
    texture2d<float> halfImage [[texture(MC_TEX_BLOOM_HALF)]],
    texture2d<float> quarterImage [[texture(MC_TEX_BLOOM_QUARTER)]],
    texture2d<float, access::write> output [[texture(MC_IMAGE_BLOOM)]],
    constant EffectOptions &options [[buffer(0)]],
    uint2 gid [[thread_position_in_grid]]
) {
    uint2 size(output.get_width(), output.get_height());
    if (any(gid >= size)) return;
    float2 uv = mc_clamped_uv(gid, size);
    constexpr sampler linearSampler(coord::normalized, address::clamp_to_edge, filter::linear);
    float3 color = halfImage.sample(linearSampler, uv).rgb;
    color += quarterImage.sample(linearSampler, uv).rgb * 0.75;
    output.write(float4(color, 1.0), gid);
}

#endif

#ifdef MC_PASS_VOLUMETRICS

static inline uint mc_effect_cascade(float depth, float4 splits) {
    if (depth <= splits.x) return 0;
    if (depth <= splits.y) return 1;
    if (depth <= splits.z) return 2;
    return 3;
}

kernel void volumetrics_kernel(
    depth2d<float> depth [[texture(MC_TEX_DEPTH)]],
    depth2d_array<float> shadowMap [[texture(MC_TEX_SHADOW)]],
    texture2d<float, access::write> output [[texture(MC_IMAGE_VOLUMETRIC)]],
    constant EffectOptions &options [[buffer(0)]],
    constant EffectShadowUniforms &shadow [[buffer(1)]],
    uint2 gid [[thread_position_in_grid]]
) {
    uint2 size(output.get_width(), output.get_height());
    if (any(gid >= size)) return;
    float2 uv = mc_clamped_uv(gid, size);
    constexpr sampler nearestSampler(coord::normalized, address::clamp_to_edge, filter::nearest);
    float3 surface = mc_view_position(uv, depth.sample(nearestSampler, uv), shadow);
    float surfaceDepth = min(max(-surface.z, 0.0), shadow.splits.w);
    float scattering = 0.0;
    constexpr uint steps = 12;
    for (uint stepIndex = 0; stepIndex < steps; ++stepIndex) {
        float t = (float(stepIndex) + 0.5) / float(steps);
        float3 position = surface * t;
        float distance = max(-position.z, 0.0);
        uint cascade = mc_effect_cascade(distance, shadow.splits);
        float4 clip = shadow.cascade[cascade] * float4(position, 1.0);
        float3 projected = clip.xyz / clip.w;
        float2 shadowUv = float2(projected.x, -projected.y) * 0.5 + 0.5;
        if (all(shadowUv >= 0.0) && all(shadowUv <= 1.0)) {
            float stored = shadowMap.sample(nearestSampler, shadowUv, cascade);
            scattering += projected.z <= stored + 0.0005 ? 1.0 : 0.0;
        }
    }
    float sunFacing = pow(saturate(-shadow.lightDirectionAndNormalOffset.z), 2.0);
    float shaft = scattering / float(steps) * options.volumetricStrength * (0.25 + sunFacing)
        * saturate(shadow.celestial.y);
    float3 tint = shadow.celestial.z < 1.5
        ? float3(1.0, 0.82, 0.58) : float3(0.32, 0.42, 0.72);
    output.write(float4(tint * shaft, 1.0), gid);
}

#endif

#ifdef MC_PASS_GRADE

struct GradeVaryings {
    float4 position [[position]];
    float2 uv;
};

vertex GradeVaryings grade_vertex(uint vertexId [[vertex_id]]) {
    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
    float2 position = corners[vertexId % 3];
    float2 uv = position * 0.5 + 0.5;
    // An intermediate fullscreen render must preserve texture row order. The final pass performs
    // the one scene-to-drawable conversion; letting this pass do it as well flips the game twice.
    uv.y = 1.0 - uv.y;
    return {float4(position, 0.0, 1.0), uv};
}

static inline float3 mc_aces(float3 color) {
    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;
    return saturate((color * (a * color + b)) / (color * (c * color + d) + e));
}

fragment float4 grade_fragment(
    GradeVaryings in [[stage_in]],
    constant EffectOptions &options [[buffer(0)]],
    texture2d<float> scene [[texture(MC_TEX_SCENE)]],
    texture2d<float> ssao [[texture(MC_TEX_SSAO)]],
    texture2d<float> bloom [[texture(MC_TEX_BLOOM)]],
    texture2d<float> volumetric [[texture(MC_TEX_VOLUMETRIC)]],
    sampler sceneSampler [[sampler(MC_TEX_SCENE)]],
    sampler ssaoSampler [[sampler(MC_TEX_SSAO)]],
    sampler bloomSampler [[sampler(MC_TEX_BLOOM)]],
    sampler volumetricSampler [[sampler(MC_TEX_VOLUMETRIC)]]
) {
    float3 color = scene.sample(sceneSampler, in.uv).rgb;
    if (options.ssao != 0) color *= ssao.sample(ssaoSampler, in.uv).r;
    if (options.bloom != 0) color += bloom.sample(bloomSampler, in.uv).rgb * options.bloomStrength;
    if (options.volumetrics != 0) color += volumetric.sample(volumetricSampler, in.uv).rgb;

    color *= options.exposure;
    if (options.tonemap != 0) color = mc_aces(color);
    float luma = dot(color, float3(0.2126, 0.7152, 0.0722));
    color = mix(float3(luma), color, options.saturation);
    color = (color - 0.5) * options.contrast + 0.5;
    color *= float3(1.0 + options.temperature * 0.10, 1.0, 1.0 - options.temperature * 0.10);
#if MC_OPTION_INVERT
    color = 1.0 - color;
#endif
    return float4(max(color, 0.0), 1.0);
}

#endif
