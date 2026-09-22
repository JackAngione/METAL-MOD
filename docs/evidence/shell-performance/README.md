# Shell performance investigation

September 21, 2026. Local captures occurred September 20, 20:46–20:52 EDT.

The shell conversion removes distant block-model tessellation, but does not turn
the distant world into independently streamed models. Two 20-second stationary
captures used separate copies of `Metalcraft Memory Paged`, a NORMAL world, on
Default/Metal: render 128, simulation 16, quality radius 10, reduction 5, pixel LOD
enabled, None pack, FOV 90, 1708×960 and a 16 GiB heap. Originals were not modified;
the user's options were restored after testing.

## Located costs

1. **Full world chunks still load across the view distance.**
   `ChunkMapNativeDistanceMixin` makes the integrated server follow the expanded
   render distance. `PlayerTicketNativeDistanceMixin` extends native chunk ticket
   tracking; neither consults native quality distance. `NativeTerrainLod` uses
   that smaller radius only for mesh selection. The follow-up's final sample held
   **26,027 complete client chunks**. Server loading/generation, transmission,
   palettes, lighting and render-region snapshots all survive the shell conversion.
   The first JFR attributes approximately 17.17 GB of weighted allocation samples
   during the capture to remaining native sky column-index copies, 4.89 GB to
   palette re-encoding and 3.34 GB to server chunk-map promotion. These are estimated
   allocation over time, not retained heap or exact byte counters.
2. **Distant fluid meshes remain at native detail.**
   `SectionCompilerLodMixin` skips `ModelBlockRenderer.tesselateBlock`, but still
   calls the original compiler and `FluidRenderer.tesselate` for every fluid block.
   `SectionCompilerWaterMixin` creates the full water metadata sidecar. At the last
   baseline sample, far shell-tagged sections held **581,604 solid indices and
   10,549,368 translucent indices**—18.1 times as many fluid indices. Model blocks
   are skipped in these sections, so their translucent output is the fluid path.
   Water sorting, sidecars and draws remain. Solid lava is preserved too.
3. **Per-section CPU work remains expensive.**
   The baseline ended at 16,671 renderable visible sections. JFR samples identify
   draw preparation/annotations, uniform binding, occlusion graph updates and
   section lookups. Recorded command batches totalled 2.96 GB across 1,054 frames;
   submission consumed 3.11 ms/frame on average. GPU occupancy averaged 2.84 ms/frame,
   versus 14.47 ms median CPU loop and 17.42 ms median frame interval. GPU occupancy
   is asynchronous and must not be subtracted from CPU time. This capture points
   primarily to CPU work, not a GPU polygon throughput limit.

There were **zero far native renderable block meshes** in every sampled visible
and resident census. All far renderable sections were shell-tagged. No offscreen
native meshes were found at this stationary camera; this does not prove their
absence after travel or a quality-radius change. The historical separate LOD
renderer is disabled. Reduced pixel shading draws the same shell geometry again
for coverage/depth reconstruction; it does not keep a hidden detailed block mesh.

## Targeted correction and validation

`LevelRendererLodDrawMixin` and `LevelRendererWaterDrawMixin` now use
`ModifyExpressionValue` to annotate an existing draw. Their former nested
constructor wrappers created operation objects, varargs arrays and boxed numeric
arguments for every section/layer/frame. The baseline JFR attributed about
0.93 GB of sampled allocation to the LOD wrapper callsite. The replacement removes
that wrapper; it preserves mesh selection, draw order, water bindings and ownership.

The opt-in `MetalMemoryProbe` now records installed solid/cutout/translucent indices
by near/far and native/shell state, both visible and resident, plus loaded full chunks
and LOD settings. Missing counter keys mean zero. It adds no census work in normal
gameplay.

Both copied-world runs finish without a renderer error. The post-change run also
exercises the transformed draw annotations and reduced-pixel passes. The complete
`MTL_DEBUG_LAYER=1 ./gradlew build --offline` passes in 26 seconds, including existing
shell, water metadata, sorting, Metal GPU and lifetime checks. No water shader or
geometry change is included, and no new Standard-pack visual acceptance is claimed.

`before.json` is the uncontended baseline frame report. `after.json` and `after.log`
verify integration and residency, **not FPS improvement**: the full build started
before that capture finished. Its timing is contaminated. Both scenes also grow
during loading. No steady-state, repeatable FPS gain or fixed-workload comparison
is claimed. `profile-summary.json` retains JFR sample attribution; ancestor counts
overlap and stacks are truncated. Full recordings remain at
`/private/tmp/metalcraft-shell-before.jfr` and `metalcraft-shell-after.jfr`.

## Remaining implementation boundaries

- A true model-only distant region needs a separate shell data/streaming path,
  bounded full-chunk tickets near the player, and safe native handoff on approach.
  Simply lowering native loading distance would make the current distant shells
  disappear because they are built from those full chunks.
- Fluid LOD must rebuild water metadata and translucent sort state with the reduced
  vertices, preserving shorelines, flow and underwater faces. Dropping the native
  layer without a replacement would delete water.
- Larger combined shell draws can reduce per-section submission cost independently
  of polygon count. The current upload unit is still each 16³ section.

## Reproduction

Copy a NORMAL save, set 128/16 and the settings above, then run:

```sh
MTL_DEBUG_LAYER=0 ./gradlew runClient \
  '-PmetalJvmArgs=-Xmx16G -Dmetalcraft.memoryProbeSeconds=20 -XX:StartFlightRecording=filename=/private/tmp/metalcraft-shell-profile.jfr,settings=profile,dumponexit=true' \
  --args='--graphicsBackend default --quickPlaySingleplayer "COPY NAME"' --offline
```

The probe writes `run/build/memory-probe-paged.json`, saves the copy and exits.
Wait for the probe and client to finish before any benchmark/build comparison.
