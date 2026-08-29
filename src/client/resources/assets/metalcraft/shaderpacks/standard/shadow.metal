// Four cascades in one layered depth pass. Each original draw is instanced four times by the
// backend; instance_id selects the cascade matrix and [[render_target_array_index]] selects its
// depth-array layer. Alpha-tested terrain and entities sample the same atlas as their world draw.

#include <metal_stdlib>
using namespace metal;

#ifdef MC_PASS_SHADOW

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

struct ShadowUniforms {
    float4x4 cascade[4];
    float4 splits;
    float4 lightDirectionAndNormalOffset;
    float4 mapSize;
};

struct ShadowVaryings {
    float4 position [[position]];
    float3 cameraViewPos;
    float3 normal;
    float2 uv;
    uint cascadeIndex [[flat]];
    uint layer [[render_target_array_index]];
};

struct ShadowDepth {
    float depth [[depth(any)]];
};

static inline ShadowVaryings mc_shadow_vertex(
    float3 cameraViewPos,
    float3 normal,
    float2 uv,
    uint instanceId,
    constant ShadowUniforms &shadow
) {
    uint cascade = instanceId & 3u;
    ShadowVaryings out;
    float4 clip = shadow.cascade[cascade] * float4(cameraViewPos, 1.0);
    // Direct MSL does not receive the GLSL-to-Metal Y conversion.
    out.position = float4(clip.x, -clip.y, clip.z, clip.w);
    out.cameraViewPos = cameraViewPos;
    out.normal = normal;
    out.uv = uv;
    out.cascadeIndex = cascade;
    out.layer = cascade;
    return out;
}

static inline float3 mc_shadow_reconstruct_normal(float3 position) {
    float3 normal = cross(dfdx(position), dfdy(position));
    float magnitude = length(normal);
    if (magnitude < 1e-8) return float3(0.0, 1.0, 0.0);
    normal /= magnitude;
    return dot(normal, position) > 0.0 ? -normal : normal;
}

static inline float mc_shadow_depth(
    ShadowVaryings in,
    constant ShadowUniforms &shadow
) {
    float3 normal = length(in.normal) < 1e-8
        ? mc_shadow_reconstruct_normal(in.cameraViewPos)
        : normalize(in.normal);
    float normalOffset = shadow.lightDirectionAndNormalOffset.w;
    // Move most at a grazing light angle and least when the surface faces the light. This is in
    // camera-view world units, so the offset remains consistent across cascade resolutions.
    float grazing = 1.0 - abs(dot(normal, shadow.lightDirectionAndNormalOffset.xyz));
    float3 offsetPosition = in.cameraViewPos + normal * (normalOffset * grazing);
    float4 clip = shadow.cascade[in.cascadeIndex] * float4(offsetPosition, 1.0);
    return saturate(clip.z / clip.w);
}

#endif

#if defined(MC_PASS_SHADOW) && defined(MC_PROGRAM_TERRAIN)

struct TerrainVertex {
    float3 Position [[attribute(0)]];
    float4 Color    [[attribute(1)]];
    float2 UV0      [[attribute(2)]];
    short2 UV2      [[attribute(3)]];
};

vertex ShadowVaryings shadow_terrain_vertex(
    TerrainVertex in [[stage_in]],
    uint instanceId [[instance_id]],
    constant McChunkSection &section [[buffer(MC_SLOT_TRANSFORMS)]],
    constant McGlobals &globals [[buffer(MC_SLOT_GLOBALS)]],
    constant ShadowUniforms &shadow [[buffer(MC_SLOT_METALCRAFTSHADOW)]]
) {
    float3 relative = in.Position + float3(section.ChunkPosition - globals.CameraBlockPos) + globals.CameraOffset;
    float3 cameraView = (section.ModelViewMat * float4(relative, 1.0)).xyz;
    return mc_shadow_vertex(cameraView, float3(0.0), in.UV0, instanceId, shadow);
}

