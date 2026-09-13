# LOD feature implementation plan

Status: P1–P5 complete for conservative loaded geometry and composition compatibility.
P6 is complete in the plan-authorized full-resolution reduced-lighting scope;
half/quarter pixel-resolution shading remains deferred.
P7 functional cache acceptance is complete. P8 is complete for the opt-in explored-terrain preview.
The loaded 16-case matrix and full 48-capture explored-horizon matrix pass execution.
The explored horizon stays within the provisional median/p99 budgets; loaded
geometry does not meet its former speedup objective. Following the user's request,
P8 now qualifies an opt-in explored-terrain preview with explicit costs and limits.
The preview is available in Metal settings without a development flag,
keeps LOD off by default, and leaves reduced-resolution shading unavailable.
The complete preparation-cost probe and two disabled-overhead pairs pass. On
2026-09-12 the user explicitly waived the remaining six long baseline pairs and
the final idle-path horizon rerun. These are skipped checks, not passing measurements;
no further long runs are required to close P8.

Branch: `codex/LOD-feature`, created from `codex/water-effects` at
`e0c116931908dc6747117348bce88998971b8752`. Existing uncommitted water work was
preserved in the checkout; it is not part of that base commit.

## Goal and scope

Keep nearby blocks sharp while progressively reducing distant terrain geometry,
texture detail, and shading work. Target high pixel resolutions and extreme terrain
view distances with bounded CPU, GPU, and memory cost. Treat these as two separate
benchmark outcomes: a 4K/Retina framebuffer and a long terrain horizon stress
different parts of the renderer. Performance gains are acceptance targets, not promises.

Use direct Apple Metal for every new rendering feature. Keep Minecraft render and
simulation distances at **16 chunks**, Graphics API at **Default**, and verify Metal
is active. A separate LOD horizon setting may extend visual terrain beyond those
16 chunks without extending simulation or entity loading. All live testing uses a
standard generated world, never a flat world.

Deliver in three stages:

1. Geometry LOD within available terrain, with full-resolution near rendering.
2. Distance-dependent shading resolution, enabled only after its complete frame
   cost and image quality pass validation.
3. A persistent distant-terrain hierarchy for a 32–256 chunk visual horizon.
   Unknown terrain remains absent/fogged until data is available.

## Repository integration points

These are inspected integration candidates, not completed LOD APIs:

| Existing code | Planned responsibility |
| --- | --- |
| `mixin/ChunkSectionsToRenderMixin.java` | Find the per-section selection seam before terrain draw submission; the current render-pass redirect alone cannot select geometry LOD. |
| `mixin/ViewAreaAccessor.java` | Access loaded render sections; do not mistake this storage for a distant-world database. |
| `shader/WorldGeometryAdapter.java` | Preserve terrain pipeline substitution and G-buffer/forward routing. |
| `shader/WorldComposition.java` | Integrate distant color/depth before translucent world composition and before hand/HUD. |
| `shader/world/TerrainShadowRenderer.java` | Integrate independently selected shadow LOD after camera geometry is stable. |
| `shader/ShaderTargetAllocator.java`, `metal/MetalRenderPass.java` | Allocate and encode optional reduced-resolution distant targets. |
| `metal/MetalTimestampQueryPool.java` | Measure GPU cost asynchronously. |
| `MetalCraftConfig.java`, `gui/MetalCraftOptionsScreen.java` | Persist renderer-wide LOD settings and link a dedicated submenu. |
| `MetalCraftRenderResolution.java` | Account for existing global half-resolution mode when calculating effective pixel density. |
| `test/MetalBenchmarkScene.java`, `test/MetalFrameMetrics.java` | Extend standard-world performance measurements. |

Paths in this table are relative to `src/client/java/dev/metalcraft/client/`.
Localization belongs in `src/client/resources/assets/metalcraft/lang/en_us.json`.

## Geometry and selection design

Introduce a renderer-owned `client/lod/` module with immutable settings, terrain
snapshots, hierarchy construction, selection, cache management, and draw submission.
Keep Minecraft hooks thin and keep simplification independent of GPU allocation.

- LOD 0 uses the existing block mesh. Higher tiers start with 2, 4, 8, and 16 block
  cells. Distant hierarchy nodes combine neighboring regions so extending the
  horizon does not retain a full-resolution mesh or draw call for every section.
- Prototype occupancy/material-aware voxel aggregation and exposed-face merging.
  Preserve meaningful material boundaries, biome tint, lighting, cave openings,
  overhangs, and coastlines. A single height per column is insufficient for general
  Minecraft terrain. Record approximation error and bounds per node.
- Unknown/custom block models, thin geometry, and transparency require explicit
  policies. Initially keep unsupported loaded sections on their existing meshes;
  do not silently turn fences, leaves, glass, or fluids into opaque cubes. Add
  conservative distant proxies only after representative visual checks pass.
- Select the coarsest tier whose projected error is below the configured pixel
  tolerance, outside a full-detail radius. Estimate pixel error as
  `worldError * viewportHeight / (2 * tan(verticalFov / 2) * nearestDepth)`.
  Use conservative node bounds, clamp near-plane cases to full detail, and include
  FOV/zoom and the actual scene dimensions. Doubling distance approximately permits
  doubling cell size; a distance-only switch must not override silhouette error.
- Add 15–25% selection hysteresis and bounded transitions. Neighboring nodes differ
  by at most one tier; use explicit transition meshes for shared boundaries.
  Skirts alone cannot guarantee correct caves, cliffs, or translucent boundaries.
  Keep exactly one surface owner outside brief, depth-safe transition intervals.
- Apply frustum culling first. Defer GPU occlusion/indirect selection until profiling
  shows CPU selection or submission is a bottleneck; preserve conservative visibility.

## Data, scheduling, and ownership

Capture immutable terrain data through supported chunk lifecycle hooks. Worker
threads build LOD nodes without touching mutable world state. Include world/session,
dimension, region position, terrain revision, and resource generation in work IDs.
Discard stale jobs after edits, unloads, teleports, reloads, and dimension changes.
Invalidate ancestor nodes and neighboring seams when blocks, light, biome data, or
fluids change. Rebuild from current data rather than retaining stale nearby geometry.

