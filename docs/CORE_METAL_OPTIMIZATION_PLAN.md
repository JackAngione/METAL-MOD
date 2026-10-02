# Core Metal optimization work

Scope: core Blaze3D/Metal integration, preserving distant terrain and shader-pack behavior.
Existing unrelated working-tree changes are excluded from these commits.

- [x] Reuse native command scratch storage and resolve/pin unique resources; batch ordinary indexed draw bindings.
- [x] Acquire drawables immediately before presentation, preserving frame hooks and surface recovery.
- [x] Copy legal texture layouts directly without per-row temporary buffers.
- [x] Deliver readback callbacks after GPU completion without blocking ordinary submissions.
- [x] Fold compatible clears into render passes and discard attachments only when their contents are proven dead.
- [x] Cache content-addressed translations, native libraries/functions, and persistent pipeline binary archives.
- [ ] Review all changes; run native/core, shader, and LOD checks plus brief standard-world integration tests.

Each modification receives its own commit with validation recorded below. Performance improvements
are not claimed from correctness tests; live timing must distinguish CPU work, GPU work, and drawable waits.

## Validation and review

1. Submission: `shaderTranslationSmoke --offline` passed, including checked/unchecked batches,
   nonadjacent duplicate resources across scratch growth, pipeline ordering, early Java resource
   release, HDR composition, water and shader reload tests. Scratch is queue-owned and protected
   for concurrent submission; ordinary indexed draws flush before returning.

2. Presentation: client compilation passed. Shader/LOD `beginFrame` stays at its original point;
   only drawable ownership moves. A late timeout drops presentation and requests reconfiguration
   instead of throwing through Blaze3D's non-throwing blit API. `metalcraft.lateDrawable=false`
   retains the early-acquire comparison path. Window/reload integration is checked in the final run.

3. Transfers: `shaderTranslationSmoke --offline` passed. Real R8/RGBA readback covers
   narrow rows, private input buffers, cropped subregions ending at the last pixel, and unaligned
   source/destination fallbacks. Fallback storage uses submission-owned arenas. Native range
   validation uses division to avoid overflow and includes only the final row's actual pixels.

4. Readbacks: full Metal/shader smoke suite passed. A real GPU event intentionally blocks the
   copy: ordinary submit returns before the event is released, callbacks wait for the pixels and
   run once on the encoder owner. Completion tickets outlive arena retirement; explicit flush,
   reload and shutdown drain them. A throwing callback does not skip later callbacks or leak its ticket.

5. Attachments: full Metal/shader smoke suite passed. Clear passes now remain open for
   compatible following draws. One-operation liveness lookahead discards only mip/slice contents
   fully overwritten by the next clear, after pending tile resolves. Pixel checks cover preserved
   color across depth clears and partial clears; existing memoryless/MRT/HDR/water tests passed.
   Unknown lifetimes still store. No assumptions are made about a shader pack's future reads.

6. Compilation: full smoke suite passed. Expanded GLSL translations use a 256-entry/16 MiB
   content/stage/name cache; native source/function entries are bounded and shared across variants.
   Metal render and compute binaries are archived by GPU and OS build, atomically saved at last
   device close (64 MiB disk limit). Changed-source pixels, a real archive hit after device reopen,
   corrupt archive fallback, and shader reload/failure isolation all passed. Override the disk
   directory with `metalcraft.pipelineCacheDir`; the default is `~/Library/Caches/MetalCraft/pipelines-v1`.
