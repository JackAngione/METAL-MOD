# MetalCraft roadmap

Last updated: 2026-08-20

## Milestone 0 — feasibility and toolchain

- [x] Verify that Minecraft Java 26.2 has a supported Blaze3D Vulkan backend.
- [x] Verify that the official macOS arm64 runtime includes LWJGL Vulkan and MoltenVK.
- [x] Pin the Fabric 26.2 / Java 25 / Gradle 9.5.1 build.
- [x] Document the Vulkan → MoltenVK → Metal design boundary.

## Milestone 1 — MoltenVK feasibility prototype (superseded)

- [x] Detect Apple silicon without loading graphics libraries early.
- [x] Prefer Vulkan when Minecraft is set to `Default`.
- [x] Preserve explicit OpenGL, command-line selection, and Minecraft crash recovery.
- [x] Report the active GPU/backend after startup.
- [x] Compile and remap a distributable Fabric jar.
- [x] Smoke-test a real 26.2 client through Vulkan / MoltenVK / Metal.

This milestone proved that Minecraft 26.2's backend abstraction is usable, but it is not the requested final architecture.

## Milestone 2 — direct Metal backend

- [x] Inventory `GpuBackend`, `GpuDeviceBackend`, `GpuSurfaceBackend`, `CommandEncoderBackend`, and `RenderPassBackend`.
- [x] Add an arm64 Objective-C native target linked directly to Metal, QuartzCore, and AppKit.
- [x] Package and load the native dylib from the Fabric jar.
- [x] Probe `MTLDevice` directly and expose GPU name/unified-memory working-set information.
- [x] Replace the native probe handles with ownership-checked device and command-queue objects.
- [x] Attach a `CAMetalLayer` to GLFW's Cocoa window and implement acquire/present.
- [x] Implement `MTLBuffer`, `MTLTexture`, texture views, and `MTLSamplerState` wrappers.
- [x] Implement shared-memory mapping, staging, upload, copy, readback, and fences.
- [x] Implement Metal render-pass descriptors, scissor state, vertex/index binding, pipelines, and direct draws.
- [x] Implement indirect/multi-draw fallbacks required by Blaze3D.
- [x] Compile GLSL to SPIR-V with shaderc, translate SPIR-V to MSL with SPIRV-Cross, and feed translated MSL into the pipeline layer.
- [x] Preserve one explicit resource-slot ABI across vertex and fragment MSL translation.
- [x] Map every Blaze3D vertex, texture, depth, stencil, cull, and blend format to Metal, with explicit diagnostics for formats that have no byte-compatible Metal representation.
- [x] Implement non-blocking timestamp queries with Metal capability tiers and retain encoded resources until command-buffer completion.
- [x] Add `MetalBackend` to Minecraft's backend selection and keep OpenGL only as crash recovery.
- [x] Reach title screen with logs containing no Vulkan or MoltenVK initialization.
- [x] Bind typed texel-buffer uniforms required by Minecraft's cloud rendering.
- [x] Preserve framebuffer orientation through Retina drawable presentation.
- [x] Load a world and validate resizing, fullscreen, resource reload, screenshots, and shutdown.

## Milestone 3 — shader-ready API

- [x] Add a public shader extension entrypoint.
- [x] Add duplicate-safe pipeline registration and lookup.
- [x] Precompile registered pipelines against the active Blaze3D device.
- [x] Recompile extension pipelines after shader resource reloads.
- [x] Document GLSL resource locations and a minimal add-on example.
- [ ] Add an example shader add-on module that renders a simple full-screen pass.
- [ ] Add graceful per-extension error isolation and failed-pipeline diagnostics.
- [ ] Define a versioned post-processing graph API (color, depth, history, and resize lifecycle).

## Milestone 4 — measurable Apple silicon optimization

- [ ] Add a reproducible world/camera benchmark harness.
- [ ] Capture average FPS plus 1% low and CPU/GPU frame-time distributions.
- [ ] Compare OpenGL and Vulkan/Metal on M1, M2, M3, and M4 families.
- [ ] Tune MoltenVK settings only when measurements demonstrate a win.
- [ ] Add unified-memory-aware upload staging and allocation telemetry where Blaze3D exposes safe hooks.
- [ ] Publish compatibility results for popular Fabric renderer mods.

## Milestone 5 — production polish

- [ ] Add an in-game MetalCraft status/configuration screen.
- [ ] Surface a clear toast when Vulkan falls back to OpenGL.
- [ ] Add automated launch tests on macOS arm64 CI hardware.
- [ ] Add release packaging, changelog generation, and signed artifacts.
- [ ] Promote from alpha after crash recovery and shader reload tests pass.

## Explicit non-goals for the foundation

- Replacing Blaze3D with a parallel renderer.
- Calling private Apple APIs.
- Translating arbitrary legacy mods' raw OpenGL calls.
- Claiming a speedup before controlled benchmark data exists.
