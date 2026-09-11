# LOD feature implementation plan

Status: P2/P3 foundations complete; P1 capture refinement and P4 Metal prototype
in progress. Live terrain replacement remains disabled.

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
P1–P4 are claimed by Codex; later tasks remain unclaimed. P2/P3 foundations
can proceed during P1 capture; integration acceptance still depends on P1.

- [x] P0 — Inspect terrain/render/settings seams, create branch from water effects,
  and document staged design. Evidence: branch base SHA above and inspected source
  paths listed in this document. No runtime behavior changed or performance measured.
- [ ] P1 — Baseline and integration audit. Identify exact mapped chunk snapshot,
  invalidation, draw-selection, and depth-composition hooks; record pack capabilities.
  Capture LOD-off CPU/GPU/memory baselines and screenshots on a fixed standard-world
  route at 16/16, Default/Metal. Acceptance: reproducible populated-world captures and
  pass-level timings, with known bottlenecks and selected hook contracts documented.
  - Owner: Codex. Status: in progress (2026-09-10). Auditing the existing benchmark
    and mapped renderer before introducing LOD behavior. Acceptance remains open
    until populated-world measurements and hook contracts are verified.
  - Partial evidence: [mapped interface audit](LOD_INTEGRATION_AUDIT.md) records
    snapshot isolation, invalidation and pre-batching selection candidates.
    `./gradlew build` passed including shader smoke checks on M4 Max/64 GB.
    The 4K/16/16 Default benchmark confirmed Metal at startup but was stopped
    because another task launched a client in the same checkout. Shared launch
    artifacts/logs and GPU contention invalidate the attempt. P1 remains unchecked;
    repeat with isolated launch files and exclusive GPU use before accepting timings.
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
- [ ] P4 — Loaded-terrain Metal rendering. Implement selection, hysteresis, bounded
  upload/retirement, and ordinary-mesh fallback. Acceptance: no cracks, overlapping
  surfaces, near-detail loss, or stale edits along a repeatable movement/zoom route;
  no render-thread waits and memory stays within configured budgets.
  - Owner: Codex. Status: in progress, isolated Metal geometry prototype only.
    Establishing atlas-safe repeated texture sampling and buffer lifetime on synthetic
    fixtures before any loaded-section replacement is enabled.
  - Partial evidence: `./gradlew lodMetalSmoke` passed on M4 Max. Direct Metal
    draws at 128²/32²/16²/127² preserve repeated atlas texture, exclude neighboring
    tile colors, preserve reverse-Z depth, enforce upload allowance and survive
    logical buffer retirement before GPU completion. This isolated prototype uses
    a test appearance resolver; it is not a live terrain renderer or pack adapter.
    P4 remains unchecked until capture/revision hooks, bounded mesh residency,
    selection transitions and live ordinary-mesh replacement pass their route checks.
- [ ] P5 — Composition and material compatibility. Validate standard/no-pack paths,
  cutouts, shadow LOD, weather, entities, transparency, and reload behavior; follow
  the water plan for relevant effects. Acceptance: visual evidence and GPU readback
  for depth/color contracts with merged and split passes where supported.
- [ ] P6 — Distance-dependent shading resolution. Prototype and measure the bands,
  conservative depth, and reconstruction described above. Acceptance: clean moving
  silhouettes and transparent intersections, full-detail near terrain, and a measured
  net GPU improvement including every added pass; otherwise keep this task open.
- [ ] P7 — Extended-horizon cache. Implement persistent parent nodes, cache versioning,
  eviction, and visible-region scheduling. Acceptance: explored standard terrain at
  32/64/128/256 LOD chunks while Minecraft remains 16/16; bounded memory/draw counts,
  correct world isolation, and graceful missing/corrupt/stale data behavior.
- [ ] P8 — Tune presets and release gates. Complete the matrix below, set defaults
  from evidence, document tradeoffs/limitations, and update README. Acceptance: all
  correctness gates pass and published targets are met or explicitly revised with
  measured results; do not market unmeasured extreme-distance performance.

Dependencies: P1 → P2/P3 → P4 → P5 → P6; P7 builds on P4/P5 and does not require P6.
P8 requires P2–P7. Ship geometry-only milestones explicitly if later stages remain open.

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

Provisional shipping targets:

- LOD disabled: no more than 2% median or 5% p99 frame-time regression versus baseline.
- Balanced at 4K, 16/16 in terrain-heavy scenes: at least 50% fewer distant terrain
  triangles and 20% lower terrain GPU time, with total median/p99 frame time no worse.
- Cached 128-chunk LOD horizon: target total median frame time within 25% of the
  16-chunk LOD-off baseline at the same resolution; p99 within 35%, with measured
  coverage and fixed budgets. Report 256 chunks separately as a stress target.
- Warm-cache LOD scheduling/upload: target at most 1 ms p95 render-thread cost per
  frame; no unbounded queues, repeated synchronous allocation spikes, or GPU waits.

These thresholds must be compared against run-to-run variability. Full-screen
lighting/post-processing, entity cost, simulation, and unseen chunk generation are
not eliminated by terrain LOD; report which limit remains on each tested device.
