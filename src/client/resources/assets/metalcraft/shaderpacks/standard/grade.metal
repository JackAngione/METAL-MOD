#include <metal_stdlib>
using namespace metal;

#include "shared/color.metal"
#if MC_STANDARD_POST_EFFECTS
#include "shared/post_effects.metal"
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

static bool hasColorGrading(constant PackOptions &options) {
    return options.temperature != 0.0 || options.tint != 0.0
        || options.contrast != 1.0 || options.saturation != 1.0
        || options.vibrance != 0.0 || options.gamma != 1.0
        || options.highlights != 0.0 || options.shadows != 0.0;
}

// All artistic controls operate on linear radiance. Uniform branches avoid
// transcendental work when a control is neutral, including the default path.
static float3 gradeLinear(float3 color, constant PackOptions &options) {
    color = select(float3(0.0), max(color, float3(0.0)), isfinite(color));
    if (options.temperature != 0.0 || options.tint != 0.0) {
        float3 gain = exp2(float3(0.25 * options.temperature + 0.125 * options.tint,
            -0.25 * options.tint, -0.25 * options.temperature + 0.125 * options.tint));
        // Retain white luminance so temperature/tint do not act as exposure.
        gain /= dot(gain, float3(0.2126, 0.7152, 0.0722));
        color *= gain;
    }
    if (options.shadows != 0.0 || options.highlights != 0.0) {
        float luminance = dot(color, float3(0.2126, 0.7152, 0.0722));
        float shadowWeight = 1.0 - smoothstep(0.0, 0.4, luminance);
        float highlightWeight = smoothstep(0.3, 1.5, luminance);
        color *= exp2(options.shadows * shadowWeight + options.highlights * highlightWeight);
    }
    if (options.contrast != 1.0) {
        color = max((color - 0.18) * options.contrast + 0.18, float3(0.0));
    }
    if (options.saturation != 1.0 || options.vibrance != 0.0) {
        float luminance = dot(color, float3(0.2126, 0.7152, 0.0722));
        float maximum = max(color.r, max(color.g, color.b));
        float minimum = min(color.r, min(color.g, color.b));
        float chroma = (maximum - minimum) / max(maximum, 0.00001);
        float amount = options.saturation * max(0.0, 1.0 + options.vibrance * (1.0 - chroma));
        color = max(mix(float3(luminance), color, amount), float3(0.0));
    }
    // Bound intermediates before tone mapping/pow; never discard HDR at 1.
    return min(color, float3(65504.0));
}

