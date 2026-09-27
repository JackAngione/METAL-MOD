# Local lighting

Implementation checklist:

- [x] Discover placed emitters from their current block-state emission, including fluids and modded blocks.
- [x] Add nearby Metal local-light visibility against block shapes, with spatial light lists and immutable uploads.
- [x] Include mobile emitters and moonlight; handle state changes, chunk unloads, world changes and pack reloads.
- [x] Verify source coverage, GPU shadows, source removal and the in-game path in a standard world at 16/16.

Enabled by default with **Local Lights** in the Standard pack's shadow options. The switch
releases the local-light resources when disabled. Blocks use `BlockState.getLightEmission()`;
the renderer does not maintain a list of allowed luminous block types. This covers fire,
soul fire, lava, torches, lanterns, lamps, luminous plants, charged/active states and modded
blocks using the same emission contract. Changes invalidate the cached section before the
next world draw.

Burning entities and fireballs emit level 15 light at their interpolated position. Held and
dropped luminous block items respect their item block-state components. Lava buckets, blaze
items, glowstone dust and glow ink also emit, as do blazes, magma cubes, glow squid (unless
temporarily darkened) and glow item frames. The glowing-outline status alone is not emission.

The Standard pack retains Minecraft's ambient/bounced block-light seed. Direct local light is
shadowed within 32 blocks of the camera, fading over the last four blocks; the light-volume
halo includes all sources and occluders that can affect those receivers. More distant surfaces
retain the vanilla lightmap. Local sources use their actual 0–15 emission and a matching range.
Spatial lists have no fixed source-count cap. Overlapping sources use the strongest visible
contribution, matching Minecraft's maximum-light propagation instead of summing thousands of
lava voxels. The existing directional shadow maps provide sunlight and moonlight shadows.

Local visibility uses block outline/occlusion boxes quantized to sixteenths. Slabs, stairs,
doors and fences retain their component boxes; translucent blocks transmit light. This is a
block-shape approximation: cutout texture holes and animated entity silhouettes are not
represented as local shadow casters. Opaque entities receive the deferred lighting.
Translucent forward materials retain their existing lightmap shading.

Validation:

- `./gradlew localLightingSmoke --offline` executes production Metal helpers and reads back
  wall/slab occlusion, reverse/axis rays, source self-occlusion, dynamic light, fog composition
  and ambient retention. CPU checks cover 300 overlapping sources without truncation and
  conservative cluster membership across all eight camera/section boundary phases.
- `./gradlew runClient -PmetalLifecycleTest -PmetalLocalLightingTest=true --args='--graphicsBackend default' --offline`
  reuses a copy of `run/saves/New World (1)` at 16 render/simulation distance. The original save
  is untouched. It verifies twelve emitter families, switched-off lamps, removed torches,
  visible local shadows, burning/extinguished entities, moonlight and option reloads.
- The passing live fixture produced 10,612 newly shadowed pixels and approximately 3,700
  fire-lit pixels. Comparing the captured moving fire views showed 11,814 newly lit pixels
  and 3,608 pixels returning to ambient at the previous position. Captures remain ignored
  under `run/screenshots/`.
- The directional LOD benchmark explicitly disables local lights because its synthetic
  fixture has no world/source buffers; local light coverage is provided by the separate suite.

## Performance follow-up

- [x] Separate local-light preparation, directional shadows and whole-frame costs.
- [x] Compare the previous cascade shader with the current shader on Metal.
- [x] Profile CPU work before selecting further changes.
- [x] Cull shadow geometry per cascade, reject empty sections early and batch shadow commands.
- [x] Skip local light work that cannot change the block-light seed.
- [x] Replace per-water-draw buffers with completion-retired arena slices and batch water draws.
- [x] Verify GPU rendering, native command validation, live lighting/water and the full build.

The local volume was not repeatedly rebuilding while standing still: preparation averaged
approximately 0.04–0.06 ms in the measured fixture, with no steady-state scene uploads.
The preceding cascade-smoothing change added approximately 0.02 ms at 1080p and 0.05 ms at
4K in the paired synthetic directional shader probe. Those numbers alone did not explain
the reported whole-frame slowdown.

Moonlight introduced a terrain shadow pass at night, where the previous renderer skipped
that pass. The pass originally submitted each selected section to every cascade and made
individual JNI calls for binds and draws. It now rejects empty/non-casting sections before
volume tests, uses a conservative per-cascade mask with a one-block model-overhang guard,
and submits the same ordered commands through the existing batch encoder. Resolution,
filtering, cascade overlap and shadow distances are unchanged.

JFR also identified a substantial pre-existing CPU hotspot in forward water: each draw
created/mapped/destroyed a 16-byte Metal buffer and water disabled command batching.
Those uniforms now occupy distinct 256-byte-aligned slices in the existing transient arena.
The arena retains them until GPU completion, allowing water to use command batching safely.

Results on Apple M4 Max, the same standard-world fixture, render/simulation distance 16/16,
and a requested 1920×1080 game framebuffer (the native presentation drawable remained
3840×2160). Half-resolution rendering was disabled. The unchanged shadow settings were
four cascades, resolution 1792 and shadow/caster distance 256. Values below average the two
local-lights-enabled captures in each interleaved OFF/ON/ON/OFF group:

