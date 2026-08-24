# Architecture

## Target render path

```text
Minecraft/Fabric rendering code
        ↓
Blaze3D RenderPipeline and RenderState APIs
        ↓
MetalCraft GpuBackend / GpuDeviceBackend
        ↓
Objective-C native ABI
        ↓
Apple Metal → Apple GPU
```

The final backend must not initialize Vulkan or MoltenVK. MetalCraft will implement Minecraft 26.2's complete public GPU backend contract, compile shader resources to Metal Shading Language, attach a `CAMetalLayer` to the GLFW Cocoa window, and own Metal resource/command lifetimes.

The current `0.2.0-dev.1` build registers `MetalBackend` ahead of OpenGL for the `Default` graphics option on Apple silicon and removes Vulkan from that candidate list. `MetalBackend` creates a complete Blaze3D adapter graph rooted at `MetalGpuDevice`; a singleplayer flat world and its resize, fullscreen-configuration, resource-reload, screenshot, close, and shutdown lifecycle have been validated through that path without Vulkan or MoltenVK initialization. Explicit OpenGL and Vulkan selections remain untouched, and OpenGL remains Minecraft's recovery backend if Metal creation fails.

This boundary matters for compatibility: Fabric's 26.2 rendering guidance requires mods to use Blaze3D rather than raw OpenGL, because the same render code must work on both OpenGL and Vulkan.

## Native ownership boundary

JNI exposes opaque, monotonically assigned handles rather than Objective-C object pointers. Each native registry entry has a concrete type and, for child objects, its owning device handle. A handle is validated on every operation, duplicate release is rejected, and native code refuses to release a device while command queues still belong to it.

On the Java side, `MetalDevice`, `MetalCommandQueue`, `MetalCommandBuffer`, `MetalSurface`, and `MetalDrawable` are `AutoCloseable`. A device tracks the queues and surfaces created through it; queues own their command buffers, and surfaces own acquired drawables. Parent shutdown recursively closes children first. Every wrapper makes repeated `close()` calls harmless and rejects operations after close.

## Cocoa surface lifecycle

The direct backend passes GLFW's Cocoa content-view pointer from LWJGL to the native bridge. On the AppKit main thread, MetalCraft preserves the view's previous layer state, installs a device-backed `CAMetalLayer`, and keeps its drawable size synchronized with framebuffer pixels. When logical framebuffer and Retina drawable sizes differ, a presentation pipeline scales the source while preserving its top-to-bottom orientation. Surface teardown restores the previous Cocoa layer.

Each frame acquires one `CAMetalDrawable`, schedules it on a command buffer from the same root `MTLDevice`, and commits that buffer. Cross-device presentation and presenting one drawable more than once are rejected. `MetalGpuSurface` drives attach, resize, acquire, format conversion, presentation, and teardown directly from Minecraft's `GpuSurfaceBackend` lifecycle.

## Initial resource ownership

`MetalDevice` can allocate shared or private buffers, private 2D textures, single cubemaps, immutable sampler states, and mip-range texture views. Textures own their views, while the device owns root buffers, textures, and samplers. Closing the device therefore releases command work and surfaces before recursively releasing every resource. General texture arrays and 3D textures remain intentionally unsupported until a Minecraft path requires them.

The native pixel-format table covers every byte-compatible Blaze3D color, depth, and stencil format that Metal exposes, plus BGRA8 for the window surface. `Blaze3DMetalMappings` classifies every `GpuFormat` explicitly and translates Blaze3D texture-binding/render-attachment usage without over-declaring incompatible Metal capabilities. Three-channel texture formats are rejected at that seam because Metal has no byte-compatible three-channel pixel formats; silently widening them to four channels would corrupt row pitches and resource copies. Metal-only availability constraints are explicit: packed RG11B10 vertex input requires macOS 14 or newer, and allocation remains authoritative for device-dependent formats such as depth24-stencil8.

## Transfers and synchronization

