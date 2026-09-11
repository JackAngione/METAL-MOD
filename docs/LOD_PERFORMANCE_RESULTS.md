# LOD measurements, 2026-09-11

Loaded-terrain geometry and composition validation pass. The full LOD plan is **not
complete**: the current geometry does not meet the performance targets, the shading
prototype fails its net-GPU-cost gate, and persistent extended-horizon parent nodes
are not implemented. All three release capability gates remain closed by default.

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
is therefore retained only under smoke sources; **P6 remains unchecked** and the
runtime uses full-resolution shading. Standard's existing merged resolve remains
the better measured choice for this experiment.

[Raw timings and quality counters](evidence/lod/shading-prototype/metrics.json),
[merged image](evidence/lod/shading-prototype/merged.png), and
[reconstructed image](evidence/lod/shading-prototype/bands.png) are retained.

## Remaining acceptance

P7 still requires a bounded persistent hierarchy from received terrain, parent-node
meshing and visible-region scheduling, cache/world/resource identity, atomic writes,
eviction, corrupt/stale/revisit handling, and measured 32/64/128/256-chunk coverage
with Minecraft kept at 16/16. Loaded render-section storage does not provide this.

P8 still requires the full Standard/None, resolution and half-resolution matrix,
GPU frame percentiles, build latency and peak memory/disk accounting, additional
hardware where available, and passing or explicitly revised targets supported by
measurements. Neither poor performance nor an unimplemented stage is marked complete.
