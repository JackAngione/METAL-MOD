# Water visual effects implementation plan

Created: 2026-09-05. Status: started on `codex/water-effects`; W1 complete; W2 HDR prerequisite in progress (W2r/W2s done; W2q wrap helper live-gated; display open).

## Outcome and scope

Add animated water normals, Fresnel reflections and sun highlights, depth-based color
and refraction, restrained shoreline foam, and underwater absorption/fog to the Standard
shader pack. Preserve biome tint, flowing water, resource reload, and the disabled vanilla
path. Target the repository's direct Apple Metal renderer, despite the workspace name.

Start with normal animation rather than moving vertices. Geometry displacement,
interactive ripples, caustics, planar reflection cameras, and temporal reflection history
are deferred. Screen-space reflections are an optional quality tier after the base effects
pass validation; sky/environment reflection supplies the baseline and fallback.

## Agent progress protocol

This file is the authoritative water work tracker. Every agent implementing, reviewing,
or validating water effects must read it and update it before handing work back.

1. Select a task whose dependencies are complete. Set its status to `in progress` and
   replace `unassigned` with your agent/task identifier before editing implementation files.
2. Keep its checkbox unchecked while work or required validation remains. Record partial
   progress and the next concrete action in its evidence cell when handing off unfinished work.
3. After all acceptance criteria pass, change `[ ]` to `[x]`, set status to `done`, and
   record the completion date, changed files or commit, validation commands/results, and
   screenshot/benchmark artifact paths. A successful build alone does not prove a visual effect.
4. If blocked, use status `blocked`, leave the checkbox unchecked, and record the missing
   prerequisite and what will unblock it. Unavailable GPU or in-game validation is a blocker
   for the corresponding acceptance criteria, not a pass.
5. Reopen a completed task if a regression invalidates its evidence. Update this plan in
   the same change as the implementation, including any necessary dependency revisions.

When several agents are assigned, use separate file ownership and serialize edits to this
tracker through the coordinating agent. Each worker reports its task ID and evidence; the
coordinator applies and verifies the update before marking the handoff complete. Shared
changes to the geometry adapter, bindings, manifest, or native bridge need one owner at a time.

## Progress tracker

| Done | ID | Deliverable | Depends on | Owner | Status | Evidence / next action |
| --- | --- | --- | --- | --- | --- | --- |
| [x] | W1 | Water identity and composition design | — | /root | done | 2026-09-05: mapped fluid/sorting/composition audit, chosen forward/metadata/depth/blend contracts, and live water-only diagnostic verified. Build and Metal lifecycle pass; see W1 completion evidence below for files, commands and captures. |
| [ ] | W2 | HDR composition prerequisite | W1 | grok | in progress | 2026-09-06: W2r reload ShaderSource and W2s Fabulous/PostChain promotion are implemented. W2q wrap helper/GPU smoke exist; live HDR session is gated because LINEAR_SRGB identity routing dropped rebuilt terrain from the water identity fixture. Physical display validation remains open. |
| [ ] | W3 | Water routing and stable frame inputs | W1, W2 | unassigned | not started | Implement material identity, snapshots, and lifetime checks. |
| [ ] | W4 | Animated surface and baseline reflections | W3 | unassigned | not started | Add bounded normal animation and water lighting. |
| [ ] | W5 | Refraction and depth absorption | W4 | unassigned | not started | Implement validated water thickness and scene sampling. |
| [ ] | W6 | Shoreline foam and underwater appearance | W5 | unassigned | not started | Implement foam and one underwater fog policy. |
| [ ] | W7 | Controls and optional screen-space reflections | W5 | unassigned | not started | Add quality controls and measure bounded SSR. |
| [ ] | W8 | Integrated validation and release defaults | W6, W7 | unassigned | not started | Run regression scenes, lifecycle checks, and paired benchmarks. |

## Implementation tasks and acceptance criteria

### W1 — Establish water identity and composition order

Read `WorldGeometryAdapter`, `WorldComposition`, `MetalShaderFrameExecutor`, the Standard
pack, and the mapped Minecraft fluid/translucency call sites. `Material.WATER` and a water
roughness branch already exist; they do not establish a working water rendering path.

Determine how water can be identified per fluid face in shared translucent terrain batches.
Use explicit fluid/material metadata or a verified resource-aware mapping; tint or the
translucent pipeline alone cannot distinguish water from glass, ice, slime, or lava.
Specify still/flowing surfaces, vertical faces, waterlogged blocks, resource-pack reload,
and underwater viewing. Record the required mesh/upload changes if identity is absent.

Document the actual opaque snapshot, water rendering, other transparency, Fabulous,
world grading, hand, underwater overlay, and HUD order. Choose a water surface pass or
supported forward substitution that preserves sorting and depth behavior. Treat a late
fullscreen effect as requiring an explicitly exported water mask and surface depth.

**Complete when:** append the chosen routing, file ownership, target/buffer contract,
depth convention, alpha/blend convention, and transparency limitations to this document;
demonstrate a debug identity view distinguishing water from other translucent materials.

### W2 — Complete the existing HDR prerequisite

Follow [SHADER_COLOR_CONTRACT.md](SHADER_COLOR_CONTRACT.md) and PR 7b in
[METAL_SHADER_ENGINE.md](METAL_SHADER_ENGINE.md). Reuse completed upstream work instead
of creating a competing color path. Live Standard rendering currently uses the legacy
8-bit path; isolated floating-point helper tests are insufficient.

**Complete when:** world color survives above 1 through opaque and forward composition,
participating pipelines blend/fog in the agreed linear space, and world-only grading
performs one tone/output conversion before hand/HUD. Validate actual display output and
transparent overlap. Record GPU readback and in-game evidence in both the existing engine
tracker and this task. Water's bounded dielectric BRDF may be implemented in W4 without
waiting for unrelated opaque-material GGX work.

### W3 — Route water and bind stable scene data

Likely integration points: `WorldGeometryAdapter`, `WorldComposition`, `FrameBindings`,
`ShaderTargetAllocator`, `ShaderGraphCompiler`, and `MetalShaderFrameExecutor`.
Add a focused water module if frame resources need ownership; introduce named typed
bindings rather than an unrelated general buffer bag.

Provide water surface position/normal and material identity; snapshot opaque color and
depth before water modifies them. Specify each texture's format, extent, sample count,
usage, producer, and lifetime. Bind projection/inverse projection, camera-relative position,
stable animation time, biome tint/flow information, and camera-in-water state as needed.
Keep uniforms safe across frames in flight. Stored water data must survive its consumers;
memoryless G-buffer attachments cannot be sampled by later passes.

**Complete when:** debug views prove water-only coverage and correct surface/opaque depth
at native and half resolution; glass and lava retain their appearance. Validate Fabulous
and ordinary transparency, resize/reload/world changes, clear values, and no sampled
read/write texture aliasing or CPU readback in the frame loop. Missing inputs select a
defined fallback. Add focused routing and GPU fixture coverage.

### W4 — Animate the surface and add reflection lighting

Proposed new pack files: `water.metal` and `shared/water.metal`, with manifest wiring.
Use two bounded wave/normal scales anchored to stable world coordinates, accounting for
camera-relative rendering and long-running time precision. Respect flow direction and
vertical faces. Begin without vertex displacement to preserve chunk seams and water edges.

Implement a finite dielectric Fresnel/roughness model with sky/environment reflection,
sun specular, and a documented indirect/blocklight policy compatible with existing lighting.
Reuse sun/weather/shadow inputs where their validity has been established. Reflection
fallback must work at night, in caves, and in dimensions without the normal sky/sun.

**Complete when:** camera movement does not make waves swim, adjacent chunks have no seams,
still and flowing water animate appropriately, grazing highlights remain finite, and
night/cave scenes avoid bright daytime reflections. GPU fixtures cover normal normalization,
Fresnel endpoints, extreme roughness, and zero-strength behavior; save in-game comparisons.

