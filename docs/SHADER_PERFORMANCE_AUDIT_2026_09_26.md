# Shader performance audit — 2026-09-26

Baseline: `49d4eda` on `metal-shader-engine`. Work is applied directly to the local
checkout. Preserve current output resolution, quality
settings, shadow filtering, lighting, water, and cloud appearance.

## Checklist

- [x] Inspect Standard shaders, local-light preparation, shadow rendering, and post submission.
- [x] Establish the unmodified build / Metal regression baseline (`./gradlew build --offline`: passed).
- [x] Remove cloud work whose existing contribution is exactly zero.
- [x] Compare original and optimized GPU output, including filter boundaries.
- [x] Measure paired GPU timings for representative cloud views.
- [x] Run the final build and focused Metal validation (`MTL_DEBUG_LAYER=1 ./gradlew build --offline`: passed).
- [x] Record findings, limitations, and review the final diff (`git diff --check`: passed).

## Scope

The existing tile-local opaque resolve, water filtering and opaque snapshots,
completion-retired resolve uploads, and shader-library reuse are already optimized.
This follow-up examines cloud noise and transparency work, plus CPU and GPU
candidates that require separate evidence before changing them.

## Implemented changes

Both changes are in `standard/shared/sky.metal`, inside `mc_sky_cumulus`:

1. Reject rays with `abs(ray.y) <= 0.02` before marching. The existing final
   `smoothstep(0.02, 0.10, abs(ray.y))` already gives these rays zero opacity.
   The previous `0.008` guard still marched some rays whose final contribution
   was zero. Derivatives remain ahead of the guard.
2. Skip relief noise, cloud lighting, haze, and accumulation when the computed
   sample alpha is exactly zero. The sample then adds zero to premultiplied
   accumulation. Every nonzero alpha retains the original calculation, sample
   positions, step count, and early-termination threshold. No derivatives occur
   in the skipped region. A prior opacity above the termination threshold cannot
   reach this skip: the previous contributing iteration would already have exited.

The retained production patch consists of two local edits. It
does not change shader options, defaults, cloud target dimensions, nonzero noise
bands, color math, shadow sampling, water, or render-pass structure.

## Regression checks

The frozen original sky source is retained in
`src/smoke/resources/sky-performance-reference.metal`. `CloudPerformanceSmoke`
compiles that source and the production source independently on Metal.

- **10,693,824 RGBA16 pixels match bit for bit**, across 198 cases: off/fast/fancy
  clouds, day/rain/sunset/night, two wind origins and opacities, below/inside/above
  cloud layers, horizon/upward/downward views, and 257×145 and 1024×576 targets.
- **32,768 shape/density cases match exactly** in float output, including values
  immediately below, at, and above each octave's filter boundaries. These helpers
  also supply terrain cloud shadows and remain unchanged.
- Correctness checks are part of `shaderTranslationSmoke` and therefore `build`.
  GPU timings are optional through `cloudPerformanceBenchmark`; timing noise does
  not fail the build.

Metal API validation also found an existing fixture ABI mismatch in
`LodShadingBenchmark`: its resolve camera buffer allocated 160 bytes while the
production `MCResolveCamera` requires 176 bytes including `cloudSettings`.
The fixture now allocates and zeroes the full 176 bytes, keeping clouds disabled
as intended and eliminating an out-of-bounds uniform read. The production world
adapter already used 176 bytes and needed no change.

The final full build passed with Metal API validation enabled, including shadow
filtering and cascade transitions, terrain/local lighting, HDR composition,
sky/celestials, water/reflections/underwater behavior, custom packs, reload, and
asynchronous upload lifetimes. No live game was launched: this patch changes only
cloud fragment control flow, and the checks render the production shader directly
with deterministic inputs. Gameplay FPS and a manual world visual inspection
were not measured. No screenshots are included in the changes.

Two broader candidates were rejected during the audit. Extracting filtered noise
into a conditional helper changed floating-point reassociation; moving cirrus
filter calculations changed one half-float rounding step in the larger-resolution
fixture. Moving the final cumulus fade earlier also changed rounding. The final
patch retains those original expressions and their placement. The comparison
thresholds were not relaxed to accept these candidates.

## Performance evidence

Device: **Apple M4 Max**. Production cloud fragment, RGBA16 target, 1024×576.
Each timing sample records 16 complete cloud draws, each with its own encoder and
attachment store, and reports GPU milliseconds per draw. Original/optimized order
alternates within each pair. Each scenario uses six warm-up pairs and 21 measured
pairs, repeated in three rounds. The table gives the last round's medians and the
change range across all three rounds of the final candidate.

| View | Original GPU ms | Optimized GPU ms | Change across rounds |
| --- | ---: | ---: | ---: |
| Upward, fancy | 0.9019 | 0.7770 | -13.8% to -11.9% |
| Horizon, fancy | 0.5759 | 0.5128 | -16.1% to -8.1% |
| Inside clouds, fancy | 0.8886 | 0.7213 | -24.2% to -17.4% |
| Above clouds, fancy | 0.7203 | 0.6442 | -19.0% to -8.7% |
| Rain, fancy | 0.7090 | 0.6409 | -12.5% to -9.6% |
| Upward, fast | 0.4955 | 0.4362 | -16.3% to -12.0% |

[Raw samples and source hashes](evidence/shader-cloud-performance/measurements.json)
identify the final measured shader. Earlier single-draw probes and broader
candidates gave unstable or regressive timings; they are not used for this claim.
The final candidate improved every scenario's median in every round. These are
cloud-pass measurements on one GPU, not whole-game FPS gains or a guarantee for
every camera, weather state, or Apple GPU.

## Further opportunities found, not implemented

| Area | Observed redundant work | What must be established before changing it |
| --- | --- | --- |
| World transform capture | `setRasterProjection` and `setRasterView` allocate/invert matrices before detecting repeated values. | Profile repeated bindings; cache exact source values and matrix properties while preserving per-frame reset, mutable inputs, invalid-transform handling, and pending-resolve flushes. |
| Local-light CPU preparation | `prepareWorld` builds a boxed retained-section set and revisits 343 section entries each frame. | Measure allocation/CPU cost; preserve chunk arrival/replacement, neighboring dynamic-shape invalidation, world switches, and origin shifts. |
| Local-light GPU uploads | Dirty scene updates rebuild the volume; local frame and sky frame use new immutable native buffers. | Profile upload frequency; reuse the established completion-retired arena only where its lifetime covers every consumer. Never overwrite an in-flight buffer. |
| Post executor | Separate option/underwater rings duplicate slot/fence work; labels and compute threadgroup arrays are rebuilt per pass. | Measure CPU contribution, then combine aligned payloads and cache immutable pass metadata. Keep custom-pack layouts, overflow retirement, and reload behavior. |
| Deferred lighting | Emissive or fully fogged receivers can perform shadow/local-light work later discarded by composition. | Preserve debug views and derivative quads before adding any per-pixel guard; benchmark coherent and mixed-material coverage. |
| Cloud filtering / cirrus | Fully filtered octaves and faded cirrus have further mathematically redundant work. | Preserve compiler rounding and demonstrate output equality plus a repeatable speedup; the tested rewrites failed the strict comparison. |

Lower precision, fewer samples, reduced shadow coverage, lower resolution, pass
fusion, and temporal reuse were excluded because they need a separate visual and
resource-lifetime evaluation. Existing local-light conservative bounds, shadow
receiver-plane correction, water filtering, and tile-local resolve remain intact.
