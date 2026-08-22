# Next steps

Last updated: 2026-08-22, after the render-path CPU work on `metal-benchmark-and-perf`.

A handoff for whoever picks up the performance work. It records what is known, what is measured,
what is still open, and which claims elsewhere in this repository are now out of date. Read
[APPLE_SILICON_PERFORMANCE.md](APPLE_SILICON_PERFORMANCE.md) for the full history; this file is the
working list.

## The one rule that matters here

Every measured win in this renderer came from a probe or a profile. Every fix picked by reading code
for "obvious waste" measured as noise, twice, against an 8-10% run-to-run spread. Instrument first,
change second, and re-measure before believing the change. The tools to do that already exist and
are described below - you should not need to build new ones for most of the work listed here.

## Reproducing a measurement

```bash
./gradlew runClient -PmetalLifecycleTest -PmetalLifecycleBenchmark=true -PmetalBenchmarkRepeats=3 -PmetalBenchmarkResolution=3840x2160
```

Roughly 10 minutes: about 4 minutes generating terrain, then nine 20-second phases. Results land in
`run/benchmarks/metalcraft-metal.json` and in the log as `Metal benchmark result:` lines.

Useful flags:

- `-PmetalBenchmarkRepeats=1` for a fast diagnostic pass. Never for a before/after claim.
- `-PmetalBenchmarkResolution=3840x2160` - pass it always. Without it the harness sizes itself from
  the primary display, which fails outright if the screen sleeps mid-setup, and which silently
  changes the workload if you ever run on a different display.
- `-PmetalJvmArgs="..."` passes JVM flags through, e.g. `-Xlog:gc*:file=gc.log:time,uptime` or
  `-XX:StartFlightRecording=filename=alloc.jfr,settings=profile`.

The requested resolution is a request. A windowed frame cannot always reach the display's full pixel
count - macOS keeps one below the menu bar, so a 3840x2160 request lands at 3840x2104 here. The run
warns when that happens and reports the size it achieved, and the header carries both the rendered
size and the drawable so the two can be seen to agree.

### Reading the stall probe

Each phase logs `Metal benchmark stall:` lines - a whole-phase total, a worst-1% total, and the
eight worst individual frames:

```
phase=stationary frame=799 intervalMs=11.840 cpuMs=7.192 outsideLoopMs=0.016 \
  acquire=4.590ms/1 gpu_frame=3.022ms/1 jvm_gc=3.000ms/1 buffer_map=0.011ms/15 0.7MiB \
  unattributedMs=1.198
```

- `intervalMs` - render-loop tail to tail, the number a player feels.
- `cpuMs` - Minecraft's own `getFrameTimeNs()`. **It does not cover all of `runTick`.** A frame can
  show 5 ms here inside a 170 ms interval.
- `outsideLoopMs` - between the previous loop ending and this one starting. Near zero means the
  render thread was inside `runTick` the whole time.
- `gpu_frame` - GPU busy time from command buffers that *completed* during the frame. It is the one
  source that does not block the render thread, so it is reported beside the interval and never
  subtracted from it. Command buffers overlap on the GPU and are attributed to whichever frame they
  finished in, so read it as occupancy, not as this frame's GPU cost.
- `unattributedMs` - `interval - outsideLoop - everything that blocked the render thread`. Ordinary
  rendering work lives here too, so a few ms is normal; a large value on a spike frame means the
  cause is not yet instrumented.

Sources are defined in `MetalStallProbe.Source`. Adding one is three lines: an enum constant, a
`begin()`/`end()` pair at the call site. Only render-thread events are recorded, deliberately.

## Environment pitfalls that have already cost time

- **`run/options.txt` can be left on `preferredGraphicsBackend:"opengl"`** by Minecraft's crash
  recovery after a failed run. The next run then silently benchmarks OpenGL. The harness asserts on
  the backend, so it fails loudly - but reset it to `"default"` rather than debugging the assertion.
- **A sleeping or locked display** makes `glfwGetPrimaryMonitor` return nothing and aborts the run.
  This has now cost time twice. Always pass an explicit resolution.
- **Machine state dominates absolute numbers.** Across one afternoon the identical scenario ran
  between 117 and 361 FPS. A run can be GPU-bound (watch for `p50AcquireMs` in the milliseconds) or
  free-running (`p50AcquireMs` around 0.009 to 0.3). These are not comparable. Compare only runs
  from the same session, back to back, and prefer allocation profiles, which are robust to machine
  state, when the effect is allocation.

## Open work

### 1. The residual traversal tail is garbage collection, and it is not ours

**Status:** diagnosed, not fixed. This is the largest remaining item and the reason the original
"traversal stall" ticket is not closed. It survived the render-path CPU work unchanged, which is
what the diagnosis predicts: the worst traversal frame is still 175 ms.

**Evidence:** the heap cycles roughly 4 GB per 150 ms; the worst frames take three to five
collections apiece. Longest single GC pause and longest safepoint in a full nine-phase run are both
about 49 ms, so the pauses alone do not cover a 171 ms frame - the rest is the render thread running
slowly under allocation pressure rather than being stopped by it.

**MetalCraft accounts for 0.6% of allocation during traversal**, and less now. The rest is vanilla
chunk meshing: `RenderPass$Draw`, `BlockPos`, `SectionRenderDispatcher$RenderSectionBufferSlice`,
and the `Object[]` and `long[]` behind them. No change to the Metal backend will move it.

**What to try, in order:**

