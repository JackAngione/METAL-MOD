# Environment-Driven Celestial and Local Lighting

## Summary

- Fix the confirmed root cause: MetalCraft omits Minecraft’s fixed −90° sky rotation, placing shadows 90° away from the visible sun.
- Freely revise or consolidate the existing uncommitted camera-relative cascade, 24-bit depth, and shader edits wherever needed for a coherent implementation.
- Add positional lighting for up to 256 vanilla or registered emissive sources, with the four highest-impact lights casting geometry shadows.

## Implementation Status

Last updated: 2026-08-30, including the completed local-lighting working tree and frame-owned GPU data fix.

### Completed

- Corrected the celestial direction to match Minecraft's `Y(-90 degrees) * X(angle)` sky
  transform. Sun and moon angles are evaluated independently, the highest above-horizon source is
  selected, camera rotation and rain brightness are applied, and celestial lighting and shadow
  replay are disabled outside `Skybox.OVERWORLD` or when fully weather-obscured.
- Integrated the existing camera-relative, large-world-stable cascade construction and 24-bit
  G-buffer view-depth reconstruction with the corrected celestial state. Deferred GGX lighting and
  volumetric tint/strength now consume the same selected source and weather intensity.
- Added the public local-light foundation: `MetalCraftLights.registry()`,
  `MetalCraftShaderContext.lights()`, `MetalCraftLightRegistry`, `MetalCraftLightProvider`, and the
  immutable validated `MetalCraftLocalLight` value.
- Added provider-scoped stable IDs, duplicate-provider and duplicate-light-ID rejection, atomic
  per-provider collection, failure isolation, and recovery reporting.
- Added the render-side `MetalWorldLighting` module and live frame-extraction publication for
  registered visual lights. It performs frustum-sphere visibility testing, double-to-camera-relative
  conversion, deterministic impact ranking and tie-breaking, the global 256-light cap, overflow
  accounting, and immutable frame snapshots. Registered visual lights are marked to bypass the
  vanilla block-light envelope.
- Preserved external shader-pack behavior: the new local-light data is not bound into external
  pack passes, so packs retain their authored lighting path.
- Added an incremental per-chunk cache for every vanilla block/fluid state reporting emission. Chunk
  load, predicted and server-verified block replacement, unload, and world replacement update it
  atomically. Vanilla spectral
  families have curated colors and unknown emitters use a warm-white fallback.
- Added per-frame extraction for burning and intrinsically luminous entities plus luminous held,
  dropped, and item/block-display stacks. These sources share deterministic IDs with the registered
  provider path.
- Added conservative 16x16 CPU tile projection, a strongest-first 64-light tile cap, overflow
  telemetry, submission-owned GPU light/tile slices, and first-party-only resolve bindings.
- Replaced the isotropic warm block term with colored positional GGX contributions and smooth radius
  falloff. Static vanilla sources use the G-buffer block-light value as their reach/occlusion
  envelope; visual dynamic and registered sources bypass it.
- Added four stable-ID local shadow slots with replacement hysteresis, a configurable 24-layer
  `depth32_float` target, six-face replay for every active slot, cube-face PCF sampling, and union
  frustum terrain preparation. Celestial and local replay use independent instance multipliers.
- Made celestial matrices, local-shadow matrices, and local-light/tile data submission-owned through
  the transient frame arena. Each binding preserves its aligned slice offset, so later camera frames
  cannot overwrite data still being consumed by an asynchronous Metal command buffer.
- Added `local_lights`, `local_light_strength`, `local_shadow_count`, and
  `local_shadow_resolution`, plus local-light-count and local-shadow debug views. External packs do
  not bind or replay local-light resources.
- Added a controlled local-light GPU benchmark covering the six 0/64/256-light by 0/4-shadow
  profiles and writing `build/reports/local-lighting.json`.

## Implementation Changes

- Derive celestial direction from Minecraft’s exact sky transform. Use independent sun and moon angles, select the above-horizon source, apply camera rotation and weather intensity consistently, and disable celestial lighting outside `Skybox.OVERWORLD`.
- Introduce a world-lighting module owning emitter collection, camera-relative conversion, deterministic ranking, tiled light lists, GPU buffers, local shadow resources, and resolve binding.
- Track static block/fluid emitters incrementally on chunk load, block changes, unloads, and world changes. Supply curated vanilla colors with a warm-white fallback for unknown blocks reporting nonzero emission.
- Collect dynamic emissive entities, burning entities, luminous held/dropped/displayed items, and registered emitters during frame extraction, then publish an immutable render snapshot.
- Add `MetalCraftLights.registry()` and `MetalCraftShaderContext.lights()`. `MetalCraftLightRegistry.register(Identifier, MetalCraftLightProvider)` accepts providers emitting stable `MetalCraftLocalLight` values with ID, world position, RGB color, intensity, radius, and shadow eligibility.
- Build bounded 16×16 screen-tile lists on the CPU: retain the 256 strongest visible lights and at most 64 lights per tile. Perform attenuation, colored GGX lighting, and accumulation per fragment on the GPU.
- Replace the isotropic warm block-light term with positional contributions. Use Minecraft’s block-light value as an occlusion/reach envelope for static emitters; registered dynamic visual lights bypass it.
- Render the four selected shadow lights into a 24-layer `depth32_float` array—six 256×256 faces per light—with stable-ID hysteresis. Generalize shadow instancing so celestial cascades use four instances and local lights use six per active shadow caster.
- Add `local_lights`, `local_light_strength`, `local_shadow_count` (0/2/4), and `local_shadow_resolution` (128/256/512) options, plus local-light-count and local-shadow debug views.
- Keep external shader packs compatible: the new resources are optional, and packs that do not consume them retain their authored lighting behavior.

## Test Plan

- [x] Add a regression test comparing production celestial vectors with Minecraft’s sky transform at noon, sunrise, sunset, and midnight; the former sunrise formula is explicitly verified as orthogonal.
- [x] Cover independent sun/moon angles, weather, camera rotation, non-overworld dimensions, large-world coordinates, and cascade stability.
- [x] Test immutable dynamic snapshots, provider validation/failure isolation, provider/light ID uniqueness, deterministic light caps and tie-breaking, overflow accounting, registered envelope bypass, visibility, and large-world camera-relative conversion.
- [x] Test static emitter-cache lifecycle, tile overflow, and shadow-slot hysteresis.
- [x] Extend GPU smoke coverage with positioned and colored lights, distance falloff, block-light occlusion, all 24 local-shadow layers, four shadowed lights, and an unshadowed fifth light.
- [x] Regress frame ownership at the production submission seam: consecutive submissions must bind distinct celestial, local-shadow, light, and tile GPU slices.
- [x] Run the Metal lifecycle scene and image validation with the static-emitter cache and local-shadow replay active.
- [x] Run `./gradlew shaderTranslationSmoke` and `./gradlew check` for the completed milestones.
- [x] Run the Metal lifecycle game test and controlled benchmarks for 0/64/256 local lights with 0/4 shadowed lights.

## Assumptions and Defaults

- Existing working-tree edits are fully editable and may be replaced when a cleaner implementation requires it.
- Vanilla 26.2 emitters receive complete built-in metadata; custom/modded emissive visuals require registered providers.
- Local shadows cover terrain and feature geometry supported by the existing shadow replay.
- Four 256×256 shadowed local lights is the default balanced profile; local lighting and local shadows remain independently adjustable.
