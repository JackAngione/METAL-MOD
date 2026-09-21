# Terrain frame work — September 20, 2026

**Archived: the runtime changes described here were rolled back after the user
reported missing chunks and no real gameplay FPS improvement. The commands and
diagnostic switches below describe that earlier patch, not the current code.**

Implemented four changes on the native Metal path:

* High-distance extraction admits at most 16 nonurgent distant compiler snapshots
  per frame, with a soft 2 ms admission window and backpressure at 64 queued jobs.
  Sections within 64 blocks of their bounds and player edits bypass the limit.
  Deferred work remains dirty; missing neighbors do not consume admission slots.
  Vanilla continues to own visibility, snapshots, compilation, uploads and cleanup.
  One snapshot cannot be interrupted; this is not a hard total frame-time limit or
  a limit on urgent jobs, active workers, or full loaded chunks.
* Terrain uniforms share one immutable camera-matrix copy per frame. Layer enum
  iteration reuses a private array instead of cloning it for every visible section.
* A command batch records repeated vertex-buffer bindings only when the buffer or
  offset changes. Reset and overlapping vertex/uniform slots invalidate the cache.
* Native Metal encoding uses buffer-offset updates after the first binding of the
  same buffer in a batch. Vertex/fragment slots are tracked independently. Resource
  validation and ownership through GPU completion remain intact.

Off-screen sections were already excluded by native frustum visibility. Full
chunks and installed meshes remain resident: dropping them immediately on a turn
would force reloading/remeshing. This work reduces pending snapshot bursts and
submission overhead; it does not implement off-screen world-chunk eviction.

## Measurements

Apple M4 Max, macOS 27, Java 25.0.4, NORMAL `metalcraft` world, render distance 128,
simulation 16, Threaded updates, Default/Metal, None pack, 1280×720 scene presented
on a 3840×2160 fullscreen drawable. Pixel LOD and geometric LOD remain enabled.
The fixed-view live route took 32.70 seconds including lifecycle checks. Metal
validation was disabled for this timing run; the full GPU suite used validation.

The short ABBA comparison holds the same 827 rendered sections by capturing the
native frustum, removing unfinished entries once, and settling LOD updates. Server
simulation is frozen during the comparison and restored afterwards. Background
world generation continues. Each mode captures one second of stationary rendering
and 1.5 seconds after marking those same sections dirty without altering blocks.
Foreground, drawable and window mode pass 52 checks per phase. Rebuild work drains
between phases. All four rebuild phases admit exactly 827 distant snapshots and
finish with an empty compiler queue.

Means of the two stationary phase summaries per mode:

| Measurement | Original behavior | Optimized | Reduction |
| --- | ---: | ---: | ---: |
| Median CPU frame time | 1.386 ms | 1.178 ms | 15.0% |
| Mean command submission CPU/frame | 0.376 ms | 0.274 ms | 27.1% |
| Recorded command bytes/frame | 344,176 | 230,176 | 33.1% |
| Peak distant snapshot intake/frame after invalidation | 827 | 16 | 98.1% |
| Average displayed FPS | 120.25 | 120.27 | No material change |

Presentation acquisition waits dominate at about 4 ms/frame. This establishes CPU
headroom and bounded rebuild intake, **not a large gameplay FPS gain**, a dense
128-distance benchmark, or a process-RAM reduction. Two short samples per mode
are not a statistical performance qualification. [Summary](summary.json) and
[complete fixed-view report](fixed-view.json) retain the measurements.

The earlier [loading route](loading-route.json) is deliberately excluded from FPS
comparison: visible terrain grew from 508 to 1,601 sections. It confirmed intake
limits under loading (563–1,479 unbounded versus 16 bounded), but workloads differed.
It predates the native offset-update optimization. An initial short JFR route also
identified terrain draw preparation allocations and recurring selection work;
no FPS claim is based on its sparse startup-inclusive samples.

## Validation

`MTL_DEBUG_LAYER=1 ./gradlew build` passes in 20 seconds. Tests cover budget bursts,
queue saturation, deadlines, urgent bypass, next-frame retry and native fallback;
4,096 actual native uniform serializations match byte for byte. GPU readback covers
duplicate vertex bindings, offset/buffer changes, aliasing, batch reset, fragment
uniform offset changes in both directions, pipeline changes and resource lifetime,
in checked and ordinary command decoding. The first serializer test used heap
buffers, which the native Std140 writer does not support; the corrected test uses
direct buffers. Production serialization was unchanged.

Both live routes pass distant geometry, reduced pixel targets, resize, disable/
reenable, approach, retreat, expanded detail radius and world close. The final
approach restores native detail; [coarse](coarse.png) and [approach](approach.png)
screenshots were inspected. These images are integration evidence, not a dense
horizon quality comparison. [Build log](build.log), [fixed-view log](fixed-view.log).

```sh
MTL_DEBUG_LAYER=1 ./gradlew build
MTL_DEBUG_LAYER=0 ./gradlew runClient -PmetalLifecycleTest \
  -PmetalGeometricLodTest=true -PmetalTerrainResolutionTest=true \
  -PmetalTerrainGameplayProbe=true -PmetalJvmArgs=-Xmx12G \
  --args='--graphicsBackend default'
```

The diagnostic `-Dmetalcraft.unboundedTerrainWork=true` restores the original
intake/allocation/binding behavior for manual comparisons. It is not a user setting.
