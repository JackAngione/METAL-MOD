#ifndef MC_CELESTIALS_METAL
#define MC_CELESTIALS_METAL

// Analytic spheres: no tessellation or per-frame texture allocation. Bundled lunar
// albedo/elevation assets: NASA Scientific Visualization Studio CGI Moon Kit.
// Angular sizes are enlarged for Minecraft readability (radius in radians).
constant float MC_SUN_RADIUS = 0.035;
constant float MC_MOON_RADIUS = 0.042;

static inline float2 mc_body_uv(float3 ray, float3 direction, float radius) {
    // A fixed orbital basis stays stable even directly overhead.
    float3 right = float3(0.0, 0.0, -1.0);
    float3 up = cross(right, direction);
    return float2(dot(ray, right), dot(ray, up)) / sin(radius);
}

static inline float3 mc_moon_surface(float3 normal, float phase, float footprint,
                                     texture2d<float> surface, sampler filtered) {
    float2 uv = float2(atan2(normal.x, normal.z) / (2.0 * M_PI_F) + 0.5,
                      0.5 - asin(clamp(normal.y, -1.0, 1.0)) / M_PI_F);
    // Explicit, conservative LOD avoids derivatives inside the small body branch.
    // Increase filtering at the foreshortened limb and longitude singularity.
    float cosLatitude = max(length(normal.xz), 0.12);
    float lod = max(0.0, log2(footprint * 1024.0 / (2.0 * M_PI_F * max(normal.z, 0.12) * cosLatitude)));
    float step = exp2(lod) / 1024.0;
    float4 material = surface.sample(filtered, uv, level(lod));
    float eastHeight = surface.sample(filtered, uv + float2(step, 0.0), level(lod)).a;
    float southHeight = surface.sample(filtered, uv + float2(0.0, step), level(lod)).a;
    float3 east = float3(normal.z, 0.0, -normal.x) / cosLatitude;
    float3 north = cross(normal, east);
    float2 slope = float2((eastHeight - material.a) / cosLatitude, (material.a - southHeight) * 2.0)
                 / (step * 2.0 * M_PI_F);
    // Modestly exaggerated LOLA relief reads at normal gameplay sizes.
    float3 bumped = normalize(normal - (east * slope.x + north * slope.y) * 0.025);
    // Minecraft order: full, waning, new, waxing. The terminator lights a sphere.
    float angle = phase * (M_PI_F / 4.0);
    float3 light = float3(-sin(angle), 0.0, cos(angle));
    float incidence = max(dot(bumped, light), 0.0);
    float terminator = smoothstep(-0.012, 0.025, dot(normal, light));
    // Lunar regolith backscatter and a faint earth-lit unilluminated hemisphere.
    float diffuse = incidence / max(incidence + normal.z, 0.08);
    float earthshine = 0.014 * (1.0 - cos(angle) * 0.5);
    return material.rgb * float3(0.97, 0.99, 1.02) * (diffuse * terminator * 2.6 + earthshine);
}

static inline float4 mc_sky_celestials(float3 ray, constant McSkyFrame& f,
                                             texture2d<float> moonSurface, sampler filtered) {
    // Derivatives are evaluated before any divergent body/feature branches.
    float pixelAngle = max(length(dfdx(ray)), length(dfdy(ray)));
    float visibility = f.sunRain.w * smoothstep(-0.075, 0.015, ray.y);
    if (visibility <= 0.0) return float4(0.0);
    float4 result = float4(0.0);
    float sunDot = dot(ray, f.sunRain.xyz);
    if (sunDot > cos(MC_SUN_RADIUS * 4.0)) {
        float2 p = mc_body_uv(ray, f.sunRain.xyz, MC_SUN_RADIUS);
        float r = length(p);
        float aa = max(pixelAngle / MC_SUN_RADIUS, 0.001);
        float coverage = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, r);
        float mu = sqrt(saturate(1.0 - dot(p, p)));
        float lowSun = 1.0 - smoothstep(0.0, 0.32, f.sunRain.y);
        float3 tint = mix(float3(1.0, 0.94, 0.79), float3(1.0, 0.27, 0.055), lowSun);
        float radiance = mix(7.0, 3.4, lowSun) * (0.42 + 0.58 * mu);
        // Tight aureole complements the broader atmospheric forward scattering.
        float glow = exp(-max(r - 1.0, 0.0) * 3.2) * 0.15;
        result = float4(tint * (radiance * coverage + glow) * visibility, coverage * visibility);
    }
    float moonDot = dot(ray, f.moonPhase.xyz);
    if (moonDot > cos(MC_MOON_RADIUS + pixelAngle * 2.0)) {
        float2 p = mc_body_uv(ray, f.moonPhase.xyz, MC_MOON_RADIUS);
        float r = length(p);
        float aa = max(pixelAngle / MC_MOON_RADIUS, 0.001);
        float coverage = 1.0 - smoothstep(1.0 - aa, 1.0 + aa, r);
        if (coverage > 0.0) {
            // Clamp the AA fringe to the limb before reconstructing its normal.
            float2 xy = p / max(r, 1.0);
            float3 normal = float3(xy, sqrt(saturate(1.0 - dot(xy, xy))));
            float3 color = mc_moon_surface(normal, f.moonPhase.w, aa, moonSurface, filtered);
            float day = mc_sky_day(f.sunRain.y);
            float haze = 1.0 - smoothstep(0.0, 0.25, f.moonPhase.y);
            color *= mix(float3(1.0), float3(1.0, 0.66, 0.40), haze * 0.65);
            // Air scattering remains in front of the moon, especially in daylight.
            color = color * mix(1.0, 0.42, day) + mc_sky_atmosphere(ray, f) * mix(0.12, 0.95, day);
            float alpha = coverage * visibility;
            result = float4(color * alpha, alpha) + result * (1.0 - alpha);
        }
    }
    return result;
}

#endif