### W5 — Refract and absorb scene color

Reconstruct positions using the documented projection/depth convention. Estimate thickness
from the water surface and opaque depth in a common space, with explicit behavior for sky,
no background hit, near-plane crossings, and the camera below the surface. Use bounded
normal-driven refraction; validate distorted samples against foreground depth and image
bounds, reverting to an undistorted valid sample when rejected.

Apply depth-dependent RGB absorption and restrained scattering in linear space with biome
tint. Combine reflected and transmitted light with a consistent energy budget and the W1
blend contract so background color is not counted twice. State the limitation that an opaque
snapshot does not contain other transparent objects.

**Complete when:** shallow water reveals the bed, deeper water attenuates progressively,
foreground silhouettes do not bleed into refraction, and borders/sky/steep views remain
stable. Validate zero thickness, maximum thickness, invalid depth, and refraction-off
fixtures, plus transparent objects both above and below water in the live renderer.

### W6 — Add foam and underwater appearance

Generate subtle shoreline/contact foam from valid water-to-scene proximity and animated
noise. Mask it to appropriate water faces; sky, missing depth, and unrelated foreground
occluders must not generate a foam outline. Avoid presenting this screen-space approximation
as a physical shoreline simulation.

Integrate underwater absorption, fog, and mild distortion with Minecraft's existing fog
and `ScreenEffectRenderer` overlay. Choose and document which system owns each contribution
to avoid applying fog twice. Keep air/water transitions stable and hand/HUD treatment
consistent with the world seam; preserve visibility and biome behavior.

**Complete when:** foam appears at tested shallow contacts without outlining every object;
underwater entry/exit, partial submersion, caves, and looking upward through the surface
pass screenshot checks. Zero foam/distortion settings have defined identity behavior.

### W7 — Expose controls and implement the optional reflection tier

Wire options through `pack.json`, existing settings/UI, and translations as needed. Provide
water enable, wave strength, refraction strength, absorption, foam, underwater distortion,
and reflection quality with bounded defaults. Use the existing uniform/recompile semantics;
disabled graph nodes must still produce defined outputs, or use validated graph rewiring.

Add optional screen-space reflection using stored scene depth/color, bounded steps/distance,
hit-thickness rejection, and edge/distance confidence fading to the W4 reflection fallback.
Start without temporal history. Keep baseline reflection usable independently of SSR.

**Complete when:** saved options survive restart/reload, repeated toggles leave no stale
output, water-off restores the agreed baseline, and failed pack configuration recovers
cleanly. Test SSR misses, off-screen rays, thin geometry, camera movement, and resolution
changes; record each quality tier's cost. If measurements justify deferring SSR, explicitly
revise this task's scope and record that decision rather than reporting SSR implemented.

### W8 — Validate the composed result and select defaults

Build a repeatable water scene using the existing client test/benchmark infrastructure:
shallow shore, deep pool, river flow, waterfall, waterlogged blocks, glass/ice/lava controls,
submerged and above-water entities/particles, and a cave pool. Capture matched water-off/on
views at noon, sunset, night, rain, above/below water, and after resource-pack reload.

Run `./gradlew build` (including existing shader smoke coverage on supported Apple silicon)
and `./gradlew runClient -PmetalLifecycleTest` for lifecycle regression. Add water-specific
GPU fixtures to the smoke suite and a deterministic client water scenario; record its exact
command here when implemented. Verify native/half resolution, odd extents, resize,
fullscreen, world/dimension changes, hand/HUD, and supported transparency modes.

Measure identical warmed scenes with water off, baseline water, and SSR, using the existing
per-pass GPU census and benchmark methodology in [APPLE_SILICON_PERFORMANCE.md](APPLE_SILICON_PERFORMANCE.md).
Record device, resolution, settings, repeats, median/spread, GPU/frame times, and added
texture memory. Choose a stated performance budget after measuring the baseline and before
tuning; passing requires meeting that budget. Do not sum overlapping pass spans as frame time.

**Complete when:** all W1–W7 criteria are satisfied, visual artifacts and performance results
are linked, remaining limitations and chosen defaults are documented, and every tracker row
has verifiable completion evidence. Keep the release gate open for any missing live checks.

## Implementation decisions and handoff notes

W1 owner: append the audited design here before downstream implementation begins.


### W1 initial audit — 2026-09-05 (`/root`)

Branch: `codex/water-effects`, based on `metal-shader-engine`. Existing working-tree
edits to the engine tracker, shadow shader, and shadow smoke were present before this
work. This initial change claims W1 and records source findings; it does not complete W1.

**Verified repository findings**

- `WorldGeometryAdapter.build` rejects every blended pipeline. `materialFor` only emits
  solid, foliage, entity, or emissive; it never emits `WATER`. Terrain vertex inputs are
  exactly Position, Color, UV0, UV2. There is no explicit fluid identifier in that adapter.
  The water roughness branch in `standard/gbuffer.metal` is therefore insufficient.
- `ChunkSectionsToRenderMixin` routes whole render groups through the adapter, retaining
  the original pipelines when substitution is declined. Water cannot be enabled merely
  by allowing blended geometry into the current opaque G-buffer resolve.
- `PreparedFeatureFrameMixin` flushes opaque resolve at `executeTranslucent` HEAD.
  `WorldComposition` records forward terrain/features, Fabulous composition, clouds,
  weather, and outlines before `PACK_POST`; hand, water/fire overlays, spectator effects,
  and HUD follow that seam. Its documentation explicitly says live grading still occurs
  at PRESENT. This is repository evidence, pending an independent mapped call-site audit.
- `MetalShaderFrameExecutor` skips world-owned groups and binds `depth` from world frame
  bindings. That post-world depth is not an established pre-water opaque snapshot.
- Standard's G-buffer channels are transient RGBA8; `post_color` is BGRA8. The existing
  color contract requires a coordinated HDR transition, including forward/Fabulous
  sources and world-only output conversion. W2 remains required.

**Proposed routing and ownership (pending mapped-source verification)**

Choose an in-place forward terrain substitution that preserves Minecraft's translucent
index order, culling, and depth state. Classify each fluid face during mesh construction
from fluid metadata, carrying a flat material identifier to the fragment stage. Preserve
non-water fragments through the compatible forward path. Do not split water into an
independently sorted global pass or infer identity from tint, atlas color, or blend state.
The mesh representation, upload layout, and translucent index reordering must be audited
before choosing an attribute versus a parallel metadata buffer.

Still and flowing water, vertical faces, and fluid faces inside waterlogged blocks need
explicit coverage. Store face orientation and flow independently from biome tint. Resource
reload must rebuild any resource-derived metadata with the mesh generation that uses it;
underwater viewing must retain the original face/culling behavior. These are implementation
requirements, not verified support claims.

`/root` owns W1 and serializes tracker changes. Proposed downstream file responsibility:
mesh construction/upload and `ChunkSectionsToRenderMixin` supply identity; a focused water
module owns snapshots and immutable frame uniforms; `WorldGeometryAdapter` owns forward
substitution; `FrameBindings`/allocator/compiler/executor supply typed resources and
lifetime validation; Standard owns water shading. Exact mapped mesh files remain to be
identified before implementation.

**Proposed resource and blend contract**

- Snapshot resolved opaque scene color into a distinct, stored RGBA16_FLOAT texture and
  opaque depth into a distinct, stored DEPTH32_FLOAT texture before forward transparency.
  Both use actual world attachment extents (including render scaling), one sample, and
  shader-read usage plus the backend's required copy/producer usage. If source sampling
  differs, explicitly resolve first; do not silently assume multisample compatibility.
- Water writes the current forward color/depth attachments according to the original
  pipeline. Snapshots are read-only through all water consumers, never alias output, and
  are retired only after GPU completion. Missing snapshots select vanilla-compatible water.