fragment ShadowDepth shadow_terrain_fragment(
    ShadowVaryings in [[stage_in]],
    constant ShadowUniforms &shadow [[buffer(MC_SLOT_METALCRAFTSHADOW)]],
    texture2d<float> atlas [[texture(MC_SLOT_SAMPLER0)]],
    sampler atlasSampler [[sampler(MC_SLOT_SAMPLER0)]]
) {
#if MC_HAS_ALPHA_CUTOUT
    if (atlas.sample(atlasSampler, in.uv).a < MC_ALPHA_CUTOUT) discard_fragment();
#endif
    return {mc_shadow_depth(in, shadow)};
}

#endif

#if defined(MC_PASS_SHADOW) && defined(MC_PROGRAM_BLOCK)

struct BlockVertex {
    float3 Position [[attribute(0)]];
    float4 Color    [[attribute(1)]];
    float2 UV0      [[attribute(2)]];
    short2 UV2      [[attribute(3)]];
};

vertex ShadowVaryings shadow_block_vertex(
    BlockVertex in [[stage_in]],
    uint instanceId [[instance_id]],
    constant McDynamicTransforms &transforms [[buffer(MC_SLOT_TRANSFORMS)]],
    constant ShadowUniforms &shadow [[buffer(MC_SLOT_METALCRAFTSHADOW)]]
) {
    float3 position = in.Position + transforms.ModelOffset;
    float3 cameraView = (transforms.ModelViewMat * float4(position, 1.0)).xyz;
    return mc_shadow_vertex(cameraView, float3(0.0), in.UV0, instanceId, shadow);
}

fragment ShadowDepth shadow_block_fragment(
    ShadowVaryings in [[stage_in]],
    constant ShadowUniforms &shadow [[buffer(MC_SLOT_METALCRAFTSHADOW)]],
    texture2d<float> atlas [[texture(MC_SLOT_SAMPLER0)]],
    sampler atlasSampler [[sampler(MC_SLOT_SAMPLER0)]]
) {
#if MC_HAS_ALPHA_CUTOUT
    if (atlas.sample(atlasSampler, in.uv).a < MC_ALPHA_CUTOUT) discard_fragment();
#endif
    return {mc_shadow_depth(in, shadow)};
}

#endif

#if defined(MC_PASS_SHADOW) && defined(MC_PROGRAM_ENTITY)

struct EntityVertex {
    float3 Position [[attribute(0)]];
    float4 Color    [[attribute(1)]];
    float2 UV0      [[attribute(2)]];
    short2 UV1      [[attribute(3)]];
    short2 UV2      [[attribute(4)]];
    float4 Normal   [[attribute(5)]];
};

vertex ShadowVaryings shadow_entity_vertex(
    EntityVertex in [[stage_in]],
    uint instanceId [[instance_id]],
    constant McDynamicTransforms &transforms [[buffer(MC_SLOT_TRANSFORMS)]],
    constant ShadowUniforms &shadow [[buffer(MC_SLOT_METALCRAFTSHADOW)]]
) {
    float3 cameraView = (transforms.ModelViewMat * float4(in.Position, 1.0)).xyz;
    float3x3 normalMatrix = float3x3(
        transforms.ModelViewMat[0].xyz,
        transforms.ModelViewMat[1].xyz,
        transforms.ModelViewMat[2].xyz
    );
    float3 normal = normalize(normalMatrix * in.Normal.xyz);
#if MC_DEFINE_APPLY_TEXTURE_MATRIX
    float2 uv = (transforms.TextureMat * float4(in.UV0, 0.0, 1.0)).xy;
#else
    float2 uv = in.UV0;
#endif
    return mc_shadow_vertex(cameraView, normal, uv, instanceId, shadow);
}

fragment ShadowDepth shadow_entity_fragment(
    ShadowVaryings in [[stage_in]],
    constant ShadowUniforms &shadow [[buffer(MC_SLOT_METALCRAFTSHADOW)]],
    texture2d<float> atlas [[texture(MC_SLOT_SAMPLER0)]],
    sampler atlasSampler [[sampler(MC_SLOT_SAMPLER0)]]
) {
#if MC_HAS_ALPHA_CUTOUT
    if (atlas.sample(atlasSampler, in.uv).a < MC_ALPHA_CUTOUT) discard_fragment();
#endif
    return {mc_shadow_depth(in, shadow)};
}

#endif
