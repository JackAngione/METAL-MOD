#ifndef MC_SHARED_OPTIONS
#define MC_SHARED_OPTIONS
#include <metal_stdlib>
using namespace metal;

// Defaults also support standalone shader fixtures without a pack preamble.
#ifndef MC_OPTION_WATER_ENABLED
#define MC_OPTION_WATER_ENABLED 1
#endif
#ifndef MC_OPTION_WATER_WAVE_STRENGTH
#define MC_OPTION_WATER_WAVE_STRENGTH 1.0
#endif
#ifndef MC_OPTION_WATER_REFRACTION_STRENGTH
#define MC_OPTION_WATER_REFRACTION_STRENGTH 1.0
#endif
#ifndef MC_OPTION_WATER_ABSORPTION
#define MC_OPTION_WATER_ABSORPTION 1.0
#endif
#ifndef MC_OPTION_WATER_FOAM
#define MC_OPTION_WATER_FOAM 1.0
#endif
#ifndef MC_OPTION_WATER_UNDERWATER_DISTORTION
#define MC_OPTION_WATER_UNDERWATER_DISTORTION 1.0
#endif
#ifndef MC_OPTION_WATER_REFLECTION_QUALITY
#define MC_OPTION_WATER_REFLECTION_QUALITY 1
#endif

// Uniform-mode options in pack.json declaration order. 4-byte fields, no padding.
struct PackOptions {
    float exposure;       // 0
    int tonemap;          // 4; 0 = none, 1 = aces
    int debugView;        // 8; 0=off 1=scene 2=albedo 3=normal 4=light 5=receiver 6=cascade 7=visibility 8=vanilla
    float shadowStrength; // 12; 1 = all sky-share energy is shadowable
};
#endif
