// Deferred lighting, executed as the last draw of the opaque G-buffer encoder. The four colour
// inputs are framebuffer fetches: they remain in Apple tile memory and are never sampled or stored.

#include <metal_stdlib>
using namespace metal;

#ifdef MC_PASS_RESOLVE

struct ResolveVaryings {
    float4 position [[position]];
    float2 uv;
};

struct ResolveTargets {
    float4 scene  [[color(MC_TARGET_SCENE)]];
    float4 albedo [[color(MC_TARGET_GBUFFER_ALBEDO)]];
    float4 normal [[color(MC_TARGET_GBUFFER_NORMAL)]];
    float4 light  [[color(MC_TARGET_GBUFFER_LIGHT)]];
};

struct ResolveOptions {
    int debugView;
    float exposure;
    int shadowDistance;
    float shadowNormalOffset;
};

struct ShadowUniforms {
    float4x4 cascade[4];
    float4 splits;
    float4 lightDirectionAndNormalOffset;
    // x: map size, y/z: projection tan-half-FOV, w: signed solar elevation.
    float4 mapSize;
};

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

vertex ResolveVaryings resolve_vertex(uint vertexId [[vertex_id]]) {
    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
    float2 position = corners[vertexId % 3];
    return {float4(position, 0.0, 1.0), position * 0.5 + 0.5};
}

static inline float3 mc_decode_normal(float2 encoded) {
    float2 f = encoded * 2.0 - 1.0;
    float3 n = float3(f, 1.0 - abs(f.x) - abs(f.y));
    float t = saturate(-n.z);
    n.xy += float2(n.x >= 0.0 ? -t : t, n.y >= 0.0 ? -t : t);
    return normalize(n);
}

// Depth occupies the otherwise-unused B/A bytes of the light target. Sixteen bits over 1024
// blocks gives centimetre-scale reconstruction while block and sky light keep one byte each.
static inline float mc_decode_view_depth(float2 encoded) {
    uint high = uint(round(encoded.x * 255.0));
    uint low = uint(round(encoded.y * 255.0));
    return float((high << 8) | low) * (1024.0 / 65535.0);
}

static inline float3 mc_view_position(float2 uv, float depth, constant ShadowUniforms &shadow) {
    float2 ndc = uv * 2.0 - 1.0;
    return float3(
        ndc.x * depth * shadow.mapSize.y,
        -ndc.y * depth * shadow.mapSize.z,
        -depth
    );
}

static inline uint mc_cascade(float depth, float4 splits) {
    if (depth <= splits.x) return 0;
    if (depth <= splits.y) return 1;
    if (depth <= splits.z) return 2;
    return 3;
}

static inline float mc_shadow_term(
    float3 viewPosition,
    float viewDepth,
    float3 normal,
    depth2d_array<float> shadowMap,
    sampler shadowSampler,
    constant ShadowUniforms &shadow
) {
    if (viewDepth > shadow.splits.w) return 1.0;
    uint cascade = mc_cascade(viewDepth, shadow.splits);
    float4 clip = shadow.cascade[cascade] * float4(viewPosition, 1.0);
    float3 projected = clip.xyz / clip.w;
    float2 uv = float2(projected.x, -projected.y) * 0.5 + 0.5;
    if (any(uv < 0.0) || any(uv > 1.0) || projected.z < 0.0 || projected.z > 1.0) return 1.0;

    float texel = 1.0 / max(shadow.mapSize.x, 1.0);
    float grazing = 1.0 - saturate(abs(dot(normal, shadow.lightDirectionAndNormalOffset.xyz)));
    float receiverBias = 0.00035 + grazing * 0.00125;
    float visibility = 0.0;
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            float stored = shadowMap.sample(
                shadowSampler, uv + float2(x, y) * texel, cascade
            );
            visibility += projected.z - receiverBias <= stored ? 1.0 : 0.0;
        }
    }
    return visibility / 9.0;
}

static inline float3 mc_material_color(int stored) {
    switch (stored) {
        case 0: return float3(0.0);
        case 1: return float3(0.55);
        case 2: return float3(0.20, 0.85, 0.25);
        case 3: return float3(0.15, 0.45, 0.95);
        case 4: return float3(0.95, 0.55, 0.15);
        case 5: return float3(0.95, 0.90, 0.25);
        default: return float3(1.0, 0.0, 1.0);
    }
}

fragment ResolveTargets resolve_fragment(
    ResolveVaryings in [[stage_in]],
    ResolveTargets fetched,
    constant ResolveOptions &options [[buffer(0)]],
    constant ShadowUniforms &shadow [[buffer(1)]],
    depth2d_array<float> shadowMap [[texture(MC_TEX_SHADOW)]],
    sampler shadowSampler [[sampler(MC_TEX_SHADOW)]]
) {
    int material = int(round(fetched.albedo.a * 255.0));
    float3 normal = mc_decode_normal(fetched.normal.rg);
    float viewDepth = mc_decode_view_depth(fetched.light.ba);

    float3 result;
    switch (options.debugView) {
        case MC_DEBUG_ALBEDO:
            result = fetched.albedo.rgb;
            break;
        case MC_DEBUG_NORMAL:
            result = normal * 0.5 + 0.5;
            break;
        case MC_DEBUG_LIGHT:
            result = float3(fetched.light.rg, material == 5 ? 1.0 : 0.0);
            break;
        case MC_DEBUG_MATERIAL:
            result = mc_material_color(material);
            break;
        case MC_DEBUG_DEPTH:
            result = float3(saturate(viewDepth / max(shadow.splits.w, 1.0)));
            break;
        case MC_DEBUG_SHADOW0:
        case MC_DEBUG_SHADOW1:
        case MC_DEBUG_SHADOW2:
        case MC_DEBUG_SHADOW3: {
            uint layer = uint(options.debugView - MC_DEBUG_SHADOW0);
            result = float3(shadowMap.sample(shadowSampler, in.uv, layer));
            break;
        }
        default: {
            if (material == 0 || viewDepth <= 0.0) {
                result = fetched.scene.rgb;
                break;
            }
            float3 viewPosition = mc_view_position(in.uv, viewDepth, shadow);
            float blockLight = fetched.light.r;
            float skyLight = fetched.light.g;
            float ndotl = material == 2
                ? abs(dot(normal, shadow.lightDirectionAndNormalOffset.xyz))
                : saturate(dot(normal, shadow.lightDirectionAndNormalOffset.xyz));
            float visibility = mc_shadow_term(
                viewPosition, viewDepth, normal, shadowMap, shadowSampler, shadow
            );

            bool daytime = shadow.mapSize.w >= 0.0;
            float horizon = smoothstep(0.0, 0.18, abs(shadow.mapSize.w));
            float3 directionalColor = daytime
                ? float3(1.00, 0.93, 0.78)
                : float3(0.32, 0.40, 0.62);
            float3 ambient = fetched.albedo.rgb * (0.08 + skyLight * (daytime ? 0.26 : 0.11));
            float3 directional = fetched.albedo.rgb * directionalColor
                * ndotl * visibility * skyLight * mix(0.18, 0.78, horizon);
            float3 block = fetched.albedo.rgb * float3(1.0, 0.48, 0.16)
                * pow(blockLight, 1.6) * 0.85;
            result = material == 5 ? fetched.albedo.rgb : ambient + directional + block;
            break;
        }
    }

    ResolveTargets out = fetched;
    out.scene = float4(result, fetched.scene.a);
    return out;
}

#endif // MC_PASS_RESOLVE
