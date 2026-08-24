# Next steps

Last updated: 2026-08-23, after batching the multi-draw command path, on `metal-benchmark-and-perf`.

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
- `-PmetalCommandBatching=false` restores the per-command JNI path under `drawMultipleIndexed` and
  changes nothing else, so a batched build can be compared against an unbatched one back to back.
- `-PmetalCheckedCommands=true` makes the native decoder re-validate every batched command against
  the Metal objects it names. It is for proving the two paths agree, not for a timed run.

The requested resolution is a request. A windowed frame cannot always reach the display's full pixel
count - macOS keeps one below the menu bar, so a 3840x2160 request lands at 3840x2104 here. The run
warns when that happens and reports the size it achieved, and the header carries both the rendered
size and the drawable so the two can be seen to agree.

### Reading the stall probe

Each phase logs `Metal benchmark stall:` lines - a whole-phase total, a worst-1% total, and the
eight worst individual frames:

```
phase=traversal frame=2319 intervalMs=166.046 cpuMs=4.038 outsideLoopMs=0.025 \
  client_post_tasks=161.086ms/1 jvm_gc=45.000ms/3 render_frame=4.097ms/1 gpu_frame=2.162ms/1 \
  client_tick=0.835ms/1 acquire=0.029ms/1 submit=0.013ms/1 client_tasks=0.000ms/1 \
  task_drain=0.000ms/1 unphasedMs=-0.002
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
handler), `client_packets`, `client_tasks`, `client_post_tasks`, then `client_tick` and
`client_gizmos` alternating once per tick the frame catches up on, then `client_pre_frame` (the
per-frame gizmo collection, sound, and mouse), `render_frame`, and `client_post_render`.

**`client_post_tasks` is the test harness, not the game.** Fabric's client gametest harness hands
each frame to the test thread there, blocking the render thread on a semaphore, and it is where the
tail this project spent months attributing to Minecraft's task queue actually lives. Every
measurement here runs under that harness, so treat a large `client_post_tasks` as the harness unless
you have a reason not to. `harness_handoff` covers only the phaser part of the handoff, which is the
small part; the semaphore wait dominates and shows up in the phase.

They are recorded by a cursor rather than by a begin/end pair each - `MetalStallProbe.split` closes
one stretch and opens the next at the same instant. That is what makes them exhaustive: pairs leave
the gaps between them uncounted, and a pair whose end sits behind a branch never fires at all, which
matters because `runTick` skips packets, tasks and ticks entirely when `advanceGameTime` is false.

*Details* happen inside a phase and explain it: `acquire`, `submit`, `present`,
`level_end_frame`, `buffer_map`, `upload_copy`, `command_batch`, `jvm_gc`, and the rest.
`command_batch` is one crossing carrying a whole multi-draw; its byte count is the size of the
recorded stream, so dividing by 48 gives the commands the crossing carried. A collection pause can land
in any phase, so it is a detail, not a phase. Summing details as well as phases subtracts the same
milliseconds twice - it drove the residual to -42 ms on the frame quoted above.

*Concurrent*: `gpu_frame` alone. The GPU runs while the render thread does; it is reported beside
the interval and never subtracted from it. Command buffers overlap on the GPU and are attributed to
whichever frame they finished in, so read it as occupancy, not as this frame's GPU cost.

Read `task_drain` rather than `client_tasks` for the task queue itself: it is timed from inside
`runAllTasks`, which is the one place the harness's park cannot reach.

Each phase also logs a `tasks` line, from `MetalTaskCensus`: a total, then the kinds of task the
main-thread queue ran, keyed by the submitting class. `tasks total=0.000ms/0` means the queue was
empty, not that the census failed - the lifecycle test asserts against that by submitting four empty
tasks, from the test thread so that they go through the queue rather than running inline, and
requiring them back.

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

### 1. The remaining tail is collection, and it is 15-20 ms rather than 170 ms

**Status:** measured. Much smaller than this file has said all along, because the number it was
being compared against was not real.

**What the traversal tail actually is.** With the benchmark harness's own stall separated out (see
below), the worst genuine frames of a traversal repeat look like this:

```
frame=3689 intervalMs=19.873 cpuMs=19.823 outsideLoopMs=0.015 render_frame=19.855ms/1 \
  jvm_gc=17.000ms/1 gpu_frame=2.055ms/1 submit=0.012ms/1 buffer_map=0.007ms/14 0.6MiB
```

15 to 20 ms intervals, of which 12 to 17 ms is a collection pause, landing inside `render_frame`
because that is where the allocation is. Two repeats of a three-repeat run had worst intervals of
16.0 and 21.5 ms. That is the whole of the remaining tail.

**The 170 ms spike was the test harness.** It is gone from this list because it was never the game.
`CLIENT_TASKS` closed at `INVOKE runAllTasks` with `shift = AFTER`, and Fabric's client gametest
harness parks the render thread at that exact instruction - `postRunTasks` hands the frame to the
test thread and blocks on `ThreadingImpl.CLIENT_SEMAPHORE.acquire()`. Being applied closer to the
call, its park fell inside a phase named for Minecraft's task queue.

The stretch is now its own phase, `client_post_tasks`, and the frame reads unambiguously:

```
frame=2319 intervalMs=166.046 cpuMs=4.038 client_post_tasks=161.086ms/1 jvm_gc=45.000ms/3 \
  render_frame=4.097ms/1 gpu_frame=2.162ms/1 client_tick=0.835ms/1 \
  client_tasks=0.000ms/1 task_drain=0.000ms/1
