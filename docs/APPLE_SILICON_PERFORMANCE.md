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

Adding a timer around drawable acquisition (`MetalPresentProbe`, read per frame by the benchmark) resolved the gap between roughly 9 ms of per-frame CPU time and a 15 ms frame interval. Acquisition is inside the measured CPU section, and it was most of it:

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

### Known gaps

The harness still has no GPU-side timing, so it can localise a stall to "not the CPU" but not to a specific stage. Metal timestamp query pools already exist in the backend (`MetalCommandEncoder`), and bracketing each submitted frame with a begin/end counter pair is the next step; Apple's Instruments Game Performance template and GPU counters remain the reference. It also does not record thermal state or system load alongside the results, which would have explained the variance above rather than leaving it inferred.

Only a floor is asserted on the numbers, because a target has to come from measurement rather than from the harness.

## Ranked implementation plan

| Rank | Change | Expected impact | Complexity |
|---:|---|---|---|
| 0 | ~~Replace the benchmark harness~~ (done; see above) | Done. Established that the traversal tail, not steady-state draw cost, is the problem | Medium |
| 1 | Persistent triple-buffered shared upload/uniform/vertex arenas; reuse blit encoders | High, and now the top target: the frame became CPU-bound once the depth-clear allocation was removed | Medium |
| 2 | Batch the JNI render ABI and replace the locked/boxed handle registry hot path | **Partly done**: caching the root device per entry gave +38%. Remaining: the boxed-`NSNumber` dictionary itself, and command batching | Medium-high |
| 3 | Remove artificial render-pass/resource churn; correct load/store actions | **Partly done**: the depth-clear scratch target is gone (20x acquire improvement). Remaining: load/store liveness, pass merging | Medium |
| 4 | Cache shader libraries, pipeline variants, texel views, and translated outputs | Medium frame-hitch/startup win | Medium |
| 5 | Argument buffers and indirect command buffers for stable draw groups | High for chunk/UI CPU submission; workload-dependent | High |
| 6 | Render the final pass directly to the drawable when legal | Medium-high GPU bandwidth win | High |
| 7 | Private heaps, aliasing, and optional untracked hazards | Medium allocation/memory win after correctness infrastructure exists | High |

### 1. Build a unified-memory fast path

Today, every `writeToBuffer` creates a shared `MTLBuffer`, maps it, copies the Java bytes, and schedules a copy to the target (`MetalCommandEncoder.java:145-154`). Texture uploads do the same with a new padded buffer (`MetalCommandEncoder.java:164-184`). Several copy paths allocate a private temporary and issue one copy for every row (`MetalCommandEncoder.java:202-216,236-248`). `MetalTransientMemory` likewise allocates a new GPU buffer for each request and ignores its `alignment` parameter when suballocating because it does not suballocate at all (`MetalTransientMemory.java:21-59,95-123`). Each native buffer copy then creates and ends a new blit encoder (`metalcraft.m:1231-1266`).

Replace this with three (optionally four under a high-performance profile) persistent `MTLStorageModeShared` arenas, one per frame in flight. Suballocate aligned slices for dynamic vertex, index, uniform, and upload data; expose each arena's `contents` once as a direct `ByteBuffer`; recycle a slot only from the command-buffer completion handler. Keep one blit encoder open across adjacent transfer commands and end it only before a render/compute pass. Let upstream transient allocation write directly into these Metal-backed slices so the normal path is Java producer -> shared unified memory -> GPU consumer, with no staging allocation or shared-to-private blit for frequently updated data.

Apple specifically recommends shared storage for CPU-populated or frequently CPU-updated data on Apple GPUs and private storage for GPU-owned data; memoryless storage is for single-pass attachments ([Apple GPU storage modes](https://developer.apple.com/documentation/metal/choosing-a-resource-storage-mode-for-apple-gpus)). Apple also recommends multiple in-flight resource instances so CPU and GPU work overlap without waits ([Synchronizing CPU and GPU work](https://developer.apple.com/documentation/metal/synchronizing-cpu-and-gpu-work)). Keep static, GPU-only geometry/textures private when traces prove that better; do not assume a private copy helps every buffer on unified memory. Test write-combined CPU cache mode only behind a benchmark flag because Apple warns that reads can be very slow and that it can have surprising pitfalls ([`MTLCPUCacheMode.writeCombined`](https://developer.apple.com/documentation/metal/mtlcpucachemode/writecombined)).

Also isolate actual readback submissions. Any pending callback currently turns submission into `waitUntilCompleted()` (`MetalCommandEncoder.java:318-330`). Use a native completion handler and only synchronize the CPU consumer that truly needs the bytes, subject to Blaze3D callback-order requirements. Never make the normal present path wait on the GPU.

### 2. Make command submission coarse-grained

Every binding and draw is a synchronized Java method followed by a separate JNI call (`MetalRenderPass.java:162-229,247-300`). Resource binding can call native code for a uniform plus both a texture and sampler before a draw (`MetalRenderPassBackend.java:216-249`). Native lookup then locks one global `NSLock`, boxes `jlong` handles into `NSNumber`, walks ownership chains, and looks up every object (`metalcraft.m:841-920`). Every pin takes another lock and hashes into an `NSMutableSet` (`metalcraft.m:197-240`). Finally, releasing any object scans every registry value for children while holding the global lock (`metalcraft.m:955-986`); per-frame temporary-buffer churn makes that O(live objects) release especially costly.

First add release-mode telemetry for JNI calls, registry lookups/lock wait, pins, releases, and live-object count. Then:

1. Submit compact native command arrays/direct-buffer structs for repeated binds and draws, validated once per batch. Keep a checked debug ABI, but make the production path coarse.
2. Replace dictionary/`NSNumber` handles with slot+generation handles in a contiguous table, store the root device directly in every slot, maintain child counts instead of scanning all objects, and shard or eliminate the render-thread lookup lock.
3. Transfer an immutable in-flight resource set to the completion handler rather than locking for every render-thread pin. Cache the last bindings on the native encoder too.
4. Bind only shader stages that use a resource; `nSetUniformBuffer`, `nSetTexture`, and `nSetSampler` currently issue both vertex and fragment calls unconditionally (`metalcraft.m:2439-2462,2534-2581`).

Apple does not publish a JNI cost model, so the exact win must come from local counters. The direction is consistent with Apple's guidance to do more GPU work with fewer CPU commands. Argument buffers explicitly reduce the overhead of assigning resources individually ([Improving CPU performance with argument buffers](https://developer.apple.com/documentation/metal/improving-cpu-performance-by-using-argument-buffers)).

Two immediate batch fixes are lower risk: the pointer-buffer indexed multi-draw overload currently loops in Java and crosses JNI per draw (`MetalRenderPassBackend.java:148-155`), and Java multi-draw creates several new primitive arrays before JNI (`MetalRenderPass.java:303-390,545-566`). Consume direct buffers in one native call. Native indirect draw-count paths still loop one Metal call per record (`metalcraft.m:2821-2904`); record stable groups into an `MTLIndirectCommandBuffer` when profiling shows repetition.

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
