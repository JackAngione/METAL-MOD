# Compact horizon and hidden chunk costs

> **Superseded (2026-09-26):** historical record of a removed LOD prototype. The current system is
> described in [the distant terrain evidence](../distant-terrain/README.md).

September 21, 2026. Implemented on the direct Metal backend.

## Diagnosis

The previous shell renderer still used Minecraft's full chunk delivery, block/light
storage and section traversal across the selected render distance. The earlier
128/16 copied NORMAL-world census found **26,027 full client chunks**. It found no
far full-detail solid meshes, but did find **10,549,368 unchanged translucent fluid
indices**, versus 581,604 solid-shell indices. See
[the original investigation](../shell-performance/README.md).

## Change

Singleplayer now separates the horizon from native chunk residency. Raw render
distance controls camera/fog/model reach; ordinary client delivery, the view area,
and the integrated server's player-loading distance stop at native quality + 3.
Simulation distance remains unchanged. The overlap has direct section shells;
beyond it, immutable whole-column models contain only height intervals, atlas UVs,
colors, lighting and fluid identity. No LevelChunk, block palette, world reference,
entity or light-engine owner is retained by a baked model.

A server-owned sampler holds at most 16 horizon loading tickets and queues at most
64 detached snapshots. Sampling releases its ticket immediately after capture;
world reset, disable and shutdown also release ownership. Native generation
neighbors, save/unload work and simulation still retain server chunks as necessary.
A rejected `/setblock` in the first test, after releasing its fixture ticket,
confirmed that even the server source chunk had unloaded while its model survived.

Cached models are grouped in 8×8 column batches, normally one solid and one
translucent draw per visible group. Water has its own reduced geometry, sorted
indices and per-vertex metadata. Block mutations coalesce refreshes without hiding
the existing model. Camera handoff chooses one main-pass geometry owner. GPU
buffers retire after their last Metal submission completes. Moving beyond the
horizon removes models and empty groups; inactive GPU meshes are retired. Mesh
residency is capped at 256 MiB and cached columns at 262,144.

## Validation

- `MTL_DEBUG_LAYER=1 ./gradlew build --offline`: complete build passes in 20 seconds,
  including Metal shader/rendering checks and 600 randomized whole-column geometry
  fixtures. `build.log` includes deliberately rejected shaders from recovery tests.
- `MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest
  '-PmetalJvmArgs=-Xmx16G -Dmetalcraft.compactHorizonTest=true'
  --args='--graphicsBackend default' --offline`: NORMAL seed `metalcraft`, 128
  render / 16 simulation, native quality 4, Default/Metal.
- The live route asserts a distant model is selected while its complete client
  chunk is absent; effective native distance is 7 and full client chunks stay
  below 500. It checks a server edit changes the detached model, approach restores
  native geometry and removes proxy ownership, retreat unloads the native chunk,
  and disable/re-enable, resize and world close release/recover ownership.
- None and Standard water captures, a side view of falling water, and native
  underwater restoration were inspected. The final route passes in 28.29 seconds;
  `live.json` and `live.log` contain the full result.

## Performance interpretation

The live ABBA phases freeze model discovery, hold camera/coverage/GPU buffers fixed,
and alternate 32-quad submissions with grouped submissions of those same buffers.
The split mode is a diagnostic stand-in for fine-grained submission, **not an
unchanged historical build**. Both paths issue exactly the same index ranges,
including the original back-to-front ordering for translucent geometry. The test
rejects a phase if the model coverage or GPU upload counter changes.

Final fixed-geometry result: **628 visible model columns** at 1280×720, None pack,
Metal validation enabled, V-Sync off, with 40 ticks per phase and 10 warm-up frames.

| Mode | Average FPS, two phases | Median CPU frame, two phases | Command bytes/frame |
| --- | --- | --- | --- |
| Split identical buffers | 310.2 / 316.8 | 2.908 / 2.835 ms | 124,240 |
| Grouped identical buffers | 1,041.5 / 1,061.6 | 0.730 / 0.727 ms | 9,232 |

Mean of the phase medians: CPU frame time **2.871 → 0.729 ms (74.6% lower)**;
command bytes/frame **92.6% lower**. The earlier passed route in `initial-abba.json`
used 607 model columns and similarly reduced median CPU frame time by 76.6%.

This isolates submission overhead from visual simplification and unloaded terrain.
It does not establish a fully populated 128/256-distance gameplay frame rate. The
model horizon fills progressively; new terrain generation still competes for CPU.
Full client chunk residency is bounded independently of that progress.

The earlier `prototype.json`/`prototype.log` record 733 full client chunks at quality
10 and a 128 horizon. They predate registering the camera/fog horizon hooks and are
retained only as loading-lifetime evidence; their FPS and submitted model counts
are not evidence of complete visible horizon coverage.

## Limits

Remote multiplayer retains the native delivery path and direct section shells.
Independent remote world sampling needs server support or an already populated
persistent cache, neither supplied by this client-only change. Cached horizon
models are session-local and rebuild after resource reload. They intentionally
approximate trees, caves, overhangs, individual transparent blocks and flowing
water. Baked light samples refresh with terrain capture; the server's simulation
and native generation/unload dependencies are not removed.

## Inspected images

- [None](none.png)
- [Standard](standard.png)
- [Whole-column falling water at coarse LOD](waterfall.png)
- [Native underwater restoration](underwater-native.png)
