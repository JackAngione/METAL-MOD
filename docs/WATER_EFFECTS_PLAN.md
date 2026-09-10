# Water visual effects implementation plan

Created: 2026-09-05. Status: started on `codex/water-effects`; W1–W7 complete; W8 integrated release validation remains open.

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
| [x] | W2 | HDR composition prerequisite | W1 | grok | done | 2026-09-06: live HDR session ungated. First-time LINEAR native stand-ins no longer poison the open session; geometry is selected before beginLinearWorld; fog clears of RGBA16_FLOAT decode through SceneColor. Standard-world water identity, linear exposure/HUD, GPU HDR>1, and sRGB layer display checks pass. See W2 completion evidence. |
| [x] | W3 | Water routing and stable frame inputs | W1, W2 | grok | done | 2026-09-07: surface/opaque depth debug views, GPU reconstruction at native and odd half extents, live native/half identity, resize/world-change snapshot extents, and ordinary vs forced Fabulous water routing. See W3 completion evidence. |
| [x] | W4 | Animated surface and baseline reflections | W3 | /root | done | 2026-09-07: periodic normals, bounded Fresnel/environment/sun, GPU seam/roughness/fallback fixtures, and standard-world camera/noon/night/cave comparisons pass. Build and lifecycle pass; see W4 completion evidence. |
| [x] | W5 | Refraction and depth absorption | W4 | /root | done | 2026-09-08: ordinary-mode opaque replacement, validated reverse-Z thickness/refraction, RGB absorption/scattering, GPU extremes, and standard-world shallow/deep/steep/underwater/Fabulous/transparent checks pass. See W5 completion evidence. |
| [x] | W6 | Shoreline foam and underwater appearance | W5 | /root | done | 2026-09-09: one-block shoreline coverage regression fixed and locked down across three shelf regions with zero sky/dry-fence leakage; GPU, NORMAL-world water matrix and lifecycle pass. See W6 final closure evidence. |
| [x] | W7 | Controls and optional screen-space reflections | W5 | /root | done | 2026-09-09: bounded controls, restart/reload persistence, fallback recovery and low/high SSR pass GPU and NORMAL-world validation; clean captures and 4K M4 Max tier timings recorded below. Baseline remains default. |
| [x] | WD | Fine surface ripples and detail slider | W4, W7 | /root | done | 2026-09-09: four filtered detail tiers, persistence, GPU seams/identity and live NORMAL-world 16/16 comparisons pass. Build and lifecycle pass; see WD completion evidence below. |
| [x] | WD2 | Irregular motion and close-up micro-ripples | WD | /root | done | 2026-09-09: coherent randomized currents, 4/8/12 detail bands, GPU and close-camera NORMAL-world 16/16 checks pass. See WD2 completion evidence. |
| [x] | WD3 | Clumped geometric patterns with shared travel direction | WD2 | /root | done | 2026-09-09: visibility regression fixed with true noise-height gradients and directional reflections; final-image checks, natural ocean, build, shader-package identity and lifecycle pass. See visible-detail closure evidence. |
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

- [x] W2q: wrap `GameRenderer.renderLevel`'s `LevelRenderer.render` with
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

- [x] W2q: wrap helper, GameRenderer mixin, GPU smoke, and live HDR session begin.
  The earlier gate was a native-generation poison: first-time LINEAR G-buffer stand-ins
  during an open session incremented `nativeGeneration` and discarded subsequent HDR
  draws (sky-only after rebuild). Admission of new LINEAR natives during a session no
  longer poisons; geometry switches to LINEAR_SRGB before `beginLinearWorld` so retiring
  legacy stand-ins cannot invalidate the token. `beginLive` now calls `begin`.
  Owner `grok/w2q`.
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

Next: W3 production water routing. W2 completion evidence follows.


### W2 completion evidence — 2026-09-06 (`grok`)

W2 is complete as the live HDR composition prerequisite. Values above 1 survive
opaque/forward GPU composition; the live Standard world graph now uses the same
RGBA16_FLOAT session, linear fog/blend producers, and a single world-only grade
into encoded main before hand/HUD. Physical display is the sRGB-tagged
`CAMetalLayer` presenting already-encoded BGRA8; a colorimeter was not used.

Root cause of the earlier live-gate: `registerNativePipeline` always bumped
`nativeGeneration` and poisoned the open session. `WorldGeometryAdapter` builds
LINEAR G-buffer stand-ins on first terrain draw, so HDR frames dropped rebuilt
geometry after sky (fog-cleared HDR looked like sky-only). Replacement of an
existing native program still poisons.

Implementation files: `SceneColor`, `MetalGpuDevice.registerNativePipeline`,
`MetalLinearWorldActivation` (`beginLive` → `begin`, geometry before session),
`MetalCommandEncoder` HDR clear decode, `MetalWaterIdentityGameTest` live-HDR
and linear-exposure assertions, `LinearWorldSessionSmoke`,
`WorldLightingModuleSmoke`.

Validation:

- `./gradlew build`: passed, including Metal GPU smoke (fog-clear decode, linear
  stand-in admission, host `SceneColor` vs `shared/color.metal` references,
  HDR RGB 2 surviving composition/grade).
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`:
  passed in 38 seconds on Metal with explicit NORMAL generation.
  `lastLiveUsedHdr` stayed true through `allChanged()` rebuilds. Numeric magenta
  identity (water-only) and linear half-exposure (decoded RGB × 0.5, then encode
  once) plus white HUD assertions passed (`/tmp/water-w2-live-client.log`).
- `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`:
  passed in 40 seconds (`/tmp/water-w2-live-lifecycle.log`).
- Visually inspected refreshed captures: terrain remains after rebuild; pool,
  falling/flowing water and waterlogged-slab water become magenta; glass, ice,
  slime and lava retain appearance; debug-off restores water; half-exposure
  darkens the world and keeps a white HUD. Glass/ice/lava overlap is stable in
  linear composition. `MCMetalSurface` continues to tag `kCGColorSpaceSRGB`;
  presentation copies encoded bytes with no second transfer.
- `git diff --check`: passed.

Local visual artifacts:

- [Baseline](../run/screenshots/0000_metalcraft-water-identity-baseline.png)
- [Water identity](../run/screenshots/0001_metalcraft-water-identity-water.png)
- [Restored](../run/screenshots/0002_metalcraft-water-identity-restored.png)
- [Half exposure with white HUD](../run/screenshots/0003_metalcraft-world-grade-half-exposure-hud.png)

Limitations carried into W3: opaque snapshots and production water metadata are
still absent; translucent terrain/entity pipelines stay on the vanilla forward
path; outline intermediates remain encoded; GGX is out of scope. No water
performance budget is due at W2.


### W3 initial increment — claimed 2026-09-06 by `/root`

- [x] W3a: implement typed stored opaque color/depth snapshot ownership, validity,
  distinct-source checks, resize/retirement, and focused GPU coverage. Owner `/root/snapshots`.
- [x] W3b: audit production metadata transport and the live pre-transparency seam;
  record concrete next integration steps. Owner `/root`.

This increment begins W3. Production routing, stable frame uniforms, native/half-resolution
debug views and live lifecycle/transparency acceptance remain required before W3 is complete.
Coordinator owns this tracker; workers report evidence without editing it.

W3b mapped audit (2026-09-07, `/root`): read the local 26.2 client sources jar
under `.gradle/loom-cache/minecraftMaven` and current backend. `SectionCompiler`
creates one builder per layer and interleaves fluid and block-model vertices. Attach a
zero-initialized metadata stream to each builder; tag water during the fluid wrapper,
then carry it through `Results.renderedLayers` into the compiled mesh. Vertex count must
match the BLOCK stream, including non-water models and reverse fluid faces.

`SectionRenderDispatcher.getRenderSectionSlice` returns shared vertex-buffer byte offsets;
`LevelRenderer.prepareChunkRenders` divides that offset by the vertex stride for each
`RenderPass.Draw.baseVertex`. `MetalRenderPassBackend.drawMultipleIndexed` binds the
whole vertex buffer and preserves this base vertex. Therefore a separately packed metadata
buffer cannot simply use the same absolute vertex ID: either mirror vertex heap allocation
exactly, or bind each section's metadata slice and subtract its original base vertex through
an explicit per-draw uniform. Choose the latter to avoid coupling allocator fragmentation.
Bind metadata and base-vertex adjustment within the existing draw loop, retaining reversed
translucent draw order and original index buffers. Never key metadata by sorted primitive.

Publication must wait for metadata upload as well as existing vertex/index callbacks in
`checkSectionMesh`; resource-generation mismatch or absent metadata selects the original
forward pipeline. `releaseSectionMesh` must retire metadata with the mesh, and buffer heap
relocation must refresh the original base-vertex adjustment. `ResortTransparencyTask`
uploads only indices and must preserve the original metadata. Cancelled rebuilds must close
the unpublished metadata alongside mesh results. These changes are the next production
routing increment; the existing magenta probe is still only an identity diagnostic.

The verified capture seam is `PreparedFrame.executeTranslucent` HEAD, immediately after
`WorldGeometryAdapter.resolveOpaque`. `LevelRenderer.addMainPass` invokes it after solid
features and Fabulous depth copies, before any translucent features/terrain. Read the HDR
session's main attachments, not whichever Fabulous output target happens to be current.
The opaque snapshot includes sky and opaque features; depth clear remains reverse-Z zero.
Projection/camera state must later be captured from the same extracted render frame rather
than queried independently during water draws. Missing projection is not identity projection.

W3 snapshot contract: each HDR world frame captures one stored, private, single-sample
RGBA16_FLOAT opaque color texture and one DEPTH32_FLOAT opaque depth texture at the
actual world attachment extent (not the window/presentation extent). Each destination has
texture-binding and copy-source/copy-destination usage; a GPU copy is its producer, so it
does not need render-attachment usage. Both sources must have copy-source usage and belong
to the same device. No memoryless attachment, mismatched extent/format, mip view, or source
alias of the owned destinations is accepted. Reverse-Z clear depth zero is copied unchanged.

The device owns the pair across frames and retires it on resize, reload and close; encoded
Metal commands retain native resources through GPU completion. Capture is once per healthy
HDR session. The public typed binding is absent before capture, outside that session or
after poisoning; stale handles reject access after invalidation. Copies and consumers use
the existing serial command queue, with no CPU readback/wait in the production frame loop.
This establishes opaque inputs only: water surface position/normal, projection, flow,
biome data and immutable per-frame uniforms still require the production routing increment.

W3a completion evidence (2026-09-07, `/root` and GPT-5.6 Sol medium
`/root/snapshots`): implemented `MetalOpaqueSnapshotOwner`, device/session lifetime
integration, capture after opaque resolve in `PreparedFeatureFrameMixin`, and
`OpaqueSnapshotSmoke` registered in the normal shader smoke suite. The water client fixture
asserts successful capture and absence of bindings outside the world session after rebuilds.

- [x] `./gradlew build`: passed including Metal GPU smoke; `/tmp/water-w3a-build.log`.
  Snapshot fixtures verify HDR
  values above 1, D32 values and reverse-Z clear zero, exact 9x5/5x3 extents, producer
  mutation after capture, same-size reuse, alias/format/extent/usage fallback, stale
  handles and resize/close while queued copies retain native resources.
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed
  in 46 seconds with explicit NORMAL world generation; `/tmp/water-w3-client.log`.
  Live snapshot-capture/session-scope assertions, water-only identity/restoration and
  linear half-exposure/white-HUD assertions pass.
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`: passed
  in 45 seconds using the standard-world fixture; `/tmp/water-w3-lifecycle.log`.
