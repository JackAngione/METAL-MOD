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
constant float MC_WATER_MAX_THICKNESS = 24.0;
constant float MC_WATER_MAX_REFRACTION_PIXELS = 8.0;
// A full source block over a solid shelf reconstructs to roughly one block of thickness.
// Keep that common shoreline case inside the contact band while rejecting deeper water.
constant float MC_WATER_FOAM_MAX_CONTACT_DISTANCE = 1.5;

struct McWaterSsrHit {
    float3 color;
    float confidence;
    float3 viewPosition;
    float _pad;
};

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

// Independent crossing packets; each velocity covers whole spatial periods per time loop.
// Vertical faces retain coherent downward flow instead of cross-surface ocean motion.
static inline float3 mc_water_pattern_velocity(float3 normal, int band) {
    const float2 velocities[7] = {float2(0.5,0.25), float2(-0.25,0.5),
        float2(0.25,-0.5), float2(-0.5,-0.25), float2(0.0,0.25),
        float2(0.25,0.0), float2(-0.25,0.25)};
    float2 v = velocities[band % 7];
    return abs(normal.y) >= 0.5 ? float3(v.x,0,v.y) : float3(0,-0.5,0);
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
    float boundedTime = isfinite(animationSeconds)
        ? animationSeconds - floor(animationSeconds / MC_WATER_TIME_PERIOD) * MC_WATER_TIME_PERIOD
        : 0.0;
    float3 position = select(float3(0.0), periodicWorldPosition, isfinite(periodicWorldPosition));
    float3 positionB = fract((position - mc_water_pattern_velocity(normal, 1) * boundedTime) / 256.0) * 256.0;
    position = fract((position - mc_water_pattern_velocity(normal, 0) * boundedTime) / 256.0) * 256.0;
    float phaseA = MC_WATER_TAU * dot(position, float3(11.0, 3.0, 7.0)) / MC_WATER_SPATIAL_PERIOD;
    float phaseB = MC_WATER_TAU * dot(positionB, float3(-5.0, 13.0, 17.0)) / MC_WATER_SPATIAL_PERIOD;
    float3 slope = waveA * (0.13 * cos(phaseA)) + waveB * (0.065 * cos(phaseB));
    return mc_water_safe_normalize(normal - slope * boundedStrength, normal);
}

// Periodic value noise supplies coherent local currents rather than per-frame randomness.
// Integer hashing is independent of floating-point sine precision at chunk boundaries.
static inline float mc_water_detail_hash(int3 cell) {
    uint3 c = uint3((cell % 32 + 32) % 32);
    uint h = c.x * 1597334677u ^ c.y * 3812015801u ^ c.z * 2798796415u;
    h = (h ^ (h >> 16)) * 2246822519u;
    h = (h ^ (h >> 13)) * 3266489917u;
    return float(h ^ (h >> 16)) / 4294967295.0;
}

static inline float mc_water_detail_noise(float3 p) {
    int3 cell = int3(floor(p));
    float3 f = fract(p);
    f = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float a = mix(mc_water_detail_hash(cell), mc_water_detail_hash(cell + int3(1,0,0)), f.x);
    float b = mix(mc_water_detail_hash(cell + int3(0,1,0)), mc_water_detail_hash(cell + int3(1,1,0)), f.x);
    float c = mix(mc_water_detail_hash(cell + int3(0,0,1)), mc_water_detail_hash(cell + int3(1,0,1)), f.x);
    float d = mix(mc_water_detail_hash(cell + int3(0,1,1)), mc_water_detail_hash(cell + int3(1,1,1)), f.x);
    return mix(mix(a, b, f.y), mix(c, d, f.y), f.z);
}

// Rounded cellular clumps: jittered geometric centers with compact smooth kernels.
// Periodic hashing keeps neighboring chunks identical; weighted overlapping cells
// avoid Voronoi edge discontinuities while retaining distinct clustered shapes.
static inline float3 mc_water_geometric_clumps_gradient(float2 p) {
    int2 cell = int2(floor(p));
    float2 local = fract(p);
    float sum = 0.0;
    float weights = 0.0;
    float2 numeratorGradient = float2(0), weightGradient = float2(0);
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            int3 key = int3(cell + int2(x, y), 7);
            float2 center = float2(x, y) + 0.3 + 0.4 * float2(
                mc_water_detail_hash(key), mc_water_detail_hash(key + int3(0,0,11)));
            float2 delta = local - center;
            float cellWeight = saturate(1.0 - dot(delta, delta) / 1.44);
            float2 derivative = (-6.0/1.44) * delta * cellWeight * cellWeight;
            cellWeight = cellWeight * cellWeight * cellWeight;
            float height = mc_water_detail_hash(key + int3(0,0,19));
            numeratorGradient += derivative * height;
            weightGradient += derivative;
            sum += cellWeight * height;
            weights += cellWeight;
        }
    }
    float height = sum / max(weights, 1.0e-6);
    return float3(height, (numeratorGradient - height*weightGradient) / max(weights, 1.0e-6));
}

