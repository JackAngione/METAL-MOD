# Shader performance audit

Baseline: `14f9896` (`metal-shader-engine`), 2026-09-24. Scope: the current Standard
shader pack and its Metal submission path. Preserve all visual settings, output
resolution, material behavior, filtering, shadow coverage, and effects.

## Checklist

- [x] Inspect the current shaders, frame submission, and existing regression tests.
- [x] Run the unmodified build and Metal GPU smoke tests (`./gradlew build`: passed).
- [x] Skip water detail noise only when its existing footprint weight is exactly zero.
- [x] Skip terrain atlas samples discarded by enabled water shading, preserving derivatives.
- [x] Use completion-retired transient upload memory for deferred resolve uniforms.
- [x] Skip the world-grade depth snapshot for packs whose post passes never read depth.
- [x] Reuse a pipeline's Metal library when vertex and fragment source are identical.
- [x] Verify GPU output equivalence and measure the affected shader work.
- [x] Run regression checks and a short standard-world integration check where practical.
- [x] Review the final diff and move the completed changes to the local checkout.

Implementation committed as `b2ee0d2` and fast-forwarded to the local
`metal-shader-engine` checkout at `/Users/jackangione/Desktop/METAL MOD`.

## Findings and boundaries

The active path comprises cascaded terrain shadows; HDR atmosphere/celestials and
the existing cloud layer; opaque terrain/entity G-buffer writes with a tile resolve;
forward water with separate opaque snapshots for refraction/reflections; and world
grading before the hand/HUD. GLSL translation and native pipeline creation happen
through reload-aware caches. The selected changes target discarded GPU work,
unconsumed texture data, and repeated resource creation within those paths.

The opaque G-buffer and deferred resolve already share an encoder and tile-local
attachments. Preserve that architecture. Pass fusion, lower precision or resolution,
fewer shadow samples, coarser geometry, and reduced reflection quality are outside
this change because they could change visible output or introduce new artifacts.

Water detail computes several noise bands before multiplying by their screen-space
filter weights. Fully filtered bands contribute zero, but still hash and interpolate
noise. Compute the same weights first and omit only zero-weight work. The terrain
fragment also samples the atlas before enabled water replaces that sample with white;
the sample is unnecessary on that existing material path.

Deferred resolve currently creates three short-lived native buffers per invocation
for options, camera data, and fog. The command encoder already provides an upload
arena whose lifetime follows GPU frame completion. Reusing it removes resource
creation and destruction without rewriting memory that an in-flight frame reads.

World grading also copies the entire depth attachment before every post pass, even
though Standard's only post pass (`grade`) reads just scene color. Use the executor's
existing read set to allocate and copy depth only for packs that consume it. Water's
opaque color/depth snapshots have separate ownership and must remain intact.

Native shader programs commonly use the same complete source for both stage entry
points. Compiling it twice inside one pipeline creation is redundant. Reuse the
first library for equal source strings, retaining separate compilation for distinct
translated stages and retaining all entry-point validation. This reduces compilation
requests from two to one for those pipelines; it is a startup/reload improvement,
not a steady-state FPS claim.

## Validation and measurements

On Apple M4 Max, `./gradlew build waterFilteringBenchmark` passed, as did
`./gradlew shaderTranslationSmoke -PmetalPassMerging=false`. The regression suite
covers HDR color, water/reflection/debug behavior, shadow filtering, native pipeline
creation, reload, and GPU resource lifetimes. Added checks establish:

- 36,864 original-versus-optimized water-filter cases, across four quality tiers,
  horizontal/vertical faces, animation/space samples, and adjacent float values at
  filter boundaries. Maximum absolute difference: `5.9604645e-8`.
- Exact RGBA16 pixel equality against the original atlas-sampling path for enabled
  and disabled water, RGSS on/off, all nine debug modes, patterned mipmaps, and
  mixed water/glass geometry.
- Eight asynchronous resolve submissions retain their own uniform values while
  reusing exactly three arena allocations. Existing mixed-camera tests and new fog
  layout/padding/bounds tests pass.
- Standard's encoded and HDR grade outputs remain correct without a snapshot.
  A custom depth-reading pack retains pre-hand depth through subsequent depth
  clears, same-size reuse, odd resize, reload, pack switching, and close.

A short live client run loaded a copy of the existing `New World` standard save
using `--graphicsBackend default`, Standard shaders, and render/simulation distance
16/16. The log confirms the M4 Max Metal backend and world entry without shader or
rendering errors. This was a load/render sanity check, not a timed game benchmark
or a manual screenshot comparison. The temporary client was terminated after the
check; source saves and local settings were not modified.

The optional cost probe uses 65,536 compute invocations with compile-time high
detail. Each footprint has five warm-up pairs and fifteen measured pairs in
alternating order. Three runs were recorded in
[measurements.json](evidence/shader-performance/measurements.json). The third
run's medians and the change range across all runs are:

| Helper footprint | Original GPU ms | Optimized GPU ms | Change across three runs |
| --- | ---: | ---: | ---: |
| 0.0002 (all detail visible) | 0.0806 | 0.0821 | +1.2% to +1.8% |
| 0.04 | 0.0808 | 0.0559 | -31.1% to -30.8% |
| 0.2 | 0.0806 | 0.0380 | -53.3% to -50.3% |
| 1.0 | 0.0810 | 0.0199 | -75.5% to -71.2% |
| 16.0 | 0.0808 | 0.0121 | -85.0% to -82.4% |

The near case pays a small branch cost; partially/fully filtered detail is markedly
cheaper. These are isolated helper timings, not whole-water-pass or game FPS gains.
Absolute timings vary with GPU clock state, hence paired ordering. The unchanged
fine-detail formulas, resolution, and quality settings still apply.

Other deterministic work reductions are three native buffer creations/destructions
per resolve, up to nine discarded atlas fetches per enabled-water pixel (one without
RGSS), and one unused world-grade depth copy per Standard frame. At 3840×2160, that
snapshot alone occupies 31.6 MiB. Atlas fetch savings describe the source path;
the compiler may already eliminate some discarded fetches. No separate measured
FPS or reload-time claim is made for these changes.

## Candidates left for a measured follow-up

The post executor could combine its option and underwater uniform rings, and cache
pass labels/threadgroup dimensions. The world adapter could also cache matrix
inversions when bindings are unchanged. These are smaller CPU candidates, not
established bottlenecks. They are not part of this implementation checklist.

Further pass fusion, hardware comparison shadow sampling, reduced precision, and
SSR restructuring need separate output and performance evidence. This audit does
not trade quality for speed or change any user-facing defaults.
