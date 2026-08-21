# Apple silicon performance audit

Date: 2026-08-20  
Scope: MetalCraft `0.2.0-dev.1`, Minecraft 26.2, macOS/Apple silicon. Recommendations are based on this repository and first-party Apple documentation. Impact estimates are hypotheses until the benchmark work below validates them.

Path key: unqualified Metal backend Java files are under `src/client/java/dev/metalcraft/client/metal/`; `MetalLifecycleGameTest.java` is under `src/client/java/dev/metalcraft/client/test/`; `metalcraft.m` is `src/native/metalcraft.m`. The bundled Minecraft source citation refers to `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-clientOnly-043a8b3edf/26.2/minecraft-clientOnly-043a8b3edf-26.2-sources.jar!/net/minecraft/client/Minecraft.java`.

## Executive conclusion

MetalCraft already removes the main graphics translation layers: Minecraft selects a no-API GLFW window (`MetalBackend.java:21-25`), the native bridge links directly to `Metal.framework` as `arm64` (`build.gradle:43-62`), and the backend only activates in an arm64 Java process (`MetalCraftPlatform.java:11-18`). The inspected development runtime is Java 25 `aarch64`, and `build/native/macos-arm64/libmetalcraft.dylib` is an arm64 Mach-O. Apple says native code runs more efficiently than translated code and that Rosetta applies to the whole process, so continuing to reject an x86_64 JVM is correct ([Building a universal macOS binary](https://developer.apple.com/documentation/apple-silicon/building-a-universal-macos-binary), [Rosetta environment](https://developer.apple.com/documentation/apple-silicon/about-the-rosetta-translation-environment)).

The largest remaining opportunities are therefore not another graphics API swap. They are: (1) make measurement trustworthy, (2) exploit unified memory with persistent shared arenas instead of allocation-and-copy churn, (3) make Java-to-native command submission coarse-grained and remove the globally locked object registry from the render hot path, and (4) reduce tile-memory flushes and repeated resource/pipeline creation. Shaderc -> SPIR-V -> SPIRV-Cross -> MSL (`MetalShaderTranslator.java:185-232,244-305`) is a load/reload-time compatibility path, not a per-frame translation layer; cache its results rather than replacing it before measured frame bottlenecks are known.

## Current measurements are exploratory only

Fresh same-machine control runs on an Apple M4 Max reported:

| Backend | Reported FPS | Reported `getFrameTimeNs()` |
|---|---:|---:|
| Direct Metal | 228 | 1.927 ms |
| OpenGL | 612 | 1.527 ms |
| Vulkan/MoltenVK | 224 | 0.454 ms |

The evidence is in `run/logs/2026-08-20-6.log.gz:85,251`, `run/logs/2026-08-20-7.log.gz:85,318`, and `run/logs/latest.log:85,251`. Five immediately preceding Metal runs instead report only 114–117 FPS (`run/logs/2026-08-20-{1..5}.log.gz`, performance records at lines 227/249/248/226/250). A 2x Metal swing under the nominally identical harness is enough to disqualify these values from publication or optimization decisions.

The harness explains much of the uncertainty:

- It samples only 60 times, once per game tick (`MetalLifecycleGameTest.java:23,115-123`), rather than recording every rendered frame. At 100+ FPS this misses most frames and cannot produce valid worst-frame or percentile statistics.
- `getFps()` is updated in one-second buckets by Minecraft (`Minecraft.java:1421-1435,1514-1516` in the bundled source), yet the test averages repeated observations of it (`MetalLifecycleGameTest.java:115-123`). Sample-window phase and earlier work can therefore influence the result.
- Minecraft assigns `frameTimeNs` from a CPU wall-clock interval before swap/presentation (`Minecraft.java:1390-1395,1518-1520` in the bundled source). The test labels the maximum sparse sample as "Metal render time" (`MetalLifecycleGameTest.java:132-134`) but records no begin/end GPU counter pair. This is not a GPU-time distribution and cannot identify whether Java, JNI, driver encoding, GPU execution, presentation, or a stall is limiting the frame.
- The 100 FPS threshold implies a 10 ms average interval while the independent 5 ms sampled-frame threshold implies 200 FPS (`MetalLifecycleGameTest.java:24-26,126-134`). Those gates do not describe one coherent target.
- The camera is static after teleport and a fixed settle delay (`MetalLifecycleGameTest.java:74-78`); it does not replay chunk upload, traversal, particles, UI, or shader-reload workloads. There are no repeated randomized runs, thermal-state controls, or 1%/0.1% lows.

Replace this with per-frame CPU timestamps plus Metal counter samples bracketing each submitted frame, a deterministic camera/input trace, warmup followed by a substantially longer capture, at least 5 repeated runs per backend, and CSV/JSON output containing median, p95, p99, 1% low, 0.1% low, CPU encode time, GPU time, present interval, allocation counts, and thermal state. Verify native arm64/Rosetta state in the result header. Apple recommends a measure-analyze-improve loop and the Instruments Game Performance template, which correlates CPU, Metal, GPU, display, allocation, and thermal timelines ([graphics performance methodology](https://developer.apple.com/documentation/metal/improving-your-games-graphics-performance-and-settings), [Instruments Game Performance](https://developer.apple.com/documentation/xcode/analyzing-the-performance-of-your-metal-app), [GPU counters](https://developer.apple.com/documentation/metal/gpu-counters-and-counter-sample-buffers)). Add opt-in `MTLCaptureManager` scopes around representative frames for GPU-trace comparison ([MTLCaptureManager](https://developer.apple.com/documentation/metal/mtlcapturemanager)).

## Ranked implementation plan

| Rank | Change | Expected impact | Complexity |
|---:|---|---|---|
| 0 | Replace the benchmark harness as above | Enables every other decision; no credible FPS claim without it | Medium |
| 1 | Persistent triple-buffered shared upload/uniform/vertex arenas; reuse blit encoders | High CPU/allocation win; may also remove avoidable copies | Medium |
| 2 | Batch the JNI render ABI and replace the locked/boxed handle registry hot path | High when CPU/draw-call bound | Medium-high |
| 3 | Remove artificial render-pass/resource churn; correct load/store actions | High if clears/pass bandwidth occur per frame | Medium |
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
