#ifndef MC_COLOR_METAL
#define MC_COLOR_METAL

// Explicit sRGB transfer at color boundaries. These are not lightmap decoders:
// vanilla's brightness-adjusted lightmap is an artistic multiplier, not sRGB radiance.
// Inputs are finite RGB; negative values are clipped, HDR values are not clipped to 1.
// Alpha, material IDs, normals and depth must never pass through these functions.
static inline float3 mc_srgb_to_linear(float3 encoded) {
    float3 x = max(encoded, float3(0.0));
    return select(pow((x + 0.055) / 1.055, float3(2.4)), x / 12.92, x <= 0.04045);
}

static inline float3 mc_linear_to_srgb(float3 linear) {
    float3 x = max(linear, float3(0.0));
    return select(1.055 * pow(x, float3(1.0 / 2.4)) - 0.055, 12.92 * x, x <= 0.0031308);
}

#endif
