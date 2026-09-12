# LOD measurements, 2026-09-11–12

Loaded-terrain geometry and composition validation pass. The full LOD plan is **not
complete**: the current geometry does not meet the performance targets, the shading
prototypes fail their net-GPU-cost gate. The persistent extended-horizon functional
route and merged/split composition regressions pass. All three release
capability gates remain closed by default.

## Generated-world geometry A/B

Machine: Apple M4 Max / 64 GB, macOS 27.0, Java 25.0.4, 16 GiB maximum heap.
Both runs use NORMAL seed `metalcraft`, render/simulation **16/16**, Default/Metal,
Standard, FOV 70, verified pitch 30°, global half resolution off, VSync off and
unlocked presentation. The requested 3840×2160 window becomes **3840×2104** on this
display; all comparisons use the actual drawable size. LOD-on uses Balanced,
four-chunk full-detail radius, 2-pixel tolerance, smoothing on and Auto memory.

Each process runs three repeats of stationary, pan and traversal phases after
world warm-up and an attempted mesh settle wait. Camera positions, angles, repeat endpoints, configuration and
device metadata match. This is one process pair, not three independent pairs.
Thermal state and power consumption were not instrumented. No base Apple Silicon
machine was available. The first repeat is materially faster in both processes,
so the table reports medians across repeats and retains all raw values.

**The combined chunk-load/render settle wait timed out in both processes.** The
original warning calls this a meshing timeout, but Fabric's predicate also requires
every chunk in the full square to be loaded. The precise failing subcondition was
not recorded. These are diagnostic captures, not verified steady-state acceptance.
Loaded fractions were 0.9954 off and 1.0000 on; whole-terrain input triangles per
frame differ by under 0.4% in the phase medians. Future reports explicitly serialize
the combined wait result so this limitation cannot disappear when logs are omitted.

| Phase | Off median interval | On median interval | Change | Off p99 interval | On p99 interval | Distant triangle reduction |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Stationary | 5.277 ms | 5.828 ms | +10.44% | 7.116 ms | 7.761 ms | 0.240% |
| Pan | 5.103 ms | 5.240 ms | +2.68% | 7.370 ms | 8.130 ms | 0.152% |
| Traversal | 7.479 ms | 7.874 ms | +5.28% | 10.133 ms | 10.778 ms | 0.062% |

Each p99 column is the median of the three phase p99s, not a pooled percentile.
LOD preparation p95 is 0.167–0.195 ms across these phases, below the 1 ms scheduling
target. Zero upload failures occurred. Maximum sampled LOD GPU allocation was
26,982,736 bytes; retained CPU candidates reached the independent 64 MiB cap.
Samples are not peak memory measurements. The complete water compatibility matrices
previously measured about 0.5% total triangle reduction, also below the proposed target.

The 50% distant-triangle and 20% terrain-GPU targets do not pass. Overlapping opaque
pass spans are almost unchanged (stationary 2.191 → 2.202 ms). The GPU report contains
command-buffer busy means and overlapping pass spans; neither is an exclusive terrain
cost or a GPU frame percentile. Do not add spans or infer GPU p95/p99 from these means.
This pair also does not measure release-disabled overhead against the pre-LOD commit.

The profile exposed many native command-batch flushes around individual LOD draws.
Ordered pipeline changes have since been added to the command stream, preserving
draw order, pipeline state, attachment checks and resource retirement. Both native
command ABIs pass alternating-pipeline pixel and in-flight lifetime checks. The full
generated-world LOD route passes again, with zero GPU allocation after both closes.

The follow-up LOD-on process uses the same command and route. Its combined settle
wait also times out, so it remains diagnostic. Loaded fraction is 1.0000 and visible
sections are 973. Compared with the original off process:

| Phase | Batched median interval | Change from off | Batched p99 interval | Distant triangle reduction | Native batches/frame before → after |
| --- | ---: | ---: | ---: | ---: | ---: |
| Stationary | 5.700 ms | +8.02% | 7.588 ms | 0.286% | 229.0 → 3.0 |
| Pan | 5.164 ms | +1.20% | 7.981 ms | 0.184% | 98.4 → 2.4 |
| Traversal | 7.857 ms | +5.05% | 10.562 ms | 0.080% | 83.7 → 3.0 |

