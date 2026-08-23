# Custom shader plan

Draft, 2026-08-23. Not yet a milestone in [ROADMAP.md](../ROADMAP.md).

The request is a custom shader system for MetalCraft, running Iris-style shader packs, with packs
that are fully Metal Shading Language. This file answers the performance question that motivated it,
records what the backend can and cannot do today, and proposes a phased build.

## Does converting Iris packs to MSL make them faster?

By itself, no. The language is not where the time goes. Split the frame into the parts a conversion
could touch:

**Shader execution on the GPU: neutral, possibly slightly negative.** An Iris pack on macOS today
goes GLSL → Apple's OpenGL frontend → AIR → GPU. Converting ahead of time goes GLSL → SPIR-V → MSL →
AIR → GPU. The last two stages, which are the ones that decide how many cycles the shader burns, are
identical. SPIRV-Cross output is occasionally worse than a driver's own path because of interface
structs and extra temporaries. Assume parity and be pleased if it is better.

**Shader compilation and pack load: real win.** An Iris pack compiles hundreds of programs, and the
resulting stutter and multi-second load are among the loudest complaints about shaders on any
platform. Metal can serialize compiled pipeline states into an `MTLBinaryArchive` and reload them on
the next launch. Apple's GL driver has no equivalent MetalCraft can drive. This win is available
regardless of how far the rest of the plan gets.

**CPU cost per draw: real win, but already banked.** Apple's GL 4.1 implementation is deprecated and
validates heavily per call; Metal encoding is far cheaper. That is MetalCraft's existing thesis and
it applies to shader packs the same way it applies to vanilla. It is not a reason to convert shader
source.

**Restructuring the pack's pass graph for tile-based deferred rendering: this is the actual win, and
it is not a language conversion.** A deferred Iris pack writes four to eight `colortex` targets in
`gbuffers`, then reads them back in `composite`. On an Apple GPU that round trip is DRAM bandwidth,
and bandwidth is usually what a shader pack runs out of first. Metal can express things OpenGL 4.1
cannot:

- `MTLStorageModeMemoryless` attachments, so a G-buffer consumed by a merged pass never reaches DRAM.
- Explicit `DontCare` load and store actions on attachments that are dead across a pass boundary.
- Programmable blending and imageblocks, so a deferred resolve reads the G-buffer from tile memory.
- Argument buffers and indirect command buffers, against the sixteen-sampler rebind per program.
- MetalFX, as a native replacement for a pack's own temporal upscaling pass.

None of those are reachable by translating a pack's source. They need the system to know the pack's
dataflow — which target each pass writes, which pass reads it next, when a target dies. A parsed
Iris pack does carry that information, in `shaders.properties` and the `RENDERTARGETS` directives.
So the performance claim is: **the win comes from understanding the pass graph, and MSL is what lets
you act on that understanding.** Conversion is the enabler, not the improvement.

Nothing above is measured. It is a hypothesis with a mechanism, and this repository's rule is that a
hypothesis is worth exactly one benchmark run. Phase 5 exists to test it, and the plan is arranged so
the earlier phases are worth shipping even if the thesis turns out to be worth 3%.

## A caveat on "fully MSL packs"

There are no MSL shader packs. Every pack that exists is GLSL, and asking authors to hand-write MSL
for a single mod on a single platform produces an ecosystem of zero packs.

The resolution is that MSL is the **runtime and cache format**, not the authoring format:

- A pack is imported once. `MetalShaderTranslator` already does GLSL → SPIR-V → MSL, and the import
  writes the MSL plus a compiled `MTLBinaryArchive` into `run/shaderpacks/.cache/<pack-hash>/`.
- At load, MetalCraft reads MSL and pre-built pipeline states. Nothing translates at frame time, and
  a warm cache skips compilation entirely.
