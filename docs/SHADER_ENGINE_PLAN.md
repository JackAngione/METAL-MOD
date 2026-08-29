# Shader engine plan

Draft, 2026-08-23. Not yet a milestone in [ROADMAP.md](../ROADMAP.md).

A first-party shader engine for MetalCraft: full deferred lighting and shadows, shader packs written
in Metal Shading Language, a built-in pack tuned for Apple GPUs, and a tuning menu generated from the
pack itself.

This supersedes the direction in [SHADERPACK_PLAN.md](SHADERPACK_PLAN.md), which planned for Iris
GLSL compatibility. That document remains useful for its backend-gap inventory and for the reasoning
about where shader performance actually comes from; its compatibility phases are dropped. Deleting it
is a separate decision.

## The idea that justifies building this

An Iris pack expresses its dataflow implicitly. The engine discovers that `colortex2` is dead after
`composite3` only by scanning source for sampler reads, and OpenGL gives it nothing to do with the
answer even when it has it. So every G-buffer target is written to DRAM and read back from DRAM,
every frame, whether or not anything needed it to survive that long.

An Apple GPU is tile-based. A render target that is written and consumed inside one tile's lifetime
never has to exist in memory at all — `MTLStorageModeMemoryless` allocates no backing store, and a
fragment shader can read the previous fragment's value at the same pixel through programmable
blending. On a bandwidth-limited GPU, deleting the G-buffer round trip is the largest single
optimization available to a deferred renderer, and it is unavailable to any GLSL pack.

So the format's central rule is: **a pack declares its dataflow, and declaration is mandatory.**
Every target states its format, scale, and lifetime. Every pass states what it reads and what it
writes. From that declaration the engine derives, rather than guesses:

- which targets are transient enough to be memoryless,
- which passes can merge into one render encoder and resolve out of tile memory,
- per-attachment load and store actions, so a dead attachment stores `DontCare`,
- pass ordering, and the barriers between a compute write and a later sampled read.

Writing MSL is not the point. Being able to act on a declared graph is the point, and MSL is what
lets the engine act.

## Two findings that set the phase order

**Terrain vertices have no normal.** `DefaultVertexFormat.BLOCK` in 26.2 is Position, Color, UV0,
UV2 — no normal, no tangent, no material ID. `ENTITY` does carry `Normal`. The previous plan assumed
extending the terrain format was a prerequisite for lighting, which put a chunk-builder rewrite in
front of everything else.

It is not a prerequisite. Minecraft terrain is overwhelmingly axis-aligned quads, so a face normal
reconstructed from view-space depth derivatives — `normalize(cross(dFdx(viewPos), dFdy(viewPos)))` —
is exact for the flat case and wrong only on the small minority of non-axis-aligned geometry. That is
enough for directional lighting, shadow-normal offsetting, and SSAO. Normal mapping and anisotropic
specular need real tangents, and those need the vertex format extended, but they are a later
enhancement rather than a gate. **Full lighting and shadows can ship before the chunk builder is
touched at all.** This is the single largest change in scope from the previous plan.

