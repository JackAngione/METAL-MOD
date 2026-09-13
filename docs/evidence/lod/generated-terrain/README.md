# Single-player terrain generation — 2026-09-13

Single-player LOD can now fill unseen terrain outward from the stationary player.
Enable terrain LOD, disk caching, a horizon above 16, and **Generate distant terrain**.
Generation defaults on but does no work with the default disabled LOD/16-chunk horizon.
Remote servers continue using received-terrain caching; there is no custom packet,
seed inference, server installation or change to the multiplayer connection protocol.

## Implementation

- Constant-space outward chunk rings, clipped to the horizon and world border.
- One 3×3 neighborhood held by loading-only, nonpersistent tickets. The scheduler
  calls `getChunkFuture` off the server thread: the mapped public method would
  managed-block if called on that thread. Vanilla owns generation and world saves.
- One server-thread snapshot and worker mesh at a time. Copies include the section's
  one-block halo, block states, biome references and light values. Workers retain no
  mutable level/light/chunk through the snapshot and use the current block models.
  Only opaque/cutout model output enters the existing Metal terrain cache/pipelines.
- Low/Balanced/High space snapshots across 4/2/1 server ticks and bound new request
  admission. New work backs off above 45 ms mean server tick time or cache queue
  pressure. Cancellation drains an old request before admitting another neighborhood.
- Snapshot revisions cannot overwrite newer received meshes or cross a cache epoch.
  Missing invalidated sections get a bounded repair pass before column completion.
  A drained revision rollover preserves disk data instead of clearing a large cache.
- Generation off, LOD off, disconnect, dimension/resource/cache changes revoke work.
  Paused-menu cancellation explicitly schedules server-thread ticket release; it
  does not rely on level ticks continuing while paused. Server stop releases references.

Generation adds saved chunks to the world. The LOD disk cap does **not** cap Minecraft
region-file storage. The horizon fills progressively, subject to existing disk-file,
mesh, result and upload budgets; dense 128/256 coverage is still unqualified. Distant
fluids remain absent. An exhausted sweep does not continuously regenerate evicted
columns; movement/settings/cache changes can start a new sweep. Existing explored
horizon benchmark/compatibility fixtures explicitly disable this new workload so
their original scenarios remain reproducible.

## Validation

Apple M4 Max, 64 GiB, macOS 27.0, Java 25:

```bash
MTL_DEBUG_LAYER=1 ./gradlew build
MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest \
  -PmetalLodGenerationTest=true --args='--graphicsBackend default'
MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest \
  -PmetalLodSettingsTest=true --args='--graphicsBackend default'
```

The [native validation build](build-final.txt) passes, including CPU LOD, native
geometry/depth, resource lifetime and shader checks. New CPU fixtures cover complete
nonduplicated 16/32/64/128/256 rings at negative coordinates, setting migration and
persistence, generated/received write races, cancelled/pre-clear snapshots, and a
bounded revision rollover preserving all disk leaves.

The [final live route](live-acceptance.txt) passes in 27s on NORMAL seed `metalcraft`,
16/16, Default/verified Metal, Standard pack, with Metal API validation. Player position
stays at (-1535.5, 236, -127.5); target chunk (-113, -25) remains absent from
`ClientChunkCache` before and throughout generation and after LOD residency/drawing.
The route asserts the target is not simulated while tickets are active. It interrupts
**nine active tickets** in the pause menu, verifies zero tickets without unpausing,
resumes generation, waits for a complete persisted column and resident geometry,
then checks stop/disable/close. Cache drops/failures/corruption and GPU upload failures
are zero. Disable reaches zero distant GPU bytes. [Metrics](metrics.json),
[inspected screenshot](generated.png). The frame reports other received cached terrain
as well; its total section/draw counters are not attributed solely to the generated column.

The [settings test](settings.txt) passes in 13s: generation-off persistence, keyboard
generation toggle, small-window scrolling, reset/back and pack independence.
The initial 22s test failure was a fixture error: mapped `ClientLevel.hasChunk` returns
true unconditionally. The corrected assertion uses the actual `ClientChunkCache`.
[Initial failure](live-initial.txt). Intermediate successful runs are retained separately;
`live-acceptance.txt` is the final active-ticket pause/cancel/resume proof.

Multiplayer compatibility is source-verified by the integrated-server gate and absence
of new network hooks; no external server session was run. This is functional validation,
not a dense-horizon performance or lower-memory-hardware qualification.
[Source hashes](source.json) and [source patch](source.patch) identify the final changes;
the pre-existing AGENTS.md edit is excluded.
