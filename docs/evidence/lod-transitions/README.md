# LOD approach restoration evidence

> **Superseded (2026-09-26):** historical record of a removed LOD prototype. The current system is
> described in [the distant terrain evidence](../distant-terrain/README.md).

September 18, 2026, Apple Silicon/macOS. One short NORMAL world (`metalcraft` seed),
render/simulation 16/16, Chunk Builder Threaded, Default graphics backend confirmed
as Metal, no shader pack, Metal API validation enabled. Route duration: 17.54 seconds.

The stepped fixture starts at 4,950 native indices and drops to 1,206 indices at
level 5 (4-block grid). With LOD still enabled, moving the camera inside the
four-chunk native radius restores exactly 4,950 indices in 0.049 seconds in this
run, then remains native through a 20-tick settling check. This timing is a single
fixture observation, not a general latency or FPS guarantee. Retreating restores
the coarse tier. Expanding the native-quality radius to 16 restores native detail;
returning it to 4 coarsens again. Level 0 restores native geometry too.

- [Far coarse screenshot](far-coarse.png): visibly broad steps.
- [Approach screenshot](approach-native.png): individual native block steps return while reduction remains 5.
- [Machine-readable report](report.json).

The two screenshots use different camera positions to demonstrate approaching;
they are not a same-camera image comparison. Both were visually inspected.

Queue regression checks also cover saturated distant work, finer-target
supersession, equivalent-request deduplication, completed/offscreen entries,
late coarse uploads, retry and world reset. Full `MTL_DEBUG_LAYER=1 ./gradlew build`
passes in 20 seconds.
