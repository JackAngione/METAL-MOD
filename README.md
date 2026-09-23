# MetalCraft

MetalCraft is an in-development, client-side Fabric renderer for Minecraft Java Edition 26.2. Its target is a **direct Apple Metal backend**:

```text
Minecraft / Blaze3D → MetalCraft Metal backend → Apple Metal
```

> **Development warning:** `0.2.0-dev.1` now loads and renders a singleplayer world through the direct Metal backend on the tested Apple M4 Max, without initializing Vulkan or MoltenVK. Cloud rendering, Retina presentation orientation, resize, fullscreen configuration, resource reload, screenshot capture, world close, and shutdown have passed validation, but the backend remains experimental and is not release-ready.

## Current status

- Builds against Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.157.0+26.2, Loom 1.17, Gradle 9.5.1, and Java 25.
- Compiles and packages an arm64 Objective-C bridge linked directly to `Metal.framework`.
- Verified that the direct bridge opens the Apple M4 Max `MTLDevice` and reads its recommended working-set size without Vulkan.
- Owns `MTLDevice` and `MTLCommandQueue` instances through typed, use-after-close-checked Java objects instead of exposing Objective-C pointers.
- Attaches an owned `CAMetalLayer` to a GLFW Cocoa view and supports resize, drawable acquisition, command-buffer presentation, and ordered teardown.
- Owns initial `MTLBuffer`, `MTLTexture`, texture-view, and `MTLSamplerState` wrappers with recursive device teardown.
- Supports scoped unified-memory mappings, GPU buffer blits, padded-row texture staging/readback, and cross-queue `MTLSharedEvent` fences.
- Supports non-blocking Metal timestamp query pools and pins every encoded drawable, buffer, texture, fence, pipeline, and query pool until its command buffer completes.
- Compiles GLSL to Vulkan SPIR-V with shaderc, translates it to MSL with SPIRV-Cross, and links separate vertex/fragment Metal libraries into owned `MTLRenderPipelineState` objects.
- Preserves one flattened Blaze3D resource-slot ABI through GLSL, SPIR-V, and MSL so vertex and fragment stages bind uniform buffers, textures, and samplers consistently.
- Binds typed texel-buffer uniforms as aligned Metal texture-buffer views, including the signed `R8` cloud-face data used while loading a world.
- Converts Blaze3D vertex bindings into Metal vertex descriptors and maps color/write-mask/blend, depth/bias, cull/winding, polygon, topology, texture, depth, and stencil formats through one exhaustive adapter.
- Encodes scoped color/depth render passes with scissor state, vertex/index bindings, direct instanced draws, both Blaze3D multi-draw layouts, and GPU-driven indirect draws.
- Implements Minecraft's `GpuBackend`, `GpuDeviceBackend`, `GpuSurfaceBackend`, resource, fence, transient-memory, command-encoder, and render-pass contracts over Metal.
- Registers Metal before OpenGL when the saved selection is `Default` on Apple silicon; Minecraft's otherwise-unconditional Vulkan availability probe is skipped on this path.
- Loads and renders a standard generated singleplayer world through Metal, including cloud rendering, correct framebuffer and Retina-window orientation, critical shader compilation, resource reload, resize, fullscreen configuration, screenshot capture, world close, and ordered shutdown, with no Vulkan or MoltenVK initialization in the launch log.
- Explicit OpenGL/Vulkan selections, Minecraft's crash recovery, and the `--graphicsBackend` launcher override remain authoritative.
- Adds a dedicated **Video Settings → MetalCraft Settings** screen. Its half-resolution option renders at macOS logical resolution while keeping the Metal drawable at native Retina size, reducing the rendered pixel count by 75% before presentation upscaling.
- Extends **Video Settings → Render Distance** to **256 chunks** using Minecraft's ordinary integrated-server loading, generation, lighting, chunk delivery, section compiler, and Metal draw path. **Distance-based terrain LOD**, enabled by default in MetalCraft Settings, reduces distant opaque surface geometry and texture/light detail while keeping nearby blocks sharp. Simulation distance stays independent. See [the native terrain LOD plan](docs/NATIVE_TERRAIN_LOD_PLAN.md).
- Provides the `metalcraft-shaders` Fabric entrypoint and reload-aware pipeline precompilation API for shader add-ons.

