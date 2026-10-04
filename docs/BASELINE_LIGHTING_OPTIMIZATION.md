# Baseline lighting optimization

- [x] Profile Standard with water, wind and clouds disabled at 16/16 in an existing NORMAL world.
- [x] Isolate CPU submission, shadow rendering, deferred lighting and local-light work.
- [x] Reduce redundant shadow submission and uniform processing while retaining near and distant lighting.
- [x] Verify work reduction and invalidation in game; run Metal regressions.
- [x] Run the final build after world-unload validation.
- [x] Qualify the follow-up GPU change in a foreground FPS comparison; the earlier CPU changes alone remain unproven.

Includes the water and distant-shadow changes described in `SHADER_DISTANCE_OPTIMIZATION.md`.

## Findings and changes

Reducing map resolution leaves CPU caster collection and draw submission intact.
The diagnostic run attributed roughly 1.2 ms/frame to this path in its later
samples. Disabling local lights or bypassing the lighting resolve did not remove
that cost; suppressing shadow draws did. These background timings identify work
to investigate; they are not a qualified gameplay FPS comparison.

- Retain static near/distant depth maps within one world tick when camera position,
  orientation, projection, world, section storage, atlas and sampler are unchanged.
  A new tick, changed world clock, mesh publication/recycling or camera movement
  requires a redraw. Wind-animated casters keep per-frame updates. The celestial
  angle must match exactly, including interpolation between world ticks (see the
  flicker correction below).
- Cache the inventory of sections with opaque geometry, including off-camera
  casters. Moving views still cull this inventory against the new shadow volumes,
  instead of scanning every empty section slot. Mesh publication/reset invalidates
  it. Retrieve the storage iterator before taking the dispatcher lock, preserving
  the established lock ordering. Draw slices are acquired fresh under that lock;
  no borrowed GPU slices are retained by the inventory.
- Avoid mapping world uniform buffers outside an active G-buffer resolve. Reuse
  matrix scratch storage and cache exact validated source transforms (including
  JOML properties), avoiding repeated inversions. Per-frame reset, invalid inputs,
  mutable sources and pending-resolve flushes retain their existing behavior.
- Clear cached frames and section references on renderer reset, geometry
  invalidation and close, so returning to the menu cannot retain an old world.

## Evidence

The final `MTL_DEBUG_LAYER=1 ./gradlew build --offline` passed (22 seconds).
GPU checks include near/distant shadow coverage and transitions, receiver
planes, filtering, HDR, water, resource lifetimes and mixed transform captures.
The added reuse check rejects changes to tick, clock, camera, world, storage,
atlas, sampler, mesh revision and wind state.

The existing disposable NORMAL save was reused at 16/16 with the default Metal
engine. In the work-only comparison, optimized windows recorded 53 updates/245
reuses and 32 updates/67 reuses: 82% and 68% of frames reused shadow maps.
Uncached windows reused none. Block edits published a new mesh revision and
camera movement refreshed the maps. The final 20-second functional run also
verified release of cached scene references on world unload. The initial unload
check caught a deferred renderer reset; the final implementation clears at the
level-extraction lifecycle boundary as well. Player settings are restored by the harness.
The final functional window recorded 52 updates and 242 reused frames (82% reuse),
and the world-unload assertion passed. `git diff --check` also passed.

Local evidence is under ignored `run/diagnostics/baseline-lighting/` and
`run/diagnostics/baseline-lighting-*.log`. The initial `before` run lacked foreground
qualification. The strict `reuse-comparison` run failed its focus gate because
the Mac was locked; its results are not used. Work-only JSON explicitly disclaims
background FPS. No FPS uplift is claimed until a focused comparison is possible.

## Follow-up after the user reported no FPS improvement

- [x] Reproduce the loss with the game focused and optional effects disabled.
- [x] Require settled terrain and avoid recompiling unchanged options between phases.
- [x] Isolate lighting, G-buffer, sky and grading work with diagnostic bypasses.
- [x] Replace individual shadow-depth fetches with Metal gathers, keeping the filter footprint.
- [x] Compare gathered filtering against the scalar reference on the GPU; run the full Metal build.
- [x] Finish the paired foreground FPS comparison with both programs resident.