static inline float mc_water_geometric_clumps(float2 p) {
    return mc_water_geometric_clumps_gradient(p).x;
}

// Circular kernels blend the four neighboring lattice values without the straight isolines
// from separate x/y interpolation. Return height and exact derivatives for smooth normals.
static inline float3 mc_water_height_noise_gradient(float2 p, int period = 32) {
    int2 cell = int2(floor(p));
    float2 f = fract(p);
    float sum = 0.0;
    float weights = 0.0;
    float2 sumGradient = float2(0);
    float2 weightGradient = float2(0);
    for (int y = 0; y <= 1; ++y) {
        for (int x = 0; x <= 1; ++x) {
            float2 delta = f - float2(x,y);
            float radius = saturate(1.0 - dot(delta,delta));
            float weight = radius * radius * radius;
            float2 derivative = -6.0 * delta * radius * radius;
            float value = mc_water_detail_hash(int3(
                ((cell + int2(x,y)) % period + period) % period, 0));
            sum += weight * value;
            weights += weight;
            sumGradient += derivative * value;
            weightGradient += derivative;
        }
    }
    float height = sum / weights;
    return float3(height, (sumGradient - height * weightGradient) / weights);
}

struct McWaterCurrentWarp {
    float2 offset;
    float2 dx;
    float2 dy;
};

// Crossing currents bend wave coordinates locally. Both fields span exactly 256 blocks
// and travel whole periods in 1024 seconds, closing spatial and animation seams.
static inline McWaterCurrentWarp mc_water_current_warp(float2 surface, float time) {
    float3 u = mc_water_height_noise_gradient(
        (surface - float2(0.25,-0.25) * time) / 32.0 + float2(1.7,3.9), 8);
    float3 v = mc_water_height_noise_gradient(
        (surface - float2(-0.25,0.5) * time) / 32.0 + float2(5.3,2.1), 8);
    return {6.0 * (float2(u.x,v.x) - 0.5),
        (6.0 / 32.0) * float2(u.y,v.y),
        (6.0 / 32.0) * float2(u.z,v.z)};
}

static inline float2 mc_water_warped_gradient(float2 gradient, McWaterCurrentWarp current, float amount) {
    return gradient + amount * float2(dot(current.dx, gradient), dot(current.dy, gradient));
}

