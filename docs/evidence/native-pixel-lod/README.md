# Native distant pixel resolution — September 20, 2026

> **Superseded (2026-09-26):** historical record of a removed LOD prototype. The current system is
> described in [the distant terrain evidence](../distant-terrain/README.md).

The active native terrain path renders eligible SOLID draws into two cached Metal
color/D32 targets: ceil(scene extent / 2) for geometry tier 2, and
ceil(scene extent / 4) for tiers 4–16. The existing camera/FOV/native-radius policy
restores texture and pixel detail immediately, even while a mesh rebuild is pending.
These are actual smaller attachments with smaller Metal viewports, beyond atlas mip
selection. Target dimensions are relative to the current scene, so global half
resolution composes naturally. No additional terrain snapshots or mesh tiers are kept.

This adapts the reduced-render-resolution/reconstruction principle documented by
[Epic](https://dev.epicgames.com/documentation/unreal-engine/temporal-super-resolution-in-unreal-engine),
using spatial reconstruction and Metal. It does not implement Unreal TSR, Nanite,
or temporal history. Grouping by resolution keeps the cost to at most two shading
passes per solid batch, using the existing batched command stream.

The ordinary terrain mesh supplies full-resolution coverage and depth. Its fragment
program checks the low-resolution sample against the primitive's extrapolated depth
plane, then reuses that color. Missing samples and depth discontinuities run the
original material. UV derivatives are evaluated before the divergent branch.
Low-resolution depth is never copied into scene depth. This avoids enlarged foreground
occluders, thin-feature holes, and changes to subsequent depth consumers.

The persisted **Distant Pixel Resolution** control is enabled by default. Disabling
it restores the previous pixel path immediately without changing geometry. The
master terrain-LOD toggle and reduction level 0 also restore native shading. Only
the verified vanilla solid pipeline with one stored color attachment and D32 is
eligible. Other shader packs (including Standard), custom resources, cutouts,
translucency, scissored passes and HDR/memoryless deferred passes retain the existing
path. The tooltip states the None-pack requirement.

## Correctness

`MTL_DEBUG_LAYER=1 ./gradlew build` passes in 21 seconds on Apple M4 Max/macOS.
[Build log](build.log). The added GPU fixtures cover actual vanilla pipeline
compilation, translucent rejection, target/pipeline reuse, even/odd/tiny dimensions,
actual coarse color values, exact full-resolution sloped depth, absent and wrong
coarse depths, and resource retirement after encoding but before GPU completion.
The full existing Metal/shader/native-LOD suite passes as well.

One 20.04-second NORMAL-generated-world route (`metalcraft` seed), render distance
128, simulation 16, Threaded builder, Default backend confirmed as Metal, None pack,
verifies the live path. It records 28,604 cumulative quarter-band draws at the first
capture (not draws in one frame), into 320×180 for a 1280×720 scene. The 1279×719
resize uses ceil-rounded 320×180. Disabling stops reduced draws without rebuilding;
reenabling resumes. Approach restores native geometry in this observation in
0.123 seconds; retreat, native-radius expansion, level zero and world close pass.
[Report](live-report.json).

Inspected [quarter shading](quarter.png), [full shading with the same coarse mesh](full.png),
[odd resize](resized.png), and [restored native geometry](native-restored.png).
The staircase's coverage is stable while interior shading is coarser. Terrain is
still loading between captures; these images are correctness evidence, not a dense
128-distance benchmark. No extra long live run was used. Final UV-derivative and
conservative pipeline/scissor guards were validated in the GPU suite afterward.

## GPU material-cost probe

The optional `terrainResolutionBenchmark` renders a covered sloped plane at
3840×2160 with the actual vanilla terrain fragment source, procedural atlas data,
nearest filtering, anisotropy 1, and the same tier-specific mip floor on both sides.
It includes initial scene clear/store, reduced shading, and reconstruction/load/store
inside one measured GPU command-buffer span. It alternates order, discards ten
warmup pairs, and reports medians of 31 measured pairs per case. It has no game
world, chunk generation, many-section vertex workload or CPU draw-submission cost.

| Material | Linear scale | Ordinary | Reduced + reconstruction | Change |
| --- | ---: | ---: | ---: | ---: |
| Non-RGSS | 1/2 | 0.1188 ms | 0.2420 ms | +103.8% |
| Non-RGSS | 1/4 | 0.0901 ms | 0.1643 ms | +82.2% |
| RGSS | 1/2 | 0.2293 ms | 0.2453 ms | +7.0% |
| RGSS | 1/4 | 0.2293 ms | 0.1786 ms | −22.1% |

[Raw samples](gpu-probe.log). This establishes a benefit for the expensive material
at quarter resolution, and a cost for cheap materials and half resolution. It does
**not** establish higher whole-game FPS at 128 chunks. Geometry is rasterized twice
for distant sections; CPU-limited, low-coverage or cheap-shader scenes may be slower.
A first probe omitted the already-existing mip floors and overstated savings; it
is superseded by this matched-mip probe. No speedup from that first probe is claimed.

Full chunk loading, generation, initial tessellation and storage remain proportional
to loaded terrain. This change does not reduce the shared atlas allocation or claim
dense 256-distance performance. A deferred-pack version requires its own attachment
and lighting integration; those packs intentionally stay on their existing path.

```sh
MTL_DEBUG_LAYER=1 ./gradlew build
./gradlew terrainResolutionBenchmark
MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest \
  -PmetalGeometricLodTest=true -PmetalTerrainResolutionTest=true \
  -PmetalJvmArgs='-Xmx12G' --args='--graphicsBackend default'
```
