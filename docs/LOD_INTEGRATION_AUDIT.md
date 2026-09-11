# LOD integration audit

P1 work in progress, 2026-09-10. No LOD rendering is enabled by this audit.

## Verified mapped interfaces

Inspected the local Minecraft 26.2 client-only deobfuscated jar with `javap -private`.
These are verified signatures; lifecycle ordering still needs bytecode/source tracing
and runtime validation before installing hooks.

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