static inline float3 mc_water_detail_height_gradient(
    float2 surface, float footprint, int quality, float time, bool horizontal, McWaterCurrentWarp current
) {
    // The first packet has the lowest frequency; every later packet is finer. Once it is
    // fully filtered, even the shared clump envelope cannot contribute to the result.
    if (quality <= 0 || footprint * 0.25 * length(float2(1, 2)) >= 0.45) return float3(0);
    float2 clumpVelocity = horizontal ? float2(0.25,0.0) : float2(0,-0.5);
    float3 clump = mc_water_geometric_clumps_gradient(
        (surface + current.offset * 0.6 - clumpVelocity * time) * 0.25);
    float envelope = 0.5 + clump.x;
    float2 envelopeGradient = mc_water_warped_gradient(clump.yz * 0.25, current, 0.6);
    const float slopes[7] = {0.32, 0.26, 0.19, 0.14, 0.10, 0.075, 0.05};
    const float2 axes[7] = {float2(1,2), float2(2,-1), float2(2,3),
        float2(-3,2), float2(1,-3), float2(3,1), float2(-2,-3)};
    int bands = quality <= 0 ? 0 : min(quality, 3) * 2 + 1;
    float frequency = 0.25;
    float3 result = float3(0);
    for (int band = 0; band < bands; ++band) {
        // Distinct integer rotations preserve the 256-block repeat without stacking grids.
        float2 axisU = axes[band];
        float2 axisV = float2(-axisU.y,axisU.x);
        float axisLength = length(axisU);
        float weight = 1.0-smoothstep(0.15,0.45,footprint*frequency*axisLength);
        // Do not evaluate noise for a packet whose existing antialias filter is exactly zero.
        if (weight == 0.0) {
            frequency *= 2.0;
            continue;
        }
        float warpAmount = 1.0 - 0.08 * float(band);
        float3 velocity = mc_water_pattern_velocity(horizontal ? float3(0,1,0) : float3(1,0,0), band);
        float2 packet = surface + current.offset * warpAmount
            - (horizontal ? velocity.xz : velocity.zy) * time;
        float2 uv = float2(dot(packet,axisU),dot(packet,axisV)) * frequency;
        float2 sample = uv + float2(7,13)*float(band);
        // Small ripples need independently jittered circular centers. A four-corner
        // lattice remains legible as tiny squares even with radial interpolation.
        float3 noise = band < 2 ? mc_water_height_noise_gradient(sample)
            : mc_water_geometric_clumps_gradient(sample);
        float centered = 2.0*noise.x-1.0;
        float rounded = sqrt(centered*centered+0.04);
        float height = 1.0-rounded;
        float2 gradient = (-2.0*centered/rounded) * noise.yz;
        float amplitude = slopes[band] * weight / (frequency*axisLength);
        float2 worldGradient = mc_water_warped_gradient(
            (axisU*gradient.x + axisV*gradient.y)*frequency, current, warpAmount);
        result.x += amplitude*envelope*height;
        result.yz += amplitude*(envelope*worldGradient + height*envelopeGradient);
        frequency *= 2.0;
    }
    return result;
}

static inline float3 mc_water_detail_height_gradient(
    float2 surface, float footprint, int quality, float time = 0.0, bool horizontal = true
) {
    McWaterCurrentWarp current = horizontal ? mc_water_current_warp(surface, time)
        : McWaterCurrentWarp{float2(0), float2(0), float2(0)};
    return mc_water_detail_height_gradient(surface, footprint, quality, time, horizontal,
        current);
}

// Coarser world-anchored waves replace unresolved close detail at distance.
// Four-cell noise at 1/64 frequency retains the existing 256-block spatial repeat.
static inline float2 mc_water_distant_slope(
    float2 surface, float time, float footprint, McWaterCurrentWarp current
) {
    float2 slope = float2(0);
    float frequency = 1.0 / 64.0;
    const float2 axes[3] = {float2(1,2), float2(2,-1), float2(2,3)};
    for (int band = 0; band < 3; ++band) {
        float2 axisU = axes[band];
        float2 axisV = float2(-axisU.y,axisU.x);
        float axisLength = length(axisU);
        float weight = 1.0-smoothstep(0.15,0.45,footprint*frequency*axisLength);
        if (weight == 0.0) {
            frequency *= 2.0;
            continue;
        }
        float warpAmount = 1.0 - 0.08 * float(band);
        float2 packet = surface + current.offset * warpAmount
            - mc_water_pattern_velocity(float3(0,1,0),band).xz * time;
        float2 uv = float2(dot(packet,axisU),dot(packet,axisV)) * frequency;
        float3 noise = mc_water_height_noise_gradient(uv + float2(7,13)*float(band),4);
        float centered = 2.0*noise.x-1.0;
        float2 gradient = (-2.0*centered/sqrt(centered*centered+0.04)) * noise.yz;
        slope += mc_water_warped_gradient(
            (axisU*gradient.x + axisV*gradient.y) * (0.16*weight/axisLength),
            current, warpAmount);
        frequency *= 2.0;
    }
    return slope;
}