No performance number is promised yet. The Metal path removes reliance on Apple's deprecated OpenGL implementation, but “optimal” needs repeatable frame-time measurements. The benchmark scenario renders ordinary generated terrain at the display's native resolution and 16 chunks, and validates that it is drawing a real world before reporting; see [docs/APPLE_SILICON_PERFORMANCE.md](docs/APPLE_SILICON_PERFORMANCE.md) for the methodology and current numbers, and [ROADMAP.md](ROADMAP.md) for tracked work.

```bash
./gradlew runClient -PmetalLifecycleTest -PmetalLifecycleBenchmark=true
```

The scenario defaults to the primary display's native resolution and 16-chunk render/simulation distances. Each phase is captured three times and reported with its median and spread; override with `-PmetalBenchmarkRepeats`. Other knobs are `-PmetalBenchmarkResolution=3840x2160`, `-PmetalBenchmarkRenderDistance`, `-PmetalBenchmarkSimulationDistance`, `-PmetalBenchmarkPhaseTicks`, and `-PmetalBenchmarkSeed`. Use `-PmetalBenchmarkPitch=30`, `-PmetalBenchmarkPack=none` (or `standard`), `-PmetalBenchmarkUnlocked=true`, and `-PmetalBenchmarkHalfResolution=false` to make those conditions explicit. Repeats return to the same starting camera and record actual camera positions and sampled heap/Metal allocations. Results are written to `run/benchmarks/metalcraft-<backend>.json`. Fresh terrain generation takes a few minutes.

Enable **Video Settings → MetalCraft Settings → Clear distance fog** to keep distant terrain clear in fair weather. This saved toggle applies immediately on Metal, pushing both distance cutoff fog and atmospheric haze beyond the loaded view. It defaults to Off; fluid, weather, boss and status-effect fog remain.

Use Minecraft's ordinary **Video Settings → Render Distance** control for the extended view, up to **256 chunks (4,096 blocks)**. With terrain LOD enabled in singleplayer on Metal, this controls the model horizon. Full client chunks and the native renderer are limited to **Native quality distance + 3 chunks** for handoff. Simulation distance is independent and unchanged. Distant models survive after their source chunks unload.

**MetalCraft Settings → Native quality distance** keeps nearby terrain at full detail (**1–256 chunks**, default **4**). Outside this radius, the overlap area uses bounded section shells. Farther out, each 16×16 chunk column becomes one compact terrain envelope, with up to 16 height tiles and a separate simplified fluid surface. There are no retained block palettes, entities, light engines or interior block meshes in these cached column models. Up to 64 columns share each Metal mesh to reduce draw submission overhead.

The integrated server samples distant chunks with at most 16 concurrent loading tickets, then releases them. Native generation dependencies and the simulation area still need full server chunks; this is not a claim that the server holds only 16 chunks. Discovery progresses outward in the background and is not instantaneous at large distances. Block edits refresh the model, resource reloads rebuild it, and moving beyond the horizon discards cached columns. GPU mesh residency is capped at 256 MiB. Multiplayer retains ordinary server delivery and bounded section shells because this client mod cannot independently sample a remote server's world.

**MetalCraft Settings → LOD detail reduction** controls progression from **0 (Native detail everywhere)** to **5 (Extreme)**; **3 (Balanced)** is the default. Distant tiles become 8 or 16 blocks wide; very distant columns use one six-face solid envelope, plus a two-sided water envelope when present. Coarse atlas colors, biome tint and sampled lighting preserve the broad terrain appearance. Caves, small openings, overhangs and individual foliage deliberately become approximate silhouettes. Water and lava also use simplified geometry; water retains translucent sorting and Standard-pack metadata. Native block and fluid geometry return on approach. Level 0 or disabling terrain LOD restores ordinary chunk loading out to the selected render distance.

