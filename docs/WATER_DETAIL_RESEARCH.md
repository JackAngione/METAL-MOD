# Making fine water detail visible

Research date: 2026-09-10. Scope: Standard pack's direct Metal forward water path.
These are implementation recommendations, not a claim that the live renderer has
been visually validated.

## What established game implementations do

**Uru: Ages Beyond Myst.** Mark Finch separates geometric waves from higher-frequency
surface normals, derives normals from height derivatives, and reflects the eye vector
into an environment map. He notes that slope depends on amplitude relative to wavelength,
that a broad frequency distribution matters, and that moving a complex pattern as one
unit looks less natural than relative wave motion. He filters wavelengths unsupported
by mesh sampling. These principles remain useful; the historical multipass lookup
implementation is not a prescription for modern Metal.
[Primary source: GPU Gems, chapter 1](https://developer.nvidia.com/gpugems/gpugems/part-i-natural-effects/chapter-1-effective-water-simulation-physical-models).

**Pacific Fighters.** Yuri Kryachko combines four height-map scales for lighting and
uses the two largest for actual displacement. Fine waves are represented in per-pixel
normals. The article also proposes combining maps into a reusable render target and
emphasizes the value of HDR lighting. Its old hardware restrictions and statement about
FFT being impractical are historical, not current performance guidance.
[Primary source: GPU Gems 2, chapter 18](https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-18-using-vertex-texture-displacement).

**Left 4 Dead 2 / Portal 2.** Valve distorts normal-map coordinates using a spatial
flow field. Two overlapping animation phases hide resets; spatial noise reduces pulsing,
and offset layers reduce repetition. Normal strength varies with flow speed. This
replaces how normals are generated while retaining the surrounding water shader.
[Primary source: Alex Vlachos, Water Flow in Portal 2, slides 8, 17–39](https://cdn.fastly.steamstatic.com/apps/valve/2010/siggraph2010_vlachos_waterflow.pdf).

## Why the current changes can look almost identical

The following are findings from the checked source, before the current visibility fix:

- `mc_water_detailed_normal` adds 4/8/12 cosine slope bands; geometric clumps only
  modulate phase and amplitude. It does not differentiate the clump height itself.
  The result remains a warped stripe spectrum, rather than fully shaped noise relief.
- Its warp and envelope vary spatially, but the accumulated slope omits their
  derivatives. This is an artistic normal field, not the derivative of the full
  warped, amplitude-modulated height field.
- `mc_water_reflection` uses one constant `environment.rgb`. It never evaluates an
  environment using `reflect(-view, normal)`. Away from a sun highlight, normal changes
  affect only the Fresnel blend. At near-normal view angles this stays close to 0.02.
- The sun lobe is a bounded power term; it does not guarantee detail contrast when
  the view misses the highlight. Adding more frequency cannot fix an unresponsive
  reflection source.
- The isotropic footprint filter fades bands with small screen footprints. This is
  necessary, but makes a distant screenshot a poor proof that High added useful detail.

Sources: [shared/water.metal](../src/client/resources/assets/metalcraft/shaderpacks/standard/shared/water.metal)
(`mc_water_detailed_normal`, `mc_water_fresnel`, `mc_water_reflection`);
[gbuffer.metal](../src/client/resources/assets/metalcraft/shaderpacks/standard/gbuffer.metal)
(forward water branch invokes the detailed normal and debug mode 5 displays it).
These are source-level explanations; live routing still needs an explicit probe.

## Recommended adaptation to this renderer

These proposals apply the cited principles to this repository; they are not descriptions
of those games' exact shaders.

1. **Prove routing separately from beauty.** In the existing water-only normal debug
   mode, capture None and High with frozen time, camera, and exposure. Then capture
   the corresponding final color. A large normal difference and tiny final-color
   difference identifies a shading visibility issue; identical normal captures point
   toward settings, stale shader compilation, wrong shader selection, or routing.

2. **Give normals something directional to reflect.** Evaluate a restrained sky model
   with the reflected direction: horizon/zenith variation and a bounded sun-adjacent
   region, using the existing environment, sun, and skylight inputs. Keep the cave and
   dimension gates. This is an approximate environment, so avoid inventing detailed
   reflected clouds absent from the actual sky. A real sky probe is a later upgrade
   requiring texture ownership and refresh policy.

3. **Generate actual irregular height slopes.** Use a periodic smooth geometric/value
   height field with a few separated scales. Return its analytic value and gradient,
   or use central height differences for an initial correctness reference. Combine
   gradients before normalizing. If height coordinates are warped, apply the chain
   rule through the warp; if amplitude is clump-modulated, include the envelope
   derivative. For a horizontal face, use `normalize(float3(-dh_dx, 1, -dh_dz))`;
   vertical faces need their existing tangent basis.

4. **Respect the requested shared direction.** Use `q = worldPosition - velocity*time`
   for all scales. Noise in q controls shape and strength without per-pixel random
   time jumps. This is coherent transport, not a full fluid simulation. If later
   adding variable local speed, use bounded, phase-blended distortion like Valve's
   approach; naive `position - noise(position)*time` stretches indefinitely.

5. **Spend detail where it survives.** Keep None as the broad baseline. Low adds
   clearly visible irregular relief, Medium adds small ripples, and High adds the
   finest resolvable bands. Use existing `dfdx`/`dfdy` positions to filter before
   summation. Measure High/None final-color contrast at close and grazing views;
   do not merely assert a larger band count. Keep an explicit slope budget so finer
   scales do not turn the water into rough plastic.

6. **Treat sun and displacement as separate decisions.** A finite, roughness-aware
   microfacet sun term can improve glints, but must be tested through HDR grading
   for sparkle and clipped peaks. Do not use stronger glints to conceal missing
   environment response. True fine displacement needs finer mesh sampling and a
   shoreline/chunk-boundary strategy; current block faces cannot represent tiny
   ripples geometrically. Start with height-derived normals and preserve stable
   water edges.

Procedural analytic gradients avoid a new texture binding but increase fragment ALU.
A reusable periodic slope/normal texture offers mip filtering and amortizes generation,
but adds allocation, sampling, reload, and lifetime work. Benchmark before choosing a
compute-generated map or FFT; neither is required to establish visible irregular detail.

Validation should use a standard world, default Metal engine, 16 render / 16 simulation
distance, a camera roughly 1–2 blocks above the surface, and matched downward and grazing
angles. Store water-mask/normal/final-color evidence alongside the normal lifecycle checks.
