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
- Adds a **Level of Detail…** submenu with persisted quality preferences. Opt-in loaded-terrain LOD has four exact-surface tiers, bounded capture/uploads, atlas-safe texture repetition, shadow reuse, and Standard/no-pack fallback. An opt-in 32–256 chunk horizon remembers explored opaque terrain in a persistent cache. Standard skips redundant lighting beyond short shadow ranges while retaining full-resolution pixels and depth. Half/quarter-resolution shading remains deferred. LOD is off by default; the explored-terrain preview adds distant scenery with a measured performance cost. See [the LOD plan](docs/LOD_FEATURE_PLAN.md).
- Provides the `metalcraft-shaders` Fabric entrypoint and reload-aware pipeline precompilation API for shader add-ons.

No performance number is promised yet. The Metal path removes reliance on Apple's deprecated OpenGL implementation, but “optimal” needs repeatable frame-time measurements. The benchmark scenario renders ordinary generated terrain at the display's native resolution and 16 chunks, and validates that it is drawing a real world before reporting; see [docs/APPLE_SILICON_PERFORMANCE.md](docs/APPLE_SILICON_PERFORMANCE.md) for the methodology and current numbers, and [ROADMAP.md](ROADMAP.md) for tracked work.

```bash
./gradlew runClient -PmetalLifecycleTest -PmetalLifecycleBenchmark=true
```

The scenario defaults to the primary display's native resolution and 16-chunk render/simulation distances. Each phase is captured three times and reported with its median and spread; override with `-PmetalBenchmarkRepeats`. Other knobs are `-PmetalBenchmarkResolution=3840x2160`, `-PmetalBenchmarkRenderDistance`, `-PmetalBenchmarkSimulationDistance`, `-PmetalBenchmarkPhaseTicks`, and `-PmetalBenchmarkSeed`. Use `-PmetalBenchmarkPitch=30`, `-PmetalBenchmarkPack=none` (or `standard`), `-PmetalBenchmarkUnlocked=true`, and `-PmetalBenchmarkHalfResolution=false` to make those conditions explicit. Repeats return to the same starting camera and record actual camera positions and sampled heap/Metal allocations. Results are written to `run/benchmarks/metalcraft-<backend>.json`. Fresh terrain generation takes a few minutes.

Enable the preview in **Video Settings → MetalCraft Settings → Level of Detail… → Enable terrain LOD**; no JVM or development flag is required. Geometry stays exact: only compatible opaque faces merge, while unsupported solid layers and other render layers keep their ordinary meshes. This conservative policy saves only a small fraction of total terrain triangles and has not demonstrated a speedup. [Measured costs and coverage](docs/LOD_PERFORMANCE_RESULTS.md) explain the preview's scope.

For remembered distant scenery, enable disk caching in the same submenu and select a 32–256 chunk horizon. Keep Minecraft render and simulation distances at **16/16** and Graphics API at **Default**. Only terrain already received and meshed by the client can appear; unknown areas and distant fluids remain absent. Cached terrain may be stale until revisited. Parent meshes batch exact opaque surfaces with bounded residency; dense 256-chunk coverage and lower-memory Macs are not qualified. **Clear distant terrain cache** removes all cached worlds and resource generations, including while LOD is disabled. Defaults remain LOD off, Balanced, full shading, a 16-chunk horizon, automatic mesh limits and a 2 GiB disk budget. Standard and no shader pack are supported; other packs retain ordinary rendering.

Use `-PmetalBenchmarkLod=true` or `false` for explicit benchmark A/B runs. Add `-PmetalLodTerrainCensus=true` to collect whole-terrain and distant-triangle counts in both modes; this diagnostic bookkeeping is excluded from ordinary disabled play and the disabled-overhead comparison. Reports retain replacement/shadow counts and LOD preparation percentiles. The dedicated NORMAL-world route is `./gradlew runClient -PmetalLifecycleTest -PmetalLodRenderTest=true --args='--graphicsBackend default'`. Render and simulation distances remain 16/16.