Use separate bounded build queues, upload bytes per frame, GPU mesh memory, and CPU
snapshot/cache budgets. Prioritize visible near nodes and missing parents. Keep the
previous valid tier until its replacement is uploaded; choose an available ancestor
when a distant child is missing. If no valid representation exists, use the ordinary
loaded mesh or leave unknown distant terrain fogged. Never block the render thread
waiting for a worker or GPU readback. Retire replaced buffers through the existing
command-buffer lifetime system after in-flight use ends.

For the extended horizon, persist compressed hierarchy nodes from terrain the client
has actually received. Key caches by server/world identity and dimension, with format
and material versions; bound disk usage with eviction and validate atomic writes.
Test disconnect/reconnect, corrupted entries, and edits after revisiting cached areas.
Show that cached distant terrain may be stale until revisited. Multiplayer cannot
invent unseen server chunks. Automatic world generation and a server streaming
extension are outside this initial feature; explored data is sufficient for stage 3.

## Texture and shading resolution

Use atlas-safe mip selection for ordinary texture minification. Verify atlas gutters,
cutout coverage, biome colors, and resource-pack reloads; texture mipmaps alone do not
reduce geometry count or full-resolution fragment invocation count.

After geometry LOD works, prototype nonoverlapping near/middle/far shading bands with
1×, 1/2×, and 1/4× linear target resolution relative to the current scene. Half linear
resolution means one quarter of the target pixels, not one half. Distance bands are
quantized approximations to proportional detail loss and need hysteresis too.

The prototype must establish an explicit depth contract: reduced-resolution depth
cannot be blindly copied to the scene attachment. Use conservative full-resolution
coverage/depth for distant opaque proxies and depth/normal-aware color reconstruction,
then compose near opaque geometry and resolve the final world depth before existing
translucent consumers. Validate projection conventions, silhouette pixels,
disocclusions, and foreground intersections. Measure the depth pass, extra target
stores, reconstruction, and lost pass merging in the total cost. If those costs erase
the savings, retain full-resolution rasterization and reduced distant material work
while revising this stage; do not claim pixel-resolution LOD has shipped.

Initially leave translucent geometry and unsupported shader packs on the established
path. Geometry LOD must work with both no pack and the standard pack; advertise pack
capabilities before enabling multiresolution composition. Respect the existing color
contract and world-only post-processing boundary. Hand, UI, particles, and entities
must not accidentally inherit terrain LOD. Existing half-resolution mode remains a
separate global setting; display the resulting effective distant scale.

Any implementation or validation involving water effects must first read
`docs/WATER_EFFECTS_PLAN.md`, claim its relevant task, and record evidence there in
the same change. Do not bypass water identity, refraction depth, or transparency
contracts when replacing terrain meshes.

## Settings submenu

Add **Video Settings → MetalCraft Settings → Level of Detail…**, implemented as
`MetalCraftLodOptionsScreen`. Use translated labels, tooltips, a scrollable layout,
keyboard navigation, Done/back navigation, and Reset to Defaults. Settings belong
in `metalcraft.json`, independently of the selected shader pack.

Initial tuning values below are proposals to calibrate during benchmarks:

| Control | Initial behavior / range |
| --- | --- |
| Enable terrain LOD | Off until release acceptance passes; restores ordinary rendering when disabled. |
| Quality preset | Quality / Balanced / Performance / Custom; Balanced selected initially. |
| Full-detail radius | 2–12 chunks, initially 4; near-plane and error safeguards still apply. |
| Geometry error tolerance | 0.5–8 scene pixels; Quality 1, Balanced 2, Performance 4. |
| Distant shading | Full / Half / Quarter / Auto; Full until stage 2 passes. |
| LOD horizon | 16 / 32 / 64 / 128 / 256 chunks; initially 16, extended values enabled only with stage 3. |
| Transition smoothing | On initially; retain crack prevention even when smoothing is off. |
| Advanced: mesh memory budget | Auto or 128–2048 MiB; Auto is bounded using device working-set information and total renderer usage. |
| Advanced: background work | Low / Balanced / High; bound build concurrency and upload work separately internally. |
| Advanced: disk cache | On for extended terrain; 0.5–8 GiB cap, initially 2 GiB; clear-cache action. |
| Diagnostics | Tier overlay, triangles/draws, GPU time, memory, queue depth, cache coverage; off initially. |

Disable unavailable controls with a reason, including inactive Metal or incompatible
pack composition. Do not offer nonfunctional toggles before their stage lands.
Changing a preset updates its owned controls; editing one switches to Custom. Clamp
malformed values and migrate missing fields safely. Apply changes through an immutable
frame-boundary snapshot, coalesce rebuild requests, and asynchronously retire old
resources. Changing horizon never changes Minecraft render/simulation settings.

## Implementation checklist and acceptance

Progress protocol: claim a task with owner/status before implementation; record
partial progress or blockers beneath it; check it off only with validation evidence
(commands, machine, scenario, and artifacts). Update this plan in the same change.
P1–P8 are complete within their explicitly recorded scopes, including the user-directed
P8 validation waiver below.
Earlier partial-progress entries below are retained as dated evidence, not current blockers.

Continuation claimed by Codex (2026-09-12): P6 implementation and P8 release
qualification. Investigate a lower-overhead Metal shading design against the existing
paired cost/image fixtures, repair steady-state benchmark qualification, and collect
the missing release measurements. Both checkboxes stay open until their acceptance
criteria pass; prior rejected prototypes are evidence, not a completed implementation.

Continuation claimed by `/root` (2026-09-11): finish P7 cache ownership, global
eviction, cancellation, visible scheduling and the generated-world horizon route;
then evaluate P6 against its measured cost and image gates. Required validation
remains unchecked until results are recorded. P8 release tuning is separate.

