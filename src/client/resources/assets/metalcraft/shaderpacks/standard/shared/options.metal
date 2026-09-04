#ifndef MC_SHARED_OPTIONS
#define MC_SHARED_OPTIONS
#include <metal_stdlib>
using namespace metal;

// Uniform-mode options in pack.json declaration order. 4-byte fields, no padding.
struct PackOptions {
    float exposure; // 0
    int tonemap;    // 4; 0 = none, 1 = aces
    int debugView;  // 8; 0 = off, 1 = scene
};
#endif
