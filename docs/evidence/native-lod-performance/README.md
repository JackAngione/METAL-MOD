# Native LOD performance — September 19, 2026

> **Superseded (2026-09-26):** historical record of a removed LOD prototype. The current system is
> described in [the distant terrain evidence](../distant-terrain/README.md).

The active native chunk path now reduces solid-terrain texture sampling to half
linear resolution at a 2-block grid and quarter resolution at grids 4–16. It uses
Metal sampler minimum LOD 1/2 and anisotropy 1, bounded by the original sampler's
maximum mip. Existing atlas storage is shared; there is no new texture allocation
and no atlas-memory reduction claim. Cutout/translucent draws and nearby terrain
keep their original sampler. Approach, zoom and disabling LOD restore texture
sampling without waiting for a mesh rebuild. Two sampler variants per original
sampler are reused and closed with their owner.

Selection compiles FOV/quality thresholds once per settings change. The extraction
loop uses squared bounds distances for both selection and rebuild priority. Queue
capacity, near-detail protection, zoom and hysteresis are preserved. The geometry
eligibility check now uses primitive locals instead of allocating position/corner
arrays and discarded material records for every quad. Mesh output is unchanged.

## Isolated CPU measurement

Apple M4 Max, Java 25, nine alternating paired samples after five warmup pairs.
Reference commit: `34bf32f8ca6acba684cbca98a35a8171243e9f08`.
The probe compiles that commit's geometry/surface meshers alongside the current
implementation. Five synthetic shapes at all four grids produce identical output
bytes and moved-vertex counts. 65,536 selection decisions match the scalar path.
The JVM thread allocation counter includes the small measurement bookkeeping cost.

| Work per sample | Before | After | Reduction |
| --- | ---: | ---: | ---: |
| 65,536 selections | 0.762 ms | 0.417 ms | 45.3% |
| 100 staircase builds | 10.992 ms | 5.774 ms | 47.5% |
| 100 slope builds | 10.231 ms | 5.000 ms | 51.1% |
| 100 cavity builds | 14.103 ms | 7.317 ms | 48.1% |
| 100 ridge builds | 11.314 ms | 6.102 ms | 46.1% |
| 100 boundary builds | 13.820 ms | 7.452 ms | 46.1% |

Mesh-worker allocations fall 59.4–67.9%. These are medians within one JVM process,
not game frame times, generation throughput or GPU measurements. The full scalar
selection API remains the comparison oracle; the runtime uses the compiled policy.
[Raw output](cpu-probe.txt), [probe](Probe.java), [reproduction script](run-probe.py).

## Correctness and live check

`MTL_DEBUG_LAYER=1 ./gradlew build` passes in 22 seconds. `git diff --check` passes.
The final automated suite checks 98,304 compiled/scalar selection comparisons,
12,000 allocation-free/original face-classifier comparisons, existing geometry
coverage/topology/contact fixtures, and native Metal mip readback. Mip tests cover
implicit and explicit sampling, half/quarter floors, capped/disabled mipmaps,
ordinary -> coarse -> ordinary restoration, reuse and resource lifetime.

One short live route runs a NORMAL generated world (`metalcraft` seed), 16 render
and simulation distance, Threaded chunk builder, Default backend confirmed as
Metal, None shader pack, with Metal API validation enabled. The route takes 17.89
seconds. Its staircase uses 1,206 instead of 4,950 indices at a 4-block grid; this
is the existing geometric reduction, not an additional triangle-saving claim.
The new texture mip is 2. Approach restores native indices in 0.046 seconds in this
single observation and restores texture detail even for a pending coarse mesh.
Retreat, expanded native radius, level zero and world close pass.
[Live report](live-report.json).

Both [coarse](coarse.png) and [restored native](native-restored.png) images were
inspected: coarse broad steps and restored individual block steps are visible.
Background terrain was still loading; these are correctness screenshots, not a
matched performance or texture-quality comparison. Metal mip readback establishes
the sampling change independently. The later classifier allocation optimization
has exact-output parity and is covered by the final build; it needs no second
live run. Standard-pack full shader checks pass, but this live route uses None.

```sh
python3 docs/evidence/native-lod-performance/run-probe.py
MTL_DEBUG_LAYER=1 ./gradlew build
MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest \
  -PmetalGeometricLodTest=true --args='--graphicsBackend default'
```

## Remaining scaling limits

The existing 256-chunk maximum remains supported, but a dense 256-chunk FPS result
is unmeasured. Full chunk generation, lighting, storage and initial tessellation
still grow with the loaded area. Coarse texture sampling can reduce bandwidth;
it cannot remove these CPU/memory costs or the shared full-resolution atlas.

The next larger gains would require reducing initial distant tessellation work,
batching native terrain draws, or introducing a separately qualified coarse
far-terrain representation instead of retaining full chunks across the entire
horizon. Those architecture changes are outside this measured optimization; the
old opaque distant-cache renderer remains disabled.