- [x] Visually inspected refreshed [water identity](../run/screenshots/0001_metalcraft-water-identity-water.png),
  [restored water](../run/screenshots/0002_metalcraft-water-identity-restored.png), and
  [half-exposure/HUD](../run/screenshots/0003_metalcraft-world-grade-half-exposure-hud.png).
  Water-only coverage, glass/ice/slime/lava controls and HUD appearance remain correct.
  These are regression views of the existing identity probe, not production depth debug views.
- [x] `git diff --check`: passed.

Next: implement metadata compile/upload/publication and per-draw routing described in W3b,
then bind immutable projection/camera/time/flow inputs and add surface/opaque depth debug
views. W3 remains in progress: native/half live depth views, explicit Fabulous/ordinary
water comparisons, resize/world-change acceptance and production water GPU routing fixtures
are still required. No new water surface shading or performance acceptance is claimed.
The added stored pair costs 12 bytes per world pixel before allocation alignment.


### W3 production metadata increment — claimed 2026-09-07 by `/root`

Snapshot progress committed as `bcdc436`. Coordinator serializes tracker and shared registration.

- [x] W3c: collect explicit per-vertex water identity, face normal and flow alongside
  unmodified BLOCK vertices during compilation; focused metadata fixtures. Owner `/root/metadata`.
- [x] W3d: transport compiled metadata through section lifetime and bind per sorted draw
  with a defined missing-data fallback. Owner `/root`.
- [x] W3e: establish immutable typed projection/camera/time water frame input contract
  with focused validity/precision fixtures. Owner `/root/frame_inputs`.

Keep W3 unchecked until production routing, depth diagnostics and live acceptance pass.

- [x] W3f: GPU fixture for actual forward water pipeline with mixed sidecar identity,
  nonzero original base vertex and sorted indices. Owner `/root/snapshots`.

W3d implementation refinement: store managed immutable metadata on the compiled mesh
before existing vertex/index publication; attach its owner directly to each Draw during
`prepareChunkRenders`. Admit a private-to-the-mesh, immutable shared Metal buffer before
its first water draw. This synchronous CPU upload needs no new asynchronous publication
callback, does not touch vanilla vertex heaps, and avoids global buffer-offset registries.
Cancellation/mesh retirement closes the owner; index-only resorting preserves it. Missing
or retired metadata keeps the original forward pipeline. Per-draw original base vertex
is bound with the sidecar; draw indices and reversed lists remain unchanged.

W3c–W3e partial progress (2026-09-07, `/root` with Sol medium agents): compiled
sections now carry explicit water sidecars (32 bytes/original vertex: normal/material,
flow/padding) into immutable per-mesh Metal buffers. Draw metadata follows the original
`RenderPass.Draw` through list reversal; shader lookup subtracts the current original
base vertex. Non-water gaps are explicitly zero. Original BLOCK tint/UV/light bytes and
indices stay unchanged, including index-only resorting. Cancellation/retirement closes
metadata with the compiled mesh. No frame-loop GPU readback or upload wait was introduced.

The Standard forward variant preserves original terrain blend/depth/cull state and shared
atlas/light/fog math, and only routes draws with available metadata and healthy captured
frame inputs. Non-water fragments retain the same compatibility shading. `WaterRoutingDebug`
uses the actual shader material stream to show water-only magenta; the legacy vertex RGB
probe is disabled during the updated client fixture. Per-draw metadata/base/count use
explicit slots 14/15; frame inputs use buffer 13; opaque snapshot textures use 12/13.
Water-bearing draw groups currently bypass command batching to retire immutable per-draw
uniforms only after native encoding; benchmark/optimization remains open.

Actual raster projection is captured at the first `ProjectionMatrixBuffer.getBuffer` in
`GameRenderer.renderLevel` after camera effects. Frame data copies that projection and its
inverse, extracted camera position/submersion and game time. The 176-byte uniform owns
projection/inverse, split camera position, bounded animation seconds and submerged flag;
it is written once per world session and retired after encoding. Time wraps at 1024 seconds;
future wave functions must be periodic at that boundary. Missing/invalid projection selects
the original forward path. These inputs are bound for future effects; no animation,
refraction, absorption or foam is implemented in this increment.

Initial validation: compile and full build passed; `/tmp/water-w3-routing-build.log`.
Updated standard-world production identity fixture passed in 39 seconds, followed by the
same test with immutable frame binding enabled in 39 seconds (`/tmp/water-w3-frame-client.log`).
Visually inspected production magenta capture: water-only coverage excludes glass/ice/slime/lava.
Final fixture/lifecycle validation and final tracker checkboxes follow when complete.

W3c–W3f completion evidence (2026-09-07, `/root`, `/root/metadata`,
`/root/frame_inputs`, `/root/snapshots`; subagents GPT-5.6 Sol medium):

