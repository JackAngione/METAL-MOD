# Functional LOD handoff — 2026-09-13

The user requested functional completion and deferred refinement. P1–P8's delivered
scope remains loaded exact-surface geometry, full-resolution reduced distant lighting,
and a bounded persistent explored opaque horizon. This checkpoint fixes a selection
handoff defect and validates the current source. It does not establish the deferred
P9 performance, dense-coverage, multiresolution or independent GLFW objectives.

## Boundary regression

The loaded square moves on horizontal chunk boundaries. Previously `needsView`
only refreshed position after more than eight blocks of movement, so a tiny step
could leave newly distant cached terrain unselected until further movement. It now
also compares the camera's X/Z chunk coordinates using floor semantics.

`./gradlew lodSmoke` fails before the fix in 2s with
`chunk-boundary crossing must select newly distant terrain: axis=0, direction=-1`.
After the fix it passes in 3s. The fixture uses the production store/cache and the
renderer's `needsView`/`view` protocol, with 0.02-block crossings on both axes in both
directions, including negative coordinates. It checks loaded ownership before the
crossing, selected distant ownership afterward, and unchanged throttling inside a
chunk. [Before](regression-before.txt), [after](regression-after.txt).

## Current-source validation

Apple M4 Max, 64 GiB, macOS 27.0, Java 25. Commands:

```bash
MTL_DEBUG_LAYER=1 ./gradlew build
MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest \
  -PmetalLodHorizonTest=true -PmetalLodHorizonToggleProbe=true \
  --args='--graphicsBackend default'
```

The complete build passes in 15s, including CPU LOD, native Metal, geometry/depth,
resource lifetime, cache and shader smoke coverage. [Build output](build.txt).

The short live route passes in 76s. It uses NORMAL terrain, seed `metalcraft`,
render/simulation distance 16/16, Default graphics API with verified Metal backend,
Standard pack, and Metal API validation. The inspected 32-chunk capture draws 722
represented sections in 97 layer draws, with farthest represented bounds at 467.20
blocks. Reported GPU payload is 73,694,656 bytes, within the 128 MiB cap. Cache drops,
failures, corruption and upload failures are zero. Disable waits for cache closure
and zero distant GPU charges; re-enable restores distant draws in the same world.
The client exits successfully. [Live output](horizon.txt), [metrics](horizon.json),
[screenshot](horizon-32.png).

The screenshot shows the explored mountain/forest patch; unexplored edges remain
absent. This focused route stops after the 32-chunk toggle check. It does not rerun
the earlier 64/128/256, edit, dimension, reload, clear-cache and persisted-reopen live
matrix, nor measure performance. Their prior evidence remains in P7/P8. A clean exit
here does not resolve the separately recorded intermittent GLFW crash.

[Base commit and input hashes](source.json) and [source patch](source.patch) identify
the implementation and regression fixture. The pre-existing user edit to AGENTS.md
is preserved and excluded from this patch.