- Use device depth in [0,1] with the actual raster projection/inverse and clear value;
  `gbuffer.metal` preserves clip Z/W and flips clip Y. Validate the projection
  range, compare direction, and depth clear at mapped/native sites before reconstruction. Compare reconstructed
  surface/background positions in a common view space, not packed G-buffer depth bytes.
- The baseline forward substitution preserves source-alpha coverage blending. For future
  refracted opaque transmission, avoid blending a fully composed background a second time:
  W5 must select an explicit replacement/composition policy and verify transparent overlap
  before enabling refraction. No final refractive blend contract is claimed at this stage.
- Opaque snapshots omit transparent entities, glass, and other translucent surfaces.
  Fabulous uses intermediate targets and requires a verified snapshot/composition seam;
  ordinary transparency ordering alone does not prove Fabulous support.

**Validation and next action**

Read the adapter, composition, executor, terrain/feature mixins, Standard manifest and
G-buffer/resolve sources, and `SHADER_COLOR_CONTRACT.md`. No renderer code changed, so no
build or GPU test is claimed. The mapped source jar named by the color contract was not
found by the initial file inventory of local/project Gradle caches. Locate the configured
Loom cache or generate sources, audit fluid tessellation and sorted upload, then implement
a water identity debug view with glass/ice/slime/lava controls. W1 stays unchecked until
that view has live evidence and the outstanding contracts above are verified.


### W1 mapped audit and diagnostic — 2026-09-05

The source jar was present under the ignored `.gradle/loom-cache/minecraftMaven`
folder; locating it required `rg --files --hidden --no-ignore`. Audit committed in
`be07df6`; the following findings supersede provisional notes above.

- [x] Locate and read mapped fluid tessellation and shared-batch sorting.
- [x] Implement an opt-in, fluid-state-based mesh diagnostic and paired capture scene.
- [x] Visually verify paired captures and close the final W1 routing/blend design (evidence below).

`SectionCompiler.compile` calls `FluidRenderer.tesselate` with each block's `FluidState`
before emitting its block model. This includes waterlogged blocks. `FluidRenderer` selects
its resource-loaded model and layer, then emits top, bottom, side, overlay, and reverse
faces through one `Output` consumer. Top UVs encode flow rotation; still/flow/overlay
materials come from `FluidStateModelSet`. Resource texture coordinates are not identity.
`BufferBuilder`'s BLOCK layout is 28 bytes: position at 0, color at 12, UV0 at 16,
light at 24. It has no material/flow attribute. `MeshData.sortQuads` sorts indices while
leaving vertices fixed; `ChunkSectionsToRender` also reverses translucent draw lists.

The diagnostic wraps only water-tagged fluid output at the section compiler call. It
changes vertex RGB to magenta, retaining alpha, UVs, light, topology and the existing
sort path. Disabled and non-water calls return the original output. This is an identity
probe, not production material transport: tint remains visible through atlas/light/fog
modulation, and no later shader should classify magenta pixels. A flag change requires
`levelExtractor.allChanged()` to rebuild terrain. The fixture performs that rebuild for
baseline, diagnostic, and restored captures and restores the prior flag/pack afterward.

For production choose an additional per-vertex material/flow stream bound beside the
original terrain vertices, indexed by the same original vertex index/base vertex. Extend
section compile results, vertex upload/storage, draw bindings, and retirement together;
initialize non-fluid vertices explicitly to non-water. Never index metadata by sorted
primitive number. This keeps vanilla BLOCK bytes and sorted indices intact but requires
an audited multi-draw binding change in W3. The diagnostic does not implement that stream.

Mapped composition order: clear main depth to **0**, sky, opaque terrain, solid features,
copy main depth to Fabulous translucent/item targets, translucent features, feature
outlines, translucent terrain, after-terrain translucent features; clouds and weather
then precede the Fabulous composition chain. Always-on-top follows the chain. World
rendering finishes before hand depth clear/render and screen overlays in `GameRenderer`.
Capture opaque color/depth after resolving solid features and before any forward features;
the existing post-world depth export is too late. Fabulous's copied depth is not a
separate immutable water snapshot. Its intermediate targets are currently RGBA8.

World depth uses reverse Z: clear 0, default `GREATER_THAN_OR_EQUAL` with depth writes;
Standard preserves projection Z/W and flips Y. Preserve the terrain pipeline's actual
state when substituting. `BlendFunction.TRANSLUCENT` uses source-alpha/one-minus-source-alpha
for RGB and one/one-minus-source-alpha for alpha. Diagnostic retains this convention.
Baseline water shading retains straight-alpha coverage and outputs only the shaded
surface contribution. For W5, choose opaque-snapshot replacement at covered water
fragments: output the complete reflected/transmitted RGB with alpha 1 through the
existing blend state, preserving the original texture-alpha discard/coverage boundary.
This avoids counting background twice and keeps depth/sort behavior. It deliberately
cannot reproduce transparent objects behind water from an opaque snapshot. Such objects
may disappear where water replaces their earlier contribution; above-water geometry
still follows vanilla sorting/depth. Document this limitation in W5 live comparisons.
Refraction-off retains baseline coverage blending. Fabulous refraction stays on that
baseline fallback until W3 verifies how alpha-1 water and its depth participate in the
transparency chain. This is a chosen supported fallback, not a Fabulous refraction claim.

Validation command: `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`.
The scene contains a water pool/falling flow, a waterlogged slab, glass, ice, slime and
lava on an elevated platform. Captures are named `metalcraft-water-identity-baseline`,
`metalcraft-water-identity-water`, and `metalcraft-water-identity-restored`.


### W1 completion evidence — 2026-09-05

W1 is complete as an identity/design milestone. W3 production metadata transport,
snapshots and forward shading remain unimplemented; the RGB probe is debug-only and
disabled by default. Final mapped findings and the replacement/fallback blend policy
above supersede the initial audit's provisional contracts.

Implementation files: `SectionCompilerWaterMixin.java`, `WaterIdentityDebug.java`,
`MetalWaterIdentityGameTest.java`, the lifecycle dispatcher, client mixin manifest and
`build.gradle`. Owner `/root`; all W1 changes share this tracker update.

Validation:

- `./gradlew build`: passed, including Metal shader translation/GPU smoke coverage.
  An earlier run encountered an unrelated shadow fixture syntax error while that work
  was changing in the shared workspace; it was corrected outside this water change.
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed on
  the Metal backend; final fixture run completed in 39 seconds. Visually inspected all
  three 854×480 captures: pool, falling/flowing water and waterlogged-slab water become
  magenta; glass, ice, slime and lava retain their appearance; debug-off restores water.
- The earlier invocation with an empty `-PmetalWaterIdentityTest` ran the ordinary
  lifecycle suite instead and passed. Use the explicit `=true` command above for water.
- `git diff --check`: passed. No performance claim or W2–W8 validation is made.

Local visual artifacts (generated outputs, not committed):

- [Baseline](../run/screenshots/0000_metalcraft-water-identity-baseline.png)
- [Water identity](../run/screenshots/0001_metalcraft-water-identity-water.png)
- [Restored](../run/screenshots/0002_metalcraft-water-identity-restored.png)

Logs: `/tmp/metalcraft-water-build.log`, `/tmp/metalcraft-water-client.log`.
Next task is W2: coordinate the existing PR 7b linear HDR transition through world and
forward targets and move tone/output conversion to the world seam. Do not start W3
production shading from the legacy 8-bit path. No water performance budget is due at W1.


### W2 progress — live world grading seam (2026-09-05, `/root`)

Continuation claimed by `/root` on 2026-09-05: establish explicit sRGB layer
interpretation and rerun the standard-world water fixture. This is a display contract
prerequisite; linear HDR composition and physical display verification remain open.