Batch counts confirm the intended submission change. The frame-time differences
between the two LOD-on processes are small relative to variation within the runs;
they do not establish a repeatable speedup. Triangle targets still fail. LOD
preparation p95 remains under 0.195 ms, upload failures remain zero, and maximum
sampled LOD GPU allocation is 27,165,440 bytes. [Raw follow-up](evidence/lod/benchmark-4k-standard-batched/metrics.json)
and [comparison](evidence/lod/benchmark-4k-batched-comparison.json) retain all samples.

Reproduction (change only `metalBenchmarkLod` between the pair):

```bash
./gradlew runClient -PmetalLifecycleTest -PmetalLifecycleBenchmark=true \
  -PmetalBenchmarkLod=false -PmetalLodExperimental=true \
  -PmetalBenchmarkPack=standard -PmetalBenchmarkPitch=30 \
  -PmetalBenchmarkRenderDistance=16 -PmetalBenchmarkSimulationDistance=16 \
  -PmetalBenchmarkResolution=3840x2160 -PmetalBenchmarkRepeats=3 \
  -PmetalBenchmarkHalfResolution=false -PmetalBenchmarkUnlocked=true \
  --args='--graphicsBackend default'
```

Raw [off report](evidence/lod/benchmark-4k-standard-off/metrics.json),
[on report](evidence/lod/benchmark-4k-standard-on/metrics.json), logs in the same
directories, and [comparison](evidence/lod/benchmark-4k-comparison.json) are retained.
Recompute with [compare-benchmarks.py](evidence/lod/compare-benchmarks.py), which
rejects mismatched camera paths and settings.

## Shading-band experiment

`./gradlew lodShadingBenchmark` runs an isolated offscreen Metal fixture, with the
actual Standard resolve body and shadow/lighting helpers. It compares the existing
merged coverage/resolve against full-resolution coverage and depth, stored G-buffer
inputs, half-linear shading from 64–128 blocks, quarter-linear shading beyond 128,
and depth/normal/material-aware reconstruction. Near pixels and disocclusions use
current full-resolution shading. A later depth-tested draw uses the original depth
attachment. Reduced targets never supply scene depth.

GPUStartTime/GPUEndTime measure one command buffer containing **all** passes, including
stores, reconstruction, and lost pass merging. CPU waits occur outside this interval.
There are 40 warm-up submissions and 180 samples per variant, alternating order.
The geometry is a synthetic input, not a Minecraft test world or an in-game speedup.

| Target | Merged GPU median | Bands GPU median | Change |
| --- | ---: | ---: | ---: |
| 1279×719 | 0.114 ms | 0.213 ms | +86.69% |
| 1920×1080 | 0.212 ms | 0.416 ms | +95.80% |
| 3840×2160 | 0.784 ms | 1.631 ms | +107.97% |

At odd extents, sixteen moving checker/foreground configurations with perspective
projection and sampled shadow edges retain bit-identical full-resolution depth.
No pixel exceeds 0.02 channel error; the largest observed error is 0.00568. This
fixture does not establish real-world transparent/water image acceptance, resource
reload behavior or moving distance-band hysteresis. It establishes a cost failure
even before those integration requirements are implemented. The current experiment
is therefore retained only under smoke sources; **P6 remained unchecked at this checkpoint** and the
runtime uses full-resolution shading. Standard's existing merged resolve remains
the better measured choice for this experiment.

[Raw timings and quality counters](evidence/lod/shading-prototype/metrics.json),
[merged image](evidence/lod/shading-prototype/merged.png), and
[reconstructed image](evidence/lod/shading-prototype/bands.png) are retained.

## P6 follow-up: in-pass sharing and tile stages

The first completion follow-up on 2026-09-11 tested four additional approaches.
P6 remained open at that checkpoint; the later decision below closes the
feasibility evaluation and defers the feature. None of the tested approaches reduces net GPU time, and the approaches
with a quarter-linear band fail the expanded synthetic image budget. Runtime shader
sources, settings, and capability gates are unchanged.

The new fragment variants reuse shaded samples through either coordinate-verified
SIMD lookup or quad shuffles. Lookup never assumes that a SIMD group covers a
particular screen rectangle. Missing or incompatible samples fall back to current
full-resolution shading. The quad variant is deliberately a **half-only control**;
a quad does not implement quarter-linear shading. Both variants skip sharing checks
when the entire group is near or beyond the configured shadow distance.