The earlier reductions in CPU work were not proof of an FPS benefit. The first
foreground attribution attempts also allowed visibility/mesh discovery to
continue during sampling. Those FPS numbers must not be used as before/after
evidence. The harness now freezes random block ticks in the disposable save,
waits for 40 ticks without mesh publication, records visible sections and mesh
revision per phase, checks presentation each tick, and supports disabling
per-pass counter instrumentation. It changes shader options only when needed.

The settled, focused `settled-no-counters` diagnostic held 775 visible sections
and mesh revision 106094 throughout all ten phases, at a 1920×1080 world target
and 1708×960 drawable. Bypassing lighting improved its two samples from
127/137 to 150/160 FPS; bypassing the whole G-buffer reached 165/186 FPS.
Sky work also contributes; skipping grading did not show a useful improvement.
These bypasses remove visuals and are attribution tools, not shipping settings.
Clouds, water and wind were off, with 16/16 distances, two 768px detailed cascades
out to 96 blocks, and a 512px distant cascade out to 320 blocks.

Lowering shadow-map resolution does not change the sixteen scalar fetches in
the soft-shadow filter. The new path groups those depths into four Metal gathers
(one gather for the coarse distant filter). It retains the sixteen comparisons,
their bilinear weights, each texel's receiver-plane correction, clear-depth
handling and out-of-bounds behavior. No world resolution, shadow distance,
filter width or update frequency is reduced by this change.

The filter test compares old/new results across 256/512/768/1024/1536/4096px maps,
subtexel motion, sloped/grazing receivers and map borders (RGBA16 agreement within
0.001), alongside the existing cascade/caster/lighting image tests. The full
`MTL_DEBUG_LAYER=1 ./gradlew build --offline` passed in 26 seconds.

The first gather FPS comparison still reloaded the pack between variants and
had substantial spread, so it is not the final qualification. Both programs
are now kept resident during the comparison. The next attempt rejected a lost
foreground window instead of reporting background timings.

The focused tests also caught an unload timing gap: the saving screen renders
while teardown waits for the server, before renderer/extractor resets finish.
Cached shadow state is now cleared at client teardown entry; a leftover extracted
frame cannot repopulate it once the client level is null.

### Qualified result

`gather-qualified` completed successfully in 68 seconds, including world loading,
eight 3-second samples, block-edit/camera invalidation checks and world-unload
validation. The reused NORMAL forest scene stayed at 768 visible sections and
mesh revision 106080 for every sample. All eight presentation checks passed
(62 checks per phase). Settings/extents match the attribution configuration above.
Both variants use the same shadow-map reuse policy; only the fragment filter changes.

| Filter | Four FPS samples | Median FPS | Median of GPU span medians |
| --- | --- | --- | --- |
| Previous scalar fetches | 180.52, 177.93, 182.17, 168.69 | 179.22 | 12.308 ms |
| Grouped Metal gathers | 189.07, 226.95, 188.44, 173.76 | 188.75 | 11.310 ms |

Median FPS increased 5.3%. All four paired comparisons improved; three were
3.0–4.7%, while one was 27.5%, so the unusually fast sample is not representative
of the expected gain. GPU spans include overlap/gaps and are not additive pass
costs. This supports a modest baseline improvement in this scene, not a broad
claim that the earlier CPU changes or every scene will gain the same amount.

The final run used `baselineVariants=scalar_shadows,standard`,
`baselineRepeats=4`, `baselineSampleTicks=60`, and `passCensus=false`.
Detailed local evidence: `run/diagnostics/baseline-lighting/gather-qualified.json`
and `run/diagnostics/baseline-lighting-gather-qualified.log`. Player settings were
restored automatically. Build output is `build/libs/metalcraft-0.2.0-dev.1.jar`.

## Shadow flicker correction

- [x] Check the grouped filter against the original on varied depth patterns at 4K.
- [x] Compare actual terrain/resolve output at 4K while turning the camera.
- [x] Reproduce stale shadow reuse with a changing interpolated celestial angle.
- [x] Require identical light direction for reuse; retain grouped sampling.
- [x] Run the complete Metal validation build.
- [x] Verify advancing light with stationary and moving cameras in the normal save.