- [x] Move pack execution to the end of the world graph, before hand-depth clear.
- [x] Store world depth separately before later hand/HUD writes.
- [x] Remove present-time pack execution, leaving one world grade and a presentation copy.
- [ ] Convert world/forward/Fabulous targets and participating shader fog/blend math to
  coherent linear RGBA16_FLOAT and validate values above 1 through live composition.
- [ ] Establish and verify display color space and HDR transparent overlap.

`GameRendererWorldGradeMixin` injects before the hand-depth clear in `renderLevel`.
`MetalWorldGrade` owns a private stored DEPTH32_FLOAT snapshot at world attachment
resolution and a format-matched, unblended copy pipeline. It encodes on the existing
world command queue after deferred resolve, grades into separate `post_color`, then
copies those pixels into main color without sampling the destination. Main depth is
not attached to that copy. Native command-buffer retention protects released resources;
there is no frame-loop CPU readback, wait or new queue. Resize reallocates the snapshot.
Projection metadata is still absent at this seam; effects requiring it must not assume
an identity matrix. This snapshot is after transparency, not W3's opaque snapshot.

Presentation now copies main color without executing the pack again. Menus/HUD/hand
are excluded from world grading. Standard grade UVs explicitly account for Metal's
upper-left framebuffer origin; the copy uses integer pixel coordinates. Color math
and world storage remain the legacy 8-bit path. No linear transfer is added prematurely.

The water fixture now also halves exposure and displays a white title. Its image
assertion checks an unobstructed sky pixel halves once (two-byte tolerance) and at least
100 central HUD pixels remain white. Standard exposure/tonemap/invert/debug settings
are controlled for the test and restored afterward. Captures include
[half exposure with white HUD](../run/screenshots/0003_metalcraft-world-grade-half-exposure-hud.png).
This proves the live world/HUD seam, not a physical display color-space contract.


W2 seam validation (2026-09-05): `./gradlew build` passed, including GPU shader smoke;
`./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true` passed with the
numeric single-exposure/HUD assertion (37 seconds), and `./gradlew runClient
-PmetalLifecycleTest -PmetalShaderLifecycleTest=true` passed (34 seconds). The lifecycle
run exposed a closed grading-resource cache reused after reload; clearing that owner on
pipeline-cache reset fixed it, and the complete lifecycle run then passed. Logs are
`/tmp/water-w2-build.log`, `/tmp/water-w2-client.log`, and `/tmp/water-w2-lifecycle.log`.
Visually inspected the half-exposure scene with white HUD. W2 remains unchecked; next is
coordinated linear world/forward target and shader conversion, not a post-only decoder.


### W2 continuation — explicit presentation contract and standard-world correction

2026-09-05, owner `/root`:

- [x] Set `MCMetalSurface`'s `CAMetalLayer.colorspace` to `kCGColorSpaceSRGB`.
  Keep BGRA8Unorm and the presentation copy unchanged: these bytes already contain
  encoded world/hand/HUD color. The future HDR grade must encode once before this seam.
- [x] Correct `MetalWaterIdentityGameTest` to explicitly select `WorldPresets.NORMAL`.
  Bytecode audit found Fabric's default consistent settings select FLAT. Earlier W1/W2
  runs therefore did not satisfy the standard-world requirement. The new standard-world
  run supersedes those visual checks; W1 identity/design acceptance still passes.
- [x] `./gradlew build` passed after both changes (including shader GPU smoke).
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true` passed
  in 42 seconds with the explicit NORMAL preset and the numeric exposure/HUD assertion.
  Visually inspected the refreshed baseline, water diagnostic, restored, and half-exposure
  HUD captures at the same artifact paths above. Water-only identity and restoration pass;
  glass, ice, slime and lava remain unchanged. The fixture platform is elevated in a normal
  generated world; the captures do not constitute a natural shoreline comparison.
- [ ] Actual compositor/display verification with reference patches remains open:
  framebuffer screenshots do not measure physical display output.
- [ ] Coordinated RGBA16_FLOAT world/forward/Fabulous targets, linear fog/blending and
  live HDR readback remain the next implementation task. W2 remains in progress.

Logs: `/tmp/water-w2-display-build.log`,
`/tmp/water-w2-display-standard-client.log`. The first continuation client run used the
old flat default and is not the standard-world validation evidence.


W2 next increment claimed by `/root` (2026-09-05): add the explicit linear-input
grade variant and an independent GPU opaque/forward/fog/output composition fixture.
The live graph must continue using legacy input until all participating producers
can switch together. The following validation passed; W2 remains in progress.

- [x] Add `MC_SCENE_LINEAR_HDR` to Standard's grade shader. When explicitly compiled
  for linear input it applies exposure/optional ACES, then sRGB output transfer once.
  Invert operates on encoded display color in this variant. Scene debug encodes without
  exposure/tonemap. With the define absent, legacy grading behavior is unchanged.
- [x] Add `HdrCompositionSmoke` to the normal shader smoke suite. Three separate Metal
  encoders store/load an RGBA16_FLOAT opaque seed and two forward draws. Independent
  CPU references check eight pixels containing HDR RGB, linear fog and alpha coverage
  0/0.25/0.5/1, including the distinct RGB/alpha blend factors used by transparency.
  The actual Standard grade shader is then checked in 12 exposure/tonemap/invert
  combinations into BGRA8_UNORM (two-byte tolerance). These are synthetic GPU draws,
  not Minecraft forward shader routing or Fabulous validation.
- [x] `./gradlew build` passed including the new GPU fixture and existing legacy grade
  tests. Log: `/tmp/water-w2-hdr-build.log`.
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true` passed
  in 41 seconds with the explicit standard-world preset and exposure/HUD assertions.
  Log: `/tmp/water-w2-hdr-client.log`. Refreshed half-exposure/HUD capture visually
  inspected at the artifact path above. This run still uses legacy world color.
- [ ] Wire the live HDR producers and host selection of the grade variant together.
  The host does not define `MC_SCENE_LINEAR_HDR` yet. Never enable it just because
  an attachment is floating point: the producer's color encoding must be known.

Next implementation boundary: `GameRenderer.mainRenderTarget` owns the legacy main
attachment and is reused for hand/HUD; a world-only HDR attachment must hand off to it
before the existing grade seam. `MetalGpuDevice.compilePipeline` currently creates
fixed-format depth/depthless pipeline variants; world forward variants need both
linear fragment semantics and RGBA16_FLOAT targets. Coordinate these with the
Standard G-buffer/resolve and Fabulous intermediates before activating HDR grading.
No live HDR, physical display or performance acceptance is claimed by this increment.


### W2 target and pipeline increment — claimed 2026-09-05 by `/root`

- [x] W2a: own distinct stored RGBA16_FLOAT world color / D32 depth, support resize
  and retirement, and grade explicitly linear world input into the existing encoded
  output attachment. Test the host executor handoff, allocation lifetime and alias checks.
- [x] W2b: select/cache attachment-format variants of Blaze3D pipelines, preserving
  depth state, blend factors, write masks, MRT channels and primitive topology. Test
  actual backend draws into HDR and legacy targets plus cache release.

Both host-mechanics steps are implemented and pass GPU smoke. They establish host mechanics; vanilla forward shader
linearization and activation of the live world route are still required before W2
can be marked complete. No W3 task is claimed while W2 remains unfinished.


W2a/W2b evidence (2026-09-05, `/root`):

- `MetalWorldTargets` owns stored single-sample RGBA16_FLOAT color and D32_FLOAT
  depth at caller-supplied world extents, with render/sample/copy usage. Same-size calls
  reuse attachments; replacement allocation is transactional; resize/reload/device close
  release views and textures. Commands retain native resources until GPU completion.
- `FrameBindings.ColorEncoding` defaults existing callers to LEGACY_ENCODED. Explicit
  LINEAR_SRGB requires HDR storage at the world seam. Standard's executor owns both
  grade variants; other packs reject linear input. `gradeLinearWorld` grades separate
  world targets into a distinct UNORM output; mismatched extents/aliasing are rejected.
