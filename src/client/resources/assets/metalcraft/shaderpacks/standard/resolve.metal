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
    int localLights;
    float localLightStrength;
    int localShadowCount;
    int localShadowResolution;
};

struct LocalLight {
    float4 positionRadius;
    float4 colorIntensity;
    // x: cube-shadow slot or -1. World voxel occlusion applies to every light.
    int4 metadata;
};

struct LocalLighting {
    // x: light count, y/z: tile grid, w: fixed tile stride.
    uint4 header;
    // xyz: world-block origin of the occupancy volume, w: volume edge length.
    int4 occupancyOrigin;
    // xyz: camera block, w: 1 when the volume contains any solid.
    int4 cameraBlock;
    float4 cameraFrac;
    float4x4 worldFromView;
    LocalLight lights[256];
};

struct LocalShadowUniforms {
    float4x4 face[24];
    uint4 parameters;
};

struct ShadowUniforms {
    float4x4 cascade[4];
    float4 splits;
    float4 lightDirectionAndNormalOffset;
    // x: map size, y/z: projection tan-half-FOV.
    float4 mapSize;
    // x: selected body's elevation, y: weather intensity, z: 0 none / 1 sun / 2 moon.
    float4 celestial;
    // GPU projection Minecraft rasterized, including reversed-Z and view-bob.
    float4x4 rasterProjection;
    float4x4 inverseRasterProjection;
    float4x4 viewRotation;
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
#define MC_DEBUG_LOCAL_LIGHT_COUNT 10
#define MC_DEBUG_LOCAL_SHADOWS 11

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

static inline uint mc_local_shadow_face(float3 direction) {
    float3 magnitude = abs(direction);
    if (magnitude.x >= magnitude.y && magnitude.x >= magnitude.z) return direction.x >= 0.0 ? 0u : 1u;
    if (magnitude.y >= magnitude.z) return direction.y >= 0.0 ? 2u : 3u;
    return direction.z >= 0.0 ? 4u : 5u;
}

static inline float mc_local_shadow_term(
    float3 viewPosition,
    LocalLight light,
    depth2d_array<float> shadowMap,
    sampler shadowSampler,
    constant LocalShadowUniforms &shadow
) {
    int slot = light.metadata.x;
    if (slot < 0 || uint(slot) >= shadow.parameters.x) return 1.0;
    uint layer = uint(slot) * 6u + mc_local_shadow_face(viewPosition - light.positionRadius.xyz);
    float4 clip = shadow.face[layer] * float4(viewPosition, 1.0);
    float3 projected = clip.xyz / clip.w;
    float2 uv = float2(projected.x, -projected.y) * 0.5 + 0.5;
    if (any(uv < 0.0) || any(uv > 1.0) || projected.z < 0.0 || projected.z > 1.0) return 1.0;
    float texel = 1.0 / max(float(shadow.parameters.y), 1.0);
    float visibility = 0.0;
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            float stored = shadowMap.sample(shadowSampler, uv + float2(x, y) * texel, layer);
            visibility += projected.z - 0.0015 <= stored ? 1.0 : 0.0;
        }
    }
    return visibility / 9.0;
}

static inline float3 mc_world_position(float3 viewPosition, constant LocalLighting &lighting) {
    float3 relative = (lighting.worldFromView * float4(viewPosition, 1.0)).xyz;
    return float3(lighting.cameraBlock.xyz) + lighting.cameraFrac.xyz + relative;
}

static inline bool mc_occupancy_solid(
    int3 voxel, constant LocalLighting &lighting, device const uint *occupancy
) {
    int size = lighting.occupancyOrigin.w;
    int3 local = voxel - lighting.occupancyOrigin.xyz;
    if (any(local < 0) || any(local >= size)) return false;
    uint index = uint((local.y * size + local.z) * size + local.x);
    return (occupancy[index >> 5] & (1u << (index & 31u))) != 0u;
}