Continuation checkpoint: root-wide disk accounting and exclusive process locking,
ancestor-first invalidation, pending-edit exclusion, visible result priority,
unsupported-capture revocation and device shutdown cleanup are implemented.
`lodSmoke` passes persistence, corruption, namespace, global budget, clear/close
and stale-worker fixtures. The initial horizon route exposed teleport invalidation
overflow clearing unrelated explored data. A minimized 4,097-new-section fixture
fails with diskBytes=0/dropped=1 before the fix and passes afterward (3 s).
Never-cached dirty sections are now ignored after namespace initialization.
The subsequent live route also exposed missing behind-camera recapture and a startup
dirty storm before disk inventory completed. Bounded existing-section extraction
repairs the first; deferring initial invalidation until inventory repairs the second.
The 4,097-unknown-section startup fixture fails before and passes after that fix.
Final live checks remain in progress at this checkpoint.

- [x] P0 — Inspect terrain/render/settings seams, create branch from water effects,
  and document staged design. Evidence: branch base SHA above and inspected source
  paths listed in this document. No runtime behavior changed or performance measured.
- [x] P1 — Baseline and integration audit. Identify exact mapped chunk snapshot,
  invalidation, draw-selection, and depth-composition hooks; record pack capabilities.
  Capture LOD-off CPU/GPU/memory baselines and screenshots on a fixed standard-world
  route at 16/16, Default/Metal. Acceptance: reproducible populated-world captures and
  pass-level timings, with known bottlenecks and selected hook contracts documented.
  - Owner: Codex. Status: complete (2026-09-10). Source-traced snapshot, invalidation,
    batching and composition contracts, pack capability limits, and reproducible
    standard-world 16/16 Default/Metal captures are documented in the audit.
  - Partial evidence: [mapped interface audit](LOD_INTEGRATION_AUDIT.md) records
    snapshot isolation, invalidation and pre-batching selection candidates.
    `./gradlew build` passed including shader smoke checks on M4 Max/64 GB.
    The 4K/16/16 Default benchmark confirmed Metal at startup but was stopped
    because another task launched a client in the same checkout. Shared launch
    artifacts/logs and GPU contention invalidated that attempt. P1 was left unchecked
    until the isolated captures below completed with exclusive GPU use.
  - Subsequent isolated runs completed: [source audit and measurements](LOD_INTEGRATION_AUDIT.md).
    Route version 2 verifies actual pitch and identical repeat endpoints, includes
    CPU percentiles, command-buffer GPU means and sampled heap/Metal allocations.
    A split-pass diagnostic run also completed three repeats and exposes opaque
    terrain GPU spans independently. Evidence: `docs/evidence/lod/baseline-route2/`
    and `docs/evidence/lod/baseline-split/`; both commands passed. The initial near-4K
    attempt, display pacing, thermal/peak-memory limits and missing GPU-frame
    percentile instrumentation are explicitly documented. The broader release
    matrix and performance claims remain P8 acceptance work.
- [x] P2 — Settings model and submenu. Implement persistence, clamping, preset rules,
  staged capability gating, and navigation. Acceptance: restart/reload round trip,
  malformed-config recovery, small-window/keyboard checks, and independent pack state.
  - Owner: Codex. Status: complete for the currently available stages. Immutable
    preferences, field recovery, preset ownership, atomic persistence, staged gating
    and allocation-free unchanged-frame adoption are implemented. Later-stage controls
    remain unavailable with a translated explanation, as required by the staged UI design.
  - Partial evidence: `./gradlew lodSmoke` passed; live
    `./gradlew runClient -PmetalLifecycleTest -PmetalLodSettingsTest=true
    --args='--graphicsBackend default'` passed on M4 Max (13 s). Covers disk
    reload, independent malformed-field recovery, 640×480 layout, keyboard
    navigation/reset/back and pack independence. Screenshots: `docs/evidence/lod/settings/`.
    Process restart verified with `-PmetalLodExpectedRadius=9` after seeding the
    persisted config; the test also switches Standard/None without changing LOD
    preferences. `./gradlew build` passed including shader and LOD CPU smoke checks.
    No geometry-availability or performance gate was promoted.
- [x] P3 — Terrain hierarchy builder. Implement snapshots, simplification, material
  policy, revision tracking, and seams. Acceptance: deterministic fixtures for solid
  terrain, caves, overhangs, thin geometry, adjacent tiers, edits, and stale jobs;
  measured triangle reduction without missing supported surfaces.
  - Owner: Codex. Status: complete as a conservative exact-surface first builder.
    Owning-thread capture copies an immutable 18³ halo snapshot, homogeneous octree
    cells collapse, matching exposed faces merge at tiers 1–4, and section-edge strips
    retain unit vertices. Approximate occupancy simplification is not enabled.
  - Partial evidence: `./gradlew lodSmoke` passes exact face-coverage fixtures for
    solid terrain, cave tunnels, overhangs, material/tint/light boundaries and halo
    occlusion at tiers 1–4; unsupported geometry falls back. Solid fixture: 3072 →
    732 triangles (76.2% reduction), geometric error zero. No live-world reduction
    is claimed. Queue fixtures cover bounded admission, duplicates, stale edits,
    world resets, resource generations, unloads and failed workers. Adjacent-tier
    boundary vertices match. `./gradlew lodMetalSmoke` also verifies fine/coarse
    coverage and depth on M4 Max. Connecting Minecraft sources and live draw ownership
    belongs to P4 and remains pending.
