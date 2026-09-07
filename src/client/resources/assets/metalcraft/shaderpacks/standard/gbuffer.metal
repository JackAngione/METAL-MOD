// G-buffer geometry programs for Minecraft's three world vertex formats.
//
// The engine compiles this file once per substituted vanilla pipeline, with MC_PROGRAM_<NAME>
// naming which program to build, MC_SLOT_<RESOURCE> giving each named uniform and sampler its
// Metal argument-table index, MC_TARGET_<TARGET> giving each declared write its colour index, and
// MC_HAS_<RESOURCE> saying whether an optional input exists in this variant. Nothing here is
// hard-coded to a slot: the numbers come from the pipeline being stood in for, so a bind by name in
// Minecraft's own code lands where this program expects it.
//
// Colour zero is Minecraft's own attachment and receives the vanilla-shaded seed. The merged
// resolve replaces routed opaque pixels with deferred lighting (sun visibility on the direct sun
// term only) and preserves the seed wherever no G-buffer material was written. Layout, materials,
// fog and the sun-term split are documented in shared/lighting.metal. The three channels after
// scene are the memoryless G-buffer proper.

#include <metal_stdlib>
using namespace metal;

#ifdef MC_PASS_GBUFFER

// ---- Minecraft's uniform blocks, in the layout std140 gives them -------------------------------
// These are filled by Minecraft, not by the pack, so the field order and padding are a contract.
// MSL's natural alignment matches std140 for every block here; ChunkSection is the one that needs
// explicit padding, because a float followed by an int2 packs tighter in MSL than in std140.

struct McProjection {
    float4x4 ProjMat;
};

