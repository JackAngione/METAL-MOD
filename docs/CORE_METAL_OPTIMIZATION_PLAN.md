# Core Metal optimization work

Scope: core Blaze3D/Metal integration, preserving distant terrain and shader-pack behavior.
Existing unrelated working-tree changes are excluded from these commits.

- [x] Reuse native command scratch storage and resolve/pin unique resources; batch ordinary indexed draw bindings.
- [x] Acquire drawables immediately before presentation, preserving frame hooks and surface recovery.
- [x] Copy legal texture layouts directly without per-row temporary buffers.
- [x] Deliver readback callbacks after GPU completion without blocking ordinary submissions.
- [x] Fold compatible clears into render passes and discard attachments only when their contents are proven dead.
- [x] Cache content-addressed translations, native libraries/functions, and persistent pipeline binary archives.
- [x] Review all changes; run native/core, shader, and LOD checks plus brief standard-world integration tests.

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

## Final review

- Metal API validation exposed a deferred-store contract violation. Color, depth and stencil
  stores now begin as `Unknown` when eligible for a later decision, and every encoder exit
  finalizes them to `Store` or `DontCare`. Consecutive color/depth clear readbacks cover this path.
- The full build exposed an archive serialization crash in the local-lighting process. Archive
  harvesting now uses source descriptors without a self-referencing archive list, and serialization
  supplies an error destination. The regression test reopens, extends, saves and reopens an archive
  containing both render and compute pipelines; stale and corrupt cache recovery remains covered.
- Reused command scratch clears only occupied hash buckets. Small HUD draws no longer clear the
  full capacity left by a large terrain batch. Ownership and invalid-handle checks remain enabled.
- The first live core capture was mostly sky. The integration route now waits for 64 visible solid
  sections, uses a downward core camera, and rejects captures without terrain detail. The corrected
  run passed with Metal API validation and its core, Standard and LOD captures were inspected.

## Reproducible validation

- `./gradlew build --offline`: core/native checks, section/light storage, native chunk distances,
  distant-terrain mesh coverage, local lighting, GPU frame accounting and the full shader suite.
- `MTL_DEBUG_LAYER=1 ./gradlew shaderTranslationSmoke --offline`: passed.
- `MTL_DEBUG_LAYER=1 ./gradlew shaderTranslationSmoke -PmetalPassMerging=false --offline`: passed.
- The following live route passed in 22 seconds, reusing `New World (4)` and checking its generator
  is NORMAL. Core and Standard use 16 render / 16 simulation; LOD uses 128 render / 16 simulation.
  It covers resize/fullscreen, resource reload, asynchronous screenshots, Standard with/without
  LOD, feature disable and world shutdown. Gradle restores player configuration after the test.

```sh
MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest \
  '-PmetalJvmArgs=-Xmx8G -Dmetalcraft.coreIntegrationTest=true' \
  --args='--graphicsBackend default' --offline
```

Set `-Dmetalcraft.coreTestWorld=...` in the JVM arguments to choose another existing standard save.
Captures remain local under ignored `run/screenshots/`. These checks establish correctness on the
available Apple Silicon Mac; they do not establish a percentage FPS improvement or qualify every Mac.
