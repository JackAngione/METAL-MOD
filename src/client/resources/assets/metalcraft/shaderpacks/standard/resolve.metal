// Deferred compose, last draw of the opaque G-buffer encoder. Colour inputs are framebuffer
// fetches: they stay in Apple tile memory and are never sampled or stored.

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

vertex ResolveVaryings resolve_vertex(uint vertexId [[vertex_id]]) {
    const float2 corners[3] = {float2(-1.0, -1.0), float2(3.0, -1.0), float2(-1.0, 3.0)};
    float2 position = corners[vertexId % 3];
    return {float4(position, 0.0, 1.0), position * 0.5 + 0.5};
}

// Project sunlight through the same world-space density field used by the sky pass.
// One filtered sample keeps the merged tile resolve inexpensive.
static inline float mc_cloud_sun_visibility(float3 receiver, float3 sun,
    float4 cloudOrigin, float4 cloudSettings) {
    if (cloudSettings.x < 0.5 || cloudSettings.y <= 0.0 || sun.y <= 0.08
        || !isfinite(cloudOrigin.w)) return 1.0;
    float cloudY = cloudOrigin.w + 12.0 + 96.0 * 0.34;
    float distance = (cloudY - (cloudOrigin.y + receiver.y)) / sun.y;
    if (distance <= 0.0 || distance > 6000.0) return 1.0;
    float2 p = (cloudOrigin.xz + receiver.xz + sun.xz * distance) / 128.0;
    float footprint = max(length(dfdx(p)), length(dfdy(p)));
    float shape = mc_cumulus_shape(float3(p.x, 0.34 * 1.25, p.y), footprint);
    float density = mc_cumulus_density(shape, mc_cumulus_coverage(p, cloudSettings.z), 0.34);
    // Preserve clear gaps while making the denser cloud cores visibly shade terrain.
    float opacity = smoothstep(0.10, 0.38, density) * cloudSettings.y;
    return 1.0 - opacity * 0.95 * smoothstep(0.08, 0.25, sun.y);
}

fragment ResolveTargets resolve_fragment(
    ResolveVaryings in [[stage_in]],
    ResolveTargets previous,
    constant PackOptions &options [[buffer(0)]],
    constant MCShadowFrame &shadowFrame [[buffer(MC_BUFFER_SHADOW_FRAME)]],
    constant MCResolveCamera &camera [[buffer(MC_BUFFER_RESOLVE_CAMERA)]],
    constant McFog &fog [[buffer(MC_BUFFER_LIGHTING_FRAME)]],
    depth2d_array<float> shadowMap [[texture(MC_TEX_SHADOW_MAP)]],
    sampler shadowSampler [[sampler(MC_TEX_SHADOW_MAP)]]
) {
    ResolveTargets out = previous;
    if (previous.albedo.a <= 0.0) {
        return out;
    }
    float viewDepth = mc_unpack_view_depth(previous.normal, previous.light);
    // Outside the shadow volume visibility is exactly one. Lighting then reconstructs
    // the existing fogged seed unchanged, so retain that seed without normal/position
    // reconstruction or fog decode/re-encode. This reduces work, never pixel resolution.
    // Large shadow volumes rarely leave eligible loaded terrain: keep their original
    // program with no per-pixel branch. shadow_distance already recompiles the pack.
#ifndef MC_REDUCE_DISTANT_LIGHTING
#define MC_REDUCE_DISTANT_LIGHTING 1
#endif
#if MC_REDUCE_DISTANT_LIGHTING && defined(MC_OPTION_SHADOW_DISTANCE) && MC_OPTION_SHADOW_DISTANCE < 128
    if (options.debugView == 0 && isfinite(viewDepth) && viewDepth >= shadowFrame.shadowDistance) {
        out.albedo.a = 0.0;
        return out;
    }
#endif
    bool validDepth = isfinite(viewDepth) && viewDepth > 0.0 && viewDepth < 1024.0;
    float2 uv = in.position.xy / max(camera.screenSize, float2(1.0));
    float3 viewPos = validDepth
        ? mc_reconstruct_view_position(uv, viewDepth, camera.inverseProjection)
        : float3(0.0);
    float3 cameraRelative = validDepth
        ? mc_view_to_camera_relative(viewPos, camera.viewToCameraRelative)
        : float3(0.0);
    float3 viewNormal = mc_decode_normal(previous.normal.rg);
    float3 worldNormal = normalize((camera.viewToCameraRelative * float4(viewNormal, 0.0)).xyz);
    uint cascade = validDepth ? mc_shadow_cascade(viewDepth, shadowFrame) : shadowFrame.cascadeCount;
    float bias = mc_shadow_receiver_bias(worldNormal, viewDepth, shadowFrame);
    float visibility = validDepth
        ? mc_shadow_visibility(cameraRelative, viewDepth, bias, shadowFrame, shadowMap, shadowSampler, worldNormal)
        : 1.0;
    float fade = mc_shadow_distance_fade(viewDepth, shadowFrame);
    if (fade > 0.0 && validDepth && shadowFrame.cascadeCount > 0u) {
        visibility *= mc_cloud_sun_visibility(cameraRelative, shadowFrame.directionToSun.xyz,
            camera.cloudOrigin, camera.cloudSettings);
    }
    visibility = mix(1.0, visibility, fade);
    if (options.debugView == 2) {
        out.scene = mc_scene_seed(float4(previous.albedo.rgb, 1.0));
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 3) {
        out.scene = mc_scene_seed(float4(previous.normal.rg, 0.5, 1.0));
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 4) {
        out.scene = mc_scene_seed(float4(previous.light.rg, 0.0, 1.0));
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 5) {
        // Camera-relative receiver, mapped from [-32, 32] so a known fixture is a solid colour.
        out.scene = mc_scene_seed(float4(validDepth ? cameraRelative / 64.0 + 0.5 : float3(0.0), 1.0));
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 6) {
        float covered = cascade < min(shadowFrame.cascadeCount, 4u) ? 1.0 : 0.0;
        out.scene = mc_scene_seed(float4(covered * float(cascade + 1u) / 4.0, covered, validDepth ? 1.0 : 0.0, 1.0));
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 7) {
        out.scene = mc_scene_seed(float4(float3(visibility), 1.0));
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 8) {
        out.albedo.a = 0.0;
        return out;
    }
    out.scene = mc_compose_lighting(
        previous.scene, previous.albedo, previous.light.rg, cameraRelative, visibility, fog, shadowFrame,
        options.shadowStrength
    );
    out.albedo.a = 0.0;
    return out;
}

#endif
