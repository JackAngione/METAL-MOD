# Environment-Driven Celestial and Local Lighting

## Summary

- Fix the confirmed root cause: MetalCraft omits Minecraft’s fixed −90° sky rotation, placing shadows 90° away from the visible sun.
- Freely revise or consolidate the existing uncommitted camera-relative cascade, 24-bit depth, and shader edits wherever needed for a coherent implementation.
- Add positional lighting for up to 256 vanilla or registered emissive sources, with the four highest-impact lights casting geometry shadows.

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

- Add a regression test comparing production celestial vectors with Minecraft’s sky transform at noon, sunrise, sunset, and midnight; the current sunrise case must fail with orthogonal directions.
- Cover independent sun/moon angles, weather, camera rotation, non-overworld dimensions, large-world coordinates, and cascade stability.
- Test emitter cache lifecycle, dynamic snapshots, provider validation/failure isolation, deterministic light caps, tile overflow, and shadow-slot hysteresis.
- Extend GPU smoke coverage with positioned and colored lights, distance falloff, block-light occlusion, all 24 local-shadow layers, four shadowed lights, and an unshadowed fifth light.
- Add a lifecycle scene with a known emitter and opaque blocker, asserting that illumination follows the source and occlusion darkens the receiver.
- Run `./gradlew shaderTranslationSmoke`, `./gradlew check`, the Metal lifecycle game test, and benchmarks for 0/64/256 local lights with 0/4 shadowed lights.

## Assumptions and Defaults

- Existing working-tree edits are fully editable and may be replaced when a cleaner implementation requires it.
- Vanilla 26.2 emitters receive complete built-in metadata; custom/modded emissive visuals require registered providers.
- Local shadows cover terrain and feature geometry supported by the existing shadow replay.
- Four 256×256 shadowed local lights is the default balanced profile; local lighting and local shadows remain independently adjustable.
