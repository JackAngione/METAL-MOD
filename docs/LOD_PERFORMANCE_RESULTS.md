# LOD measurements, 2026-09-11–12

Loaded-terrain geometry and composition validation pass. P6 implements the
plan-authorized full-resolution reduced-lighting fallback; half/quarter pixel
resolution remains deferred. P8 is complete as an **opt-in explored-terrain preview**, following the user's
acceptance of modest large-horizon costs and explicit instruction to skip the
remaining long tests (2026-09-12). The former loaded-geometry speedup targets remain future
optimization objectives, not claims for this preview.

The preview exposes geometry and the 32–256-chunk horizon in Metal settings
without a development flag. LOD stays off by default, with Balanced, full shading,
a 16-chunk horizon, automatic mesh limits and a 2 GiB disk budget. Standard and
no shader pack are supported; other packs retain ordinary rendering. Dense circles,
lower-memory devices and true reduced-resolution shading are outside this preview's
qualified coverage. Two of eight disabled-overhead pairs pass. The remaining six pairs
and final idle-path horizon rerun were waived by the user; they are unmeasured,
not passing results.

## P8 qualified explored-horizon preview

The fresh full route passes in **13m13s**, including all 48 timed captures and
same-world re-enable, three repeated repairs, resource reload, dimension isolation,
persisted reopen, active/inactive cache clear and both world closes. NORMAL seed
`metalcraft`, 16/16 Default/Metal, M4 Max/64 GB, native 3840×2160, 2 GiB disk budget.
The test cache starts empty. Accepted phases retain continuous foreground/visibility
checks; minimum GPU coverage is 99.853%, and sampled thermal state is nominal.

A controlled Finder activation proves recovery: all 220 disturbed frames are
rejected, and a fresh warmed retry passes. Rejected timings are not part of the
summaries. Mode/drawable changes and GPU/resource failures remain fatal.

| Horizon | Pack | Scene | Median frame change | p99 change |
| --- | --- | --- | ---: | ---: |
| 128 | Standard | Full | +21.83% | +2.14% |
| 128 | Standard | Half | +18.35% | +1.04% |
| 128 | None | Full | +6.67% | -3.96% |
| 128 | None | Half | +1.98% | -7.72% |
| 256 | Standard | Full | +20.37% | -0.31% |
| 256 | Standard | Half | +17.55% | +1.27% |
| 256 | None | Full | +2.16% | -3.56% |
| 256 | None | Half | +4.23% | -1.00% |

Every row meets the provisional +25% median / +35% p99 budget. The enabled
captures represent 1,002 sections at 128 and 995 at 256. These are explored patches;
the farther camera does not turn the 256 case into a denser coverage test. Negative
tail changes are variability, and GPU spans increase with the added horizon.

Cold-cache exploration after world creation takes 47.85s including its final
120-tick settle; initial world creation is excluded. Three 1,650-block edits persist
in 800.3 / 646.0 / 399.8 ms. Maximum recorded GPU mesh payload high-water is
116.70 MiB and cold queue high-water is 9.18 MiB. Recorded per-store write-payload
high-water reaches 103.48 MiB, while a later disk snapshot reaches 114.17 MiB;
these differently scoped values are not interchangeable. The maximum sampled OS
process-lifetime physical footprint is 5.29 GiB. Actual driver allocation peaks and
filesystem allocation overhead are not measured. All sampled limits pass, uploads
never fail, and closes/clears leave zero distant GPU bytes.

The inspected 128 off/on and 256 on images show the remembered mountain; unknown
gaps and omitted distant fluids remain explicit. The repaired gold marker survives
reload. [Raw report, source identity, retry evidence and images](evidence/lod/p8-release/horizon-preview/README.md).

The full matrix's preparation counter covers loaded LOD only. A subsequent focused
Standard/full probe includes distant frame maintenance, selection, uniform allocation
and upload work in that timer. The median-of-repeat p95 is **0.165 ms at 128 / 0.161 ms at 256**; the largest enabled-phase p95 is **0.166 ms**, below 1 ms. [Complete timer evidence](evidence/lod/p8-release/horizon-cost/README.md). The native build passes in 19s, and the disabled-idle refinement passes in 18s. The actual
settings UI passes in 16s with no development flag, including keyboard enable/reset,
small-window layout, persistence, malformed recovery and pack independence.
[Settings evidence](evidence/lod/p8-release/settings-preview/README.md).