Run `python3 scripts/lod-release-matrix.py` for the Standard/None × 1080p/native × full/half × LOD off/on matrix. It retains three repeats per case, checks actual monitor attachment and drawable dimensions, requires every server-tracked chunk to be received, and warms the full pan before measuring. Every measured game tick must retain window focus, visibility and presentation mode; keep the Mac unlocked and Minecraft foreground throughout the run. GPU frame spans include all command buffers submitted on the render thread during a frame; reports retain samples and median/p95/p99, with incomplete samples explicitly counted. Worker build timing, CPU charge peaks, OS process memory peaks and thermal state accompany the measurements. Use `--resume` to continue matching captures and `python3 scripts/lod-release-summary.py build/reports/lod-release` to compare them. Capture completion does not automatically pass performance gates.

The September 12 M4 Max matrix completes all 16 cases and 144 phases with verified presentation. It saves only 0.127–0.250% of distant triangles; no tested configuration demonstrates a median frame-time benefit from loaded-terrain LOD. The full build passes Metal API validation. LOD remains disabled by default. The earlier opt-in horizon matrix is evaluated separately: all eight 128/256-chunk Standard/None × full/half comparisons meet the +25% median / +35% p99 frame-time budget on the tested explored patches. The largest measured median cost is 21.83%. P8 still requires six disabled-baseline pairs and a final horizon regression after the idle-path refinement. See [the current results and limits](docs/LOD_PERFORMANCE_RESULTS.md).

For paired stationary measurements of explored terrain at 128/256 chunks, add `-PmetalLodHorizonBenchmark=true -PmetalBenchmarkFullscreen=true -PmetalBenchmarkUnlocked=true -PmetalBenchmarkHalfResolution=false` to the horizon lifecycle test (`-PmetalLodHorizonTest=true`). It measures Standard/None, full/half, and LOD off/on with three repeats using the default 2 GiB disk budget, and reports represented sections. Summarize with `python3 scripts/lod-horizon-summary.py run/build/lod-horizon.json`. These explored patches do not establish dense 256-chunk coverage. A modest performance cost for the additional horizon is acceptable; the measured tradeoff and memory/coverage limits remain explicit.

`python3 scripts/lod-run-horizon.py --cold-cache` runs that complete route (clearing only the isolated game-test cache) and saves its invocation, source patch, raw report and screenshots. It additionally measures exploration/readiness waits, repeated edit persistence, worker update latency and logical GPU/disk/queue payload high-water marks. These counters exclude driver allocation overhead and filesystem metadata. Interrupted foreground captures are discarded and retried at most three times; changed presentation modes and rendering failures remain fatal. Add `--cost-probe` for Standard/full at both horizons with three off/on repeats and the complete lifecycle/repair checks. Its preparation timer includes loaded and distant frame maintenance, selection, uniform writes and CPU uploads; worker execution and GPU rendering remain separate.

For the separate disabled-overhead comparison, create a detached worktree at pre-LOD commit `f48460d`, then run `python3 scripts/lod-prepare-baseline.py /path/to/worktree`. It backports the current measurement harness and shared texel-buffer correction while preserving the old renderer without LOD hooks. Run `python3 scripts/lod-run-baseline.py /path/to/worktree build/reports/lod-release build/reports/lod-disabled --paired-current --resume`, followed by `python3 scripts/lod-disabled-summary.py build/reports/lod-disabled/pre-lod build/reports/lod-disabled/current`. The backport patch is saved for review. Each current/off capture runs next to its pre-LOD counterpart, with alternating process order and matching settings. Resume requires a complete pair with matching commands and source identity. The runner removes development/census flags to measure ordinary disabled behavior. Run only one Minecraft benchmark at a time, without Metal API validation; use `MTL_DEBUG_LAYER=1 ./gradlew build` separately for correctness.

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
