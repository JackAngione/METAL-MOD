# Native chunk distance validation — September 17, 2026

The active terrain path now extends Minecraft's native full-detail render distance
through 256. These checks establish distance correctness and a live boundary crossing;
they do not qualify dense full-detail rendering at 256.

## Reproduce

```sh
./gradlew nativeChunkDistanceSmoke
./gradlew runClient -PmetalLifecycleTest -PmetalNativeChunkDistanceTest=true -PmetalJvmArgs=-Xmx8G --args='--graphicsBackend default'
MTL_DEBUG_LAYER=1 ./gradlew build
```

The user explicitly requested a live render distance above 32 for this task.
The live test used 36 render / 16 simulation, a NORMAL world with seed `metalcraft`,
Default graphics selecting Metal on Apple M4 Max, and an 8 GiB JVM heap limit.

## Results

- Exact player-distance graph coverage at 33, 36, 64, 127, 128, 255 and 256, with every
  represented cell compared to independent minimum Chebyshev distances. Multiple
  players, movement, removals, teleport, queue cancellation, resizing and draining pass.
- The transformed render-distance option accepts 33–256. The actual client-information
  encoder/decoder keeps its vanilla layout and all later fields, with a maximum wire
  distance of 32. Minecraft 26.2 serializes local connections too; the integrated
  server uses its own full render distance for its in-memory local player.
- The native priority queue accepts 256 and the removal sentinel 258. Near work pops first.
- At 36/16, column `(35, 0)` arrived normally, beyond the native 32 boundary. A marker
  placed only after receipt produced a native solid-layer mesh with GPU buffer residency
  in the visible-section list. The column was outside simulation.
- 36 -> 33 unloaded the target; 33 -> 36 received and rendered it again. The world saved
  and closed normally. Legacy LOD capture and distant generation stayed inactive even
  with old saved LOD opt-in enabled. The scenario completed in 64.50 seconds.

[Machine-readable live report](live-36.json) · [Live frame](live-36.png).
The small cyan marker near the image center is in column 35, about 556 blocks from
the camera. Native terrain, fog and Metal composition remain in use. The frame is
supporting evidence; buffer residency and visible-section membership are asserted
by the test itself.

The final `MTL_DEBUG_LAYER=1 ./gradlew build` passed in 19 seconds, including
the distance smoke and Metal shader/rendering validation.

## Limits

No 256-distance world or renderer was allocated during validation. Complete native
chunks, section tracking and meshes have roughly quadratic distance costs; memory
and frame time at dense 256 remain unqualified. Geometry is full detail, with
block-resolution LOD deferred. Remote/LAN users remain subject to their server and
byte-compatible requested distance. Existing historical LOD performance figures do
not measure this architecture.

The final manifest also removes the two obsolete LOD camera/fog hooks. They were
no-ops at the tested 33/36 distances; removing them restores native clip/fog behavior
at lower distances too. No additional world run was needed for that removal.

## Distance-fog toggle — September 18

**MetalCraft Settings → Clear distance fog** is saved as `clearDistanceFog` and
defaults to Off. On Metal it moves the terrain cutoff and ordinary atmospheric fog
start beyond the loaded view; it keeps the sky/cloud fades independent to avoid
exposing the sky dome's edge. The environment-selection guards preserve native
fluid/status fog, weather (including rain fade-out) and boss fog.

The same NORMAL-world 36/16 test passed with Off -> On -> Off and a config reload
while On. Final visual inspection confirmed clear distant terrain, native sky
blending, and the restored distance fade. No extra chunks are loaded by this toggle.
The final Metal API validation build passed in 25 seconds.

- [Normal distance fog](fog-off.png)
- [Clear distance fog enabled](fog-clear.png)
- [Normal fog restored](fog-restored.png)
- [Final live report](fog-toggle.json)

The paired frames validate fair-weather appearance and persistence; this short
run did not repeat underwater, weather or status-effect scenarios.
