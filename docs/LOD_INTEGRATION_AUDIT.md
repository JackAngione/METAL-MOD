# LOD integration audit

P1 work in progress, 2026-09-10. No LOD rendering is enabled by this audit.

## Verified mapped interfaces

Inspected the local Minecraft 26.2 client-only deobfuscated jar with `javap -private`.
The initial signatures were subsequently traced in Loom's generated Minecraft 26.2
sources (`./gradlew genSources`, passed). Runtime hooks still require live validation.

| Concern | Mapped seam and proposed contract |
| --- | --- |
| Snapshot capture | `RenderRegionCache.createRegion(ClientLevel, long)` produces `RenderSectionRegion`; capture renderer-owned values before scheduling LOD workers. |
| Snapshot isolation | `SectionCopy` holds block states, but `RenderSectionRegion` also retains `ClientLevel` and `LevelLightEngine`. Do not pass the region as an immutable LOD snapshot: copy light, tint, material and occupancy explicitly on the owning thread. |
| Dirty sections | `SectionUpdateTracker.setDirty(int,int,int,boolean)` is a candidate shared revision seam. `extract.LevelExtractor` exposes block, range, section and neighbor dirty methods. Trace their convergence before selecting one injection, so revisions are neither missed nor counted repeatedly. |
| Chunk lifecycle | `ClientChunkCache.replaceWithPacketData`, `replaceBiomes`, `onLightUpdate`, and `drop` cover received terrain, biome replacement, light and unload candidates. Its update sets are consumed/flipped; do not consume them from a second owner. |
| World/resource lifecycle | `LevelExtractor.setLevel`, `allChanged`, and `onResourceManagerReload` are generation-reset candidates. Every job must carry session, dimension, terrain revision and resource generation. |
| Geometry selection | `LevelRenderer.prepareChunkRenders(Matrix4fc)` assembles `ChunkSectionsToRender`. Select here while section identity is available, before layer draw lists are batched. Confirm the exact loop with bytecode before modifying it. |
| Draw submission | `ChunkSectionsToRender` already contains grouped `RenderPass.Draw` lists and section UBO slices. The existing `renderGroup` redirect changes attachments/pipelines; it is too late to infer safe section ownership from a pass alone. |
| Ordinary fallback | `RenderSection.getSectionMesh()` and `getSectionNode()` expose mesh and position. `setSectionNode`/`reset` recycle storage: an index alone cannot identify an LOD job. Keep ordinary geometry until a validated replacement is uploaded. |

## Composition and capabilities

`WorldGeometryAdapter` owns terrain pipeline substitution and G-buffer/forward
routing. `WorldComposition` documents a final world stage after translucent content
and before hand/HUD. That final stage is too late to insert opaque LOD depth needed
by translucent consumers. P4 must preserve current opaque routing; P5 must establish
the earlier composition seam before any reduced-resolution work.

No LOD pack capability has been established. Treat no-pack and Standard as separate
validation cases, with multiresolution shading unavailable for both until P6 passes.
Do not infer compatibility merely because a pack loads. Water implementation and
water-specific validation remain governed by WATER_EFFECTS_PLAN.md.

## Baseline procedure and remaining measurement gaps

Machine: Apple M4 Max Mac Studio, 16 CPU cores, 64 GB RAM, arm64. Only this machine
has been inspected; base Apple Silicon hardware remains untested.

The existing benchmark uses generated terrain with structures, chooses an inland
site by roughness, checks loaded coverage and visible sections, and captures three
interleaved stationary/pan/traversal repeats. It disables VSync and the frame cap.
Explicitly supply 16/16 because its existing render-distance default is 32.

Initial invocation is the 3840x2160 command in LOD_FEATURE_PLAN.md. Startup confirmed
Default selected Apple M4 Max Metal, an IMMEDIATE 3840x2160 surface, and Standard
loaded. Startup confirmation is not a successful populated-world baseline.

`MetalFrameMetrics` records CPU/frame-interval percentiles, stalls, task census and
GPU pass spans. Pass spans must not be summed into GPU frame time. P1 still needs
GPU frame percentiles, memory measurements, configuration/thermal provenance and
retained populated-world screenshots before acceptance. Native resolution, 1080p,
half-resolution variants and no-pack remain outstanding. No performance conclusion
is supported yet.