// Tiered multiscale height gradients replace the old phase-modulated sine bands.
// Higher tiers add shorter irregular crests with independent crossing transport.
static inline float3 mc_water_detailed_normal(
    float3 baseNormal, float3 flow, float3 position, float seconds, float strength,
    int quality, float3 pixelDx, float3 pixelDy, float cameraDistance = 0.0, float detailChunks = 16.0
) {
    float3 broad = mc_water_animated_normal(baseNormal, flow, position, seconds, strength);
    if (quality <= 0 || !isfinite(strength) || strength <= 0.0) return broad;
    float3 normal = mc_water_safe_normalize(baseNormal, float3(0, 1, 0));
    float3 axis = abs(normal.y) < 0.9 ? float3(0, 1, 0) : float3(1, 0, 0);
    float3 tangent = mc_water_safe_normalize(cross(axis, normal), float3(0, 0, 1));
    position = select(float3(0), position, isfinite(position));
    position = fract(position / 256.0) * 256.0;
    float time = isfinite(seconds) ? seconds - floor(seconds / 1024.0) * 1024.0 : 0.0;
    float2 surface = abs(normal.y) >= 0.5 ? position.xz
        : (abs(normal.x) > abs(normal.z) ? position.zy : position.xy);
    float footprint = max(length(pixelDx), length(pixelDy));
    float range = clamp(detailChunks,2.0,32.0) * 16.0;
    float detailWeight = 1.0-smoothstep(range*0.75,range,max(0.0,cameraDistance));
    if (detailWeight <= 0.0) return broad;
    bool horizontal = abs(normal.y) >= 0.5;
    McWaterCurrentWarp current = horizontal ? mc_water_current_warp(surface, time)
        : McWaterCurrentWarp{float2(0), float2(0), float2(0)};
    float3 height = mc_water_detail_height_gradient(
        surface, footprint, quality, time, horizontal, current);
    if (abs(normal.y) >= 0.5 && cameraDistance > 16.0 && detailWeight > 0.0) {
        height.yz += mc_water_distant_slope(surface,time,footprint,current)
            * smoothstep(16.0,64.0,cameraDistance);
    }
    height *= detailWeight;
    float3 slope = abs(normal.y) >= 0.5 ? float3(height.y,0,height.z)
        : (abs(normal.x) > abs(normal.z) ? float3(0,height.z,height.y) : float3(height.y,height.z,0));
    slope -= normal * dot(slope,normal);
    return mc_water_safe_normalize(broad - slope * clamp(strength, 0.0, 2.0), broad);
}

/// Unpolarized air-to-water Fresnel. Surface roughness changes the distribution of
/// reflected directions, not the reflectance of each air/water interface.
static inline float mc_water_fresnel(float nDotV) {
    float cosine = saturate(isfinite(nDotV) ? nDotV : 0.0);
    const float ior = 1.333;
    float transmittedCosine = sqrt(max(0.0, 1.0 - (1.0 - cosine * cosine) / (ior * ior)));
    float perpendicular = (cosine - ior * transmittedCosine)
        / (cosine + ior * transmittedCosine);
    float parallel = (ior * cosine - transmittedCosine)
        / (ior * cosine + transmittedCosine);
    return 0.5 * (perpendicular * perpendicular + parallel * parallel);
}

// Approximate the existing sky's horizon/zenith gradient in reflection direction.
// This is a directional fallback, not a captured sky/cloud probe. Environment and
// cave/daylight availability remain owned by the caller's existing gates.
static inline float3 mc_water_environment_radiance(float3 skyColor, float3 normal, float3 view) {
    float3 ray = reflect(-view, normal);
    float elevation = saturate(ray.y);
    float horizonWeight = pow(1.0 - elevation, 3.0);
    float luminance = dot(skyColor, float3(0.2126, 0.7152, 0.0722));
    float3 zenith = skyColor * float3(0.55, 0.70, 0.90);
    float3 horizon = mix(skyColor, float3(luminance), 0.25) * 1.1;
    float aboveGround = mix(0.25, 1.0, smoothstep(-0.25, 0.0, ray.y));
    return mix(zenith, horizon, horizonWeight) * aboveGround;
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
    float fresnel = mc_water_fresnel(dot(normal, view));
    float3 reflected = mix(base, mc_water_environment_radiance(envColor, normal, view), fresnel * envAvailability);

    float sunEnergy = clamp(isfinite(sunDirectionEnergy.w) ? sunDirectionEnergy.w : 0.0, 0.0, 8.0);
    float3 sunRaw = select(float3(0.0), sunDirectionEnergy.xyz, isfinite(sunDirectionEnergy.xyz));
    float sunMagnitudeSquared = dot(sunRaw, sunRaw);
    if (sunEnergy == 0.0 || visibility == 0.0 || sunMagnitudeSquared <= 1.0e-12) return reflected;
    float3 sun = sunRaw * rsqrt(sunMagnitudeSquared);
    float nDotL = saturate(dot(normal, sun));
    float3 halfVector = mc_water_safe_normalize(sun + view, normal);
    // GGX microfacets with Smith masking make the glint depend on the actual
    // sun/view/wave geometry. The minimum width approximates the sun's finite disc
    // and keeps the directional-light peak bounded at very low roughness.
    float alpha = max(boundedRoughness * boundedRoughness, 0.04);
    float alphaSquared = alpha * alpha;
    float nDotH = saturate(dot(normal, halfVector));
    float nDotV = saturate(dot(normal, view));
    if (nDotL <= 0.0 || nDotV <= 0.0) return reflected;
    float distributionBase = nDotH * nDotH * (alphaSquared - 1.0) + 1.0;
    float distribution = alphaSquared / (M_PI_F * distributionBase * distributionBase);
    float viewMask = nDotL * sqrt(alphaSquared + (1.0 - alphaSquared) * nDotV * nDotV);
    float lightMask = nDotV * sqrt(alphaSquared + (1.0 - alphaSquared) * nDotL * nDotL);
    float geometry = 2.0 * nDotV * nDotL / max(viewMask + lightMask, 1.0e-6);
    float sunFresnel = mc_water_fresnel(dot(view, halfVector));
    return reflected + float3(sunEnergy * visibility * distribution * geometry * sunFresnel
        * nDotL / max(4.0 * nDotV, 1.0e-6));
}

