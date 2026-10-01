# Core Metal optimization work

Scope: core Blaze3D/Metal integration, preserving distant terrain and shader-pack behavior.
Existing unrelated working-tree changes are excluded from these commits.

- [x] Reuse native command scratch storage and resolve/pin unique resources; batch ordinary indexed draw bindings.
- [x] Acquire drawables immediately before presentation, preserving frame hooks and surface recovery.
- [ ] Copy legal texture layouts directly without per-row temporary buffers.
- [ ] Deliver readback callbacks after GPU completion without blocking ordinary submissions.
- [ ] Fold compatible clears into render passes and discard attachments only when their contents are proven dead.
- [ ] Cache content-addressed translations, native libraries/functions, and persistent pipeline binary archives.
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