- [x] `./gradlew build`: final pass including actual forward water GPU fixture;
  `/tmp/water-w3f-build.log`, 11 seconds. `WaterForwardPipelineSmoke` calls the actual
  adapter pipeline with 28-byte BLOCK vertices, base vertex 2, mixed water/glass IDs,
  overlapping reordered indices, debug on/off and HDR blend reference checks.
  Metadata and immutable frame serialization/precision/invalid-input fixtures also pass.
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed
  with actual metadata routing and frame-uniform binding in explicit NORMAL world;
  `/tmp/water-w3-frame-client.log`, 39 seconds. Successful production draw count,
  water-only identity/restoration, session scope and linear exposure/HUD asserted.
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`: passed
  with opaque texture bindings and frame retirement in place; 40 seconds,
  `/tmp/water-w3-routing-lifecycle.log`.
- [x] Visually inspected refreshed production identity and restored captures at the W3a
  artifact paths. Pool, flowing/falling water and waterlogged water are magenta only
  under shader diagnostic; glass/ice/slime/lava stay unchanged; restored water is blue.
- [x] `git diff --check`: passed. Earlier compile errors were fixture/mixin Java casts,
  corrected before these passing runs. Subagents resumed successfully after usage-limit retry.

W3 is still unchecked. Next acceptance work: surface/opaque depth debug shader views at
native and half resolution, explicit ordinary/Fabulous comparisons and resize/world-change
coverage. Opaque textures and stable frame data are bound but await depth/effect consumers;
GPU binding alone is not proof of reconstruction or visual effects. These substep completions
establish production identity/routing and frame ownership, not the entire W3 acceptance gate.


### W3 depth-debug increment — claimed 2026-09-07 by `grok`

Coordinator `grok` owns this tracker and the remaining W3 acceptance files. Prior W3a–W3f
routing stays in place.

- [x] W3g: surface and opaque depth debug views in the production forward water program,
  reconstructing opaque device depth with the captured reverse-Z projection. Owner `grok`.
- [x] W3h: GPU fixture for reconstruction at native and odd half extents, clear-zero far
  fallback, mixed water/glass identity, and independent CPU references. Owner `grok`.
- [x] W3i: live standard-world native and half-resolution depth/identity captures, plus
  ordinary vs forced Fabulous transparency. Owner `grok`.
- [x] W3j: live resize and world-change snapshot extent/routing checks with no frame-loop
  CPU readback. Owner `grok`.

W3 completion evidence follows.


### W3 completion evidence — 2026-09-07 (`grok`)

W3 is complete as the water routing and stable frame-input milestone. Production metadata,
opaque snapshots, immutable reverse-Z frame uniforms, and forward substitution already
existed; this increment adds proven surface/opaque depth debug views and live
native/half/Fabulous/resize/world-change acceptance.

Debug views (production forward program, no BLOCK rebuild):

- Identity: water-only magenta from the sidecar material stream.
- Surface depth: water fragments output `(1, saturate(-viewZ/32), 0)` from interpolated
  view position. Nearer water is more red; farther water is more yellow.
- Opaque depth: water fragments reconstruct the stored D32 snapshot with the captured
  inverse projection (undoing the Metal clip-Y flip) and output
  `(saturate(-viewZ/32), 1, 0)`. Pool-over-bed is lime; waterfall-over-sky (device depth
  0 / far) is yellow. Glass, ice, slime and lava keep vanilla shading.

Clear/sky device depth 0 reconstructs to the far plane. Missing snapshots or frame
inputs still select the original forward pipeline. Snapshots remain distinct stored
RGBA16_FLOAT / DEPTH32_FLOAT copies; no frame-loop CPU readback.

Fabulous HDR layers advertise RGBA16_FLOAT to Blaze3D while vanilla pipelines declare
RGBA8. `WorldGeometryAdapter` and `RenderPassColorFormatMixin` supply format-matched
pipeline copies, and post contracts are copied onto those identities so the HDR session
still selects `FABULOUS_TRANSPARENCY` / `LINEAR_COPY`. Vanilla's FABULOUS preset leaves
`improvedTransparency` off on macOS; the live check enables that option directly.

Implementation files: `gbuffer.metal`, `WaterRoutingDebug`, `MetalRenderPassBackend`,
`MetalGpuDevice` snapshot-extent diagnostics, `MetalLinearWorldActivation`,
`WorldGeometryAdapter.copyWithColorFormat`, `MetalLinearWorldPostActivation`,
`RenderPassColorFormatMixin`, `WaterDepthDebugSmoke`, `WaterForwardPipelineSmoke`,
`MetalWaterIdentityGameTest`, mixins.json, and this tracker.

Validation:

- `./gradlew build`: passed, including Metal GPU smoke (`/tmp/water-w3g-build.log`).
  `WaterDepthDebugSmoke` reconstructs reverse-Z view Z at 8x4 and odd 5x3, checks
  clear-zero far encode, mixed water/glass, and independent CPU references
  (`/tmp/water-w3g-smoke.log`).
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed in
  45 seconds on Metal with explicit NORMAL generation (`/tmp/water-w3g-client.log`).
  Snapshot extents match the world attachment at native glfw pixels, half of those
  pixels, and after a 1280x720 resize. World-change fill still produces water draws.
  Numeric identity (water-only) and linear half-exposure/white-HUD assertions pass.
- `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`: passed in
  39 seconds including resize, fullscreen, resource reload (`/tmp/water-w3g-lifecycle.log`).
- Visually inspected identity, restored, surface-depth, opaque-depth, half-res identity,
  HUD, and Fabulous identity captures. Ordinary path: pool/flow/waterlogged water only;
  glass/ice/slime/lava unchanged. Surface vs opaque encodings differ as described.
  HUD remains white over a darkened world.
- `git diff --check`: passed.

Local visual artifacts (generated outputs, not committed):

- [Baseline](../run/screenshots/0000_metalcraft-water-identity-baseline.png)
- [Water identity](../run/screenshots/0001_metalcraft-water-identity-water.png)
- [Restored](../run/screenshots/0002_metalcraft-water-identity-restored.png)
- [Surface depth](../run/screenshots/0003_metalcraft-water-surface-depth.png)
- [Opaque depth](../run/screenshots/0004_metalcraft-water-opaque-depth.png)
- [Half exposure with white HUD](../run/screenshots/0006_metalcraft-world-grade-half-exposure-hud.png)
- [Native glfw identity](../run/screenshots/0007_metalcraft-water-identity-native.png)
- [Half-resolution identity](../run/screenshots/0008_metalcraft-water-identity-half.png)
- [Resized identity](../run/screenshots/0010_metalcraft-water-identity-resized.png)
- [World-change identity](../run/screenshots/0011_metalcraft-water-identity-world-change.png)
- [Fabulous identity](../run/screenshots/0012_metalcraft-water-identity-fabulous.png)

Limitation carried into W4/W5: Fabulous water identity and other translucents are
correct, but the composed Fabulous screenshot currently omits opaque terrain and sky
(black background). Ordinary transparency includes the opaque world. Do not treat
Fabulous opaque composite as proven when implementing refraction. No water animation,
absorption, foam, or performance budget is claimed. Next task is W4.


### W4 progress — claimed 2026-09-07 by `/root`

Coordinator serializes this tracker. Smaller tasks use GPT-5.6 Sol medium agents.

- [x] W4a: periodic world-anchored normals and finite baseline lighting; owner `/root/water_shader`, done; build/GPU and live evidence below.
- [x] W4b: standard-world paired animation/lighting captures; owner `/root/water_live`, done; coordinator ran and inspected the corrected test matrix below.
- [x] W4c: actual shared-helper GPU fixtures; owner `/root/water_gpu`, done.
  `./gradlew build` passed including Metal compute/readback checks and the 255–256
  interpolated-quad regression (`/tmp/water-w4-build.log`, 12 seconds).
- [x] W4d: extracted immutable sky/sun inputs, integration and final validation; owner `/root`, done; serialization/night/rain/nonstandard-sky fixtures and lifecycle pass below.

Preserve W3's recorded Fabulous opaque-background limitation. W4 remains unchecked until
GPU and live acceptance evidence has been inspected.

W4 partial evidence (2026-09-07): implementation compiles and the full build/GPU smoke
passes. First live run stopped at a normal-debug comparison whose >80 RGB threshold was
inherited from depth debug. Inspection and pixel measurements showed real bounded normal
animation (4238 central-pool samples exceeded RGB delta 12; none exceeded 40). The test
now measures that diagnostic at delta 12 and keeps the original depth threshold unchanged.
A separate review corrected per-vertex wrapping that would distort interpolation at the
256-block boundary; a GPU regression now evaluates the actual 255.5 midpoint. Live rerun
is pending. Cave comparison is now at noon, with a torch, independently testing sky occlusion.


### W4 completion evidence — 2026-09-07 (`/root`, GPT-5.6 Sol medium workers)

W4 is complete. Implementation lives in Standard `shared/water.metal`, `gbuffer.metal`
and `pack.json`; immutable lighting capture is in `WaterFrameInputs` and
`GameRendererWorldGradeMixin`. `WaterRoutingDebug` adds BASELINE (4) and NORMALS (5),
while OFF (0) is normal production shading. Identity and depth modes remain unchanged.

**Animation and lighting contract**

- Two bounded analytic normal scales, no displaced vertices. Integer chunk block origins
  reduce modulo 256 before float conversion; local vertex positions stay unwrapped through
  interpolation. This preserves both distant-coordinate precision and quads spanning the
  255–256 boundary. Integer spatial frequencies repeat after 256 blocks, with 205/451 time
  cycles per 1024 seconds. Surface normals are normalized with finite fallbacks; wave
  strength zero returns the normalized mesh normal. Flow projected onto the face selects
  travel direction for each scale, including vertical falling faces. This is a bounded
  directional approximation, not physical flow simulation.
- Dielectric F0 is 0.02; default roughness 0.08. Roughness-aware Schlick mixes the existing
  linear lightmap seed with the linear environment; a bounded sun lobe avoids a singular
  point-light peak. Original alpha, ordering, coverage and fog remain intact. Refraction
  and a replacement transmission/blending contract remain W5 work.
- Frame layout is now 208 bytes: W3's original 176 bytes, world sun direction/energy at
  176, linear sky RGB/availability at 192. Values copy the same extracted `SkyRenderState`.
  Sun direction matches the established terrain-shadow convention; rainBrightness scales
  sun energy, and sun elevation gates daylight. Night environment is deliberately dark
  (at most 2% of extracted sky color); non-Overworld, missing or invalid sky uses zero
  sky/sun. Existing biome tint and blocklight remain in the vanilla base term.
- Squared vertex skylight suppresses sky/sun in caves. Forward water does not sample the
  terrain shadow map; this is an explicit occlusion approximation, so detailed outdoor
  cast-shadow reflection visibility is not claimed. No point-light specular is invented.
  Controls remain W7 work; no water UI settings or SSR are claimed here.

**Validation**

- `./gradlew build`: passed in 12s, including real Metal GPU smoke and production forward
  pipeline compilation (`/tmp/water-w4-build.log`). `WaterSurfaceSmoke` tests normalization,
  zero strength, degenerate inputs, temporal/spatial periods, negative chunk coordinates,
  wrapped section seams, the interpolated 255.5 regression, Fresnel endpoints, roughness
  0/1 at grazing, finite highlights, and missing-light identity. `WaterFrameInputsSmoke`
  verifies exact layout, linear sky conversion, rain/night/nonstandard fallback and copying.
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed in
  69s (`/tmp/water-w4-client.log`). Explicit NORMAL generation with fixed seed 12345;
  built fixtures sit above the generated terrain. Includes W3 identity/depth/native/half/
  resize/world-edit/Fabulous routing checks and W4 frozen-time paired comparisons.
- `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`: passed in
  40s (`/tmp/water-w4-lifecycle.log`), including reload, resize and fullscreen.
- Visually inspected noon baseline/effect, normal animation, translated-camera normals,
  grazing baseline/effect, night effect and torch-lit enclosed noon baseline/effect.
  Still and flowing surfaces animate; vertical water retains its face orientation; the
  x=16 pool has no chunk line. Grazing reflection is restrained, with no bright daytime
  reflection in night or cave captures. Glass/ice/slime/lava identity controls stay separate.
- Fixed-time camera validation: register the central pool's edges row by row between the
  original and x+4 translated camera normal images. Across 9629 interior samples, mean
  absolute RGB error is 0.016/255, median 0, p95 0.167/255. This independently confirms
  that camera translation does not move the wave pattern relative to the pool. Enclosed
  noon baseline/effect mean RGB difference is 0.271/255 (animated atlas/torch noise remains).
  Metrics: `/tmp/water-w4-visual-metrics.txt`.
- `git diff --check`: passed. The first failed diagnostic run and corrected threshold are
  documented above; the final build and live acceptance use the corrected implementation.

**Local comparison artifacts** (generated, not committed)

- [Noon baseline](../run/screenshots/0012_metalcraft-water-w4-noon-baseline.png),
  [noon effect t0](../run/screenshots/0013_metalcraft-water-w4-noon-effect-t0.png),
  [noon effect t1](../run/screenshots/0015_metalcraft-water-w4-noon-effect-t1.png).
- [Normals t0](../run/screenshots/0014_metalcraft-water-w4-noon-normals-t0.png),
  [normals t1](../run/screenshots/0016_metalcraft-water-w4-noon-normals-t1.png),
  [translated camera](../run/screenshots/0017_metalcraft-water-w4-camera-translated.png),
  [translated normals](../run/screenshots/0018_metalcraft-water-w4-camera-translated-normals.png).
- [Grazing baseline](../run/screenshots/0019_metalcraft-water-w4-grazing-baseline.png),
  [grazing effect](../run/screenshots/0020_metalcraft-water-w4-grazing-effect.png).
- [Night baseline](../run/screenshots/0021_metalcraft-water-w4-night-baseline.png),
  [night effect](../run/screenshots/0022_metalcraft-water-w4-night-effect.png).
- [Enclosed noon baseline](../run/screenshots/0023_metalcraft-water-w4-cave-baseline.png),
  [enclosed noon effect](../run/screenshots/0024_metalcraft-water-w4-cave-effect.png).

**Next concrete steps (W5)**

1. Resolve or explicitly gate W3's existing Fabulous opaque/sky black-background limitation
   before accepting refracted composition in that mode. W4 does not fix that pre-existing issue.
2. Claim W5 and establish one replacement/transmission blend policy so opaque snapshot
   color is not counted twice through source-alpha blending.
3. Implement validated common-space thickness, bounded refraction with foreground/border
   rejection, and depth-dependent absorption; test sky, near-plane, below-surface and
   transparent-overlap cases. Keep W5 unchecked until its own GPU/live criteria pass.


### W5 completion evidence — 2026-09-08 (`/root`)

W5 is complete for ordinary transparency. Implementation lives in Standard
`shared/water.metal` and `gbuffer.metal`; `WaterFrameInputs` carries an immutable,
fail-closed refraction gate selected by the verified world composition mode.
`WaterRoutingDebug.REFRACTION_OFF` retains W4 lighting for matched comparisons, and the
existing NORMAL-world client fixture now builds explicit one- and five-block-deep pools.

**Refraction, absorption, and composition contract**

- Reverse-Z opaque depth reconstructs into the same view space as the interpolated water
  surface. Clear depth, non-finite data, a surface at/across the near plane, and a sample
  in front of the surface reject replacement. Valid Euclidean view-space path length is
  clamped to 24 blocks. Clear sky/no opaque hit therefore retains the finite W4 surface
  fallback rather than inventing a far-plane water column.
- The animated normal produces an at-most-eight-pixel offset, weighted by thickness.
  Out-of-bounds candidates and candidate depths in front of water revert to the valid
  undistorted opaque sample. This prevents border reads and foreground-silhouette bleed.
- Opaque snapshot RGB is already fogged. The shader reconstructs its sampled position,
  removes that established fog where numerically recoverable, applies Beer-Lambert RGB
  attenuation plus restrained biome-tinted scattering in linear space, combines it with
  W4 Fresnel/environment/sun reflection, then applies fog once at the water surface.
- A valid ordinary-mode result writes the complete reflected/transmitted RGB with alpha
  one through Minecraft's retained source-alpha blend state. The atlas alpha still owns
  discard/coverage boundaries, but the opaque background is not blended a second time.
  Refraction-off, invalid depth, camera-submerged, and Fabulous frames retain W4's original
  straight-alpha surface contribution. Fabulous remains gated because its known missing
  opaque composite is still unresolved; W5 does not claim refractive Fabulous support.
- The opaque snapshot deliberately omits other transparency. The live fixture confirms
  above-water stained glass remains in the sorted result; stained glass below the water
  is visible with refraction off but disappears under opaque-snapshot replacement. This
  is the W1-documented limitation, now validated rather than silently treated as support.

**Validation**

- `./gradlew build`: passed in 12s (`/tmp/water-w5-build.log`), including production Metal
  pipeline compilation and `WaterSurfaceSmoke`. The GPU helper fixture covers zero and
  maximum thickness, clear/foreground/near-plane-invalid inputs, zero-strength refraction,
  bounded offsets, image edges, zero-thickness absorption identity, maximum RGB absorption,
  and invalid-thickness identity. `WaterFrameInputsSmoke` verifies the immutable ordinary/
  Fabulous gate layout and preservation of captured lighting.
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: final pass in
  1m27s on Apple M4 Max (`/tmp/water-w5-client-final.log`). The fixture explicitly selects
  NORMAL world generation with seed 12345. It compares W4-only and W5 shading at frozen
  time, exercises shallow/deep pools, red-bed silhouettes, above/below-water stained glass,
  a steep border/sky view, underwater fallback, and Fabulous fallback. Refraction changes
  more than 80 sampled pixels; underwater and Fabulous matched pairs have zero pixels over
  a 5% difference threshold. The deep crop's linear RGB mean is
  `(0.0294, 0.0508, 0.0945)` versus shallow `(0.0879, 0.1256, 0.1836)`.
- `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`: passed in
  42s (`/tmp/water-w5-lifecycle.log`), including resource reload, resize, and fullscreen.
- Visually inspected the corrected W5 images after an initial fixture run exposed stone
  roofs over both pools. The final open-pool captures show a readable shallow bed,
  progressive deep attenuation, stable silhouettes/borders/sky, the documented transparent
  limitation, and unchanged underwater/Fabulous fallbacks. `git diff --check` passed.

**Local comparison artifacts** (generated, not committed)

- [W4/refraction off](../run/screenshots/0025_metalcraft-water-w5-refraction-off.png) and
  [refraction plus absorption](../run/screenshots/0026_metalcraft-water-w5-refraction-absorption.png).
- [Steep border/sky](../run/screenshots/0027_metalcraft-water-w5-steep-border-sky.png).
- [Underwater refraction off](../run/screenshots/0028_metalcraft-water-w5-underwater-refraction-off.png)
  and [underwater fallback](../run/screenshots/0029_metalcraft-water-w5-underwater-fallback.png).
- [Fabulous refraction off](../run/screenshots/0030_metalcraft-water-w5-fabulous-refraction-off.png)
  and [Fabulous fallback](../run/screenshots/0031_metalcraft-water-w5-fabulous-fallback.png).
- [Fabulous identity](../run/screenshots/0032_metalcraft-water-identity-fabulous.png).

Next tasks unlocked by W5 are W6 (foam and underwater appearance) and W7 (controls and
optional SSR). W6 should replace the explicit underwater W4 fallback with one owned fog/
absorption policy; W7 owns user-facing strength controls.


### W5 continuation audit — 2026-09-08 (`/root`)

- [x] Verify the existing W5 completion against committed implementation and retained
  validation artifacts. Owner `/root`, with GPT-5.6 Sol low worker
  `/root/w5_acceptance_audit`; status done. W5 landed in `865b042`.
  Both audits found no remaining W5 acceptance gap within its documented ordinary-mode
  scope. Confirmed all three retained validation logs above end `BUILD SUCCESSFUL`,
  the client selects NORMAL generation with seed 12345, and W5 captures exist.
  Reinspected captures 0026 and 0027 for shallow/deep attenuation and stable silhouettes,
  steep views, borders and sky. GPU fixtures cover the listed numerical extremes;
  visual depth/transparent behavior remains screenshot-based evidence.
  Corrected the stale opening status to W1–W5 complete. No shader changes or redundant
  test reruns were needed; `git diff --check` passed. Existing Fabulous, underwater,
  and submerged-transparency limitations remain as documented above.


### W6 progress — claimed 2026-09-08 by `/root`

Coordinator serializes tracker changes; GPT-5.6 Sol low workers own independent files.

- [x] W6a: bounded contact foam and GPU fixtures; owner `/root/w6_foam`.
- [x] W6b: standard-world foam, entry/exit, partial-submersion, cave and upward captures; owner `/root/w6_live`.
- [x] W6c: single underwater fog/absorption policy, mild distortion and integration; owner `/root`.
- [x] W6d: build, GPU/live/lifecycle validation and visual inspection; owner `/root`.

W6 acceptance passed; completion evidence follows the partial-progress history below.

2026-09-08 partial progress: W6a shader and helper GPU fixtures implemented; W6b live
matrix implemented, camera waterline positions under refinement. First build exposed a
standalone grade fixture without the new host binding; an optional-binding fallback fixed
that compatibility case, and the next `./gradlew build` passed (11s,
`/tmp/water-w6-build.log`). New distortion-specific fixtures and live validation are next.
Mapped 26.2 audit confirms `WaterFogEnvironment` owns attribute/biome fog color and
water-vision-scaled distances; `ScreenEffectRenderer.submitWater` retains its 0.1-alpha
immersion veil after hand rendering. W6 retains those owners, adds no second fog term,
and distorts composed world color only before tone/output transfer and hand/HUD. Captured
camera WATER state and fluid-surface depth ease distortion through the first 0.25 block;
a named immutable `underwater_frame` binding uses completion-retired uniform storage.


### W6 completion evidence — 2026-09-08 (`/root`, GPT-5.6 Sol low workers)

W6 is complete. Contact foam lives in `shared/water.metal` and `gbuffer.metal`;
`shared/underwater.metal` and `grade.metal` implement world-only distortion.
`UnderwaterFrameInputs`, `FrameBindings`, `MetalShaderFrameExecutor`, the world grade/
activation bridge and `GameRendererWorldGradeMixin` carry the immutable named
`underwater_frame` binding. Standard's manifest declares that binding and helper.

**Appearance and ownership contract**

- Foam uses the valid **undistorted** opaque thickness, upward material face normal,
  and world-periodic animated noise. Its contact band fades from 0.12 to 0.65 blocks;
  warm-white mixing is capped at 0.34 before existing surface fog. Sky/clear/missing
  depth, foreground hits, distant surfaces and vertical/downward faces produce no foam.
  This is screen-space contact approximation, not physical shoreline simulation.
  Ordinary above-water replacement owns foam; the existing Fabulous and submerged
  forward-surface fallbacks remain intact.
- Minecraft `WaterFogEnvironment`/`FogRenderer` exclusively own underwater distance
  attenuation, fog color, biome/environment attributes and water-vision scaling.
  `ScreenEffectRenderer.submitWater` retains its separate 0.1-alpha encoded immersion
  veil. No second underwater absorption/fog term is added by Standard. W5's surface-to-
  bed RGB absorption remains above-water only, avoiding a duplicate underwater column.
- Distortion samples already-composed world color in the existing grade pass, before
  tone/output conversion, hand, overlay and HUD. Each axis is bounded to 1.5 render
  pixels; a 12-pixel edge fade prevents border streaking. The same extracted camera
  WATER classification drives the effect, with a smoothstep over the first 0.25 block
  below the fluid surface. Leaving water immediately supplies identity. Time repeats
  after 1024 seconds. The immutable 16-byte payload survives world-session close on
  the activation frame; a dedicated completion-retired uniform ring protects GPU reads.
  Missing inputs, poisoned sessions and encoded fallback use zero distortion.
- `FOAM_OFF` (7) keeps W5 refraction but supplies exact-zero foam strength;
  `UNDERWATER_DISTORTION_OFF` (8) keeps production surface effects and exact original
  scene UVs. User-facing strength controls remain W7 scope.

**Validation**

- `./gradlew build`: final pass in 10s (`/tmp/water-w6-build-final.log`). Production
  forward/grade Metal pipelines compile; `WaterSurfaceSmoke` covers positive/animated
  contact, zero foam and sky/invalid/foreground/distance/face rejection. New
  `UnderwaterSurfaceSmoke` runs the actual Metal helper for exact identity, periodic
  time, bounded native/odd extents and edge behavior. `UnderwaterFrameInputsSmoke`
  verifies transition smoothness, zero/exit/invalid input, clamping and GPU layout.
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed in
  1m47s (`/tmp/water-w6-client.log`). Uses explicit NORMAL generation, seed 12345;
  fixture structures are above generated terrain. W3–W5 regressions plus W6 paired
  foam and underwater captures pass. Camera fog assertions prove air, partial WATER,
  submerged WATER, cave WATER and returned-air states.
- `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`: passed
  in 39s (`/tmp/water-w6-lifecycle.log`), including resource reload, resize, fullscreen,
  failure fallback and recovery. This fixture also explicitly selects NORMAL generation.
- Visually inspected foam off/on, entry, partial submersion, distortion off/on, upward,
  exit and cave/HUD captures. Foam is sparse at shallow contacts; dry fences and sky
  are not outlined. Underwater geometry stays readable and upward views remain bounded.
  Cave visibility remains low-light, with the hand and white HUD composed afterward.
  Entry/exit views retain matching geometry and no residual underwater veil; they are
  not asserted pixel-identical because vanilla lighting/vision varies over the sequence.
- Pixel evidence (`/tmp/water-w6-visual-metrics.txt`): foam changes 677 pixels at summed
  RGB delta >12 and 177 at >80; dry fence/sky control changes zero pixels at >12.
  Underwater distortion changes 113626 pixels at >12 (mean per-channel delta 2.045/255).
  Cave title retains 31104 near-white pixels. These are image comparisons, not timings.
- Initial standalone grade fixture binding and live fixture camera-field compile errors
  were corrected before the passing runs. A GPT-5.6 Sol low independent integration
  audit found no actionable lifetime, binding, camera-state or edge-handling defect.
  `git diff --check` passed.

**Local visual artifacts** (generated, not committed)

- [Foam off](../run/screenshots/0032_metalcraft-water-w6-contact-foam-off.png),
  [foam on](../run/screenshots/0033_metalcraft-water-w6-contact-foam-on.png).
- [Entry air](../run/screenshots/0034_metalcraft-water-w6-entry-air.png),
  [partial submersion](../run/screenshots/0035_metalcraft-water-w6-partial-submersion.png).
- [Distortion off](../run/screenshots/0036_metalcraft-water-w6-underwater-distortion-off.png),
  [underwater entry](../run/screenshots/0037_metalcraft-water-w6-underwater-entry.png),
  [looking upward](../run/screenshots/0038_metalcraft-water-w6-underwater-looking-up.png).
- [Exit air](../run/screenshots/0039_metalcraft-water-w6-exit-air.png),
  [cave with hand/HUD](../run/screenshots/0040_metalcraft-water-w6-underwater-cave-hud.png).

W7 controls/optional SSR and W8 integrated performance/release validation remain open.
The previously documented Fabulous opaque-composite and submerged-transparent-object
limitations are unchanged; W6 does not claim to resolve them.

### Latest-commit FPS audit — 2026-09-08 (`/root`)

2026-09-08 follow-up: user tested with W6 stashed and still observed roughly 70%
lower FPS. Owner `/root`; status in progress. Comparison expanded to W3 `8923222`,
W4 `0be0f6d`, and W5 `865b042`. Temporary direct production-pipeline GPU probe:
`./gradlew -I /tmp/water-commit-probe.init.gradle waterCommitPerformanceProbe`.
Two interleaved 60-repeat 4K runs measured W3/W4/W5 water-and-glass draw medians
0.2532/0.5103/0.7187 ms and 0.2524/0.5038/0.7205 ms. Glass-only medians were
0.2495/0.2678/0.2739 ms and 0.2433/0.2593/0.2688 ms. These are amortized
submission-to-completion times for 12 repeated synthetic draws, not game FPS;
the cumulative water-heavy cost is reproducibly about 2.85x. Logs:
`/tmp/water-commit-probe.log`, `/tmp/water-commit-probe-repeat.log`.
An isolated W5 checkout at `/tmp/water-commit-world` is running a NORMAL seed-12345
world comparison with committed W3/W4/W5 pack resources, warmed interleaved repeats,
water-heavy and inland scenes, and full-frame/pass metrics. No shader fix yet.

Full-world partial result: at actual 3840x2104, 12-chunk requested render distance,
NORMAL seed 12345, three interleaved water-heavy repeats give median FPS
W3 57.30 (56.76–58.08), W4 53.14 (53.08–53.15), W5 50.61 (50.42–50.94).
This W5-host/committed-pack comparison isolates pack changes, not every historical
host binary. The inland noise-site search was stopped after the water measurements;
the follow-up uses existing generated spawn-area land to avoid that lengthy search.
Log `/tmp/water-commit-world.log`; inspected screenshot
`/tmp/water-commit-world/run/screenshots/0002_commit-probe-scene0-w5.png`.
The full-frame trace reveals 17 native pipeline creations every warmed frame,
roughly 9–11 ms CPU. `Frame.close()` restores LEGACY_ENCODED, and
`WorldGeometryAdapter.beginFrame(encoding)` forgets substitutions/resolve whenever
the encoding changes. The following live frame selects LINEAR_SRGB and rebuilds.
This mechanism predates W4/W5: close restoration was added in `2bfdf878`, cache
invalidation in `635b4e79`, and live HDR was enabled in `fec69b4`.
An isolated diagnostic toggle now suppresses the two redundant legacy transitions
(frame close and surface acquisition) while retaining explicit beginLive selection.
The controlled original/cached W5 full-world comparison is in progress; this is
temporary instrumentation, not a production fix or lifecycle acceptance claim.

Controlled cache experiment completed successfully (`/tmp/water-cache-world.log`,
`/tmp/water-cache-world-results.json`). Three interleaved repeats per mode/scene,
same committed W5 shaders and NORMAL world at 3840x2104:

| Scene | Original median FPS (range) | Cache preserved median FPS (range) |
| --- | --- | --- |
| Water-heavy pool | 51.69 (51.14–52.40) | 102.49 (99.97–104.52) |
| Generated land at 96,136,-32 | 60.79 (57.83–63.52) | 120.00 (119.57–120.01) |

Warmed pipeline creation drops from 17/frame to zero; land median CPU render time
falls from approximately 15.9 ms to 4.1 ms. The cached land run is presentation-paced
near 120 Hz, so this does not measure its unconstrained maximum FPS. This confirms
per-frame encoding transitions evicting pipeline caches as a substantial performance
defect, including on land. It does not establish the user's exact 70% regression or
prove that W4/W5 introduced it: the triggering HDR path predates those commits.
W4/W5 increase shader work and the cost of the repeated rebuilds. Production files
were not changed for the experiment; the diagnostic modifications remain only in
`/tmp/water-commit-world`. A production fix must retain safe encoded fallback,
hand/HUD behavior, reload/resize, and resource retirement and pass lifecycle checks.

- [x] Identify and experimentally isolate a substantial FPS defect.
- [x] Implement and validate production pipeline-cache lifetime fix; exact user-scene
  before/after comparison remains outstanding. W6/W8 performance acceptance stays open.

- [x] Compare `865b042` (W5) against parent `0be0f6d`, separating the existing
  uncommitted W6 changes. Static audit complete: the production shader adds up to two
  opaque-depth reads and one opaque-color read per eligible water fragment, two
  position reconstructions, background fog removal and RGB exponential absorption.
  The branch is gated to ordinary above-water water rendering. This commit does not
  change the executor, snapshot allocation/copy implementation, or fullscreen grade.
- [ ] Reproduce the reported FPS decrease with matched standard-world full-frame
  measurements. Status: blocked on identification of the affected running build and
  last known-good build/scene. The retained W5/W6 grade probe tests a different
  comparison and cannot establish the performance impact of W4-to-W5 water shading.
  Existing benchmark infrastructure supports NORMAL terrain and repeated frame/pass
  measurements, but no matched W4/W5 result establishes this report's cause yet.
  No performance fix or measured causal finding is claimed by this static audit.


### W6 regression investigation — reopened 2026-09-08 (`/root`)

User reports massive performance regression and no visible new water effects. Prior
correctness-only captures did not measure performance and are insufficient to close this
report. W6 is reopened. Next: reproduce with paired same-scene timings and inspect actual
effect coverage; preserve current changes while comparing against W5.

- [x] Establish reproducible performance and visible-effect feedback loops.
- [x] Identify cause, fix, and rerun paired standard-world validation.


Regression investigation partial evidence (2026-09-08): user clarifies roughly half FPS
while walking on land, not just underwater. W6 remains open; no performance fix is claimed.
A temporary differential harness compiles the committed W5 executor/resources and current
W6 executor/resources, runs the real LINEAR_SRGB grade at 3840x2160, and alternates 60
repeats of eight encodes per batch. Command:
`./gradlew -I /tmp/water-w6-probe.init.gradle waterGradePerformanceProbe`.
The harness has a >50% grade-cost regression assertion. Two successful runs measured
W5/W6 medians 0.1428/0.1543 ms and 0.1373/0.1793 ms, respectively
(`/tmp/water-w6-grade-perf.log`, `/tmp/water-w6-grade-perf-repeat.log`). These include CPU
submission and completion waits for the isolated grade, not full-frame times. Variation
and a concurrent OpenGL game prevent treating this as a full-game performance result;
it has not reproduced the reported halving of FPS. Original baseline/build identification
and a paired land-scene full-frame measurement remain required.

Reanalysis of the prior foam off/on captures shows only 677 of 921600 pixels (0.07346%)
change by summed RGB >12, concentrated beside stairs (187 left, 310 right) and the wall;
the broad shallow shelf changes zero pixels. Thus the earlier nonzero-pixel assertion
proves a localized effect but not useful ordinary shoreline coverage. The original W6
completion claim above is superseded by this reopened investigation. No speculative
shader changes have been applied during diagnosis.


### Pipeline cache lifetime fix — 2026-09-09 (`/root`)

- [x] Implement and validate encoding-specific geometry program caches. Owner `/root`,
  done. HDR close still restores legacy semantics for hand/HUD; the next world frame
  reuses its cached substitutions and resolve instead of rebuilding 17 pipelines.
  Channel refresh retires parked programs; adapter close/reload retires both sets.
- [x] GPU regression: `WaterForwardPipelineSmoke` crosses LINEAR → LEGACY → LINEAR
  and asserts identical water stand-in and compiled pipeline objects survive.
- [x] `./gradlew build`: passed (12s), including Metal GPU smoke, explicit legacy/
  linear selection, forced fallback and exception-safe activation tests.
- [x] `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`:
  passed (43s), NORMAL world, reload/resize/fullscreen/failure recovery and hand/HUD.
- [x] Isolated committed-W5-host NORMAL seed-12345 water scene with the final cache
  implementation: three repeats at 3840x2104 yield median 119.15 FPS
  (118.95–119.37), with zero warmed pipeline creations. This later run is near the
  presentation limit and is not a same-session before/after speedup claim.
  Earlier interleaved diagnostic trials measured 51.69 → 102.49 FPS over water and
  60.79 → 120.00 FPS on land. Logs: `/tmp/water-cache-fix-build.log`,
  `/tmp/water-cache-fix-lifecycle.log`, `/tmp/water-final-cache-perf.log`.

An initial diagnostic approach retained linear selection after frame close; lifecycle
validation caught an encoded hand-pass incompatibility. The final implementation keeps
all existing encoding transitions and caches each encoding separately. W6 visual work,
W8 release budgets, and the exact reported 70% user-scene regression remain separate.


### W6 implementation commit — 2026-09-09 (`/root`)

User reports the performance bug was found in a separate task and requests committing
W6. The pipeline-cache fix is now committed as `5ff4cda`, preserving encoding-specific
programs across HDR transitions; its evidence is recorded above. Commit the implemented
W6 foam, underwater distortion, frame bindings and tests on top of that fix. The prior
W6 standard-world and lifecycle evidence remains recorded. Broader ordinary-shoreline
visibility remains open: the separate cache fix does not establish improved foam coverage.
W6's checkbox therefore stays unchecked pending that visual acceptance, rather than
conflating an implementation commit with completion of all reopened validation.

Precommit integration validation: `./gradlew build` passed in 11s, including Metal GPU
smoke, on top of `5ff4cda` (`/tmp/water-w6-precommit-build.log`).
`git diff --check` passed.

### W6 final closure evidence — 2026-09-09 (`/root`)

The reopened ordinary-shoreline visibility gate now passes. The root cause was the
combination of a 0.65-block hard contact cutoff, which excluded the common one-block-deep
Minecraft shelf, and a noise threshold that could erase an otherwise valid contact at
some animation phases. `shared/water.metal` now admits contacts up to 1.5 blocks and keeps
a faint continuous contact trace beneath the animated crest. Existing upward-face,
valid-depth, foreground, sky, zero-strength and distant-water gates remain unchanged.

- `WaterSurfaceSmoke` first reproduced the defect deterministically: a one-block contact
  returned zero. The updated production helper passes one-block-positive and two-block-zero
  fixtures alongside all existing animation and rejection checks.
- The NORMAL-world client assertion now requires changed samples in the left, center and
  right shallow-shelf regions and rejects changes on sky and the dry fence. Final counts at
  RGB delta >12 with two-pixel sampling were left 325, center 138 and right 358 (821 total),
  versus the reopened investigation's roughly 169 sampled pixels; sky and dry fence were 0.
- `./gradlew build`: passed in 14s, including the Metal GPU smoke suite.
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed in
  2m43s using NORMAL generation and seed 12345. Captures `0032` through `0040` revalidated
  foam off/on, entry/exit, partial submersion, distortion identity/on, upward view, cave,
  hand and HUD; the broader W3–W7 water regression matrix also passed.
- `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`: passed in
  43s, including reload, resize, fullscreen, failure fallback/recovery, world close and
  clean shutdown. `git diff --check` passed.
- Visual inspection of the final foam pair confirms a restrained trace across the near
  shoreline and both side contacts without tinting the open pool, sky or dry fence.

W6 is complete. W8 retains the broader composed-scene release matrix and performance budget.

### W7 controls increment — 2026-09-09 (`/root`)

Continuation: controls committed as `40ac07f`. Coordinator `/root` owns integration,
tracker updates and serialized GPU/live runs. GPT-5.6 Sol low agents own independent
SSR shader (`/root/ssr`), GPU checks (`/root/ssr_gpu`), and live harness
(`/root/live_controls`) work. W7 remains in progress.

- [x] Claim W7 after its completed W5 dependency and audit settings propagation.
- [x] Add water enable, wave/refraction strength, absorption, foam, underwater
  distortion, and off/baseline reflection quality to Standard's existing settings UI.
- [x] Validate production forward water-off RGB/alpha ordering, reload retention,
  and zero/default control variant compilation in `WaterForwardPipelineSmoke`.
- [x] Validate saved controls across restart, live repeated toggles and failed
  configuration recovery in a NORMAL world; capture off/on and zero-strength views.
- [x] Implement bounded optional SSR, GPU rejection fixtures and standard-world
  camera/edge/thin-geometry/resolution checks; measure each tier before acceptance.

This is the first implementation increment, not W7 completion or an SSR deferral.
All new controls use existing `recompile` semantics so forward geometry and grade
receive the same saved options without extending their distinct uniform layouts.
The existing generic UI supplies labels and numeric sliders from option declarations.
Strengths range from 0 to 1 in 0.1 increments; default 1 preserves prior appearance.
Reflection defaults to baseline; the only currently offered tiers are off/baseline.
SSR is deliberately not advertised before implementation and measurement.

Water-off skips added surface effects and grade distortion while preserving vanilla
compatibility shading, original alpha/sorting/fog and Minecraft's underwater fog/veil.
Diagnostic identity/depth modes remain available. Zero refraction strength removes
the sample displacement while retaining absorption/replacement composition; zero
absorption removes optical attenuation/scattering; zero waves uses the mesh normal.
Foam and distortion zero retain their helper identity behavior. Reflection off removes
both environment reflection and sun highlight. Graph outputs remain defined because
no graph nodes are disabled. Recompile cost during UI adjustment remains unmeasured.

Validation: `./gradlew build` passed in 15s, including actual Metal GPU fixtures
(`/tmp/water-w7-controls-build.log`). The expanded forward fixture compares water-off
against independent baseline HDR RGB and sorted overlap references, verifies false
survives runtime reload, and compiles zero/default variants after option changes.
`git diff --check` passed. This increment has no new live screenshots, restart check,
SSR implementation or performance results; those acceptance gates stay open above.

### W7 optional SSR continuation — 2026-09-09 (`/root`)

- [x] Commit the validated initial controls increment as `40ac07f`.
- [x] Implement low/high SSR in shared water helpers and forward water composition.
- [x] Add readable translated controls/tooltips and keep baseline as the default.
- [x] Implement fresh-runtime disk persistence/recovery smoke, SSR GPU fixtures,
  NORMAL-world controls/SSR captures, and interleaved full-frame quality benchmark.
- [x] Static integration review: fix sun visibility on partial hits, retain the valid
  refinement endpoint, reject excessive grazing ray penetration, preserve ordinary/
  submerged fallback scope, correct fixture fill limits and zero-control expectations.
- [x] Pass full build/GPU validation after the test compile-error correction: 2026-09-09, `./gradlew build`, 16s, `/tmp/water-w7-build-final.log`.
- [x] Run and inspect live W7 captures and lifecycle regression.
- [x] Record tier timings, spreads and visual acceptance; resolve remaining fixture
  gaps before closing W7.

Ownership: GPT-5.6 Sol low agents `/root/ssr`, `/root/ssr_gpu` and
`/root/live_controls` supplied shader, GPU and live-fixture changes. Coordinator
integrated review corrections, labels/tooltips, isolated persistence test and benchmark.
The agents reached their usage limit during follow-up review; saved changes remain.

SSR low uses 12 march steps plus up to 3 refinements, maximum distance 24 and
maximum ray penetration 0.35; high uses 24 steps plus up to 5 refinements, distance
48 and penetration 0.18. A 0.12 start bias is included in the distance bound.
Both use pre-water opaque color/depth, reverse-Z reconstruction and bounded edge,
distance and penetration confidence. Invalid/clear/off-screen/uncertain hits fall
back to baseline; rejected first crossings deliberately terminate conservatively.
Nearly screen-parallel rays also fall back. There is no temporal history or added
texture allocation. Hits replace the Fresnel environment contribution while keeping
the original sun visibility; sampled hit fog is removed before existing surface fog.
Production SSR runs only for ordinary above-water composition. Transparent objects
are absent from the snapshot; Fabulous and submerged paths retain baseline reflection.

Historical validation state before the final runs: the first `./gradlew build` compiled client code but failed
`compileShaderSmokeJava` because the new persistence fixture declared an uncaught
checked exception (`/tmp/water-w7-build.log`). The coordinator corrected that declaration.
The requested rerun was rejected by automatic approval review with a usage-limit
error; no post-correction build or new GPU/live success is claimed. This is the
current execution blocker, not evidence that SSR meets acceptance. W7 stays unchecked.
Manifest/translation parsing and `git diff --check` pass after the final source edits.

Next commands, serialized to avoid GPU contention:

1. `./gradlew build`
2. `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true -PmetalWaterQualityBenchmark=true`
3. `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`

The benchmark writes `run/water-w7-quality-results.json`: three interleaved repeats
of water-off, reflection-off, baseline, low SSR and high SSR, each with 40 warmup
ticks and 100 capture ticks. It records actual opaque snapshot extents, device,
camera/settings, frame pacing, GPU/pass attribution and logical snapshot bytes
(12 bytes/pixel for the already-existing color/depth pair; SSR adds zero textures).
Pass spans overlap and must not be summed as frame time. These are fixture-scene
measurements, not broad W8 release validation. Summarize medians/ranges after running.

Final coverage combines the GPU fixture's positive hits, clear/off-screen/foreground/
invalid misses, even/odd extents and cave/miss composition with inspected live captures
for thin geometry, grazing/edge confidence, camera movement and half resolution. Restart
coverage uses newly constructed runtime instances reading the saved settings file; reload
and failed-pack recovery also run in the NORMAL-world client.

### W7 completion check — 2026-09-09 (`/root`)

User reports that the effects work well and requests a commit if W7 is complete.
The prior approval-review usage blocker has cleared. `./gradlew build` now passes
in 16s (`/tmp/water-w7-build-final.log`), including actual SSR GPU hits/misses,
even/odd extents, cave sun gating, and fresh-runtime persistence/recovery checks.

The NORMAL-world command with `-PmetalWaterQualityBenchmark=true` failed after
1m58s (`/tmp/water-w7-client-final.log`) at `W7 repeated toggle left stale water
output: 9336 changed samples`. Earlier W3–W6 and initial W7 off/baseline and
reenable assertions reached this point successfully. Visually inspected captures
`run/screenshots/0042_metalcraft-water-w7-disabled-first.png` and
`run/screenshots/0045_metalcraft-water-w7-disabled-again.png`: both show the
compatibility water surface, but the differing pixels require diagnosis before
declaring stable toggles. No root cause or shader regression is established yet.
Do not weaken the threshold without identifying the source of the difference.

The run stopped before SSR tier captures and timing collection; there are no W7
benchmark results or new lifecycle pass. W7 remains incomplete and unchecked.
No completion commit was created because the user's commit request was conditional
on W7 being done. Next: diagnose the image-comparison failure, rerun live/timing
and lifecycle acceptance, then record evidence and commit if all W7 gates pass.

Diagnosis: the two disabled captures were separated by two shader recompiles and
compared Minecraft's vanilla animated water sprites several seconds apart. Server
`tick freeze` does not stop render-time sprite animation, so the assertion measured
legitimate texture-frame drift across the water surface. Both disabled captures
visually showed the compatibility path, and the first disabled capture already
matched its immediately adjacent vanilla-baseline capture. The repeated-toggle
check now uses the same strict comparison against a second adjacent baseline capture;
it does not raise the threshold or excuse a shader-state mismatch.

### W7 completion evidence — 2026-09-09 (`/root`)

W7 is complete. Baseline remains the release default; SSR Low and SSR High are explicit
optional tiers. The implementation adds no temporal history and no SSR-only textures.

Validation:

- `./gradlew build` passed in 13s on Apple M4 Max, including production shader
  compilation, forward water controls, fresh-runtime disk restore/recovery and GPU SSR
  hit/refinement/miss/off-screen/foreground/even-odd/cave fixtures.
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true
  -PmetalWaterQualityBenchmark=true` passed in 4m23s in the seed-12345 NORMAL world.
  It covered repeated off/on toggles, zero strengths, resource reload, low/high SSR,
  failed-pack recovery, camera movement, thin glass/iron geometry, screen-edge rays,
  native/half resolution and Fabulous fallback. Results:
  `run/water-w7-quality-results.json`.
