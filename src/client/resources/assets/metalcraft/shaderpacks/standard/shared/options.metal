#ifndef MC_SHARED_OPTIONS
#define MC_SHARED_OPTIONS
#include <metal_stdlib>
using namespace metal;

// Uniform-mode options in pack.json declaration order. 4-byte fields, no padding.
struct PackOptions {
    float exposure;       // 0
    int tonemap;          // 4; 0 = none, 1 = aces
    int debugView;        // 8; 0=off 1=scene 2=albedo 3=normal 4=light 5=receiver 6=cascade 7=visibility 8=vanilla
    float shadowStrength; // 12; 1 = all sky-share energy is shadowable
};
#endif
