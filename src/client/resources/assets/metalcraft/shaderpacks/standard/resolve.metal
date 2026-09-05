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
        ? mc_shadow_visibility(cameraRelative, viewDepth, bias, shadowFrame, shadowMap, shadowSampler)
        : 1.0;
    visibility = mix(1.0, visibility, mc_shadow_distance_fade(viewDepth, shadowFrame));
    if (options.debugView == 2) {
        out.scene = float4(previous.albedo.rgb, 1.0);
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 3) {
        out.scene = float4(previous.normal.rg, 0.5, 1.0);
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 4) {
        out.scene = float4(previous.light.rg, 0.0, 1.0);
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 5) {
        // Camera-relative receiver, mapped from [-32, 32] so a known fixture is a solid colour.
        out.scene = float4(validDepth ? cameraRelative / 64.0 + 0.5 : float3(0.0), 1.0);
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 6) {
        float covered = cascade < min(shadowFrame.cascadeCount, 4u) ? 1.0 : 0.0;
        out.scene = float4(covered * float(cascade + 1u) / 4.0, covered, validDepth ? 1.0 : 0.0, 1.0);
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 7) {
        out.scene = float4(float3(visibility), 1.0);
        out.albedo.a = 0.0;
        return out;
    }
    if (options.debugView == 8) {
        out.albedo.a = 0.0;
        return out;
    }
    out.scene = mc_compose_lighting(
        previous.scene, previous.albedo, worldNormal, previous.light.rg, cameraRelative, visibility, fog, shadowFrame
    );
    out.albedo.a = 0.0;
    return out;
}

#endif