- `MetalCompiledRenderPipeline` caches depth/depthless color-slot-zero format variants.
  Descriptor copies retain blend factors, masks, auxiliary MRT formats and primitive
  topology. Failed pair construction closes the first PSO; cache clear closes variants.
- `WorldHdrTargetsSmoke` exercises actual Blaze3D backend draws with synthetic linear
  producers, both depth/depthless passes, alpha blending and host grade output readback.
  HDR red remains 2; encoded output matches independent values. Sizes 9x3, 5x3, 9x3
  cover odd extents, reuse and resize. Alias rejection and reload retirement pass.
  This test does not execute Minecraft forward shaders or Fabulous composition.
- `./gradlew build` passed including new HDR host coverage, prior composition/transfer
  tests and legacy shader smoke. Log: `/tmp/water-w2-target-build.log`.
- Standard-world water fixture passed in 41 seconds with exposure/HUD assertions.
  Command: `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`.
  Log: `/tmp/water-w2-target-client.log`; screenshots use the W1/W2 artifact paths above.

The general lifecycle fixture now explicitly selects NORMAL too (its prior default was
flat). `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true` passed
in 36 seconds; log `/tmp/water-w2-target-lifecycle.log`. The half-exposure/HUD capture
was visually inspected. `git diff --check` passed. W2 remains unchecked: `prepareLinearWorldTargets`
and `gradeLinearWorld` are tested host APIs, not yet called by the live world graph.
Next: convert actual opaque/forward shader color/fog producers and Fabulous intermediates,
then switch world rendering and its grade handoff together. No new water visual effect,
live HDR acceptance or physical display measurement is claimed by these two steps.


### W2 opaque producer increment — claimed 2026-09-05 by `/root`

- [x] W2c: add an explicitly selected linear Standard opaque seed, chunk fade, fog and
  deferred reconstruction variant, preserving legacy defaults and lightmap compatibility.
  Validate shared production color math on GPU before handing off.
- Live forward/Fabulous conversion and coordinated activation remain required; this
  increment does not claim live HDR acceptance.

W2c evidence (2026-09-05, `/root`): Standard `gbuffer.metal` converts the completed
unfogged compatibility seed through `mc_scene_seed` before chunk fading/fog when
`MC_SCENE_LINEAR_HDR` is defined. `shared/lighting.metal` uses the same decoded fog
color for fading, fog application, unfogging and deferred recomposition. Alpha,
cutout ordering, encoded albedo metadata and the default legacy path are preserved.
The lightmap/cardinal/overlay seed remains Minecraft's artistic compatibility result;
this does not claim physical illumination or independently linear overlay mixing.

`HdrCompositionSmoke` now executes production shared functions in both variants and
compares HDR readback to independent CPU references: seed RGB on both transfer branches
and above 1, chunk visibility with non-unit fog alpha, partial fog, unfog round trip,
and fully fogged fallback. This is shared-function GPU coverage, not a live linear
G-buffer or forward routing test.

- `./gradlew build`: passed, including new and existing GPU smoke coverage;
  `/tmp/water-w2-opaque-build.log`.
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed
  in 40 seconds using the explicit NORMAL preset and numeric exposure/HUD assertions;
  `/tmp/water-w2-opaque-client.log`. Refreshed
  [half-exposure HUD capture](../run/screenshots/0003_metalcraft-world-grade-half-exposure-hud.png)
  visually inspected: world darkens and HUD remains white.
- `git diff --check`: passed.

W2 remains in progress. Next: convert actual forward GLSL producers and Fabulous
intermediates, verify the linear G-buffer variant through full geometry draws, then
activate the world target/grade handoff coherently. Display validation remains open.


### W2 forward and geometry increment — claimed 2026-09-05 by `/root`

- [x] W2d: audit and implement explicit, fail-closed linear variants of supported
  forward GLSL producers, with numeric GPU coverage. Owner `/root`; read-only forward
  and Fabulous audits delegated to `/root/forward_audit` and `/root/fabulous_audit`.
- [x] W2e: exercise the actual Standard linear geometry shaders with HDR output.
  Owner `/root/opaque_geometry`; coordinator serializes tracker updates.
- Live HDR activation remains gated on complete producer and intermediate coverage.

W2e evidence (2026-09-06, `/root/opaque_geometry`): `StandardGeometryHdrSmoke`
executes 30 actual terrain/block/entity vertex and fragment draws across legacy and
linear variants. Stored HDR scene and three metadata MRT readbacks check seed transfer,
partial fog, terrain chunk fade, alpha cutout (including fade-before-cutout), and
encoded albedo/material preservation. `./gradlew shaderTranslationSmoke` passed;
`/tmp/water-w2-geometry-smoke.log`. This closes the bounded offscreen geometry check;
entity overlay/cardinal combinations, live route and display remain unvalidated.

Parallel mapped-source audit (`/root/fabulous_audit`, `/root/forward_audit`):

- Scope routing around the complete `LevelRenderer.render` call from `GameRenderer`.
  The frame graph imports main target early but also fetches it in a deferred clear;
  redirecting only the initial import is insufficient. `SkyRenderer` caches the original
  RenderTarget, so an accessor-only override on GameRenderer also misses sky draws.
- `LevelRenderer.render` creates one RGBA8 descriptor for five Fabulous layers;
  `PostChain.addToFrame` independently creates the transparency chain's internal final
  target. Both must become HDR only for the explicitly active linear world chain.
  `transparency.fsh` consumes premultiplied layer RGB: preserve its composition math,
  and do not decode its sampled linear intermediate textures again. Its blit stays linear.
- Decode the world clear fog RGB too. Keep encoded outline/postprocessing targets and
  subsequent hand/HUD outside that contract. Restore world routing exception-safely.
- Deferred pool reuse compares format; persistent PostChain target reuse checks only
  size and would need format-aware retirement if generalized beyond vanilla's nonpersistent
  transparency final target.
- Remaining forward programs include sky, stars, position/text variants, glint, lightning,
  world border and other effects. Glint/lightning need conversion before attenuation;
  generic final-output decoding is not an acceptable replacement. Unsupported source
  replacements must keep the entire world on legacy rendering, not mix spaces.

W2d implementation and GPU evidence (2026-09-06, `/root`, `/root/forward_audit`):

`LinearWorldShaders` verifies expanded Minecraft 26.2 vertex/fragment fingerprints for
terrain, block, entity, particle and clouds before adapting them. It decodes completed
compatibility seeds before fog, terrain chunk fade before fog, and cloud RGB before
alpha-only distance attenuation. Alpha and lightmap compatibility semantics remain intact.
Fingerprints preserve token spacing and preprocessor line boundaries; a `++i` to `+ +i`
source mutation is rejected. Unknown/replaced sources and missing/failed programs throw.
`MetalGpuDevice.precompileLinearWorldPipeline` owns a distinct semantic cache, retired
on reload/close and invalidated when registering a native replacement. Existing draw
selection remains legacy; attachment-format variants do not imply linear semantics.

`LinearWorldShadersSmoke` compiles 30 actual vanilla static pipeline combinations in
both modes and checks source rejection, cache isolation/reuse and retirement. Its
actual transformed particle fragment GPU draws check HDR seed, RGB transfer knees,
zero/partial/full fog, alpha discard, and straight-alpha overlap against independent
CPU references. `./gradlew shaderTranslationSmoke` passed;
`/tmp/water-w2-forward-smoke.log`. `./gradlew build` also passed;
`/tmp/water-w2-forward-build.log`.

These completed substeps cover only the explicitly supported forward programs. W2
remains in progress: remaining producers, Fabulous/sky/clear routing and coordinated
activation are still required. No live HDR or physical display acceptance is claimed.

Final W2d/W2e regression (2026-09-06):

- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true` passed in
  40 seconds with explicit NORMAL world generation and numeric exposure/HUD assertions;
  `/tmp/water-w2-forward-client.log`. Refreshed
  [half-exposure HUD capture](../run/screenshots/0003_metalcraft-world-grade-half-exposure-hud.png)
  visually inspected; the world darkens and HUD remains white. This is a legacy-route
  regression check, not live HDR validation.
- `git diff --check` passed.


### W2 remaining forward producers — claimed 2026-09-06 by `/root`

- [x] W2f: extend verified forward variants to sky, stars, position/color/texture,
  world border, glint and lightning producers,
  preserving attenuation/fog ordering and testing actual GLSL on GPU. Owner `/root`;
  `/root/forward_checks` owns the smoke fixture.
- [x] W2g: audit remaining forward producers, sky routing and blend prerequisites.
  Owner `/root/route_audit`; coordinator owns all tracker edits.

W2 remains in progress. Live activation stays gated on complete producer coverage.

W2g evidence (2026-09-06, `/root/route_audit`): audited actual 26.2 GLSL and mapped
RenderPipelines/SkyRenderer bytecode (`/tmp/w2g-pipelines.txt`, `/tmp/w2g-sky.txt`).
Sky uses sky, position_tex_color (End), position_color (sunrise/sunset), stars, and
position_tex (celestial) programs; its cached RenderTarget still needs explicit routing.
Glint uses SRC_COLOR/ONE RGB and ZERO/ONE alpha; lightning uses SRC_ALPHA/ONE,
including alpha. Stars/celestial/world border use SRC_ALPHA/ONE RGB and ONE/ZERO alpha.
These blend policies must survive conversion. Remaining unsupported producers after
W2f include beacon beam, crumbling, entity shadow, lines, leash, End portal, text and
text background, plus debug_point vertex support. Crumbling requires its own
DST_COLOR/SRC_COLOR overlap check. This read-only audit completes W2g only; it does
not validate live sky/Fabulous routing or complete W2.

W2f implementation and GPU evidence (2026-09-06, `/root`, `/root/forward_checks`):
`LinearWorldShaders` adds fingerprint-verified variants for nine program families,
decoding completed compatibility RGB before fog/attenuation and preserving alpha and
discard order. Nonfog programs do not require a fog helper. The live route remains
legacy; arbitrary replacement sources still fail closed.

`LinearWorldShadersSmoke` compiles 51 actual vanilla pipeline combinations in both
modes and adds 216 actual fragment draws with independent CPU references in stored
RGBA16_FLOAT targets. Checks include HDR RGB, transfer knees, zero/partial/full fog,
four alpha values, sky's FogSkyEnd behavior, glint RGB fade with SRC_COLOR/ONE overlap
and preserved destination alpha, lightning RGBA fade with additive SRC_ALPHA/ONE,
and stars/world-border overlay blending. These controlled-varying fragment fixtures
do not claim full-scene rendering or physical display validation.

- [x] `./gradlew shaderTranslationSmoke` passed; `/tmp/water-w2f-forward-smoke.log`.
- [x] `./gradlew build` passed; `/tmp/water-w2f-build.log`.
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`
  passed in 40 seconds with explicit NORMAL world generation and numeric exposure/HUD
  assertions; `/tmp/water-w2f-client.log`. Visually inspected refreshed water identity
  and half-exposure/HUD captures at the W1/W2 screenshot paths: water-only magenta
  coverage remains correct, and the darkened world retains a white HUD. This is a
  legacy-route regression check, not live HDR validation.
- [x] `git diff --check` passed.

Next: remaining beam/crumbling/shadow/line/leash/portal/text producers, then coordinated
world/sky/clear/Fabulous routing and live HDR readback. W2 remains unchecked.


### W2 additional forward producers — claimed 2026-09-06 by `/root`

- [x] W2h: add verified beam, crumbling, entity shadow, lines, leash, portal, item and text
  variants, plus debug_point vertex support. Owner `/root`; `/root/forward_checks`
  owns smoke coverage and `/root/route_audit` audits the remaining routing boundary.
  Coordinator serializes tracker edits. W2 remains in progress; activation is gated.

W2h routing audit (2026-09-06, `/root/route_audit`): item shaders are actual world
producers (`ItemFeatureRenderer` selects material item RenderTypes; item translucent
uses ITEM_ENTITY_TARGET), so item coverage is included in W2h. WATER_MASK writes no
color and needs verified depth-only handling. Outline generation stays encoded, but
ENTITY_OUTLINE_BLIT needs an explicit encoded-to-linear composition boundary; its
blit_screen shader is also used by TRACY_BLIT, so shader ID alone cannot determine
its encoding. GUI, panorama, lightmap and atlas maintenance retain their contracts.

The native G-buffer stand-ins and deferred resolve still lack live linear selection.
`precompileLinearWorldPipeline` intentionally rejects native replacements; activation
must supply an explicit linear native contract, not bypass this guard. Next coordinated
steps are native G-buffer/resolve selection, full world plus cached sky target routing,
linear clear color, Fabulous layers/composition/copies, outline composition, preflight
before frame writes, and one grade handoff before hand/HUD. Source evidence:
`/tmp/w2h-types.txt`, `/tmp/w2h-item.txt`, `/tmp/w2g-pipelines.txt`, `/tmp/w2g-sky.txt`.

W2h implementation and GPU evidence (2026-09-06, `/root`, `/root/forward_checks`):
`LinearWorldShaders` verifies and adapts beam, crumbling, entity shadow, lines, leash,
portal, item, text and text-background programs, plus the debug_point vertex shader.
Completed artistic portal layers and item overlay/lightmap seeds decode before fog.
Text converts each output branch while retaining its distinct discard/modulator order.
These source-verified variants remain opt-in; encoded auxiliary targets and live
world routing are not changed by this increment.

`LinearWorldShadersSmoke` now compiles 75 actual vanilla pipeline combinations in
both modes. Another 264 fragment draws check crumbling DST_COLOR/SRC_COLOR RGB
overlap with ONE/ZERO alpha; flat leash input; a completed two-layer portal seed
and fog; six text define combinations (default/GUI/see-through, normal/grayscale);
and two text-background variants. Low modulator alpha distinguishes discard before
versus after modulation. Prior 216 simple-effect draws and particle checks remain.
Beam, entity-shadow, lines and item receive compilation coverage here, not additional
full-mesh numeric fixtures. This closes the bounded producer increment only.