// Amanatides & Woo DDA through the camera-centred occupancy volume. Opaque world blocks
// occlude every local light, including those that do not own a cube-shadow slot.
static inline float mc_voxel_visibility(
    float3 startWorld,
    float3 endWorld,
    constant LocalLighting &lighting,
    device const uint *occupancy
) {
    if (lighting.occupancyOrigin.w <= 0 || lighting.cameraBlock.w == 0) return 1.0;
    float3 delta = endWorld - startWorld;
    float lengthSquared = dot(delta, delta);
    if (lengthSquared < 0.25) return 1.0;
    int3 voxel = int3(floor(startWorld));
    int3 endVoxel = int3(floor(endWorld));
    int3 difference = endVoxel - voxel;
    int manhattan = abs(difference.x) + abs(difference.y) + abs(difference.z);
    if (manhattan <= 1) return 1.0;

    float3 dir = delta / sqrt(lengthSquared);
    int3 step = int3(dir.x >= 0.0 ? 1 : -1, dir.y >= 0.0 ? 1 : -1, dir.z >= 0.0 ? 1 : -1);
    float3 inv = 1.0 / max(abs(dir), float3(1e-8));
    float3 nextBoundary = float3(voxel) + float3(dir.x >= 0.0, dir.y >= 0.0, dir.z >= 0.0);
    float3 tMax = (nextBoundary - startWorld) / dir;
    tMax = select(float3(1e30), tMax, abs(dir) > 1e-8);
    int3 startVoxel = voxel;
    for (int i = 0; i < 48; ++i) {
        if (all(voxel == endVoxel)) return 1.0;
        if (!all(voxel == startVoxel) && mc_occupancy_solid(voxel, lighting, occupancy)) return 0.0;
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            voxel.x += step.x;
            tMax.x += inv.x;
        } else if (tMax.y < tMax.z) {
            voxel.y += step.y;
            tMax.y += inv.y;
        } else {
            voxel.z += step.z;
            tMax.z += inv.z;
        }
    }
    return 1.0;
}

static inline float3 mc_local_lighting(
    float3 albedo,
    float3 normal,
    float3 viewPosition,
    float roughness,
    bool twoSided,
    float2 pixel,
    constant LocalLighting &lighting,
    device const uint *tiles,
    device const uint *occupancy,
    depth2d_array<float> localShadowMap,
    sampler localShadowSampler,
    constant LocalShadowUniforms &localShadow,
    float strength
) {
    if (lighting.header.x == 0 || lighting.header.y == 0 || lighting.header.z == 0) {
        return float3(0.0);
    }
    uint tileX = min(uint(pixel.x) / 16u, lighting.header.y - 1u);
    uint tileY = min(uint(pixel.y) / 16u, lighting.header.z - 1u);
    uint base = (tileY * lighting.header.y + tileX) * lighting.header.w;
    uint count = min(tiles[base], 64u);
    float3 worldPosition = mc_world_position(viewPosition, lighting);
    float3 result = float3(0.0);
    for (uint entry = 0; entry < count; ++entry) {
        uint lightIndex = tiles[base + 1u + entry];
        if (lightIndex >= lighting.header.x) continue;
        LocalLight light = lighting.lights[lightIndex];
        float3 toLight = light.positionRadius.xyz - viewPosition;
        float distanceSquared = dot(toLight, toLight);
        float radius = light.positionRadius.w;
        if (distanceSquared >= radius * radius || distanceSquared < 1e-6) continue;
        float distance = sqrt(distanceSquared);
        float normalizedDistance = distance / radius;
        float window = saturate(1.0 - normalizedDistance * normalizedDistance
            * normalizedDistance * normalizedDistance);
        float attenuation = window * window / max(distanceSquared, 0.25);
        float3 brdf = mc_sun_brdf(
            albedo, normal, viewPosition, toLight / distance, roughness, twoSided
        );
        float3 lightWorld = mc_world_position(light.positionRadius.xyz, lighting);
        float visibility = mc_voxel_visibility(worldPosition, lightWorld, lighting, occupancy)
            * mc_local_shadow_term(
                viewPosition, light, localShadowMap, localShadowSampler, localShadow
            );
        result += brdf * light.colorIntensity.rgb * light.colorIntensity.w
            * attenuation * radius * radius * visibility * strength;
    }
    return result;
}

