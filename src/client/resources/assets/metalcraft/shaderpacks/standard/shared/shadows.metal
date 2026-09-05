#ifndef MC_SHADOWS_METAL
#define MC_SHADOWS_METAL
// Named bindings: shadow_frame -> MC_BUFFER_SHADOW_FRAME, shadow_map -> MC_TEX_SHADOW_MAP.
// The caller assigns slots; this contract does not reserve slots in vanilla geometry PSOs.
struct MCShadowFrame {
    float4x4 cameraRelativeToShadow[4];
    float4x4 inverseProjection;
    float4x4 viewToCameraRelative;
    float4 cascadeFar;
    float4 directionToSun;
    uint cascadeCount;
    float inverseResolution;
    float shadowDistance;
    float casterExtension;
};
// UV uses Metal's top-left texture origin; hardware depth is already in [0,1].
float3 mc_shadow_camera_relative(float2 uv, float depth, constant MCShadowFrame& frame) {
    float4 view = frame.inverseProjection * float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, depth, 1.0);
    return (frame.viewToCameraRelative * float4(view.xyz / view.w, 1.0)).xyz;
}

// Returns cascadeCount when the receiver is outside the covered camera depth range.
// Equality belongs to the nearer cascade; unused physical array layers are never selected.
// Overlapping blend bands are not applied: a split pixel uses only the nearer cascade.
uint mc_shadow_cascade(float viewDepth, constant MCShadowFrame& frame) {
    if (!isfinite(viewDepth) || viewDepth <= 0.0 || viewDepth > frame.shadowDistance) {
        return frame.cascadeCount;
    }
    for (uint i = 0; i < min(frame.cascadeCount, 4u); ++i) {
        if (viewDepth <= frame.cascadeFar[i]) return i;
    }
    return frame.cascadeCount;
}

// Visibility only: lighting decides how much sunlight to apply. The receiver position is
// camera-relative world space, and viewDepth is positive forward camera depth (not radial
// distance or hardware depth). Bias is in normalized shadow depth and moves toward the sun.
// Manual comparisons use the module's nearest sampler, avoiding a new comparison-sampler ABI.
float mc_shadow_visibility(float3 cameraRelative, float viewDepth, float depthBias,
    constant MCShadowFrame& frame, depth2d_array<float> map, sampler nearestSampler, float3 worldNormal) {
    uint cascade = mc_shadow_cascade(viewDepth, frame);
    if (cascade >= min(frame.cascadeCount, 4u) || cascade >= map.get_array_size()
        || !all(isfinite(cameraRelative))) return 1.0;
    float4 clip = frame.cameraRelativeToShadow[cascade] * float4(cameraRelative, 1.0);
    if (!all(isfinite(clip)) || clip.w <= 0.0) return 1.0;
    float3 ndc = clip.xyz / clip.w;
    if (any(abs(ndc.xy) > 1.0) || ndc.z < 0.0 || ndc.z > 1.0) return 1.0;
    // shadow_terrain_vertex flips clip Y; Metal's viewport flip cancels it.
    float2 uv = ndc.xy * 0.5 + 0.5;
    float4x4 matrix = frame.cameraRelativeToShadow[cascade];
    float3 rowX = float3(matrix[0].x, matrix[1].x, matrix[2].x);
    float3 rowY = float3(matrix[0].y, matrix[1].y, matrix[2].y);
    float3 rowZ = float3(matrix[0].z, matrix[1].z, matrix[2].z);
    // Inverse-transpose the receiver plane into this orthographic light volume.
    float3 plane = float3(dot(rowX, worldNormal) / max(dot(rowX, rowX), 1e-12),
                         dot(rowY, worldNormal) / max(dot(rowY, rowY), 1e-12),
                         dot(rowZ, worldNormal) / max(dot(rowZ, rowZ), 1e-12));
    // Quantized normals nearly perpendicular to the light cannot define a stable
    // depth plane; retain the bounded bias path at those grazing angles.
    bool stablePlane = abs(dot(worldNormal, frame.directionToSun.xyz)) > 0.08;
    float2 depthGradient = stablePlane && abs(plane.z) > 1e-5 ? -2.0 * plane.xy / plane.z : float2(0.0);
    float receiverDepth = ndc.z - max(depthBias, 0.0);
    // Bilinearly interpolate depth comparisons, not stored depth. Nine nearest
    // comparisons jump by 1/9 when the receiver crosses a texel boundary. The
    // overlapping bilinear 3x3 kernels combine into sixteen weighted comparisons.
    float resolution = float(map.get_width());
    float2 texelPosition = uv * resolution - 0.5;
    float2 base = floor(texelPosition);
    float2 fraction = texelPosition - base;
    float4 weightsX = float4(1.0 - fraction.x, 1.0, 1.0, fraction.x);
    float4 weightsY = float4(1.0 - fraction.y, 1.0, 1.0, fraction.y);
    float visibility = 0.0;
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            float weight = weightsX[x] * weightsY[y];
            float2 center = (base + float2(x - 1, y - 1) + 0.5) / resolution;
            // Treat taps beyond the light volume as unoccluded, not clamped edge casters.
            if (any(center < 0.0) || any(center >= 1.0)) visibility += weight;
            else {
                // Compare at the sampled texel center, not the center receiver's depth.
                // Otherwise neighboring samples on a sloped plane shadow the plane itself.
                float tapDepth = receiverDepth + dot(depthGradient, center - uv);
                visibility += weight * (tapDepth <= map.sample(nearestSampler, center, cascade) ? 1.0 : 0.0);
            }
        }
    }
    return visibility / 9.0;
}

