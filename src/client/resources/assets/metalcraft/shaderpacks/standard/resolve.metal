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
    constant PackOptions &options [[buffer(0)]]
) {
    ResolveTargets out = previous;
    if (previous.albedo.a <= 0.0) {
        return out;
    }
    if (options.debugView == 2) {
        out.scene = float4(previous.albedo.rgb, 1.0);
        return out;
    }
    if (options.debugView == 3) {
        out.scene = float4(previous.normal.rg, 0.5, 1.0);
        return out;
    }
    if (options.debugView == 4) {
        out.scene = float4(previous.light.rg, 0.0, 1.0);
        return out;
    }
    // Geometry already wrote the sampled RGB lightmap, overlays, emissive treatment and fog
    // into scene. UV2 levels are metadata, not a replacement for that lighting. Preserve the
    // shaded seed until the lighting module can also reconstruct and apply those effects.
    return out;
}

#endif