Initial attempt was stopped: a separate `runClient -PmetalWaterIdentityTest=true`
process was running concurrently in this checkout, sharing launch artifacts/logs
and GPU resources. A build/smoke run also overlapped this exploratory attempt.
Discard all timings from this attempt; rerun in an isolated run directory with
exclusive GPU use. Do not treat the shared latest.log as baseline evidence.
`./gradlew build` passed, including `shaderTranslationSmoke` (37 seconds).

## Source-traced hook contracts

- `LevelExtractor.extract` creates one `RenderRegionCache` per extraction. For each
  dirty visible section with eligible neighbors it creates a region, adds a
  `SectionUpdateRenderState`, and then clears the dirty bit. Capture immutable LOD
  values at this owning-thread seam, before the region is sent to worker compilation.
  Do not use the dirty-bit clear as proof that an LOD upload completed.
- `RenderRegionCache.createRegion` copies 27 surrounding sections; the LOD snapshot
  only needs the 16³ section plus its one-block halo. `RenderSectionRegion.getBlockTint`
  delegates to live `ClientLevel`, and its light engine is a live reference.
- Block changes expand a one-block neighborhood before converging on
  `LevelExtractor.setSectionDirty(int,int,int,boolean)`. This delegates to
  `SectionUpdateTracker.setDirty`, which only marks tracked storage. Coalesce repeated
  marks, and retain separate LOD revisions for cached terrain outside that storage.
- `ClientChunkCache.onLightUpdate` marks a section through the same extractor path.
  `ClientPacketListener.handleChunksBiomes` replaces biome data, calls `onChunkLoaded`,
  then marks a 3×3 column neighborhood across the vertical section range. A hook on
  `replaceBiomes` alone would run before that full invalidation sequence.
- `LevelExtractor.setLevel` recreates the tracker via `allChanged` or clears it on
  disconnect. `onResourceManagerReload` only resets the sky renderer: it must **not**
  be treated as the whole mesh/material reset. Track material generation alongside
  `LevelRenderer.invalidateCompiledGeometry`, plus explicit session changes.
- `prepareChunkRenders` iterates `visibleSections` under the dispatcher lock, reads
  `SectionMesh.SectionDraw` and its uber-buffer slice, then allocates a section UBO
  entry and constructs grouped `RenderPass.Draw` objects. Select before this loop
  commits ordinary draws; never queue builds, allocate GPU buffers or wait for them
  while holding that lock. A separate LOD draw needs its own vertex ABI/pipeline;
  inserting a custom vertex buffer into the existing layer grouping is invalid.

## Initial isolated capture (retained, not a release-performance baseline)

Artifacts: `docs/evidence/lod/baseline-initial/metrics.json` and `scene.png`.
Command: original plan baseline command, isolated worktree, three repeats, 16/16,
Default/Metal. This clean run selected **no pack**, unlike the earlier shared-checkout
startup. Requested 3840×2160; actual drawable/scene 3840×2104 due to window limits.
Seed `metalcraft`, site (-1536,173,-128), 726 visible sections, loaded fraction 1.0.
Screenshot inspection confirms generated mountainous terrain but excessive sky/cloud
coverage (featureless fraction 0.8741); use the revised pitch control for the next run.

| Phase | Median of CPU medians (ms) | Median 1% low (FPS) |
| --- | ---: | ---: |
| Stationary | 0.735 | 88.01 |
| Pan | 0.795 | 88.62 |
| Traversal | 0.872 | 56.58 |

Frame intervals sit near 8.33 ms / 120 Hz and acquisition consumes about 7.4 ms.
The harness explicitly flagged display pacing. GPU pass spans identify sky and
immediate entity/item draws as significant in this view; they are overlapping spans,
not additive frame costs. These numbers do not establish a terrain LOD speedup.
The revised harness now records explicit pack/half-resolution/unlocked settings,
camera pitch, requested LOD preferences, and heap/Metal allocation samples after
each phase. Memory samples are point-in-time values, not peak or resident-set usage.