- [x] `./gradlew shaderTranslationSmoke` passed; `/tmp/water-w2h-forward-smoke.log`.
- [x] `./gradlew build` passed; `/tmp/water-w2h-build.log`.
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`
  passed in 40 seconds using explicit NORMAL world generation and numeric exposure/HUD
  assertions; `/tmp/water-w2h-client.log`. Visually inspected refreshed water identity
  and half-exposure/HUD screenshots at the existing W1/W2 artifact paths. Water-only
  coverage and the white HUD over a darkened world remain correct. This is a legacy
  route regression, not live HDR acceptance.
- [x] `git diff --check` passed.

W2 remains unchecked. Next implementation boundary is the native linear G-buffer/resolve
selection and coordinated target/encoding route recorded in the audit above.


### W2 native linear selection — claimed 2026-09-06 by `/root`

- [x] W2i: add explicit matching linear native G-buffer/resolve selection with format
  guards and cache retirement. Owner `/root`; status done (evidence below).
- [x] W2j: declare native pipeline color semantics and test linear-cache rejection,
  selection and retirement. Owner `/root/native_contract`; status done (evidence below).
- [x] W2k: GPU-check production adapter/resolve encoding selection, HDR storage and
  legacy restoration. Owner `/root/adapter_checks`; status done (evidence below).
- [x] W2l: audit concrete world/sky/Fabulous activation hooks and preflight gaps.
  Owner `/root/activation_audit`; status done (evidence below).

Coordinator serializes tracker changes. Live activation and display acceptance remain
open; these substeps must not mark W2 complete without its full evidence.

W2i–W2k evidence (2026-09-06, `/root`, `/root/native_contract`, `/root/adapter_checks`):
`WorldGeometryAdapter.beginFrame(ColorEncoding)` selects Standard geometry and merged
resolve together, requires RGBA16_FLOAT for explicit linear scenes, flushes pending
resolve before switching, and retires native stand-ins and resolve pipelines. The
no-argument live entry remains legacy. Compilation/encoding failures in linear mode
throw; unsupported forward producers still require the future whole-world preflight.
`MetalGpuDevice.NativeProgram` carries explicit encoding, with the old constructor
retaining legacy semantics. Native linear-cache admission requires LINEAR_SRGB;
replacement, forgetting and reload retire cached programs. Debug resolve outputs decode
encoded metadata colors for the later linear world grade; metadata attachments stay encoded.

GPU integration exposed a pre-existing flag-value error: ShaderPassCompiler emits
MC_SCENE_LINEAR_HDR=0 for legacy sources, while shared/lighting.metal used #ifdef.
Changed those guards to #if so zero actually selects legacy seed/fog math. Adapter
selection replaces that generated zero declaration with one rather than adding a
conflicting definition. This correction is included in the standard-world regression.

`NativeColorContractSmoke` checks native legacy rejection, linear admission/cache reuse,
replacement/forget retirement, and failed-compilation recovery. The production adapter
fixture checks a pending legacy albedo resolve is encoded before mode-switch retirement,
actual native terrain stand-in linear compilation, HDR seed RGB 2 surviving resolve,
linear albedo-debug numeric transfer, RGBA8 rejection, and legacy-on-float semantics
and debug restoration. Existing 30 full geometry GPU draws and forward fixtures pass.
These checks do not constitute live linear routing or new water surface effects.

- [x] `./gradlew build`: passed, including Metal GPU smoke; `/tmp/water-w2i-build.log`.
  The final run includes strengthened pending-resolve and legacy-restoration assertions.
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`:
  passed in 40 seconds using the explicit NORMAL preset; `/tmp/water-w2i-client.log`.
  Numeric exposure/HUD checks passed. Visually inspected refreshed
  [identity capture](../run/screenshots/0001_metalcraft-water-identity-water.png) and
  [half-exposure/HUD capture](../run/screenshots/0003_metalcraft-world-grade-half-exposure-hud.png):
  water-only magenta coverage and white HUD over darkened world remain correct.
- [x] `git diff --check`: passed.

W2l evidence (2026-09-06, `/root/activation_audit`, verified by `/root`): saved audit at
`/tmp/water-w2l-activation-audit.md`; coordinator checked the mapped source jar directly.
Concrete activation boundaries: GameRenderer.renderLevel calls LevelRenderer.render at
line 562, before hand-depth clear at 568; cached SkyRenderer.renderTarget is assigned at
76; LevelRenderer creates the five RGBA8 Fabulous targets at 181–190; PostChain.addToFrame
creates RGBA8 internal descriptors at 133–135 and builds dynamic pipeline objects at
97. Static pipeline enumeration alone cannot preflight these post pipelines. The final
entity-outline blit is later, in GameRenderer.render at 425, after renderLevel and the
current grade seam. This refines earlier broad outline-order notes: outline production
is inside the world graph, but final outline composition is outside the current seam.

Next: implement a scoped, exception-safe whole-world session with explicit main/sky
attachment routing, linear clear/copy handling, Fabulous target promotion and verified
linear-preserving post shaders, and an explicit outline composition policy. Keep outline
intermediate color encoded unless all outline producers and filters receive a coordinated
contract; widening them alone is not sufficient. A prior-frame pipeline census is not a
complete fail-closed preflight for unseen dynamic pipelines; atomic activation remains
an unresolved requirement. No activation code or physical display acceptance is claimed.
W2 stays unchecked; W3 remains gated on W2. Sol agents hit their usage limit after saving
work; coordinator corrected the fixture, completed validation, and serialized this tracker.


### W2 verified post composition — claimed 2026-09-06 by `/root`

- [x] W2m: implement explicit source-verified linear-preserving Fabulous transparency
  and copy pipeline contracts. Owner `/root/post_contract`; status done (evidence below).
- [x] W2n: add actual post shader GPU fixtures for HDR preservation and layer ordering.
  Owner `/root/post_checks`; status done (evidence below).
- [x] W2o: resolve atomic activation/preflight design against current frame execution.
  Owner `grok`; status done (evidence below).

Coordinator serializes this tracker. W2 remains open and W3 gated; these preparation
steps do not claim live activation or physical display validation.

- [x] W2p: validate the actual Fabulous post-chain configuration before future
  activation, rejecting changed inputs, targets and copy modulation. Owner `/root`;
  status done (evidence below). Source verification alone does not establish texture semantics.


W2m/W2n/W2p evidence (2026-09-06, `/root`, `/root/post_contract`, `/root/post_checks`):
`LinearWorldPostShaders` and `MetalGpuDevice.precompileLinearWorldPostPipeline` add
explicit Fabulous composition and linear copy semantics, fingerprinting the actual vanilla
sources without inserting a second RGB decoder. The separate cache verifies current
sources even on cache hits, rejects missing/replaced/mismatched/native programs, and
retires alongside the existing caches. This API remains opt-in.

`LinearWorldTransparencyConfig` separately validates the loaded vanilla two-pass graph: scene
and depth input identities, nonpersistent full-size final target, pass order/output and
identity copy modulation. It deliberately rejects unsupported resource replacements;
shader approval alone cannot establish that a sampled texture contains linear scene color.
The checker does not itself activate or promote any targets.

GPU evidence: actual packaged screenquad/transparency/blit run against stored RGBA16_FLOAT
textures. Independent CPU references check unsorted depth layers, premultiplied blending,
alpha-zero exclusion with nonzero RGB, HDR red 1.75 surviving composition, and modulated
copy RGB/alpha without another transfer. Sources changed on an existing cache identity
and missing sources after legacy compilation are rejected; reload closes compiled variants.
Eight mutations of the actual vanilla JSON graph are rejected. Integration fixed fixture
API/uniform-name errors and its native sampler offset: inherited Globals uniforms occupy
binding slots, so the fixture now derives offsets from the compiled layout.

