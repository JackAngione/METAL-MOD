// Frozen production reference from 49d4eda, before the 2026-09-26 performance audit.
// Keep independent of production helpers so GPU comparisons detect formula changes.
#ifndef MC_SKY_METAL
#define MC_SKY_METAL

// Procedural sky assets. All palette entries and lighting are linear RGB.
// Periodic value noise gives stable world-space formations without a bitmap atlas.
struct McSkyFrame {
    float4x4 clipToWorld;
    float4 skyColor;
    float4 sunRain;       // world sun direction, clear-weather brightness
    float4 cloudOrigin;   // periodic advected camera X/Z, camera Y, cloud base Y
    float4 cloudSettings; // cloud mode (0/off, 1/fast, 2/fancy), alpha, unused
    float4 fogColor;
    float4 moonPhase;     // world moon direction, Minecraft phase index (0/full, 4/new)
};

static inline float mc_sky_hash(float2 p) {
    p = p - floor(p / 256.0) * 256.0;
    float3 q = fract(float3(p.x, p.y, p.x) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

static inline float mc_sky_noise(float2 p) {
    float2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mc_sky_hash(i), mc_sky_hash(i + float2(1, 0)), f.x),
               mix(mc_sky_hash(i + float2(0, 1)), mc_sky_hash(i + 1.0), f.x), f.y);
}

static inline float mc_cloud_shape(float2 p, bool detailed) {
    float n = mc_sky_noise(p) * 0.57;
    n += mc_sky_noise(p * 2.0 + 17.0) * 0.28;
    n += mc_sky_noise(p * 4.0 + 41.0) * 0.15;
    if (detailed) n += (mc_sky_noise(p * 8.0 + 93.0) - 0.5) * 0.075;
    return n;
}

static inline float mc_sky_hash3(float3 p) {
    p = p - floor(p / 256.0) * 256.0;
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

static inline float mc_sky_noise3(float3 p) {
    float3 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(mc_sky_hash3(i), mc_sky_hash3(i + float3(1, 0, 0)), f.x),
                  mix(mc_sky_hash3(i + float3(0, 1, 0)), mc_sky_hash3(i + float3(1, 1, 0)), f.x), f.y);
    float b = mix(mix(mc_sky_hash3(i + float3(0, 0, 1)), mc_sky_hash3(i + float3(1, 0, 1)), f.x),
                  mix(mc_sky_hash3(i + float3(0, 1, 1)), mc_sky_hash3(i + 1.0), f.x), f.y);
    return mix(a, b, f.z);
}

// Shared by sky rendering and the terrain sunlight resolve.
static inline float mc_cumulus_coverage(float2 p, float rainBrightness) {
    return mc_sky_noise(p * 0.25) * 0.10 + (1.0 - rainBrightness) * 0.065;
}

static inline float mc_cumulus_shape(float3 volume, float footprint) {
    return mix(mc_sky_noise3(volume), 0.5, smoothstep(0.25, 0.8, footprint)) * 0.57
         + mix(mc_sky_noise3(volume * 2.0 + 17.0), 0.5, smoothstep(0.25, 0.8, footprint * 2.0)) * 0.28
         + mix(mc_sky_noise3(volume * 4.0 + 41.0), 0.5, smoothstep(0.25, 0.8, footprint * 4.0)) * 0.15;
}

static inline float mc_cumulus_density(float shape, float coverage, float h) {
    float profile = pow(abs(h - 0.34) / 0.66, 2.0) * 0.24;
    // Raising the threshold leaves larger clear gaps between cloud banks.
    return 0.5 * smoothstep(0.580 - coverage + profile, 0.740 - coverage + profile, shape)
         * smoothstep(0.0, 0.10, h) * (1.0 - smoothstep(0.88, 1.0, h));
}

static inline float mc_sky_day(float sunHeight) {
    return smoothstep(-0.16, 0.14, sunHeight);
}

