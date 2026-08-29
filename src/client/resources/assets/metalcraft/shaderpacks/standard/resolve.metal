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
    // x: map size, y/z: projection tan-half-FOV.
    float4 mapSize;
    // x: selected body's elevation, y: weather intensity, z: 0 none / 1 sun / 2 moon.
    float4 celestial;
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

// Depth occupies normal A plus light B/A. Twenty-four bits over 1024 blocks gives sub-millimetre
// reconstruction, preventing quantisation from sliding a stationary receiver through its shadow.
static inline float mc_decode_view_depth(float3 encoded) {
    uint high = uint(round(encoded.x * 255.0));
    uint middle = uint(round(encoded.y * 255.0));
    uint low = uint(round(encoded.z * 255.0));
    return float((high << 16) | (middle << 8) | low) * (1024.0 / 16777215.0);
}

static inline float3 mc_fresnel_schlick(float cosTheta, float3 f0) {
    return f0 + (1.0 - f0) * pow(saturate(1.0 - cosTheta), 5.0);
}

static inline float mc_distribution_ggx(float nDotH, float roughness) {
    float alpha = roughness * roughness;
    float alpha2 = alpha * alpha;
    float denominator = nDotH * nDotH * (alpha2 - 1.0) + 1.0;
    return alpha2 / max(M_PI_F * denominator * denominator, 1e-5);
}

static inline float mc_geometry_schlick_ggx(float nDotDirection, float roughness) {
    float r = roughness + 1.0;
    float k = r * r * 0.125;
    return nDotDirection / max(nDotDirection * (1.0 - k) + k, 1e-5);
}

static inline float3 mc_sun_brdf(
    float3 albedo, float3 normal, float3 viewPosition, float3 lightDirection, float roughness,
    bool twoSided
) {
    float3 viewDirection = normalize(-viewPosition);
    float3 shadingNormal = normal;
    if (twoSided && dot(shadingNormal, lightDirection) < 0.0) shadingNormal = -shadingNormal;
    float nDotL = saturate(dot(shadingNormal, lightDirection));
    float nDotV = saturate(dot(shadingNormal, viewDirection));
    if (nDotL <= 0.0 || nDotV <= 0.0) return float3(0.0);

    float3 halfDirection = normalize(viewDirection + lightDirection);
    float nDotH = saturate(dot(shadingNormal, halfDirection));
    float vDotH = saturate(dot(viewDirection, halfDirection));
    float3 f0 = float3(0.04);
    float3 fresnel = mc_fresnel_schlick(vDotH, f0);
    float distribution = mc_distribution_ggx(nDotH, max(roughness, 0.045));
    float geometry = mc_geometry_schlick_ggx(nDotV, roughness)
        * mc_geometry_schlick_ggx(nDotL, roughness);
    float3 specular = distribution * geometry * fresnel / max(4.0 * nDotV * nDotL, 1e-4);
    float3 diffuse = (1.0 - fresnel) * albedo * (1.0 / M_PI_F);
    return (diffuse + specular) * nDotL;
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
    float viewDepth = mc_decode_view_depth(float3(fetched.normal.a, fetched.light.b, fetched.light.a));

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
            float visibility = mc_shadow_term(
                viewPosition, viewDepth, normal, shadowMap, shadowSampler, shadow
            );

            bool hasCelestial = shadow.celestial.z > 0.5;
            bool daytime = shadow.celestial.z < 1.5;
            float celestialIntensity = saturate(shadow.celestial.y);
            float horizon = smoothstep(0.0, 0.18, abs(shadow.celestial.x));
            float3 directionalColor = daytime
                ? float3(1.00, 0.93, 0.78)
                : float3(0.32, 0.40, 0.62);
            float roughness = max(fetched.normal.b, 0.045);
            float3 viewDirection = normalize(-viewPosition);
            float3 ambientFresnel = mc_fresnel_schlick(
                saturate(dot(normal, viewDirection)), float3(0.04)
            );
            float clearAmbient = daytime ? 0.25 + skyLight * 0.85 : 0.20 + skyLight * 0.35;
            float ambientIrradiance = hasCelestial
                ? clearAmbient * mix(0.35, 1.0, celestialIntensity)
                : 0.20 + skyLight * 0.35;
            float3 ambient = (1.0 - ambientFresnel) * fetched.albedo.rgb
                * (ambientIrradiance / M_PI_F);
            float3 directional = mc_sun_brdf(
                fetched.albedo.rgb, normal, viewPosition, shadow.lightDirectionAndNormalOffset.xyz,
                roughness, material == 2
            ) * directionalColor * visibility * skyLight * celestialIntensity
                * mix(0.55, 2.5, horizon);
            // Minecraft exposes block light as an isotropic scalar rather than a light position;
            // treat it as warm diffuse irradiance while retaining energy conservation.
            float3 block = fetched.albedo.rgb * (1.0 / M_PI_F) * float3(1.0, 0.48, 0.16)
                * pow(blockLight, 1.6) * 2.6;
            result = material == 5 ? fetched.albedo.rgb : ambient + directional + block;
            break;
        }
    }

    ResolveTargets out = fetched;
    out.scene = float4(result, fetched.scene.a);
    return out;
}

#endif // MC_PASS_RESOLVE