## P8 confirmed loaded matrix (2026-09-12)

All 16 Standard/None × 1080p-windowed/native-fullscreen × full/half scene × off/on
cases pass the measurement contract on M4 Max/64 GB/macOS 27.0, NORMAL seed
`metalcraft`, Default/Metal, 16/16. Total client execution is 32m35s. There are
172,383 captured frames, at least 99.656% GPU sample coverage, and 11,808 successful
presentation checks across 144 phases. Every case receives all 1,057 server-tracked
chunks and drains the light/render queues. All sampled thermal states are nominal.

Each table entry is **median frame-interval change / p99 change**, LOD on versus off.
Values are medians of the three repeat percentiles, not pooled percentiles.

| Drawable / pack / scene | Stationary | Pan | Traversal |
| --- | ---: | ---: | ---: |
| 1080p / Standard / full | +6.67% / +3.28% | +8.52% / +13.32% | +7.33% / +10.17% |
| 1080p / Standard / half | +6.53% / +3.46% | +4.52% / +2.10% | +5.72% / +8.47% |
| 1080p / None / full | +16.28% / +0.25% | +36.57% / +15.78% | +24.55% / +8.94% |
| 1080p / None / half | +11.63% / −10.16% | +17.84% / −7.53% | +43.83% / −5.65% |
| 4K / Standard / full | +14.24% / +9.24% | +11.69% / +8.78% | +12.13% / +18.34% |
| 4K / Standard / half | +3.83% / +0.45% | +2.19% / +2.76% | +3.57% / +3.74% |
| 4K / None / full | +14.10% / +5.96% | +12.11% / −5.83% | +11.49% / +6.71% |
| 4K / None / half | +17.33% / +10.20% | +8.33% / +0.80% | +12.16% / +2.75% |

Distant-triangle reductions are only **0.127–0.250%** across the 24 phase summaries.
LOD preparation p95 stays below **0.234 ms**, and no upload failures are recorded.
The proposed 50% distant-triangle target is missed; these frame/GPU spans do not
establish the separate 20% exclusive terrain-GPU target. No preset is promoted.

The maximum sampled OS process-lifetime physical-footprint peak is 5.09 GiB;
maximum sampled Metal allocation is 1.55 GiB. These have different scopes and must
not be added together. Logical loaded capture reservations remain within 32 MiB
and retained CPU meshes within 64 MiB. Full native API-validation builds pass in
17s after the sampler/fixture corrections and 16s after adding horizon diagnostics.
[Before/after validation](evidence/lod/p8-release/validation/README.md).

Foreground checks establish presentation state, not freedom from drawable pacing.
Acquire waits, CPU/GPU spans and repeat ranges remain in the raw reports; the
larger None-pack changes must not all be attributed to geometry cost. The separate
world processes share seed and camera route but do not produce identical geometry
counts: terrain input triangles differ by approximately −4.13% to +2.02% across
matched phase summaries. Inspected 4K Standard/full and 1080p None/half images show
matching near terrain but some variation in distant coverage and player skins.
These are performance diagnostics, not a pixel-parity acceptance test.

The conservative mesher rejects candidates with no triangle reduction. Accepted
replacements nevertheless require a 48-byte-per-vertex texture-repeat sidecar in
addition to the ordinary 28-byte vertex. The 4K Standard/full replacements save
about 2.5% of their own triangles while affecting only a small part of the scene.
This helps explain why the current approach has not demonstrated a net benefit;
a benefit threshold alone has not been implemented or measured.

[Raw matrix, source patch and inspected images](evidence/lod/p8-release/matrix/README.md)
and [matched summary](evidence/lod/p8-release/matrix/summary.json). The prior GLFW
cleanup crash is symbolicated to a null monitor in its Cocoa video-mode path;
which callback caused that state is unresolved. Sixteen clean exits do not prove
an intermittent fault fixed. [Crash analysis](evidence/lod/p8-release/validation/glfw-analysis.md).

## P8 measurement contract