- The first successful benchmark run exposed a Mojang reload fade in its SSR Low
  screenshot. The harness now waits for `client.gui.overlay() == null`; the corrected
  clean visual run, `./gradlew runClient -PmetalLifecycleTest
  -PmetalWaterIdentityTest=true`, passed in 2m34s. Inspected captures
  `run/screenshots/0041_metalcraft-water-w7-enabled.png` through
  `run/screenshots/0055_metalcraft-water-w7-failure-recovered.png` show stable toggles,
  bounded SSR fading, no thin-geometry streaks, stable camera/edge behavior and a clean
  half-resolution result. The disabled pairs use adjacent vanilla baselines because
  vanilla water sprite animation continues while game ticks are frozen.
- `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true` passed
  in 42s, including world close/reopen, resource lifetime and clean shutdown checks.
- `git diff --check` passed.

Tier measurements use three interleaved five-second samples at 3840x2160, native world
resolution, unlocked presentation, with 40 warmup and 100 capture ticks per sample.
Values below are median (min-max). `GPU frame` is the measured whole GPU frame and is
not a sum of overlapping pass spans.

| Tier | Average FPS | 1% low FPS | p50 interval | GPU frame |
| --- | ---: | ---: | ---: | ---: |
| Water off | 327.5 (263.0-334.0) | 77.5 (76.9-87.1) | 1.654 ms (1.608-1.939) | 2.832 ms (2.827-2.889) |
| Reflection off | 308.8 (253.9-311.9) | 74.4 (73.8-82.5) | 1.805 ms (1.779-2.103) | 3.140 ms (3.026-3.568) |
| Baseline | 304.7 (295.4-323.0) | 77.7 (75.2-79.1) | 1.869 ms (1.853-2.057) | 3.219 ms (3.080-3.544) |
| SSR Low | 258.8 (238.6-264.5) | 78.4 (72.9-79.1) | 2.980 ms (2.861-3.592) | 4.743 ms (4.490-5.374) |
| SSR High | 245.5 (242.1-252.6) | 76.9 (66.3-77.2) | 3.391 ms (3.146-3.414) | 5.127 ms (4.615-5.199) |