The native feasibility probe also tests one tile dispatch with shared sample storage,
and two tile dispatches that pack coarse work before reconstruction. These use Metal
imageblocks within the existing render encoder, so they require no external G-buffer
stores or sampled G-buffer textures. The packed version tests whether reducing idle
SIMD lanes can offset the extra tile dispatch and reconstruction work. Apple's
[tile/imageblock documentation](https://developer.apple.com/documentation/metal/tailor-your-apps-for-apple-gpus-and-tile-based-deferred-rendering)
describes the underlying API. Neither probe is linked into the runtime native library.

Machine: Apple M4 Max / 64 GB, macOS 27.0 (26A428), Java 25.0.4. No other Minecraft
client was present in the process check. Thermal state and power were not sampled.
These are offscreen fixtures, **not Minecraft worlds**; no generated-world acceptance,
16/16 gameplay performance, or frame-rate improvement is claimed.

Each fixture uses the actual Standard resolve body and identical exported uniforms,
including the projection for each target size. Tests cover 1279×719, 1920×1080 and
3840×2160, separately at the default 96-block and maximum 256-block shadow distance.
Each configuration has three repeats, 80 warm-up submissions per repeat, and 180
samples per variant per repeat. Variant order rotates to distribute ordering bias.
Raw samples and median/p95/p99 are retained. GPU command-buffer intervals include
coverage/depth, every resolve/store/reconstruction operation and a subsequent
depth-tested consumer. CPU readbacks and synchronous harness waits are outside them.

The following are medians of the three paired repeat changes at 3840×2160; positive
values mean slower. Tile modes use their own paired baseline with identical tile
dimensions (16² direct, 32² packed). Do not compare their absolute baseline costs
against the Java harness's automatically sized tiles.

| Approach | 96-block shadows | 256-block shadows |
| --- | ---: | ---: |
| Stored G-buffer, half/quarter targets and reconstruction | +111.4% | +67.7% |
| Fragment SIMD half/quarter lookup | +22.5% | +41.3% |
| Fragment quad, half-only control | +6.1% | +9.0% |
| Single tile dispatch, half/quarter sharing | +17.2% | +15.8% |
| Packed coarse tile work plus reconstruction | +49.4% | +49.5% |

The best control at default shadows increases the median from 0.7711 to 0.8175 ms.
Removing external stores reduces the original regression, but sharing and
reconstruction still cost more than the shading they remove in these fixtures.
The default quarter band at 200 blocks lies outside the 96-block shadow volume;
the 256-block case is needed to exercise its shadow reconstruction.

All sixteen moving 1279×719 configurations retain bit-identical depth and zero near
pixels above 0.02 maximum channel error. This is a tolerance check, not a claim of
bit-identical near color. With 256-block shadows, the stored-band approach reaches
0.03906 error, with 53,491 pixel observations exceeding 0.02. SIMD and tile
half/quarter sharing reach 0.05261, with 745,570 observations exceeding 0.02. Each
comparison covers 14,713,616 pixel observations. The half-only control stays below
0.02 (maximum 0.01792), but fails performance and does not provide quarter shading.
`imageBudgetPassed` records these failures explicitly; successful benchmark execution
must not be interpreted as P6 acceptance. The consumer is an opaque depth-tested
stripe, not a real transparent/water scene. The images were visually inspected.

During probe development, a 32² direct tile requested 40,960 bytes against Metal's
32,768-byte tile budget. Without checking encoder creation, the missing draw could
leave the previous image intact and falsely look fast and correct. Those results
were discarded. The retained direct probe uses 16² tiles, checks encoder creation
and texture allocation, completed GPU status/timestamps and finite readback
channels, and retains the corrected measurements only. The packed probe uses 32²
tiles with 5,120 bytes of shared coarse storage and an explicit producer barrier.

Reproduce the complete matrix (it also runs the original Java comparison):

```bash
./gradlew lodPackedTileShadingBenchmark
MTL_DEBUG_LAYER=1 ./gradlew lodPackedTileShadingBenchmark
./gradlew build
```

The first command writes performance evidence; the second is separate API validation
and must not replace the uninstrumented timing reference. Reports are written under
`build/reports/lod-shading/`. The native probe consumes fixture source/uniform bytes
exported by the Java task, so Gradle enforces that dependency. Subsets are available
as `lodShadingBenchmark` and `lodTileShadingBenchmark`.

Retained evidence: [fragment timings/readbacks](evidence/lod/shading-followup/metrics.json),
[direct tile](evidence/lod/shading-followup/tile-direct-metrics.json),
[packed tile](evidence/lod/shading-followup/tile-packed-metrics.json),
[benchmark log](evidence/lod/shading-followup/benchmark.log),
[validation](evidence/lod/shading-followup/validation.txt),
[256-block reference](evidence/lod/shading-followup/shadows-256/merged.png) and
[SIMD reconstruction](evidence/lod/shading-followup/shadows-256/fused.png).

Implementing P6 in a future stage would require a design that passes the net-cost gate, moving
band hysteresis, generated-world silhouette and transparent-intersection validation,
frame-boundary settings/capability integration, and reload/resize/half-resolution
lifecycle coverage. These measurements do not prove that all possible shading LOD
designs fail; they rule out promoting these prototypes as a completed feature.

## P6 decision

The completion continuation finishes P6's feasibility evaluation and **defers its
implementation**. P6's implementation checkbox remains open: its acceptance gates
have not passed.
The retained three-repeat M4 Max measurements above are sufficient to reject these
five implementations: even the half-only control costs 6.1% more GPU time at 4K;
complete half/quarter approaches cost 17.2–111.4% more with default shadows. Their
extra passes or in-pass sharing cost more than the work removed. Extended shadows
also reveal half/quarter reconstruction errors beyond the stated image budget.

Production keeps full-resolution rasterization, the existing merged resolve and
its established depth/composition contracts. No multiresolution option or capability
is promoted. This is an explicit feasibility/scope decision, not a claim that
pixel-resolution LOD shipped, that moving-world acceptance passed, or that every
possible future design will fail. A new design must satisfy P6's
original GPU, image, transition and lifecycle gates before becoming available.
The current release milestone proceeds with geometry and explored-terrain caching;
P8 still owns performance qualification and release activation.

## Persistent horizon implementation

P7 retains immutable emitted SOLID/CUTOUT meshes in a versioned compressed hierarchy.
Eight parent levels batch exact surfaces; oversized parents are manifests that refine
to their children. Empty known children remain distinguishable from missing data.
Unknown terrain and distant fluids are absent. This does not generate or request
additional chunks, entities or simulation. It is not a simplified dense 256-chunk world.

The direct Metal renderer inserts these nodes into the existing opaque terrain pass
and full-resolution depth before water. Selection applies the camera frustum, the
ordinary chunk-aligned 16-chunk exclusion and the requested 32–256-chunk horizon.
Parents and descendants never own the same surface in a selected frame. Actual
surface bounds also reject sparse parents whose geometry is beyond the horizon.

The shared worker owns filesystem access, global eviction, checksums, atomic writes
and a process lock across world/dimension/material namespaces. A second process
that cannot own the root disables its cache gracefully. Clear works while enabled
or disabled and covers all namespaces. Cache format 2 rejects older representations.
Memory pressure revokes a generation instead of displaying stale output.

Limits are explicit: 4 MiB per node, 16 MiB compressed pending mesh bytes, 4,096
pending updates, 16 changed leaves per worker batch, 64 MiB/128 selected CPU nodes,
128 MiB distant GPU residency (also at most half the configured mesh budget),
and at most 256 opaque layer draws per frame. The worker trims the root to the
configured disk budget and 16,384 files after each bounded update; temporary writes
and batch construction can briefly exceed the retained disk/CPU budgets. No measured
peak-memory or dense-horizon throughput claim follows from these structural bounds.

Re-received dirty cached terrain is rebuilt even behind the camera. On Minecraft's
owning extraction thread, a bounded scan of 128 known keys requests at most 1/2/4
existing section snapshots per extraction for Low/Balanced/High. Those snapshots use
the normal asynchronous compiler path, require existing neighbor data, and yield
to the normal capture queue under pressure. The worker never reads mutable world data.
This repairs the missing mountain observed after revisiting an area without turning
toward it. Initial chunk updates wait for the disk inventory before deciding which
old leaves to revoke, preventing unknown arrivals from clearing the cache on reopen.

The cache smoke uses a manually stepped production worker and real temporary files.
It covers corruption, global budgets and locks, namespace isolation, exact negative
coordinates, empty/oversized parents, pending edits, parent repair for unchanged
siblings, stale tickets, compressed bursts, initial/teleport dirty storms, and clear
or close during initialization. A 4,097-unknown-section startup fixture failed before
the inventory fix and passes afterward.

Native Metal API validation also exposed pre-existing uniform tail allocation errors:
12/56-byte logical blocks require 16/64-byte Metal allocations. Uniform allocations
now round up to 16 bytes while preserving logical buffer/mapping bounds. A real
Metal shader fixture checks both tail values and rejection of out-of-range maps.

## P7 generated-world results

On Apple M4 Max/64 GB, macOS 27.0, the final NORMAL seed `metalcraft` route passed in
**3m41s** on 2026-09-12. Every stage verifies **16/16, Default/Metal**. Command:

```bash
MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest -PmetalLodHorizonTest=true --args='--graphicsBackend default'
```

| Horizon (chunks) | Submitted opaque draws/frame | Represented sections | Farthest node's nearest surface bound (blocks) | Charged distant GPU MiB |
| --- | ---: | ---: | ---: | ---: |
| 32 | 96 | 713 | 467.2 | 69.2 |
| 64 | 119 | 1,010 | 793.1 | 104.5 |
| 128 | 122 | 999 | 2,044.5 | 108.3 |
| 256 | 129 | 990 | 3,281.6 | 109.8 |

These are snapshots of the explored patch viewed from progressively more distant
positions, not a fully populated circle at each radius. Bound distance is measured
against emitted geometry's AABB rather than the much larger partially empty octree
cell. The 128-node selection and 128 MiB residency caps can omit terrain in denser
scenes; they do not establish dense-horizon visual completeness or performance.

Standard and None both draw the remembered terrain. A gold wall edit persists with
matching received/persisted revision, and **1,282** bounded hidden recaptures restore
revisited geometry. Inspected images preserve the mountain through edit and resource
reload. Nether draws no Overworld cache; returning to Overworld restores it. Reopening
reads persisted nodes before any new leaf is saved; its screenshot shows initial
incremental uploads. Disable, both world closes and active/inactive global clear
release all charged distant GPU bytes. The inactive clear also verifies zero `.lod`
files across the test root. Every recorded stage has zero cache drops/failures and
zero GPU upload failures. Native API validation reports no Metal assertion.

Evidence: [report](evidence/lod/horizon-final/lod-horizon.json),
[command/log](evidence/lod/horizon-final/client.log),
[validation notes](evidence/lod/horizon-final/validation.txt),
[32 chunks](evidence/lod/horizon-final/0001_metalcraft-lod-horizon-32.png),
[edited](evidence/lod/horizon-final/0006_metalcraft-lod-horizon-edited.png),
[reloaded](evidence/lod/horizon-final/0007_metalcraft-lod-horizon-reloaded.png), and
[startup regression](evidence/lod/horizon-final/opening-regression-after.log).
The live tests use an isolated `run/build/lod-test-cache`; normal play uses
`metalcraft-lod` under the game directory. This is functional M4 Max coverage only;
repair latency, dense coverage and lower-memory hardware remain release work.

The full water matrix also passes with a 64-chunk cached horizon, NORMAL seed 12345,
16/16 Default/Metal and native API validation. Merged: **4m35s / 686,912 distant
draws**. Split: **4m34s / 675,629 distant draws**. Both have zero upload failures and
zero charged distant GPU bytes after world close. Coverage includes identity and
opaque depth, native/half/resize, refraction, foam, underwater, SSR tiers and camera
movement, ordinary/Fabulous transparency and reload. Commands and inspected images:
[merged](evidence/lod/horizon-water-merged/validation.txt),
[split](evidence/lod/horizon-water-split/validation.txt).

The final merged run includes a diagnostic-predicate fix: half-resolution filtering
left 23,595 magenta identity samples but only 37 highlights above the old brightness
cutoff. The assertion now tests magenta chroma over at least 500 samples and keeps
the <=10 baseline/restored leakage limit. Renderer appearance is unchanged.
[Failed capture and analysis](evidence/lod/water-identity-threshold/validation.txt).

## Remaining acceptance

P8 still requires the full Standard/None, resolution and half-resolution matrix,
GPU frame percentiles, build latency and peak memory/disk accounting, additional
hardware where available, and passing or explicitly revised targets supported by
measurements. P6 is deferred, not shipped; P7 functional cache coverage does not
qualify dense extreme-distance performance. No release capability is promoted.
