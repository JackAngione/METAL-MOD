# Native chunk distance foundation

Replace the active cached opaque horizon with Minecraft's ordinary chunk pipeline,
extending render distance to 256 before adding any block-resolution LOD.

- [x] Trace 26.2 loading, delivery, compilation and rendering limits. The render
  option and ChunkMap cap at 32; player distance storage is signed-byte based,
  the generic distance graph cannot hold 256, and ticket priorities have a small
  fixed queue. ClientInformation serializes distance as a byte.
- [x] Extend the native render option and integrated-server loading distance.
- [x] Widen only player-loading distance bookkeeping; retain native tickets,
  throttling, generation, lighting, saving, delivery and simulation separation.
- [x] Disable legacy horizon generation and LOD replacement, including saved
  enabled preferences; expose ordinary render distance as the active control.
- [x] Verify graph movement/removal/multiple sources/resizing through 256,
  protocol compatibility, and a short NORMAL-world Metal run at 36/16.
- [x] Record evidence and current full-detail memory/qualification limits.

The user explicitly overrides AGENTS.md for this task: live tests use render/
simulation 36/16 and Graphics API Default (Metal), proving the old boundary is crossed. Tests of 256-distance bookkeeping do not generate a world
or allocate a 256-distance renderer. Dense 256 full-detail performance is not a
qualification target for this foundation.

## Implementation notes

- `Options` extends only the render-distance range; simulation keeps its native range.
- `ChunkMap` accepts up to 256. The integrated server already follows the client option
  and sends the native cache-radius packet (a VarInt).
- Only `PlayerTicketTracker` uses the integer-level graph. The natural-spawn, lighting,
  loading-status and simulation graphs remain native. At distances up to 32, propagation
  stays bounded to the original radius instead of building a 256-ring graph.
- Distance callbacks feed the existing player-loading ticket dispatcher. It retains
  the native four-job throttle and generation/status/light/save lifecycles. The priority
  queue now accommodates 256 and the removal sentinel. Extended local connections use
  the existing adaptive chunk-send quota and nearest-first selection.
- Minecraft 26.2 serializes settings even on local connections. Serialization clamps
  the advertised distance to 32, preserving the vanilla layout and other fields.
  `ChunkMap` uses the integrated server's full distance for the in-memory local player;
  remote/LAN players retain their own byte-compatible requested distance.
- The renderer is unchanged: native client chunks, dirty tracking, section compilation,
  culling, uploads, terrain layers and Metal draw submission all follow native distance.
- Legacy LOD capability gates are false and the old terrain-generation event loop is
  no longer registered. Legacy camera/fog horizon mixins are not registered either;
  clip and fog distances follow native render distance unless the explicit fog toggle
  below is enabled. Existing settings and disk
  caches are not deleted.

## Validation

- PASS: `./gradlew nativeChunkDistanceSmoke` — exact coverage/levels at
  33/36/64/127/128/255/256, multiple sources, movement, source removal, queue cancellation,
  teleport, shrinking/growing, complete drain, and remote wire bounds.
- PASS: live NORMAL-world test on Apple M4 Max, Default -> Metal, 36 render / 16
  simulation. Column (35, 0) arrived through ordinary chunk packets and its solid mesh
  was uploaded and visible. The target was outside simulation. Changing 36 -> 33 -> 36
  unloaded and rendered it again; world close completed. Scenario duration: 64.50 s.
- PASS: real option validation through 256; actual settings-packet encoding/decoding
  preserves all fields while limiting the wire distance to 32; the native ticket queue
  accepts priority 256 and removal sentinel 258, preserving near-first order.
- PASS: `MTL_DEBUG_LAYER=1 ./gradlew build` on the final code/manifest (19 s),
  including native-distance smoke, retained LOD unit fixtures, shader translation,
  GPU frame metrics and Metal validation.
- [Evidence, screenshot and scope](evidence/native-chunks/README.md).

## Distance-fog control follow-up

- [x] Add the persisted **Clear distance fog** toggle to MetalCraft Settings, default Off.
- [x] Push native cutoff and atmospheric haze beyond the loaded view on Metal, without
  changing chunk loading or the camera clip range. Preserve selected fluid/status fog,
  rain (including its fade), boss fog, fog color, sky blending and cloud-distance fade.
- [x] Validate Off -> On -> Off frames in the same NORMAL world at 36/16, persistence,
  and the final Metal validation build.

Follow-up evidence (September 18): the 36/16 NORMAL-world run passed with saved
Off -> On (config reload) -> Off, visible chunk 35 and shrink/reload/close. Paired
frames show clear terrain with native sky blending, and restoration of the original
fade. `MTL_DEBUG_LAYER=1 ./gradlew build` passed in 25 seconds. See the
[fog toggle evidence](evidence/native-chunks/README.md#distance-fog-toggle--september-18).
