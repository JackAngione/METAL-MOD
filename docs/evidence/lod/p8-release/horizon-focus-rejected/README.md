# Rejected horizon capture, 2026-09-12

The fresh 128/256-chunk route with continuous presentation checks and repair/resource diagnostics exits nonzero in 1m49s. It creates and explores NORMAL terrain, verifies the same-world cache re-enable path and reaches the 128-chunk camera, then times out acquiring AppKit/GLFW focus before the first timed capture.

The desktop tool subsequently reports: “The Mac is locked and automatic unlock could not unlock it.” The attempt is not performance evidence. `client.txt`, `invocation.json`, `source.patch` and the partial `lod-horizon.json` preserve the failure. Images produced before the failure are listed in `images.json` and remain locally under `build/reports/lod-horizon-confirmed`.

The source patch is relative to `fade2e8`; its digest is `04158c0900bab1189ee94b3708dca34d4d7e162db63d40996f89fb48e8c0a8c9`. The complete native API-validation build passes on this source. Repeat with the Mac unlocked and Minecraft foreground before accepting new horizon timings.
