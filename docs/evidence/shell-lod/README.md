# Exterior shell LOD — September 20, 2026

The active native compiler now builds a coarse solid exterior from its immutable
section snapshot. It skips ordinary block-model tessellation for shell sections,
including cutout/custom models. A horizontal tile stores only its lowest/highest
occupied height while building; only the envelope faces are uploaded. There is
no retained coarse block volume, full block mesh or alternate geometry tier.
The old distant disk cache remains disabled.

Native quality distance is horizontal. Every enabled tier outside it uses a shell,
including during zoom. Levels 0 and master-disable restore native rendering. The
existing 2/4 tier IDs use 4-block shell tiles, and 8/16 use larger tiles. At most
16 tiles produce at most 160 quads per section; the coarsest isolated solid proxy
has six faces. Shared tile walls and fully buried section boundaries are omitted.
Partially covered boundaries remain closed against different tiers and native
neighbors. Native opaque-block visibility remains valid because the proxy contains
the source model blocks. Empty native meshes do not consume coarsening requests.

Materials use the current resource pack's particle sprite, biome particle tint
where present, directional shading and copied regional light. Untinted materials
use white tint. Small material details, cutouts, cavities and distant block entities
are deliberately omitted. Fluid tessellation, transparency sorting and water
metadata continue through the existing compiler. Any solid fluid vertices remain
the prefix of the combined solid mesh.

## Validation

- The complete Metal-validation build passes, including the existing GPU suite.
- `NativeShellSmoke` compares 320 randomized envelopes to an independent unit-face
  exterior oracle. It covers exact outward coverage without duplicates, strict
  bounds, occupied material samples, empty/buried sections, internal cavities and
  fully/partially covered neighbor boundaries.
- Native buffer fixtures verify byte-identical fluid prefixes, appended shell
  lifetime after arena growth/source release, draw counts and promotion from
  16-bit to 32-bit indices when the combined mesh crosses 65,535 vertices.
- Selection regressions cover every native radius from 1–256, zoom retaining
  shells, disable/level-zero restoration, and existing bounded rebuild supersession.
- The final NORMAL-world route at 128 render / 16 simulation, Threaded,
  Default/Metal, passes in **20.58 seconds**. The stone staircase's sampled section
  changes from **4,950 to 342 indices** (825 to 57 quads, **93.09% less geometry**).
  Approaching restores the exact native count in 0.049 seconds in this run.
  Retreat, radius expansion, level zero, lighting edits/removal, pixel-resolution
  toggle, odd-size resize and world close pass. See `live.json` and `live.log`.
- `shell.png` and `native-restored.png` were inspected: the same-camera staircase
  has broad simple steps and the natural landscape/foliage becomes coarse shapes;
  native block and leaf detail returns afterward. The restored image includes chat.

Initial live attempts exposed a missing null-tint fallback for stone. Minecraft
catches a compiler NPE as buffer-pool exhaustion, which hid the failure and stalled
uploads. A temporary diagnostic wrapper identified it and was removed. The final
compiler handles absent tint providers and reports any future shell NPE with
section context rather than allowing that silent retry behavior.

## Scope and limits

Shells use Minecraft's existing **16×16×16 section** culling/upload units, so a
vertical chunk column can contain several shell meshes. This is not a one-draw
renderer for the entire column. The shell budget excludes separately rendered
fluids. World chunks still load/generate and retain block data; simulation and
server storage are unchanged. The compiler still scans block states for native
visibility/fluids and shell extraction, but skips individual block-model geometry.
Large separated terrain across sections can remain separate surfaces. Near/far
changes are asynchronous and can visibly pop. No whole-game FPS, dense fully
loaded 128-distance memory result, or new Standard/water visual qualification is
claimed by this short None-pack route.

```sh
MTL_DEBUG_LAYER=1 ./gradlew build --offline
MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest \
  -PmetalGeometricLodTest=true -PmetalTerrainResolutionTest=true \
  -PmetalJvmArgs=-Xmx16G --args='--graphicsBackend default' --offline
```
