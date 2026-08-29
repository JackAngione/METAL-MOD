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
- Loads and renders a singleplayer flat world through Metal, including cloud rendering, correct framebuffer and Retina-window orientation, critical shader compilation, resource reload, resize, fullscreen configuration, screenshot capture, world close, and ordered shutdown, with no Vulkan or MoltenVK initialization in the launch log.
- Explicit OpenGL/Vulkan selections, Minecraft's crash recovery, and the `--graphicsBackend` launcher override remain authoritative.
- Adds a dedicated **Video Settings → MetalCraft Settings** screen. Its half-resolution option renders at macOS logical resolution while keeping the Metal drawable at native Retina size, reducing the rendered pixel count by 75% before presentation upscaling.
- Provides the `metalcraft-shaders` Fabric entrypoint and reload-aware pipeline precompilation API for shader add-ons.
- Provides a validated local-light provider registry with bounded, deterministic per-frame snapshots.

No performance number is promised yet. The Metal path removes reliance on Apple's deprecated OpenGL implementation, but “optimal” needs repeatable frame-time measurements. The benchmark scenario now renders ordinary generated terrain from a ground-level camera at the display's native resolution and 32 chunks, and validates that it is drawing a real world before reporting; see [docs/APPLE_SILICON_PERFORMANCE.md](docs/APPLE_SILICON_PERFORMANCE.md) for the methodology and current numbers, and [ROADMAP.md](ROADMAP.md) for tracked work.

```bash
./gradlew runClient -PmetalLifecycleTest -PmetalLifecycleBenchmark=true
```

The scenario defaults to the primary display's native resolution, 32 chunks, and simulation distance 16. Each phase is captured three times and reported with its median and spread; override with `-PmetalBenchmarkRepeats`. Other knobs are `-PmetalBenchmarkResolution=3840x2160`, `-PmetalBenchmarkRenderDistance`, `-PmetalBenchmarkSimulationDistance`, `-PmetalBenchmarkPhaseTicks`, and `-PmetalBenchmarkSeed`. Results are written to `run/benchmarks/metalcraft-<backend>.json`. The first run of a seed generates a 32-chunk radius of fresh terrain, which takes a few minutes.

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
On Apple silicon, `build` also runs `shaderTranslationSmoke`, which validates translation, Vulkan coordinate conversion, resource-slot preservation, and typed texel-buffer sampling, then exercises vertex descriptors, indexed rendering, multipass load behavior, readback, and pipeline creation on the active Metal device. The lifecycle command creates a flat singleplayer world and validates resize, fullscreen configuration, resource reload, screenshot output, world close, and shutdown. Its screenshot is written under `run/screenshots/`.

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
        context.lights().register(
            Identifier.fromNamespaceAndPath("my_addon", "dynamic_lights"),
            output -> output.accept(new MetalCraftLocalLight(
                1L, new Vec3(0.5, 65.0, 0.5),
                1.0F, 0.35F, 0.08F, 4.0F, 12.0F, true
            ))
        );
    }
}
```

Place sources at `assets/my_addon/shaders/example.vsh` and `assets/my_addon/shaders/example.fsh`. MetalCraft precompiles registered pipelines after startup and after shader-resource reloads. Registration alone does not draw geometry; a later shader module can retrieve the pipeline from `MetalCraftShaders.registry()` and use Fabric/Blaze3D render hooks to submit it. Light providers run once per extracted world frame on the render thread; each provider must keep its numeric light IDs stable between frames.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for design boundaries and [ROADMAP.md](ROADMAP.md) for tracked work.