fragment float4 grade_fragment(
    GradeVaryings in [[stage_in]],
    texture2d<float> sceneTex [[texture(MC_TEX_SCENE)]],
    sampler sceneSampler [[sampler(MC_TEX_SCENE)]],
    constant PackOptions &options [[buffer(0)]]
#if MC_STANDARD_POST_EFFECTS
    , texture2d<float> bloomTex [[texture(1)]]
    , texture2d<float> dofTex [[texture(2)]]
    , constant McPostFrame &postFrame [[buffer(2)]]
#if MC_STANDARD_DOF
    , depth2d<float> worldDepth [[texture(3)]]
    , texture2d<float> focusTex [[texture(4)]]
#endif
#endif
#ifdef MC_BUFFER_UNDERWATER_FRAME
    , constant McUnderwaterFrame &underwater [[buffer(MC_BUFFER_UNDERWATER_FRAME)]]
#endif
) {
    float2 uv = in.uv;
#if MC_SCENE_LINEAR_HDR && defined(MC_BUFFER_UNDERWATER_FRAME)
    uv = mc_underwater_uv(uv, float2(sceneTex.get_width(), sceneTex.get_height()),
        underwater.animationSeconds, underwater.strength
            * MC_OPTION_WATER_ENABLED * MC_OPTION_WATER_UNDERWATER_DISTORTION);
#endif
    float3 sampled = sceneTex.sample(sceneSampler, uv).rgb;
    if (options.debugView == 1) {
#if MC_SCENE_LINEAR_HDR
        return float4(mc_linear_to_srgb(sampled), 1.0);
#else
        return float4(sampled, 1.0);
#endif
    }
#if MC_STANDARD_POST_EFFECTS
    bool spatialEffects = options.bloom > 0;
#if MC_STANDARD_DOF
    spatialEffects = spatialEffects || options.depthOfField > 0;
#endif
    if (spatialEffects) {
        float3 spatialColor = sampled;
        bool spatialChanged = false;
#if !MC_SCENE_LINEAR_HDR
        spatialColor = mc_srgb_to_linear(spatialColor);
#endif
#if MC_STANDARD_DOF
        if (options.depthOfField > 0) {
            uint2 depthPixel = min(uint2(uv * float2(worldDepth.get_width(), worldDepth.get_height())),
                uint2(worldDepth.get_width() - 1, worldDepth.get_height() - 1));
            float coc = mc_post_coc(mc_post_distance(worldDepth.read(depthPixel), postFrame),
                focusTex.read(uint2(0)).r);
            if (coc != 0.0) {
                // Bilateral upsampling uses signed CoC instead of mixing both sides
                // of a depth discontinuity with a plain bilinear lookup.
                float2 size = float2(dofTex.get_width(), dofTex.get_height());
                float2 base = floor(uv * size - 0.5) + 0.5;
                float3 blurred = 0.0;
                float total = 0.0;
                for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
                    float2 tapUv = (base + float2(x,y)) / size;
                    float4 tap = dofTex.sample(sceneSampler, tapUv);
                    float2 delta = abs(tapUv * size - uv * size);
                    float weight = max(0.0, 1.0 - delta.x) * max(0.0, 1.0 - delta.y)
                        * (1.0 - smoothstep(0.04, 0.18, abs(tap.a - coc)));
                    blurred += tap.rgb * weight;
                    total += weight;
                }
                if (total > 1.0e-5) {
                    spatialColor = mix(spatialColor, blurred / total,
                        mc_post_dof_blend(options.depthOfField) * abs(coc));
                    spatialChanged = true;
                }
            }
        }
#endif
        if (options.bloom > 0) {
            float intensity = options.bloom == 1 ? 0.6 : options.bloom == 2 ? 1.1 : 1.8;
            spatialColor += bloomTex.sample(sceneSampler, uv).rgb * intensity;
            spatialChanged = true;
        }
        if (spatialChanged) {
#if !MC_SCENE_LINEAR_HDR
            sampled = mc_linear_to_srgb(spatialColor);
#else
            sampled = spatialColor;
#endif
        }
    }
#endif
    float3 color = sampled * options.exposure;
    bool grading = hasColorGrading(options);
    if (grading) {
#if !MC_SCENE_LINEAR_HDR
        color = mc_srgb_to_linear(color);
#endif
        color = gradeLinear(color, options);
#if !MC_SCENE_LINEAR_HDR
        // The fallback's historical exposure/invert/tone mapping use encoded
        // RGB. Return to that domain before mapping even for tiny adjustments.
        color = mc_linear_to_srgb(color);
#endif
    }
#if MC_OPTION_INVERT && !MC_SCENE_LINEAR_HDR
    color = float3(1.0) - color;
#endif
    if (options.tonemap == 1) {
        color = acesFitted(color);
    } else if (options.tonemap == 2) {
        color = max(color, float3(0.0));
        color = color / (1.0 + color);
    }
    if (options.gamma != 1.0) {
#if !MC_SCENE_LINEAR_HDR
        color = mc_srgb_to_linear(color);
#endif
        color = pow(max(color, float3(0.0)), float3(1.0 / options.gamma));
#if !MC_SCENE_LINEAR_HDR
        color = mc_linear_to_srgb(color);
#endif
    }
#if MC_SCENE_LINEAR_HDR
    // Only enable after every world producer supplies linear scene color.
    // Hand/HUD and presentation consume encoded RGB after this one transfer.
    color = mc_linear_to_srgb(color);
#if MC_OPTION_INVERT
    color = float3(1.0) - color;
#endif
#endif
    if (grading) color = saturate(color);
#if MC_STANDARD_POST_EFFECTS
    if (options.filmGrain > 0) {
        float luminance = saturate(dot(color, float3(0.2126, 0.7152, 0.0722)));
        float amplitude = options.filmGrain == 1 ? 0.07 : options.filmGrain == 2 ? 0.14 : 0.21;
        // Symmetric monochrome noise, strongest in midtones; highlights and
        // black retain headroom so the perturbation does not bias brightness.
        float envelope = 4.0 * luminance * (1.0 - luminance);
        // Two world pixels per cell at 4K survive Retina display downsampling.
        float cellSize = max(1.0, float(sceneTex.get_height()) / 1080.0);
        uint2 grainCell = uint2(in.position.xy / cellSize);
        color += mc_post_grain(grainCell, postFrame.animationFrame) * amplitude * envelope;
    }
#endif
    return float4(color, 1.0);
}

#endif
