#ifndef MC_LOCAL_LIGHTS_METAL
#define MC_LOCAL_LIGHTS_METAL

#if MC_OPTION_LOCAL_LIGHTS && defined(MC_PASS_RESOLVE)
// ABI: LocalLightVolume + WorldLocalLighting. All positions share an integer volume origin.
struct MCLocalFrame {
    float4 camera; // xyz camera in volume, w receiver distance
    uint4 counts;  // volume size, static lights, moving lights, enabled
};
struct MCLocalLight {
    float4 position; // xyz in volume, w emission/range
    uint4 flags;
};

static inline bool mc_local_inside(int3 cell, uint size) {
    return all(cell >= int3(0)) && all(cell < int3(size));
}
static inline uint mc_local_index(int3 cell, uint size) {
    return uint(cell.x) + size * (uint(cell.y) + size * uint(cell.z));
}

static inline bool mc_local_box_hit(uint packed, float3 origin, float3 inverseRay, float limit) {
    float3 low = float3(packed & 31u, (packed >> 10) & 31u, (packed >> 20) & 31u) / 16.0;
    float3 high = float3((packed >> 5) & 31u, (packed >> 15) & 31u, (packed >> 25) & 31u) / 16.0;
    float3 a = (low - origin) * inverseRay, b = (high - origin) * inverseRay;
    float3 near = min(a, b), far = max(a, b);
    return min(min(far.x, far.y), far.z) > max(max(max(near.x, near.y), near.z), 0.015)
        && max(max(near.x, near.y), near.z) < limit;
}

// Off-screen blockers participate. Amanatides/Woo traversal touches each crossed block once;
// actual component boxes avoid turning slabs, stairs and fences into full-cube occluders.
static inline float mc_local_visibility(float3 receiver, MCLocalLight light, uint size, device const uint *scene) {
    float3 delta = light.position.xyz - receiver;
    float distance = length(delta);
    if (distance < 0.05) return 1.0;
    float3 ray = delta / distance;
    float3 inverse = 1.0 / select(float3(1e-8), ray, abs(ray) > 1e-8);
    int3 step = select(int3(-1), int3(1), ray >= 0.0);
    int3 cell = int3(floor(receiver)), target = int3(floor(light.position.xyz));
    float3 boundary = float3(cell) + select(float3(0.0), float3(1.0), step > 0);
    float3 next = (boundary - receiver) * inverse;
    float3 stride = abs(inverse);
    for (uint iteration = 0; iteration < 64u; iteration++) {
        if (!mc_local_inside(cell, size)) return 0.0;
        // The opaque luminous block itself must not hide its own emission.
        if (light.flags.x == 0u && all(cell == target)) return 1.0;
        uint shape = scene[mc_local_index(cell, size)];
        if (shape != 0u) {
            uint count = scene[shape];
            for (uint box = 0; box < count; box++) {
                if (mc_local_box_hit(scene[shape + 1u + box], receiver - float3(cell), inverse, distance - 0.025)) return 0.0;
            }
        }
        float travel = min(min(next.x, next.y), next.z);
        if (travel >= distance - 0.025) return 1.0;
        bool3 advance = next <= travel + 1e-5;
        cell += select(int3(0), step, advance);
        next += select(float3(0.0), stride, advance);
    }
    return 0.0;
}

static inline float mc_local_energy(float3 position, MCLocalLight light) {
    float value = saturate((light.position.w - length(position - light.position.xyz)) / 15.0);
    return value * value;
}

// x = placed-light visibility, y = moving-source irradiance. Every overlapping source is
// eligible; the early exit is a conservative bound, never a fixed "nearest N lights" cutoff.
static inline float2 mc_local_lighting(float3 relative, float3 normal, constant MCLocalFrame &frame,
    device const uint *scene, device const MCLocalLight *lights, device const uint *clusters,
    device const MCLocalLight *moving, bool tracePlaced = true) {
    float fade = 1.0 - smoothstep(frame.camera.w - 4.0, frame.camera.w, length(relative));
    if (frame.counts.w == 0u || (frame.counts.y == 0u && frame.counts.z == 0u) || fade <= 0.0) return float2(1.0, 0.0);
    float3 position = relative + frame.camera.xyz + normal * 0.035;
    int3 cell = int3(floor(position));
    if (!mc_local_inside(cell, frame.counts.x)) return float2(1.0, 0.0);
    uint clusterSize = frame.counts.x / 8u;
    uint cluster = mc_local_index(cell / 8, clusterSize);
    uint offset = clusters[cluster * 2u], count = clusters[cluster * 2u + 1u];
    float potential = 0.0, visible = 0.0;
    for (uint i = 0; tracePlaced && i < count; i++) {
        float bound = as_type<float>(clusters[offset + i * 2u + 1u]);
        if (bound < visible) break;
        MCLocalLight light = lights[clusters[offset + i * 2u]];
        float energy = mc_local_energy(position, light);
        potential = max(potential, energy);
        if (energy > visible) visible = max(visible, energy * mc_local_visibility(position, light, frame.counts.x, scene));
    }
    float dynamic = 0.0;
    for (uint i = 0; i < frame.counts.z; i++) {
        MCLocalLight light = moving[i];
        float3 delta = light.position.xyz - position;
        float facing = 0.25 + 0.75 * saturate(dot(normal, delta * rsqrt(max(dot(delta, delta), 1e-6))));
        float energy = mc_local_energy(position, light) * facing;
        if (energy > dynamic) dynamic = max(dynamic, energy * mc_local_visibility(position, light, frame.counts.x, scene));
    }
    float visibility = potential > 1e-6 ? saturate(visible / potential) : 1.0;
    return float2(mix(1.0, visibility, fade), dynamic * fade);
}

static inline float4 mc_compose_local_lighting(float4 scene, float4 albedo, float2 levels,
    float3 relative, float2 local, constant McFog &fog) {
    if (local.x == 1.0 && local.y == 0.0) return scene;
    if (mc_gbuffer_material(albedo.a) == MC_MATERIAL_EMISSIVE) return scene;
    float amount = mc_fog_amount(mc_fog_spherical_distance(relative), mc_fog_cylindrical_distance(relative), fog);
    if (amount >= 0.999) return scene;
    float3 seed = mc_unfog(scene.rgb, fog, amount);
    float share = saturate(levels.x / max(levels.x + levels.y, 1e-5));
    // Keep 20% of block energy as indirect/bounced light around corners.
    seed *= 1.0 - share * 0.8 * (1.0 - local.x);
    float3 surface = mc_scene_seed(float4(albedo.rgb, 1.0)).rgb;
    // Warm fire/torch light, resolved in the active scene encoding before the existing grade.
    float3 emitted = surface * float3(1.0, 0.72, 0.42) * local.y * 1.6;
    seed = max(seed, emitted);
    return float4(mix(seed, mc_scene_fog_color(fog), amount), scene.a);
}
#endif
#endif
