Native validation continuation, 2026-09-12

The original `MTL_DEBUG_LAYER=1 ./gradlew shaderTranslationSmoke` reproduces the texture-buffer/2D binding assertion. A shader-schema regression fails before the fix. Enabling SPIRV-Cross native texture buffers matches the existing Metal binding ABI. The next validation assertion comes from the shadow-filtering smoke fixture reusing its layered depth vertex shader for a 2D color output; it now has a separate ordinary vertex entry point.

`MTL_DEBUG_LAYER=1 ./gradlew build` passes in 17 seconds after both corrections, including real texel-buffer pixel checks and production shadow-filtering comparisons. Expected invalid-pack/extension recovery exceptions in the log are smoke fixtures, not validation failures. These runs are correctness checks, not performance measurements.

The same command passes in 16 seconds after adding horizon queue/store/residency high-water counters and repeated repair diagnostics. See `native-diagnostics-build-green.txt`.

`qualification.txt` records four negative report checks: missing presentation evidence, mismatched monitor attachment, missing GPU samples and unsettled terrain are all rejected. These are mutated-report checks, not an induced live lock test. The subsequent horizon capture also rejects focus acquisition after the Mac locks; its partial report is retained separately.

`glfw-analysis.md` records symbolication of the earlier cleanup crash against its exact local native library. The fault is a null monitor in a Cocoa video-mode operation; the initiating callback sequence remains unknown. The 16 clean current matrix exits do not prove that intermittent bug fixed.


Final preview validation (2026-09-12 evening): `MTL_DEBUG_LAYER=1 ./gradlew build`
passes after exposing opt-in settings (18s, `preview-build-green.txt`), after complete
preparation timing/settings-test coverage (19s, `final-build-green.txt`), and after
skipping disabled-idle LOD bookkeeping (18s, `idle-build-green.txt`). These builds
include native shader, world/LOD, resource-lifetime and GPU timing fixtures. Live
NORMAL-world horizon and UI evidence are under `../horizon-preview`, `../horizon-cost`
and `../settings-preview`. The shared intermittent GLFW cleanup investigation remains
open; these successful exits do not establish its root-cause fix.

The updated baseline-preparation script also compiles on a clean detached `f48460d` worktree in 1s (`baseline-reproduction-green.txt`). This verifies reproduction setup; it is not a GPU timing result.
