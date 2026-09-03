# Apple silicon performance audit

Date: 2026-08-20  
Scope: MetalCraft `0.2.0-dev.1`, Minecraft 26.2, macOS/Apple silicon. Recommendations are based on this repository and first-party Apple documentation. Impact estimates are hypotheses until the benchmark work below validates them.

Path key: unqualified Metal backend Java files are under `src/client/java/dev/metalcraft/client/metal/`; `MetalLifecycleGameTest.java` is under `src/client/java/dev/metalcraft/client/test/`; `metalcraft.m` is `src/native/metalcraft.m`. The bundled Minecraft source citation refers to `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-043a8b3edf/26.2/minecraft-clientOnly-043a8b3edf-26.2-sources.jar!/net/minecraft/client/Minecraft.java`.

## Executive conclusion

MetalCraft already removes the main graphics translation layers: Minecraft selects a no-API GLFW window (`MetalBackend.java:21-25`), the native bridge links directly to `Metal.framework` as `arm64` (`build.gradle:43-62`), and the backend only activates in an arm64 Java process (`MetalCraftPlatform.java:11-18`). The inspected development runtime is Java 25 `aarch64`, and `build/native/macos-arm64/libmetalcraft.dylib` is an arm64 Mach-O. Apple says native code runs more efficiently than translated code and that Rosetta applies to the whole process, so continuing to reject an x86_64 JVM is correct ([Building a universal macOS binary](https://developer.apple.com/documentation/apple-silicon/building-a-universal-macos-binary), [Rosetta environment](https://developer.apple.com/documentation/apple-silicon/about-the-rosetta-translation-environment)).

The largest remaining opportunities are therefore not another graphics API swap. They are: (1) make measurement trustworthy, (2) exploit unified memory with persistent shared arenas instead of allocation-and-copy churn, (3) make Java-to-native command submission coarse-grained and remove the globally locked object registry from the render hot path, and (4) reduce tile-memory flushes and repeated resource/pipeline creation. Shaderc -> SPIR-V -> SPIRV-Cross -> MSL (`MetalShaderTranslator.java:185-232,244-305`) is a load/reload-time compatibility path, not a per-frame translation layer; cache its results rather than replacing it before measured frame bottlenecks are known.

## The previous measurements were invalid

Earlier same-machine runs reported 228 FPS for Metal, 612 for OpenGL, and 224 for Vulkan/MoltenVK, with five immediately preceding Metal runs at 114-117. Those numbers should be discarded outright. The harness was not measuring a world the renderer had to draw, for four independent reasons, each verified against the code and the captured screenshot:

- **The server never served the requested render distance.** The harness set `client.options.renderDistance()` but never called `Options.broadcastOptions()`. `ChunkMap` clamps delivery to `player.requestedViewDistance()`, which comes from the `ClientInformation` packet and is only re-sent on broadcast, so a benchmark asking for 21 chunks was served the 5 it joined with. The frame was mostly fog.
- **The scene was empty.** A spectator was parked at Y=150 over a seed-1 coastline with structures off. Roughly three quarters of the frame was sky above flat ocean.
- **The render target was a quarter of a real session.** 1920x1080 against a 3840x2160 display.
- **No streaming work was in the capture.** The camera was static after a settle delay, so no chunk was generated, meshed, or uploaded during the 60-tick sample, and spectator mode kept the HUD and held item out of the frame.

Two further effects distort any gametest-based capture and are now handled explicitly:

- **Chunk delivery starves.** `PlayerChunkSender` paces sends from a rate the client measures in wall-clock nanoseconds per chunk (`ChunkBatchSizeCalculator`). The gametest harness parks the client thread between ticks, so the rate collapses to its `MIN_CHUNKS_PER_TICK` floor of 0.01. One run held 157 of 4225 chunks after twenty minutes. The benchmark now pins the rate to `MAX_CHUNKS_PER_TICK` every tick.
- **Minecraft throttles an idle client to 30 FPS.** Under the default `InactivityFpsLimit.AFK`, `FramerateLimitTracker` clamps to `min(limit, 30)` after 60 seconds without input, and a benchmark whose camera holds still trips it by construction. It showed up unmistakably: the stationary and panning phases measured 29.9 FPS at exactly 33.333 ms per frame while spending 10 ms of CPU on each, and only the phase holding a movement key ran free. The benchmark now sets `InactivityFpsLimit.MINIMIZED`.

## Current methodology

`MetalLifecycleGameTest` with `-PmetalLifecycleBenchmark=true` generates ordinary terrain with structures on, then picks its camera site from generator noise rather than a hand-picked coordinate: `MetalBenchmarkScene` reads `ChunkGenerator.getBaseHeight` over a grid of candidates, rejects anything not comfortably inland, scores the rest on surface roughness and elevation, and chooses the heading whose terrain rises least above the camera so the view is open rather than buried in a hillside. The camera stands on that ground at eye height. Three phases are captured separately - stationary, a full 360-degree pan, and creative flight that forces continuous chunk generation, meshing, and upload - and each reports average, 1% low, 0.1% low, and CPU-time percentiles from every rendered frame, with frame pacing taken from consecutive render-loop end timestamps so presentation waits are counted rather than dropped.

The scenario asserts its own validity before it reports anything: the server must be serving the requested view distance, at least 75% of the render distance must be loaded, and the renderer must be drawing a real section count. That last gate is what catches a blocked camera, and it earned its place - one run stood on a snow peak facing a wall of blocks two metres away, with a frame full of terrain that drew almost none of the world.

### Results on the tested M4 Max

3840x2160, 32 chunks, simulation distance 16, seed `metalcraft`, site -1536,173,-128, ~2590 sections drawn, 88% of the render distance loaded. Two runs of the identical scenario, roughly ten hours apart on the same machine:

| Phase | Run A avg | Run B avg | Run A p50 CPU | Run B p50 CPU | Run A p50 interval | Run B p50 interval |
|---|---:|---:|---:|---:|---:|---:|
| Stationary | 105.7 | 69.1 | 8.901 ms | 9.105 ms | 9.064 ms | 15.678 ms |
| Pan | 183.7 | 92.8 | 4.210 ms | 5.151 ms | 4.764 ms | 9.071 ms |
| Traversal | 104.5 | 66.0 | 8.933 ms | 9.078 ms | 9.321 ms | 16.228 ms |

Two findings, and the second is the more useful one.

**The renderer is not CPU-bound, and the run-to-run variance is not CPU-side either.** Per-frame CPU time is nearly identical across the two runs - 8.90 against 9.11 ms at p50 for the stationary phase - while the frame interval nearly doubled. The CPU finishes its work in the same time and then waits longer. Whatever sets the frame rate here, and whatever moved between the two runs, is downstream of the measured CPU section: GPU execution, drawable acquisition, or presentation. macOS reported no thermal warning and the machine was on AC power for both; run B had a load average of 4.5, so external contention is the likeliest cause, which is itself the point.

**A single pass is not a measurement.** A 1.5x swing with no code change is larger than most of the improvements in the plan below, so any before-and-after comparison drawn from one pass of each phase would be measuring the machine rather than the change. The scenario therefore repeats its phases (`-PmetalBenchmarkRepeats`, default 3, interleaved so drift spreads across phases rather than landing on whichever ran last) and reports median, minimum, maximum, and spread. Quote the median; read the spread before believing any difference.

**The traversal stall reproduces regardless.** The worst frame was 158.9 ms in run A and 175.6 ms in run B, against a p99 CPU time under 13 ms in both. Streaming new terrain produces stalls an order of magnitude longer than any steady-state frame, and unlike the averages this does not move with machine state. That is the clearest defect the benchmark has surfaced, and it points at the allocation and upload churn in items 1 and 3 below.

### What the frame time was actually going into

Adding a timer around drawable acquisition (then `MetalPresentProbe`, since folded into `MetalStallProbe` as its `ACQUIRE` source) resolved the gap between roughly 9 ms of per-frame CPU time and a 15 ms frame interval. Acquisition is inside the measured CPU section, and it was most of it:

| Phase | Interval | CPU frame | of which acquire | Remaining CPU work |
|---|---:|---:|---:|---:|
| Stationary | 14.85 ms | 9.41 ms | 4.89 ms | ~4.5 ms |
| Pan | 9.21 ms | 5.43 ms | 3.77 ms | ~1.7 ms |
| Traversal | 16.26 ms | 9.44 ms | 5.97 ms | ~3.5 ms |

The renderer was not CPU-bound. Actual per-frame CPU work was 1.7 to 4.5 ms inside a 15 ms frame, and the rest was a stalled acquire. This matters for the plan below: items 1 and 2 target CPU cost that was not the constraint.

An earlier hypothesis in this document blamed the full-frame presentation blit. That was wrong and the arithmetic should have caught it sooner - roughly 66 MB per frame at 67 FPS is 4.4 GB/s against a memory system an order of magnitude faster, so the copy costs about 0.1 ms, not 15.

The actual cause was allocation on the render path. `clearDepthTexture` created a full-size BGRA scratch render target for every depth clear, purely because the pass descriptor required a colour attachment - 33 MB allocated and released per call at 3840x2160. Supporting a depth-only `MTLRenderPassDescriptor` and deleting the scratch target dropped `p50AcquireMs` from 5.965 to 0.294, a factor of twenty, and took the traversal phase from 65.2 to 78.2 FPS in the first repeat after the change. Allocating a large texture per frame stalls the pipeline far more than the bandwidth it moves.

### Measured effect of removing the depth-clear scratch target

Three repeats of each phase before and after the change, same machine, same scene, same seed, 3840x2160 at 32 chunks:

| Phase | Before | After | Change | 1% low before | 1% low after |
|---|---:|---:|---:|---:|---:|
| Stationary | 66.8 | 100.9 | +51% | 49.9 | 65.5 |
| Pan | 68.8 | 104.2 | +51% | 52.7 | 70.2 |
| Traversal | 67.2 | 99.7 | +48% | 43.7 | 57.8 |

Drawable acquisition went from 4.893 ms to 0.012 ms at p50, a factor of roughly 400, and held that across every repeat.

**The bottleneck has moved.** After the change the stationary phase shows a 9.805 ms frame interval against 9.607 ms of CPU time with acquisition at 0.012 ms, so essentially the whole frame is now inside the measured CPU section. The renderer is CPU-bound where it was previously stalled on presentation, which inverts the earlier conclusion: items 1 and 2 below - persistent shared arenas and coarse-grained JNI submission - are now the right targets, and they were not before. The remaining per-frame allocation churn they describe (`writeToBuffer` creating a shared buffer per call, `MetalTransientMemory` allocating per request, texel views and fan index buffers rebuilt per draw) is the same class of mistake that the depth-clear scratch target turned out to be, which is reason to expect more from them than their original estimates suggested.

### Profiling the CPU-bound frame, and the registry fix

With the frame CPU-bound, a 20-second `sample` of the render thread during the capture phases replaced guesswork. Two changes made by reading the code for obvious waste - a persistent triangle-fan index buffer and a texel-buffer view cache - had already produced no measurable movement (+1.2%, +0.3%, +1.3% against an 8-10% run spread), because fans and texel binds are rare in this scene. Both were kept as strictly-less-work, but they are unmeasured wins, not demonstrated ones.

The profile pointed somewhere else entirely:

| Symbol | Share of render-thread samples |
|---|---:|
| `mc_get_objects_same_device` | 19.4% |
| ...of which `mc_root_device_handle_locked` | 13.1% |
| ...of which `__CFNumberHash` alone | 3.1% |

Handle resolution was the largest identifiable cost on the thread. `mc_root_device_handle_locked` walked the ownership chain on every command, doing a dictionary lookup with a boxed `NSNumber` key at each level, to re-derive a value that cannot change: ownership is fixed when an object is registered. Resolving the root device once at registration and storing it on the entry - and reading it from the entry the hot path had already fetched, rather than looking it up a second time - reduced the walk to a field read.

| Phase | Before | After | Change |
|---|---:|---:|---:|
| Stationary | 102.1 | 141.2 | +38% |
| Pan | 104.5 | 144.4 | +38% |
| Traversal | 101.0 | 138.7 | +37% |

Per-frame CPU time fell from 9.6 ms to 6.6 ms. The lesson is the same one the depth-clear scratch target taught: both measured wins came from a profile or a probe, and both hand-picked "obvious waste" fixes measured as noise.

### Cumulative effect

Against the first trustworthy measurement of this scenario, at 3840x2160 and 32 chunks:

| Phase | Original | Now | Change | 1% low then | 1% low now |
|---|---:|---:|---:|---:|---:|
| Stationary | 66.8 | 141.2 | +111% | 49.9 | 78.5 |
| Pan | 68.8 | 144.4 | +110% | 52.7 | 73.6 |
| Traversal | 67.2 | 138.7 | +106% | 43.7 | 67.6 |

Slightly more than double, from two changes: removing a per-frame full-size texture allocation, and not re-deriving a constant on every command.

### Finding the traversal stall: per-frame attribution

The traversal tail - worst frames of 30-330 ms against a p99 CPU time under 13 ms - was the clearest
defect this benchmark had surfaced, and percentiles could not explain it. A phase could report that
its worst frame took 36 ms while the CPU section reported 8 ms, but nothing said where the other
28 ms went. `MetalStallProbe` closes that gap: it times every render-path operation capable of
blocking or allocating - drawable acquisition, both GPU waits, buffer/texture/pipeline creation,
buffer mapping, object release, staging copies, direct-buffer allocation - and the frame recorder
snapshots the accumulators once per frame. Each phase now reports its eight worst frames with their
own breakdown and a residual, so a spike either names its cause or proves the
cause is somewhere not yet instrumented.

Only events raised on the render thread are counted. Chunk meshing and resource loading create Metal
objects from worker threads; that work does not stall the frame, and attributing it to whichever
frame happened to be in flight would be worse than not measuring it.

Three things the probe established before any code changed:

- **The spikes are not Metal work.** On every spike frame, `acquire` and every Metal source read
  essentially zero. The steady-state streaming cost is real but small: one 20-second traversal phase
  spends 79 ms in staging copies across 12,826 calls moving 423 MiB, and 19 ms in 32,042 buffer
  mappings. Neither is within an order of magnitude of explaining a 200 ms frame.
- **The spikes are inside `runTick`, but outside what Minecraft times.** A `runTick` HEAD timestamp
  was added to separate the two, because the first hypothesis - that the render thread was parked
  outside the render loop - was wrong. A 219 ms frame measured `outsideLoopMs=0.026`, while
  `Minecraft.getFrameTimeNs()` reported 7.1 ms for the same frame.
- **The spikes are garbage collection.** `-Xlog:gc*` and `-Xlog:safepoint` put the longest pause and
  the longest safepoint of an entire nine-phase run at ~49 ms each, so no single pause covers a
  219 ms frame. The heap cycles from ~2.3 GB to ~6.4 GB between young collections - roughly 4 GB per
  150 ms - and the worst frames take three to five collections apiece. The remainder is the render
  thread running slowly under allocation pressure rather than being stopped by it, which is why it
  registers as neither collection time nor a safepoint.

Two measurement gaps the same work closed, both of the "the harness was not measuring what it said"
class this document has already been burned by twice. The surface now logs its present mode and
display-sync state at configuration: one run paced every phase to exactly 8.33 ms and looked like a
20% regression, when the machine was simply GPU-bound and `acquire` was absorbing the slack. And the
report header now records heap size and collector names, because a tail this sensitive to allocation
is not comparable between runs that cannot state the heap they ran on. `-PmetalJvmArgs` passes JVM
flags through so the heap can be varied deliberately.

### The per-draw allocation that fed the stall

An allocation profile (JFR, `settings=profile`) of one traversal capture attributed **4.31 GB - 18.2%
of everything the JVM allocated - to MetalCraft**, and 4.18 GB of that to a single method. During the
stationary phase it was 4.53 GB, or 43.8%: nearly half of all allocation in a scene that is not
moving.

`MetalRenderPassBackend.bindResources` called `BindGroupLayout.flattenUniforms` and
`flattenSamplers` before **every draw**, and each builds fresh lists:

| Allocation | Call chain |
|---:|---|
| 1.51 GB | `List12.toArray` <- `ArrayList.addAll` <- `flattenUniforms` <- `bindResources` |
| 0.89 GB | `ArrayList.grow` <- `addAll` <- `flattenUniforms` <- `bindResources` |
| 0.65 GB | `ArrayList.grow` <- `addAll` <- `flattenSamplers` <- `bindResources` |
| 0.40 GB | `List12.toArray` <- `addAll` <- `flattenSamplers` <- `bindResources` |
| 0.73 GB | `AbstractImmutableList.iterator` <- both flatten calls |

A pipeline's bind-group layouts are fixed once it is compiled, so this was rebuilding a constant
thousands of times per frame - the same mistake as the depth-clear scratch target and the re-derived
root device. Both flattened lists are now resolved in `MetalCompiledRenderPipeline`'s constructor and
read as fields.

| Phase | MetalCraft allocation before | after | Share of all JVM allocation | Total process allocation |
|---|---:|---:|---|---:|
| Traversal | 4.306 GB | 0.101 GB | 18.2% -> 0.6% | 23.72 -> 17.68 GB |
| Stationary | 4.527 GB | 0.079 GB | 43.8% -> 1.5% | 10.34 -> 5.41 GB |

### Removing the O(live objects) release scan

Independently, the probe measured `mc_release_object` at 31.9-34.7 us per call, 201 ms in a single
stationary phase across 6,292 releases on the render thread. It answered "does anything still name
this object as its owner?" by copying and walking every value in the registry - and the renderer
releases thousands of chunk buffers while streaming. Each entry now carries a child count maintained
at registration and release; the scan survives only on the error path, where it still names the
offending child's type. There is exactly one registry-removal site, so the count cannot drift.

| | releases | total | per release |
|---|---:|---:|---:|
| Before | 6,292 | 201.0 ms | 31.9 us |
| Before | 1,660 | 52.9 ms | 31.9 us |
| Before | 1,413 | 47.2 ms | 33.4 us |
| After | 5,627 | 35.1 ms | 6.2 us |
| After | 4,339 | 33.6 ms | 7.7 us |
| After | 1,338 | 8.3 ms | 6.2 us |

### Measured effect

Same machine, same scene, same configured surface, before as one pass and after as three interleaved
repeats. Quote the median; the pan phase's 89.7% spread in this set is a reminder of why.

| Phase | Before avg | After median | Change | 1% low before | 1% low after |
|---|---:|---:|---:|---:|---:|
| Stationary | 153.7 | 193.2 | +25.7% | 113.8 | 127.3 |
| Traversal | 159.9 | 191.2 | +19.6% | 51.6 | 104.7 |

Per-frame CPU time fell from 5.877 ms to 5.079 ms at p50 for traversal and from 6.320 ms to 4.974 ms
for stationary. The traversal 1% low roughly doubled, which is the number that describes the stutter.

**The tail is reduced but not eliminated, and what remains is not ours.** Two of three traversal
repeats now have a worst frame under 23 ms, against 197 ms before. The third still spikes to 171 ms,
and its attribution is unambiguous: 208 ms of the 215 ms attributed across that repeat's worst 1% of
frames is collection time. After the fix, MetalCraft accounts for 0.6% of allocation during
traversal; the rest is vanilla chunk meshing - `RenderPass$Draw`, `BlockPos`,
`SectionRenderDispatcher$RenderSectionBufferSlice`, and the `Object[]` and `long[]` behind them. No
further change to the Metal backend can move that. Reducing the residual stall means JVM heap and
collector tuning, or upstream changes to how Minecraft meshes chunks, and either should be measured
with the same probe rather than assumed.

Note that the numbers above were measured before the resolution reporting was fixed, and the surface
they ran against was 7680x2104 while the harness reported 3840x2160. The comparison holds because
both sides configured the same surface, but the resolution labelling them was wrong. Later
measurements in this document state the size that was actually rendered and presented.

### Cutting the per-frame CPU cost of binding and handle lookup

Three changes to the render path, measured back to back on one machine at 3840x2104, three
interleaved repeats each, against a build differing only in these three changes:

- **Resources are bound only to the stages that read them.** `nSetUniformBuffer`, `nSetTexture`,
  `nSetSampler`, and `nSetTexelBuffer` each issued a vertex call and a fragment call for every bind,
  because nothing in the Java layer knew which stage wanted what. SPIRV-Cross is now asked which
  slots each translated stage declares, and the answer is resolved into a per-slot stage mask once
  when the pipeline compiles.
- **The handle registry is a slot table rather than a boxed dictionary.** Every native command
  resolved two or three handles, and each lookup allocated an `NSNumber`, hashed a `CFNumber`, and
  probed a hash table to answer what is an array index. A handle now packs a generation above a slot
  index, so a lookup is a bounds check and a compare, and the entry's fields are struct loads rather
  than property sends. The generation is what makes slot reuse safe.
- **The render-pass adapter is reused rather than rebuilt.** It allocated four hash maps per pass,
  two of them keyed by boxed slot indices; those are now flat arrays, and one adapter instance
  serves every pass on an encoder.

| Phase | Median FPS before | after | Change | p50 CPU ms before | after | Change |
|---|---:|---:|---:|---:|---:|---:|
| Stationary | 223.0 | 300.9 | +34.9% | 4.279 | 3.186 | -25.5% |
| Pan | 228.4 | 309.3 | +35.4% | 4.216 | 3.094 | -26.6% |
| Traversal | 214.4 | 289.7 | +35.1% | 4.441 | 3.230 | -27.3% |

The 1% lows moved with the medians: 137.0 to 183.0 stationary, 141.9 to 181.9 pan, 112.1 to 136.6
traversal. Every repeat of the changed build beat every repeat of the baseline on both statistics,
which is what makes this readable against the 10-15% spread within each phase.

Per-frame CPU time is the number to trust here. These changes remove CPU work on the render thread
and nothing else, and it fell by about a quarter in every phase.

**The traversal tail is unchanged, as expected.** The worst frame in the changed build's traversal
repeats is still 175 ms. That tail is collection time driven by vanilla chunk meshing, and no change
to the Metal backend addresses it.

A fourth change followed from the second, and is recorded here without a measurement. Once a lookup
was a bounds check and a few struct loads, the lock around it was the larger cost: an `NSLock` charges
an `objc_msgSend` and a `pthread_mutex` round-trip on each of the two calls that guard every command,
plus a `pthread_once` to construct it lazily. It is now an `os_unfair_lock` with a static
initialiser - an atomic compare-and-swap when uncontended, and no `pthread_once` on the path at all.
**This is a cost removed, not a demonstrated speedup.** The effect is well below what this harness
resolves against an 8-10% spread, and no attempt was made to claim otherwise. The lock is still
global, and whether it should be on the render-thread path at all is left open beside command
batching, which would take it once per batch instead of once per command.

### The first GPU-side numbers, and a run that was neither CPU- nor GPU-bound

`GPU_FRAME` was added to the stall probe from `MTLCommandBuffer`'s own GPU start and end times, and
the first diagnostic run with it landed on a machine state that this document has previously only
been able to call "GPU-bound". It was not.

| | Stationary | Pan | Traversal |
|---|---:|---:|---:|
| p50 interval | 8.127 ms | 8.307 ms | 8.256 ms |
| p50 CPU | 3.253 ms | 2.405 ms | 3.141 ms |
| GPU busy per frame | 3.53 ms | - | - |
| p50 acquire | 4.580 ms | 5.112 ms | 5.043 ms |
| Average FPS | 120.0 | 120.0 | 118.4 |

Three things follow. The frame rate is pinned at exactly the display's 120 Hz, with a p50 interval
of 8.127 ms against a refresh interval of 8.333 ms - **even though presentation was configured
`IMMEDIATE`, the surface logged `displaySync=false`, vsync was off, and the frame limiter was
unlimited.** The same machine free-ran at 300 FPS an hour earlier on the same scene. Second, neither
processor is saturated: 3.25 ms of CPU and 3.53 ms of GPU inside an 8.13 ms frame. Third, what fills
the frame is the 4.58 ms the render thread spends waiting for a drawable.

So the run was paced by the presentation path, not by the renderer. Display sync was off, so the
remaining suspect is `CAMetalLayer`'s drawable pool - `maximumDrawableCount` is not currently set -
or the window server applying its own pacing to a layer-backed window. That is worth chasing,
because a paced run absorbs exactly the stalls the benchmark exists to find.

The harness now warns when a phase's frames arrive at the refresh interval, so a paced run cannot be
quietly compared against a free-running one. It is a warning rather than a failure: the CPU time,
GPU time, and stall attribution in such a phase are all still meaningful, and a diagnostic run is
usually after those.

This is also the first measurement that could distinguish "the GPU is the bottleneck" from "the
render thread is waiting on presentation". Before `GPU_FRAME`, both looked identical from the CPU.

### The traversal stall is not garbage collection

This document, and the handoff beside it, said the residual traversal tail was garbage collection.
That was wrong, and the way it was wrong is worth recording.

The evidence for it was that collection dominated the worst-1% totals of a traversal repeat: 208 ms
of the 215 ms attributed. Two things falsify it.

**Running the same capture under ZGC removed collection from the tail and left the spike.** Across
the worst 1% of traversal frames, collection fell from 154 ms under G1 to 0.000 ms. The worst frame
was still 158 ms, of which 158.3 ms was unattributed. A collector change that eliminates the
supposed cause and leaves the effect intact is about as clean a refutation as this harness produces.

**The worst single frame never attributed most of itself to collection anyway.** Under G1 it was
47 ms of 175 ms. The worst-1% total was misleading because it aggregates many frames, and because
collection time overlaps whatever was running rather than replacing it.

Finding the real cause meant instrumenting the parts of `runTick` that Minecraft's own frame timer
does not cover - which is most of it. `getFrameTimeNs()` starts after the drawable is acquired and
stops before submit and present, so a 175 ms frame reporting 4 ms of CPU time was not a
contradiction, just an unmeasured stretch. Adding phases for queued packet processing, the
main-thread task queue, the client tick loop, and `renderFrame` as a whole settled it in one run:

```
frame=955 intervalMs=175.731 cpuMs=3.956 outsideLoopMs=0.040 client_tasks=170.137ms/1 \
  jvm_gc=47.000ms/3 render_frame=4.016ms/1 gpu_frame=3.506ms/1 client_tick=1.210ms/1 \
  acquire=0.027ms/1 submit=0.015ms/1 buffer_map=0.010ms/15 0.6MiB upload_copy=0.005ms/2
```

170 of the 175 ms is bracketed by `Minecraft.runAllTasks()`, and the frame rendered in 4 ms with
0.015 ms of Metal work in it. The reading offered here - that the queue was full of chunk mesh
uploads scheduled from worker threads - was an inference from the bracket's name, and it is wrong:
see *The traversal stall was the test harness* below. What survives is the part that was measured,
that the stall is neither the render path nor the collector.

This also forced the attribution model to grow a distinction it had been missing. Sources are now
phases, which partition the loop and are the only ones subtracted from the interval; details, which
sit inside a phase and explain it, collection pauses among them; and `gpu_frame`, which runs
concurrently. Summing all three drove the residual to -42 ms on the frame above. With phases alone
the frames balanced to within about 0.01 ms - except for one 50 ms pan frame that attributed 45.8 ms
to the residual and almost nothing to any phase.

### Making the phases exhaustive, and what the residual was not

The four phases above did not cover `runTick`; they covered four calls inside it. Everything between
them - the presence handler, the per-tick gizmo collections opened and closed around each `tick()`,
the per-frame gizmo collection, `soundManager.updateSource`, `mouseHandler.handleAccumulatedMovement`
- fell into the residual, and so the 45.8 ms frame had four candidate explanations and no way to
choose between them. This document previously named one of them, the sound and mouse handling, as
though it had been measured. It had not been.

Bracketing them the same way would not have fixed that. A begin/end pair per stretch still discards
the gaps between the pairs, and `runTick` skips packets, tasks and ticks wholesale when
`advanceGameTime` is false, so a pair whose end sits inside that branch never fires and leaves its
start to be consumed by some later frame. The phases are recorded by a cursor instead: each boundary
closes the stretch that ended and opens the next at the same instant, via `MetalStallProbe.split`.
Every nanosecond between the first boundary and the last then lands in exactly one phase, whichever
branches the frame took.

Measured over a full run, the residual it leaves is between -0.004 and -0.001 ms per frame, against
45.8 ms before. And the stretch this document had guessed at is not where time goes: across 2353
traversal frames, `client_pre_frame` - the per-frame gizmo collection, sound, and mouse together -
totalled 7.3 ms, or 0.003 ms per frame, and its worst single frame was 0.005 ms. `client_gizmos`, the
tick section's own overhead, totalled 102.5 ms across 2748 crossings. Neither is capable of a 45.8 ms
spike. That capture was display-paced at 120 FPS and did not reproduce the spike, so this rules the
stretch out as a routine cost rather than explaining the one frame; what it settles is that the
residual is no longer a place a stall can hide.

### Naming the tasks in the queue

`CLIENT_TASKS` established that the traversal spike is the main-thread queue, and then could say
nothing further: it brackets `runAllTasks` as a whole, and the queue is a `Queue<Runnable>` that any
thread may submit to. `MetalTaskCensus` counts one level below it, keyed by the class of the runnable
- Minecraft submits almost everything as a lambda, and a lambda's class names the method that created
it. It hangs off `BlockableEventLoop.doRunTask`, the single point every queued task passes through,
guarded on the render thread and on a capture being active, and counted only at the outermost level
so that a task which drains the queue from inside itself is not charged twice.

Three benchmark runs with it recorded nothing at all: `tasks total=0.000ms/0` in every phase, with
`CLIENT_TASKS` at 0.24-0.63 ms across roughly 2350 drains - the cost of peeking at an empty queue.
None of those runs reproduced the 170 ms spike either.

An instrument that reports nothing is worth less than no instrument, because it reads as good news.
So the lifecycle test submits four empty tasks through `Minecraft.execute` during its capture and
asserts the census counted at least four; it reports
`tasks total=0.013ms/4 dev.metalcraft.client.test.MetalLifecycleGameTest$$Lambda=0.013ms/4`. Counting
and naming both work, and the assertion will fail loudly if a future version routes tasks past
`doRunTask` - the real exposure, since this hooks an internal event-loop method rather than anything
Blaze3D promises.

What the silence means is that the queue is empty during the phases this harness measures. Every
capture waits for terrain to settle first, and in three runs out of four whatever fills the queue had
already finished by then. The stall lives in the streaming window the harness deliberately skips
past, which is where the census should be pointed next; the lifecycle capture already sits in that
window and now prints its attribution.

### The traversal stall was the test harness

The queue being empty was the clue, and it took one more step to read. `CLIENT_TASKS` is bracketed
in `runTick` at `INVOKE runAllTasks` with `shift = AFTER`. So is Fabric's client gametest harness:
its `postRunTasksHook` runs at the same instruction, and `postRunTasks` marks the client as able to
accept tasks, enters a `Phaser` phase, and then blocks on `ThreadingImpl.CLIENT_SEMAPHORE.acquire()`
until the test thread hands the frame back. Applied closer to the call than this project's boundary,
the entire park fell inside a phase named for Minecraft's task queue.

How unstable that was is worth stating plainly: the spike moved from `CLIENT_TASKS` to
`CLIENT_GIZMOS` when an unrelated mixin was added to this mod, because the two injections at that one
instruction are ordered by Mixin's application order and nothing else.

The stretch is now its own phase. On the worst traversal frame of a three-repeat run:

```
frame=2319 intervalMs=166.046 cpuMs=4.038 client_post_tasks=161.086ms/1 jvm_gc=45.000ms/3 \
  render_frame=4.097ms/1 gpu_frame=2.162ms/1 client_tick=0.835ms/1 \
  client_tasks=0.000ms/1 task_drain=0.000ms/1
```

161 of 166 ms is the harness. `task_drain` - the queue's own work, timed from inside `runAllTasks`
where the park cannot reach it - is 0.000 ms, and the census names no tasks at all.

**What this invalidates.** Every traversal worst-frame figure and every traversal 1% low in this
document that predates this section was measured through that park. They are not measurements of
Minecraft and should not be used. Medians, per-frame CPU time, and whole-phase averages are
unaffected: the park spikes roughly one frame per run rather than costing every frame. The
before/after comparison in *Cutting the per-frame CPU cost of binding and handle lookup* rests on
medians and CPU time and stands.

**What is left.** Once the harness frame is set aside, the worst genuine frames of a traversal repeat
are 15 to 20 ms, and each is a collection pause:

```
frame=3689 intervalMs=19.873 cpuMs=19.823 render_frame=19.855ms/1 jvm_gc=17.000ms/1 \
  gpu_frame=2.055ms/1 submit=0.012ms/1 buffer_map=0.007ms/14 0.6MiB
```

Two repeats of that run had worst intervals of 16.0 and 21.5 ms. So collection is the real remaining
tail after all - not of the 170 ms spike, which it never was, but of a tail an order of magnitude
smaller than this document has been quoting. The allocation behind it is vanilla chunk meshing;
MetalCraft is 0.6% of allocation during traversal.

### Command batching, and a quarter of the render thread's CPU time

Every bind and every draw used to be a synchronized Java method wrapping its own JNI call, which
then took the native registry's global lock, resolved its handles, and pinned its resources before
issuing the one Metal call it existed for. `MetalRenderPassBackend.drawMultipleIndexed` - the entry
point Minecraft 26.2 draws chunk sections through - now records those commands into
`MetalCommandStream`, a flat array of 48-byte records in one reused direct buffer, and hands the
whole array to `nSubmitCommandStream`. One crossing resolves every handle, pins every resource in a
single acquisition of the in-flight set's lock, and issues every encoder call.

At 4K this scene records **about 14,000 commands per frame and submits them in three to nine
batches**. What used to be 14,000 Java monitors, 14,000 JNI transitions, and 14,000 acquisitions of
the registry lock is now that many batches of each.

Two three-repeat runs, back to back in one session at 3840x2104, the second adding
`-PmetalCommandBatching=false` to restore the per-command path and change nothing else:

| Median of three repeats | Stationary | Pan | Traversal |
|---|---:|---:|---:|
| p50 CPU per frame, batched | **3.064 ms** | **3.031 ms** | **3.043 ms** |
| p50 CPU per frame, per command | 4.086 ms | 3.911 ms | 3.979 ms |
| Average FPS, batched | **209.4** | **219.5** | **202.3** |
| Average FPS, per command | 188.7 | 199.7 | 190.7 |

Per-frame CPU time is the number to trust, for the same reason as the binding work above: this
change removes CPU work from the render thread and nothing else. It fell by 22-25% in every phase,
every paired repeat was faster batched, and in stationary and traversal the two sets of repeats do
not overlap at all. The FPS row moved with it, by 6-11%, but those sets *do* overlap in pan and
traversal, so the frame rate corroborates the CPU column rather than standing on its own. Both runs
were free-running - `p50AcquireMs` around 0.02 in every phase - so neither was paced by presentation.

**The 1% lows did not move**, and were not expected to. They are collection time driven by vanilla
chunk meshing, and batching removes CPU work rather than allocation.

The batch submission is itself timed, as the `command_batch` source: about 0.78 ms per frame for
14,000 commands, or 55 ns each - and that figure still contains every Metal encoder call the
per-command path also made. Its byte count is the size of the recorded stream, so dividing by the
48-byte record size gives the commands a frame's crossings carried.

Validation moved rather than disappeared. The recorder makes the same range checks the per-command
setters made, from buffer sizes it already knows in Java; the native decoder re-checks everything a
record can be judged on by itself - opcodes, slot indices, stage masks, counts, index alignment -
because those are integer compares, and leaves the buffer-length arithmetic alone because that is a
message send per command. `-PmetalCheckedCommands=true` turns the native range checks back on and
names the offending command by index. The shader smoke test renders the same draws through both
ABIs and through the per-command path and compares the readbacks byte for byte, which is the only
evidence that the coarse path encodes what it replaces.

### Localising GPU time to a pass

`GPU_FRAME` says whether the GPU was busy; it cannot say which pass was keeping it busy, and that is
the question the remaining GPU-side items turn on. Storing a dead attachment as `DontCare` or merging
two compatible passes is only worth doing to a pass that costs something, and a whole-frame total
that already mixes every pass together cannot rank them.

Metal answers it with counter samples written by the GPU at an encoder's boundaries. **Which boundary
is available is the whole difficulty, and it is not the one this document previously assumed.**
Querying the tested M4 Max directly:

| `supportsCounterSampling:` | M4 Max |
|---|---|
| `MTLCounterSamplingPointAtStageBoundary` | yes |
| `MTLCounterSamplingPointAtDrawBoundary` | **no** |
| `MTLCounterSamplingPointAtBlitBoundary` | **no** |
| `MTLCounterSamplingPointAtDispatchBoundary` | **no** |

So a timestamp can be taken at a pass's edges and never part-way through it. That invalidates the
suggestion above that the existing timestamp query pools were most of the answer: `nWriteRenderTimestamp`,
which samples mid-encoder through `sampleCountersInBuffer:`, requires a draw boundary and therefore
**can never succeed on the hardware this backend targets**. Nothing in Minecraft 26.2 reaches it -
`TimerQuery` and `TracyGpuProfiler` both go through `CommandEncoder.writeTimestamp`, which samples at
an encoder boundary and does work - so it has sat unreachable rather than failing loudly.

What works instead is the render pass descriptor's own `sampleBufferAttachments`, which name a sample
buffer and the indices to write at the start of the vertex stage and the end of the fragment stage.
`MetalPassCensus` interns each Blaze3D pass label to a small integer, `nBeginRenderPass` attaches two
slots of a shared 512-pass ring to the descriptor, and the samples are resolved in the command
buffer's completion handler and accumulated per label. The render thread hands native code an int per
pass rather than a string, and a pass begun while nothing is being captured attaches nothing at all -
the sample buffer is not even created.

**The resolved timestamps need no conversion.** A single pass measured both ways - stage-boundary
samples against `MTLCommandBuffer`'s `GPUStartTime`/`GPUEndTime` - agreed to the nanosecond
(7,285,125 raw ticks against 7,285,125.0 ns), so the counter is nanoseconds on the same timebase, and
`sampleTimestamps:gpuTimestamp:` returns an identical CPU and GPU value on this hardware.

The smoke test pins the relationship rather than the value: it times **one** pass inside a command
buffer that also runs an untimed clear, and requires that pass to report positive GPU time no greater
than the command buffer's own. In that test the timed pass reports 0.023 ms inside a 5.258 ms buffer.
The single-pass restriction is load-bearing, for the reason below.

#### What a pass span is, and why spans cannot be added

The first world run through this instrument reported per-pass spans summing to **1.5x to 2.2x** the
`GPU_FRAME` total for the same phase. That is not a fault in either measurement. A pass's span runs
from its vertex stage starting to its fragment stage ending, and Apple's GPU pipelines one pass's
tiling against the previous pass's fragment work, so the spans genuinely overlap.

Four passes in one command buffer on the M4 Max, timed at all four stage boundaries (microseconds,
relative to the first sample):

| pass | vertex start | vertex end | fragment start | fragment end |
|---:|---:|---:|---:|---:|
| 0 | 0 | 21,259 | 1,353 | 26,642 |
| 1 | 21,384 | 63,250 | 26,704 | 66,724 |
| 2 | 43,271 | 56,542 | 45,383 | 59,950 |
| 3 | 63,396 | 78,391 | 67,214 | 83,593 |

Pass 2 begins and ends entirely inside pass 1's span. Every pass's fragment stage starts long before
its own vertex stage ends. Summed, the spans come to 1.30x the command buffer's own GPU time, and
restricting the sum to fragment stages only still gives 1.15x - so **there is no additive
decomposition available at this sampling granularity**, not merely an inconvenient one.

Read a pass span the way `GPU_FRAME` is read: as occupancy, for ranking passes against each other,
never as a share of the frame. The aggregate is reported as `sumOfSpans` rather than `total` for that
reason - this project has already spent time on a residual driven to -42 ms by adding figures that
overlapped, and this is the same mistake with a different source.

#### First world reading

Each benchmark phase logs a `gpuPassSpans` line beside its `tasks` line, and carries the same
breakdown in the JSON. One repeat at 3840x2104, 32 chunks, on the M4 Max - a diagnostic pass, not a
before/after claim. Mean span per frame, traversal phase (4,644 frames):

| Pass | Span per frame |
|---|---:|
| Section layers for opaque | 1.769 ms |
| Section layers for translucent | 0.701 ms |
| Clouds | 0.565 ms |
| Blit render target | 0.108 ms |
| GUI before blur | 0.103 ms |
| Stars | 0.101 ms |
| Sky moon | 0.090 ms |
| Sky sun | 0.076 ms |
| Sunrise sunset | 0.073 ms |
| Sky disc | 0.036 ms |
| (depth clear), twice per frame | 0.020 ms |

Two things stand out, and neither was predictable from reading the code.

**Clouds are the third most expensive pass in the frame**, at 0.565 ms of span per frame - within a
factor of 1.25 of all translucent chunk geometry, and roughly five times the blit that presents the
frame. Whatever else is true of the cloud pass, it is not the cheap incidental it looks like.

**The sky is five separate render passes** - stars, moon, sun, sunrise/sunset, and the sky disc -
each beginning and ending its own encoder against the full 3840x2104 attachment, and together
carrying 0.376 ms of span per frame for a very small amount of geometry. That is the clearest
pass-merging candidate the ranked plan has ever had a number for. It is a candidate rather than a
finding: because spans overlap, 0.376 ms is not the time that merging them would recover, and only a
measured before/after can say what is.

### Known gaps

Per-frame attribution now covers the render path, the JVM's collection time, time spent outside the
render loop, and GPU busy time. The last of those comes from `MTLCommandBuffer`'s own GPU start and
end times, collected in the completion handler that already existed and drained by the render thread
once per frame as the `GPU_FRAME` source. It answers "is the GPU busy", not "how long did this frame
take on the GPU": command buffers are attributed to whichever frame they finished in, and they can
overlap on the GPU, so the sum can exceed the wall clock. GPU time is now also attributed per render
pass, by the stage-boundary counter samples described above; what is still missing below that is a
breakdown *within* a pass, which this hardware cannot sample for at all. Apple's Instruments Game
Performance template and GPU counters remain the reference for that.

The harness records heap size and collector names but still not thermal state or system load, which would
have explained the variance above rather than leaving it inferred - and which matters more than this
document previously assumed: across one afternoon the same scenario ran anywhere from 117 to 361 FPS
depending on machine and display state.

Only a floor is asserted on the numbers, because a target has to come from measurement rather than from the harness.

### Merging compatible passes

`submitRenderPass` no longer ends the Metal render encoder. It holds it open, and the next
`createRenderPass` continues into the same encoder when `canMerge` accepts: identical attachments at
identical mip levels, and no clear on the new pass. The previous pass's load action is irrelevant,
because whatever it loaded or cleared has already happened - which is what lets a clearing pass be
followed by loading ones, the shape the sky actually has.

Safety rests on one seam. Every command-buffer operation in `MetalCommandEncoder` already reached the
buffer through `commands()`, so ending the deferred pass there covers copies, blits, present, fences,
timestamps, the region and depth clears, and any non-mergeable pass, in one place. The two paths that
could have bypassed it do not: `MetalTransientMemory` only receives the command buffer at submission,
and `MetalGpuSurface.blitFromTexture` routes through `blitToDrawable`.

It merges about four times per frame, and it merges more than the sky. With merging on, `Stars`,
`Sky moon`, `Sky sun`, `Sunrise sunset` **and** `Section layers for opaque` all disappear from
`gpuPassSpans`, absorbed into `Sky disc` - one span per frame covering the whole merged group. The
main scene becomes a single encoder. **This defeats the per-pass instrument**, which is what
`-PmetalPassMerging=false` is for: turn merging off to get the per-pass breakdown back.

#### What a round trip costs, measured in isolation

The game measurement is confounded (below), so the mechanism was measured on its own: N passes into a
3840x2104 colour and depth32 target, interleaved separate-versus-merged so any drift hits both alike,
median of 60 iterations, with no drawable and no compositor involved.

| Passes, 3840x2104 colour + depth | Separate encoders | One merged encoder | Saved |
|---|---:|---:|---:|
| 5, full-screen coverage | 0.225 ms | 0.056 ms | 0.169 ms |
| 13, full-screen coverage | 0.893 ms | 0.081 ms | 0.812 ms |
| 5, tiny geometry | 0.078 ms | 0.046 ms | 0.033 ms |

An avoided round trip is worth 0.04 to 0.07 ms when the attachment is genuinely dirty. The third row
is the important caveat: with only a small triangle drawn, separate encoders cost barely more than a
merged one, so **Apple's driver is already eliding most of the round trip when little was written**.
The saving is real, but it scales with how much of the attachment a pass actually dirties rather than
with the number of passes.

#### Why there is still no frame-rate number

Four paired game runs were taken and **none of them is a valid A/B**, because presentation pacing
changed between runs of the identical build. In every pair one run sat at exactly 120.0 FPS with an
8.33 ms interval and a ~5 ms `p50AcquireMs`, while the other free-ran at 210-290 FPS with
`p50AcquireMs` around 0.017 ms. Reversing the order moved the pacing to the other configuration, and
inserting a throwaway warm-up run did not remove it.

Both states report `presentMode=IMMEDIATE displaySync=false`, so this is not the layer's own
synchronisation - `MCMetalSurface` has it switched off. It is the compositor retiring drawables at a
fixed rate, which is the explanation the standing ~4.55 ms acquire question in
[NEXT_STEPS.md](NEXT_STEPS.md) had been missing: the wait is intermittent, it is not display sync,
and it toggles between runs of one build.

`gpu_frame` is no way around it. Under pacing the command buffer's `GPUEndTime` absorbs presentation
waiting, so GPU busy per frame reads *higher* when paced - 6.5 ms against 4.9 ms for the same scene -
and the paced and free comparisons then point in opposite directions.

The one free-running run of each configuration that exists suggests roughly 3 to 4% higher average
FPS, and the traversal repeats did not overlap. That is recorded only as a reason to keep going. **It
is not a result**: the two runs come from different pairs, which is exactly the cross-session
comparison this document's own methodology forbids.

## Ranked implementation plan

Current status of each item, and the work that is open now, is tracked in [NEXT_STEPS.md](NEXT_STEPS.md). Some descriptions below predate later changes; that file names which.

| Rank | Change | Expected impact | Complexity |
|---:|---|---|---|
| 0 | ~~Replace the benchmark harness~~ (done; see above) | Done. Established that the traversal tail, not steady-state draw cost, is the problem | Medium |
| 1 | Persistent triple-buffered shared upload/uniform/vertex arenas; reuse blit encoders | High, and now the top target: the frame became CPU-bound once the depth-clear allocation was removed | Medium |
| 2 | Batch the JNI render ABI and replace the locked/boxed handle registry hot path | **Done**: caching the root device per entry gave +38%; the O(live objects) release scan became a child count; the boxed-`NSNumber` dictionary is now a slot+generation table and stage-selective binding halves the bind calls, together worth +35% and a quarter of the per-frame CPU time; command batching then took another 22-25% of per-frame CPU off the multi-draw path. Remaining: argument buffers, and whether the registry lock belongs on the render thread at all | Medium-high |
| 2b | ~~Stop rebuilding the flattened bind-group layout per draw~~ (done) | Done. Removed 98% of the renderer's Java allocation and gave +20-26%; see above | Low |
| 3 | Remove artificial render-pass/resource churn; correct load/store actions | **Partly done**: the depth-clear scratch target is gone (20x acquire improvement). Remaining: load/store liveness, pass merging | Medium |
| 4 | Cache shader libraries, pipeline variants, texel views, and translated outputs | Medium frame-hitch/startup win | Medium |
| 5 | Argument buffers and indirect command buffers for stable draw groups | High for chunk/UI CPU submission; workload-dependent | High |
| 6 | Render the final pass directly to the drawable when legal | Medium-high GPU bandwidth win | High |
| 7 | Private heaps, aliasing, and optional untracked hazards | Medium allocation/memory win after correctness infrastructure exists | High |

### 1. Build a unified-memory fast path

**Partly done.** `MetalTransientMemory` now implements three rotating frame slots with suballocating
arenas, and `writeToBuffer` and `writeToTexture` allocate staging out of them rather than creating a
shared `MTLBuffer` per call. The description below is the original plan; what is left of it is
blit-encoder reuse, the row-at-a-time private-temporary copy paths
(`MetalCommandEncoder.copyBufferToTexture` and the padded `copyTextureToBuffer`), and isolating
readback submissions so a pending callback does not turn `submit()` into `waitUntilCompleted()`.

The original plan, for the parts still open: replace this with three (optionally four under a high-performance profile) persistent `MTLStorageModeShared` arenas, one per frame in flight. Suballocate aligned slices for dynamic vertex, index, uniform, and upload data; expose each arena's `contents` once as a direct `ByteBuffer`; recycle a slot only from the command-buffer completion handler. Keep one blit encoder open across adjacent transfer commands and end it only before a render/compute pass. Let upstream transient allocation write directly into these Metal-backed slices so the normal path is Java producer -> shared unified memory -> GPU consumer, with no staging allocation or shared-to-private blit for frequently updated data.

Apple specifically recommends shared storage for CPU-populated or frequently CPU-updated data on Apple GPUs and private storage for GPU-owned data; memoryless storage is for single-pass attachments ([Apple GPU storage modes](https://developer.apple.com/documentation/metal/choosing-a-resource-storage-mode-for-apple-gpus)). Apple also recommends multiple in-flight resource instances so CPU and GPU work overlap without waits ([Synchronizing CPU and GPU work](https://developer.apple.com/documentation/metal/synchronizing-cpu-and-gpu-work)). Keep static, GPU-only geometry/textures private when traces prove that better; do not assume a private copy helps every buffer on unified memory. Test write-combined CPU cache mode only behind a benchmark flag because Apple warns that reads can be very slow and that it can have surprising pitfalls ([`MTLCPUCacheMode.writeCombined`](https://developer.apple.com/documentation/metal/mtlcpucachemode/writecombined)).

Also isolate actual readback submissions. Any pending callback currently turns submission into `waitUntilCompleted()` (`MetalCommandEncoder.java:318-330`). Use a native completion handler and only synchronize the CPU consumer that truly needs the bytes, subject to Blaze3D callback-order requirements. Never make the normal present path wait on the GPU.

### 2. Make command submission coarse-grained

Every binding and draw outside `drawMultipleIndexed` is still a synchronized Java method followed by a separate JNI call (`MetalRenderPass.java`); the multi-draw path is batched, and the effect is measured above. Resource binding can call native code for a uniform plus both a texture and sampler before a draw (`MetalRenderPassBackend.java`), though each such call now reaches one stage rather than two. Native lookup then locked one global `NSLock`, boxed `jlong` handles into `NSNumber`, walked ownership chains, and looked up every object. Only the lock remains; the rest is described under the measured effect above, and a batch now takes it once per 256 commands rather than once per command. Every pin outside a batch takes another lock and hashes into an `NSMutableSet` (`metalcraft.m`). Finally, releasing any object scans every registry value for children while holding the global lock (`metalcraft.m:955-986`); per-frame temporary-buffer churn makes that O(live objects) release especially costly.

First add release-mode telemetry for JNI calls, registry lookups/lock wait, pins, releases, and live-object count. Then:

1. ~~Submit compact native command arrays/direct-buffer structs for repeated binds and draws, validated once per batch. Keep a checked debug ABI, but make the production path coarse.~~ Done, for `drawMultipleIndexed`: see the measured effect above. Remaining here: the immediate path still stands for every other entry point, and nothing has shown that batching those is worth the branch.
2. ~~Replace dictionary/`NSNumber` handles with slot+generation handles in a contiguous table, store the root device directly in every slot, maintain child counts instead of scanning all objects.~~ Done. Remaining here: shard or eliminate the render-thread lookup lock, which is still one global `NSLock`.
3. Transfer an immutable in-flight resource set to the completion handler rather than locking for every render-thread pin. Batching pins a whole batch under one acquisition (`pinAll:count:`), which removes the per-command lock round-trip but still hashes each object into an `NSMutableSet`. Cache the last bindings on the native encoder too.
4. ~~Bind only shader stages that use a resource.~~ Done: the translated shaders are reflected for the slots each stage declares, and the bind calls take a stage mask. See the measured effect above.

Apple does not publish a JNI cost model, so the exact win must come from local counters. The direction is consistent with Apple's guidance to do more GPU work with fewer CPU commands. Argument buffers explicitly reduce the overhead of assigning resources individually ([Improving CPU performance with argument buffers](https://developer.apple.com/documentation/metal/improving-cpu-performance-by-using-argument-buffers)).

Two batch fixes look easy but are not currently worth doing: the pointer-buffer indexed multi-draw overload loops in Java and crosses JNI per draw, and Java multi-draw creates several new primitive arrays before JNI. Minecraft 26.2 reaches neither - chunk sections and the world border both go through `drawMultipleIndexed`, and nothing in the client calls `multiDrawIndexed`. Fix them when a caller appears, or when a mod needs them; measuring first means finding the call site first. Native indirect draw-count paths still loop one Metal call per record (`metalcraft.m:2821-2904`); record stable groups into an `MTLIndirectCommandBuffer` when profiling shows repetition.

### 3. Respect the tile renderer and stop creating render-loop objects

`clearDepthTexture` allocates a full-size BGRA scratch render target solely because the local pass descriptor requires a color attachment (`MetalCommandEncoder.java:129-141`; `MetalRenderPass.java:130-140`). Support a depth-only `MTLRenderPassDescriptor` and remove this allocation and bandwidth entirely. Fold clears into the consumer pass's load action whenever Blaze3D ordering permits.

The general pass adapter always stores both color and depth (`MetalCommandEncoder.java:58-78`), even when an attachment is dead after the pass. Propagate liveness so transient attachments use `MTLStoreActionDontCare`; merge adjacent compatible passes and avoid attachment ping-pong. Use memoryless textures only for depth/stencil/MSAA or intermediates whose contents never leave one pass. Apple says memoryless storage remains in tile memory and can substantially reduce memory use/bandwidth, and advises merging compatible passes and avoiding unnecessary stores on Apple GPUs ([Memoryless storage](https://developer.apple.com/documentation/metal/mtlstoragemode/memoryless), [Apple-silicon Metal optimization](https://developer.apple.com/videos/play/wwdc2020/10632/)).

Cache immutable or range-keyed objects. `nSetTexelBuffer` creates a new texture-buffer view every time a changed binding is set (`metalcraft.m:2465-2531`); retain views by `(buffer, offset, length, format)` for the buffer lifetime. Triangle-fan emulation allocates, fills, and releases a new index buffer per draw (`MetalRenderPassBackend.java:261-291`); use a persistent geometrically growing fan-index buffer. Apple recommends creating persistent objects early and reusing buffers/textures rather than creating resources in the render loop ([Persistent objects](https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/MTLBestPracticesGuide/PersistentObjects.html)).

### 4. Cache compilation work instead of changing runtime graphics APIs

One Blaze3D pipeline is translated once, but `compilePipeline` constructs separate with-depth and without-depth native pipelines from identical MSL (`MetalGpuDevice.java:222-252`). Each native call recompiles vertex and fragment source into new `MTLLibrary` objects (`metalcraft.m:2101-2143`) before creating the pipeline state (`metalcraft.m:2213-2237`). Cache `MTLLibrary`/`MTLFunction` by MSL hash and share them across attachment/depth variants. Persist translation outputs by source+defines+translator version, and add `MTLBinaryArchive` caching keyed by GPU family/OS/backend version. Build known variants asynchronously during loading, not on first draw.

Apple identifies runtime MSL compilation as expensive, recommends reusing functions and pipeline states, and documents binary archives to avoid compatible runtime compilation ([Functions and libraries](https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/MTLBestPracticesGuide/FunctionsandLibraries.html), [Binary archives](https://developer.apple.com/documentation/metal/creating-binary-archives-from-device-built-pipeline-state-objects), [Asynchronous pipelines](https://developer.apple.com/library/archive/documentation/3DDrawing/Conceptual/MTLBestPracticesGuide/Pipelines.html)). Resource-pack shaders remain dynamic, so precompile only bundled/stable shaders and use an invalidatable on-device cache for the rest.

### 5. Adopt argument buffers, ICBs, and direct presentation selectively

After the simpler batching work, translate the flattened Blaze3D binding ABI into one reusable argument buffer per material/bind group. This replaces repeated uniform/texture/sampler calls and makes stable chunk materials cheap to rebind. Combine read-only resources in heaps so residency can be declared per heap. Apple states that argument buffers reduce critical-path CPU commands and that heaps reduce per-resource residency work ([Argument buffers](https://developer.apple.com/documentation/metal/managing-groups-of-resources-with-argument-buffers), [Argument buffers with heaps](https://developer.apple.com/documentation/metal/using-argument-buffers-with-resource-heaps)).

Use CPU-built reusable ICBs first for stable HUD/static chunk batches; consider GPU-built ICBs only with a later GPU culling design. Apple documents ICBs as a way to reuse commands and avoid repeated allocation/encoding ([CPU-encoded ICBs](https://developer.apple.com/documentation/metal/encoding-indirect-command-buffers-on-the-cpu)). Keep a capability/fallback path for the full supported Apple-silicon range.

Presentation currently performs a full-frame texture copy whenever source and drawable match, or a sampling pass when they do not (`metalcraft.m:1584-1619`). Investigate borrowing the drawable texture for the final Blaze3D pass when screenshots/post-processing no longer need the offscreen color texture. This could remove one 4-byte-per-pixel read and write each frame, but it is a high-complexity lifetime/API change and should follow a GPU trace proving the copy is significant.

## Aggressive memory-for-speed profile

The user accepts higher memory consumption for FPS. On the tested M4 Max, Metal reports a 53,084 MiB recommended working set (`run/logs/latest.log:58`). Use that headroom deliberately, not as permission for unbounded retention:

- Start with three 128-256 MiB shared dynamic/upload arenas on this class of device, grow geometrically from measured peak demand, and keep old arenas available for reuse instead of reallocating every frame.
- Keep persistent private heaps for long-lived chunk/static resources and separate aliasable heaps for non-overlapping transient render targets.
- Retain translated MSL, native libraries, PSOs, depth/sampler states, texel views, argument buffers, and profitable ICBs with size/hit telemetry and an LRU fallback under pressure.
- Expose `MTLDevice.currentAllocatedSize` alongside `recommendedMaxWorkingSetSize`; apply a configurable soft budget and evict cold caches before approaching it. Apple defines the recommendation as the footprint below which allocations should not affect runtime performance ([recommended working set](https://developer.apple.com/documentation/metal/mtldevice/recommendedmaxworkingsetsize), [current allocated size](https://developer.apple.com/documentation/metal/mtldevice/currentallocatedsize)).
- Record resident/cache bytes, peak per-frame arena use, overflow allocations, heap fragmentation, cache hit rates, and OS thermal/memory-pressure events in every benchmark. More unified memory helps only while it avoids allocation, copying, and compilation; paging or memory compression will reverse the gain.

Do not disable Metal hazard tracking globally at first. Heaps can reduce allocation cost and alias memory, but untracked resources require complete explicit synchronization. Adopt them after the renderer has pass-level lifetime data and GPU-trace validation ([Memory heaps](https://developer.apple.com/documentation/metal/memory-heaps), [Heaps and fences](https://developer.apple.com/documentation/metal/implementing-a-multistage-image-filter-using-heaps-and-fences)).

## Recommended delivery order

1. Land the per-frame benchmark/trace output and reproduce all three backends under native arm64.
2. Add counters around JNI, registry, native object allocation, buffer/texture/view creation, blit encoders, command buffers, and waits.
3. Implement triple-buffered shared arenas, direct transient slices, persistent fan indices, and batched blits.
4. Replace registry scans/boxing and batch resource+draw commands across JNI.
5. Remove the depth-clear scratch target; propagate load/store liveness and merge compatible passes.
6. Cache MSL libraries/functions, variants, texel views, and binary archives.
7. Prototype argument buffers/ICBs and direct-to-drawable final rendering behind independent flags; keep only measured wins.

This order attacks the current renderer's clearest CPU and memory-traffic costs while preserving its most important architectural success: a native arm64 Java process issuing direct Metal commands with no Vulkan, MoltenVK, or OpenGL layer in the Metal path.
