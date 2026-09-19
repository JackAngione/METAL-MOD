# Geometric LOD correction — September 18, 2026

The earlier implementation only merged coplanar faces. It saved vertices but
preserved block-sized terrain silhouettes, making the visual effect weak even at
level 5. The new path clusters actual terrain vertices and removes collapsed
geometry, so small steps and small features disappear as selected detail decreases.

## Inspected comparison

Same camera, NORMAL generated world / seed `metalcraft`, 16 render and simulation,
Threaded chunk builder, Default -> Metal, None shader pack. A stone staircase is
placed above the normal generated terrain to make block-scale detail unambiguous.

- [Level 0 — native detail](level-0.png): original one-block staircase, restored after LOD.
- [Level 5 — extreme reduction](level-5.png): large coarse steps and visibly simpler geometry.

The tested section goes from **4,950 to 972 indices (80.36% fewer)** and returns to
exactly 4,950 when level 0 is restored. The live test confirms 165 geometric builds
and 132,434 displaced input vertex references. Those counts include other visible
sections and rebuilds. They are not a steady-state frame census or an FPS result.
[Raw report](report.json).

The successful route takes 15.39 seconds (26 seconds including startup) on Apple
M4 Max/macOS, Java 25, Minecraft 26.2 with Metal API validation enabled. Background
terrain was still loading, so compare the controlled staircase rather than treating
the full-frame terrain coverage as a quality or performance benchmark. Level 5 uses
an 8-block grid at the fixture under the actual 77-degree spectator camera FOV.

An earlier attempt timed out waiting for a hard-coded 4-block tier derived from
an assumed 70-degree effective FOV. The runtime actually selected tier 8; the
corrected test checks the camera-selected tier. In the successful run, the initial
native screenshot preceded visible fixture presentation. It is excluded; the
restored-native screenshot supplies the valid same-camera comparison. The harness
now gives the initial frame longer to settle. No additional live run was needed.

## Reproduction and checks

```sh
./gradlew nativeTerrainLodSmoke
MTL_DEBUG_LAYER=1 ./gradlew build
MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest \
  -PmetalGeometricLodTest=true --args='--graphicsBackend default'
```

The geometric smoke checks closed oriented-edge balance after clustering stepped
slopes, ridges, cavities and a section-boundary fixture. It also checks actual
vertex movement, profitable output, exact section perimeter, native/invalid-tier
fallback, protected contact geometry and input budgets. A synthetic 994-quad
staircase becomes 252/84/22/6 quads at grid sizes 2/4/8/16. Existing surface-merge,
setting recovery and selection/hysteresis checks also pass.
The final `MTL_DEBUG_LAYER=1 ./gradlew build` passes in 19 seconds, including the
new geometry checks and the existing Metal suite; `git diff --check` passes.

Only eligible opaque terrain is clustered. Section-boundary vertices and contacts
with unsupported, cutout and translucent geometry stay protected. Their source
buffers are unchanged. Ambiguous, empty or unprofitable reductions fall back to
surface merging/native output. Small material/shape features can intentionally
vanish at high reduction levels. Chunk loading, initial tessellation, simulation
and framebuffer resolution remain native. Standard-pack visual qualification and
net frame-time improvement are not established by this None-pack route.
