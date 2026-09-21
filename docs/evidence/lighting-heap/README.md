# Lighting heap correction — September 20, 2026

F3 used heap approaching 100% led to profiling a copy of `New World (294)` at the
saved player location. The original save was not changed. The previous frame-work
patch was rolled back after reported missing chunks and no real FPS improvement.

JFR identified repeated whole block/sky lighting-index clones as the dominant
allocation source. The initial diagnostic recording attributed about 184 GB of
weighted allocation samples to those copies. This is allocation over time, not
184 GB of retained memory. The early forced-GC histogram is retained in
`initial-heap-histogram.txt`; it does not describe the final fully loaded world.

## Implementation

`LightSectionSnapshots` shares unchanged index pages between lighting snapshots.
A fork copies a 64-reference root; a write detaches only its group and hash bucket.
Separate ownership tokens protect both mutable branches. Removal releases empty
pages/groups. Native light-byte copying, cache invalidation, light propagation and
volatile publication remain in place. Only the audited native block/sky storage
classes opt in; unknown subclasses and nonempty constructor inputs retain native
storage. The sky column-height index still uses native cloning.

No offscreen chunk eviction, visibility rule, draw suppression or rebuild throttle
is added. Rendering continues through Metal. The lighting index is shared by the
client and integrated server in this client mod.

## Copied-save comparison

One sequential pair of 30-second world-loading captures, with simulation running,
3840×2160, Default/Metal, no shader pack, render distance 128, simulation distance 16,
and the same `-Xmx16G`. Each run starts from a separate identical copy of the save.
Both use JFR profile settings and the same opt-in frame/heap probe. Metal validation
is disabled for this comparison. Source reports: `flat.json`, `paged.json`,
`flat-run.txt`, `paged-run.txt`; JFR-derived values are in `summary.json`.

| Measurement | Native flat snapshots | Paged snapshots |
|---|---:|---:|
| Peak used heap, sampled each second | 15.56 GiB | 8.49 GiB |
| Used heap at last sample | 11.57 GiB | 7.09 GiB |
| Committed heap at last sample | 16.00 GiB | 10.27 GiB |
| JFR total GC pauses during capture | 2,304 ms | 619 ms |
| Longest GC pause during capture | 252 ms | 15.9 ms |
| Average FPS | 30.4 | 29.7 |
| 1% low FPS | 5.2 | 9.1 |
| p99 frame interval | 125.6 ms | 97.8 ms |
| Worst frame interval | 380.2 ms | 130.5 ms |
| Rendered sections at last sample | 35,016 | 36,560 |

This demonstrates lower allocation pressure and fewer large stalls, **not an
average-FPS improvement**. The growing scene is not identical between runs; the
paged run rendered 4.4% more sections. This short pair does not establish a
long-session heap bound, final loaded-world memory, or results with shader packs.
One-second heap sampling can miss instantaneous peaks. JVM committed memory is
different from used heap and process memory.

In the measured windows, weighted samples attributed 137.16 GB to native block/sky
map copies. The paged run attributed 11.71 GB to the remaining native sky copy path
and 2.73 GB to paged writes. JFR allocation sampling is an estimate; these numbers
are not exact byte counters. Other material sources include terrain meshing,
render preparation and chunk palettes. Further sustained FPS work needs separate
profiling of those paths.

## Correctness and build

- `MTL_DEBUG_LAYER=1 ./gradlew build`: passes in 23 seconds (`build.txt`).
- Deterministic map/reference parity: 30,000 operations, mutable forks, negative and
  extreme keys, removals, payload isolation and concurrent publication.
- Allocation fixture: 262,144 entries, snapshot plus 64 edits, median of nine:
  6,293,104 → 118,288 bytes (98.1% less); unchanged snapshot allocates 328 bytes.
  Fixture CPU time was 0.356 → 0.017 ms; this is not a whole-game FPS result.
- Actual transformed native block/sky map checks pass on both enabled and disabled
  paths, including bridge copies, caches, payload edits and unloading.
- NORMAL-world Default/Metal 128/16 regression route passes in 19.78 seconds with
  Metal validation enabled: torch placement/removal, sky restoration, LOD toggles,
  approach/retreat, quality-radius expansion, odd-size resize and world close
  (`regression.json`, `regression.txt`). Screenshots were inspected for the distant
  fixture and restored native detail. This route is not an exhaustive missing-chunk
  proof for every world or travel pattern.

## Reproduction

Set render distance 128, simulation distance 16, the None shader pack and Default
graphics backend. Copy a normal generated save twice before running; never use the
original save for the probe. The probe saves and exits after the capture.

```sh
MTL_DEBUG_LAYER=0 ./gradlew runClient \
  '-PmetalJvmArgs=-Xmx16G -Dmetalcraft.memoryProbeSeconds=30 -Dmetalcraft.flatLightSnapshots=true -XX:StartFlightRecording=filename=/private/tmp/metalcraft-light-flat.jfr,settings=profile,dumponexit=true' \
  --args='--graphicsBackend default --quickPlaySingleplayer "Metalcraft Memory Baseline Retry"'

MTL_DEBUG_LAYER=0 ./gradlew runClient \
  '-PmetalJvmArgs=-Xmx16G -Dmetalcraft.memoryProbeSeconds=30 -XX:StartFlightRecording=filename=/private/tmp/metalcraft-light-paged.jfr,settings=profile,dumponexit=true' \
  --args='--graphicsBackend default --quickPlaySingleplayer "Metalcraft Memory Paged"'

MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest \
  -PmetalGeometricLodTest=true -PmetalTerrainResolutionTest=true \
  -PmetalJvmArgs=-Xmx16G --args='--graphicsBackend default'
```

The diagnostic system property `metalcraft.flatLightSnapshots=true` disables this
optimization for comparison. The memory probe is absent from normal gameplay
unless `metalcraft.memoryProbeSeconds` is explicitly positive (maximum 120).
The JFR windows were selected using the world-loaded/complete log markers:
16:20:53–16:21:23 flat and 16:22:28–16:22:59 paged, local time. Allocation summaries
sum sample weights by the first Minecraft/mod stack frame; GC figures sum
`sumOfPauses` and take the maximum `longestPause` within those windows.
