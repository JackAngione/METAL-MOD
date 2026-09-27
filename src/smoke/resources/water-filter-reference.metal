// Frozen pre-optimization Standard water kernels from 14f9896. Shared noise/warp helpers
// remain production code; only the two filtered loops are kept here as an independent oracle.

static inline float3 mc_water_reference_detail_height_gradient(
    float2 surface, float footprint, int quality, float time, bool horizontal, McWaterCurrentWarp current
) {
    float2 clumpVelocity = horizontal ? float2(0.25,0.0) : float2(0,-0.5);
    float3 clump = mc_water_geometric_clumps_gradient(
        (surface + current.offset * 0.6 - clumpVelocity * time) * 0.25);
    float envelope = 0.5 + clump.x;
    float2 envelopeGradient = mc_water_warped_gradient(clump.yz * 0.25, current, 0.6);
    const float slopes[7] = {0.32, 0.26, 0.19, 0.14, 0.10, 0.075, 0.05};
    const float2 axes[7] = {float2(1,2), float2(2,-1), float2(2,3),
        float2(-3,2), float2(1,-3), float2(3,1), float2(-2,-3)};
    int bands = quality <= 0 ? 0 : min(quality, 3) * 2 + 1;
    float frequency = 0.25;
    float3 result = float3(0);
    for (int band = 0; band < bands; ++band) {
        // Distinct integer rotations preserve the 256-block repeat without stacking grids.
        float2 axisU = axes[band];
        float2 axisV = float2(-axisU.y,axisU.x);
        float axisLength = length(axisU);
        float warpAmount = 1.0 - 0.08 * float(band);
        float3 velocity = mc_water_pattern_velocity(horizontal ? float3(0,1,0) : float3(1,0,0), band);
        float2 packet = surface + current.offset * warpAmount
            - (horizontal ? velocity.xz : velocity.zy) * time;
        float2 uv = float2(dot(packet,axisU),dot(packet,axisV)) * frequency;
        float2 sample = uv + float2(7,13)*float(band);
        // Small ripples need independently jittered circular centers. A four-corner
        // lattice remains legible as tiny squares even with radial interpolation.
        float3 noise = band < 2 ? mc_water_height_noise_gradient(sample)
            : mc_water_geometric_clumps_gradient(sample);
        float centered = 2.0*noise.x-1.0;
        float rounded = sqrt(centered*centered+0.04);
        float height = 1.0-rounded;
        float2 gradient = (-2.0*centered/rounded) * noise.yz;
        float weight = 1.0-smoothstep(0.15,0.45,footprint*frequency*axisLength);
        float amplitude = slopes[band] * weight / (frequency*axisLength);
        float2 worldGradient = mc_water_warped_gradient(
            (axisU*gradient.x + axisV*gradient.y)*frequency, current, warpAmount);
        result.x += amplitude*envelope*height;
        result.yz += amplitude*(envelope*worldGradient + height*envelopeGradient);
        frequency *= 2.0;
    }
    return result;
}

static inline float2 mc_water_reference_distant_slope(
    float2 surface, float time, float footprint, McWaterCurrentWarp current
) {
    float2 slope = float2(0);
    float frequency = 1.0 / 64.0;
    const float2 axes[3] = {float2(1,2), float2(2,-1), float2(2,3)};
    for (int band = 0; band < 3; ++band) {
        float2 axisU = axes[band];
        float2 axisV = float2(-axisU.y,axisU.x);
        float axisLength = length(axisU);
        float warpAmount = 1.0 - 0.08 * float(band);
        float2 packet = surface + current.offset * warpAmount
            - mc_water_pattern_velocity(float3(0,1,0),band).xz * time;
        float2 uv = float2(dot(packet,axisU),dot(packet,axisV)) * frequency;
        float3 noise = mc_water_height_noise_gradient(uv + float2(7,13)*float(band),4);
        float centered = 2.0*noise.x-1.0;
        float2 gradient = (-2.0*centered/sqrt(centered*centered+0.04)) * noise.yz;
        float weight = 1.0-smoothstep(0.15,0.45,footprint*frequency*axisLength);
        slope += mc_water_warped_gradient(
            (axisU*gradient.x + axisV*gradient.y) * (0.16*weight/axisLength),
            current, warpAmount);
        frequency *= 2.0;
    }
    return slope;
}