Shared buffers expose scoped direct `ByteBuffer` mappings over unified memory. A live mapping is a child of its buffer, so the ownership registry prevents native buffer release until mappings close. Private buffers remain GPU-only and reject mapping.

Command buffers encode `MTLBlitCommandEncoder` copies between buffers and between padded staging buffers and private textures. Synchronous texture convenience methods pack tightly stored Java pixels into Metal's 256-byte-aligned rows for upload, and remove that padding after readback. Lower-level copy methods remain available for the future Blaze3D command encoder to batch asynchronously.

Cross-queue GPU ordering uses monotonically valued `MTLSharedEvent` fences. Command buffers can encode signal and wait values, while Java can query the completed value without forcing a CPU wait. Native validation requires every command, resource, and fence participating in a transfer to descend from the same `MTLDevice`.

`MetalRenderPassBackend.drawMultipleIndexed` does not encode its binds and draws one JNI call at a time. It records them into `MetalCommandStream` - a flat array of fixed-size records in one reused direct buffer - and submits the whole array in a single crossing, which resolves every handle under a strided hold of the registry lock, pins every resource in one acquisition, and issues every encoder call. The recorder validates the ranges it can answer from Java; the decoder validates what a record can be judged on by itself, with the full native checks available behind `-Dmetalcraft.checkedCommands=true`. Commands outside that ABI, texel-buffer binds among them, flush the batch first so that ordering is exactly what the per-command path produced.

Every native command buffer owns an in-flight resource set. Encoding a copy, draw, fence operation, presentation, or timestamp sample adds the participating native objects to that set even if their Java wrappers close immediately afterward. A Metal completion handler releases the complete set only after GPU execution finishes. This makes lifetime safety an invariant of command encoding rather than a responsibility repeated by each higher-level caller.

Timestamp queries use a shared `MTLCounterSampleBuffer` plus generation-tagged availability state. Query reads are non-blocking: they return empty until the command buffer's completion handler publishes that generation, and then resolve only the requested counter range. Command-level timestamps adapt to the device's supported sampling tier. MetalCraft samples directly at blit boundaries when available and otherwise creates an empty blit pass whose end-of-stage sample represents the command boundary used by Blaze3D timers. Mid-render-pass sampling is exposed separately and accepted only on devices that advertise draw-boundary counters; callers can query that capability instead of discovering it through an encoder failure.

## Render commands

A command buffer can own one active scoped `MetalRenderPass`. Its descriptor accepts a drawable or private color texture at a selected mip level, an optional depth texture/mip, load/store actions, and clear values. The native bridge converts that descriptor to `MTLRenderPassDescriptor`, installs a full-target viewport and scissor by default, and refuses mismatched attachment dimensions or formats. Ending the Java scope ends and releases the native encoder; committing with an open pass is rejected.

`MetalShaderTranslator` compiles each expanded GLSL stage to Vulkan 1.2 / SPIR-V 1.5 with shaderc, then translates it to macOS MSL 2.4 with SPIRV-Cross. The Blaze3D seam assigns vertex locations by semantic name, links separate-stage varyings by name even when one stage omits an output, and normalizes OpenGL's `gl_VertexID` spelling for Vulkan GLSL. Existing explicit locations remain preserved. Vertex Y is flipped during translation to bridge Vulkan and Metal clip-space conventions. Translation failures retain the stage and source name in their diagnostics.

Blaze3D resource bindings use one flattened ABI: uniform buffers occupy the first slots and texture/sampler pairs follow them. The adapter injects those bindings as explicit GLSL decorations, and SPIRV-Cross's MSL decoration-binding option preserves their numeric slots instead of compacting each shader stage independently. `MetalRenderPassBackend` binds buffers, textures, and samplers to those same slots, keeping vertex and fragment stages consistent even when either stage uses only a subset of the pipeline layout. Typed texel buffers use the binding's `GpuFormat` to create an aligned `MTLTextureTypeTextureBuffer` view over the source buffer; both the buffer and transient view remain pinned until the command buffer completes.