**The pipeline layer already speaks MRT; the pass layer does not.**
[MetalRenderPipeline.java:283](../src/client/java/dev/metalcraft/client/metal/MetalRenderPipeline.java#L283)
validates up to eight color targets and the native pipeline builder loops over them, but
[MetalCommandEncoder.java:58](../src/client/java/dev/metalcraft/client/metal/MetalCommandEncoder.java#L58)
throws on anything other than exactly one color attachment, and the native render-pass descriptor only
ever fills `colorAttachments[0]`. Blaze3D itself supports eight. The G-buffer work is the pass side
catching up to three layers that are already ready for it.

## Pack format

A pack is a directory or `.zip` under `run/shaderpacks/`, containing `pack.json` and `*.metal`
sources. The built-in pack ships inside the mod jar and loads through the same path with no
privileged access — if the built-in pack needs something the format cannot express, the format is
wrong.

```jsonc
{
  "format": 1,
  "name": "MetalCraft Standard",
  "targets": {
    "gbuffer_albedo":  { "format": "rgba8_unorm",   "scale": 1.0, "lifetime": "transient" },
    "gbuffer_normal":  { "format": "rgba8_unorm",   "scale": 1.0, "lifetime": "transient" },
    "gbuffer_light":   { "format": "rgba8_unorm",   "scale": 1.0, "lifetime": "transient" },
    "scene":           { "format": "rgba16_float",  "scale": 1.0, "lifetime": "frame"     },
    "history":         { "format": "rgba16_float",  "scale": 1.0, "lifetime": "history"   },
    "shadow":          { "format": "depth32_float", "size": 2048, "layers": 4             }
  },
  "passes": [
    { "id": "gbuffer",  "kind": "geometry", "geometry": "terrain,entities,blockentities",
      "writes": ["gbuffer_albedo", "gbuffer_normal", "gbuffer_light", "depth"] },
    { "id": "resolve",  "kind": "fullscreen", "merge_with": "gbuffer",
      "tile_reads": ["gbuffer_albedo", "gbuffer_normal", "gbuffer_light", "depth"],
      "reads": ["shadow"], "writes": ["scene"] },
    { "id": "bloom",    "kind": "compute", "reads": ["scene"], "writes": ["bloom_chain"] },
    { "id": "final",    "kind": "fullscreen", "reads": ["scene", "bloom_chain"], "writes": ["drawable"] }
  ],
  "options": [ /* see the menu section */ ]
}
```

Three target names are reserved for attachments the host supplies rather than the pack allocating:
`scene` is Minecraft's own colour attachment, `depth` its depth attachment, and `drawable` the
surface being presented. A geometry pass writes `scene` first and `depth` alongside its own
channels, which is what keeps Minecraft's attachment at colour index zero for the geometry this
engine does not route, and what gives substituted and unsubstituted draws one depth order between
them.

`lifetime: "transient"` plus a `merge_with` that consumes it is what earns a target
`MTLStorageModeMemoryless` and a `tile_reads` binding instead of a sampler. The engine verifies the
claim: a target declared transient that is read by an unmerged pass is a load error naming both
passes, not a silent promotion to DRAM.

Pipeline-state compilation is ahead-of-time and cached. Import hashes the pack's MSL plus its state
descriptors, compiles the pipeline states, and writes them to an `MTLBinaryArchive` under
`run/shaderpacks/.cache/<hash>/`. A warm cache means switching packs compiles no pipeline states;
Metal may still recreate an `MTLLibrary` from source before resolving its functions from the archive.

## The built-in pack

Deferred, with the G-buffer sized to stay inside tile memory so the resolve can merge.

- **G-buffer, three targets plus depth.** Albedo RGB with a material flag in alpha; octahedral normal
  and roughness; block-light and sky-light plus sixteen-bit linear view depth (emissive is a material
  class). Twelve bytes of colour plus four of depth
  per pixel, which leaves headroom in the tile budget for the resolve's own imageblock use.
- **Deferred resolve merged into the G-buffer encoder,** reading all three targets through
  programmable blending rather than as sampled textures. The three G-buffer targets are memoryless
  and never allocated. This is the pack's reason to exist and the thing to measure first.
- **Cascaded shadow maps, four cascades, rendered in one pass** into a depth texture array using
  layered rendering with `[[render_target_array_index]]` chosen in the vertex stage. Four cascades in
  one encoder rather than four encoders is a second Apple-specific structural win, and it is the
  reason texture arrays move from "deliberately unsupported" to a Phase 0 requirement.
- **SSAO in compute at half resolution,** bilateral upsample, using reconstructed normals.
- **Volumetric light shafts,** optional, half-resolution compute raymarch against the shadow cascades.
- **Bloom as a compute mip chain,** downsample then upsample with a filtered combine, rather than
  fragment ping-pong between full-resolution targets.
- **Final pass writes the drawable directly** — tonemap, colour grade, and optionally MetalFX
  upscaling.

One caveat to record now rather than discover later: MetalFX temporal upscaling wants motion vectors.
Camera-only motion vectors are nearly free and correct for static terrain, which is most of the
screen. Entities and block entities will ghost until per-object previous transforms exist. Ship
spatial upscaling first and treat temporal as contingent.

## The tuning menu

Generated from the pack manifest, so the built-in pack and any third-party pack get the same screen
and neither needs Java code to be configurable. It extends the existing
**Video Settings → MetalCraft Settings** entry
([VideoSettingsScreenMixin.java](../src/client/java/dev/metalcraft/client/mixin/VideoSettingsScreenMixin.java)
already installs that button).

```jsonc
"options": [
  { "id": "shadow_resolution", "category": "shadows", "type": "enum",
    "values": [1024, 2048, 4096], "default": 2048, "apply": "reload" },
  { "id": "shadow_distance",   "category": "shadows", "type": "int",
    "min": 64, "max": 512, "step": 32, "default": 192, "apply": "uniform" },
  { "id": "ssao",              "category": "lighting", "type": "bool",
    "default": true, "apply": "recompile" },
  { "id": "exposure",          "category": "tonemap", "type": "float",
    "min": 0.25, "max": 4.0, "default": 1.0, "apply": "uniform" }
]
```

The `apply` field is the part that matters. Every shader-pack settings screen in existence recompiles
the world when you touch anything, because GLSL packs express every option as a `#define`. Here the
manifest distinguishes three costs, and the menu behaves accordingly:

- `uniform` — the value is a field in a uniform buffer. Applies on the next frame, no recompile, live
  while the slider is dragged. Most options should be this.
- `recompile` — the value is a compile-time constant that changes generated code. Rebuilds the
  affected pipelines only, from the binary archive when the resulting variant was cached before.
- `reload` — the value changes resource allocation, such as shadow-map resolution. Rebuilds the
  graph's targets.

The screen carries, alongside the options: preset buttons (Performance, Balanced, Quality), a live
readout of CPU frame time, GPU busy time, and the dominant stall source, and a per-option cost
annotation once one has been measured. `MetalStallProbe` already produces per-source frame
attribution and has a `setEnabled` switch
([MetalStallProbe.java:135](../src/client/java/dev/metalcraft/client/metal/MetalStallProbe.java#L135));
the readout should reuse that directly rather than the benchmark capture machinery in
`MetalFrameMetrics`, which is built for offline runs. Settings persist per pack in
`config/metalcraft-shaders.json`.

## Phases

Each phase ends in something that renders. Do not start the next until the current one does.

### Phase 0 — backend capabilities

Everything here is a hard blocker for a later phase; nothing here is speculative.

Branch: `metal-shader-engine`. Every item below is verified against the M4 Max by
`shaderTranslationSmoke`, and each assertion was checked to fail when its expectation is wrong,
because a GPU test that passes vacuously is worse than none.

- [x] **Multiple render targets through the pass layer.** The descriptor carries a positional list
  where a null entry reserves an index nothing is attached to, matching Blaze3D's
  `withUnusedColorAttachment`. `setPipeline` matches pipeline to pass index by index and names the
  index that disagrees. Relaxing that match also lets a pass with no colour attachments bind a
  pipeline whose targets are all unused, which is how the shadow pass in Phase 3 will draw.
- [x] **`MTLStorageModeMemoryless` textures and tile-memory reads.** Everything needing an address
  into such a texture - upload, readback, views, a load with nothing to load, a store with nowhere
  to store - is rejected where the caller can see it. The test fills two memoryless attachments and
  consumes them in a second draw in the same pass through framebuffer fetch.
- [x] **Texture arrays and layered rendering.** One instanced draw fills a four-layer array through
  `[[render_target_array_index]]`. Metal cannot infer a pipeline's primitive class when the vertex
  stage writes that, so raster state gained a topology class; leaving it unspecified keeps every
  existing pipeline as it was. Readback takes a layer, since a pass that ignored the layer index
  would still look correct from layer zero alone.
- [x] **Compute pipeline state and dispatch.** Encoders use serial dispatch, so one dispatch reads
  what the one before it wrote. Across encoders Metal's hazard tracking orders a compute write
  against a later render read, which is what the test measures rather than assumes.
- [x] **Per-attachment load and store actions driven by the caller.** `MetalRenderPass` carries the
  actions and the graph compiler now derives them from declared reads, writes, target lifetime, and
  merged tile consumers. The Blaze3D adapter still stores unconditionally because its
  `RenderPassDescriptor` has no discard concept; that stays the separate ranked item in
  [NEXT_STEPS.md](NEXT_STEPS.md).
- [x] **A persistent `MTLBinaryArchive` pipeline cache keyed on a content hash.** Render and compute
  descriptors are hashed with all shader and fixed-function state, cold entries explicitly add
  their functions and serialize under the hash, and warm entries fail on an archive miss rather
  than silently recompiling the pipeline state. An invalid persisted archive is logged, discarded,
  and rebuilt as a cold entry.

Exit: the smoke test draws into four attachments in one pass with two of them memoryless, reads the
result through tile memory in a merged second pass, dispatches a compute pass over the output, renders
four array layers in one encoder, and on a fresh-device second launch compiles no pipeline states.

### Phase 1 — graph runtime, pack format, and the menu skeleton

- [x] Pack discovery, `pack.json` parsing and validation, MSL compilation, binary-archive caching.
- [x] The graph compiler: lifetime analysis, memoryless promotion, pass merging, load/store derivation,
  and the validation errors that name the offending pass when a declaration is inconsistent.
- [x] Resource allocation and reallocation on resize and pack switch.
- [x] The generated menu, wired to the three apply modes, with whatever handful of options the trivial
  pack declares. It costs little once the manifest exists, and every later phase then gets its
  options in the UI for free rather than as a deferred task.

Exit: a built-in pack that does nothing but blit the scene to the drawable loads, hot-reloads on
pack switch, survives resize and resource reload, and exposes one option of each apply mode.

### Phase 2 — G-buffer fill

- [x] **Intercept the world draw** at the two places Minecraft opens a pass over its main target and
  binds a pipeline: `ChunkSectionsToRender.renderGroup` for terrain and
  `PreparedRenderType.drawFromBuffer` for entities, block entities, and every other feature. Two
  redirects each - the pass gains the pack's channels where it is created, the pipeline is swapped
  where it is bound - so the atlas and lightmap binds, the per-section uniform slices, and the
  batched multi-draw all keep running unchanged. A stand-in pipeline carries the vanilla one's
  bind-group layouts and vertex bindings verbatim, so every name the caller binds resolves to the
  same Metal slot; only the programs differ, and `MetalGpuDevice` gained a seam that compiles a
  pack's MSL in place of translating GLSL that was never written.
- [x] **MSL geometry programs for the three vanilla world formats** - `core/terrain`, `core/block`,
  `core/entity` - reproducing Minecraft's own shading into colour zero, because nothing lights the
  G-buffer yet and the game has to look the same. Terrain and loose block models reconstruct their
  normal from screen-space derivatives of camera-relative position; entities read the one world
  vertex format that carries a real normal. Minecraft's colour attachment stays at index zero, so
  unsubstituted geometry and the composite that follows still land where they always did.
- [x] **Material classification** from the pipeline's own compiled-in defines rather than a table of
  pipeline names, so a render type added by a mod is classified the way a vanilla one is: alpha
  cutout on world terrain is foliage, an entity program compiled without a lightmap is emissive.
  Water is a declared class that nothing reaches yet, because water is translucent terrain and
  Phase 4 is where translucency gets a forward pass. That is the one part of this phase's stated
  scope left undone, and it is undone by construction rather than by omission.

**The coverage bar, stated rather than discovered.** A draw is routed only when its vertex program is
one of the three, its colour target does not blend, its vertex format and bind-group layout match
what the program reads, and every compile-time define it carries is one the pack implements. A
blended draw cannot write a G-buffer - a normal, a material class and a light level are not
quantities that blend - which is what keeps translucent terrain, translucent entities and the glint
layer out on their own terms. Minecraft's item program (`core/item`: held items, dropped items, item
frames, maps) and its GUI programs are not implemented in this phase, and neither are the sky,
clouds, weather, particles, outlines or text. Every pipeline offered and declined is logged once per
pack.

Exit, met. `shaderTranslationSmoke` compiles all five substituted world variants on the device
through the real substitution path, refuses a blended one and a variant carrying an unimplemented
define, and then draws each of them:

- solid terrain, cutout terrain above its threshold, and the same cutout below it, where every
  attachment has to still hold the value the pass cleared it to - which is what proves the threshold
  is compiled in rather than merely declared;
- loose block models, whose colour modulator and model offset are read from their own std140 offsets
  and are therefore observable in the result rather than merely present;
- entities and their emissive variant, where the vertex normal is read rather than reconstructed and
  the cardinal light folds into the scene but deliberately not into the albedo.

The pack's last pass is then run once per debug view over a G-buffer the test wrote by hand, so the
octahedral decode, the material palette and the depth curve are each checked against a value chosen
rather than one a draw happened to produce. Every one of those expectations was perturbed and
checked to fail, because a GPU test that passes vacuously is worse than none.

In the client, the same world rendered with and without `-PmetalShaderPack=false` is pixel-identical
outside the first-person arm, whose animation phase differs by the same amount between two runs of
the baseline itself. Rendering each channel into Minecraft's own attachment - the only way to see
one, since Minecraft's screenshot reads the main render target rather than the drawable - shows the
normal channel at (128, 255, 217) across flat ground, which is octahedral +Y with solid roughness;
the material channel separating terrain from entities; and the light channel at full sky light and
no block light in open daylight.

At this phase boundary the runtime still refused compute passes rather than half-running them. Phase
5 now executes them in graph order; arbitrary merged render groups remain outside the one
geometry/resolve shape the world router can keep open.

### Phase 3 — shadows

- [x] Second world render from the light's direction, four cascades in one layered pass, with cascade
  selection, stabilisation against camera motion, and a configurable distance and resolution.
- [x] Depth bias and normal offsetting against acne and peter-panning, using the reconstructed normal.
- [x] Cutout foliage and entity handling in the shadow program.

Exit, met. The prepared terrain draw list is rebuilt against the union of four light frusta rather
than reused from camera visibility, while the already-prepared solid entity and block-entity buffers
are replayed once before the main world pass. One four-instance draw routes primitives to all four
layers through `render_target_array_index`; every later shadow draw loads the same layered depth
attachment, so terrain, cutouts, loose block models and entities share one encoder. Practical split
distances cover the configured range, each light-space centre is snapped to a shadow texel, and the
resolution rebuilds the target without recompiling unrelated pack state.

The depth pipeline combines slope-scaled bias with a fragment depth derived from the reconstructed
terrain normal (or the entity vertex normal) after a configurable world-space normal offset. The
smoke test compiles every routed shadow variant on the device, refuses blended geometry, draws an
alpha-tested triangle into all four array layers, and checks that sub-texel camera motion leaves a
fixed world point at the same shadow coordinate. The pack exposes each cascade as a debug view;
lighting consumes the map in Phase 4.

### Phase 4 — deferred resolve

- [x] The lighting model: sun and moon directional light with the shadow term, block and sky light from
  the G-buffer, ambient, and emissive.
- [x] Merge the resolve into the G-buffer encoder, read the G-buffer through tile memory, and confirm the
  three G-buffer targets allocate no backing store.
- [x] A forward pass for translucent geometry — water, glass, stained glass — after the resolve, because
  deferred cannot order translucency.

Exit, met. The resolve is the final draw in each opaque encoder lifetime. It framebuffer-fetches
scene, albedo, normal and light, reconstructs camera-view position from a sixteen-bit linear depth,
selects and PCF-samples the four shadow cascades, and combines sun/moon directional light, block and
sky light, ambient and emissive into Minecraft's scene attachment. A command that would break the
encoder first consumes any pending tile data, so unsupported opaque draws remain correct without
silently discarding the G-buffer.

All three G-buffer targets now use `MTLStorageModeMemoryless`, report a zero-byte device footprint,
cannot be viewed, uploaded, sampled or read back, and use `DontCare` stores. The GPU smoke test runs
the built-in resolve against those real memoryless allocations, checks that the shadow term darkens
an occluded surface, then blends a translucent forward draw over the result. Minecraft's ordered
translucent terrain and feature pipelines remain forward and begin only after the opaque resolve;
that covers water, glass, stained glass and translucent entities without putting non-orderable data
in the G-buffer. The automated Metal lifecycle test loads a singleplayer world, resizes, reloads
resources, captures a non-empty lit frame, and shuts down cleanly with this path active.

### Phase 5 — effects

- [x] **Graph execution for compute effects.** A compute pass compiles `<pass-id>_kernel`, binds its
  declared reads followed by writable images, dispatches over its first output, and is ordered by
  the compiled graph. `enabled_by` names a boolean pack option and removes the whole dispatch when
  it is off, so an effect's disabled measurement is not a shader branch that still pays for the
  work. Compute and fullscreen effects both publish encoder-boundary GPU occupancy under
  `MetalCraft shader: <pass-id>`.
- [x] **Half-resolution SSAO** with an eight-sample depth ring, view-space normal reconstruction,
  depth-discontinuity rejection, and a full-resolution bilateral sample in the grade pass.
- [x] **Compute bloom resolution chain:** half-resolution threshold/downsample, quarter-resolution
  downsample, then a filtered half-resolution upsample/combine.
- [x] **Half-resolution volumetric light shafts:** twelve view-ray samples transformed through the
  four shadow cascades, with sun/moon tint and a live strength option.
- [x] **Tonemapping and colour grading:** exposure, optional ACES curve, saturation, contrast and
  colour temperature, composed with AO, bloom and shafts into a sixteen-bit scene before output.
- [x] **MetalFX spatial upscaling.** The native bridge owns a reusable `MTLFXSpatialScaler`; when the
  drawable is larger than the graded scene, the graph's final draw is replaced by MetalFX. Equal
  sizes, unsupported devices, and the nearest/linear choices use the final fullscreen fallback.
  The settings screen is scrollable now that the built-in pack exposes seventeen options.

Exit, met. `shaderTranslationSmoke` renders the effect graph with all three optional effects enabled
and disabled and requires distinct non-empty output, checks a solid HDR image through MetalFX at 2x,
and proves compute encoders report positive GPU time. The lifecycle test then loads a world, resizes,
reloads resources, presents through both the linear and MetalFX paths, captures a non-empty frame,
and shuts down cleanly.

The short 2026-08-29 lifecycle capture at a 1280x720 world resolution measured these mean
encoder-boundary spans per frame on the M4 Max: SSAO 0.014 ms, bloom down-half 0.014 ms,
down-quarter 0.006 ms, bloom up 0.018 ms, volumetrics 0.026 ms, and grade/composite 0.068 ms. The
linear final draw measured 0.089 ms in the adjacent run. These are diagnostic occupancy spans, not
an additive partition or a repeated performance claim. MetalFX correctly replaced the final draw in
its run; its internal encoders cannot be labelled by `MetalPassCensus`, so its comparative cost stays
in Phase 6's repeated whole-frame measurement rather than being inferred from two lifecycle runs.

### Phase 6 — measurement

The performance argument at the top of this file is a mechanism, not a result. Phase 6 tests it one
change at a time with three repeats and records the results in
[APPLE_SILICON_PERFORMANCE.md](APPLE_SILICON_PERFORMANCE.md), including negative and inconclusive
results.

- [x] **Reproducible mechanism harness.** `shaderPhaseSixBenchmark` interleaves the two sides of each
  A/B inside every repeat, reports repeat spread, and writes its full JSON under `build/reports`.
- [x] **Memoryless G-buffer and merged resolve:** 0.0325 ms against 0.1326 ms for stored attachments
  and a sampled second pass, a 75.5% reduction in the isolated GPU workload.
- [x] **Layered cascades against four encoders:** 0.0662 ms against 0.0612 ms. The layered path is
  8.1% slower on the isolated GPU workload, so it is retained for its one-pass CPU submission shape,
  not claimed as a GPU optimization.
- [x] **Compute bloom against fragment ping-pong:** 0.0395 ms against 0.0465 ms, a 15.0% reduction.
- [x] **Whole pack against vanilla Metal:** matching three-repeat captures completed. Both were
  compositor-paced to 120 Hz, making FPS and presentation-contaminated `GPU_FRAME` unsuitable for a
  performance claim. The measurable CPU cost added by the pack is 1.66-1.86 ms at p50.
- [x] **OpenGL/Iris comparison disposition:** no visually comparable Iris pack exists here, and
  GLSL/Iris compatibility is an explicit non-goal. Vanilla OpenGL or an unrelated pack would change
  both renderer and workload, so Phase 6 records the comparison as unavailable rather than publishing
  a misleading number.

Exit, met. Every runnable comparison has three repeats, the mechanism results exceed or explicitly
fail the historical spread, the whole-world capture records its pacing confound, and the unavailable
cross-backend workload is named rather than silently substituted.

## Risks worth stating before starting

- **A new format starts with one pack.** Dropping GLSL import means there are no third-party packs at
  launch and the built-in pack carries the entire value of the feature. That is a deliberate trade for
  a format that can express tile-memory dataflow, but it means the built-in pack has to be good, not
  merely correct.
- **A fully-MSL engine cannot run on the OpenGL recovery path.** The pack must disable itself and say
  so, rather than render wrongly, whenever the active backend is not Metal.
- **Draw interception is a long tail.** Weather, particles, the sky, clouds, item frames, maps, the
  end portal, and every mod that adds a render type. Pick the coverage bar explicitly and state what
  is unhandled instead of discovering it in screenshots.
- **The bottleneck may be somewhere else entirely.** [NEXT_STEPS.md](NEXT_STEPS.md) records 170 ms of
  a 175 ms spike sitting in Minecraft's main-thread task queue, with 0.015 ms of Metal work inside it.
  Profile with the pack active before Phase 6 rather than after: if that queue still dominates, the
  bandwidth work is improving a number nobody is waiting on.
- **Tile budget is a hard ceiling.** If the G-buffer plus the resolve's imageblock use exceeds the
  tile memory budget, the merge silently stops being possible and the pack falls back to DRAM. Check
  the actual budget on the target hardware before committing to the G-buffer layout, not after.

## Non-goals

- Iris or OptiFine pack compatibility, and GLSL import in any form.
- Any platform other than macOS arm64 with the Metal backend active.
- Matching any existing renderer's output.
- Extending the terrain vertex format, until normal mapping specifically requires it.
- Any performance claim before Phase 6 produces repeated measurements.
