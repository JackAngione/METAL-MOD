# Shader color contract

Source audit: 2026-09-05, Minecraft 26.2 mapped sources and the direct Metal backend.
This records PR 7b's transfer prerequisite. The live renderer still uses the PR 7a
8-bit compatibility path; the floating-point world composition and GGX work remain open.

## Observed path

| Boundary | Source evidence | Consequence |
| --- | --- | --- |
| Atlas | `TextureAtlas.createTexture` allocates `RGBA8_UNORM`; `MipmapGenerator` uses `ARGB.linearToSrgbChannel` when constructing mip colors | Atlas RGB is treated as encoded color by the asset pipeline. The Metal texture mapping is plain `MTLPixelFormatRGBA8Unorm`, so sampling does not decode it. Alpha is coverage. Arbitrary resource packs may supply different artistic encodings. |
| Lightmap | `Lightmap` allocates `RGBA8_UNORM`; `assets/minecraft/shaders/core/lightmap.fsh` combines sky/block/ambient/night vision, clamps, and mixes in `notGamma` using `BrightnessFactor` | This is a bounded, brightness-adjusted artistic multiplier. It is neither isolated sunlight nor a known sRGB encoding of radiance. Applying an sRGB decoder to this multiplier is not a justified physical conversion. |
| Geometry | Vanilla `terrain.fsh` multiplies sampled color by vertex color, then applies visibility fade and fog. Standard `gbuffer.metal` similarly multiplies atlas, tint and lightmap, then writes fogged `scene` | The current seed and fog arithmetic operate on legacy color values. `shared/lighting.metal` recovers and shadows this seed in that same space. Neither path establishes linear lighting. |
| World target | `MainTarget` selects `GpuFormat.RGBA8_UNORM`; `RenderTarget` allocates that format | Values above 1 are lost before present-time grading. A floating-point post target alone cannot recover them. |
| Grade | `standard/grade.metal` applies exposure and optional ACES fit directly to sampled scene; `pack.json` selects `bgra8_unorm` for `post_color` | Grading now executes at the world seam before hand/HUD. It remains a legacy color operation, not a linear HDR tonemapper. Default exposure 1 / tonemap none preserves the seed. |
| Present | `mc_presentation_pipeline` returns the linearly filtered source sample; `MCMetalSurface` selects `BGRA8Unorm` and explicitly assigns `kCGColorSpaceSRGB` | Neither the fragment program nor the pixel format performs an sRGB output transfer. The layer tells the compositor to interpret the already encoded bytes as sRGB. Physical display validation remains open. |

Mapped sources are in the project's Loom `minecraft-clientOnly-043a8b3edf-26.2-sources.jar`;
vanilla GLSL is in the cached 26.2 client jar. Backend evidence is in
`src/native/metalcraft.m`; Standard sources are under
`src/client/resources/assets/metalcraft/shaderpacks/standard/`.

## HDR implementation contract

1. Keep the legacy path unchanged until the entire world composition path can switch
   coherently. Do not add a decoder to the existing present-time grade: the input has
   already been shaded, fogged, blended and clipped, and contains the HUD.
2. Treat conventional atlas/tint/overlay/fog RGB as encoded color at the new lighting
   boundary. Use explicit sRGB transfer helpers for those RGB values. Do not transform
   alpha, light levels, roughness, material IDs, normals or depth. Define any special
   resource-pack color interpretation separately.
3. Preserve Minecraft's lightmap/brightness semantics as an explicit compatibility
   policy. Decoding the unfogged legacy seed can provide a linear representation of
   that appearance, but does not recover physical illuminance or undo earlier clipping.
   The GGX change must define its sun/indirect energy split rather than separately
   decoding and multiplying the lightmap and claiming vanilla parity.
4. Write lighting to `RGBA16_FLOAT`. Apply emission and fog in linear space. Convert
   every participating forward source to the same space before alpha blending; sky,
   clouds, translucent terrain, entities, particles and Fabulous intermediate targets
   require coverage. Widening only the deferred target does not satisfy this contract.
5. Tone map the composed world once at `WorldComposition.PACK_POST`, encode its RGB
   once, and composite later hand/overlays/HUD according to the documented world seam.
   Keep the final UNORM presentation copy free of another shader transfer. Before
   shipping, explicitly establish and visually validate the layer's display color space;
   source inspection alone cannot establish the compositor's actual display output.

`shared/color.metal` supplies finite, nonnegative sRGB RGB transfer functions, with
negative input clipped to zero and values above 1 preserved. They do not tone map,
sanitize NaN/Inf, or alter alpha. They are intentionally not included by the live pack
until its color-space transition is implemented.

The lighting smoke compiles the actual helper file and checks an `RGBA16_FLOAT`
readback against independent numeric references: black/midgray/white, values on both
sides of each transfer knee, encoded and linear round trips, negative inputs, alpha,
and values 2 and 4. This proves helper behavior and floating-point attachment storage;
it does not prove HDR survives live forward composition or presentation.


2026-09-05 W2 update: the live world-only grading seam is now wired. Pack execution
and a stored depth snapshot precede hand-depth clear; present only copies the finished
main scene. World and forward storage/math are still legacy 8-bit. The remaining HDR
contract above is unchanged; the seam is not evidence of live HDR or display calibration.


W2 preparation (2026-09-05): Standard `grade.metal` now has an explicit
`MC_SCENE_LINEAR_HDR` variant for a future linear producer. It applies exposure and
optional ACES before sRGB encoding; display inversion follows encoding. The live host
does not enable this variant. `HdrCompositionSmoke` tests real RGBA16_FLOAT store/load,
two coverage-blended forward draws with linear fog, and the actual grade shader against
independent CPU values. This extends isolated transfer tests to synthetic composition;
it does not satisfy the live HDR routing requirement. Build and the legacy standard-world
water/HUD fixture pass; detailed logs and progress are in `WATER_EFFECTS_PLAN.md`.


W2 host mechanics (2026-09-05): FrameBindings now explicitly distinguishes legacy
encoded input from LINEAR_SRGB. Standard's executor compiles both grade variants and
selects the latter only for the explicit linear contract. Stored HDR world targets and
an independent UNORM output handoff are available through MetalGpuDevice; pipeline
format variants preserve shader semantics rather than inferring color space. GPU host
fixtures pass, but the live world graph still uses the legacy path. Forward source
linearization and Fabulous conversion must precede activation.


W2c opaque color preparation (2026-09-05): Standard now has opt-in linear seed,
chunk-fade, fog and deferred fog-reconstruction semantics under `MC_SCENE_LINEAR_HDR`.
The completed unfogged vanilla seed is decoded as the documented lightmap/brightness
compatibility policy; alpha and metadata are unchanged. Production shared functions
pass independent GPU references in both legacy and linear variants, including values
above 1. Build and the standard-world water/exposure/HUD fixture pass; evidence is in
`WATER_EFFECTS_PLAN.md`. Live activation, full linear geometry/forward/Fabulous coverage
and actual display validation remain open; the HDR gate is still unchecked.