struct McGlobals {
    int3 CameraBlockPos;
    float3 CameraOffset;
    float2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

struct McChunkSection {
    float4x4 ModelViewMat;
    float ChunkVisibility;
    float _pad0;
    int2 TextureSize;
    int3 ChunkPosition;
};

struct McDynamicTransforms {
    float4x4 ModelViewMat;
    float4 ColorModulator;
    float3 ModelOffset;
    float4x4 TextureMat;
};

struct McLighting {
    float3 Light0_Direction;
    float3 Light1_Direction;
};

// ---- The attachments a world draw writes -------------------------------------------------------

#ifdef MC_WATER_FORWARD
// Typed sidecar binding: 32 bytes per original vertex, independent of sorted indices.
struct WaterVertexMetadata { float4 normalMaterial; float4 flow; };
struct WaterDraw { uint baseVertex; uint vertexCount; uint debugMode; uint reserved; };
// Matches WaterFrameInputs.UNIFORM_BYTES: projection, inverse, split camera, time, submerged.
struct WaterFrameUniform {
    float4x4 projection;
    float4x4 inverseProjection;
    float4 cameraWorldHigh;
    float4 cameraWorldResidual;
    float animationSeconds;
    uint cameraSubmerged;
    float2 pad;
};
// Linear view-depth debug encoding. Reverse-Z device 0 (clear/sky) reconstructs to the far plane.
constant float MC_WATER_DEBUG_DEPTH_RANGE = 32.0;

// Geometry already negated clip Y for Metal. Undo that pair before applying the captured inverse.
static inline float3 mc_water_view_from_device_depth(
    float2 pixel, float deviceDepth, float2 extent, float4x4 inverseProjection
) {
    float2 uv = pixel / max(extent, float2(1.0));
    float2 metalNdc = uv * 2.0 - 1.0;
    float4 viewH = inverseProjection * float4(metalNdc.x, -metalNdc.y, deviceDepth, 1.0);
    return viewH.xyz / max(abs(viewH.w), 1.0e-7);
}

static inline float mc_water_debug_depth_encode(float3 viewPos) {
    return saturate(max(0.0, -viewPos.z) / MC_WATER_DEBUG_DEPTH_RANGE);
}
#endif

struct GBufferTargets {
    float4 scene  [[color(MC_TARGET_SCENE)]];
    float4 albedo [[color(MC_TARGET_GBUFFER_ALBEDO)]];
    float4 normal [[color(MC_TARGET_GBUFFER_NORMAL)]];
    float4 light  [[color(MC_TARGET_GBUFFER_LIGHT)]];
};

struct GBufferVaryings {
    float4 position [[position]];
	/// Camera-view space. Keeping positions and normals in the same space lets the resolve rebuild
	/// a shadow lookup from screen position plus the packed linear depth.
    float3 worldPos;
    float3 normal;
    float4 tint;
    float4 lightMapColor;
    float2 uv;
    /// Block light and sky light, straight off UV2 and normalised out of Minecraft's 0..240 range.
    float2 lightLevels;
    float sphericalDistance;
    float cylindricalDistance;
#ifdef MC_WATER_FORWARD
    float waterMaterial [[flat]];
    float3 waterFlow;
#endif
};

// Fog, octahedral encode/decode, material IDs and the sun-term split live in shared/lighting.metal.

/**
 * Clip space, with the sign convention Minecraft's own programs are translated into.
 *
 * <p>Minecraft's GLSL is compiled through SPIR-V with the vertex-Y flip that turns OpenGL's
 * bottom-left NDC origin into Metal's top-left one. A program written directly in MSL gets no such
 * treatment, so it has to flip for itself - and it is not only the image that flips. Negating Y
 * reverses triangle winding too, which means an unflipped program also culls the wrong face and
 * shows the inside of the world rather than its surface.
 */
static inline float4 mc_clip_position(float4 clip) {
    return float4(clip.x, -clip.y, clip.z, clip.w);
}

/// Minecraft's lightmap lookup: UV2 is a 0..240 pair addressing a 16x16 texture by texel centre.
static inline float4 mc_sample_lightmap(texture2d<float> lightMap, sampler lightSampler, float2 uv2) {
    float2 coord = clamp(uv2 / 256.0 + 0.5 / 16.0, float2(0.5 / 16.0), float2(15.5 / 16.0));
    return lightMap.sample(lightSampler, coord, level(0.0));
}

/// How rough this surface class is, until a pack can say so per block.
static inline float mc_material_roughness(int material) {
    if (material == MC_MATERIAL_WATER) {
        return 0.05;
    }
    if (material == MC_MATERIAL_FOLIAGE) {
        return 0.9;
    }
    if (material == MC_MATERIAL_ENTITY || material == MC_MATERIAL_EMISSIVE) {
        return 0.7;
    }
    return 0.85;
}

/// Fills the three G-buffer channels from one shaded fragment.
static inline void mc_write_gbuffer(
    thread GBufferTargets &out, float3 albedo, float3 normal, float2 lightLevels, float viewDepth
) {
    // One past the material class, so a zero alpha means "nothing drew here" rather than the first
    // class. The G-buffer is cleared to zero and the sky never writes it, so the distinction is the
    // difference between reading the sky as solid and reading it as absent.
    out.albedo = float4(albedo, float(MC_MATERIAL + 1) / 255.0);
    // Preserve camera-relative position accurately enough that a moving camera does not make a
    // stationary receiver crawl across shadow texels. The normal target's alpha byte was unused,
    // so depth can use 24 bits without another attachment or more tile memory.
    uint packedDepth = uint(round(saturate(viewDepth / 1024.0) * 16777215.0));
    out.normal = float4(
        mc_encode_normal(normal), mc_material_roughness(MC_MATERIAL),
        float((packedDepth >> 16) & 255u) / 255.0
    );
    out.light = float4(
        lightLevels.x,
        lightLevels.y,
        float((packedDepth >> 8) & 255u) / 255.0,
        float(packedDepth & 255u) / 255.0
    );
}

/// The face normal of a flat surface, from how its camera-relative position changes across the
/// quad. Exact for an axis-aligned face, which is nearly all of Minecraft's terrain, and wrong only
/// on the minority of geometry that is not flat - which is why the vertex format does not have to
/// grow a normal before this phase can produce one.
static inline float3 mc_reconstruct_normal(float3 worldPos) {
    float3 reconstructed = cross(dfdx(worldPos), dfdy(worldPos));
    float magnitude = length(reconstructed);
    if (magnitude < 1e-8) {
        return float3(0.0, 1.0, 0.0);
    }
    reconstructed /= magnitude;
    // The camera is the origin of this space, so a normal pointing away from it faces the wrong way.
    return dot(reconstructed, worldPos) > 0.0 ? -reconstructed : reconstructed;
}

#endif // MC_PASS_GBUFFER

// ================================================================================================
// Terrain
// ================================================================================================

#if defined(MC_PASS_GBUFFER) && defined(MC_PROGRAM_TERRAIN)

struct TerrainVertex {
    float3 Position [[attribute(0)]];
    float4 Color    [[attribute(1)]];
    float2 UV0      [[attribute(2)]];
    short2 UV2      [[attribute(3)]];
};

vertex GBufferVaryings gbuffer_terrain_vertex(
    TerrainVertex in [[stage_in]],
    constant McProjection &projection [[buffer(MC_SLOT_PROJECTION)]],
    constant McChunkSection &section [[buffer(MC_SLOT_TRANSFORMS)]],
    constant McGlobals &globals [[buffer(MC_SLOT_GLOBALS)]],
    texture2d<float> lightMap [[texture(MC_SLOT_SAMPLER2)]],
    sampler lightSampler [[sampler(MC_SLOT_SAMPLER2)]]
#ifdef MC_WATER_FORWARD
    , uint vertexId [[vertex_id]]
    , device const WaterVertexMetadata *waterMetadata [[buffer(14)]]
    , constant WaterDraw &waterDraw [[buffer(15)]]
#endif
) {
    float3 relative = in.Position + float3(section.ChunkPosition - globals.CameraBlockPos) + globals.CameraOffset;
    float3 viewPos = (section.ModelViewMat * float4(relative, 1.0)).xyz;
    float2 uv2 = float2(in.UV2);

    GBufferVaryings out;
    out.position = mc_clip_position(projection.ProjMat * float4(viewPos, 1.0));
    out.worldPos = viewPos;
    out.normal = float3(0.0);
    out.tint = in.Color;
#ifdef MC_WATER_FORWARD
    uint localVertex = vertexId - waterDraw.baseVertex;
    WaterVertexMetadata metadata = localVertex < waterDraw.vertexCount
        ? waterMetadata[localVertex] : WaterVertexMetadata{float4(0.0), float4(0.0)};
    out.waterMaterial = metadata.normalMaterial.w;
    out.normal = metadata.normalMaterial.xyz;
    out.waterFlow = metadata.flow.xyz;
    if (waterDraw.debugMode == 1u && out.waterMaterial == 1.0) out.tint.rgb = float3(1.0, 0.0, 1.0);
#endif
    out.lightMapColor = mc_sample_lightmap(lightMap, lightSampler, uv2);
    out.uv = in.UV0;
    out.lightLevels = saturate(uv2 / 240.0);
    out.sphericalDistance = mc_fog_spherical_distance(relative);
    out.cylindricalDistance = mc_fog_cylindrical_distance(relative);
    return out;
}

/// Minecraft's texel-snapping atlas fetch, reproduced so colour zero still matches vanilla.
static inline float4 mc_sample_nearest(
    texture2d<float> atlas, sampler atlasSampler, float2 uv, float2 pixelSize,
    float2 du, float2 dv, float2 texelScreenSize
) {
    float2 uvTexelCoords = uv / pixelSize;
    float2 texelCenter = round(uvTexelCoords) - 0.5;
    float2 texelOffset = uvTexelCoords - texelCenter;
    texelOffset = (texelOffset - 0.5) * pixelSize / texelScreenSize + 0.5;
    texelOffset = clamp(texelOffset, 0.0, 1.0);
    return atlas.sample(atlasSampler, (texelCenter + texelOffset) * pixelSize, gradient2d(du, dv));
}

/// Minecraft's rotated-grid supersampled atlas fetch, for the same reason.
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

#ifdef MC_WATER_FORWARD
fragment float4 gbuffer_terrain_fragment(
#else
fragment GBufferTargets gbuffer_terrain_fragment(
#endif
    GBufferVaryings in [[stage_in]],
    constant McFog &fog [[buffer(MC_SLOT_FOG)]],
    constant McChunkSection &section [[buffer(MC_SLOT_TRANSFORMS)]],
    constant McGlobals &globals [[buffer(MC_SLOT_GLOBALS)]],
    texture2d<float> atlas [[texture(MC_SLOT_SAMPLER0)]],
    sampler atlasSampler [[sampler(MC_SLOT_SAMPLER0)]]
#ifdef MC_WATER_FORWARD
    , constant WaterFrameUniform &waterFrame [[buffer(13)]]
    , texture2d<float> opaqueColor [[texture(12)]]
    , depth2d<float> opaqueDepth [[texture(13)]]
    , constant WaterDraw &waterDraw [[buffer(15)]]
#endif
) {
#ifdef MC_WATER_FORWARD
    if (in.waterMaterial == 1.0 && waterDraw.debugMode >= 2u) {
        float2 extent = float2(float(opaqueDepth.get_width()), float(opaqueDepth.get_height()));
        float2 pixel = clamp(in.position.xy, float2(0.0), max(extent - 1.0, float2(0.0)));
        uint2 coord = uint2(pixel);
        float3 opaqueView = mc_water_view_from_device_depth(
            in.position.xy, opaqueDepth.read(coord), extent, waterFrame.inverseProjection
        );
        float encoded = waterDraw.debugMode == 2u
            ? mc_water_debug_depth_encode(in.worldPos)
            : mc_water_debug_depth_encode(opaqueView);
        float keepColor = opaqueColor.read(coord).x * 0.0;
        if (waterDraw.debugMode == 2u) {
            return float4(1.0, encoded + keepColor, 0.0, 1.0);
        }
        return float4(encoded + keepColor, 1.0, 0.0, 1.0);
    }
#endif
    float2 pixelSize = 1.0 / float2(section.TextureSize);
    float4 texel = globals.UseRgss == 1
        ? mc_sample_rgss(atlas, atlasSampler, in.uv, pixelSize)
        : mc_sample_nearest(atlas, atlasSampler, in.uv, pixelSize, dfdx(in.uv), dfdy(in.uv),
            sqrt(dfdx(in.uv) * dfdx(in.uv) + dfdy(in.uv) * dfdy(in.uv)));

    // The order below is vanilla's: the visibility fade changes alpha, so the cutout test has to
    // see the faded value or a chunk fading in would cut out differently than it does today.
    float4 shaded = texel * in.tint * in.lightMapColor;
    shaded = mc_chunk_fade(mc_scene_seed(shaded), section.ChunkVisibility, fog);
#if MC_HAS_ALPHA_CUTOUT
    if (shaded.a < MC_ALPHA_CUTOUT) {
        discard_fragment();
    }
#endif

#ifdef MC_WATER_FORWARD
    return mc_apply_fog(shaded, in.sphericalDistance, in.cylindricalDistance, fog);
#else
    GBufferTargets out;
    out.scene = mc_apply_fog(shaded, in.sphericalDistance, in.cylindricalDistance, fog);
    // Albedo is the surface before the light hits it, so the lightmap and the fog stay out of it.
    mc_write_gbuffer(
        out, (texel * in.tint).rgb, mc_reconstruct_normal(in.worldPos), in.lightLevels, max(0.0, -in.worldPos.z)
    );
    return out;
#endif
}

#endif // MC_PROGRAM_TERRAIN

// ================================================================================================
// Loose block models
// ================================================================================================

#if defined(MC_PASS_GBUFFER) && defined(MC_PROGRAM_BLOCK)

struct BlockVertex {
    float3 Position [[attribute(0)]];
    float4 Color    [[attribute(1)]];
    float2 UV0      [[attribute(2)]];
    short2 UV2      [[attribute(3)]];
};

vertex GBufferVaryings gbuffer_block_vertex(
    BlockVertex in [[stage_in]],
    constant McProjection &projection [[buffer(MC_SLOT_PROJECTION)]],
    constant McDynamicTransforms &transforms [[buffer(MC_SLOT_TRANSFORMS)]],
    texture2d<float> lightMap [[texture(MC_SLOT_SAMPLER2)]],
    sampler lightSampler [[sampler(MC_SLOT_SAMPLER2)]]
) {
    float3 modelPos = in.Position + transforms.ModelOffset;
    float3 viewPos = (transforms.ModelViewMat * float4(modelPos, 1.0)).xyz;
    float2 uv2 = float2(in.UV2);

    GBufferVaryings out;
    out.position = mc_clip_position(projection.ProjMat * float4(viewPos, 1.0));
    out.worldPos = viewPos;
    out.normal = float3(0.0);
    out.tint = in.Color;
    out.lightMapColor = mc_sample_lightmap(lightMap, lightSampler, uv2);
    out.uv = in.UV0;
    out.lightLevels = saturate(uv2 / 240.0);
    out.sphericalDistance = mc_fog_spherical_distance(modelPos);
    out.cylindricalDistance = mc_fog_cylindrical_distance(modelPos);
    return out;
}

fragment GBufferTargets gbuffer_block_fragment(
    GBufferVaryings in [[stage_in]],
    constant McFog &fog [[buffer(MC_SLOT_FOG)]],
    constant McDynamicTransforms &transforms [[buffer(MC_SLOT_TRANSFORMS)]],
    texture2d<float> atlas [[texture(MC_SLOT_SAMPLER0)]],
    sampler atlasSampler [[sampler(MC_SLOT_SAMPLER0)]]
) {
    float4 texel = atlas.sample(atlasSampler, in.uv);
    float4 shaded = texel * in.tint * in.lightMapColor * transforms.ColorModulator;
#if MC_HAS_ALPHA_CUTOUT
    if (shaded.a < MC_ALPHA_CUTOUT) {
        discard_fragment();
    }
#endif

    GBufferTargets out;
    out.scene = mc_apply_fog(mc_scene_seed(shaded), in.sphericalDistance, in.cylindricalDistance, fog);
    mc_write_gbuffer(
        out, (texel * in.tint * transforms.ColorModulator).rgb,
        mc_reconstruct_normal(in.worldPos), in.lightLevels, max(0.0, -in.worldPos.z)
    );
    return out;
}

#endif // MC_PROGRAM_BLOCK

// ================================================================================================
// Entities and block entities
// ================================================================================================

#if defined(MC_PASS_GBUFFER) && defined(MC_PROGRAM_ENTITY)

// The variant switches Minecraft compiles this program with. The engine refuses to substitute a
// pipeline carrying any switch not listed here, so every branch below has a counterpart in vanilla's
// entity.vsh and entity.fsh and nothing is silently approximated.
#if MC_DEFINE_PER_FACE_LIGHTING || !MC_DEFINE_NO_CARDINAL_LIGHTING
#define MC_ENTITY_CARDINAL_LIGHT 1
#else
#define MC_ENTITY_CARDINAL_LIGHT 0
#endif

struct EntityVertex {
    float3 Position [[attribute(0)]];
    float4 Color    [[attribute(1)]];
    float2 UV0      [[attribute(2)]];
    short2 UV1      [[attribute(3)]];
    short2 UV2      [[attribute(4)]];
    float4 Normal   [[attribute(5)]];
};

struct EntityVaryings {
    float4 position [[position]];
    float3 worldPos;
    float3 normal;
    /// The front-facing tint; under per-face lighting {@code tintBack} carries the other side.
    float4 tint;
    float4 tintBack;
    /// The vertex colour before any cardinal light, which is what the G-buffer's albedo wants.
    float4 baseTint;
    float4 lightMapColor;
    float4 overlayColor;
    float2 uv;
    float2 lightLevels;
    float sphericalDistance;
    float cylindricalDistance;
};

/// Minecraft's two-directional vertex light, folded into a colour exactly as light.glsl folds it.
static inline float4 mc_mix_light_separate(float2 light, float4 color) {
    float2 clamped = max(float2(0.0), light);
    float accumulated = min(1.0, (clamped.x + clamped.y) * 0.6 + 0.4);
    return float4(color.rgb * accumulated, color.a);
}

vertex EntityVaryings gbuffer_entity_vertex(
    EntityVertex in [[stage_in]],
    constant McProjection &projection [[buffer(MC_SLOT_PROJECTION)]],
    constant McDynamicTransforms &transforms [[buffer(MC_SLOT_TRANSFORMS)]]
#if MC_ENTITY_CARDINAL_LIGHT
    , constant McLighting &lighting [[buffer(MC_SLOT_LIGHTING)]]
#endif
#if !MC_DEFINE_NO_OVERLAY
    , texture2d<float> overlay [[texture(MC_SLOT_SAMPLER1)]]
#endif
#if !MC_DEFINE_EMISSIVE
    , texture2d<float> lightMap [[texture(MC_SLOT_SAMPLER2)]]
    , sampler lightSampler [[sampler(MC_SLOT_SAMPLER2)]]
#endif
) {
    float2 uv2 = float2(in.UV2);

    EntityVaryings out;
    float3 viewPos = (transforms.ModelViewMat * float4(in.Position, 1.0)).xyz;
    out.position = mc_clip_position(projection.ProjMat * float4(viewPos, 1.0));
    out.worldPos = viewPos;
    // The one world format that carries a real normal, so nothing has to be reconstructed here.
    float3x3 normalMatrix = float3x3(
        transforms.ModelViewMat[0].xyz,
        transforms.ModelViewMat[1].xyz,
        transforms.ModelViewMat[2].xyz
    );
    out.normal = normalize(normalMatrix * in.Normal.xyz);
    out.baseTint = in.Color;
    out.lightLevels = saturate(uv2 / 240.0);
    out.sphericalDistance = mc_fog_spherical_distance(in.Position);
    out.cylindricalDistance = mc_fog_cylindrical_distance(in.Position);

#if MC_DEFINE_APPLY_TEXTURE_MATRIX
    out.uv = (transforms.TextureMat * float4(in.UV0, 0.0, 1.0)).xy;
#else
    out.uv = in.UV0;
#endif

#if MC_DEFINE_PER_FACE_LIGHTING
    float2 cardinal = float2(dot(lighting.Light0_Direction, in.Normal.xyz), dot(lighting.Light1_Direction, in.Normal.xyz));
    out.tint = mc_mix_light_separate(cardinal, in.Color);
    out.tintBack = mc_mix_light_separate(-cardinal, in.Color);
#elif MC_ENTITY_CARDINAL_LIGHT
    float2 cardinal = float2(dot(lighting.Light0_Direction, in.Normal.xyz), dot(lighting.Light1_Direction, in.Normal.xyz));
    out.tint = mc_mix_light_separate(cardinal, in.Color);
    out.tintBack = out.tint;
#else
    out.tint = in.Color;
    out.tintBack = in.Color;
#endif

#if MC_DEFINE_EMISSIVE
    out.lightMapColor = float4(1.0);
#else
    out.lightMapColor = mc_sample_lightmap(lightMap, lightSampler, uv2);
#endif

#if MC_DEFINE_NO_OVERLAY
    out.overlayColor = float4(0.0);
#else
    // texelFetch, which in MSL is an unfiltered read of the 16x16 overlay at integer coordinates.
    out.overlayColor = overlay.read(uint2(clamp(int2(in.UV1), int2(0), int2(15))), 0);
#endif
    return out;
}

fragment GBufferTargets gbuffer_entity_fragment(
    EntityVaryings in [[stage_in]],
    bool frontFacing [[front_facing]],
    constant McFog &fog [[buffer(MC_SLOT_FOG)]],
    constant McDynamicTransforms &transforms [[buffer(MC_SLOT_TRANSFORMS)]],
    texture2d<float> atlas [[texture(MC_SLOT_SAMPLER0)]],
    sampler atlasSampler [[sampler(MC_SLOT_SAMPLER0)]]
) {
    float4 texel = atlas.sample(atlasSampler, in.uv);
#if MC_HAS_ALPHA_CUTOUT
    if (texel.a < MC_ALPHA_CUTOUT) {
        discard_fragment();
    }
#endif

#if MC_DEFINE_PER_FACE_LIGHTING
    // Winding is reversed against vanilla by the clip-space Y flip, so the facing test is too.
    float4 faceTint = frontFacing ? in.tintBack : in.tint;
#else
    float4 faceTint = in.tint;
#endif

    float4 shaded = texel * faceTint * transforms.ColorModulator;
    // Albedo is the surface before any light reaches it, so it keeps the texture, the vertex tint
    // and the damage/flash overlay, and drops the lightmap and the cardinal light that a deferred
    // resolve is going to supply for itself.
    float4 albedo = texel * in.baseTint * transforms.ColorModulator;
#if !MC_DEFINE_NO_OVERLAY
    shaded = float4(mix(in.overlayColor.rgb, shaded.rgb, in.overlayColor.a), shaded.a);
    albedo = float4(mix(in.overlayColor.rgb, albedo.rgb, in.overlayColor.a), albedo.a);
#endif
#if !MC_DEFINE_EMISSIVE
    shaded *= in.lightMapColor;
#endif

    GBufferTargets out;
    out.scene = mc_apply_fog(mc_scene_seed(shaded), in.sphericalDistance, in.cylindricalDistance, fog);
    float3 normal = length(in.normal) < 1e-8 ? mc_reconstruct_normal(in.worldPos) : normalize(in.normal);
    mc_write_gbuffer(out, albedo.rgb, normal, in.lightLevels, max(0.0, -in.worldPos.z));
    return out;
}

#endif // MC_PROGRAM_ENTITY
