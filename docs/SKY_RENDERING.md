# Standard sky rendering

The bundled **MetalCraft Standard** shader pack renders its Overworld sky directly
through Metal. Select it in **Video Settings → MetalCraft Settings → Shader pack**.
Minecraft's **Clouds** setting controls the new formations: Off disables them, Fast
uses a six-sample cumulus volume, and Fancy uses twelve samples plus high cirrus.

## Implementation

- The atmosphere uses the extracted biome sky color, terrain fog color, sun angle,
  and rain brightness. It adds a deeper zenith, horizon haze, a circumsolar halo,
  warm sunrise/sunset scattering, and a violet band opposite the sun.
- Cumulus uses periodic three-dimensional noise in a shallow 96-block volume above
  the dimension's cloud height. Rounded tops, flatter bases, directional density
  shading, warm twilight lighting, and denser/darker rainy formations replace the
  block cloud mesh. A separate sparse, warped noise field supplies high cirrus.
  At grazing angles the volume smoothly becomes a distant sheet; unresolved noise
  octaves fade to their mean to prevent horizon banding and crawling pixels.
- The procedural atmosphere/cloud assets are `standard/shared/sky.metal`; celestial
  sphere shading is in `standard/shared/celestials.metal`, with entry points in
  `standard/sky.metal`. Everything ships with the mod; no runtime downloads are needed.
- Custom analytic sphere models replace the vanilla sun/moon sprites. The sun has
  limb darkening, a tight aureole, and warmer, dimmer emission near the horizon.
  The moon wraps NASA LROC color and LOLA elevation maps onto its surface, with
  crater bump relief, regolith-style shading, a curved phase terminator, earthshine,
  horizon tint, and daylight atmospheric scattering. All eight Minecraft phases
  and independently extracted sun/moon angles remain authoritative.
- Minecraft's original stars draw after the atmosphere, followed by the custom
  celestial models and then clouds. The whole lunar sphere occludes stars, including
  its unlit side; clouds attenuate both celestial models. Disc edges are antialiased
  at scene resolution. Angular diameters are about 4° (sun) and 4.8° (moon), deliberately
  larger than real life for readability at ordinary Minecraft fields of view.
- The two bundled 1024×512 lunar maps are packed into one linear RGBA8 Metal texture
  with a complete mip chain (~2.67 MiB) on pack load. Explicit footprint filtering
  stabilizes surface detail at the limb and small screen sizes. See
  [asset provenance and credit](../src/client/resources/assets/metalcraft/shaderpacks/standard/textures/CREDITS.md).
- Camera rays use the final projection after view bob, hurt, and screen effects,
  with Minecraft's Metal raster orientation. A homogeneous far/near point difference
  removes view-bob translation from the sky direction while retaining rotation and
  FOV changes. Normalizing a near-plane position instead caused the sky to swing
  when walking. World-space advection is calculated
  in double precision and wrapped over complete noise periods before GPU upload.
- Cloud shading runs at half scene width and height in one reusable RGBA16F target.
  The target follows resize and half-resolution rendering. Clouds use premultiplied
  linear blending before the existing Standard exposure/tone-map/output transfer.
  Fast uses fewer volume samples and omits cirrus; Off skips both cloud passes.
- The vanilla cloud draw is suppressed only after a successful sky draw in the
  same HDR session. Fabulous retains its framegraph-cleared cloud target for its
  later transparency composition, and main color/depth samplers follow the HDR
  session's attachment identity mapping. Other packs, unavailable HDR, the Nether/End,
  fluid fog, and sky-blocking status effects retain the existing rendering path.
- Shader reload, pack changes, failure, and device close retire the sky pipelines,
  sampler, lunar texture, and cloud target. Uniform uploads remain immutable until the GPU finishes.

The atmosphere and cloud lighting are artistic approximations. Clouds are composed
as a sky background before terrain; this does not implement cloud shadows on the
ground, terrain intersections inside clouds, or a multiple-scattering simulation.
The bounded step count, early opacity exit, and reduced target limit work; they are
not a claim of a measured whole-game frame-rate improvement.

## Custom celestial models

- [x] Implement native Metal sphere shading for a limb-darkened sun and cratered moon;
  preserve extracted Minecraft positions, all eight moon phases, and the star field.
- [x] Verify circular opaque silhouettes, all eight phases, correct waxing/waning,
  rain attenuation, HDR solar limb darkening and rear-hemisphere culling with GPU readback.
- [x] Inspect sun/moon in a short NORMAL-world Metal run at 16/16 and complete the build.
  Full build passed (23 seconds); the single live sweep passed (36 seconds), including
  golden-hour sun, full/quarter moon, stars, cloud modes, Fabulous, reload, and resize.

## Validation checklist

Walking stability regression:

- [x] Cancel view-bob translation when reconstructing sky directions, retaining rotation and FOV.
- [x] GPU comparison with translated walking projections and a short NORMAL-world 16/16 walking check.
  The regression failed before the fix (mean HDR difference 0.156 with an 0.08-block
  bob translation) and passes afterward. The player walked 7.51 blocks with bobbing
  enabled; the sun moved at most 2.14 pixels from normal view rotation, within the
  8-pixel allowance. See [walking validation](evidence/sky/walking-validation.txt).

- [x] Metal compilation and GPU readback: finite HDR output; transparent cloud-off
  output; gaps and dense formations; sunset warmth; darker night and rain; correct
  premultiplied composition; above-layer camera fixture.
- [x] Correct the native sky/vanilla raster orientation and visually inspect the
  sky against the original celestial objects and generated terrain.
- [x] NORMAL generated world, Default/Metal, 16 render / 16 simulation distance:
  day, sunrise, sunset, night, rain, Off/Fast/Fancy, reload, resize, and pack restore.
- [x] Final Fabulous image check and final build after the HDR sampler correction:
  `./gradlew build` passed, including the sampled main-color/depth regression;
  the final live sweep passed with 170,448 changed upper-frame pixels when toggling
  clouds. Visually checked the final day, dawn, dusk, night, and Fabulous captures.

Evidence from 2026-09-22 on Apple M4 Max: [validation log](evidence/sky/validation.txt),
[day/Fabulous](evidence/sky/day-fabulous.png), [sunrise](evidence/sky/sunrise.png),
and [sunset](evidence/sky/sunset.png). Custom models: [sun in game](evidence/sky/sun-in-game.png),
[full moon in game](evidence/sky/moon-in-game.png), [quarter moon in game](evidence/sky/moon-quarter-in-game.png).
Close GPU fixtures use a 12° vertical field of view: [full moon](evidence/sky/moon-full-detail.png)
and [crescent](evidence/sky/moon-crescent-detail.png). These are direct renderer outputs.

Reproduce the GPU fixtures with `./gradlew shaderTranslationSmoke`; their previews
are written to `build/reports/sky/`. Run the short live sweep with:

```sh
./gradlew runClient -PmetalLifecycleTest -PmetalSkyTest=true --args='--graphicsBackend default'
```

The test waits for visible terrain meshes, checks that clouds visibly change the
upper frame, rejects black day/twilight screenshots, and saves each view under
`run/screenshots/*metalcraft-sky-*.png`. It restores the selected shader pack, cloud
setting, and transparency setting on exit.

For only the walking regression, add `-PmetalSkyMotionTest=true` to that command.
It uses a short walkway inside the same generated NORMAL world, enables view bob,
checks the sun across four walking frames, and restores the original bobbing setting.