| Scene | FPS before → after | Median CPU ms before → after | Native buffers/frame before → after |
| --- | --- | --- | --- |
| Night, no fixture light | 81.0 → 111.1 | 8.39 → 4.54 | 930 → 5 |
| Night, torch | 81.1 → 111.8 | 8.15 → 4.49 | 930 → 5 |
| Day, torch | 84.8 → 105.4 | 7.91 → 4.83 | 930 → 5 |

[Measurements](evidence/lighting-performance/measurements.json) retain phase counts and
timings. These are short controlled captures under the Fabric harness, not a claim about
every world or a complete rollback comparison with a previous commit.

Validation passed with `JAVA_TOOL_OPTIONS=-Dmetalcraft.checkedCommands=true ./gradlew build --offline`.
The live `metalLocalLightingTest` route with `-Dmetalcraft.verifyWaterBatch=true` and
`-Dmetalcraft.checkedCommands=true` retained torch/fire shadows, moving/extinguished light,
moon shadows and per-section water identity across two pools (2,473 and 6,433 marked pixels).
The Metal depth tests also exercise sparse cascade masks, including inactive intermediate
layers. Captures and the JFR recording remain local and ignored.

For a repeat cost probe, add `-Dmetalcraft.localLightingBenchmark=true` to `metalJvmArgs` on
the existing local-light test route. `metalcraft.lightingBenchmarkLabel` selects the report
name under `run/benchmarks`; `metalcraft.lightingBenchmarkScenes` narrows the scene list.
`metalcraft.lightingJfr=true` records the measured portion to `run/benchmarks/lighting-cpu.jfr`.
The `night_previous` diagnostic reproduces the old no-moon-shadow behavior only while that
benchmark is enabled; it does not alter normal play.

## Shadow stability follow-up

- [x] Reproduce camera-dependent self-shadowing on an unoccluded plane using production Metal filtering.
- [x] Check the optimized caster culling against the unculled depth result during camera motion.
- [x] Preserve exact terrain face axes before rotation/depth packing, with geometric/stored-normal fallbacks.
- [x] Keep receiver-plane correction active at grazing angles and treat empty depth texels as unoccluded.
- [x] Complete the live standard-world check and final build.
- [ ] Record the corrected shadows for review — blocked by the locked macOS session (no window frames).

The G-buffer packs view-space normals into two 8-bit channels. Turning the camera changes
the quantization error when that normal is decoded back into world space. The error was
then amplified by receiver-plane depth correction at grazing sun/moon angles, causing
flat, unoccluded terrain to alternately shadow itself. In the Metal regression, visibility
fell to 0.35–0.48 with the previous normal at light heights 0.081, 0.09 and 0.12.

Terrain now preserves exact axis-aligned face directions from chunk-local positions in the
unused roughness byte (tags 1=X, 2=Y, 3=Z), before camera rotation or depth packing can tilt
them. Other surfaces use a geometric normal from reconstructed receiver positions, falling
back to the stored normal at discontinuities or degenerate derivatives. Local-light shading
normals remain unchanged. Receiver-plane correction also stays active below the old 0.08
light-angle cutoff, and clear depth texels remain unoccluded when a grazing receiver plane
extends beyond the depth volume.

The Metal regression retains at least 0.999 visibility (half-float readback tolerance) across
128 camera orientations, four cascades and ten light elevations from 0.001 to 0.7, including
both sides of the old cutoff. It checks all three axis tags, packed-depth error, non-axis
surfaces and fallback behavior. No shadow samples, attachments, buffers or passes were added.

`ShadowCasterMotionSmoke` also compares every depth texel in all four cascades over 24
moving frames with mixed solid/cutout draws and changing sparse masks: optimized and
unculled results match. The batching, caster culling and transient water allocation fixes
remain in place. A suspected 90-degree light-basis switch was ruled out as the cause and
left unchanged; it permutes the square grid rather than explaining the reproduced acne.

Run the live motion check through the existing standard-save lighting route with
`-Dmetalcraft.shadowMotionTest=true`. Its floor, time and camera sweep are confined to the
existing test-world copy. CSV measurements are written under ignored `run/benchmarks`;
optional `metalcraft.shadowMotionRecordDir` coordinates a local Minecraft-window recorder.

The live 16/16 test found **0 false-shadow samples out of 28,577** across its camera sweep.
At time 12700 (light height 0.02299), preserving exact face axes reduced the derivative-only
result from 9,157 false-shadow samples to zero. Reproduce this case with
`-Dmetalcraft.shadowMotionTime=12700`; `metalcraft.shadowMotionLabel` names the captures/CSV.
The user also confirmed the flashing was resolved in normal play.
`JAVA_TOOL_OPTIONS=-Dmetalcraft.checkedCommands=true ./gradlew build --offline` passed.
Before the exact-axis fix, a paired synthetic derivative-normal resolve probe measured 0.2859 → 0.2917 ms at 1920×1080 and
1.0831 → 1.1066 ms at 3840×2160 (about 0.024 ms additional GPU work at 4K).
These isolate shader cost and are not whole-game FPS measurements.
Window recording was attempted through both ScreenCaptureKit's recording output and a
direct frame writer. The session reports `IOConsoleLocked = Yes`; the first route rejects
its first frame and the second receives none. Capture attempts remain local/ignored. This
does not prevent the Metal render-target readbacks used for visual assertions.