static inline float mc_sky_twilight(float sunHeight) {
    return (1.0 - smoothstep(0.04, 0.34, abs(sunHeight)))
         * smoothstep(-0.20, -0.04, sunHeight);
}

// Warm forward scattering and a violet anti-solar band at twilight, with an
// exponential horizon path-length approximation. This is an artistic atmosphere,
// not a planetary multiple-scattering simulation.
static inline float3 mc_sky_atmosphere(float3 ray, constant McSkyFrame& f) {
    float day = mc_sky_day(f.sunRain.y);
    float dusk = mc_sky_twilight(f.sunRain.y) * f.sunRain.w;
    float elevation = max(ray.y, 0.0);
    float horizon = exp(-elevation * 5.5);
    float3 zenith = max(f.skyColor.rgb * float3(0.52, 0.68, 0.93), float3(0.002, 0.004, 0.012));
    float3 haze = mix(float3(0.008, 0.012, 0.025), float3(0.47, 0.60, 0.72), day);
    float3 color = mix(zenith, haze, horizon * 0.72);
    float towardSun = dot(ray, f.sunRain.xyz);
    float warmLobe = pow(saturate(towardSun * 0.5 + 0.5), 5.0);
    float sunsetBand = exp(-abs(ray.y - 0.055) * 6.0);
    color = mix(color, float3(0.29, 0.095, 0.20), dusk * sunsetBand * (1.0 - warmLobe) * 0.43);
    color = mix(color, float3(1.20, 0.29, 0.055), dusk * sunsetBand * warmLobe * 0.74);
    // Mie-style circumsolar aureole surrounding the custom solar disc.
    float halo = pow(saturate(towardSun), 32.0) * 0.16 + pow(saturate(towardSun), 256.0) * 0.24;
    color += mix(float3(1.0, 0.88, 0.65), float3(1.0, 0.33, 0.075), dusk)
           * halo * day * f.sunRain.w;
    float grey = dot(color, float3(0.2126, 0.7152, 0.0722));
    color = mix(float3(grey) * float3(0.80, 0.86, 0.94), color, 0.35 + 0.65 * f.sunRain.w);
    // Match vanilla terrain fog at/below the horizon; avoids a hard sky/terrain seam.
    return mix(f.fogColor.rgb, color, smoothstep(-0.07, 0.10, ray.y));
}

// Sparse, gently warped high ice-cloud filaments above the cumulus volume.
static inline float4 mc_sky_cirrus(float3 ray, constant McSkyFrame& f) {
    float pixelAngle = max(length(dfdx(ray)), length(dfdy(ray)));
    float delta = f.cloudOrigin.w + 320.0 - f.cloudOrigin.y;
    if (delta * ray.y <= 0.0 || abs(ray.y) < 0.02) return float4(0.0);
    float distance = delta / ray.y;
    float fade = (1.0 - smoothstep(4000.0, 10000.0, distance)) * smoothstep(0.02, 0.12, abs(ray.y));
    fade *= smoothstep(0.0, 20.0, abs(delta));
    float2 p = (f.cloudOrigin.xz + ray.xz * distance) / 128.0;
    float2 streak = float2(p.x + p.y * 0.25, p.y * 3.0);
    streak.y += mc_sky_noise(p * 0.5) * 2.5;
    float shape = mc_cloud_shape(streak, false);
    float alpha = smoothstep(0.62, 0.84, shape) * 0.13 * fade * f.cloudSettings.y;
    alpha *= 1.0 - smoothstep(0.25, 1.0, pixelAngle * distance * 3.0 / (128.0 * max(abs(ray.y), 0.08)));
    float day = mc_sky_day(f.sunRain.y);
    float dusk = mc_sky_twilight(f.sunRain.y) * f.sunRain.w;
    float sunward = pow(saturate(dot(ray, f.sunRain.xyz) * 0.5 + 0.5), 4.0);
    float3 color = mix(float3(0.025, 0.035, 0.055), float3(0.80, 0.85, 0.93), day);
    color = mix(color, float3(1.25, 0.43, 0.23), dusk * (0.4 + sunward * 0.6));
    color *= mix(0.48, 1.0, f.sunRain.w);
    return float4(color * alpha, alpha);
}

