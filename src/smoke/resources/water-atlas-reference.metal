// Frozen atlas sampling from Standard 14f9896 for full forward-fragment equivalence.
static inline float4 mc_sample_rgss(texture2d<float> atlas, sampler atlasSampler, float2 uv, float2 pixelSize) {
    float2 du = dfdx(uv);
    float2 dv = dfdy(uv);
    float2 texelScreenSize = sqrt(du * du + dv * dv);
    float maxTexelSize = max(texelScreenSize.x, texelScreenSize.y);
    float minPixelSize = min(pixelSize.x, pixelSize.y);
    float blendFactor = smoothstep(minPixelSize, minPixelSize * 2.0, maxTexelSize);

    float minDerivative = min(length(du), length(dv));
    float maxDerivative = max(length(du), length(dv));
    float mipLevelExact = max(0.0, log2(sqrt(minDerivative * maxDerivative) / minPixelSize));
    float mipLevelLow = floor(mipLevelExact);
    float mipBlend = fract(mipLevelExact);

    const float2 offsets[4] = {
        float2(0.125, 0.375), float2(-0.125, -0.375), float2(0.375, -0.125), float2(-0.375, 0.125)
    };
    float4 low = float4(0.0);
    float4 high = float4(0.0);
    for (int index = 0; index < 4; ++index) {
        float2 sampleUv = uv + offsets[index] * pixelSize;
        low += atlas.sample(atlasSampler, sampleUv, level(mipLevelLow));
        high += atlas.sample(atlasSampler, sampleUv, level(mipLevelLow + 1.0));
    }
    float4 rgss = mix(low * 0.25, high * 0.25, mipBlend);
    return mix(mc_sample_nearest(atlas, atlasSampler, uv, pixelSize, du, dv, texelScreenSize), rgss, blendFactor);
}

// MC_REFERENCE_ATLAS_FRAGMENT
    float2 pixelSize = 1.0 / float2(section.TextureSize);
    float4 texel =
#ifdef MC_TERRAIN_LOD
        in.lodMapV.z != 0.0 ? mc_lod_sample(atlas, atlasSampler, in.uv, in.lodMapU, in.lodMapV, in.lodBounds) :
#endif
        globals.UseRgss == 1
        ? mc_sample_rgss(atlas, atlasSampler, in.uv, pixelSize)
        : mc_sample_nearest(atlas, atlasSampler, in.uv, pixelSize, dfdx(in.uv), dfdy(in.uv),
            sqrt(dfdx(in.uv) * dfdx(in.uv) + dfdy(in.uv) * dfdy(in.uv)));

    // Enabled water uses its biome vertex tint and lightmap, never the animated vanilla
    // atlas texel. Its RGB would print the vanilla pattern into the absorption/fallback
    // colour, and its alpha could print the same pattern into the translucent blend.
    // Keep the atlas path for other translucent blocks and for water-off/debug baseline.
#ifdef MC_WATER_FORWARD
    if (MC_OPTION_WATER_ENABLED && in.waterMaterial == 1.0
        && (waterDraw.debugMode == 0u || waterDraw.debugMode == 5u || waterDraw.debugMode == 6u
            || waterDraw.debugMode == 7u || waterDraw.debugMode == 8u)) {
        texel = float4(1.0);
    }
#endif