```

161 of 166 ms is the harness. The task queue's own drain, timed from inside `runAllTasks` where the
park cannot reach it, is **0.000 ms**. Across five runs the task census counted zero tasks in any
phase, and a poll counter put the queue at zero deliveries in some 24,000 polls.

**What this invalidates.** Every traversal worst-frame and 1% low this project has published was
measured through that park, so the tail figures in
[APPLE_SILICON_PERFORMANCE.md](APPLE_SILICON_PERFORMANCE.md) predating this are not measurements of
Minecraft. Medians, CPU per frame, and the phase averages are unaffected - the park is a spike on
roughly one frame per run, not a distributed cost.

**What to do about the collection tail:**

1. It is vanilla chunk meshing's allocation, not this renderer's - MetalCraft accounts for 0.6% of
   allocation during traversal. The lever is JVM tuning, and it is now worth measuring properly
   because the effect being looked for is 15 ms rather than being lost beside a 170 ms artefact.
2. ZGC removed collection from the tail entirely in an earlier capture. The 1% low comparison that
   went with it - 45.3 under G1 against 89.0 under ZGC - **is still not valid**: the G1 side was a
   single-repeat diagnostic and both were display-paced. Re-run it three repeats each, back to back.
   G1 knobs left untested: `-XX:G1NewSizePercent`, `-XX:G1HeapRegionSize=16m` or `32m`,
   `-XX:MaxGCPauseMillis`. ZGC is generational by default on the JDK 25 this builds against;
   `-XX:+ZGenerational` was removed in 24 and is ignored with a warning.
3. A cleaner measurement would be a harness that does not park the render thread at all. Nothing here
   needs it now that the park is attributed, but it is the only way to see the tail a real player
   sees rather than the tail plus a test artefact.

### 2. Command batching

**Status:** done for the path that matters, and measured. It removed 22-25% of the render thread's
per-frame CPU time in every phase.

`MetalRenderPassBackend.drawMultipleIndexed` - the entry point Minecraft 26.2 draws chunk sections
through - no longer crosses JNI per bind and per draw. It records them into `MetalCommandStream`, a
flat array of 48-byte records in one direct buffer that is allocated per encoder and reused, and
hands the array to `nSubmitCommandStream`. One crossing resolves every handle, pins every resource,
and issues every encoder call. At 4K this scene records about 14,000 commands per frame and submits
them in three to nine batches, so what used to be 14,000 Java monitors, 14,000 JNI transitions, and
14,000 acquisitions of the native registry lock is now that many batches of each.

The numbers, and the two runs behind them, are in
[APPLE_SILICON_PERFORMANCE.md](APPLE_SILICON_PERFORMANCE.md). The short version: p50 CPU per frame
fell from 4.086/3.911/3.979 ms to 3.064/3.031/3.043 ms across stationary, pan and traversal, every
paired repeat was faster batched, and the 1% lows did not move - which is what item 1 predicts,
because the tail is collection and this removes CPU work rather than allocation.

**The ABI, in one paragraph.** A record is a fixed 48 bytes with named fields rather than a packed
per-opcode encoding, so decoding is an array index and an ABI mismatch is one comparison: the stream
header carries the record size Java believes in, and a native build whose struct has drifted fails
on the first batch instead of encoding garbage. Five opcodes - vertex buffer, uniform buffer,
texture, sampler, indexed draw - each naming exactly one Metal object, which is what lets the
resolved objects be a plain array parallel to the records. Adding one means an opcode constant, an
encode method on `MetalCommandStream`, a case in `mc_validate_command`, and a case in
`mc_encode_commands`; the operand's type goes in `mc_command_operand_type`.

**Where validation went.** The recorder makes the same range checks the per-command setters made,
from buffer sizes Java already knows. The native decoder checks everything a record can be judged on
by itself - opcodes, slot indices, stage masks, counts, index alignment - because those are integer
compares, and leaves buffer-length arithmetic alone because that costs a message send per command.
`-PmetalCheckedCommands=true` turns those back on and names the offending command by index. The
shader smoke test renders the same draws through the per-command path, the coarse batch, and the
checked batch, and compares the readbacks byte for byte; that comparison is the only evidence that
the coarse path encodes what it replaces, so keep it working when the ABI changes.

**What is still open here.**

- **Texel buffers are deliberately outside the ABI.** Binding one resolves a cached texture view
  against the buffer's format alignment, which is far more native work than a compact record can
  describe, and it is rebound once per pass rather than once per draw. A batch flushes when one
  appears, so ordering is preserved. Every other immediate command flushes for the same reason, at
  one seam - `MetalRenderPassBackend.pass()`.
- **The registry lock is still global**, and a batch now takes it once per 256 commands rather than
  once per command. The stride exists because holding it for a whole batch would stall chunk-meshing
  threads for as long as the batch is large. Sharding it, or giving lookups a lock-free read path,
  is still the open question - but it is now a much smaller one.
- **Pinning still hashes every resource into an `NSMutableSet`**, once per batch under one lock
  rather than once per command under one lock each. Handing an immutable set to the completion
  handler, as item 3 of the ranked plan describes, is what would remove the hashing too.
- **The other entry points are still per-command.** Nothing has shown that batching a single
  `drawIndexed` is worth the branch, and `drawMultipleIndexed` is where the commands are.
- Two batch fixes that look easy are **still not worth doing**: the
  `multiDrawIndexed(PointerBuffer, …)` overload loops in Java and crosses JNI per draw, and the Java
  multi-draw paths build several primitive arrays before crossing. Nothing in Minecraft 26.2 calls
  either. Fix them when a caller appears.

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