Against baseline's median GPU frame, SSR Low adds 1.524ms and SSR High adds 1.908ms
in this deliberately water-heavy 4K fixture. Baseline water adds 0.387ms over water-off.
The existing RGBA16_FLOAT plus D32 opaque snapshots consume 99,532,800 logical bytes
at this extent for every tier; SSR adds zero texture bytes. These focused W7 measurements
justify keeping baseline as the default while exposing both SSR tiers. W8 still owns the
broader release performance budget and composed-scene regression matrix.

### WD — Fine water surface detail (2026-09-09, `/root`)

User-requested ocean-inspired fine ripples, retaining the stylized broad waves.

- [x] Claim task and inspect existing water/settings contracts.
- [x] Add None/Low/Medium/High detail slider and filtered, periodic fine normals.
- [x] Validate GPU identity, seams, filtering, tier compilation and saved setting.
- [x] Inspect live tier comparisons in a NORMAL world at 16/16 using default Metal; run build and lifecycle checks.

Acceptance: None preserves existing broad normals; higher tiers add progressively finer
animated detail without chunk seams or distant aliasing. Wave strength zero remains
still. Settings survive reload/restart. Record visual evidence before closure.

WD partial evidence: `./gradlew build` passed with actual Metal GPU checks
(`/tmp/water-detail-build.log`). All four forward variants compile; fresh-runtime
persistence retains High; helper fixtures pass None/broad identity, zero waves,
256-block seams, 1024-second looping, waterfall normalization, sub-block variation
and filtering back to the broad normal. Medium is the default (four additional
bands); Low adds two and High six. No additional textures or passes. Live visual
validation is running; checkbox remains open until inspection and lifecycle pass.