The new release harness requires every chunk in the server's actual rounded
tracking footprint, then drains the light/render queues and warms a full camera
pan. It rejects incomplete terrain instead of timing out and continuing. This
replaces the old square-corner requirement that the server need not satisfy.
Windowed/fullscreen and global half resolution are explicit; reports retain both
drawable and scene dimensions, pack options, fixed camera samples and thermal state.

`python3 scripts/lod-release-matrix.py --phase-ticks 80` runs 16 isolated cases:
Standard/None × 1080p/native fullscreen × full/half × LOD off/on. Each case contains
three stationary, pan and traversal repeats. These are repeats within one process
per configuration, not three independently launched A/B pairs. Source digests and
commands must match to resume a capture. `scripts/lod-release-summary.py` rejects
mismatched source, camera/settings, readiness or timing coverage before comparing.

GPU frame samples are the span from the first GPU start to the last GPU end of
buffers committed on the render thread during that frame. They include gaps;
overlapping buffers are never summed. Completion callbacks are nonblocking and
phase-owned, with a bounded 32,768-frame capture. Pending, invalid, empty and overflow
frames are counted and excluded. Raw samples and p50/p95/p99 are retained; at least
95% sample coverage is required. This is GPU latency for the stated submission
scope, not an exclusive terrain cost or presentation interval. Real native tests
cover multi-buffer spans, empty/incomplete frames and a delayed completion crossing
a phase reset. Legacy command-buffer busy means and overlapping pass spans remain
separate diagnostics.

Loaded-mesh worker measurements report cumulative admitted attempts, total and
maximum build time, plus reservation and retained-byte high-water charges. They
include rejected attempts and exclude queue wait/upload. Heap and Metal allocations
are snapshots; OS process resident/physical-footprint peaks include startup. These
are not GPU allocation peaks or hierarchy-build latency percentiles. The new horizon diagnostics additionally record logical GPU mesh payload high-water
charges (including resources awaiting GPU completion), compressed queue high-water
bytes, owned disk payload before atomic rename/trim, completed worker-batch latency,
and repeated edit-to-persist latency. The locked-display attempt is retained as rejected evidence; the subsequent unlocked 48-capture route completes as reported above. Disk payload excludes filesystem
metadata/allocation rounding; GPU payload excludes driver allocation overhead. Thermal states are recorded (0 nominal to 3 critical). Only M4 Max
hardware is tested; base/lower-memory Apple Silicon and power consumption remain
unmeasured.

The optional horizon benchmark uses the default 2 GiB disk budget and alternates
off/on order across three repeats at 128 and 256 chunks. It waits for received
terrain, drained queues, a warm cache and stable GPU residency before timing. The
same-world re-enable regression is checked before these measurements. These are
stationary **explored patches**, not dense circles, with represented-section counts
recorded per capture. User-approved modest large-horizon cost is evaluated against
the provisional +25% median / +35% p99 reporting budget. It does not waive correctness
or justify claiming a speedup from loaded geometry.

## Earlier provisional explored-horizon results

These are provisional timing results. Lock/focus state was not recorded per phase;
computer-use later reported the Mac locked while qualifying the loaded-terrain
matrix. Repeat under verified presentation conditions before release acceptance.
The correctness counters and lifecycle assertions below remain useful independently.

The full 48-capture horizon route passes in **12m37s** on M4 Max/64 GB, native
3840×2160, NORMAL seed `metalcraft`, 16/16, Default/Metal. API validation is disabled
for timings and was exercised separately. All sampled thermal states are nominal.
GPU timing coverage is at least 99.76%. Each row below summarizes three repeats;
values are medians of repeat percentiles. Raw within-repeat pairs and ranges remain
in the [matched summary](evidence/lod/release-horizon/summary.json) and
[complete report](evidence/lod/release-horizon/lod-horizon.json).