`MetalRenderPipeline.GlslDescriptor` feeds a vertex/fragment GLSL pair through that translation path. The native layer compiles the resulting vertex and fragment MSL in separate `MTLLibrary` objects, because independently emitted SPIRV-Cross sources can contain colliding helper symbols, then links their resolved entry points into an owned pipeline module containing both `MTLRenderPipelineState` and `MTLDepthStencilState`. A caller can still use `MetalRenderPipeline.Descriptor` to supply MSL directly. Pipeline color/depth formats must match the active pass. A pass can then bind vertex buffers, set a bounded scissor rectangle, and encode instanced direct or 16/32-bit indexed draws. All render-pass, pipeline, and buffer handles are checked for a common root device before native encoding.

`Blaze3DMetalMappings.pipelineDescriptor` is the state-mapping seam. It flattens Blaze3D's buffer-indexed `VertexFormat` values into Metal attribute locations, offsets, strides, per-vertex/per-instance step functions, and divisors. The same conversion carries up to eight color-target formats, write masks, and independent color/alpha blend equations, plus depth comparison/write/bias and raster cull/winding/wireframe state. Binding the owned pipeline applies all dynamic Metal encoder state together. The adapter exhaustively classifies primitive topologies as well; triangle fans require converted indices because Metal has no triangle-fan primitive.

Blaze3D exposes interleaved and separate direct multi-draw layouts. MetalCraft normalizes both layouts into validated arrays and crosses JNI once, then encodes the individual Metal draw commands in a native fallback loop. Indirect buffers remain GPU-driven: Metal consumes the Vulkan-compatible 16-byte `DrawIndirect` and 20-byte `DrawIndexedIndirect` argument layouts directly. Multi-draw indirect is implemented by encoding one Metal indirect command per fixed-stride argument record because Metal does not expose Vulkan's draw-count form.

Shader inputs passed directly to `MetalShaderTranslator` must already have includes (including Minecraft's `#moj_import` directives) expanded by the resource-loading layer. The direct adapter now covers singleplayer world loading and the automated resize, fullscreen-configuration, resource-reload, screenshot, close, and shutdown lifecycle. Broader world compatibility, extended play sessions, physical fullscreen switching across real monitor configurations, and performance measurement remain future validation boundaries.

## Safety and recovery

- `OPENGL` remains untouched when selected explicitly or chosen by Minecraft after an unexpected startup failure.
- A `--graphicsBackend` launcher argument is resolved by Minecraft separately and remains authoritative.
- Direct Metal failure falls through to OpenGL inside Minecraft's existing backend loop.
- `-Dmetalcraft.disable=true` disables MetalCraft's preference without changing files.
- The mod does not edit `options.txt`, install system software, or ship its own graphics driver.

## Shader extension lifecycle

Add-ons implement `MetalCraftShaderExtension` and declare the `metalcraft-shaders` entrypoint. At client startup MetalCraft supplies a context containing immutable device information and the shared registry. Each registered `RenderPipeline` is precompiled through `GpuDevice`.

Shader sources can come from a normal resource pack (`assets/<namespace>/shaders/*.vsh` and `*.fsh`) or a complete custom Blaze3D `ShaderSource`. The registry is precompiled again after `ShaderManager` applies a resource reload, so add-ons do not need to reach into the Vulkan or Metal backends.

The API intentionally ends at pipeline ownership. A shader add-on will separately choose an appropriate Fabric rendering hook, create buffers/textures through Blaze3D, bind its registered pipeline, and issue draw calls. That keeps post-processing, world shading, and UI effects composable instead of forcing one monolithic shader system.

## Compatibility contract

- Target: Minecraft Java Edition 26.2 only (`~26.2`).
- Runtime: Fabric Loader 0.19.3+, Fabric API, Java 25.
- Optimized platform: macOS on arm64/aarch64.
- Other platforms: mod loads but does not change backend order.
- API status: experimental for the `0.2.x` line; shader add-ons should pin a compatible MetalCraft version.