/// Composes a screen-space hit as a replacement for the environment radiance. A miss takes the
/// original path exactly, which keeps baseline and disabled tiers independent of SSR resources.
static inline float3 mc_water_reflection_with_ssr(
    float3 baseColor,
    float3 normalWorld,
    float3 viewToCameraWorld,
    float roughness,
    float skyLight,
    float4 sunDirectionEnergy,
    float4 environment,
    McWaterSsrHit ssr
) {
    float confidence = saturate(isfinite(ssr.confidence) ? ssr.confidence : 0.0);
    if (confidence == 0.0) {
        return mc_water_reflection(baseColor, normalWorld, viewToCameraWorld, roughness,
            skyLight, sunDirectionEnergy, environment);
    }

    float3 hitColor = max(select(float3(0.0), ssr.color, isfinite(ssr.color)), float3(0.0));
    float visibility = saturate(isfinite(skyLight) ? skyLight : 0.0);
    visibility *= visibility;
    float fallbackAvailability = saturate(isfinite(environment.w) ? environment.w : 0.0) * visibility;
    float3 fallbackColor = max(select(float3(0.0), environment.rgb, isfinite(environment.rgb)), float3(0.0));
    float3 safeBase = select(float3(0.0), baseColor, isfinite(baseColor));
    float3 normal = mc_water_safe_normalize(normalWorld, float3(0.0, 1.0, 0.0));
    float3 view = mc_water_safe_normalize(viewToCameraWorld, normal);
    if (dot(normal, view) < 0.0) normal = -normal;
    float fresnel = mc_water_fresnel(dot(normal, view));
    float3 fallbackSurface = mix(safeBase, mc_water_environment_radiance(fallbackColor, normal, view), fresnel * fallbackAvailability);
    float3 hitSurface = mix(safeBase, hitColor, fresnel);
    float3 baseline = mc_water_reflection(baseColor, normalWorld, viewToCameraWorld, roughness,
        skyLight, sunDirectionEnergy, environment);
    // Only replace the environment share. The original sun term and its cave visibility remain.
    return baseline + confidence * (hitSurface - fallbackSurface);
}

// Keep the physical helper independently testable; quality zero disables both sky and sun.
static inline float3 mc_water_configured_reflection(
    float3 baseColor, float3 normalWorld, float3 viewToCameraWorld, float roughness,
    float skyLight, float4 sunDirectionEnergy, float4 environment
) {
#if MC_OPTION_WATER_REFLECTION_QUALITY == 0
    return baseColor;
#else
    return mc_water_reflection(baseColor, normalWorld, viewToCameraWorld, roughness,
        skyLight, sunDirectionEnergy, environment);
#endif
}


static inline float3 mc_water_ssr_view_from_device_depth(
    float2 pixel, float deviceDepth, float2 extent, float4x4 inverseProjection
) {
    // Terrain's clip-Y flip and Metal's top-left viewport cancel one another.
    float2 ndc = pixel / max(extent, float2(1.0)) * 2.0 - 1.0;
    float4 viewH = inverseProjection * float4(ndc, deviceDepth, 1.0);
    if (!all(isfinite(viewH)) || abs(viewH.w) <= 1.0e-7) return float3(NAN);
    return viewH.xyz / viewH.w;
}