The reuse key previously accepted sun/moon angles within 0.001 radians of the
cached frame. The world tick was still part of the key, so an otherwise stationary
view could hold its shadow map between ticks and then jump to the current light
direction. The fitted matrices and depth map now refresh for **any** change to
the interpolated angle. Identical camera/light/caster frames can still reuse maps.

The regression test fails before the fix on `0.3 -> 0.30000004` within the same
world tick, and also covers decreasing and larger within-tolerance changes.
The fixed full build with Metal validation passed. The grouped filter agrees
with the scalar reference within 0.001 across two 3840×2160 varied-depth GPU
fixtures. Six actual 4K terrain/resolve image pairs, taken during camera turns,
were byte-identical in the visibility channel. This isolates the stale light
state from the grouped-fetch optimization, which remains enabled.

The final 45-second live run passed at verified 3840×2160 and 16/16 distance in
the existing NORMAL save. With time advancing, the stationary camera recorded
209 shadow updates and zero reuses over 20 ticks; the turning camera recorded
216 updates and zero reuses. This removes the former 20 Hz hold/catch-up behavior.
The six old/new filter image pairs still had zero changed pixels. World unload
validation passed and the harness restored player settings.

Evidence stays local under `run/diagnostics/shadow-flicker*.log`,
`run/diagnostics/shadow-flicker/`, and ignored `run/screenshots/`.

## Remaining moving-shadow instability

- [x] Reproduce motion of a real caster's shadow under a smoothly advancing sun.
- [x] Remove absolute-world-coordinate amplification from the shadow grid.
- [x] Keep camera translation locked to texels and remove the grid-axis switch near noon.
- [x] Test world-reset state, world-border precision, filtering, coverage and camera movement.
- [x] Complete the 4K normal-world receiver tracking check and final build.

The remaining instability came from snapping the light-space grid using
`dot(absoluteCameraPosition, rotatingLightAxis)`. Even a stationary camera far
from world origin then changes the grid's fractional phase rapidly as the sun
turns. Rasterized caster edges move across texels much faster than their physical
shadow moves. Refreshing stale maps alone did not address this separate cause.

Each live shadow module now retains two grid phases, advanced only by camera
translation projected onto the current light axes. A stationary camera's absolute
world position never enters that update. Camera translation at fixed light still
preserves world-locked texels. The state resets on scene/world teardown. The light
basis projects the celestial orbit's Z axis onto its light plane, avoiding the
former change of grid orientation around `abs(sun.y) == 0.99` and at noon.

The GPU regression renders a real 4×4-block caster and tracks its shadow centroid
over 96 small sun-angle steps against the analytical projected position. With
four 1024px cascades and a 256-block detailed range, mean unintended movement per
step at `(4702.5,216,546.5)` fell from **0.030974 blocks to 0.0000794 blocks**
(approximately 390× smaller). Near world border it fell from 0.028267 to the
same 0.0000794; the origin result is unchanged. The old implementation fails the
regression. Additional tests cover subtexel camera translation, scene resets,
noon orientation continuity and actual interpolated block-face tags at 4K.

The live surface check disables the screen vignette in the test only, and tracks
receivers verified fully lit with a margin around each sample. The original
heightmap-only exclusions included real offscreen-caster shadows; vignette also
darkened otherwise constant debug colors. Those preliminary counts are not
evidence of incorrect surface normals. The player's options are restored by the
harness. Faster grouped shadow filtering and the existing pass/sample counts
remain unchanged; this fix changes CPU cascade fitting only.

Final validation: the 45-second existing NORMAL-world run rendered at verified
3840×2160 with 16/16 distances, 1024px detailed shadows over 256 blocks, 256-block
caster extension and 512-block distant shadows. Across ten camera turns at a
grazing sun angle, **0 / 28,186** tracked initially lit receiver samples darkened
unexpectedly. The full `MTL_DEBUG_LAYER=1 ./gradlew build --offline` passed,
including the new moving-sun and noon-basis regressions. Settings were restored
and no screenshots were added to Git. Local evidence: `shadow-sun-motion-before.log`,
`shadow-sun-motion-fixed.log`, `shadow-surface-tracked-4k.log` and
`shadow-stability-final-build.log` under `run/diagnostics/`.
