# Water visual effects implementation plan

Created: 2026-09-05. Status: started on `codex/water-effects`; W1 complete; W2 HDR prerequisite in progress.

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
| [ ] | W2 | HDR composition prerequisite | W1 | /root | in progress | 2026-09-05: live world-only grading and stored world depth implemented; exposure/HUD test passed. Explicit sRGB presentation tagging and corrected standard-world fixture pass; linear HDR targets/forward math and physical display validation remain open. |
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