static inline uint mc_tile_light_count(
    float2 pixel, constant LocalLighting &lighting, device const uint *tiles
) {
    if (lighting.header.y == 0 || lighting.header.z == 0) return 0;
    uint tileX = min(uint(pixel.x) / 16u, lighting.header.y - 1u);
    uint tileY = min(uint(pixel.y) / 16u, lighting.header.z - 1u);
    return min(tiles[(tileY * lighting.header.y + tileX) * lighting.header.w], 64u);
}

static inline float3 mc_view_position(float2 uv, float depth, constant ShadowUniforms &shadow) {
    // Packed view-Z is exact. XY must be solved through the same projection the G-buffer used,
    // or lighting is evaluated on camera rays and slides with view-bob.
    float2 ndc = uv * 2.0 - 1.0;
    ndc.y = -ndc.y;
    float viewZ = -depth;
    float4 col0 = shadow.rasterProjection[0];
    float4 col1 = shadow.rasterProjection[1];
    float4 known = shadow.rasterProjection[2] * viewZ + shadow.rasterProjection[3];
    float2 a1 = col0.xy - ndc * col0.w;
    float2 a2 = col1.xy - ndc * col1.w;
    float det = a1.x * a2.y - a2.x * a1.y;
    float2 b = ndc * known.w - known.xy;
    float2 xy = abs(det) > 1e-8
        ? float2(b.x * a2.y - a2.x * b.y, a1.x * b.y - b.x * a1.y) / det
        : float2(ndc.x * depth * shadow.mapSize.y, ndc.y * depth * shadow.mapSize.z);
    return float3(xy, viewZ);
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
    float3 relativeWorld = transpose(float3x3(
        shadow.viewRotation[0].xyz, shadow.viewRotation[1].xyz, shadow.viewRotation[2].xyz
    )) * viewPosition;
    float4 clip = shadow.cascade[cascade] * float4(relativeWorld, 1.0);
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
    constant LocalLighting &localLighting [[buffer(2)]],
    device const uint *localTiles [[buffer(3)]],
    constant LocalShadowUniforms &localShadow [[buffer(4)]],
    device const uint *occupancy [[buffer(5)]],
    depth2d_array<float> shadowMap [[texture(MC_TEX_SHADOW)]],
    depth2d_array<float> localShadowMap [[texture(1)]],
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
        case MC_DEBUG_LOCAL_LIGHT_COUNT: {
            float heat = float(mc_tile_light_count(in.position.xy, localLighting, localTiles)) / 64.0;
            result = float3(saturate(heat * 2.0), saturate(1.0 - abs(heat * 2.0 - 1.0)), saturate(1.0 - heat * 2.0));
            break;
        }
        case MC_DEBUG_LOCAL_SHADOWS: {
            uint count = mc_tile_light_count(in.position.xy, localLighting, localTiles);
            uint tileX = min(uint(in.position.x) / 16u, localLighting.header.y - 1u);
            uint tileY = min(uint(in.position.y) / 16u, localLighting.header.z - 1u);
            uint base = (tileY * localLighting.header.y + tileX) * localLighting.header.w;
            uint shadowed = 0;
            for (uint entry = 0; entry < count; ++entry) {
                uint lightIndex = localTiles[base + 1u + entry];
                if (lightIndex < localLighting.header.x && localLighting.lights[lightIndex].metadata.x >= 0) shadowed++;
            }
            result = float3(float(shadowed) / 4.0, shadowed > 0 ? 0.35 : 0.0, 0.0);
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
            float3 local = options.localLights != 0 ? mc_local_lighting(
                fetched.albedo.rgb, normal, viewPosition, roughness, material == 2,
                in.position.xy, localLighting, localTiles, occupancy,
                localShadowMap, shadowSampler, localShadow, options.localLightStrength
            ) : float3(0.0);
            float3 fill = fetched.albedo.rgb * blockLight * 0.03;
            result = material == 5 ? fetched.albedo.rgb : ambient + directional + local + fill;
            break;
        }
    }

    ResolveTargets out = fetched;
    out.scene = float4(result, fetched.scene.a);
    return out;
}

#endif // MC_PASS_RESOLVE
