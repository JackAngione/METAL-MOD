# Next steps

Last updated: 2026-08-22, after commit `9c9ea9f` on `metal-benchmark-and-perf`.

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
./gradlew runClient -PmetalLifecycleTest -PmetalLifecycleBenchmark=true -PmetalBenchmarkRepeats=3
```

Roughly 10 minutes: about 4 minutes generating terrain, then nine 20-second phases. Results land in
`run/benchmarks/metalcraft-metal.json` and in the log as `Metal benchmark result:` lines.

Useful flags:

- `-PmetalBenchmarkRepeats=1` for a fast diagnostic pass. Never for a before/after claim.
- `-PmetalBenchmarkResolution=3840x2160` - required if the screen may sleep or lock mid-run.
- `-PmetalJvmArgs="..."` passes JVM flags through, e.g. `-Xlog:gc*:file=gc.log:time,uptime` or
  `-XX:StartFlightRecording=filename=alloc.jfr,settings=profile`.

### Reading the stall probe

Each phase logs `Metal benchmark stall:` lines - a whole-phase total, a worst-1% total, and the
eight worst individual frames:

```
phase=traversal#1 frame=568 intervalMs=171.626 cpuMs=5.764 outsideLoopMs=0.027 \
  jvm_gc=48.000ms/3 acquire=0.032ms/1 buffer_map=0.014ms/15 0.6MiB unattributedMs=123.543
```

- `intervalMs` - render-loop tail to tail, the number a player feels.
- `cpuMs` - Minecraft's own `getFrameTimeNs()`. **It does not cover all of `runTick`.** A frame can
  show 5 ms here inside a 170 ms interval.
- `outsideLoopMs` - between the previous loop ending and this one starting. Near zero means the
  render thread was inside `runTick` the whole time.
- `unattributedMs` - `interval - outsideLoop - everything attributed`. Ordinary rendering work lives
  here too, so a few ms is normal; a large value on a spike frame means the cause is not yet
  instrumented.

Sources are defined in `MetalStallProbe.Source`. Adding one is three lines: an enum constant, a
`begin()`/`end()` pair at the call site. Only render-thread events are recorded, deliberately.

## Environment pitfalls that have already cost time

- **`run/options.txt` can be left on `preferredGraphicsBackend:"opengl"`** by Minecraft's crash
  recovery after a failed run. The next run then silently benchmarks OpenGL. The harness asserts on
  the backend, so it fails loudly - but reset it to `"default"` rather than debugging the assertion.
- **A sleeping or locked display** makes `glfwGetPrimaryMonitor` return nothing and aborts the run.
  Pass an explicit resolution.
- **Machine state dominates absolute numbers.** Across one afternoon the identical scenario ran
  between 117 and 361 FPS. A run can be GPU-bound (watch for `p50AcquireMs` in the milliseconds and
  every phase pinned to the same interval) or free-running (`p50AcquireMs` around 0.009). These are
  not comparable. Compare only runs from the same session, and prefer allocation profiles, which are
  robust to machine state, when the effect is allocation.

## Open work

### 1. The residual traversal tail is garbage collection, and it is not ours

**Status:** diagnosed, not fixed. This is the largest remaining item and the reason the original
"traversal stall" ticket is not closed.

**Evidence:** two of three traversal repeats now peak under 23 ms, against 197 ms before. The third
still reaches 171 ms, and 208 ms of the 215 ms attributed across that repeat's worst 1% of frames is
collection time. The heap cycles roughly 4 GB per 150 ms; the worst frames take three to five
collections apiece. Longest single GC pause and longest safepoint in a full nine-phase run are both
about 49 ms, so the pauses alone do not cover a 171 ms frame - the rest is the render thread running
slowly under allocation pressure rather than being stopped by it.

**After the fixes in `9c9ea9f`, MetalCraft accounts for 0.6% of allocation during traversal.** The
rest is vanilla chunk meshing: `RenderPass$Draw`, `BlockPos`,
`SectionRenderDispatcher$RenderSectionBufferSlice`, and the `Object[]` and `long[]` behind them. No
change to the Metal backend will move it.

**What to try, in order:**

1. JVM tuning, measured with the probe rather than assumed. The run uses G1 with a 16 GiB heap by
   default. Worth testing: a larger young generation (`-XX:G1NewSizePercent`), a larger region size
   (`-XX:G1HeapRegionSize=16m` or `32m`, given the large `Object[]`/`long[]` churn), a pause target
   (`-XX:MaxGCPauseMillis`), and generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`) which trades
   throughput for much shorter pauses. Use `-PmetalJvmArgs`.
2. If a configuration wins, decide what to do with it. A renderer mod cannot set the user's JVM
   flags, so the outcome is a documented recommendation plus a benchmark default - not a code change.
3. Only then consider whether anything upstream is worth reporting or working around.

**Do not** spend effort shaving the remaining MetalCraft allocation for the sake of this item; at
0.6% it cannot move the tail. Item 4 below is worth doing on its own merits, not for the tail.

### 2. The benchmark renders roughly twice the pixels it reports

**Status:** confirmed, unfixed. Invalidates absolute resolution claims, not same-machine A/B.

