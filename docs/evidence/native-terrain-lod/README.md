# Native terrain surface LOD — September 18, 2026

> **Superseded (2026-09-26):** historical record of a removed LOD prototype. The current system is
> described in [the distant terrain evidence](../distant-terrain/README.md).

Apple M4 Max, macOS 27.0, Java 25, Minecraft 26.2. Native Default graphics
backend reports Metal. NORMAL generated world, seed `metalcraft`, render/simulation
16/16, native Chunk Builder = Threaded (`PrioritizeChunkUpdates.NONE`).

```sh
./gradlew nativeTerrainLodSmoke
MTL_DEBUG_LAYER=1 ./gradlew build
MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest \
  -PmetalNativeTerrainLodTest=true --args='--graphicsBackend default'
```

The unit suite passes 540 randomized tier cases, all axes/windings, exact surface
coverage/material boundaries, original section-edge bytes, holes, overlays, custom
faces, alpha fallback, worker bounds, distance/zoom selection and hysteresis.
Uniform 256-quad surfaces reduce to 124, 76 and 64 quads at 2/4/8-block tiers.

The final live scenario takes 26.15 seconds (36 seconds including application
startup), with successful native uploads and clean world close. It checks a
16×16 stone slab in a generated world at native chunk offset 15, an edited/repaired
hole, persisted enable/disable, zoom from tier 4 to 2, approach from tier 4 to 1,
exact original index-count restoration, and retreat back to tier 4.

[Raw live report](report.json): the slab has 1,056 indices with LOD versus 3,456
without it: **69.44% fewer triangles and vertex bytes**. The 843 distant compiler
invocations process 317,005 quads and emit 215,480: **32.03% fewer quads**. These
counts include repeat builds and the fixture; they are not a steady-state visible
terrain census. The simplifier records 72.17 ms summed worker time across those
invocations, excluding native tessellation/upload. No CPU/GPU frame-time or FPS
improvement is asserted.

Inspected None-pack images:

- [Distant LOD](far.png): intact natural hills/forest, distant fixture present.
- [LOD disabled](off.png): native geometry restored.
- [Near detail](near.png): original slab texture/geometry restored on approach.

Standard pack has successful native mesh uploads but **fails visual acceptance**:
[LOD on](standard-on-unqualified.png) and [LOD off](standard-off-unqualified.png)
both show black sky/opaque terrain after settling. The JSON's `packs` field records
that both upload paths were exercised; it does not establish a visual pass. This
composition issue remains undiagnosed and is not presented as fixed by terrain LOD.
The test now explicitly labels its report as requiring visual inspection.

Earlier runs: an initial fixture assertion incorrectly expected full detail at
FOV 30 (the correct hysteretic result is cell size 2); a corrected 19.59-second
route passed but captured Standard before the longer settle/off comparison above.
No long-distance live stress test or performance benchmark was run.