static inline bool mc_water_ssr_project(
    float3 viewPosition, float4x4 projection, float2 extent,
    thread float2 &pixel, thread float &deviceDepth
) {
    if (!all(isfinite(viewPosition))) return false;
    float4 clip = projection * float4(viewPosition, 1.0);
    if (!all(isfinite(clip)) || clip.w <= 1.0e-7) return false;
    float3 ndc = clip.xyz / clip.w;
    pixel = (ndc.xy * 0.5 + 0.5) * extent;
    deviceDepth = ndc.z;
    return isfinite(deviceDepth) && deviceDepth >= 0.0 && deviceDepth <= 1.0
        && all(pixel >= float2(0.5)) && all(pixel <= extent - 0.5);
}

/// Bounded, single-frame screen-space reflection against the pre-water opaque snapshots.
/// The projection is Minecraft's pre-Metal-Y-flip matrix; visible view-space positions have z < 0.
static inline McWaterSsrHit mc_water_screen_space_reflection(
    float3 surfaceView,
    float3 normalView,
    float4x4 projection,
    float4x4 inverseProjection,
    texture2d<float> opaqueColor,
    depth2d<float> opaqueDepth
) {
    McWaterSsrHit miss = {float3(0.0), 0.0, float3(0.0), 0.0};
#if MC_OPTION_WATER_REFLECTION_QUALITY <= 1
    return miss;
#else
    uint width = opaqueDepth.get_width();
    uint height = opaqueDepth.get_height();
    if (width == 0u || height == 0u || opaqueColor.get_width() != width
        || opaqueColor.get_height() != height || !all(isfinite(surfaceView))
        || !all(isfinite(normalView)) || surfaceView.z >= -1.0e-4) return miss;

#if MC_OPTION_WATER_REFLECTION_QUALITY == 2
    constexpr uint stepCount = 12u;
    constexpr uint refinementCount = 3u;
    constexpr float maxDistance = 24.0;
    constexpr float hitThickness = 0.35;
#else
    constexpr uint stepCount = 24u;
    constexpr uint refinementCount = 5u;
    constexpr float maxDistance = 48.0;
    constexpr float hitThickness = 0.18;
#endif
    float2 extent = float2(float(width), float(height));
    float3 viewToCamera = mc_water_safe_normalize(-surfaceView, float3(0.0, 0.0, 1.0));
    float3 normal = mc_water_safe_normalize(normalView, viewToCamera);
    if (dot(normal, viewToCamera) < 0.0) normal = -normal;
    float3 rayDirection = mc_water_safe_normalize(reflect(-viewToCamera, normal), float3(0.0));
    if (!all(isfinite(rayDirection)) || dot(rayDirection, rayDirection) <= 1.0e-8) return miss;

    constexpr float startDistance = 0.12;
    float previousDistance = startDistance;
    float previousDelta = -INFINITY;
    // Seed the sign bracket at the biased origin. If opaque geometry is already in front of the
    // ray, it is an occluder rather than a reflection hit and must not be accepted on step one.
    float3 startPosition = surfaceView + rayDirection * startDistance;
    float2 startPixel;
    float startDeviceDepth;
    if (!mc_water_ssr_project(startPosition, projection, extent, startPixel, startDeviceDepth)) return miss;
    uint2 startCoord = uint2(startPixel);
    float startSceneDepth = opaqueDepth.read(startCoord);
    if (isfinite(startSceneDepth) && startSceneDepth > 1.0e-7) {
        float3 startSceneView = mc_water_ssr_view_from_device_depth(
            float2(startCoord) + 0.5, startSceneDepth, extent, inverseProjection);
        if (!all(isfinite(startSceneView))) return miss;
        previousDelta = startSceneView.z - startPosition.z;
    }
    for (uint step = 1u; step <= stepCount; ++step) {
        float distanceAlongRay = startDistance
            + (maxDistance - startDistance) * float(step) / float(stepCount);
        float3 rayPosition = surfaceView + rayDirection * distanceAlongRay;
        float2 pixel;
        float rayDeviceDepth;
        if (!mc_water_ssr_project(rayPosition, projection, extent, pixel, rayDeviceDepth)) return miss;
        uint2 coord = uint2(pixel);
        float sceneDeviceDepth = opaqueDepth.read(coord);
        if (!isfinite(sceneDeviceDepth) || sceneDeviceDepth <= 1.0e-7) {
            previousDistance = distanceAlongRay;
            previousDelta = -INFINITY;
            continue;
        }
        float3 sceneView = mc_water_ssr_view_from_device_depth(
            float2(coord) + 0.5, sceneDeviceDepth, extent, inverseProjection);
        if (!all(isfinite(sceneView))) return miss;
        float delta = sceneView.z - rayPosition.z;
        if (delta >= 0.0 && previousDelta < 0.0) {
            float low = previousDistance;
            float high = distanceAlongRay;
            float2 hitPixel = pixel;
            float3 hitView = sceneView;
            float hitDelta = delta;
            for (uint refine = 0u; refine < refinementCount; ++refine) {
                float midpoint = (low + high) * 0.5;
                float3 midpointRay = surfaceView + rayDirection * midpoint;
                float midpointDepth;
                float2 midpointPixel;
                if (!mc_water_ssr_project(midpointRay, projection, extent, midpointPixel, midpointDepth)) return miss;
                uint2 midpointCoord = uint2(midpointPixel);
                float sampledDepth = opaqueDepth.read(midpointCoord);
                if (!isfinite(sampledDepth) || sampledDepth <= 1.0e-7) {
                    low = midpoint;
                    continue;
                }
                float3 midpointView = mc_water_ssr_view_from_device_depth(
                    float2(midpointCoord) + 0.5, sampledDepth, extent, inverseProjection);
                if (!all(isfinite(midpointView))) return miss;
                float midpointDelta = midpointView.z - midpointRay.z;
                if (midpointDelta >= 0.0) {
                    high = midpoint;
                    hitPixel = midpointPixel;
                    hitView = midpointView;
                    hitDelta = midpointDelta;
                } else {
                    low = midpoint;
                }
            }
            // Reject uncertain crossings rather than marching through an occluder.
            // Z alone underestimates separation for nearly screen-parallel rays.
            float rayPenetration = hitDelta / max(abs(rayDirection.z), 1.0e-4);
            if (abs(rayDirection.z) < 0.01 || rayPenetration > hitThickness) return miss;
            uint2 hitCoord = uint2(hitPixel);
            float2 uv = (float2(hitCoord) + 0.5) / extent;
            float edge = min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y));
            float edgeConfidence = smoothstep(0.0, 0.08, edge);
            float distanceConfidence = 1.0 - smoothstep(0.60, 1.0, high / maxDistance);
            float thicknessConfidence = 1.0 - smoothstep(0.0, hitThickness, rayPenetration);
            float confidence = saturate(edgeConfidence * distanceConfidence * thicknessConfidence);
            if (confidence <= 0.0) return miss;
            return McWaterSsrHit{
                max(opaqueColor.read(hitCoord).rgb, float3(0.0)), confidence, hitView, 0.0
            };
        }
        previousDistance = distanceAlongRay;
        previousDelta = delta;
    }
    return miss;