WD visual-check correction: the initial live run reached the Low capture but its
new assertion reused the full-effect summed RGB >80 threshold (only 3 samples).
Inspection of None/Low showed coherent visible ripples across the pool, so the
new detail-specific check now requires at least 100 changed water-region samples
at RGB >8 and at most 10 changed sky samples. This measures fine surface detail
without changing existing W4–W7 thresholds or altering the shader to satisfy a
large-effect test. The rerun also asserts 16/16 at every capture.

### WD completion evidence — 2026-09-09 (`/root`)

- `./gradlew build`: final pass, 14s, `/tmp/water-detail-build.log`, including
  production forward compilation at every detail tier and GPU/persistence fixtures.
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed
  in 2m51s, `/tmp/water-detail-client.log`. NORMAL seed 12345, default Metal
  renderer, render/simulation 16/16 asserted at every capture. Existing animation,
  moving camera, cave/night, refraction, foam, underwater, toggles/reload, SSR,
  half-resolution and Fabulous regressions pass.
- Inspected `run/screenshots/0041_metalcraft-water-detail-0.png` (None),
  `0042_metalcraft-water-detail-1.png` (Low), `0043_metalcraft-water-detail-2.png`
  (Medium), and `0044_metalcraft-water-detail-3.png` (High), all in the same
  screenshots directory. Fine ripples are visible over the existing broad waves;
  higher tiers add smaller variation. Adjacent tier water-region changed samples
  at summed RGB >8: 21,951 / 9,889 / 1,347; sky changes: 0 / 0 / 0.
