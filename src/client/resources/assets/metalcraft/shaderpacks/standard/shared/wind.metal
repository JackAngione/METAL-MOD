#ifndef MC_SHARED_WIND
#define MC_SHARED_WIND
#include <metal_stdlib>
using namespace metal;

#ifndef MC_OPTION_WIND_STRENGTH
#define MC_OPTION_WIND_STRENGTH 1.0
#endif

struct WindDraw { uint baseVertex; uint vertexCount; float seconds; float reserved; };

// Integer reduction before conversion keeps motion stable at the world border. Every spatial
// frequency below is an integer multiple of 2*pi/1024, so adjacent sections meet across the wrap.
static inline float3 mc_wind_world_position(int3 section, float3 local) {
    return float3(section & int3(1023)) + local;
}

static inline float3 mc_wind_offset(float3 world, float height, float seconds) {
    if (height == 0.0 || MC_OPTION_WIND_STRENGTH == 0.0) return float3(0.0);
    const float cycle = 6.28318530718 / 1024.0;
    // A broad gust travels through the canopy, with slower bending and a little leaf flutter.
    // Integer time frequencies join at 1024 animation seconds. The host clock runs at 80%
    // speed, wrapping after 1280 game seconds, and is shared by foliage and shadow draws.
    float gust = 0.65 + 0.35 * sin(cycle * (dot(world.xz, float2(3.0, 2.0)) - seconds * 23.0));
    float bend = sin(cycle * (dot(world.xz, float2(11.0, 7.0)) + seconds * 137.0));
    float crosswind = sin(cycle * (dot(world.xz, float2(-7.0, 13.0)) + seconds * 191.0));
    float2 sway = float2(0.94, 0.34) * (0.35 + 0.65 * bend)
        + float2(-0.34, 0.94) * (0.22 * crosswind);
    if (height < 0.0) {
        float flutter = sin(cycle * (dot(world, float3(79.0, 37.0, 53.0)) + seconds * 811.0));
        return float3(sway.x * 0.11 * gust + flutter * 0.018,
            flutter * 0.012, sway.y * 0.11 * gust) * MC_OPTION_WIND_STRENGTH;
    }
    // Lower/upper halves share height 1 at their joint. Height zero keeps roots motionless.
    float weight = height * height;
    float2 offset = sway * (0.10 * weight * gust) * MC_OPTION_WIND_STRENGTH;
    return float3(offset.x, -dot(offset, offset) / (2.0 * max(height, 0.01)), offset.y);
}
#endif