- A pack may also ship MSL directly, per pass, and skip import. `MetalRenderPipeline.Descriptor`
  ([MetalRenderPipeline.java:232](../src/client/java/dev/metalcraft/client/metal/MetalRenderPipeline.java#L232))
  already accepts MSL sources, so this seam exists today.

That gives fully-MSL execution, which is what the performance argument needs, without a dead
ecosystem. Authors who want Metal-only features write MSL; everyone else ships the GLSL pack they
already have.

## What the backend can and cannot do today

Confirmed by reading the code, not assumed.

**Available.**

- Up to eight color targets in a *pipeline* descriptor, matching Blaze3D's own eight
  (`RenderPipeline.Builder.colorTargetStates` is `new ColorTargetState[8]`).
- Direct MSL pipeline creation, separate vertex/fragment libraries, linked into one owned module.
- A flattened resource-slot ABI preserved across stages, with per-stage bind masks from SPIRV-Cross
  reflection.
- Typed texel buffers, private textures, mip-range views, samplers, fences, timestamp queries.
- Vanilla `PostChain` / `PostChainConfig` / `FrameGraphBuilder`, which already run through the Metal
  backend. This is the seam Phase 1 builds on and it needs no backend change at all.

**Missing, and each one is a blocker for some phase.**

- **Multiple render targets in a render pass.** The encoder throws today:
  [MetalCommandEncoder.java:58](../src/client/java/dev/metalcraft/client/metal/MetalCommandEncoder.java#L58)
  requires exactly one color attachment, and the native descriptor only ever fills
  `colorAttachments[0]`. Hard blocker for `gbuffers`.
- **Compute pipelines.** Not present anywhere in the Java or Objective-C layer. Modern packs use
  `.csh` for lighting, bloom downsampling, and histogram exposure.
- **Texture arrays and 3D textures.** Deliberately unimplemented. Needed for `shadowcolor` arrays and
  for packs that use volumetric LUTs.
- **Load/store liveness.** Every pass stores color and depth unconditionally. Already tracked as an
  open item in [NEXT_STEPS.md](NEXT_STEPS.md); it becomes load-bearing here rather than merely
  wasteful.
- **Pipeline-state caching to disk.** `pipelineCache` is an in-memory `IdentityHashMap` keyed on the
  `RenderPipeline` object and cleared on reload.

**Not reusable.** Iris itself cannot be ported or depended on. It issues raw OpenGL throughout, which
is exactly what MetalCraft's compatibility contract rules out. This plan reimplements Iris's *pack
semantics* on Blaze3D. That reimplementation, not the MSL question, is the dominant cost.

## Phases

Each phase ends in something demonstrable. Do not start the next one until the previous one renders.

### Phase 0 — backend prerequisites

Only the parts later phases actually block on.

- Extend `MetalRenderPass.Descriptor` and the native descriptor builder from one color attachment to
  eight. Widen `MetalCommandEncoder.createRenderPass` to pass Blaze3D's full attachment list through,
  and widen `MetalRenderPassBackend` to match. The pipeline side already loops over eight targets, so
  this is the pass side catching up.
- Add per-attachment load/store actions derived from frame-graph liveness, so a dead target stores
  `DontCare`. This is already a ranked item; do it here because the G-buffer is where it pays.
- Add an `MTLBinaryArchive`-backed pipeline cache keyed on a content hash of the MSL plus the state
  descriptor, persisted under `run/shaderpacks/.cache/`.

Exit: the shader-translation smoke test draws into four color attachments in one pass and reads all
four back, and a second launch loads its pipelines from the archive with no compilation.

### Phase 1 — post-process packs

The subset of Iris that needs no vertex-format surgery, no shadow pass, and no draw interception:
`composite1..N` and `final`, reading `colortex0` and `depthtex0`.

- A pack source that reads folders and `.zip` files from `run/shaderpacks/`, separate from the
  resource-pack list.
- A parser for the pack subset in scope: `shaders.properties` options, `#define` toggles, the
  `RENDERTARGETS` and `DRAWBUFFERS` directives, and the `composite`/`final` naming convention.
- Compile the parsed graph into a `PostChainConfig`-equivalent and run it through the existing frame
  graph. Vanilla's own post chain already does this; the new work is producing the config from Iris
  conventions rather than from a Mojang JSON file.
- The uniform subset these passes need: camera position and matrices and their inverses, sun and moon
  vectors, `frameTimeCounter`, viewport size, rain strength, biome and weather scalars.
- A settings screen alongside **Video Settings → MetalCraft Settings**: pack selection, and the
  pack's own options surfaced from `shaders.properties`.

Exit: a real pack's bloom, tonemapping, and depth-of-field render correctly at 32 chunks, and the
benchmark harness reports the cost.

### Phase 2 — gbuffers and deferred lighting

The large one.

- Map every vanilla `RenderPipeline` location to an Iris program (`gbuffers_terrain`,
  `gbuffers_entities`, `gbuffers_water`, `gbuffers_skybasic`, and the rest), and substitute the pack's
  pipeline when a pack is active. `MetalRenderPassBackend.setPipeline` receives the `RenderPipeline`
  object, but substitution has to happen above it: the pass is already begun with vanilla's single
  attachment by then, and a `gbuffers` program needs the pass itself to be MRT. The interception
  point is therefore the frame graph and `RenderType`, not the backend.
- Extend chunk and entity vertex formats with the attributes packs expect — `mc_Entity`,
  `mc_midTexCoord`, `at_tangent`, block and entity IDs. This touches chunk building and is the single
  largest piece of work in the plan. Budget accordingly and treat every other estimate here as
  contingent on it.
- `deferred1..N` passes between the G-buffer and composite stages.
- Depth copies at the points Iris defines them (`depthtex0/1/2`).

Exit: a deferred pack lights the world correctly, with no missing or misassigned material IDs.

### Phase 3 — shadow pass

- A second world render from the light's view into `shadowtex0/1`, with its own frustum and a
  configurable resolution and distance.
- `shadowcolor0/1`, which needs the texture-array support deferred from Phase 0.
- Entity, terrain, and cutout handling in the shadow program.

Exit: shadows match the pack author's intent on a pack that is known-good elsewhere.

### Phase 4 — compute passes

- Compute pipeline state, dispatch, and threadgroup memory in the native layer and the Blaze3D
  adapter, plus the barriers between a compute write and a later sampled read.
- `.csh` translation through the existing SPIR-V path.

Exit: a pack whose bloom or exposure runs in compute produces the same image as its fragment path.

### Phase 5 — the TBDR optimization pass, and the measurement

This is where the performance claim at the top of this file gets tested. Not before.

- Derive attachment lifetimes from the parsed pass graph and mark every transient G-buffer
  `MTLStorageModeMemoryless`.
- Merge adjacent passes that share a tile footprint, and use programmable blending or imageblocks for
  a deferred resolve that would otherwise round-trip through DRAM.
- Argument buffers for the pack's sampler set.
- Evaluate MetalFX against a pack's own TAA and upscaling.

Measure each of these separately, with `-PmetalBenchmarkRepeats=3`, against the 8–10% run-to-run
spread this harness has. One change, one measurement, and re-measure before believing it. If the
memoryless G-buffer is worth less than the spread, say so in
[APPLE_SILICON_PERFORMANCE.md](APPLE_SILICON_PERFORMANCE.md) and stop.

## What could invalidate the plan

- **The vertex-format work in Phase 2 is larger than everything else combined.** If it stalls, Phase
  1 still ships and is still worth having; the plan is ordered so that this is true.
- **Pack compatibility is a long tail, not a milestone.** Packs rely on undocumented Iris and
  OptiFine behaviour. Pick two or three reference packs, state which they are, and refuse to claim
  general compatibility.
- **The TBDR thesis may be small.** If Minecraft with a pack is still CPU-bound on the main-thread
  task queue that [NEXT_STEPS.md](NEXT_STEPS.md) identifies as a 170 ms component of a 175 ms spike,
  then saving G-buffer bandwidth changes a number nobody was waiting on. Check where the time goes
  with a pack active *before* building Phase 5.
- **OpenGL fallback loses shaders entirely.** A fully-MSL pipeline cannot run on Minecraft's recovery
  backend. The pack must disable itself, visibly, rather than render wrongly.

## Non-goals

- Bit-exact Iris output. The target is that a pack looks right, not that it matches Iris pixel for
  pixel.
- Running unmodified Iris as a dependency.
- Supporting packs on anything other than macOS arm64 with the Metal backend active.
- Any performance claim before Phase 5 produces repeated measurements.