- [x] P4 — Loaded-terrain Metal rendering. Implement selection, hysteresis, bounded
  upload/retirement, and ordinary-mesh fallback. Acceptance: no cracks, overlapping
  surfaces, near-detail loss, or stale edits along a repeatable movement/zoom route;
  no render-thread waits and memory stays within configured budgets.
  - Owner: Codex. Status: complete (2026-09-11), exact-surface loaded geometry.
    Production atlas resolution, all four tiers, pack preflight and final compiled-mesh
    identity checks now own successful replacement draws. Ordinary draws remain the
    fallback for unsupported data, unavailable tiers and declined shaders.
  - Current work claimed by Codex (2026-09-11): connect the resource-completion
    timeline to the actual world command queue, resolve uploaded-tier availability
    before final neighbor balancing, and carry bounded captured output through the
    compiled-mesh lifetime. Each subtask will retain its own validation evidence;
    live draw replacement remains gated until the complete P4 route passes.
  - [x] P4 completion timeline (2026-09-11): `MetalGpuDevice` exposes submission
    reservation and nonblocking completion polling on the actual world queue.
    Reservations preserve active/merged passes; abandoned draws still receive a
    completion signal. `./gradlew build` passed on Apple M4 Max, including a
    20-submission Metal/residency fixture with immediate invalidation, retained
    in-flight charges, completion release and otherwise empty submissions.
  - [x] P4 available-tier resolution (2026-09-11): final loaded selection applies
    smoothing and uploaded availability before balancing, then reapplies availability
    on each refinement. One-way adjacency propagates in both directions. `lodSmoke`
    passes missing-tier cascade fixtures and 1,000 seeded sparse graphs; every chosen
    tier exists, respects the error/transition limit and differs by at most one from
    visible neighbors. This pure resolver still awaits live draw integration.
  - [x] P4 captured-candidate lifetime (2026-09-11): bounded extraction tickets carry
    session, dimension, section, revision and resource generation into final-mesh
    capture. Compiler results transfer ownership to their exact compiled mesh;
    cancellation/close and ticket revocation release copied CPU data. Build and
    retained snapshots have separate 64 MiB caps; the identity table holds at most
    32,768 sections. Edits, unloads, replacement captures and generation changes
    revoke work immediately. The first live run exposed retained bytes at world close;
    immediate CPU release on revocation fixed it, including the publication race.
    `./gradlew build` passed (11 s). The standard-world 16/16 Default/Metal compiler
    lifecycle test then passed (43 s) on M4 Max with Standard: 178 candidate transfers,
    two block edits, resource reload, teleport, and zero build/retained bytes or
    tracked sections at close. [Evidence](evidence/lod/capture-lifetime/validation.txt),
    [counters](evidence/lod/capture-lifetime/metrics.json), and ordinary-rendering
    [screenshot](evidence/lod/capture-lifetime/scene.png). These are lifecycle checks,
    not LOD-on screenshots or frame-time measurements.
  - Current continuation owner: `/root` (2026-09-11), in progress. Implement
    generation-scoped atlas resolution and a bounded live Metal draw adapter,
    then validate replacement ownership in generated terrain before promoting
    capabilities. P5–P8 remain dependent on that evidence.
  - [x] P4 initial live tier (2026-09-11): immutable atlas lookup, BLOCK-compatible
    texture-repeat sidecar, bounded uploads before dispatcher locking, final mesh
    identity check and actual world-queue retirement are integrated behind
    `metalcraft.lodRenderTest`. Standard and source-verified no-pack variants retain
    their original depth/target/fog/light/color contracts. `./gradlew build` passed;
    `./gradlew runClient -PmetalLifecycleTest -PmetalLodRenderTest=true
    --args='--graphicsBackend default'` passed in 42 s on M4 Max/64 GB, NORMAL
    seed `metalcraft`, 16/16. Across both packs/reload/teleport: 272,200 replacement
    draws, zero upload failures, and zero LOD GPU bytes after world close.
    [Counters and initial screenshots](evidence/lod/live-tier1/metrics.json).
    Replaced triangles fell 239,843,824 → 229,449,036 (4.33%, cumulative draws,
    **not** all visible terrain). No timing benefit is claimed. This uses only tier 1;
    broader movement/zoom, production shader readback and higher tiers remain open.
  - [x] P4 live route and production shader acceptance (2026-09-11):
    `./gradlew build` passes CPU fixtures and actual Standard legacy/HDR and no-pack
    Metal shader readbacks at 128², 32², 16² and 127². Poison atlas neighbors never
    leak; full coverage, reverse-Z depth, tint/light and mip behavior pass. At the
    odd-size image, only exact block-edge sampling ties may differ between triangles.
    Current-resource shader verification declines changed/missing sources after reload.
    The NORMAL seed `metalcraft`, 16/16 Default/Metal live route passed in 1m4s on
    M4 Max/64 GB: Standard/None, tiers 1–4, three verified camera endpoints, FOV 70/30,
    radius 2/12, continuous flight/panning, 1279×719 resize, half resolution, two edits,
    reload, teleport and world close. Runtime checks enforce available/error-safe tiers
    and adjacent-tier differences ≤1. GPU allocations remained below 34 MiB in sampled
    stages and were zero after close, with zero upload failures. Uploads are bounded
    before dispatcher locking; shared-event completion is polled without waiting.
    [Report](evidence/lod/live-route/metrics.json), screenshots and validation log are
    retained in the same directory. Images were inspected for terrain holes and seams.
    Across 759,847 replacement draws, triangles were 521,980,764 → 497,160,548 (4.76%).
    This is **only the replaced draws**, not total terrain or a GPU timing benefit.
    Conservative capture reduced all observed compile output by only about 0.25%;
    the provisional performance targets are not met by these counts. P8 needs proper A/B.
