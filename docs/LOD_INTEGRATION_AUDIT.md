# LOD integration audit

> **Superseded (2026-09-26).** This document describes an earlier LOD prototype whose code has
> been removed. The current system is described in
> [the distant terrain evidence](evidence/distant-terrain/README.md) and
> [the rewrite plan](LOD_REWRITE_PLAN.md).

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
Do not infer compatibility merely because a pack loads. Water-specific validation
requires its own rendering checks.

## Baseline procedure and remaining measurement gaps

Machine: Apple M4 Max Mac Studio, 16 CPU cores, 64 GB RAM, arm64. Only this machine
has been inspected; base Apple Silicon hardware remains untested.

The existing benchmark uses generated terrain with structures, chooses an inland
site by roughness, checks loaded coverage and visible sections, and captures three
interleaved stationary/pan/traversal repeats. It disables VSync and the frame cap.
Explicitly supply 16/16 because its existing render-distance default is 32.

The initial 3840x2160 invocation confirmed
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

- Capture actual model/atlas appearance, tint and per-vertex light/AO. The final
  `MeshData` capture below is verified across Fabric's alternate block renderer.
  Irregular models, cutouts, fluids and nonconstant shading need explicit policies.
  Emitted UV bounds alone do not identify a sprite or its atlas gutter; production
  mip/animation/reload handling still needs a generation-scoped material resolver.
- Carry session/resource/revision IDs from extraction through build and upload.
  Extraction-to-compiled-candidate identity is now verified (2026-09-11, below);
  production LOD upload and draw ownership remain pending.
  Recheck compiled-mesh identity before suppressing an ordinary draw, since an
  upload may replace it between preparation and submission.
- Keep custom buffers out of the ordinary BLOCK vertex pipeline. Preflight all
  bindings and resources before transferring surface ownership; failure must retain
  the ordinary mesh. The prototype does not yet implement world fog/lightmap or
  Standard's linear HDR G-buffer contract.
- Feed `LodMeshResidency` the world queue's `reserveResourceSubmission()` and
  `completedResourceSubmission()` timeline. Its real Metal shared-event integration
  now passes residency tests. Live LOD submission has not yet adopted it. Retired
  in-flight buffers remain charged; a fixed frame-delay guess is not sufficient.
- Apply availability fallbacks and transition limits before final neighbor balancing.
  Candidate selection alone is not proof that an uploaded tier exists.

No-pack and Standard both remain gated for live LOD. Multiresolution composition,
shadow LOD and extended-horizon rendering have no advertised capability yet.

## Final-mesh capture and feasibility evidence

The initial `BlockQuadOutput` hook failed coverage validation: populated final meshes
contained vertices that never passed through that callback. Inspection of the installed
Fabric renderer API 14.1.3+2b0d8a229e's `SectionCompilerMixin` bytecode confirms that
`tesselateBlockProxy` calls `AltModelBlockRenderer` with its own `QuadEmitter`, whose
output writes to the same layer builders. A vanilla-only model callback is therefore
not an authoritative snapshot seam in this mod configuration.

`SectionCompilerLodMixin` instead reads completed `MeshData` while its compiler worker
still owns it, after all emitters finish and before upload/release. It validates the
BLOCK vertex ABI and copies packed color/light, positions and UVs. No mutable world,
model, atlas object or buffer escapes the diagnostic. Admission reserves a conservative
16 MiB per compile, with a 64 MiB aggregate cap and 8,192-quad section limit. All
reservations are released in `finally`; disabled normal gameplay performs no capture.

`LodBakedMesh` merges exact unit faces only when shading, UV orientation and material
footprint match. It preserves nonuniform vertex shading verbatim, rejects custom or
overlapping geometry, and retains unit section-edge tessellation. CPU coverage
fixtures test every tier, preserved light/AO gradients, copy isolation and fallback.

Live command (38 s, Apple M4 Max/64 GB, Default confirmed Metal):

```bash
./gradlew runClient -PmetalLifecycleTest -PmetalLodCompilerTest=true \
  --args='--graphicsBackend default'
```

This creates a normal generated world with seed `metalcraft`, render/simulation 16,
and visits the snowy mountain baseline area. It watches section (-97, 10, -8), edits
block (-1540, 175, -128), requires that section to recompile, reloads resources,
requires it to recompile again, closes the world, then requires zero reserved bytes.
The [capture report](evidence/lod/compiler-capture/metrics.json) and
[ordinary-rendering screenshot](evidence/lod/compiler-capture/scene.png) are retained.

After close: 1,805 observed compiles; 145 supported, 1,619 material/layer rejections,
41 geometry rejections, zero admission misses and zero reserved bytes. Supported
quads reduced 39,412 → 38,033 (3.50%). Relative to 856,341 total captured solid quads,
that is 0.16%. These are compile observations including rebuilds, not unique visible
terrain or an LOD-on draw census. The screenshot shows ordinary rendering, and no
frame-time gain is claimed. This result rules out treating the conservative fixture's
76.2% reduction as representative of the live world. The performance target needs a
broader simplification/material design before live ownership can be promoted.

## Capture lifetime and queue completion — 2026-09-11

