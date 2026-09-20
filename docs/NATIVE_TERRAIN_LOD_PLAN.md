# Native terrain surface LOD

## High-distance CPU and RAM cleanup — September 20

Owner: Codex. Status: implemented and validated, following the report that reduced pixel
resolution looks correct but does not improve game performance.

- [x] Replace eager high-distance render/dirty bookkeeping with lazy storage,
  preserving native slot indices, recycling, dirty tracking and mesh lifetime.
- [x] Page the visibility graph's sparse slot table to avoid large per-rebuild
  arrays; measure allocation and camera-movement work against native storage.
- [x] Reduce avoidable LOD scheduling/draw-submission work while keeping the
  accepted distant appearance and prompt near-detail restoration.
- [x] Pass deterministic concurrency/lifecycle/equivalence checks and a full build;
  use brief NORMAL-world, 128/16, Default/Metal checks for integration.

Scope: bookkeeping and renderer overhead. Whole loaded chunks, server generation,
initial tessellation and simulation are separate scaling costs; report measured
allocation/CPU improvements separately from whole-game FPS or total resident RAM.

Validation: the complete Metal-validation build passes in 20 seconds. Native
storage parity, actual dirty-state construction, edits, negative coordinates,
teleport recycling, concurrency and graph-array equivalence pass. The allocation
probe measures 69,747,864 → 25,000 bytes for empty dirty bookkeeping, and
6,340,720 → 24,824 bytes for an empty visibility table. A 4,356-resident-entry
camera-movement fixture measures 4.338 → 0.034 ms median.

Initial live attempts caught deferred native dirty-state positioning; creation now
initializes the node explicitly, with regression coverage using the actual native
class. The final 18.74-second NORMAL-world 128/16 route passes quarter-resolution
shading, toggle, resize, approach, retreat, radius expansion and world close.
It records 11,955 render entries and 18,185 dirty entries out of 1,585,176 logical
slots. Images were inspected; no Metal validation error occurred. Whole-game FPS
and fully loaded 128-distance RAM were not benchmarked. Travel can eventually
populate the ring; native chunk memory remains. [Evidence](evidence/high-distance-cleanup/README.md).

## Distant pixel resolution — September 20

Owner: Codex. Status: implemented and validated for the None/default Metal path. The current task requires actual reduced
shading resolution, beyond mesh simplification and atlas mip selection.

- [x] Render eligible distant solid terrain into half/quarter linear-resolution
  Metal targets, grouped by tier; retain full-resolution near terrain.
- [x] Reconstruct color with native full-resolution geometry coverage/depth and
  depth-checked fallback shading at discontinuities; restore on approach/zoom.
- [x] Verify resize, odd sizes, resource lifetime, disabled/unsupported fallback,
  and reduced pixel counts with Metal readback and the full build.
- [x] Run one short standard-world route at render distance 128 using Default/Metal;
  inspect images and record measured performance and remaining scaling limits.