The harness requests 3840x2160 and the JSON records `"width":3840,"height":2160`, but the surface
configures **7680x2104** - visible in the `MetalCraft surface configured:` log line. That is about
16.2M pixels against an intended 8.3M. The height of 2104 rather than 4320 suggests the window is
clamped by the display's usable height in points and then scaled by the Retina backing factor.

Look at the interaction between `MetalLifecycleGameTest.applyDisplaySettings` (which resizes in
logical points and waits on `Window.getWidth()`), `requestedResolution`, the backing scale factor,
and `WindowRenderResolutionMixin` / `MetalCraftRenderResolution`. Either achieve the requested
drawable size exactly or fail loudly, in the spirit of the existing "No primary display is
available" assertion. **The report header and JSON must record the actual configured drawable size,
not the requested one** - that is the part that makes results trustworthy again.

Every resolution figure in `APPLE_SILICON_PERFORMANCE.md` is suspect until this is settled.

### 3. No GPU-side timing

**Status:** open, unchanged. Named as a known gap in the performance document.

The probe can localise a stall to "not the CPU" but not to a GPU stage. Metal timestamp query pools
already exist (`MetalTimestampQueryPool`, `MetalCommandEncoder.writeTimestamp`), so bracketing each
submitted frame with a begin/end counter pair and surfacing it as a probe source is the next step.
Apple's Instruments Game Performance template and GPU counters are the reference.

This matters most for the phases where `p50AcquireMs` is in the milliseconds: today those runs can
only be described as "GPU-bound", with no breakdown.

### 4. Remaining renderer allocation

**Status:** small but real. Measured, cheap to fix, will not move the tail.

Per 20-second traversal phase, after the bind-group fix:

| Site | Allocation | What |
|---|---:|---|
| `MetalRenderPassBackend.bindTexture` | 62.4 MB | `HashMap$Node[]` growth and rehashing |
| `MetalCommandEncoder.createRenderPass` | 16.2 MB | descriptor records |
| `MetalRenderPassBackend.draw` | 10.7 MB | |
| `MetalCommandEncoder.createFence` | 4.0 MB | |

`MetalRenderPassBackend` allocates four `HashMap`s per render pass (`uniforms`, `textures`,
`boundUniforms`, `boundTextures`) and grows them as bindings arrive; 60 MB of the total is
`HashMap$Node[]`. Presizing them, or replacing the two `Map<Integer, …>` binding caches with arrays
indexed by resource slot, removes most of it. The slot indices are already dense integers, so the
array form is a better fit than a boxed-key map anyway.

### 5. Ranked plan items still open

From `APPLE_SILICON_PERFORMANCE.md`, with current status:

- **Item 2, command batching.** Still open. Binding and drawing is one synchronized Java method plus
  a separate JNI call each. The `multiDrawIndexed(PointerBuffer, …)` overload still loops in Java
  and crosses JNI once per draw (`MetalRenderPassBackend.java:148-156`); consume the direct buffers
  in one native call. The registry's boxed-`NSNumber` dictionary is also still there - the root
  device and the child count are now cached on each entry, but the lookup itself still hashes a
  `CFNumber`. A slot+generation table in a contiguous array is the documented plan.
- **Item 2, stage-selective binding.** `nSetUniformBuffer`, `nSetTexture`, and `nSetSampler` still
  issue both vertex and fragment calls unconditionally.
- **Item 3, load/store liveness and pass merging.** The general pass adapter always stores both
  colour and depth, even when an attachment is dead after the pass.
- **Items 4-7.** Shader library/PSO caching and binary archives; argument buffers and ICBs;
  direct-to-drawable final pass; private heaps and aliasing. All untouched, all still ranked.

### 6. Milestone 3, shader-ready API

Three items remain open in [ROADMAP.md](../ROADMAP.md) and are unrelated to performance: an example
shader add-on rendering a full-screen pass, per-extension error isolation with failed-pipeline
diagnostics, and a versioned post-processing graph API.

## Claims elsewhere that are now stale

Fix these when you touch the surrounding text; they will mislead otherwise.

- **`APPLE_SILICON_PERFORMANCE.md` item 1** describes a unified-memory fast path as unbuilt: "every
  `writeToBuffer` creates a shared `MTLBuffer`", "`MetalTransientMemory` allocates a new GPU buffer
  for each request and ignores its `alignment` parameter because it does not suballocate at all".
  That is no longer true. `MetalTransientMemory` implements three rotating frame slots with
  suballocating arenas, and `writeToBuffer` goes through `allocateStaging`. What remains of item 1
  is blit-encoder reuse and isolating readback submissions so a pending callback does not turn
  `submit()` into `waitUntilCompleted()`.
- **Any absolute resolution figure** in that document, per item 2 above.
- **`MetalPresentProbe` no longer exists.** It was folded into `MetalStallProbe` as the `ACQUIRE`
  source. Older text refers to it by name.

## Where the raw data went

The JFR recordings, GC and safepoint logs, and per-run benchmark logs behind commit `9c9ea9f` were
written to a session scratchpad and are **not** in the repository. The numbers extracted from them
are in `APPLE_SILICON_PERFORMANCE.md`. If you need the raw traces again, re-run with the flags in
"Reproducing a measurement" above - the analysis was a JFR `ObjectAllocationSample` dump grouped by
the outermost `dev.metalcraft` stack frame, which is enough to reproduce the allocation tables.