The earlier diagnostic released all copied output before returning from the compiler.
It now optionally retains bounded immutable candidates, still only under
`metalcraft.lodCompilerTest`. `SectionUpdateRenderState` stamps each region during
extraction. Its ticket includes world session, dimension, coordinates, revision and
material generation. Dirty-section hooks revoke edits and expanded neighboring seams;
chunk storage removal revokes unloaded columns. `setLevel`, `allChanged` and
`invalidateCompiledGeometry` revoke the relevant generations. The identity table is
bounded at 32,768 sections, and evicting an identity revokes outstanding work.

`SectionCompiler.Results` owns the copied candidate until construction of its
`CompiledSectionMesh`, which takes ownership exactly once. Cancellation, mesh close
or ticket revocation releases the CPU snapshot. Revocation also handles a candidate
being published concurrently. Separate 64 MiB build and 64 MiB retained-data budgets
remain charged while their corresponding objects are owned. Unsupported sections
still use ordinary rendering; no world state, sprite object or native source buffer
is retained by a candidate.

The first live run reached successful edits/reload/teleport but timed out waiting for
retained CPU bytes to clear after world close. Waiting for vanilla mesh disposal was
insufficient. Immediate disposal on ticket revocation fixed the lifecycle failure.
The final run passed in 43 seconds on Apple M4 Max, using a NORMAL generated world,
seed `metalcraft`, 16 render/simulation distance, Default/Metal, and Standard pack.
It transferred 178 candidates and closed all 178, ending with zero build bytes,
retained bytes, candidate owners and tracked section identities.

Artifacts: [validation](evidence/lod/capture-lifetime/validation.txt),
[counters](evidence/lod/capture-lifetime/metrics.json), and
[ordinary terrain screenshot](evidence/lod/capture-lifetime/scene.png).
The screenshot was inspected and shows generated snowy mountains and distant hills.
No terrain draws were replaced and no performance improvement is claimed.

`./gradlew build` passed in 11 seconds, including existing shader checks, CPU identity
and revocation fixtures, 1,000 seeded availability/adjacency graphs, and a real Metal
world-queue completion test over 20 submissions. The queue allocates its shared event
only on first use, reserves one value per borrowed submission, and signals after the
last pass ends. Completion polling never flushes, submits or waits; a declined draw's
otherwise empty reservation still completes. The live renderer must adopt this
timeline when it begins borrowing GPU meshes.

## Loaded geometry integration (2026-09-11)

P4 is now implemented behind `metalcraft.lodExperimental` (also enabled by the
`metalcraft.lodRenderTest` route). A generation-scoped index resolves actual atlas
sprite bounds. Compiler workers simplify final BLOCK output at four exact-surface
tiers; matching packed tint, AO, light and UV orientation are required for merging.
Valid solid layers can participate in mixed sections; cutout and translucent layers
keep ordinary draws. Unsupported solid geometry declines the whole solid replacement.
No-reduction candidates are discarded. Capture work stops when disabled and invalidates
retained candidates; enabling in a loaded world requests a fresh extraction.

`LodLoadedRenderer.prepare` runs before dispatcher locking, selects against scene
height/FOV and conservative bounds, considers neighbor constraints before upload,
and resolves actual uploaded availability before final balancing. CPU build admission
is bounded to 1/2/4 workers by the work setting, with separate 64 MiB retained data.
GPU admission is bounded by configured memory, renderer working-set headroom and
per-frame upload limits. In-flight allocations remain charged until the tested world
queue completion timeline advances. Reads of immutable CPU candidates do not acquire
worker monitors.

The LevelRenderer draw-construction hook retains exact section/compiled-mesh ownership.
The Metal backend preflights a source-compatible opaque shader and resources before
borrowing an uploaded mesh, rechecks owner/revision/atlas/origin, and suppresses the
ordinary draw only after encoding a replacement. Standard legacy/HDR and verified
no-pack variants retain projection, packed light/tint, G-buffer targets and depth.
Water's forward bindings are unchanged. Shadow collection independently culls light
volumes and may reuse any resident exact-position solid tier, with no new upload under
the dispatcher lock; cutout casters retain original UVs and geometry.

Production Metal readback tests compare original and coarse BLOCK meshes, poison atlas
neighbors, mip levels, reverse-Z depth, odd extents and shader reload rejection. Shadow
fixtures compare every texel of one through four cascade maps. Generated terrain route
checks include Standard/None, movement/zoom, disabled capture, edits, resource reload,
teleport, half resolution, odd resize, fullscreen, Nether/Overworld and saved-world reopen.
See `evidence/lod/live-route/` and `evidence/lod/live-lifecycle/`.

P5's full water matrix passes both merged and split paths. Whole-terrain counters now
separate ordinary/distant/replaced/shadow draws. Benchmark reports retain counter
endpoints and per-frame median/p95/p99 LOD selection, retirement and upload time.
The earlier few-percent replacement-only reductions must not be presented as whole-
terrain savings: the full water matrix measured well below 1% total triangle reduction.

Runtime multiresolution shading and persistent 32–256 chunk parent nodes are not
implemented. Their controls remain gated, as does ordinary release activation of
loaded geometry. The isolated shading-band prototype increases all-pass GPU cost
by about 108% at 4K. The first geometry A/B reduces distant triangles by only
0.06–0.24% and misses the frame-time gates. These failures, measurement limitations,
and subsequent ordered-pipeline batching work are recorded in
[LOD performance results](LOD_PERFORMANCE_RESULTS.md). No extended-distance result
or release readiness is claimed.
