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
- Adds **distant terrain (LOD)**: with it on (the default) in singleplayer, **Video Settings → Render Distance** is the total view, up to **1024 chunks (16,384 blocks)**, while only the **native distance** (default 12) is loaded, simulated and meshed as ordinary chunks. The rest is drawn from compact background-built models. With it off, Render Distance extends ordinary chunk loading to **256 chunks**. Simulation distance stays independent. See [the distant terrain evidence](docs/evidence/distant-terrain/README.md).
- Provides the `metalcraft-shaders` Fabric entrypoint and reload-aware pipeline precompilation API for shader add-ons.
- The Standard water shader now bends blocks beneath moving water more noticeably and reduces scene transmission by about 10% through a one-block-deep layer.

No performance number is promised yet. The Metal path removes reliance on Apple's deprecated OpenGL implementation, but “optimal” needs repeatable frame-time measurements. The benchmark scenario renders ordinary generated terrain at the display's native resolution and 16 chunks, and validates that it is drawing a real world before reporting; see [docs/APPLE_SILICON_PERFORMANCE.md](docs/APPLE_SILICON_PERFORMANCE.md) for the methodology and current numbers, and [ROADMAP.md](ROADMAP.md) for tracked work.

```bash
./gradlew runClient -PmetalLifecycleTest -PmetalLifecycleBenchmark=true
```

The scenario defaults to the primary display's native resolution and 16-chunk render/simulation distances. Each phase is captured three times and reported with its median and spread; override with `-PmetalBenchmarkRepeats`. Other knobs are `-PmetalBenchmarkResolution=3840x2160`, `-PmetalBenchmarkRenderDistance`, `-PmetalBenchmarkSimulationDistance`, `-PmetalBenchmarkPhaseTicks`, and `-PmetalBenchmarkSeed`. Use `-PmetalBenchmarkPitch=30`, `-PmetalBenchmarkPack=none` (or `standard`), `-PmetalBenchmarkUnlocked=true`, and `-PmetalBenchmarkHalfResolution=false` to make those conditions explicit. Repeats return to the same starting camera and record actual camera positions and sampled heap/Metal allocations. Results are written to `run/benchmarks/metalcraft-<backend>.json`. Fresh terrain generation takes a few minutes.

### Distant terrain (LOD)

**Video Settings → MetalCraft Settings → Terrain & Distance** has three controls:

- **Distant terrain** (on by default). Singleplayer on Metal.
- **Native distance** (2–256 chunks, default **12**): ordinary full-quality chunks — entities, block entities, caves and exact blocks. The integrated server loads and sends only this radius and the client meshes only it.
- **Distant detail** (1–8, default **5**): how slowly detail falls off. A level-L cell is 2^L blocks wide; each step moves every level about 1.5× farther away. At 5 a cell stays near six pixels at 1080p. Detail also sets how far block-sized distant cells keep **real block textures, biome tints and ambient occlusion**, so they look like ordinary chunks seen from afar: 256 blocks at 5, 320 at 6, 384 at 7 and 512 at 8. Farther cells use one averaged colour each, which is what a mipmapped block texture becomes there.

The screen's status line says whether distant terrain is running and, if not, why: most often the Graphics API is not Default (Metal), or Render Distance is not larger than the native distance.

Minecraft's **Render Distance** sets the total view. Between the native distance and it, terrain comes from a quadtree of compact heightfield models built on background threads, never by the server: chunks you have loaded keep their real blocks, chunks saved in the world's region files are read back, and never-generated terrain is sampled straight from the world generator's density function (95% of columns match vanilla's surface exactly), with biome surface blocks, snow lines, rock on steep slopes and approximate tree canopies. Textured cells show each column's top block, its side, and the soil and rock under it (grass over dirt over stone). A 128-chunk view completes in under 3 seconds after joining, and flying 512 blocks rebuilds it in about 2. Models draw through Minecraft's ordinary terrain pipelines, so the Standard pack shades distant terrain and water too. Where native chunks are not ready yet (joining, teleports, reloads), the distant model stands in for them.

Measured back to back on an M4 Max at 1280×720 (indicative; conditions in the evidence): 1,850 FPS with only the 12-chunk native radius, **1,265 FPS with distant terrain to 128 chunks** and **1,021 FPS at 1024 chunks**; selecting and scheduling distant terrain takes about 0.03 ms per frame. Enable the `distant_terrain` entry in the F3 debug options screen to see nodes, draws, memory and pending builds. Distant terrain shows surfaces only, and dimensions with a ceiling (the Nether) keep the native radius without distant terrain. [Design, measurements and limits](docs/evidence/distant-terrain/README.md).

Enable **Clear distance fog** in the same screen to keep distant terrain clear in fair weather. It pushes distance cutoff fog and atmospheric haze beyond the whole view; fluid, weather, boss and status-effect fog remain.

Above 32 chunks, render-section and dirty-state bookkeeping now allocates entries on demand, and camera moves visit resident entries. Large visibility lookup tables use sparse pages. This avoids eagerly creating millions of bookkeeping objects for terrain that has not arrived. In a 128-distance CPU fixture, dirty-tracker setup allocation fell from 69.7 MB to 25 KB and repositioning 4,356 resident entries took 0.034 ms versus 4.338 ms for the eager native store. These are bookkeeping measurements, not whole-game FPS or total RAM figures. [Validation and limits](docs/evidence/high-distance-cleanup/README.md).

Without distant terrain, the 256-chunk limit is a functional limit, not a performance or memory guarantee: full chunks and fully populated section storage grow roughly with the square of distance, and dense 256-distance play remains unqualified and can exceed available memory. Changing render distance does not raise simulation distance. Generated world chunks are saved normally. Remote multiplayer remains limited by the server; the vanilla client-information packet advertises at most 32 to avoid its signed-byte overflow. This client-only mod does not extend an unmodified remote server's generation radius.

Native distance validation without distant terrain is recorded in [the native chunk evidence](docs/evidence/native-chunks/README.md). Earlier LOD prototypes and their evidence are superseded by [the distant terrain rewrite](docs/evidence/distant-terrain/README.md).

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

Standard also animates tree leaves, tall/short grass and ferns with a gentle wind.
Grass bends from fixed roots, both halves of tall plants move together, and sun/moon
shadows follow the same motion. **MetalCraft Settings → Shaders → Wind** controls
the effect and its strength (enabled at 100% by default). Fences and other blocks
stay still. Wind applies to native block meshes; distant LOD models remain static.
Animation runs in Metal vertex shaders with immutable mesh metadata, without
rebuilding terrain every frame. `./gradlew build` includes wind GPU checks; the
short existing-standard-world check uses `-PmetalLifecycleTest` and
`-PmetalJvmArgs='-Xmx8G -Dmetalcraft.windTest=true'` with `runClient` and the default
graphics backend, at 16 render / 16 simulation distance.

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