[Loading, geometry, performance and validation evidence](docs/evidence/compact-horizon/README.md).

**MetalCraft Settings → Distant Pixel Resolution** now shades distant solid terrain in actual half/quarter-width-and-height Metal targets (one quarter/one sixteenth of the scene pixels), following the selected LOD tier. It is enabled by default and currently supports the **None** shader pack. Nearby terrain stays full resolution. Reconstruction uses native geometry for exact coverage and depth, with ordinary shading at missing samples and depth discontinuities. The setting restores full pixel shading immediately when disabled, without rebuilding geometry. Other shader packs, cutout foliage, translucency, entities and UI keep their existing rendering. Targets follow the current scene size, including global half-resolution mode, and are reused across frames.

A short NORMAL-world test at **128 render / 16 simulation distance**, Default/Metal, confirms real quarter-resolution chunk draws, resize, toggle and approach restoration. A separate 4K covered-plane probe using Minecraft's terrain material measured **22.1% lower GPU time** for quarter resolution with RGSS filtering, including target stores and reconstruction. Half resolution was 7.0% slower in that case, and both scales were slower with the cheaper non-RGSS material. This is a material-cost probe, not a whole-game FPS gain; the setting is provided for direct comparison. [Evidence, limits and reproduction](docs/evidence/native-pixel-lod/README.md).

Above 32 chunks, render-section and dirty-state bookkeeping now allocates entries on demand, and camera moves visit resident entries. Large visibility lookup tables use sparse pages. This avoids eagerly creating millions of bookkeeping objects for terrain that has not arrived. LOD scheduling also avoids boxed keys and temporary positions; compatible Metal terrain pipelines reuse resource bindings. In a 128-distance CPU fixture, dirty-tracker setup allocation fell from 69.7 MB to 25 KB and repositioning 4,356 resident entries took 0.034 ms versus 4.338 ms for the eager native store. These are bookkeeping measurements, not whole-game FPS or total RAM figures. [Validation and limits](docs/evidence/high-distance-cleanup/README.md).

The distance limit is a functional limit, not a performance or memory guarantee. Full chunks and fully populated section storage still grow roughly with the square of distance. Dense 256-distance play remains unqualified and can exceed available memory. Native generation and full world-chunk storage still occur. Shells avoid distant block-model tessellation and reduce uploaded geometry; they do not remove world data or simulation costs. Changing render distance does not raise simulation distance. Generated world chunks are saved normally. Remote multiplayer remains limited by the server; the vanilla client-information packet advertises at most 32 to avoid its signed-byte overflow. This client-only mod does not extend an unmodified remote server's generation radius.

The former LOD preview's measurements and implementation notes remain in [the historical LOD plan](docs/LOD_FEATURE_PLAN.md) and [results](docs/LOD_PERFORMANCE_RESULTS.md); they do not qualify the current native pipeline. Native distance validation is tracked in [docs/NATIVE_CHUNKS_PLAN.md](docs/NATIVE_CHUNKS_PLAN.md), and current surface reduction and validation in [docs/NATIVE_TERRAIN_LOD_PLAN.md](docs/NATIVE_TERRAIN_LOD_PLAN.md).

```bash
./gradlew nativeChunkDistanceSmoke
./gradlew runClient -PmetalLifecycleTest -PmetalNativeChunkDistanceTest=true -PmetalJvmArgs=-Xmx8G --args='--graphicsBackend default'
```

The dedicated test uses a standard generated world at **36 render / 16 simulation** (an explicit task-specific override of the usual 16/16 test policy). It checks a column 35 chunks away through native delivery, GPU upload and visible-section selection, then tests shrinking and restoring distance. It also checks option and wire behavior through 256 and native priority-queue boundaries without allocating a 256-distance world. The headless distance smoke verifies all represented cells against exact multi-source distances through 256, plus movement, removal, teleportation and resizing.

## Install

