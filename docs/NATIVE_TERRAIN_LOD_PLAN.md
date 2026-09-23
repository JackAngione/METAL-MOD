# Native terrain surface LOD

## Adjustable distant model detail — September 23

Owner: /root. Status: implemented and validated. The active detached horizon has a
saved 1–5 detail control. Level 1 retains the prior adaptive 4/8/16-block cells;
levels 2 and 3 cap cells at 8 and 4 blocks, while levels 4 and 5 capture new
2-block and 1-block surface samples. This restores terrain silhouettes and more
local material variation without retaining full client chunks. These remain
height-envelope models, so caves, overhangs and block entities are not native.

- [x] Add a persistent terrain-menu control and map five levels to real horizon
  sampling and Metal mesh resolution.
- [x] Rebuild detached samples when detail changes; preserve bounded tickets,
  native handoff, edit refresh and GPU retirement.
- [x] Validate tier geometry, saved-value recovery, the full Metal build and one
  brief NORMAL-world 128/16 Default/Metal route with levels 1–5.

`./gradlew build --offline` passed, including the new 2-block and 1-block closed
mesh fixtures and settings-codec cases. A 31.50-second Metal-validation world route
passed every detail setting, source-chunk absence, edit refresh, native approach
and retreat, Standard water, disable/re-enable, resize and close. Inspected
screenshots show progressively finer terrain at levels 4 and 5. The route does
not benchmark full-horizon memory or frame rate; higher levels intentionally
increase sampling and mesh cost. Local evidence: `run/build/compact-horizon.json`
and `run/screenshots/0000_compact-horizon-none.png` through
`0004_compact-horizon-detail-5.png`.

## Model-only horizon and FPS correction — September 21

Owner: /root. Status: implemented and validated. Goal: simplify fluids like solid
shells, remove distant full-chunk residency where feasible, and verify submission gains.

- [x] Compile fluid envelopes directly, including water metadata and native detail restoration.
- [x] Decouple the distant model lifetime from full chunk data and bound native loading.
- [x] Reduce the measured section submission bottleneck and compare identical model buffers.
- [x] Pass geometry/ownership tests and brief NORMAL-world 128/16 Metal validation,
  including Standard water routing, handoff and measured performance.

The integrated singleplayer path now caps native client delivery/view storage at
quality distance + 3, leaving simulation unchanged. Distant columns are detached
height/material envelopes, grouped 8×8 for submission. At most 16 temporary sampler
tickets obtain source data; models survive after source chunks unload. Edits refresh
cached columns, movement drops out-of-range data, and disable/world close release
ownership. GPU retirement follows completed Metal submissions.

The 28.29-second final NORMAL 128/16, Default/Metal route passes absent-client-chunk
rendering, server edits, native approach/retreat, None/Standard water, falling-water
and underwater captures (inspected), resize, disable/re-enable and world close.
Native quality 4 produces effective native distance 7 and fewer than 500 full client
chunks. All 628 visible model columns and their uploaded buffers remain unchanged
through the diagnostic ABBA comparison: grouping reduces median CPU frame time
from 2.87 to 0.73 ms (74.6%) and command bytes/frame from 124,240 to 9,232 (92.6%).
This isolates fine-grained submission overhead, not a historical-build or fully
populated 128-distance gameplay FPS comparison. Multiplayer retains native delivery.

The full Metal-validation build passes, including 600 randomized whole-column
geometry cases and the earlier section/fluid checks. The prior direct-fluid route
also reduced its pond from 2,700 to 480 indices and restored native water exactly.
[Architecture, raw measurements, images and limits](evidence/compact-horizon/README.md).

## Shell performance investigation — September 21

Owner: /root. Status: investigation complete; major loading/fluid costs remain.

- [x] Census visible and resident native/shell meshes beyond the quality radius;
  profile a brief copied NORMAL-world run at 128/16 on Default/Metal.
- [x] Remove the confirmed per-draw annotation allocation overhead; retain an
  opt-in mesh/layer/chunk census for subsequent work.
- [x] Validate live annotation integration and the full Metal build; record
  attribution evidence and remaining limits.

No far native block meshes appeared in either stationary copied-save census.
However, at the final baseline sample far shells contained 581,604 solid indices
plus **10,549,368 unchanged translucent fluid indices**. The follow-up also counted
26,027 complete client world chunks, despite a native-quality distance of 10.
Quality distance controls meshes, not server chunk tickets or client block/light
storage. Native section traversal and draw submission remain CPU costs even for
tiny meshes. The baseline averaged 2.84 ms of GPU occupancy per captured frame,
against 17.42 ms median frame interval.