// Callers without a receiver normal retain the raw visibility/bias contract.
float mc_shadow_visibility(float3 cameraRelative, float viewDepth, float depthBias,
    constant MCShadowFrame& frame, depth2d_array<float> map, sampler nearestSampler) {
    return mc_shadow_visibility(cameraRelative, viewDepth, depthBias, frame, map, nearestSampler, float3(0.0));
}

// World-space depth range of one cascade, from the light-space Z scale.
float mc_shadow_cascade_depth_range(uint cascade, constant MCShadowFrame& frame) {
    float4x4 m = frame.cameraRelativeToShadow[cascade];
    return 1.0 / max(length(float3(m[0].z, m[1].z, m[2].z)), 1e-8);
}

float mc_shadow_cascade_texel_size(uint cascade, constant MCShadowFrame& frame) {
    float4x4 m = frame.cameraRelativeToShadow[cascade];
    float invExtent = length(float3(m[0].x, m[1].x, m[2].x));
    return 2.0 * frame.inverseResolution / max(invExtent, 1e-8);
}

// Normalized shadow-depth bias from world/texel units. Slope raises the offset for
// grazing receivers; the cap avoids pushing a contact receiver through the caster.
float mc_shadow_receiver_bias(float3 worldNormal, float viewDepth, constant MCShadowFrame& frame) {
    uint cascade = mc_shadow_cascade(viewDepth, frame);
    if (cascade >= min(frame.cascadeCount, 4u)) return 0.0;
    float depthRange = mc_shadow_cascade_depth_range(cascade, frame);
    float texel = mc_shadow_cascade_texel_size(cascade, frame);
    float3 sun = frame.directionToSun.xyz;
    float nDotL = 0.0;
    if (all(isfinite(worldNormal)) && all(isfinite(sun)) && dot(sun, sun) > 1e-8) {
        nDotL = saturate(dot(normalize(worldNormal), normalize(sun)));
    }
    float slope = sqrt(max(0.0, 1.0 - nDotL * nDotL)) / max(nDotL, 0.08);
    float worldBias = min(0.05 + min(texel, 0.1) * (0.5 + slope), 0.12);
    return saturate(worldBias / depthRange);
}

// 1 inside the shadow volume, 0 at/beyond shadowDistance. Applied to visibility, not lighting.
float mc_shadow_distance_fade(float viewDepth, constant MCShadowFrame& frame) {
    if (frame.cascadeCount == 0u || frame.shadowDistance <= 0.0 || !isfinite(viewDepth)) return 0.0;
    float start = frame.shadowDistance * 0.9;
    if (viewDepth <= start) return 1.0;
    if (viewDepth >= frame.shadowDistance) return 0.0;
    return 1.0 - (viewDepth - start) / max(frame.shadowDistance - start, 1e-5);
}

// Inverse of mc_write_gbuffer's 24-bit packing: linear positive view depth over [0, 1024].
uint mc_unpack_view_depth_bits(float4 normal, float4 light) {
    uint hi = uint(round(saturate(normal.a) * 255.0));
    uint mid = uint(round(saturate(light.b) * 255.0));
    uint lo = uint(round(saturate(light.a) * 255.0));
    return (hi << 16) | (mid << 8) | lo;
}

float mc_unpack_view_depth(float4 normal, float4 light) {
    return float(mc_unpack_view_depth_bits(normal, light)) * (1024.0 / 16777215.0);
}

// Geometry negates clip Y before Metal viewport conversion. Inverting that pair
// means original raster NDC Y is 2*uv.y-1, not 1-2*uv.y.
// Intersect the unprojected pixel line with z = -viewDepth. Minecraft folds walking
// translation into the projection, so this line need not pass through the view origin.
// Scaling a single unprojected point amplifies that translation with receiver distance.
// Use two finite clip depths; the far endpoint can be at infinity.
// Do not pass linear depth to mc_shadow_camera_relative, which expects hardware [0,1] depth.
float3 mc_reconstruct_view_position(float2 uv, float viewDepth, float4x4 inverseProjection) {
    float2 ndc = uv * 2.0 - 1.0;
    float4 viewH = inverseProjection * float4(ndc, 0.0, 1.0);
    float4 middleH = viewH + 0.5 * inverseProjection[2];
    float3 origin = viewH.xyz / viewH.w;
    float3 direction = middleH.xyz / middleH.w - origin;
    return origin + direction * ((-viewDepth - origin.z) / direction.z);
}

float3 mc_view_to_camera_relative(float3 viewPos, float4x4 viewToCameraRelative) {
    return (viewToCameraRelative * float4(viewPos, 1.0)).xyz;
}

struct MCResolveCamera {
    float4x4 inverseProjection;
    float4x4 viewToCameraRelative;
    float2 screenSize;
};
#endif