- [x] `./gradlew build`: passed including all Metal GPU smoke; `/tmp/water-w2m-build.log`.
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`:
  passed in 40 seconds using the explicit NORMAL preset; `/tmp/water-w2m-client.log`.
  Numeric exposure/HUD assertions passed. Visually inspected refreshed
  [water identity](../run/screenshots/0001_metalcraft-water-identity-water.png) and
  [half-exposure/HUD](../run/screenshots/0003_metalcraft-world-grade-half-exposure-hud.png)
  captures: magenta water coverage excludes glass/ice/slime/lava; darkened world retains
  white HUD. This is a legacy-route regression, not live HDR acceptance.
- [x] Final `git diff --check`: passed.

W2o partial progress (2026-09-06): audit saved at `/tmp/water-w2o-activation.md`.
Confirmed that an unseen pipeline can arrive after earlier HDR draws have been encoded;
a prior-frame census cannot prove atomic readiness, and replaying LevelRenderer is unsafe.
The audit proposes suppressing unsupported draws for one frame and forcing next-frame
legacy. This is a possible degraded policy, **not** proof of the existing whole-frame
fallback acceptance criterion; no such suppression or activation is implemented here.
Next: establish a complete eligibility boundary or explicitly revise and validate the
recovery policy before enabling scoped attachment routing. Reload-correct ShaderSource
selection, actual dynamic PostChain pipeline identities, target promotion and session
cleanup remain required. W2o stays unchecked.

Outline policy is now explicit in `WorldComposition` and `SHADER_COLOR_CONTRACT.md`:
keep generation/filtering encoded, and preserve the final outline overlay at mapped
GameRenderer.render:425 after grade/hand/screen effects. Do not promote outline
intermediates merely because generation runs inside the world graph. This resolves the
outline scope choice without claiming any new live rendering. W2 remains unchecked;
W3 stays gated pending coordinated HDR routing and live/display acceptance.


### W2 atomic session and recovery — claimed 2026-09-06 by `grok`

- [x] W2o: implement the fail-closed HDR session, identity routing, generation guards
  and validated recovery policy from the activation audit. Owner `grok`; status done
  (evidence below). Live GameRenderer wrapping is a later increment.

W2o evidence (2026-09-06, `grok`): `MetalLinearWorldSession` owns one HDR frame token
with captured main color/depth identities, HDR views, shader/native generations and the
reload ShaderSource. `MetalGpuDevice.beginLinearWorld` prefights known forward/post
pipelines, installs the token only on success, and returns null for the one forced-legacy
frame after a poisoned session. Nested sessions are rejected. HDR-owned passes in
`MetalRenderPassBackend.setPipeline` call `linearPipelineFor` only: unseen supported
pipelines compile synchronously; unsupported pipelines encode zero draws, poison the
session, still allow `gradeLinearWorld`, and force the next complete frame through
legacy targets. Native/shader generation changes poison the open session. Clears, copies
and render passes translate only the captured main color/depth objects; same-size
unrelated targets are untouched. `setReloadShaderSource` and
`registerLinearWorldPostContract` exist for later mixins; they are not live-wired.

`LinearWorldSessionSmoke` exercises actual backend draws: cached extra view of main
color routes to HDR; original RGBA8 main stays green; unrelated same-size target stays
blue; HDR stores values above 1; a copy of main color reads back the HDR pixels; an
unseen LINEAR_SRGB native compiles before draw; a legacy-cached native cannot bind to
the HDR pass; poison recovery skips one begin then reactivates; native registration
mid-session discards subsequent HDR draws. This does not wrap `LevelRenderer.render`,
promote Fabulous descriptors, or install ShaderManager/PostChain mixins.

- [x] `./gradlew build`: passed including all Metal GPU smoke; `/tmp/water-w2o-build.log`.
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`:
  passed in 39 seconds using the explicit NORMAL preset; `/tmp/water-w2o-client.log`.
  Numeric exposure/HUD assertions passed. Visually inspected refreshed
  [water identity](../run/screenshots/0001_metalcraft-water-identity-water.png) and
  [half-exposure/HUD](../run/screenshots/0003_metalcraft-world-grade-half-exposure-hud.png)
  captures: magenta water coverage excludes glass/ice/slime/lava; darkened world retains
  white HUD. This is a legacy-route regression, not live HDR acceptance.
- [x] Final `git diff --check`: passed.

Next: wrap `GameRenderer.renderLevel`'s `LevelRenderer.render` with the session,
install reload-correct ShaderSource and PostChain pipeline registration, promote
Fabulous/transparency targets while a token is active, and keep the live no-argument
geometry entry legacy until that wrap is complete. Physical display validation remains
open. W2 stays unchecked; W3 remains gated.


### W2 live wrapping claimed — 2026-09-06 by `grok`

Coordinator `grok` serializes this tracker. Three parallel worktree agents own disjoint
implementation files; they must not edit this plan.

- [ ] W2q: wrap `GameRenderer.renderLevel`'s `LevelRenderer.render` with
  `MetalLinearWorldSession`, exception-safe close, and `gradeLinearWorld` when a token
  is active. Keep `WorldGeometryAdapter.beginFrame()` legacy at acquire until the wrap
  itself selects LINEAR_SRGB for that frame. Owner `grok/w2q`.
- [ ] W2r: install reload-correct `ShaderSource` selection through `ShaderManager.apply`
  into `MetalGpuDevice.setReloadShaderSource`. Owner `grok/w2r`.
- [ ] W2s: promote Fabulous/transparency descriptors to RGBA16_FLOAT while a token is
  active and register PostChain pipelines via `registerLinearWorldPostContract`,
  verifying the live graph with `LinearWorldTransparencyConfig`. Owner `grok/w2s`.

Physical display validation stays unclaimed. These substeps must not mark W2 complete.


### W2q/W2r/W2s evidence — 2026-09-06 by `grok`

Coordinator merged worktree agents `grok/w2q`, `grok/w2r`, and `grok/w2s`, registered
mixins, and validated. W2 stays unchecked; W3 remains gated.

- [ ] W2q: wrap helper, GameRenderer mixin, GPU smoke, and encoded grade seam are in
  place. Live HDR session begin is **gated**. Enabling `MetalLinearWorldActivation.begin`
  from GameRenderer selected LINEAR_SRGB and identity-routed main to RGBA16_FLOAT, then
  dropped rebuilt terrain from the standard-world water identity fixture (sky-only
  captures after `allChanged()`, no magenta). `beginLive` therefore returns the encoded
  `gradeWorld` path. `WorldGeometryAdapter.beginFrame()` at acquire stays legacy.
  Owner `grok/w2q`; coordinator applied the gate after live validation.
- [x] W2r: `ShaderManager.apply` installs `compilationCache::getShader` through
  `MetalGpuDevice.setReloadShaderSource`; `close()` clears it. GPU fixture covers
  install, GLSL selection, identity no-op, generation/poison, and null fallback.
  Owner `grok/w2r`.
- [x] W2s: Fabulous layer and transparency-final descriptors promote to RGBA16_FLOAT
  only while a fabulous HDR token is active and `LinearWorldTransparencyConfig` accepts
  the live graph. PostChain createPass registers `FABULOUS_TRANSPARENCY` / `LINEAR_COPY`.
  Outline/spectator chains stay RGBA8. No live promotion occurs while W2q is gated.
  Owner `grok/w2s`.

Implementation files: `MetalLinearWorldActivation`, `MetalGpuDevices`,
`GameRendererWorldGradeMixin`, `GpuDeviceAccessor`, `LevelRendererAccessor`,
`ShaderManagerMixin`, `MetalLinearWorldPostActivation`,
`LevelRendererFabulousPromotionMixin`, `PostChainLinearWorldMixin`,
`LinearWorldTransparencyConfig.tryVerify`, `WorldGeometryAdapter` identity-routed
scene format, `MetalWaterIdentityGameTest` magenta assertion, mixins.json, and smokes
`LinearWorldActivationSmoke`, `LinearWorldReloadSourceSmoke`,
`LinearWorldFabulousPromotionSmoke`.

Validation:

- `./gradlew build`: passed, including Metal GPU smoke
  (`/tmp/water-w2qrs-build.log`, `/tmp/water-w2qrs-smoke.log`).
  Activation, reload-source, and Fabulous promotion fixtures printed their pass lines.
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed
  in 37 seconds on Metal with explicit NORMAL world generation. Numeric magenta
  identity (water-only, baseline/restored clean) and encoded-path exposure/HUD
  assertions passed (`/tmp/water-w2qrs-client.log`).
- Visually inspected refreshed captures: pool/flow/waterlogged water become magenta;
  glass, ice, slime and lava retain appearance; debug-off restores water; half-exposure
  darkens the world and keeps a white HUD. This is the gated encoded route, not live HDR.
- `git diff --check`: passed.

Local visual artifacts:

- [Baseline](../run/screenshots/0000_metalcraft-water-identity-baseline.png)
- [Water identity](../run/screenshots/0001_metalcraft-water-identity-water.png)
- [Restored](../run/screenshots/0002_metalcraft-water-identity-restored.png)
- [Half exposure with white HUD](../run/screenshots/0003_metalcraft-world-grade-half-exposure-hud.png)

Next: ungate `beginLive` once rebuilt terrain survives LINEAR_SRGB identity routing,
then live HDR readback and physical display validation. Fog clear RGB is still vanilla
encoded; `shared/color.metal` has no host decoder. W2 remains unchecked.
