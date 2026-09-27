# Direct fluid shells — September 21

> **Superseded (2026-09-26):** historical record of a removed LOD prototype. The current system is
> described in [the distant terrain evidence](../distant-terrain/README.md).

`NativeShellCompiler` now builds solid and fluid envelopes directly. Distant
compilation no longer calls the ordinary block/fluid compiler first. Water uses
the same 4/8/16-block horizontal grid as solid shells, capped at 320 quads per
fluid kind including reverse-wound underside faces. Lava remains in its native
material layer. Fractional source/flow heights and connecting vertical walls keep
the proxy closed; fully hidden neighboring boundaries are omitted.

The reduced mesh receives new water identity/normal/flow metadata and a matching
translucent sort state. Native opaque visibility is still computed from source
blocks; there is no distant block-entity extraction. Models and fluid materials
use the current resource pack's sprites and biome tint. Near compilation is unchanged.

Validation:

- The full Metal-validation build passes in 21 seconds (`build.log`).
- Pure checks cover exposed ocean caps and undersides, fractional heights and flow
  steps, buried water, thin pool wall closure, bounded faces and matching metadata.
- The NORMAL-world 128/16 Default/Metal route completes in 20.24 seconds (`live.json`).
  Its pond changes from 2,700 to 480 indices (82.22% fewer), with metadata matching
  the uploaded reduced mesh. Approaching restores the exact 2,700 native indices.
- None and Standard images were inspected (`none.png`, `standard.png`); the water
  surface is present and Standard shading routes onto the reduced pond.
- Existing solid geometry, lighting edits, radius/tier changes, resize and world
  shutdown checks pass in the same run.

This is geometry/integration evidence, not a whole-game FPS claim. The live route
does not exhaustively validate every waterfall, custom fluid, biome or underwater
scene. Full chunk residency and per-section submission remain the next goal work.
