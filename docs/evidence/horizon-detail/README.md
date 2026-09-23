# Maximum-detail horizon coverage and performance — September 23, 2026

The original detail-5/F3 capture at 64 render distance showed 36 FPS and missing
distant terrain. Generation had completed successfully; rendering was the failure.

## Root causes and changes

1. One-block samples were emitted as individual caps/walls, including covered
   boundaries between columns inside the same resident group. `HorizonMesher`
   now forms the exact envelope union within each group and merges equal planar
   faces. Height, atlas UV, tint, light, material identity and winding must agree.
   Sample resolution and detail controls are unchanged. Group/native/missing-column
   boundaries stay closed. Water retains reverse faces and its metadata ABI;
   merged fluid quads are limited to 16 blocks for local transparency sorting.
2. The hard-coded 256 MiB allowance was too small for maximum-detail coverage.
   The new allowance is 1/16 of Metal's recommended working set, clamped to
   128 MiB–1 GiB, with a 256 MiB fallback if the driver reports no budget.
   This M4 Max reports 53,084 MiB, so the LOD cap is 1 GiB. That is a cap, not a
   preallocation. Retired in-flight geometry remains charged until safe to free.
3. Rejected meshes were rebuilt and uploaded up to four times **per frame**, then
   discarded. Admission now uses an exact cached geometry byte estimate before
   allocating Metal buffers. Coverage is installed first with closed coarse
   envelopes, then refined. Under pressure, coverage survives and off-screen
   geometry is retired. An unchanged rejected full-detail mesh incurs no repeated
   build/upload. Both tested final views refine entirely to detail 5.
4. Every group's visibility box formerly spanned y=-2048…2048, submitting many
   groups outside the actual terrain view. Conservative bounds now come from the
   sampled surfaces. Edits can expand them; stale bounds after removals remain
   conservative, so they cannot prematurely cull geometry.

## Same-world measurement

Apple M4 Max, macOS 27, Java 25, 16 GiB test heap. Fresh NORMAL world, seed
`metalcraft`, position (8.5, 210, 8.5), yaw/pitch 135/18, 64 render / 16 simulation,
native quality 20, reduction 2, horizon detail 5, 1920×1080, Standard shader pack,
Default/verified Metal, F3 visible. All 11,840 distant columns finish before
measurement; discovery then freezes and the camera/terrain stay unchanged.

The opt-in `metalcraft.horizonLegacyMesh` path reproduces the former geometry,
visibility bounds, fixed budget and repeated admission failures for comparison.
It runs inside the instrumented current build, not an untouched historical binary.
Its FPS therefore is not interchangeable with the earlier 36-FPS screenshot.
The fixed phase follows on exactly the same captured terrain. Both use the same
frame/GPU instrumentation. These are short stationary phases, not an all-world
or all-hardware performance guarantee.

| Metric | Diagnostic legacy path | Fixed path |
| --- | ---: | ---: |
| Average FPS | 22.01 | 107.66 |
| 1% low FPS | 17.61 | 78.39 |
| Median CPU frame | 43.87 ms | 8.72 ms |
| Render-thread allocation | 3,220.62 MiB/s | 412.45 MiB/s |
| Mesh rebuilds during measurement | 436 | 0 |
| Drawn / requested visible column candidates | 736 / 9,399 | 4,981 / 4,981 |
| Coarse fallback groups | 0 (missing geometry instead) | 0 |
| Charged horizon GPU memory | 253.97 MiB | 764.00 MiB |

Different candidate totals reflect the corrected height bounds. The fixed path
renders all actual candidates at the requested one-block detail. The lossless
reductions do not mean maximum detail becomes free: complete geometry needs more
residency than the incomplete old view. Native terrain, shaders and other game
work still account for the remaining frame time and allocations.

The fixed scene also records zero uploads during its stationary measurement.
After turning 180 degrees, all 4,819 new candidates reach full detail under the
same memory cap. The reverse screenshot was taken shortly after this recovery;
its instantaneous FPS is not a separate stationary benchmark.

[Raw final metrics](final.json), [live log](final-live.log).
Screenshots remain local and are excluded from Git: `before.png`,
`fixed-f3.png` (109 FPS), and `fixed-reverse.png`.

## Validation and limits

- Full `./gradlew build --offline` passes in 23 seconds, including Metal rendering,
  shader and resource-lifetime checks.
- CPU tests compare oriented per-unit face coverage and all material attributes
  against the old emitter at every supported cell size, plus randomized cross-column
  unions, masked native boundaries, fractional flat-water envelopes and per-vertex
  water identity/normal preservation. A uniform detail-5 8×8 group shrinks from
  110,592 individual quads to 326 equivalent quads; real varied terrain merges less.
- GPU-budget fixtures cover unknown, small, large and extreme reported capacities.
- The final NORMAL-world route passes full-detail coverage, stable builds/uploads,
  camera reversal, bounded ownership and disable/close. Both fixed screenshots were
  inspected for restored distant terrain and Standard-water continuity.
- The [first iteration](256mib-rejected.json) retained the old 256 MiB allowance.
  It preserved coverage with fallback geometry but correctly failed the zero-fallback
  maximum-detail gate. Its [log](256mib-rejected.log) is retained. The final memory-aware
  policy resolves that failure without reducing requested detail on this machine.
- This live qualification targets the reported 64-chunk failure. Full 128/256-distance
  detail-5 coverage and lower-memory Macs are not claimed; memory pressure can retain
  coarser complete coverage on those configurations. Water shader code is unchanged.

```bash
./gradlew nativeTerrainLodSmoke --offline
./gradlew build --offline
./gradlew runClient -PmetalLifecycleTest \
  '-PmetalJvmArgs=-Xmx16G -Dmetalcraft.horizonDetailTest=true' \
  --args='--graphicsBackend default' --offline
```

### Native and water distance 16 screenshot

September 23: the local, untracked `native16-water16-f3.png` uses the same NORMAL seed, position
(8.5, 210, 8.5), yaw 135/pitch 18 and 1920×1080 Metal view. Native quality and
Standard water detail distances are both 16 chunks; horizon is 64, detail 5,
simulation 16. Native loading includes the existing three-chunk handoff margin
(actual 19). Both requested settings persist in the local run configuration.

Capture checks pass: Standard active without error, 12,400 columns cached,
5,261/5,261 visible columns at full detail, no coarse fallback or admission skips,
zero generation failures. F3 shows 147 FPS (instantaneous, not an averaged benchmark).
See `native16-water16.json` and `native16-water16-live.log`. The first capture was
rejected because a non-finite sky input during startup disabled Standard; the final
capture activates Standard after camera initialization and explicitly checks its
active/error state. Temporary capture-only test edits were restored.