#endif
}

static inline float3 mc_water_configured_reflection_with_ssr(
    float3 baseColor, float3 normalWorld, float3 viewToCameraWorld, float roughness,
    float skyLight, float4 sunDirectionEnergy, float4 environment, McWaterSsrHit ssr
) {
#if MC_OPTION_WATER_REFLECTION_QUALITY == 0
    return baseColor;
#else
    return mc_water_reflection_with_ssr(baseColor, normalWorld, viewToCameraWorld, roughness,
        skyLight, sunDirectionEnergy, environment, ssr);
#endif
}

/// Returns view-space water path length, or -1 when the opaque sample is not behind the surface.
/// Device depth zero is reverse-Z's clear/sky value and deliberately has no invented thickness.
static inline float mc_water_thickness(
    float3 surfaceView, float3 backgroundView, float backgroundDeviceDepth
) {
    if (!all(isfinite(surfaceView)) || !all(isfinite(backgroundView))
        || !isfinite(backgroundDeviceDepth) || backgroundDeviceDepth <= 1.0e-7
        || surfaceView.z >= -1.0e-4 || backgroundView.z > surfaceView.z + 1.0e-4) return -1.0;
    float thickness = length(backgroundView - surfaceView);
    return clamp(isfinite(thickness) ? thickness : -1.0, 0.0, MC_WATER_MAX_THICKNESS);
}