1. JVM tuning, measured with the probe rather than assumed. The run uses G1 with a 16 GiB heap by
   default. Worth testing: a larger young generation (`-XX:G1NewSizePercent`), a larger region size
   (`-XX:G1HeapRegionSize=16m` or `32m`, given the large `Object[]`/`long[]` churn), a pause target
   (`-XX:MaxGCPauseMillis`), and generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`) which trades
   throughput for much shorter pauses. Use `-PmetalJvmArgs`.
2. If a configuration wins, decide what to do with it. A renderer mod cannot set the user's JVM
   flags, so the outcome is a documented recommendation plus a benchmark default - not a code change.
3. Only then consider whether anything upstream is worth reporting or working around.

**Do not** spend effort shaving MetalCraft's remaining allocation for the sake of this item; it
cannot move the tail.

### 2. Command batching

**Status:** open, and now the largest item that is actually ours.

Binding and drawing is one synchronized Java method plus a separate JNI call each. Stage-selective
binding roughly halved the number of those calls and the slot table made each one much cheaper, and
together those were worth about a quarter of the per-frame CPU time - but the shape is unchanged:
one crossing per command.

The plan is to submit compact native command arrays or direct-buffer structs for repeated binds and
draws, validated once per batch, keeping a checked debug ABI behind a flag. The hot caller to design
against is `MetalRenderPassBackend.drawMultipleIndexed`, which is what Minecraft 26.2 uses for chunk
sections: per draw it sets a vertex buffer, resolves the uniform and sampler layout by name, and
issues a draw.

Two batch fixes that look easy are **not currently worth doing**: the `multiDrawIndexed(PointerBuffer, …)`
overload loops in Java and crosses JNI per draw, and the Java multi-draw paths build several
primitive arrays before crossing. Nothing in Minecraft 26.2 calls either - `drawMultipleIndexed` is
the only multi-draw entry point the client uses. Fix them when a caller appears.

The registry's lookup lock is also still a single global `NSLock`, taken on every command. The
lookups behind it are now cheap, so the lock is the remaining shared cost; shard it or remove it
from the render-thread path.

### 3. GPU timing below the command buffer

**Status:** partly done.

`MetalStallProbe.Source.GPU_FRAME` now reports GPU busy time per frame, taken from
`MTLCommandBuffer`'s own GPU start and end times in the completion handler that already existed. It
is enough to separate "the GPU is saturated" from "the render thread is waiting on presentation",
which was the question that could not previously be answered. A GPU-bound diagnostic run showed
3.4 ms of GPU time per frame against a 4.55 ms acquire wait inside a 9.9 ms interval - so that run
was paced by the drawable pool, not by the GPU.

What remains is localising GPU time to a *stage*. `MetalTimestampQueryPool` and
`MetalCommandEncoder.writeTimestamp` already exist, so bracketing individual passes with counter
samples is the next step. Apple's Instruments Game Performance template and GPU counters are the
reference.

While you are there: the near-constant ~4.55 ms acquire in that run is worth its own look. Display
sync was off and the frame rate was uncapped, so a constant wait points at `CAMetalLayer`'s drawable
pool rather than at the display. `maximumDrawableCount` is not currently set.

### 4. Ranked plan items still open

From `APPLE_SILICON_PERFORMANCE.md`, with current status:

- **Item 1, unified memory.** Partly done. `MetalTransientMemory` suballocates from three rotating
  frame slots and `writeToBuffer`/`writeToTexture` go through it. Still open: blit-encoder reuse,
  the row-at-a-time private-temporary copy paths in `MetalCommandEncoder.copyBufferToTexture` and
  the padded `copyTextureToBuffer`, and isolating readback submissions so a pending callback does
  not turn `submit()` into `waitUntilCompleted()`.
- **Item 3, load/store liveness and pass merging.** Open. The general pass adapter always stores
  both colour and depth, even when an attachment is dead after the pass.
- **Items 4-7.** Shader library/PSO caching and binary archives; argument buffers and ICBs;
  direct-to-drawable final pass; private heaps and aliasing. All untouched, all still ranked.

### 5. Milestone 3, shader-ready API

Three items remain open in [ROADMAP.md](../ROADMAP.md) and are unrelated to performance: an example
shader add-on rendering a full-screen pass, per-extension error isolation with failed-pipeline
diagnostics, and a versioned post-processing graph API.

## Things worth knowing before you change the render path

- **The stage masks are load-bearing.** `MetalCompiledRenderPipeline` decides which shader stages
  each resource slot is bound to, from SPIRV-Cross reflection of the translated shaders. A slot
  missing from a mask is never bound, which renders the wrong thing rather than merely running
  slowly. The masks come from *declared* resources rather than active ones for exactly that reason,
  and `MetalShaderTranslationSmoke` pins them for a shader with known bindings and draws two
  single-stage cases so a wrong table shows up as wrong pixels.
- **There is no sampler mask, deliberately.** A texel buffer reflects as a sampled image but
  compiles to a texture with no sampler, so a reflected sampler mask over-approximates on exactly
  the slots where it would differ - and the render path never gives those slots a sampler. The
  texture mask covers both.
- **One render-pass adapter is reused per encoder.** `MetalCommandEncoder.submitRenderPass` detaches
  it, so using it afterwards throws rather than encoding into the next pass. Only one pass can be
  active on an encoder at a time, which is what makes the reuse safe.
- **Handles carry a generation.** A native handle packs a generation above a slot index. Slots are
  recycled through a free list, and the generation is what stops a stale handle from resolving to
  whatever now occupies its slot.

## Where the raw data went

Benchmark logs, JFR recordings, and GC/safepoint logs are written to a session scratchpad and are
**not** in the repository. The numbers extracted from them are in `APPLE_SILICON_PERFORMANCE.md`. To
reproduce the allocation tables, re-run with the flags above and take a JFR `ObjectAllocationSample`
dump grouped by the outermost `dev.metalcraft` stack frame.
