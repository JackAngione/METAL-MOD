# Water visual effects implementation plan

Created: 2026-09-05. Status: started on `codex/water-effects`; W1 audit in progress.

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
| [ ] | W1 | Water identity and composition design | — | /root | in progress | 2026-09-05: repository audit and proposed contracts recorded below. Next: locate/generate mapped fluid sources, verify face metadata and sorting, then implement and capture the debug identity view. No live validation yet. |
| [ ] | W2 | HDR composition prerequisite | W1 | unassigned | not started | Complete or verify engine PR 7b live HDR gate. |
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
