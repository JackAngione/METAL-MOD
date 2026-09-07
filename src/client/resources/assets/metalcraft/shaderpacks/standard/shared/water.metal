#ifndef MC_WATER_METAL
#define MC_WATER_METAL

#include <metal_stdlib>
using namespace metal;

// Water animation deliberately repeats in both space and time. Chunk coordinates are reduced
// before conversion to float, which keeps distant chunks precise; all wave vectors complete an
// integer number of cycles over the 256-block spatial period and the 1024-second time period.
constant float MC_WATER_SPATIAL_PERIOD = 256.0;
constant float MC_WATER_TIME_PERIOD = 1024.0;
constant float MC_WATER_TAU = 6.28318530717958647692;

static inline float3 mc_water_safe_normalize(float3 value, float3 fallback) {
    float magnitudeSquared = dot(value, value);
    return all(isfinite(value)) && magnitudeSquared > 1.0e-12
        ? value * rsqrt(magnitudeSquared) : fallback;
}

/// Returns a precise, repeating world anchor without converting a large chunk coordinate to float.
static inline float3 mc_water_periodic_world_position(int3 chunkPosition, float3 localPosition) {
    int3 wrappedChunk = ((chunkPosition % 256) + 256) % 256;
    // Keep the local coordinate unwrapped. Wrapping each vertex after this addition would make a
    // quad crossing 255 -> 256 interpolate through 127.5 instead of 255.5. Adjacent sections may
    // therefore differ by exactly 256 at the seam; the integer-cycle phases below make those
    // coordinates equivalent without damaging interpolation inside either primitive.
    return float3(wrappedChunk) + select(float3(0.0), localPosition, isfinite(localPosition));
}

static inline float3 mc_water_tangent_direction(float3 direction, float3 normal, float3 fallback) {
    return mc_water_safe_normalize(direction - normal * dot(direction, normal), fallback);
}

/// Two analytic wave scales. The result is a world-space unit normal on horizontal and vertical faces.
static inline float3 mc_water_animated_normal(
    float3 baseNormal,
    float3 flow,
    float3 periodicWorldPosition,
    float animationSeconds,
    float strength
) {
    float3 normal = mc_water_safe_normalize(baseNormal, float3(0.0, 1.0, 0.0));
    float boundedStrength = clamp(isfinite(strength) ? strength : 0.0, 0.0, 2.0);
    if (boundedStrength == 0.0) return normal;

    float3 axis = abs(normal.y) < 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0);
    float3 fallbackTangent = mc_water_safe_normalize(cross(axis, normal), float3(0.0, 0.0, 1.0));
    float3 waveA = mc_water_tangent_direction(float3(11.0, 3.0, 7.0), normal, fallbackTangent);
    float3 waveB = mc_water_tangent_direction(float3(-5.0, 13.0, 17.0), normal,
        mc_water_safe_normalize(cross(normal, waveA), fallbackTangent));
    float3 safeFlow = select(float3(0.0), flow, isfinite(flow));
    safeFlow -= normal * dot(safeFlow, normal);
    // Flow selects wave travel direction without rotating the integer-cycle spatial basis. This
    // preserves exact chunk-period seams while waterfalls and opposing currents still travel in
    // their metadata direction. Still water uses the positive default direction.
    float travelA = dot(safeFlow, waveA) < -1.0e-4 ? -1.0 : 1.0;
    float travelB = dot(safeFlow, waveB) < -1.0e-4 ? -1.0 : 1.0;
    float boundedTime = isfinite(animationSeconds)
        ? animationSeconds - floor(animationSeconds / MC_WATER_TIME_PERIOD) * MC_WATER_TIME_PERIOD
        : 0.0;
    float3 position = select(float3(0.0), periodicWorldPosition, isfinite(periodicWorldPosition));
    float phaseA = MC_WATER_TAU * (dot(position, float3(11.0, 3.0, 7.0)) / MC_WATER_SPATIAL_PERIOD
        - travelA * 205.0 * boundedTime / MC_WATER_TIME_PERIOD);
    float phaseB = MC_WATER_TAU * (dot(position, float3(-5.0, 13.0, 17.0)) / MC_WATER_SPATIAL_PERIOD
        - travelB * 451.0 * boundedTime / MC_WATER_TIME_PERIOD);
    float3 slope = waveA * (0.13 * cos(phaseA)) + waveB * (0.065 * cos(phaseB));
    return mc_water_safe_normalize(normal - slope * boundedStrength, normal);
}

/// Roughness-aware Schlick Fresnel for water (IOR approximately 1.33, F0 rounded to 0.02).
static inline float mc_water_fresnel(float nDotV, float roughness) {
    float cosine = saturate(isfinite(nDotV) ? nDotV : 0.0);
    float boundedRoughness = saturate(isfinite(roughness) ? roughness : 1.0);
    const float f0 = 0.02;
    float grazing = max(f0, 1.0 - boundedRoughness);
    float oneMinus = 1.0 - cosine;
    return clamp(f0 + (grazing - f0) * oneMinus * oneMinus * oneMinus * oneMinus * oneMinus,
        f0, grazing);
}

/// Adds the baseline sky and sun reflection while retaining vanilla lightmap/blocklight as baseColor.
/// Without a shadow map in the forward pass, squared surface skylight is the cave/occlusion proxy.
static inline float3 mc_water_reflection(
    float3 baseColor,
    float3 normalWorld,
    float3 viewToCameraWorld,
    float roughness,
    float skyLight,
    float4 sunDirectionEnergy,
    float4 environment
) {
    float3 base = select(float3(0.0), baseColor, isfinite(baseColor));
    float3 normal = mc_water_safe_normalize(normalWorld, float3(0.0, 1.0, 0.0));
    float3 view = mc_water_safe_normalize(viewToCameraWorld, normal);
    if (dot(normal, view) < 0.0) normal = -normal;
    float visibility = saturate(isfinite(skyLight) ? skyLight : 0.0);
    visibility *= visibility;
    float envAvailability = saturate(isfinite(environment.w) ? environment.w : 0.0) * visibility;
    float3 envColor = max(select(float3(0.0), environment.rgb, isfinite(environment.rgb)), float3(0.0));
    float boundedRoughness = saturate(isfinite(roughness) ? roughness : 1.0);
    float fresnel = mc_water_fresnel(dot(normal, view), boundedRoughness);
    float3 reflected = mix(base, envColor, fresnel * envAvailability);

    float sunEnergy = clamp(isfinite(sunDirectionEnergy.w) ? sunDirectionEnergy.w : 0.0, 0.0, 8.0);
    float3 sunRaw = select(float3(0.0), sunDirectionEnergy.xyz, isfinite(sunDirectionEnergy.xyz));
    float sunMagnitudeSquared = dot(sunRaw, sunRaw);
    if (sunEnergy == 0.0 || visibility == 0.0 || sunMagnitudeSquared <= 1.0e-12) return reflected;
    float3 sun = sunRaw * rsqrt(sunMagnitudeSquared);
    float nDotL = saturate(dot(normal, sun));
    float3 halfVector = mc_water_safe_normalize(sun + view, normal);
    float exponent = mix(128.0, 8.0, boundedRoughness);
    // This normalized-looking highlight is intentionally clamped: a point-sun GGX peak would be
    // singular at zero roughness without a finite solar disc representation.
    float lobe = min(pow(saturate(dot(normal, halfVector)), exponent) * nDotL, 1.0);
    return reflected + float3(sunEnergy * visibility * fresnel * lobe);
}

#endif