Engine reference: Unreal's screen-percentage rendering reduces shaded pixel count
before reconstruction ([Epic documentation](https://dev.epicgames.com/documentation/unreal-engine/temporal-super-resolution-in-unreal-engine)). This design
uses spatial reconstruction without temporal history or motion-vector dependencies.
Native depth rasterization preserves terrain silhouettes and subsequent occlusion.


Validation: `MTL_DEBUG_LAYER=1 ./gradlew build` passes in 21 seconds on Apple M4 Max.
Metal readback proves half/quarter sample reuse at even, odd and 1×1 dimensions,
exact full-resolution sloped depth, absent/wrong-depth fallback, and retirement
before GPU completion. UV gradients are captured before divergent reconstruction
so fallback texture filtering remains defined. The 20.04-second NORMAL-world route
at 128/16, Threaded, Default/Metal confirms real 320×180 targets at 1280×720,
1279×719 resize, disable/reenable, approach, expanded native radius and level zero.
Images were inspected. The final derivative/guard hardening passed the GPU suite;
no second live run was needed.

The optional 4K actual-terrain-material probe uses equal mip floors, ten warmup
pairs and 31 alternating measured pairs, including target clear/store/load and
reconstruction. Quarter resolution with RGSS costs 0.1786 ms versus 0.2293 ms
(22.1% lower); half costs 0.2453 ms (7.0% higher). Non-RGSS is cheaper than either
reconstruction path. These results qualify a shader-work tradeoff, not a dense-128
FPS gain or a universal speedup. The independent pixel-resolution toggle permits
comparison without changing geometry. Other packs retain full-resolution shading;
no memoryless G-buffer/deferred-lighting or water contract is changed. Native
chunk loading, initial compilation, simulation and per-section draw submission
still scale with distance. [Evidence](evidence/native-pixel-lod/README.md).

## Large-distance performance follow-up — September 19

Owner: Codex. Status: implemented and validated. Changes target the active native chunk path;
legacy distant-cache rendering stays disabled.

- [x] Reduce solid-terrain texture sampling to half/quarter linear resolution with
  distance-selected native LOD, restore immediately on approach/zoom, retain cutout detail.
- [x] Compile distance/FOV thresholds once per settings change and reuse squared
  distances for selection/priority; preserve existing refinement scheduling.
- [x] Reduce geometry-worker allocation without changing output topology or materials.
- [x] Run CPU equivalence, Metal mip readback, full build, and one short NORMAL-world
  16/16, Threaded, Default/Metal restoration check. Report measured limits.


Validation: `MTL_DEBUG_LAYER=1 ./gradlew build` passes in 22 seconds; selection
matches 98,304 scalar decisions, eligibility matches 12,000 original-classifier
cases, and Metal readback verifies half/quarter mip floors plus restoration and
lifetime. One 17.89-second NORMAL-world, 16/16, Threaded, Default/Metal route
passes coarse textures, approach/refinement, retreat, radius expansion, level zero
and world close. Inspected images and the report are retained.

The paired CPU probe measures 45.3% lower selection time, 46.1–51.1% lower
geometry-worker time and 59.4–67.9% fewer worker allocated bytes, with identical
geometry output across five shapes and four tiers. These are isolated CPU savings;
full atlas storage, chunk loading/generation and the 256-chunk memory limit remain.
No dense-256 FPS gain is claimed. [Evidence and reproduction](evidence/native-lod-performance/README.md).

## Approach transition correction

Owner: Codex. Status: implemented and verified following coarse chunks persisting near the camera.

- [x] Make pending rebuilds target-aware so approaching can supersede stale coarse requests.
- [x] Reserve refinement capacity and prioritize nearer sections without cancelling equivalent work each frame.
- [x] Cover queue saturation/stale targets in regression checks and verify far-to-near native restoration in a short NORMAL-world, 16/16, Threaded, Metal run.

Pending entries now include the requested tier, so a finer target supersedes an
older coarse snapshot immediately, including when the currently installed mesh is
still native. Completed/recycled meshes are pruned every frame rather than waiting
for a visible-list revisit or the ten-second lost-request fallback. Coarsening
cannot consume the last 64 of the 128 tracked slots. The nearest eight refinements
are selected across all visible coarse sections, independently of visibility-list
order; coarsening scans at most 512 eligible list positions and requests at most
four rebuilds per frame. Equivalent in-flight refinement is allowed to finish.
This replaces the older fixed-head/rotating-only refinement scan.

Targeted `nativeTerrainLodSmoke` passes saturation, supersession, deduplication,
offscreen completion, late-upload, lost-job and reset regressions alongside geometry
and selection checks. The 17.54-second NORMAL-world, 16/16, Threaded, Default/Metal
route passes: 1,206 coarse indices return to exactly 4,950 native indices within
0.049 seconds of approaching in this run, remain native after settling, then
coarsen on retreat. Expanding the native-quality radius also restores native
geometry. [Inspected screenshots and report](evidence/lod-transitions/README.md).
`MTL_DEBUG_LAYER=1 ./gradlew build` passes in 20 seconds; `git diff --check` passes.

## Native quality distance control

Owner: Codex. Status: implemented; automated validation passed.

- [x] Add a persisted 1–256 chunk slider, default 4, to set the native-detail boundary.
- [x] Apply the boundary to live section selection and anchor normal-FOV LOD onset there;
  preserve level 0, the master toggle, zoom refinement, and asynchronous rebuilds.
- [x] Verify boundary changes, settings validation, and the full build.

`nativeQualityDistance` now controls the protected section-bound distance. At
70-degree FOV, the first coarse tier begins just outside that radius; the 0–5
setting controls progression beyond it. Zoom can refine farther sections, while
the native radius overrides all coarsening and hysteresis. Frame snapshots carry
the setting to both rebuild selection and worker-region tier assignment. Existing
bounded asynchronous rebuilds apply changes to already loaded sections.

Validation: all 256 radii pass boundary/onset, prior-tier restoration, zoom, master
toggle and level-zero checks. Increasing the radius never coarsens an existing
tier. Settings tests cover round trips, missing/malformed values and overflow-safe
clamping. `MTL_DEBUG_LAYER=1 ./gradlew build` passes in 22 seconds, including native
geometry and Metal regression checks; locale JSON and `git diff --check` pass.
No additional in-game run was performed for this control. Historical threshold and
screenshot measurements below predate this configurable onset.

## Active correction: geometric detail reduction

Owner: Codex. Status: implemented and visually verified following the user's report that LOD looks native.
The earlier flat-face merge reduced tessellation but retained every block silhouette;
its measurements below are historical evidence, not proof of geometric simplification.

- [x] Add distance-selected vertex clustering that removes small solid-terrain steps
  and changes silhouettes, with native section boundaries and protected model contacts.
- [x] Verify bounded output, degenerate/opposite-face removal, native fallback,
  boundary continuity, and actual vertex displacement on stepped terrain.
- [x] Verify a visible native/strong-LOD comparison in a short NORMAL-world,
  16/16, Threaded, Default/Metal run; record evidence and update the description.

The compiler now clusters eligible opaque unit-face vertices onto the selected
2/4/8/16-block grid. It removes collapsed triangles, cancels coincident opposite
faces, and packs surviving triangles into the native quad/index contract. Unlike
flat-face merging, this changes distant silhouettes and removes small stair steps.
All vertices on section boundaries are fixed. Unsupported solid quads and the full
bounding neighborhoods of non-solid quads pin their contacts; non-solid buffers are
not changed. Empty, overlapping/nonmanifold or unprofitable results fall back to
the earlier surface merger. Each section still uses one native uploaded mesh.
Coarsened sections conservatively expose all native occlusion connections, since
an omitted wall must not hide newly exposed terrain behind it.

Pure checks pass closed-surface edge balance for stepped slopes, ridges, cavities,
and a fixture touching the section boundary. A 994-quad staircase becomes 252/84/22/6
quads at grid sizes 2/4/8/16, with displaced vertices. The existing 720 surface-tier
fixtures and 0–5 selection/setting checks also pass. These are geometry counts,
not measured FPS gains. The original surface-only evidence below is superseded.

The 15.39-second NORMAL-world route at 16/16, Threaded, Default/Metal passes level
0 -> 5 -> 0 and exact native index-count restoration. Level 5 visibly turns a
one-block staircase into broad coarse steps. Its sampled section uses 972 indices
instead of 4,950 (80.36% fewer). The actual spectator camera FOV is 77 degrees and
selects an 8-block grid; an initial test incorrectly assumed the option's 70 degrees
was the effective camera FOV. The revised test uses the camera-selected tier.
[Inspected native/coarse screenshots and report](evidence/geometric-lod/README.md).

The first screenshot in the passing run was taken before the fixture appeared in
the rendered frame, so it is excluded from the visual comparison. The inspected
comparison uses the successful restored-native frame against the level-5 frame at
the same camera. The harness now includes additional initial settling time.

Final validation: `MTL_DEBUG_LAYER=1 ./gradlew build` passes in 19 seconds on
Apple M4 Max/macOS, including all new geometric checks and the existing Metal suite.
`git diff --check` passes. No net frame-time improvement is claimed.

## Historical surface-only implementation

Owner: Codex, September 18, 2026. Status: native surface LOD implemented and validated;
Standard shader-pack visual acceptance remains unqualified as noted below.

The extended native chunk pipeline remains authoritative. Build one distance-selected
mesh on its compiler worker and upload it through the ordinary Metal terrain path.
Reduce opaque surface tessellation and texture/light sampling in 2, 4, and 8 block
patches, retaining full detail near the camera. Preserve material boundaries, terrain
silhouettes, section edges, and unsupported models. This is surface LOD, not a
heightmap replacement, virtualized geometry system, or reduced-resolution framebuffer.

- [x] Trace native extraction, immutable region snapshots, worker compilation, mesh
  ownership, upload and dirty tracking. Legacy LOD replacement remains disabled.
- [x] Implement bounded worker surface reduction, distance/FOV selection, hysteresis,
  native rebuilds on movement, and an enabled-by-default setting.
- [x] Verify reductions and topology/material/buffer contracts with deterministic tests.
  `./gradlew nativeTerrainLodSmoke` passes: 256 surface quads become 124/76/64 at
  2/4/8-block tiers; 540 randomized tier cases retain exact oriented surface coverage,
  material bounds and byte-identical section edges. Duplicate faces, non-opaque alpha,
  custom geometry and excessive input preserve native fallback. Selection tests cover
  approach, zoom, hysteresis, disabled state and negative coordinates.
  `MTL_DEBUG_LAYER=1 ./gradlew build` passes on Apple M4 Max/macOS (final build 18 s), including
  native distance through 256, retained LOD fixtures and the existing Metal suite.
- [x] Run a short NORMAL-world check at 16/16, Threaded chunk builder, Default/Metal;
  verify native upload, movement/refinement, edits and toggle restoration. Record
  measured geometry savings separately from any unmeasured frame-time improvement.
  The final 26.15-second scenario passes native upload, edited/repaired surface,
  persisted toggle, 4 -> 2 zoom refinement, 4 -> 1 approach restoration to the exact
  original index count, retreat coarsening, and world close. The controlled stone
  surface uses 1,056 rather than 3,456 indices (69.44% fewer triangles). Across 843
  distant compiler invocations, 317,005 input quads become 215,480 (32.03% fewer).
  These are cumulative build counts, including rebuilds, not a frame-time benchmark.
  [Commands, report and inspected images](evidence/native-terrain-lod/README.md).

## Runtime bounds and behavior

- A minimum four-chunk radius always uses native geometry. At reduction level 3 and 70-degree FOV,
  initial coarsening begins approximately 110/221/442 blocks from section bounds
  for 2/4/8-block patches. Retention thresholds are 82/163/326 blocks; narrower FOV
  restores finer detail. Patches retain exact surface positions, not block-sized
  displacement error. Texture/light detail is deliberately approximate.
- Section edges remain unit quads, and merging never spans a hole or a material
  boundary. Native frustum/occlusion, shadow mesh consumers and section lifecycle
  continue using the installed ordinary mesh. The layer's entire upload buffer and
  index count shrink together; no alternative draw submission or GPU shader is used.
- Each worker processes at most 8,192 quads per eligible layer. Larger/unsupported
  output falls back to native geometry. A section retains one mesh, with transient
  replacement lifetime managed by Minecraft's native upload arena.
- Each frame checks at most 576 visible sections and requests at most four detail
  restorations plus four coarsenings. At most 128 LOD rebuild requests are tracked;
  pending requests are deduplicated and expire after ten seconds. Old meshes stay
  installed while replacements build; cancellation, unload and resource/world reset
  follow native ownership. Hidden sections refresh when they become visible.

## Validation limits

The None/default Metal image path passes visual inspection. Standard-pack uploads
also succeed, but its settled captures show black sky/opaque terrain **with LOD on
and off**. This route therefore does not establish Standard-pack visual acceptance;
the cause of that composition failure was not diagnosed here. No water/shader
implementation was changed to work around it. Both captures are retained.

The first live attempt had an incorrect expectation that FOV 30 would restore tier 1
at 232 blocks; the intended hysteresis selects tier 2. Correcting the assertion
allowed the route to pass. The subsequent settled capture extended only pack warmup
and an off comparison to investigate its black output.

This is surface LOD rather than voxel resampling or an Unreal Nanite equivalent.
Native chunk loading, generation, initial tessellation, and simulation memory costs
remain. Terrain silhouettes and cutout/translucent layers retain native detail;
irregular surfaces reduce less than broad surfaces. No net FPS gain or dense
256-chunk performance result is claimed.

Constraints: only SOLID output is eligible. Cutout and translucent meshes retain
their original bytes and ordering. No additional chunk generation, world snapshots,
GPU mesh cache, synchronous GPU wait, or full-distance live stress run is needed.

## Detail reduction control — September 18 follow-up

Owner: Codex. Status: complete.

- [x] Add a persisted 0–5 detail reduction control; 0 restores native meshes, 3
  preserves the existing behavior, and 5 uses the strongest surface reduction.
- [x] Apply selection changes through bounded native rebuilds and support larger
  patches without changing section-edge or material/coverage contracts.
- [x] Verify level selection, clamping/defaults, and larger-patch geometry; build
  the mod and document the control.

The keyboard-accessible cycle control sits below the terrain LOD toggle in MetalCraft
Settings. Changes persist as `nativeLodReduction`; missing/malformed values default
to 3 and integer values clamp to 0–5. Turning the master toggle off preserves the
chosen level but renders native detail. Level 0 also restores native detail without
disabling the master toggle. The selected level is adopted at extraction and workers
receive the resulting cell size through their existing immutable region snapshot.

| Level | Label | Distance weighting | Maximum cell grid |
| --- | --- | --- | --- |
| 0 | Native detail | Disabled | 1 block |
| 1 | Subtle | 0.5× | 2 blocks |
| 2 | Mild | 0.75× | 4 blocks |
| 3 | Balanced (default) | 1× | 8 blocks |
| 4 | Strong | 1.5× | 16 blocks |
| 5 | Extreme | 3× | 16 blocks |

Weighting multiplies the distance after FOV adjustment before applying the existing
hysteresis. Higher settings admit larger patches sooner, with the full-detail radius
unchanged. The 16-block grid reduces an uninterrupted 256-quad surface to 61 quads;
the largest actual merged patch is 14×14 because section-edge strips remain native.
Extreme remains constrained by surface outlines/material boundaries; it does not
replace terrain with coarse occupancy voxels or simplify translucent/cutout models.

Targeted validation: `./gradlew nativeTerrainLodSmoke` passes with 720 randomized
mesh-tier cases including the new 16-block grid, plus all six settings across 513
distances and five previous tiers. Checks cover monotonic strength, level 0, master
disable, near protection, caps, unchanged level-3 behavior, safe JSON recovery and
numeric round trips. The existing live harness now fixes level 3 during its original
route and restores the user's saved level afterward. No additional live world run
is needed for the standard cycle widget and pure selector/mesher changes.
`MTL_DEBUG_LAYER=1 ./gradlew build` passes on Apple M4 Max/macOS in 21 seconds,
including the new checks and the existing Metal validation suite. `git diff --check`
also passes.