- [x] P5 — Composition and material compatibility. Validate standard/no-pack paths,
  cutouts, shadow LOD, weather, entities, transparency, and reload behavior; follow
  the water plan for relevant effects. Acceptance: visual evidence and GPU readback
  for depth/color contracts with merged and split passes where supported.
  - Owner: `/root`. Status: complete (2026-09-11). Shadow residency reuse,
    explicit opt-in compatibility/benchmark configuration, and generated-world water,
    transparency and lifecycle validation have real LOD ownership counters. Water W8
    remains open for its broader release matrix.
  - Merged-pass NORMAL-world water matrix passed (3m38s, M4 Max, seed 12345,
    16/16 Default/Metal) with 608,581 LOD world draws and 882,504 LOD shadow draws,
    zero upload failures and zero charged GPU bytes after close. Whole-terrain
    counters show only 0.096% triangle reduction over this water-focused matrix.
    [Evidence](evidence/lod/water-merged/metrics.json). A pre-existing W5 comparison
    failure also reproduced with LOD off: LocalPlayer underwater vision keeps changing
    fog during server tick freeze. Waiting for vision=1 fixes the test without relaxing
    its image threshold. The temporary animation-freeze experiment was removed.
  - Current material-policy work: allow an independently validated SOLID layer in a
    mixed-layer section; retain cutout/translucent layers verbatim. Invalid geometry
    still declines the entire solid replacement. Discard no-reduction CPU candidates
    before retention. Subsequent closure evidence follows.
  - Final P5 evidence: the broader solid-layer route passed in 1m14s, including
    Standard/None, disabled capture, tiers 1–4, fullscreen, Nether/Overworld and saved-world
    reopen. It recorded 1,412,257 LOD world draws, 329,275 LOD shadow draws, 121 safe
    stale-owner fallbacks, zero upload failures and zero CPU/GPU retention after closing.
    [Route report](evidence/lod/live-lifecycle/metrics.json). Production shadow readback
    compares every texel for 1–4 cascades against the ordinary BLOCK mesh.
    Full NORMAL seed 12345 water matrices at 16/16 Default/Metal passed in 3m27s each:
    [merged](evidence/lod/water-merged-final/metrics.json) and
    [split](evidence/lod/water-split/metrics.json), with over 3.2 million LOD world draws
    and 3.8 million LOD shadow draws each, zero upload failures and zero charged bytes
    after close. Identity/depth, nearby transparent controls, above/below water,
    ordinary/Fabulous, HDR/hand/HUD, weather/time, reflections and reload assertions pass.
    Full-terrain triangle reductions were 0.508% merged and 0.511% split; these are
    composition coverage runs, not controlled performance A/B measurements.
    `./gradlew build` passed in 12s with CPU and production Metal GPU fixtures.
