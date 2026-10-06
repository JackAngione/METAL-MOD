#ifndef MC_POST_EFFECTS_METAL
#define MC_POST_EFFECTS_METAL

// Projection coefficients come from the actual world raster matrix. No fixed
// near/far planes, CPU depth readback or water animation clock are required.
struct McPostFrame {
    float projectionZ;
    float projectionW;
    float clipZ;
    float clipW;
    uint animationFrame;
    uint _pad0;
    uint _pad1;
    uint _pad2;
};

static float mc_post_distance(float depth, constant McPostFrame &frame) {
    if (!isfinite(depth) || depth <= 1.0e-7) return 2000.0;
    float denominator = depth * frame.clipZ - frame.projectionZ;
    if (abs(denominator) < 1.0e-8) return 2000.0;
    return clamp(abs((frame.projectionW - depth * frame.clipW) / denominator), 0.05, 2000.0);
}

// Signed normalized focus error: foreground < 0; background > 0. The broad
// exact-zero interval preserves the subject and its surroundings at every tier.
// Keep this independent of strength so Low retains the same silhouette rejection.
static float mc_post_coc(float distance, float focus) {
    float nearHalf = max(6.0, 0.6 * focus);
    float farHalf = max(12.0, 1.25 * focus);
    if (distance < focus - nearHalf) {
        return -smoothstep(0.0, nearHalf, focus - nearHalf - distance);
    }
    return smoothstep(0.0, 2.0 * farHalf, max(0.0, distance - focus - farHalf));
}

static float mc_post_dof_radius(int level) {
    return level <= 1 ? 4.0 : level == 2 ? 8.0 : 12.0;
}

static float mc_post_dof_blend(int level) {
    return level <= 1 ? 0.25 : level == 2 ? 0.5 : 0.75;
}

static float mc_post_grain(uint2 pixel, uint frame) {
    uint h = pixel.x * 1597334677u ^ pixel.y * 3812015801u ^ frame * 2798796415u;
    h = (h ^ (h >> 16)) * 2246822519u;
    h = (h ^ (h >> 13)) * 3266489917u;
    return float(h ^ (h >> 16)) * (1.0 / 4294967296.0) - 0.5;
}

#endif