- `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`: passed
  in 43s, `/tmp/water-detail-lifecycle.log`, including reload, resize/fullscreen,
  failure recovery and shutdown in a NORMAL world with default Metal at 16/16.
- `git diff --check`: passed.

The Standard pack's **Water detail** numeric slider displays None / Low / Medium /
High, defaults to Medium and uses existing recompile/persistence semantics. None
removes only the added fine detail; Wave strength zero disables both broad and
fine normals. Fine bands are anchored to periodic world coordinates and filtered
by pixel footprint before Nyquist; detail changes reflection/refraction normals
without moving mesh edges. No textures or extra passes are allocated. This task
does not establish new full-frame performance measurements or close W8's release gate.

### WD2 — Irregular fine water motion (`/root`, 2026-09-09)

- [x] Claim follow-up and inspect water/detail contracts.
- [x] Implement coherent randomized motion and finer detail across existing tiers.
- [x] Verify GPU periodicity, time evolution, filtering and zero/None identity.
- [x] Inspect close surface tier/time captures in NORMAL world on default Metal at 16/16.
- [x] Run build and lifecycle checks, record evidence.

Optional vertex displacement is being evaluated; retain mesh edges unless the
benefit justifies changes to fluid silhouettes, depth and waterlogged boundaries.
Acceptance: visibly less uniform motion, finer near-camera ripples, stable distance
filtering, all existing controls and water regressions preserved.

WD2 partial evidence: build and Metal GPU fixtures passed in 20s
(`/tmp/water-micro-build.log`). Replaced the shared sinusoidal modulation with
three quintic-smoothed, periodically hashed current fields, independently advected
over time. They bend phase and modulate packet strength without frame-to-frame
random jumps. Tiers now evaluate 4/8/12 bands; High reaches ~0.027-block wavelengths
and stronger slopes. Conservative footprint filtering includes a phase-warp margin.
None still retains the original two broad waves. Tests verify 0.015-block variation,
time evolution, periodic current noise, normal normalization and prior identities.

Use normal relief rather than optional mesh displacement: block-fluid faces lack
the tessellation to represent these micro-wavelengths; moving their corners would
change boundaries without resolving the fine shape. No new geometry, textures or
passes. Live close-up inspection and lifecycle checks remain pending. Overview
higher tiers may converge after filtering; distinct-tier visibility is required
in the new close-camera comparison, not for unresolved distant frequencies.

### WD2 completion evidence — 2026-09-09 (`/root`)

- `./gradlew build`: passed in 20s including actual Metal forward variants and
  extended micro-normal/current-noise GPU fixtures (`/tmp/water-micro-build.log`).
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed
  in 3m16s (`/tmp/water-micro-client.log`), NORMAL seed 12345, default Metal backend,
  16 render and simulation distance asserted at every capture. All existing
  water regressions pass, including toggles, resource reload, SSR and underwater.
- Close camera: player `(0,184,180)`, yaw 180, pitch 40; eye approximately 1.75
  blocks above the water. Inspected None/High captures
  `run/screenshots/0045_metalcraft-water-micro-close-0.png` and
  `run/screenshots/0048_metalcraft-water-micro-close-3.png`. Low/Medium are 0046/0047
  with the same basename and tier suffix. Each adjacent close tier passed the
  water-region visibility assertion (at least 100 samples at summed RGB >8).
- Inspected normal captures `0049_metalcraft-water-micro-close-normals-t0.png` and
  `0050_metalcraft-water-micro-close-normals-t1.png` plus final-color
  `0051_metalcraft-water-micro-close-moving.png`, all in `run/screenshots/`.
  A 10-tick time step changes at least 1,000 close normal samples at RGB >8.
  Curved wave packets and smaller normal/refraction variation evolve continuously.
