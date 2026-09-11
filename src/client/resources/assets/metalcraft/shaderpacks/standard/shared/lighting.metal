#ifndef MC_LIGHTING_METAL
#define MC_LIGHTING_METAL

#if MC_SCENE_LINEAR_HDR
#include "shared/color.metal"
#endif
// G-buffer and deferred lighting contract (PR 7a). No occupancy, point lights, or GGX.
//
// Attachments:
//   scene        fogged(albedo * RGB lightmap * overlays). Emissive skips the lightmap.
//   albedo.rgb   surface before light. albedo.a = (materialId + 1) / 255; 0 means empty/sky.
//   normal.rg    octahedral unit normal in view space (same space as reconstructed viewPos).
//   normal.b     roughness placeholder (dielectric/Lambert in 7a; unused here).
//   light.rg     UV2 block/sky in [0, 1], from Minecraft's 0..240 range.
//   normal.a + light.ba  24-bit linear view depth over [0, 1024].
//
// Materials match WorldGeometryAdapter.Material ordinals. Emission and the vanilla seed's
// overlay mix stay in albedo/scene; they are not a separate shadowed term.
//
// The RGB lightmap is recovered from the unfogged seed, not resampled. Vanilla already
// applied sky energy isotropically (no N·L), so the shadowed sun term is that sky share:
//   sunWeight = saturate(sky / (sky + block) * shadowStrength)   when the sun is active
//   lit       = unfogged - unfogged * sunWeight * (1 - visibility)
// Gating on N·L would leave dawn ground and walls unshadowed because the seed still
// carries full sky lightmap on those faces. vis = 1 is an identity. Blocklight, emission,
// and fog stay unshadowed. cascadeCount == 0 (night, other dimensions, missing frame)
// leaves sunWeight = 0.

#ifndef MC_MATERIAL_SOLID
#define MC_MATERIAL_SOLID 0
#define MC_MATERIAL_FOLIAGE 1
#define MC_MATERIAL_WATER 2
#define MC_MATERIAL_ENTITY 3
#define MC_MATERIAL_EMISSIVE 4
#endif

struct McFog {
    float4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
};

// Compatibility boundary: decode the completed, unfogged vanilla lightmap seed.
// Lightmap/brightness and cardinal light remain artistic multipliers, not radiance.
// Alpha and G-buffer metadata never pass through the RGB transfer.
static inline float4 mc_scene_seed(float4 encoded) {
#if MC_SCENE_LINEAR_HDR
    return float4(mc_srgb_to_linear(encoded.rgb), encoded.a);
#else
    return encoded;
#endif
}

static inline float3 mc_scene_fog_color(constant McFog &fog) {
    return mc_scene_seed(fog.FogColor).rgb;
}

static inline float4 mc_chunk_fade(float4 scene, float visibility, constant McFog &fog) {
    return mix(float4(mc_scene_fog_color(fog), fog.FogColor.a * scene.a), scene, visibility);
}

static inline float mc_fog_spherical_distance(float3 pos) {
    return length(pos);
}

static inline float mc_fog_cylindrical_distance(float3 pos) {
    return max(length(pos.xz), abs(pos.y));
}

static inline float mc_linear_fog_value(float distance, float start, float end) {
    if (distance <= start) {
        return 0.0;
    }
    if (distance >= end) {
        return 1.0;
    }
    return (distance - start) / (end - start);
}

static inline float mc_fog_amount(float spherical, float cylindrical, constant McFog &fog) {
    float value = max(
        mc_linear_fog_value(spherical, fog.FogEnvironmentalStart, fog.FogEnvironmentalEnd),
        mc_linear_fog_value(cylindrical, fog.FogRenderDistanceStart, fog.FogRenderDistanceEnd)
    );
    return saturate(value * fog.FogColor.a);
}

static inline float4 mc_apply_fog(float4 color, float spherical, float cylindrical, constant McFog &fog) {
    return float4(mix(color.rgb, mc_scene_fog_color(fog), mc_fog_amount(spherical, cylindrical, fog)), color.a);
}

static inline float3 mc_unfog(float3 fogged, constant McFog &fog, float amount) {
    float remain = 1.0 - amount;
    if (remain <= 1e-3) {
        return mc_scene_fog_color(fog);
    }
    return (fogged - mc_scene_fog_color(fog) * amount) / remain;
}

static inline int mc_gbuffer_material(float albedoAlpha) {
    return int(round(saturate(albedoAlpha) * 255.0)) - 1;
}

static inline float2 mc_encode_normal(float3 n) {
    n /= max(abs(n.x) + abs(n.y) + abs(n.z), 1e-8);
    float2 encoded = n.xy;
    if (n.z < 0.0) {
        encoded = (1.0 - abs(float2(n.y, n.x))) * float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return encoded * 0.5 + 0.5;
}

static inline float3 mc_decode_normal(float2 encoded) {
    float2 f = encoded * 2.0 - 1.0;
    float3 n = float3(f, 1.0 - abs(f.x) - abs(f.y));
    if (n.z < 0.0) {
        n.xy = (1.0 - abs(n.yx)) * float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    return normalize(n);
}

static inline bool mc_sun_active(constant MCShadowFrame &frame) {
    // cascadeCount is the CPU night/dimension gate. Do not also require directionToSun.y > 0:
    // dawn is y ≈ 0 and is when ground shadows are longest.
    return frame.cascadeCount > 0u;
}

// Fraction of recovered (unfogged) lighting treated as the shadowed directional sun term.
static inline float mc_direct_sun_weight(float2 lightLevels, constant MCShadowFrame &frame, float shadowStrength) {
    if (!mc_sun_active(frame)) {
        return 0.0;
    }
    float block = saturate(lightLevels.x);
    float sky = saturate(lightLevels.y);
    return saturate(sky / max(sky + block, 1e-5) * max(shadowStrength, 0.0));
}

static inline float3 mc_light_unfogged(float3 unfogged, float sunWeight, float visibility) {
    return unfogged - unfogged * sunWeight * (1.0 - saturate(visibility));
}

// Rebuilds fogged lighting from the G-buffer seed. Emissive pixels keep the seed.
static inline float4 mc_compose_lighting(
    float4 scene,
    float4 albedo,
    float2 lightLevels,
    float3 cameraRelative,
    float visibility,
    constant McFog &fog,
    constant MCShadowFrame &frame,
    float shadowStrength
) {
    int material = mc_gbuffer_material(albedo.a);
    if (material == MC_MATERIAL_EMISSIVE) {
        return scene;
    }
    float spherical = mc_fog_spherical_distance(cameraRelative);
    float cylindrical = mc_fog_cylindrical_distance(cameraRelative);
    float amount = mc_fog_amount(spherical, cylindrical, fog);
    if (amount >= 0.999) {
        return scene;
    }
    float3 unfogged = mc_unfog(scene.rgb, fog, amount);
    float sunWeight = mc_direct_sun_weight(lightLevels, frame, shadowStrength);
    float3 lit = mc_light_unfogged(unfogged, sunWeight, visibility);
    return float4(mix(lit, mc_scene_fog_color(fog), amount), scene.a);
}
#endif