| Horizon | Pack | Scene | Off median | On median | Median change | p99 change |
| --- | --- | --- | ---: | ---: | ---: | ---: |
| 128 | Standard | Full | 2.944 ms | 3.681 ms | +25.03% | −1.79% |
| 128 | Standard | Half | 3.111 ms | 3.623 ms | +16.46% | +0.58% |
| 128 | None | Full | 1.060 ms | 1.357 ms | +28.02% | +5.16% |
| 128 | None | Half | 1.044 ms | 1.225 ms | +17.34% | +2.65% |
| 256 | Standard | Full | 2.778 ms | 3.372 ms | +21.38% | −0.91% |
| 256 | Standard | Half | 2.734 ms | 3.148 ms | +15.14% | +3.26% |
| 256 | None | Full | 1.173 ms | 1.234 ms | +5.20% | +0.39% |
| 256 | None | Half | 1.166 ms | 1.143 ms | −1.97% | −0.51% |

128-chunk full-resolution Standard is borderline against the provisional +25%
median budget; None exceeds it by 3 percentage points, at **0.30 ms** absolute
cost. Other rows meet that median reporting budget, and every row stays within
the +35% p99 budget. The small negative changes are timing variability, not evidence
that drawing a larger horizon speeds up rendering. GPU median spans increase in
every row. For example, Standard/full at 128 grows from 2.487 to 3.209 ms.
The user accepts a modest large-horizon cost; these measurements describe the
observed tradeoff without silently changing the threshold or promoting a default.

All enabled repeats represent **1,007 sections at 128** and **1,001 at 256**.
The cameras move farther from the same explored patch, so the 256 case is not
denser and is not directly comparable as a scaling curve. The CPU result ceiling,
resident-memory bounds and missing fluids still limit coverage. Maximum sampled
distant GPU charge is 64.0 MiB; maximum sampled whole-test-root disk usage is
587.2 MiB under the 2 GiB budget. OS process peak physical footprint is 5.39 GiB.
GPU/disk samples are not transient allocation/write peaks.

The route also passes same-world re-enable, edit persistence/repair, resource
reload, dimension isolation, persisted reopen, active/inactive global clear, and
both world closes. It encodes 17,109,367 distant draws, with zero upload failures
and zero charged distant bytes at both closes and after clear. This is correctness
and sparse explored-patch performance evidence; dense horizons remain unqualified.
Exact command/source identity: [invocation](evidence/lod/release-horizon/invocation.json).

## Earlier provisional loaded-matrix checkpoint

Eight 1080p cases completed on the same source revision, with Standard/None,
full/half, off/on and three camera-route repeats. Every retained run passes
received-terrain, queue, configuration and GPU sample checks. The full Standard
pair shows only 0.14–0.22% distant-triangle savings and 6.3–8.2% higher median
frame intervals. LOD preparation p95 remains below 0.28 ms in that pair.
This does not meet the proposed 50% distant-triangle reduction or establish the
20% exclusive terrain-GPU improvement.

Several other pairs are presentation-limited or asymmetric. Standard/half LOD-on
has about 8.3 ms intervals while CPU work takes roughly 5–6 ms. Its pan#2 average
drawable acquisition wait is 2.98 ms versus 0.41 ms off. None/half has the reverse
pacing asymmetry after a clean retry. Large apparent FPS/GPU-span gains or losses
in those pairs must not be attributed to geometry optimization. The comparison
script reports acquire waits and an explicitly heuristic 120-Hz cadence flag.
[Raw cases, matched comparisons and limits](evidence/lod/release-windowed-diagnostic/validation.txt).

The first None/half/on attempt crashed in `libglfw` during Fabric's final window
reset, after its measurements and world close. The unchanged retry passes; the
failed attempt stays rejected and its root cause is unresolved.
[Crash evidence](evidence/lod/window-reset-failure/validation.txt).

The first native case was correctly rejected at 3840×2104/windowed, despite
requesting fullscreen. Fabric's resizeWindow forces windowed mode. The benchmark
now skips that resize for native fullscreen, applies only scene scaling, and checks
actual GLFW monitor attachment before capture. The build passes in 15s; live
verification subsequently passes in the confirmed matrix above. [Failure/correction](evidence/lod/window-mode-failure/validation.txt).

## Historical generated-world geometry A/B

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

P6 is complete in the plan's permitted **full-resolution reduced-work scope**.
Pixel-resolution LOD remains deferred. The five earlier implementations above and
a sixth direct SIMD lookup all fail the cost gate. The direct lookup removes the
ballot/search loop but still costs +13.12% at 4K/default shadows and +25.02% with
256-block shadows; extended-shadow maximum channel error is 0.03564, with 158,088
observations above 0.02. [Sixth probe](evidence/lod/shading-direct/metrics.json).