- Inspected `0052_metalcraft-water-micro-grazing.png` and
  `0053_metalcraft-water-micro-grazing-half.png`: near detail remains visible with
  distance filtering; no torn surface edges. At overview distance High/Medium
  appropriately converge (7 changed samples); every overview sky comparison is 0.
- `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`: passed
  in 43s (`/tmp/water-micro-lifecycle.log`), including reload, resize/fullscreen,
  recovery and shutdown in a NORMAL world using default Metal at 16/16.
- `git diff --check`: passed.

The existing None–High slider and Medium default are retained. Additional apparent
depth comes from stronger surface normals; actual mesh height displacement remains
unimplemented by design. This is coherent procedural water motion, not a fluid
simulation. Full-frame performance for these new detail bands was not benchmarked;
W8 remains open.

### WD3 — Clumped geometric waves (`/root`)

- [x] Claim request and inspect current motion.
- [x] Add cellular geometric clumps and one shared advection coordinate.
- [x] Verify common-direction transport, clump periodicity and existing GPU contracts.
- [x] Inspect close tier/time/grazing views; run build and lifecycle checks at NORMAL 16/16 on default Metal.

All horizontal wave scales must travel together; random clump structure should
shape packets without independently moving noise layers or opposing wave phases.
Waterfall faces retain downward travel. None still disables fine bands but adopts
the requested common direction for the broad waves.

WD3 partial evidence: `./gradlew build` passes in 17s
(`/tmp/water-clumps-build.log`) after correcting an initial reserved Metal identifier.
The 3x3 cellular field uses jittered geometric centers and overlapping compact
cubic weights to form smooth clumps. Those clumps modulate crest phase, spacing
and strength. All broad/detail/noise coordinates now share velocity `(0.5,0,0.25)`
blocks/second on horizontal faces; vertical faces descend at `(0,-0.5,0)`.
Independent band clocks and counter-moving noise layers are removed.

GPU fixtures prove `normal(p + velocity*dt, t + dt) == normal(p,t)` at all four
tiers, including the broad waves and downward waterfall case; clump spatial
periodicity/variation and existing fine detail/filtering tests pass. The global
horizontal direction intentionally replaces individual fluid-flow directions per
the user's request. Live close-camera and lifecycle checks remain pending.

### WD3 completion evidence — 2026-09-09 (`/root`)

- `./gradlew build`: passed in 17s (`/tmp/water-clumps-build.log`). Extended Metal
  fixtures validate shared translation at all tiers, broad/detail agreement,
  downward waterfall transport, geometric-clump periodicity and spatial variation,
  with prior normal/identity/filtering/persistence tests passing.
- `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`: passed
  in 3m10s (`/tmp/water-clumps-client.log`), NORMAL seed 12345, default Metal,
  render/simulation 16/16 asserted at each capture. Existing routing, animation,
  camera, cave/night, foam, refraction, underwater, toggles, reload and SSR checks pass.
- Inspected close camera High `run/screenshots/0048_metalcraft-water-micro-close-3.png`,
  the later time sample `0051_metalcraft-water-micro-close-moving.png`, and grazing
  `0052_metalcraft-water-micro-grazing.png` in the same directory. The camera remains
  approximately 1.75 blocks above the water. Clumped stronger ripples sit between
  calmer patches, with finer near-surface distortion retained. All adjacent close
  tiers pass visibility checks; the close normal time-pair passes animation checks.
  The exact common direction is established by GPU transport identity tests rather
  than inferred solely from still screenshots. Overview sky changes are all zero.
- `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`: passed
  in 40s (`/tmp/water-clumps-lifecycle.log`), covering reload, resize/fullscreen,
  fallback recovery and shutdown in NORMAL terrain on default Metal at 16/16.
- `git diff --check`: passed.

The None–High slider and Medium default remain. All surface wave scales now share
one travel vector; clumps randomize the pattern's shape/spacing/strength, not its
transport direction. Mesh geometry and memory allocations are unchanged. W8's
full-frame performance/release gate remains separate.

### WD3 reopened — visible-detail diagnosis (`/root`)

User reports the result looks unchanged. Prior GPU identities and tiny changed-pixel
thresholds do not establish the requested visible, clumped water texture.

- [x] Build and run a deterministic final-image visibility feedback loop.
- [x] Verify active shader routing/build; distinguish normal generation from lighting.
- [x] Research primary-source game-water techniques and adapt the appropriate fix.
- [x] Validate a clearly visible before/after on the live Metal surface, plus regression checks.

Research agent `/root/water_research` owns only `docs/WATER_DETAIL_RESEARCH.md`;
coordinator owns implementation, tests and this tracker.

Visible-detail diagnosis evidence (2026-09-09):

- Replay command `java tools/diagnostics/WaterDetailVisibility.java
  build/water-detail-before/0045_metalcraft-water-micro-close-0.png
  build/water-detail-before/0048_metalcraft-water-micro-close-3.png` fails in 0.24s:
  fine-detail RMS 2.439/255, total RMS 3.344/255, coverage above 5/255 = 8.65%.
  Thresholds require RMS >=2.5 and coverage >=10%; previous sparse pixel checks
  could not establish a useful fine texture. These are measured final rendered PNGs.
- Focused live command `./gradlew runClient -PmetalLifecycleTest
  -PmetalWaterIdentityTest=true -PmetalWaterDetailProbe=true` passed with the old
  appearance: active Standard/detail setting verified, 47,746 None/High normal
  samples changed. The helper does reach real forward water draws. No separate
  running Minecraft process was found during the instance check; the user-viewed
  instance has not independently been identified.
- Ranked hypotheses: flat reflection radiance hides normals; sine phase modulation
  fails to generate distinct shape; filtering erases detail; stale client build.
- The new GPU reflection test fails on the old helper: equal N dot V but different
  reflected sky directions give identical radiance. A horizon/zenith directional
  approximation fixes this, keeping cave/night gates and consistent SSR fallback.
  That isolated fix passes GPU tests but still fails the final-image detail check.
- Research: [WATER_DETAIL_RESEARCH.md](WATER_DETAIL_RESEARCH.md), primary-source
  Uru, Pacific Fighters and Valve water techniques. The adopted change replaces
  phase-warped sine detail with analytic derivatives of a ridged multiscale height
  field, including the clump envelope derivative. Low/Medium/High now use 3/5/7
  height bands. Integer rotated coordinates preserve wrapping and shared advection;
  footprint filtering removes unresolved scales. No new textures or mesh displacement.
- GPU tests verify the analytic noise and full height derivatives against finite
  differences, alongside the prior transport/normal/filtering/reflection contracts.
  Build passes (`/tmp/water-visible-build.log`, 10s).
- Fixed-time focused live run passes (`/tmp/water-height-client.log`, 1m3s).
  `java tools/diagnostics/WaterDetailVisibility.java
  build/water-visible-evidence/focused-none.png
  build/water-visible-evidence/focused-high.png` passes: RMS 4.652/255, total
  RMS 13.299/255, coverage 41.36%. The client animation clock is now fixed at
  tick 340 (350 for the later sample), not merely frozen at an arbitrary tick.
  Inspected High capture clearly shows irregular connected crests. The old and
  new pairs each use matched settings internally; their wave phases differ, so
  these numbers are acceptance results, not a precise same-phase speedup/ratio.

Full composed-water regression, generated natural-water inspection and lifecycle
validation remain running; WD3 stays open until those pass.

### Visible-detail closure evidence — 2026-09-09 (`/root`)

The old code reached live water normals, but its weak stripe-like detail and
direction-independent sky radiance did not establish the requested appearance.
The final implementation uses true multiscale ridged height derivatives (including
clump-amplitude derivatives) plus a directional sky fallback. This supersedes WD3's
earlier sine-band completion claim. Single-direction transport and None–High
controls remain; Medium is still the default.

- Final `./gradlew build` passes in 10s (`/tmp/water-visible-final-build.log`).
  Both `shared/water.metal` and `gbuffer.metal` in
  `build/libs/metalcraft-0.2.0-dev.1.jar` match the tested source byte-for-byte.
- Full `./gradlew runClient -PmetalLifecycleTest -PmetalWaterIdentityTest=true`
  passes in 3m16s (`/tmp/water-visible-client.log`). NORMAL seed 12345, default
  Metal engine, render/simulation 16/16. Prior water controls, SSR, cave/night,
  underwater, refraction, foam, camera and resolution checks all pass.
- Full-run close comparison passes the same stricter replay check: fine-detail
  RMS 4.761/255, total RMS 13.502/255, coverage 44.95%. Captures are
  `run/screenshots/0045_metalcraft-water-micro-close-0.png` and
  `run/screenshots/0049_metalcraft-water-micro-close-3.png`.
- Generated deep water (no fixture blocks placed there) at player
  `(-224,63.15,-32)`, yaw180/pitch35, passes with 263,814 changed water samples.
  Inspected natural None/High and grazing images, retained as
  `build/water-visible-evidence/natural-none.png`, `natural-high.png`,
  and `natural-grazing.png`. The finer connected crests remain visible over the
  ocean and its underwater vegetation, rather than depending on a tiled pool bed.
- `java tools/diagnostics/WaterDetailVisibility.java
  build/water-visible-evidence/natural-none.png
  build/water-visible-evidence/natural-high.png` passes in 0.40s: fine-detail
  RMS 3.226/255, total RMS 15.559/255, coverage 36.81%.
- `./gradlew runClient -PmetalLifecycleTest -PmetalShaderLifecycleTest=true`
  passes in 41s (`/tmp/water-visible-lifecycle.log`), covering reload, resize,
  fullscreen, recovery and shutdown with NORMAL terrain and default Metal at 16/16.
- `git diff --check` passes; no temporary debug logging remains. The focused
  `-PmetalWaterDetailProbe=true` scenario and standalone visibility check are
  retained explicitly as diagnostic/regression tools. Research is documented in
  [WATER_DETAIL_RESEARCH.md](WATER_DETAIL_RESEARCH.md).

The sky fallback approximates horizon/zenith radiance rather than capturing actual
clouds. Geometry remains undisplaced. New full-frame performance measurements and
W8 release qualification remain outside this visibility fix. The user's separate
instance, if any, was not identified; packaging verification applies to the rebuilt
workspace JAR and live validation applies to the workspace-launched Metal renderer.