The draw constructor wrappers allocated varargs/boxed arguments per section/layer;
they now annotate the constructed object directly. Both 20-second 128/16 Metal
captures complete and the full Metal-validation build passes in 26 seconds. The
second capture overlapped the build, so its FPS is excluded from improvement claims.
Full chunk residency, lighting and fluid meshing were located but not redesigned
in this investigation. [Evidence and next implementation boundaries](evidence/shell-performance/README.md).

## Exterior shell rework — September 20

Owner: /root. Status: implemented and validated. This supersedes the geometric clustering path
below for terrain outside Native quality distance. The disabled historical disk
cache is not part of the active renderer.

- [x] Build bounded exterior envelopes directly from section snapshots, skipping
  ordinary block-model tessellation beyond the native boundary.
- [x] Include foliage/custom block silhouettes in the proxy, remove internal
  cavities and distant block entities, and preserve fluid rendering contracts.
- [x] Verify geometry bounds, hidden interiors, empty terrain, seams and detail
  restoration; run the full build and one brief NORMAL-world 128/16 Metal route.

Historical section-shell path (still used for native overlap and multiplayer):
the shell is partitioned into Minecraft's existing section upload units; it is
one installed solid mesh per section, not a retained block mesh or a second tier.
World chunk loading and simulation remain Minecraft-owned.

Validation: 320 randomized exterior-oracle fixtures pass, together with buried/
partial-neighbor coverage, fluid-prefix lifetime/index-width checks and all native
radius/zoom/disable selection checks. The complete Metal-validation build passes.
The final 20.58-second NORMAL-world 128/16, Threaded, Default/Metal route passes
approach/retreat, radius changes, level zero, lighting, resize and world close.
The sampled fixture drops from 4,950 to 342 indices (93.09%) and restores its exact
native mesh on approach. Images were inspected. Initial live diagnostics exposed
and fixed missing untinted-material handling; no FPS gain is inferred from geometry
counts. [Evidence and limits](evidence/shell-lod/README.md).

## Heap-pressure correction — September 20

Owner: Codex. Status: implemented and validated following real gameplay reaching
its 16 GiB heap limit and missing-chunk reports after the frame-pacing patch.

- [x] Roll back the frame-pacing patch's runtime changes and restore native rebuild intake.
- [x] Profile a copy of the user's latest world at 128/16 and a 16 GiB heap.
- [x] Replace whole lighting-map snapshot copies with isolated sharing of unchanged data.
- [x] Verify snapshot mutation isolation, removals, concurrency/publication and allocation
  scaling, then validate the real workload and renderer without increasing the heap.

JFR identified sky/block lighting-map snapshot cloning as the dominant allocation
source. A 30-second copied-save comparison at 3840×2160, Default/Metal, 128/16 and
`-Xmx16G` reduced sampled peak used heap from 15.56 to 8.49 GiB, GC pause time from
2.30 to 0.62 seconds, and worst frame from 380 to 130 ms. Average FPS was essentially
unchanged (30.4 vs 29.7); 1% low improved from 5.2 to 9.1. The optimized run rendered
4.4% more sections, so this is a loading/stutter comparison, not a fixed-workload FPS
claim or a long-session memory ceiling.

The full Metal-validation build and a 19.78-second NORMAL-world 128/16 route pass,
including live torch edits, sky restoration, LOD approach/retreat/disable, resize and
world close. Snapshot plus 64 edits at 262,144 entries allocates 98.1% less memory.
No view-dependent chunk eviction was introduced. [Evidence](evidence/lighting-heap/README.md).

## Gameplay frame pacing — September 20

Owner: Codex. Status: rolled back after user-reported missing chunks and no real FPS
improvement. Results below are historical, not evidence of a shipped improvement.

- [x] Bound distant compiler snapshot intake and queue growth; retain dirty work
  for later frames and prioritize nearby sections/player edits.
- [x] Remove repeated terrain matrix/enum allocations and redundant vertex binds;
  use Metal offset updates for successive uniforms in the same buffer.
- [x] Verify scheduling limits, GPU output/state transitions, full build, and a
  brief NORMAL-world 128/16 Default/Metal route with performance evidence.

Off-screen terrain is already frustum culled. This change limits pending snapshot
memory; it does not evict full world chunks or promise a fully loaded 128-distance
FPS gain. Immediate mesh eviction on camera turns would force remeshing.

Validation: full Metal-validation build passes in 20 seconds. A 32.70-second
NORMAL-world 128/16 route passes restoration/lifecycle checks. Its fixed 827-section
ABBA comparison measures 27.1% less command-submission CPU time, 33.1% fewer command
bytes and 15.0% lower median CPU frame time. Invalidation intake falls from 827 to
16 distant snapshots/frame; all 827 complete in each phase. Displayed FPS remains
about 120, limited by presentation. The earlier growing-world timing is excluded
from FPS comparison. No fully loaded 128-distance FPS or process-memory improvement
is claimed. [Evidence and reproduction](evidence/terrain-frame-work/README.md).

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