The production Standard resolve now preserves the existing scene seed beyond the
shadow volume, where shadow visibility is exactly one. This skips redundant
position/normal reconstruction and fog decode/re-encode. Full pixel coverage and
depth remain intact; the albedo ownership marker is cleared as before. The existing
`shadow_distance` recompile option removes the branch entirely at distances >=128,
where the extra per-pixel test did not pay for itself. Debug views use the old path.
There are no reduced targets, reconstruction, history, transitions or new resources.
No Half/Quarter/Auto option or multiresolution capability is promoted.

Final production-source fixture, M4 Max/64 GB, three paired repeats, 180 samples
per variant/repeat with rotated order and 80 warm-up submissions:

| Resolution | Default 96-block shadows: all-pass GPU change |
| --- | ---: |
| 1279×719 | −0.50% |
| 1920×1080 | −2.73% |
| 3840×2160 | **−4.08%** |

The 4K repeat changes are all improvements. At 256-block shadows the two programs
use the same original path: the 4K +0.15% difference and noisy smaller-resolution
results are sampling/clock variability, not an optimization claim. These are
offscreen lighting fixtures, not an overall game frame-rate improvement.
[Raw samples and moving images](evidence/lod/shading-distance/metrics.json).

Moving ordinary fixtures preserve color and depth exactly. Another 648 production
comparisons cover shadow distances 32/96/128/256, all debug views, fog boundaries,
legacy/HDR and depth offsets around the identity threshold. Depth is exact and
maximum channel error is 0.001953125, within the 0.002 tolerance (HDR rounding).
`MTL_DEBUG_LAYER=1 ./gradlew metalFrameMetricsSmoke lodMetalSmoke` and the full
shading benchmark pass. `./gradlew build lodShadingBenchmark` passes in 38s.
[Native checks](evidence/lod/shading-distance/p6-targeted-validation.txt),
[fixture validation](evidence/lod/shading-distance/p6-fixture-native-validation.txt),
[regular build and timings](evidence/lod/shading-distance/p6-final-build-benchmark.txt).

The NORMAL-world 16/16 Default/Metal loaded lifecycle passes with native validation
in 73s. The full water/cache suite passes in both merged (279s) and split (276s)
modes. Motion, silhouettes, FOV, edits, half/resize/fullscreen, reload, dimension
changes/reopen, depth/refraction/underwater/HDR and hand/HUD assertions pass; both
water runs have zero upload failures and zero distant bytes on close. Representative
terrain, half-resolution, refraction and underwater images were inspected.
[Live evidence and invocations](evidence/lod/fallback-validation/).

The full native-validation build has separate existing debt: `shaderTranslationSmoke`
binds a texture buffer to a 2D vertex texture slot and aborts. This reproduces using
the original HEAD native library with unchanged smoke source; it is not a passing
validation command. [Baseline reproduction](evidence/lod/shading-distance/p6-baseline-metal-validation.txt).
The regular build, targeted new native checks and live validation pass.
P8 completion and its explicit validation scope are recorded at the top of this document.

## Persistent horizon implementation

The P8 paired benchmark found and fixed a same-world re-enable bug: disabling LOD
closed the cache but retained the world/device/atlas identities, so enabling it
again missed the reopen condition. An absent cache now triggers recreation. The
focused NORMAL-world 16/16 Default/Metal toggle fails before the fix and passes
afterward with native API validation, restoring actual distant draws. This assertion
is part of the full horizon route. [Before/after regression](evidence/lod/horizon-reenable-failure/validation.txt).

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

## Completion and remaining follow-ups

P6's full-resolution reduced-lighting fallback is complete. Actual half/quarter
pixel-resolution shading remains a deferred follow-up with its original cost,
image and transition gates.

P8 is complete under the user-directed validation scope. On 2026-09-12 the user
instructed us to skip the remaining long tests: six disabled-versus-pre-LOD pairs
and the final full horizon/enable-disable regression after the idle refinement.
Neither skipped check is represented as a pass. The earlier full route,
repair/preparation probes, settings UI and native build remain the recorded evidence.

