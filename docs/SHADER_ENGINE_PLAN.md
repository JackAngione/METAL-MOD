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
  and roughness; block-light and sky-light with emissive. Twelve bytes of colour plus four of depth
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

- Intercept the world draw and route terrain, entities, and block entities into the pack's G-buffer
  pass. The substitution point is the frame graph and `RenderType`, above the backend: by the time
  `MetalRenderPassBackend.setPipeline` sees a pipeline, the pass has already begun with vanilla's
  single attachment.
- MSL geometry programs for the vanilla vertex formats, with normals reconstructed from depth
  derivatives.
- Material classification: which draws are emissive, which are foliage, which are water.

Exit: a debug view of each G-buffer channel is correct across terrain, entities, block entities, and
cutout foliage.

### Phase 3 — shadows

- Second world render from the light's direction, four cascades in one layered pass, with cascade
  selection, stabilisation against camera motion, and a configurable distance and resolution.
- Depth bias and normal offsetting against acne and peter-panning, using the reconstructed normal.
- Cutout foliage and entity handling in the shadow program.

Exit: shadows are stable under camera motion and free of acne at every cascade boundary at the
default settings.

### Phase 4 — deferred resolve

- The lighting model: sun and moon directional light with the shadow term, block and sky light from
  the G-buffer, ambient, and emissive.
- Merge the resolve into the G-buffer encoder, read the G-buffer through tile memory, and confirm the
  three G-buffer targets allocate no backing store.
- A forward pass for translucent geometry — water, glass, stained glass — after the resolve, because
  deferred cannot order translucency.

Exit: the world is lit and shadowed, translucency composites correctly, and a capture confirms the
G-buffer never reaches DRAM.

### Phase 5 — effects

SSAO, bloom, volumetrics, tonemapping and colour grading, MetalFX spatial upscaling. Each one lands
with its options in the menu and a measurement of its cost.

### Phase 6 — measurement

The performance argument at the top of this file is a mechanism, not a result. Test it here, one
change at a time, with `-PmetalBenchmarkRepeats=3`, against the 8–10% run-to-run spread this harness
has. Specifically worth isolating:

- memoryless G-buffer and merged resolve, against the same pack with both disabled,
- layered single-pass cascades against four encoders,
- compute bloom against fragment ping-pong,
- the whole pack against vanilla Metal, and against OpenGL with a comparable Iris pack, which is the
  comparison a user actually cares about.

Record the numbers in [APPLE_SILICON_PERFORMANCE.md](APPLE_SILICON_PERFORMANCE.md) whichever way they
come out. If the memoryless resolve is worth less than the spread, that is the finding.

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
