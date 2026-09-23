#include <metal_stdlib>
using namespace metal;
// shared/sky.metal is prepended by StandardSkyRenderer.
struct McSkyVertex { float4 position [[position]]; float2 uv; };
vertex McSkyVertex sky_vertex(uint id [[vertex_id]]) {
    float2 p = float2((id << 1) & 2, id & 2) * 2.0 - 1.0;
    return {float4(p, 0.0, 1.0), float2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5)};
}
static inline float3 mc_sky_ray(float2 uv, constant McSkyFrame& f) {
    // Match mc_clip_position / translated vanilla's Metal Y flip. Scene attachments
    // keep Minecraft's raster orientation until the final presentation blit.
    // The final projection includes view-bob translation. A single unprojected
    // near-plane point includes that offset and makes the sky swing while walking.
    // Subtract two unprojected points in homogeneous form to recover a direction:
    // translation cancels, rotation/FOV remain, and an infinite far plane is valid.
    // Minecraft uses reversed depth: 0 = far, 1 = near.
    float4 farPoint = f.clipToWorld * float4(uv * 2.0 - 1.0, 0.0, 1.0);
    float4 nearPoint = farPoint + f.clipToWorld[2];
    return normalize(farPoint.xyz * nearPoint.w - nearPoint.xyz * farPoint.w);
}
fragment float4 sky_atmosphere(McSkyVertex in [[stage_in]], constant McSkyFrame& f [[buffer(0)]]) {
    return float4(mc_sky_atmosphere(mc_sky_ray(in.uv, f), f), 1.0);
}
fragment float4 sky_celestials(McSkyVertex in [[stage_in]], constant McSkyFrame& f [[buffer(0)]],
                               texture2d<float> moonSurface [[texture(0)]], sampler filtered [[sampler(0)]]) {
    return mc_sky_celestials(mc_sky_ray(in.uv, f), f, moonSurface, filtered);
}
fragment float4 sky_clouds(McSkyVertex in [[stage_in]], constant McSkyFrame& f [[buffer(0)]]) {
    if (f.cloudSettings.x < 0.5) return float4(0.0);
    float3 ray = mc_sky_ray(in.uv, f);
    float4 high = f.cloudSettings.x > 1.5 ? mc_sky_cirrus(ray, f) : float4(0.0);
    float4 low = mc_sky_cumulus(ray, f);
    if (f.cloudOrigin.y > f.cloudOrigin.w + 320.0) return high + low * (1.0 - high.a);
    return low + high * (1.0 - low.a);
}
fragment float4 sky_composite(McSkyVertex in [[stage_in]], texture2d<float> clouds [[texture(0)]],
                              sampler filtered [[sampler(0)]]) {
    return clouds.sample(filtered, in.uv);
}
