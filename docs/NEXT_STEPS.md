# Next steps

Last updated: 2026-08-23, after making the render-loop phases exhaustive on `metal-benchmark-and-perf`.

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
phase=traversal frame=349 intervalMs=151.931 cpuMs=4.214 outsideLoopMs=0.044 \
  client_tasks=146.167ms/1 jvm_gc=50.000ms/4 render_frame=4.289ms/1 gpu_frame=4.182ms/1 \
  client_tick=1.050ms/1 client_gizmos=0.372ms/2 acquire=0.030ms/1 submit=0.016ms/1 \
  unphasedMs=-0.004
```

- `intervalMs` - render-loop tail to tail, the number a player feels.
- `cpuMs` - Minecraft's own `getFrameTimeNs()`. **It covers only the render section**, and not even
  all of that: it starts after the drawable is acquired and stops before submit and present. A frame
  can show 4 ms here inside a 175 ms interval, which is what made the traversal stall so hard to
  find.
- `outsideLoopMs` - between the previous loop ending and this one starting. Near zero means the
  render thread was inside `runTick` the whole time.
- `unphasedMs` - `interval - outsideLoop - the phases`. The phases now partition `runTick` from its
  first statement to its last, so this is only the sliver of the loop outside `runTick` itself. It
  measures between -0.004 and -0.001 ms per frame; anything larger means a phase boundary has
  stopped matching the code it was aimed at.

**Sources come in three kinds, and mixing them up is what made the interval impossible to balance.**

*Phases* partition `runTick` end to end, and only these are subtracted to get `unphasedMs`. In the
order the loop crosses them: `client_pre_render` (the close check, a pending reload, the presence
handler), `client_packets`, `client_tasks`, then `client_gizmos` and `client_tick` alternating once
per tick the frame catches up on, then `client_pre_frame` (the per-frame gizmo collection, sound, and
mouse), `render_frame`, and `client_post_render`.

They are recorded by a cursor rather than by a begin/end pair each - `MetalStallProbe.split` closes
one stretch and opens the next at the same instant. That is what makes them exhaustive: pairs leave
the gaps between them uncounted, and a pair whose end sits behind a branch never fires at all, which
matters because `runTick` skips packets, tasks and ticks entirely when `advanceGameTime` is false.

*Details* happen inside a phase and explain it: `acquire`, `submit`, `present`,
`level_end_frame`, `buffer_map`, `upload_copy`, `jvm_gc`, and the rest. A collection pause can land
in any phase, so it is a detail, not a phase. Summing details as well as phases subtracts the same
milliseconds twice - it drove the residual to -42 ms on the frame quoted above.

*Concurrent*: `gpu_frame` alone. The GPU runs while the render thread does; it is reported beside
the interval and never subtracted from it. Command buffers overlap on the GPU and are attributed to
whichever frame they finished in, so read it as occupancy, not as this frame's GPU cost.

Each phase also logs a `tasks` line, from `MetalTaskCensus`: a total, then the kinds of task the
main-thread queue ran, keyed by the submitting class. `tasks total=0.000ms/0` means the queue was
empty, not that the census failed - the lifecycle test asserts against that by submitting four empty
tasks and requiring them back.

Sources are defined in `MetalStallProbe.Source`, and `isPhase()` is what sorts them. Adding a detail
is three lines: an enum constant and a `begin()`/`end()` pair at the call site. Adding a *phase*
means splitting an existing one, in `MinecraftTickPhaseMixin` - insert a boundary that splits the
cursor into the new source, never a fresh begin/end pair, or the partition stops being exhaustive.
Only render-thread events are recorded, deliberately.

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

### 1. The traversal stall is Minecraft's main-thread task queue

**Status:** located, not fixed. Still the largest remaining item, and the previous diagnosis was
wrong.

**What it is.** The worst traversal frame of a run, with the render loop fully divided into phases:

```
frame=955 intervalMs=175.731 cpuMs=3.956 outsideLoopMs=0.040 client_tasks=170.137ms/1 \
  jvm_gc=47.000ms/3 render_frame=4.016ms/1 gpu_frame=3.506ms/1 client_tick=1.210ms/1 \
  acquire=0.027ms/1 submit=0.015ms/1 buffer_map=0.010ms/15 0.6MiB upload_copy=0.005ms/2