/// Bounded screen-space distortion. The animated slope moves the background while the
/// underlying face normal contributes only a small static bend. Strength zero is an identity.
static inline float2 mc_water_refraction_offset_pixels(
    float3 normalView, float3 baseNormalView, float thickness, float strength
) {
    if (!all(isfinite(normalView)) || !all(isfinite(baseNormalView))
        || !isfinite(thickness) || thickness < 0.0) return float2(0.0);
    float boundedStrength = saturate(isfinite(strength) ? strength : 0.0);
    float pathWeight = saturate(thickness / 1.5);
    float2 waveSlope = clamp(normalView.xy - baseNormalView.xy, float2(-1.0), float2(1.0));
    float2 staticBend = clamp(normalView.xy, float2(-1.0), float2(1.0)) * 0.25;
    float2 offset = (waveSlope * 3.5 + staticBend)
        * (MC_WATER_MAX_REFRACTION_PIXELS * boundedStrength * pathWeight);
    return clamp(offset, float2(-MC_WATER_MAX_REFRACTION_PIXELS),
        float2(MC_WATER_MAX_REFRACTION_PIXELS));
}

static inline bool mc_water_sample_in_bounds(float2 pixel, float2 extent) {
    return all(isfinite(pixel)) && all(isfinite(extent)) && all(extent >= float2(1.0))
        && all(pixel >= float2(0.5)) && all(pixel <= extent - 0.5);
}

/// Beer-Lambert RGB absorption with a restrained biome-colored in-scatter floor.
static inline float3 mc_water_absorb(float3 scene, float3 biomeTint, float thickness) {
    float boundedThickness = clamp(isfinite(thickness) ? thickness : 0.0, 0.0, MC_WATER_MAX_THICKNESS);
    float3 safeScene = max(select(float3(0.0), scene, isfinite(scene)), float3(0.0));
    float3 safeTint = saturate(select(float3(0.0), biomeTint, isfinite(biomeTint)));
    float3 transmittance = exp(-float3(0.18, 0.065, 0.025) * boundedThickness);
    // A full block of water transmits about ten percent less of the scene. Fade that
    // added veil in with path length so zero thickness remains an exact identity.
    transmittance *= 1.0 - 0.1 * saturate(boundedThickness);
    float3 scattering = safeTint * 0.18;
    return safeScene * transmittance + scattering * (1.0 - transmittance);
}

/// Restrained screen-space contact foam from the undistorted water-to-opaque distance.
/// This is deliberately limited to upward source faces; strength zero is an exact identity.
static inline float mc_water_contact_foam(
    float thickness,
    float3 faceNormalWorld,
    float3 periodicWorldPosition,
    float animationSeconds,
    float strength
) {
    float boundedStrength = saturate(isfinite(strength) ? strength : 0.0);
    if (boundedStrength == 0.0 || !isfinite(thickness) || thickness < 0.0
        || thickness >= MC_WATER_FOAM_MAX_CONTACT_DISTANCE
        || !all(isfinite(faceNormalWorld))) return 0.0;

    float3 faceNormal = mc_water_safe_normalize(faceNormalWorld, float3(0.0));
    float upward = smoothstep(0.72, 0.92, faceNormal.y);
    if (upward == 0.0) return 0.0;

    float3 position = select(float3(0.0), periodicWorldPosition, isfinite(periodicWorldPosition));
    float boundedTime = isfinite(animationSeconds)
        ? animationSeconds - floor(animationSeconds / MC_WATER_TIME_PERIOD) * MC_WATER_TIME_PERIOD
        : 0.0;
    float phaseA = MC_WATER_TAU * (dot(position, float3(19.0, 0.0, 23.0)) / MC_WATER_SPATIAL_PERIOD
        - 317.0 * boundedTime / MC_WATER_TIME_PERIOD);
    float phaseB = MC_WATER_TAU * (dot(position, float3(-31.0, 0.0, 13.0)) / MC_WATER_SPATIAL_PERIOD
        + 229.0 * boundedTime / MC_WATER_TIME_PERIOD);
    float noise = saturate(0.58 + 0.24 * sin(phaseA) + 0.18 * sin(phaseB));
    float proximity = 1.0 - smoothstep(0.12, MC_WATER_FOAM_MAX_CONTACT_DISTANCE, thickness);
    // Keep a faint continuous contact trace so a valid one-block shore cannot disappear at a
    // low-noise animation phase; the moving crest remains the dominant part of the pattern.
    float pattern = mix(0.25, 1.0, smoothstep(0.30, 0.82, noise));
    return saturate(boundedStrength * upward * proximity * pattern);
}

#endif
