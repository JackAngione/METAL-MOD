#ifndef MC_SHADOWS_METAL
#define MC_SHADOWS_METAL
// Named bindings: shadow_frame -> MC_BUFFER_SHADOW_FRAME, shadow_map -> MC_TEX_SHADOW_MAP.
// The caller assigns slots; this contract does not reserve slots in vanilla geometry PSOs.
struct MCShadowFrame {
    float4x4 cameraRelativeToShadow[4];
    float4x4 inverseProjection;
    float4x4 viewToCameraRelative;
    float4 cascadeFar;
    float4 directionToSun;
    uint cascadeCount;
    float inverseResolution;
    float shadowDistance;
    float casterExtension;
};
// UV uses Metal's top-left texture origin; hardware depth is already in [0,1].
float3 mc_shadow_camera_relative(float2 uv, float depth, constant MCShadowFrame& frame) {
    float4 view = frame.inverseProjection * float4(uv.x * 2.0 - 1.0, 1.0 - uv.y * 2.0, depth, 1.0);
    return (frame.viewToCameraRelative * float4(view.xyz / view.w, 1.0)).xyz;
}
#endif
