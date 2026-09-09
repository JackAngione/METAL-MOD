#ifndef MC_UNDERWATER_METAL
#define MC_UNDERWATER_METAL

struct McUnderwaterFrame {
    float animationSeconds;
    float strength;
    float2 padding;
};

// World color is already fogged by Minecraft. Only displace it here; adding another
// absorption/fog term would double the biome-aware distance attenuation. The encoded
// water overlay, hand and HUD are drawn after grade and remain undistorted.
static inline float2 mc_underwater_uv(float2 uv, float2 extent, float seconds, float strength) {
    if (!isfinite(strength) || strength <= 0.0 || !isfinite(seconds)
        || !all(isfinite(uv)) || !all(isfinite(extent)) || any(extent <= 0.0)) return uv;
    const float tau = 6.28318530718;
    float phase = tau * seconds / 1024.0;
    float2 wave = float2(sin(uv.y * tau * 3.0 + phase * 173.0),
                         sin(uv.x * tau * 4.0 - phase * 227.0));
    float2 edgePixels = min(uv, 1.0 - uv) * extent;
    float edgeFade = smoothstep(0.0, 12.0, min(edgePixels.x, edgePixels.y));
    // At most 1.5 native render pixels per axis; no edge clamping streaks.
    return uv + wave * (1.5 * saturate(strength) * edgeFade) / extent;
}

#endif
