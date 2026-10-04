# Water snapshots and distant shadows

- [x] Capture opaque water inputs only for prepared water draws that consume them; retain snapshot-free water shading.
- [x] Add a cheaper distant cast-shadow tier with a smooth transition from detailed shadows.
- [x] Verify snapshot routing, distant shadow coverage/filtering, reload and resource lifetimes on Metal.
- [x] Run the build and a brief existing standard-world check at 16/16; record results.

Water: shader-disabled and dry frames skip the copies. Underwater and Fabulous use
a separate cached pipeline without opaque texture bindings; enabled waves and
environment reflections remain. Explicit depth diagnostics still capture. The
Metal shader suite passed, including pixel equality with/without snapshot bindings
for above-water fallback and submerged water.

Distant shadows: retain the configured detailed cascades, then blend into one
512×512 (or smaller) cascade with four depth comparisons instead of sixteen.
`distant_shadow_distance` defaults to 256 blocks; values at/below the detailed
distance disable the extra tier. The tier uses loaded terrain casters and does not
load extra chunks or generate distant terrain. It preserves the existing lightmap,
material and fog composition. Fine cloud shadows fade out with the detailed tier.

Validation:

- Metal API validation and the shader regression suite passed. Actual rendered
  caster depth reaches the production tile resolve at 128 and 200 blocks, with
  continuous handoff samples from 80 through 280 blocks. Cave light, emissive
  surfaces, partial fog and a missing distant frame preserve their contracts.
- The live test completed in an 18-second Gradle run using a disposable clone of
  `New World (4)`, a NORMAL generator, the default Metal backend and 16/16 distances.
  Above-water capture, disabled-water copy suppression, snapshot-free underwater
  shading, distant-frame creation, reload and disable passed. Water captures were
  inspected; the terrain-facing capture did not establish distant visual quality.
- Logs and screenshots remain under ignored `run/diagnostics/` and
  `run/screenshots/`. The original save and player settings were preserved.
- Final `MTL_DEBUG_LAYER=1 ./gradlew build --offline` passed, including the coarse
  four-tap filtering edge-motion check, and `git diff --check` passed.

The distant map adds at most 2 MiB (Metal's array binding uses two physical layers,
one active) and one depth pass. Distant receiver filtering uses four comparisons
rather than sixteen, with no cascade overlap or procedural cloud-shadow sampling
outside the detailed range. These are bounded work reductions, not a measured
whole-game FPS claim; cost depends on caster geometry and screen coverage.