The initial native/None/full pilot exceeded the 2% median target. Skipping idle
maintenance and per-draw census then yielded two passing native None/full and
None/half pairs, with zero LOD capture, upload, draw bookkeeping and preparation
work while off. The full eight-case disabled target and the final refinement's
horizon cost remain unestablished. These are documented measurement limits, not
outstanding P8 completion requirements. [Partial comparison and waiver](evidence/lod/p8-release/disabled-comparison/README.md).
The rejected foreground-loss attempts remain historical evidence in
[the rejection record](evidence/lod/p8-release/disabled-focus-rejected/README.md).

Separate follow-ups outside the opt-in explored-terrain preview are:

- Improve loaded geometry enough to establish a net speedup; the measured distant-triangle reduction is only 0.127–0.250%. The original 50% triangle / 20% exclusive terrain-GPU objectives remain future work.
- Qualify dense horizon coverage and sustained repair churn, actual driver GPU allocation peaks, and base/lower-memory Apple Silicon. Explored patches and logical payload counters do not establish these results.
- Resolve the intermittent GLFW final-window-reset crash. Its null-monitor native fault is identified; the callback sequence that creates that state is not. Clean test exits do not prove a fix.
- Implement and qualify actual half/quarter pixel-resolution shading; P6 currently uses its accepted full-resolution reduced-lighting fallback.


### Loaded construction follow-up (2026-09-12)

P9.1 shares emitted-face classification across all four loaded tiers. Short CPU
fixtures match the pre-change tier output and reduce median sample construction
cost by 28–69% and allocated bytes by 61–63% on M4 Max. The native validation build
passes. These synthetic worker measurements do not establish game frame-time gains
or change the measured triangle savings above. [Evidence and reproduction](evidence/lod/shared-classification/README.md).


### Loaded selection follow-up (2026-09-12)

P9.2a builds reciprocal adjacency once for the two loaded-selection passes and
uses a bounded integer work queue. A short paired probe, including graph
construction, matches prior selector outputs and reduces median sample CPU time
34–49% and allocated bytes 68–71% across four 256/4,096-node fixtures. Independent
randomized solver checks and the native validation build pass. This is synthetic
selection evidence, not a game frame-time or GPU claim; P9.2 remains open.
[Evidence and reproduction](evidence/lod/shared-selection/README.md).


## P9 exact-mesh sharing and dense qualification checkpoint (2026-09-12)

The loaded compiler now constructs the maximal exact surface merge once and aliases
its CPU/GPU ownership across four selection tiers. Smaller rectangle limits did not
reduce the geometric error of these exact, flat-shaded surfaces. Section boundary
strips, full-detail selection, stale ownership and unsupported-model fallbacks remain.
CPU/Metal smoke and the final native API-validation build pass. The first two focused
NORMAL-world 16/16 Default/Metal off-runs were rejected for foreground loss.
The subsequent user-authorized foreground pair passes all 18 phases at 3840×2160,
None/full. Median intervals change +6.73/+1.19/−14.22% for stationary/pan/traversal,
while CPU work rises 11–16% and average-FPS repeat ranges overlap. Distant triangle
savings are 0.264–0.398%. No overall game frame-time benefit is established.
Observed driver allocation peaks are 1,275,953,152/1,303,740,416 bytes off/on.
[Raw qualified pair and attribution limits](evidence/lod/p9-qualification/foreground-pair/README.md).

The reproducible synthetic production-cache probe retains all 1,024 sections of a
light contiguous patch, but only 936/1,024 at 640 quads per section under the unchanged
64 MiB result budget. It submits 768 repairs per case with producer/worker overlap,
coalesces to 400 writes, drains completely and preserves the final payloads across
reopen with zero drops/failures. These are stepped-worker cache results, not live
explored-terrain coverage or render-thread timings. Native allocation-event telemetry
now reports observed device allocation peaks, separately from LOD payload accounting.
Dense live measurements and other Apple Silicon devices remain untested. P9.2 and
P9.3 stay open. [Evidence, commands and explicit limits](evidence/lod/p9-qualification/README.md).
