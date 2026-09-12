# Confirmed matrix, 2026-09-12

Command: `caffeinate -di python3 scripts/lod-release-matrix.py --phase-ticks 80 --output build/reports/lod-release-confirmed --resume`.
All 16 isolated cases exit successfully: Standard/None × native-fullscreen/1080p-windowed × full/half scene × experimental LOD off/on. Each case has three repeats of stationary, pan and traversal. These are repeats within a process, not independently relaunched A/B pairs.

M4 Max/64 GB, macOS 27.0, Java 25.0.4, NORMAL seed `metalcraft`, render/simulation 16/16, Default/Metal, API validation off for timing. Native drawable is 3840×2160; windowed drawable is 1920×1080. Scene dimensions are recorded independently. All 1,057 tracked chunks are received, light/render queues drain, and the camera pan is warmed before capture.

All 144 phases pass continuous AppKit/GLFW focus, visibility, stable monitor attachment and drawable checks: 11,808 checks, 172,383 frames, GPU coverage at least 99.656%, nominal sampled thermal state, zero upload failures. Total client execution is 1,954.9 seconds. This establishes the capture contract, not release performance acceptance or pixel parity.

Every `invocation.json` records the exact command, source digest, exit code and elapsed time. `source.patch` reconstructs the measured tracked source changes over `fade2e8`; the uniform source digest is `40f492acf1241c079ce3386415ecd9a1e39e0d4de3a8f137279383776504557d`. Additional horizon telemetry was applied only after this matrix completed and is absent from this source patch.

`summary.json` is produced by `python3 scripts/lod-release-summary.py docs/evidence/lod/p8-release/matrix`. It requalifies each raw report and compares matched off/on cases using `docs/evidence/lod/compare-benchmarks.py`. Values are medians of three repeat percentiles. Raw GPU samples, CPU work, acquire waits, repeat ranges, memory and camera samples remain in each `metrics.json`.

Four inspected images are retained: native Standard/full off/on and 1080p None/half off/on. Near terrain matches visually; distant coverage and player skins vary. Input triangle counts also vary slightly between otherwise matched processes, so these are not image-parity fixtures. `images.json` retains SHA-256 identities and local paths for all 16 original screenshots. Other screenshots and client logs remain under ignored `build/reports/lod-release-confirmed`.

The matrix saves 0.127–0.250% of distant triangles and increases median intervals in every tested configuration. Preparation p95 stays below 0.234 ms. Foreground checks do not guarantee unpaced presentation; inspect acquire waits and GPU/CPU spans before attributing a change to geometry. Full results and release limits: [LOD_PERFORMANCE_RESULTS.md](../../../../LOD_PERFORMANCE_RESULTS.md).
