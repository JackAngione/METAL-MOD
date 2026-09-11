# LOD integration audit

P1 completed, 2026-09-10. No live LOD rendering is enabled by this audit.

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
The harness explicitly flagged display pacing. GPU pass labels identify physical
encoders: with pass merging enabled, a `Sky disc` span also includes compatible
terrain work merged into that encoder. It cannot be attributed to sky alone.
The spans overlap and are not additive frame costs. These numbers do not establish
a terrain LOD speedup; a split-pass diagnostic capture is needed for terrain attribution.
The revised harness now records explicit pack/half-resolution/unlocked settings,
camera pitch, requested LOD preferences, and heap/Metal allocation samples after
each phase. Memory samples are point-in-time values, not peak or resident-set usage.

## Verified route version 2

Artifacts: `docs/evidence/lod/baseline-route2/metrics.json` and `scene.png`.
M4 Max/64 GB, macOS 27.0, Java 25.0.4, no pack, 1920×1080, 16/16,
Default/Metal, half-resolution off, unlocked presentation on, VSync off, FOV 70.
Command: original plan baseline command with resolution 1920×1080 and
`-PmetalBenchmarkPitch=30 -PmetalBenchmarkPack=none -PmetalBenchmarkUnlocked=true
-PmetalBenchmarkHalfResolution=false`. Three 400-tick repeats, each reset to the
same site and warmed for 100 ticks. Startup, pan and traversal camera samples are
recorded; all three repeats reached exactly the same traversal endpoint.

Actual initial pitch is verified at 30°. The initial screenshot draws 972 sections,
loaded fraction 0.9954 (rounded to 0.995 in the log), with featureless fraction 0.678.
The earlier `baseline-1080` attempt predates the server-teleport pitch fix and repeat
reset; its requested-pitch metadata is not proof of its initial camera angle. Retain
it only as exploratory evidence, not as an A/B reference for route version 2.

| Phase | CPU median / p95 / p99 (ms), medians across repeats | Median 1% low FPS | Mean completed-command-buffer GPU time (ms), median across repeats |
| --- | --- | ---: | ---: |
| Stationary | 0.976 / 1.123 / 1.250 | 381.57 | 0.791 |
| Pan | 0.673 / 1.059 / 1.221 | 451.82 | 0.607 |
| Traversal | 0.950 / 1.228 / 1.494 | 334.72 | 0.821 |

The GPU column is deliberately **not** labeled GPU-frame percentiles: the native
probe reports aggregate completed-command-buffer work, which is not a per-frame
distribution. GPU-frame median/p95/p99 instrumentation remains a release-gate gap.
Post-phase Metal allocation samples range from 1026–1155 MiB; heap used samples
range from 745–1649 MiB. Neither is a peak. Thermal state was not measured.
Stationary FPS varies by 10.8% across repeats; any future small regression claim
must account for that variability. No LOD speedup is claimed.

## Split-pass diagnostic capture

Artifacts: `docs/evidence/lod/baseline-split/metrics.json` and `scene.png`.
The route-version-2 invocation above plus `-PmetalPassMerging=false` completed
successfully in 7m14s, again with three repeats. This is a diagnostic configuration,
separate from the ordinary merged performance baseline. The opaque terrain encoder
is now labeled `Section layers for opaque` rather than being folded into `Sky disc`.

| Phase | Median of mean opaque-terrain spans (ms) | Individual-repeat means (ms) |
| --- | ---: | --- |
| Stationary | 0.571 | 0.561, 0.620, 0.571 |
| Pan | 0.375 | 0.342, 0.375, 0.382 |
| Traversal | 0.608 | 0.596, 0.609, 0.608 |

These spans include stage overlap/stalls; do not sum them with other passes or
substitute them for total GPU-frame time. Entity/item work, clouds and terrain all
remain measurable contributors. Stationary FPS spread is 18.2% in this diagnostic
run, so this evidence does not support small speedup claims. P1's reproducible
baseline and hook-audit gate is satisfied; P8 still requires paired LOD-on/off tests,
GPU-frame distributions, further scenes/resolutions/packs and final budget tuning.

## Remaining live-adapter contracts (P4/P5)

The tested `LodMetalGeometry` prototype draws a custom vertex ABI into an isolated
color/depth pass. Its appearance resolver is synthetic. It must not replace a vanilla
draw until the following contracts are implemented and verified:

- Capture actual model/atlas appearance, tint and per-vertex light/AO. Vanilla's
  `BlockQuadOutput` receives `BakedQuad` and `QuadInstance` values after lighting;
  copy those values before reuse if this seam is selected. Irregular models, cutouts,
  fluids, rotated/noncanonical UVs and nonconstant shading need explicit policies.
- Carry session/resource/revision IDs from extraction through build and upload.
  Recheck compiled-mesh identity before suppressing an ordinary draw, since an
  upload may replace it between preparation and submission.
- Keep custom buffers out of the ordinary BLOCK vertex pipeline. Preflight all
  bindings and resources before transferring surface ownership; failure must retain
  the ordinary mesh. The prototype does not yet implement world fog/lightmap or
  Standard's linear HDR G-buffer contract.
- Feed `LodMeshResidency` a monotonic GPU completion timeline. The existing Metal
  shared events can be polled without waiting; do not use a fixed frame-delay guess
  or charge only currently visible meshes. Retired in-flight buffers remain charged.
- Apply availability fallbacks and transition limits before final neighbor balancing.
  Candidate selection alone is not proof that an uploaded tier exists.

No-pack and Standard both remain gated for live LOD. Multiresolution composition,
shadow LOD and extended-horizon rendering have no advertised capability yet.