1. Install Minecraft Java Edition 26.2 and Fabric Loader 0.19.3 or newer.
2. Install Fabric API for 26.2.
3. Copy `build/libs/metalcraft-0.2.0-dev.1.jar` into the instance's `mods` directory only for development testing.
4. Leave **Video Settings → Graphics API** on **Default** and restart Minecraft.
5. Confirm `Using graphics backend Metal` and `MetalCraft direct renderer active` in `logs/latest.log`.

On Retina displays, enable **Video Settings → MetalCraft Settings → Half-resolution rendering** to trade native-pixel sharpness for substantially lower GPU load. The setting applies immediately and is saved in `config/metalcraft.json`.

For emergency recovery, select OpenGL in Minecraft, pass `--graphicsBackend=opengl`, remove the mod, or add `-Dmetalcraft.disable=true` to the JVM arguments.

## Build and run

```bash
./gradlew build
./gradlew runClient
./gradlew runClient -PmetalLifecycleTest --args='--graphicsBackend default'
```

The development artifact is written to `build/libs/metalcraft-0.2.0-dev.1.jar`.
On Apple silicon, `build` also runs `shaderTranslationSmoke`, which validates translation, Vulkan coordinate conversion, resource-slot preservation, and typed texel-buffer sampling, then exercises vertex descriptors, indexed rendering, multipass load behavior, readback, and pipeline creation on the active Metal device. The lifecycle command creates a standard generated singleplayer world and validates resize, fullscreen configuration, resource reload, screenshot output, world close, and shutdown. Its screenshot is written under `run/screenshots/`.

The direct backend can create a translated pipeline with:

```java
MetalRenderPipeline pipeline = device.createRenderPipeline(
    new MetalRenderPipeline.GlslDescriptor(
        expandedVertexGlsl,
        "example/example.vsh",
        expandedFragmentGlsl,
        "example/example.fsh",
        MetalTexture.Format.BGRA8_UNORM,
        MetalTexture.Format.DEPTH32_FLOAT
    )
);
```

Sources passed here must already have `#moj_import` or other includes expanded. Translation diagnostics retain the supplied source names.

The bundled **MetalCraft Standard** pack includes an atmospheric Overworld sky,
drifting cumulus and high cirrus clouds, sunrise/sunset color and halos, and weather
lighting. Custom round sun and moon models add solar limb darkening, lunar surface
detail, and all eight moon phases, with Minecraft's celestial paths and stars.
Select Standard in
**Video Settings → MetalCraft Settings**; the ordinary **Clouds** setting controls
Off/Fast/Fancy quality. Cloud shading uses a half-resolution Metal target.
See [sky rendering and validation](docs/SKY_RENDERING.md).

## Shader add-on pathway

Shader add-ons should stay inside Blaze3D instead of issuing raw OpenGL, Vulkan, or Metal calls. Backend-neutral pipelines use the active Metal device while remaining compatible with Minecraft's OpenGL recovery path.

An add-on declares an entrypoint:

```json
{
  "entrypoints": {
    "metalcraft-shaders": [
      "com.example.MyShaderExtension"
    ]
  }
}
```

It then registers backend-neutral pipelines:

```java
public final class MyShaderExtension implements MetalCraftShaderExtension {
    @Override
    public void registerShaders(MetalCraftShaderContext context) {
        RenderPipeline pipeline = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("my_addon", "pipeline/example"))
            .withVertexShader(Identifier.fromNamespaceAndPath("my_addon", "example"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("my_addon", "example"))
            .build();
        context.registry().register(pipeline);
    }
}
```

Place sources at `assets/my_addon/shaders/example.vsh` and `assets/my_addon/shaders/example.fsh`. MetalCraft precompiles registered pipelines after startup and after shader-resource reloads. Registration alone does not draw geometry; a later shader module can retrieve the pipeline from `MetalCraftShaders.registry()` and use Fabric/Blaze3D render hooks to submit it.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for design boundaries and [ROADMAP.md](ROADMAP.md) for tracked work.