```

170 of the 175 ms is `Minecraft.runAllTasks()` - the main-thread task queue, which during terrain
streaming is dominated by chunk mesh uploads scheduled from worker threads. Rendering the frame took
4 ms. The Metal calls inside that 170 ms are negligible: 0.010 ms of buffer mapping across fifteen
maps, 0.005 ms of upload copying. **The stall is neither the render path nor the collector.**

**Why the previous diagnosis was wrong, and how to avoid repeating it.** The handoff before this one
said the tail was garbage collection, on the strength of collection dominating the worst-1% totals -
208 ms of 215 ms. Two things falsify that:

1. Running the identical capture under ZGC removed collection from the tail almost entirely: 0.000 ms
   attributed across the worst 1% of traversal frames, against 154 ms under G1. **The spike survived
   at 158 ms.** A collector change that eliminates the supposed cause and leaves the effect intact is
   about as clean a refutation as this harness can produce.
2. Even under G1, the worst single frame attributed only 47 ms of its 175 ms to collection. The
   worst-1% *total* was misleading because it aggregates many frames, and because collection time
   overlaps whatever was running - the pauses land inside the task queue rather than instead of it.

Collection was a correlate: allocation pressure from the same meshing work that fills the queue.

**The instrument for step 1 now exists, and the stall did not reproduce under it.**
`MetalTaskCensus`, fed by `BlockableEventLoopTaskCensusMixin` on `BlockableEventLoop.doRunTask`,
counts every task the render thread runs during a capture, keyed by the task's own class - which for
Minecraft's lambdas names the method that submitted it. Each phase now logs a `tasks` line beside its
stall lines, totals first.

Across three benchmark runs it recorded **nothing**: `tasks total=0.000ms/0` in every phase, with
`client_tasks` at 0.24-0.63 ms across roughly 2350 drains, which is the cost of peeking at an empty
queue and returning. The 170 ms spike did not occur in any of those runs. Two things follow.

*The census is not silently broken.* That was the first suspicion, and it is ruled out rather than
assumed: the lifecycle test submits four empty tasks through `Minecraft.execute` during its capture
and asserts the census counted at least four. It reports
`tasks total=0.013ms/4 dev.metalcraft.client.test.MetalLifecycleGameTest$$Lambda=0.013ms/4`, so both
the counting and the naming work. That assertion is also the guard for the real risk here: this hooks
an internal event-loop method rather than anything Blaze3D promises, and if Minecraft ever routes
tasks elsewhere the instrument would otherwise go quiet and read as good news.

*The stall is not a property of the settled traversal phase.* The capture that produced the 170 ms
frame and the captures that produced nothing are the same scenario on the same machine; the harness
waits for terrain to settle before every capture, and whatever fills the queue had finished by then
in three runs out of four. So the next step is not more of the same run - it is to capture *during*
streaming, before the settle wait, where the queue is by construction busy. The lifecycle capture
already sits in that window and now prints its attribution, which makes it the cheapest place to
start.

**What to try, in order:**

1. Capture with the census while terrain is still streaming, not after it settles, and read the
   `tasks` line. Everything needed for this is in place.
2. If they are chunk mesh uploads, the question becomes whether the backend can accept them without
   the main thread - Blaze3D's threading rules decide that, not us - or whether the batch can be
   bounded per frame. A frame that uploads 170 ms of mesh is a frame that should have uploaded some
   of it later.
3. JVM tuning is still worth measuring, but as a second-order effect now. ZGC did not fix the spike.
   It may well help the 1% low - the traversal 1% low was 45.3 under G1 and 89.0 under ZGC - but
   **that pair is not a valid comparison**: the G1 side was a single-repeat diagnostic run and both
   were display-paced. Re-run it properly, three repeats each, back to back, before believing it.
   G1 knobs left untested: `-XX:G1NewSizePercent`, `-XX:G1HeapRegionSize=16m` or `32m`,
   `-XX:MaxGCPauseMillis`. Note that ZGC is generational by default on the JDK 25 this builds
   against; `-XX:+ZGenerational` was removed in 24 and is ignored with a warning.

**MetalCraft is not in this path.** It accounts for 0.6% of allocation during traversal and
0.015 ms of the 170 ms spike. Do not spend effort shaving the renderer for the sake of this item.

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

The registry's lookup lock is a single global lock taken on every command, and it is now an
`os_unfair_lock` rather than an `NSLock`. With the lookups behind it reduced to a bounds check and a
few field reads, the `objc_msgSend` plus `pthread_mutex` round-trip that `NSLock` charges was most of
what a lookup cost; the replacement is an atomic compare-and-swap when uncontended, and it dropped
the `pthread_once` that guarded the lock's lazy allocation off the same path. **This was not measured
as a win** - it is below what the harness can resolve against an 8-10% spread, and it is recorded
here as a cost removed, not as a speedup. What is still open is whether the lock should exist on the
render-thread path at all: sharding it by slot, or giving lookups a lock-free read path, is the next
step if command batching does not make the question moot by taking the lock once per batch.

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
pool rather than at the display. **The obvious knob is not available**: an earlier draft of this file
said `maximumDrawableCount` was unset, and it is not - `MCMetalSurface`'s initialiser has set it to
`3` since the initial commit, and `3` is both the default and the largest value `CAMetalLayer`
accepts. There is no deeper pool to ask for, so the wait has to be explained some other way: three
drawables in flight and a constant wait means the presentation side is retiring them at a fixed
rate, which points at the compositor, not at pool depth.

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