- [x] P6 — Reduced distant shading work (full-resolution fallback accepted, 2026-09-12).
  - Scope revision follows the fallback in “Texture and shading resolution”: retain
    full-resolution rasterization and skip identity lighting beyond short shadow
    volumes. This is **not** half/quarter pixel-resolution LOD. It adds no targets,
    history, band transitions or resource lifetime; depth and near shading stay exact.
    Debug views and shadow distances >=128 compile/use the original path.
  - Production acceptance: `./gradlew build lodShadingBenchmark` passes on M4 Max/64 GB.
    Three paired repeats at 4K/default 96-block shadows reduce all-pass fixture GPU
    time by **4.08%** (median of repeat changes). At 256-block shadows the branch is
    compiled out; +0.15% is measurement noise, not a distinct long-shadow algorithm.
    Moving ordinary fixtures retain exact color/depth. 648 additional threshold,
    fog, debug and legacy/HDR cases retain exact depth and <=0.001953125 channel error.
    Native API validation passes `metalFrameMetricsSmoke lodMetalSmoke` and the full
    shading cost/image fixture. [Samples, images and validation](evidence/lod/shading-distance/metrics.json).
  - Generated NORMAL-world acceptance at 16/16, Default/Metal, with native API
    validation: loaded lifecycle (73s), merged water/cache (279s) and split water/cache
    (276s) all pass. Motion, FOV/radius, edits, resize/fullscreen/half, reload,
    dimensions/reopen, water depth/refraction/underwater/HDR and hand/HUD are covered.
    Zero upload failures and zero charged GPU bytes on close. Representative loaded,
    half-resolution, refraction and underwater images inspected. [Evidence](evidence/lod/fallback-validation/).
  - The unrelated `shaderTranslationSmoke` texture-buffer/2D binding assertion under
    `MTL_DEBUG_LAYER=1` reproduces with the original HEAD native library; normal build,
    targeted native validation and live suites pass. It is a separate validation debt.
  - Continuation owner: Codex (2026-09-12). A sixth, coordinate-verified direct
    SIMD lookup removes the earlier ballot/search loop. The three-repeat paired
    Standard Metal fixture still regresses at 4K: +13.12% with 96-block shadows,
    +25.02% with 256-block shadows. The latter also fails the image budget
    (maximum channel error 0.03564; 158,088 observations above 0.02).
    Full-resolution depth is unchanged, with zero near-pixel errors above 0.02.
    The probe remains outside runtime sources. `./gradlew lodShadingBenchmark`
    passes execution in 21s on M4 Max/64 GB; this does not pass P6 acceptance.
    [Raw paired samples and images](evidence/lod/shading-direct/metrics.json).
  - Earlier fallback implementation claim (Codex, 2026-09-12): the plan explicitly
    permits full-resolution rasterization with reduced distant material work when
    reconstruction erases the savings. An identity-lighting fast path preserves
    the existing scene seed beyond the shadow volume. An isolated probe saves
    about 5% at 4K/default shadows with identical moving-fixture color and depth.
    Compile it out for long shadow distances using the existing recompile option;
    the runtime uniform-only guard costs 2% when no pixels qualify. Validate the
    production specialization, fog/debug/color contracts and generated-world
    lifecycle before closing this revised P6 scope. Half/quarter pixel shading
    remains deferred and must not be advertised as implemented.
  - [x] Feasibility evaluation completed, 2026-09-12: retain full-resolution
    rasterization and the existing merged Standard resolve. Five measured approaches
    fail the all-pass GPU-cost gate; the half/quarter approaches also fail the
    extended-shadow image budget. Those failures keep pixel-resolution shading
    deferred; the validated full-resolution fallback above closes the revised scope.
    Half/Quarter/Auto controls and the multiresolution capability remain unavailable.
    A future implementation must pass moving silhouettes, transparent intersections,
    near-detail preservation, band hysteresis and net GPU improvement including every
    added pass. [Decision and retained evidence](LOD_PERFORMANCE_RESULTS.md#p6-decision).
  - Owner: `/root`. Status: follow-up evaluated; acceptance still failed (2026-09-11).
    Four additional prototypes remove external G-buffer stores: spatial SIMD sample
    lookup, half-only quad sharing, one tile dispatch, and packed coarse tile work
    followed by tile reconstruction. All remain isolated smoke experiments.
  - [x] P6 follow-up cost matrix: `./gradlew lodPackedTileShadingBenchmark` runs
    all five approaches at 1279×719, 1920×1080 and 3840×2160, with 96/256-block
    shadow coverage and three repeats of 180 samples per variant. Apple M4 Max,
    64 GB, macOS 27.0. Every approach fails its paired all-pass GPU cost gate.
    At 4K/default shadows, even the half-only control is 6.1% slower; the complete
    half/quarter approaches are 17.2–111.4% slower than their paired baselines.
  - [x] P6 follow-up image readback: sixteen moving odd-size fixtures preserve
    full-resolution depth bit-for-bit and have no near-pixel errors above 0.02.
    Extending shadows to 256 blocks exposes distant image-budget failures in every
    half/quarter approach (maximum channel error 0.0391–0.0526). The earlier default
    fixture had no shadow work at quarter-band depth; its passing result did not
    establish quarter-band lighting quality. Reports now explicitly flag this failure.
    [Commands, measurements, limitations and artifacts](LOD_PERFORMANCE_RESULTS.md#p6-follow-up-in-pass-sharing-and-tile-stages).
    At this checkpoint P6 stayed unchecked: no net GPU improvement, generated-world motion/transparent
    intersection acceptance, runtime integration, or distance-band hysteresis has passed.
  - Validation of the follow-up: the complete matrix passes execution in 30 s and
    again under `MTL_DEBUG_LAYER=1` in 31 s, with no Metal API errors. `./gradlew build`
    passes in 11 s, including existing CPU/Metal/shader smoke checks;
    `git diff --check` passes. These validate the probes, not the failed P6 gates.
  - Owner: `/root`. Status: prototype evaluated; acceptance failed (2026-09-11).
    `./gradlew lodShadingBenchmark` compares the actual Standard resolve with 1×/half/
    quarter bands, full-resolution coverage/depth, stored G-buffer inputs and depth/
    normal/material-aware reconstruction. At 3840×2160, all-pass GPU median increases
    0.784 → 1.631 ms (+108%). Odd-size moving foreground/depth readback passes, but this
    synthetic fixture does not establish world-image acceptance. No runtime capability
    is enabled. [Full results and remaining requirements](LOD_PERFORMANCE_RESULTS.md).
- [x] P7 — Extended-horizon cache. Implement persistent parent nodes, cache versioning,
  eviction, and visible-region scheduling. Acceptance: explored standard terrain at
  32/64/128/256 LOD chunks while Minecraft remains 16/16; bounded memory/draw counts,
  correct world isolation, and graceful missing/corrupt/stale data behavior.
  - [x] Cache correctness and lifecycle (2026-09-12): root-wide disk budget and
    exclusive lock, bounded compressed captures, visible selection, pending/stale
    revision exclusion, checksummed version-2 atomic storage, ancestor repair and
    inactive global clear pass production scheduler/filesystem smoke fixtures.
    Startup and teleport dirty-storm fixtures preserve unrelated explored terrain.
    Offscreen received cached sections use bounded existing asynchronous extraction.
  - [x] NORMAL-world horizon route (2026-09-12): M4 Max/64 GB/macOS 27.0,
    16/16 Default/Metal, `MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest
    -PmetalLodHorizonTest=true --args='--graphicsBackend default'` passes in 3m41s.
    32/64/128/256 snapshots submit 96/119/122/129 opaque draws and retain
    69.2/104.5/108.3/109.8 MiB distant GPU memory. Standard/None, edits, 1,282 hidden
    recaptures, reload, dimension isolation, persisted reopen and active/inactive
    clear pass. Every recorded cache has zero drops/failures; no upload failures;
    disable and both closes release all charged distant GPU bytes. Images inspected.
    [Measurements and limitations](LOD_PERFORMANCE_RESULTS.md#p7-generated-world-results),
    [artifacts](evidence/lod/horizon-final/lod-horizon.json).
  - [x] Extended-water compatibility (2026-09-12): full merged/split NORMAL-world
    matrices at 16/16 Default/Metal, 64-chunk cached horizon and `MTL_DEBUG_LAYER=1`
    pass in 4m35s/4m34s. They record 686,912/675,629 distant draws, zero upload
    failures and zero charged distant GPU bytes after close. Identity/depth,
    refraction/foam/SSR, underwater, native/half/resize, ordinary/Fabulous and reload
    checks pass. [Merged](evidence/lod/horizon-water-merged/validation.txt) and
    [split](evidence/lod/horizon-water-split/validation.txt) commands and artifacts.
    The water plan is updated in the same change; W8 remains open.
  - Owner: `/root`. Status: complete for explored opaque cache functionality
    (2026-09-12). Exact emitted SOLID/CUTOUT surfaces batch through eight parent
    levels and enter existing Metal opaque pipelines/full-resolution depth. Unknown
    terrain and distant fluids remain absent. The bounded selection can omit data
    in denser scenes; dense 256-chunk coverage and performance remain P8 work.
  - Access remains experimental: `-PmetalLodHorizonExperimental=true` or the explicit
    `-PmetalLodHorizonTest=true` lifecycle test. The ordinary loaded-geometry flag
    does not enable it. No release capability was promoted.
  - Final `./gradlew build` passes in 14s on M4 Max, including production cache,
    CPU/Metal LOD, padded-uniform readback and shader translation/GPU checks.
    `git diff --check` passes. Earlier failed runs are retained as regression
    evidence, including startup dirty overflow and the half-resolution diagnostic
    highlight threshold; the final route/matrices above include their fixes.
    General shader lifecycle (42s) and the separate creative-search route (20s)
    also pass under native API validation, NORMAL terrain, 16/16 Default/Metal.
    [Final commands and logs](evidence/lod/horizon-final/validation.txt).
- [x] P8 — Tune presets and release gates. Complete for the opt-in explored-terrain
  preview (Codex, 2026-09-12), using the recorded measurements and the user-directed
  waiver of the remaining long runs. Defaults, tradeoffs and limitations are documented
  in README and the results. Skipped checks do not establish their performance targets;
  do not market unmeasured extreme-distance performance.
  - Closure: the user instructed "skip the long tests. they are unnecesary" after
    the six remaining baseline pairs and final horizon rerun were identified. Those
    requirements are waived for this completion. Existing native/UI, 48-capture horizon,
    preparation-cost and two disabled-pair results are retained with their source identities.
    Geometry and horizon controls work without a development flag; LOD remains off
    by default. Loaded-geometry speedup remains a deferred objective.
    [Results and remaining follow-ups](LOD_PERFORMANCE_RESULTS.md).
    - [x] Native API-validation build. The texel-buffer translator mismatch and shadow
      filtering test fixture are corrected. The complete build passes in 19s after
      final preparation instrumentation and 18s after the disabled-idle refinement.
      [Evidence](evidence/lod/p8-release/validation/README.md).
    - [x] GPU frame distributions, loaded-worker total/max build time, CPU reservation
      and retention charges, OS resident/physical-footprint peaks, and thermal provenance.
      Real native capture isolation, delayed completion and multi-buffer fixtures pass.
      GPU spans include gaps; they are not exclusive terrain timings.
    - [x] Loaded performance matrix: 16 Standard/None × 1080p/native × full/half × off/on
      cases, 144 phases, NORMAL seed `metalcraft`, 16/16 Default/Metal, M4 Max/64 GB.
      All 1,057 tracked chunks arrive and rendering settles; continuous presentation
      checks and >=99.656% GPU coverage pass. [Matrix](evidence/lod/p8-release/matrix/README.md).
    - [x] Fresh cold-test-cache 128/256 horizon route: all 48 captures and same-world
      re-enable, three repeated repairs, reload, dimension isolation/return, persisted
      reopen, active/inactive clear and both closes pass in 13m13s. All eight frame
      comparisons meet +25% median / +35% p99; maximum median cost is 21.83%.
      The enabled scenes represent 1,002/995 explored sections, not dense circles.
      A controlled foreground interruption discards all 220 disturbed frames, then
      passes a fresh attempt; rendering/mode errors are never retried as focus loss.
      [Qualified route and inspected images](evidence/lod/p8-release/horizon-preview/README.md).
    - [x] Cold readiness, repair and bounded-resource diagnostics: 47.85s designated
      exploration including its settle (excluding world creation), three edits persisted
      in 800.3/646.0/399.8 ms, maximum recorded GPU payload high-water 116.70 MiB,
      cold compressed queue 9.18 MiB. Recorded per-store write high-water is 103.48 MiB;
      a later disk snapshot reaches 114.17 MiB. Limits pass and closes/clears reach
      zero distant GPU bytes. Driver/filesystem allocation overhead is outside this scope.
    - [x] Complete render-thread preparation cost: a 12-capture Standard/full focused
      probe retains all repair/lifecycle checks and includes loaded+distant maintenance,
      selection, uniforms and CPU uploads. Median-of-repeat p95 is 0.165/0.161 ms for
      128/256; the largest enabled phase is 0.166 ms, below 1 ms.
      [Probe](evidence/lod/p8-release/horizon-cost/README.md).
    - [x] Resolve disabled-overhead validation scope: two native None/full and None/half
      pairs pass <=2% median / <=5% p99 in every measured phase after skipping idle
      maintenance and per-draw census. The remaining six long pairs are **skipped at
      the user's direction**, not recorded as passes. The complete eight-case target
      remains unestablished. The initial failed pilot and foreground-rejected attempts
      remain archived; reproduction setup compiles in 1s.
      [Partial comparison](evidence/lod/p8-release/disabled-comparison/README.md).
    - [x] Resolve final idle-path horizon regression: the user **waived this long rerun**.
      The earlier 48-capture route and complete preparation probe pass on their recorded
      sources; the idle refinement passes the native build and two disabled comparisons.
      No new full horizon or enable-disable result is claimed for that refinement.
    - [x] Explicitly revised loaded-geometry objective: confirmed distant-triangle
      savings are 0.127–0.250%; no median speedup or 20% exclusive terrain-GPU saving
      is established. The preview offers explored scenery with stated cost. The former
      50% triangle / 20% GPU targets remain optimization follow-ups, not achieved claims.
    - [x] Settings/defaults: no development flag required, LOD off, Balanced, full
      shading, 16-chunk horizon, automatic mesh limits, default 2 GiB disk cache.
      Standard/None supported; other packs fall back. Actual keyboard enable/reset,
      small-window layout, persistence/recovery and pack-independent preferences pass
      in 16s. [UI evidence](evidence/lod/p8-release/settings-preview/README.md).
      README records enabling, measured tradeoffs, reproduction and unsupported coverage.
  - Follow-ups outside this preview's qualified scope (not completed by these tests):
    dense horizon/repair-churn throughput; actual driver allocation peaks; base and
    lower-memory Apple Silicon; true half/quarter shading; profitable loaded geometry.
    The intermittent GLFW final-window-reset null-monitor fault also remains an
    independent investigation. Its triggering callback is unknown; clean exits do not
    prove it fixed. [Crash analysis](evidence/lod/p8-release/validation/glfw-analysis.md).
  - Owner: `/root`. Status: partially evaluated; release criteria unmet (2026-09-11). Added explicit
    LOD-on/off benchmark configuration, whole-terrain/distant triangle counters and
    per-frame LOD preparation percentiles. A three-repeat Standard near-4K A/B pair
    uses NORMAL seed `metalcraft`, 16/16, Default/Metal, full scene resolution and
    unlocked presentation. Actual drawable size is 3840×2104 (macOS window clamp).
  - Follow-up claimed: preserve ordered native pipeline changes in the command stream.
    The first A/B showed hundreds of batch flushes around LOD replacements versus two
    ordinary batches. Validate checked/coarse ABI pixels and pipeline lifetimes, then
    repeat the same LOD-on route before drawing a performance conclusion.
  - Correctness follow-up passes: `./gradlew build` (11s) includes both native command
    ABIs, alternating pipeline/draw pixel checks and pipeline release before GPU
    completion. The full P4 NORMAL-world 16/16 Default/Metal route passes in 1m13s
    after batching, including Standard/None, tiers 1–4, motion/zoom, radius, edits,
    resize/fullscreen/half, reload, teleport, dimensions and world reopen. It records
    2,535,368 replacement draws, zero upload failures and zero charged GPU bytes on
    both closes. [Counters and images](evidence/lod/live-batched/metrics.json).
    The separate general shader/creative-search lifecycle also passed in 41s before
    this batching change; [evidence](evidence/lod/general-lifecycle/metrics.json).
  - Measurement result: the follow-up near-4K run passes its diagnostic execution
    checks but not release acceptance. Ordered batching reduces median native batches
    per frame from 229/98/84 to 3/2.4/3 (stationary/pan/traversal). Median intervals
    remain 8.0%/1.2%/5.1% above the earlier off run; distant triangle savings are only
    0.08–0.29%. Both original A/B processes and this follow-up hit the combined Fabric
    chunk-load/render settle timeout, so steady-state readiness is unverified.
    Future reports now serialize that result and explicitly select NORMAL terrain.
    [Measurements, commands, limitations and artifacts](LOD_PERFORMANCE_RESULTS.md).
    P8 remains open; no preset or capability was promoted on these results.
  - Final composition follow-up: the complete merged water matrix passes after
    ordered batching (3m28s), NORMAL seed 12345, 16/16 Default/Metal, M4 Max.
    It records 3,133,425 LOD world draws and 3,731,634 LOD shadow draws, zero upload
    failures and zero charged bytes after close. [Evidence](evidence/lod/water-batched/metrics.json).
    The water W8 tracker is updated in the same change and remains open for its own
    broader release-performance matrix.
  - Final verification: `./gradlew build` passes in 11s on M4 Max after all runtime,
    native batching, smoke, and benchmark-report changes; `git diff --check` passes.
    At that earlier checkpoint the development configuration was left with LOD disabled
    and P6–P8 were unchecked. See the current completion scopes and blockers above.

Dependencies: P1 → P2/P3 → P4 → P5 → P6 evaluation; P7 builds on P4/P5 and does not require shading LOD.
P8 requires P2–P7 correctness and the recorded P6 scope decision. A geometry/cache
milestone must explicitly exclude multiresolution shading and its unpassed gates.

## Validation and performance gates

Use the same seed, camera path, time, weather, pack, resolution, and warm-up for each
A/B pair. Test hills, mountains, forests, coasts, caves, villages, building edits,
fast flight, zoom, teleports, dimension changes, resource reload, resize/fullscreen,
world close/reopen, and shutdown. Include stationary and moving captures; inspect
boundary crops and tier overlays. Prepare distant cache coverage before steady-state
comparisons and measure first-time building/cache churn separately.

Run `./gradlew build` and relevant shader smoke checks after implementation. Extend
the benchmark harness with an explicit LOD configuration and coverage counters before
claiming extended-horizon results. Existing baseline invocation:

```bash
./gradlew runClient -PmetalLifecycleTest -PmetalLifecycleBenchmark=true \
  -PmetalBenchmarkRenderDistance=16 -PmetalBenchmarkSimulationDistance=16 \
  -PmetalBenchmarkResolution=3840x2160 -PmetalBenchmarkRepeats=3 \
  --args='--graphicsBackend default'
```

Verify the harness selects a standard generated world and the log confirms Metal.
Do not rely on older README benchmark defaults, which describe other distances and
flat-world lifecycle coverage. Repeat at native display resolution and 1920×1080,
with half-resolution off/on as separate cases; keep device, thermal state, VSync,
and frame-cap conditions recorded. Target at least a base Apple Silicon system and
a higher-end system where hardware is available; report untested hardware explicitly.

Record median/p95/p99 CPU and GPU frame time, 1% lows, terrain triangles and draw
counts by tier, build latency, upload bytes/time, GPU allocations, CPU heap/cache,
disk usage, and actual visible terrain coverage. Capture at least three repeats.

Release criteria and retained optimization objectives:

User clarification (2026-09-12): a slight performance decrease is acceptable when
large LOD horizons add visible terrain. Extended-horizon acceptance measures that
tradeoff against the 16-chunk baseline; it does not require a speedup while drawing
more terrain. Keep the provisional 25% median / 35% p99 comparison below as an
explicit reporting budget, and retain image, bounded-resource and correctness gates.

- LOD disabled reporting target: no more than 2% median or 5% p99 frame-time regression
  versus baseline. Two native None pairs pass. The user waived the remaining six long
  pairs and final idle-path horizon rerun for P8 completion (2026-09-12); full matrix
  confirmation is not claimed and is no longer a completion prerequisite.
- Revised P8 scope (2026-09-12): an opt-in explored-terrain horizon with explicit
  measured cost and bounded resources. The former 50% distant-triangle / 20% terrain
  GPU improvement at 4K, 16/16 is a deferred optimization objective, not a release
  claim or a prerequisite for this scoped feature. The completed matrix misses it;
  do not present conservative loaded replacements as an FPS optimization.
- Cached 128-chunk LOD horizon: target total median frame time within 25% of the
  16-chunk LOD-off baseline at the same resolution; p99 within 35%, with measured
  coverage and fixed budgets. Report 256 chunks separately as a stress target.
- Warm-cache LOD scheduling/upload: target at most 1 ms p95 render-thread cost per
  frame; no unbounded queues, repeated synchronous allocation spikes, or GPU waits.

These thresholds must be compared against run-to-run variability. Full-screen
lighting/post-processing, entity cost, simulation, and unseen chunk generation are
not eliminated by terrain LOD; report which limit remains on each tested device.
