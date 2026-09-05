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
    constant MCShadowFrame& frame, depth2d_array<float> map, sampler nearestSampler) {
    uint cascade = mc_shadow_cascade(viewDepth, frame);
    if (cascade >= min(frame.cascadeCount, 4u) || cascade >= map.get_array_size()
        || !all(isfinite(cameraRelative))) return 1.0;
    float4 clip = frame.cameraRelativeToShadow[cascade] * float4(cameraRelative, 1.0);
    if (!all(isfinite(clip)) || clip.w <= 0.0) return 1.0;
    float3 ndc = clip.xyz / clip.w;
    if (any(abs(ndc.xy) > 1.0) || ndc.z < 0.0 || ndc.z > 1.0) return 1.0;
    float2 uv = float2(ndc.x * 0.5 + 0.5, 0.5 - ndc.y * 0.5);
    float receiverDepth = ndc.z - max(depthBias, 0.0);
    float visibility = 0.0;
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            float2 tap = uv + float2(x, y) * frame.inverseResolution;
            // Treat taps beyond the light volume as unoccluded, not clamped edge casters.
            if (any(tap < 0.0) || any(tap >= 1.0)) visibility += 1.0;
            else visibility += receiverDepth <= map.sample(nearestSampler, tap, cascade) ? 1.0 : 0.0;
        }
    }
    return visibility / 9.0;
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

// Raster ProjMat is OpenGL clip (Y-up). Geometry flips Y for Metal; screen UV is top-left.
// Scale the unprojected ray so -view.z equals the packed linear view depth. Do not pass that
// depth to mc_shadow_camera_relative, which expects hardware [0,1] depth.
float3 mc_reconstruct_view_position(float2 uv, float viewDepth, float4x4 inverseProjection) {
    float2 ndc = float2(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0);
    float4 viewH = inverseProjection * float4(ndc, 0.0, 1.0);
    float w = abs(viewH.w) < 1e-8 ? 1e-8 : viewH.w;
    float3 ray = viewH.xyz / w;
    return ray * (viewDepth / max(-ray.z, 1e-8));
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