// A shallow cumulus volume: bounded steps through a 96-block slab. The vertical
// profile rounds the tops and leaves flatter bases; directional density relief
// provides self-shading without a second shadow march or temporal history.
static inline float4 mc_sky_cumulus(float3 ray, constant McSkyFrame& f) {
    float pixelAngle = max(length(dfdx(ray)), length(dfdy(ray)));
    if (abs(ray.y) < 0.008) return float4(0.0);
    float base = f.cloudOrigin.w + 12.0;
    float bottom = (base - f.cloudOrigin.y) / ray.y;
    float top = (base + 96.0 - f.cloudOrigin.y) / ray.y;
    float begin = max(min(bottom, top), 0.0);
    float end = min(max(bottom, top), 6000.0);
    if (end <= begin) return float4(0.0);
    bool detailed = f.cloudSettings.x > 1.5;
    int steps = detailed ? 12 : 6;
    end = min(end, begin + 1600.0);
    float stepLength = (end - begin) / float(steps);
    float day = mc_sky_day(f.sunRain.y);
    float dusk = mc_sky_twilight(f.sunRain.y) * f.sunRain.w;
    float sunward = pow(saturate(dot(ray, f.sunRain.xyz) * 0.5 + 0.5), 4.0);
    float3 ambient = mix(float3(0.007, 0.012, 0.023), float3(0.22, 0.29, 0.39), day);
    float3 direct = mix(float3(0.028, 0.038, 0.06), float3(1.05, 1.02, 0.96), day);
    direct = mix(direct, float3(1.50, 0.43, 0.13), dusk * (0.38 + sunward * 0.62));
    float4 result = float4(0.0);
    float3 atmosphere = mc_sky_atmosphere(ray, f);
    // At grazing angles a thin volume spans many kilometres. Collapse its sample
    // footprint towards a distant sheet, continuously, instead of undersampling
    // that path into horizontal bands or introducing screen-space dithering.
    float volumeDetail = smoothstep(0.12, 0.38, abs(ray.y));
    for (int i = 0; i < steps; i++) {
        float fraction = mix(0.5, (float(i) + 0.5) / float(steps), volumeDetail);
        float distance = mix(begin, end, fraction);
        float3 world = f.cloudOrigin.xyz + ray * distance;
        float h = saturate((world.y - base) / 96.0);
        float2 p = world.xz / 128.0;
        float coverage = mc_cumulus_coverage(p, f.sunRain.w);
        float3 volume = float3(p.x, h * 1.25, p.y);
        // Procedural mip filtering: fade unresolved octaves to their mean instead
        // of letting the distant sheet alias into hard patches as the camera moves.
        float footprint = pixelAngle * distance / (128.0 * max(abs(ray.y), 0.08));
        float shape = mc_cumulus_shape(volume, footprint);
        float density = mc_cumulus_density(shape, coverage, h);
        float alpha = (1.0 - exp(-density * stepLength * 0.055));
        float relief = saturate(0.58 + (shape - mc_sky_noise3(volume + f.sunRain.xyz * 0.3)) * 1.7);
        float lighting = saturate(relief * 0.65 + h * 0.55 - density * 0.18);
        float3 color = mix(ambient, direct, lighting);
        color += direct * pow(saturate(dot(ray, f.sunRain.xyz)), 12.0)
               * pow(1.0 - density, 3.0) * 0.60 * f.sunRain.w;
        color *= mix(0.35, 1.0, f.sunRain.w);
        color = mix(atmosphere, color, exp(-distance / 10000.0));
        result += float4(color * alpha, alpha) * (1.0 - result.a);
        if (result.a > 0.985) break;
    }
    float fade = (1.0 - smoothstep(2500.0, 6000.0, begin)) * smoothstep(0.02, 0.10, abs(ray.y));
    return result * fade * f.cloudSettings.y;
}
#endif
