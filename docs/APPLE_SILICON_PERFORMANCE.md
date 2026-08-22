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

170 of the 175 ms is `Minecraft.runAllTasks()`, the main-thread task queue, which during terrain
streaming is dominated by chunk mesh uploads scheduled from worker threads. The frame rendered in
4 ms. The Metal work inside those 170 ms is 0.015 ms. The collection pauses happen *inside* the
queue, driven by the same meshing that fills it - a correlate, not a cause.

This also forced the attribution model to grow a distinction it had been missing. Sources are now
phases, which partition the loop and are the only ones subtracted from the interval; details, which
sit inside a phase and explain it, collection pauses among them; and `gpu_frame`, which runs
concurrently. Summing all three drove the residual to -42 ms on the frame above. With phases alone
the frames balance to within about 0.01 ms, and a stall outside every phase - one 50 ms frame turned
out to spend 46 ms in the sound and mouse handling between the tick loop and `renderFrame` - is now
visible instead of hidden in a residual.

### Known gaps

Per-frame attribution now covers the render path, the JVM's collection time, time spent outside the
render loop, and GPU busy time. The last of those comes from `MTLCommandBuffer`'s own GPU start and
end times, collected in the completion handler that already existed and drained by the render thread
once per frame as the `GPU_FRAME` source. It answers "is the GPU busy", not "how long did this frame
take on the GPU": command buffers are attributed to whichever frame they finished in, and they can
overlap on the GPU, so the sum can exceed the wall clock. Localising a stall to a specific GPU
*stage* still needs encoder-level counter samples, for which the timestamp query pools already exist
in the backend; Apple's Instruments Game Performance template and GPU counters remain the reference.

The harness records heap size and collector names but still not thermal state or system load, which would
have explained the variance above rather than leaving it inferred - and which matters more than this
document previously assumed: across one afternoon the same scenario ran anywhere from 117 to 361 FPS
depending on machine and display state.

Only a floor is asserted on the numbers, because a target has to come from measurement rather than from the harness.

## Ranked implementation plan

Current status of each item, and the work that is open now, is tracked in [NEXT_STEPS.md](NEXT_STEPS.md). Some descriptions below predate later changes; that file names which.

| Rank | Change | Expected impact | Complexity |
|---:|---|---|---|
| 0 | ~~Replace the benchmark harness~~ (done; see above) | Done. Established that the traversal tail, not steady-state draw cost, is the problem | Medium |
| 1 | Persistent triple-buffered shared upload/uniform/vertex arenas; reuse blit encoders | High, and now the top target: the frame became CPU-bound once the depth-clear allocation was removed | Medium |
| 2 | Batch the JNI render ABI and replace the locked/boxed handle registry hot path | **Mostly done**: caching the root device per entry gave +38%; the O(live objects) release scan became a child count; the boxed-`NSNumber` dictionary is now a slot+generation table and stage-selective binding halves the bind calls, together worth +35% and a quarter of the per-frame CPU time. Remaining: command batching itself | Medium-high |
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

Every binding and draw is a synchronized Java method followed by a separate JNI call (`MetalRenderPass.java:162-229,247-300`). Resource binding can call native code for a uniform plus both a texture and sampler before a draw (`MetalRenderPassBackend.java`), though each such call now reaches one stage rather than two. Native lookup then locked one global `NSLock`, boxed `jlong` handles into `NSNumber`, walked ownership chains, and looked up every object. Only the lock remains; the rest is described under the measured effect above. Every pin takes another lock and hashes into an `NSMutableSet` (`metalcraft.m:197-240`). Finally, releasing any object scans every registry value for children while holding the global lock (`metalcraft.m:955-986`); per-frame temporary-buffer churn makes that O(live objects) release especially costly.

First add release-mode telemetry for JNI calls, registry lookups/lock wait, pins, releases, and live-object count. Then:

1. Submit compact native command arrays/direct-buffer structs for repeated binds and draws, validated once per batch. Keep a checked debug ABI, but make the production path coarse.
2. ~~Replace dictionary/`NSNumber` handles with slot+generation handles in a contiguous table, store the root device directly in every slot, maintain child counts instead of scanning all objects.~~ Done. Remaining here: shard or eliminate the render-thread lookup lock, which is still one global `NSLock`.
3. Transfer an immutable in-flight resource set to the completion handler rather than locking for every render-thread pin. Cache the last bindings on the native encoder too.
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
