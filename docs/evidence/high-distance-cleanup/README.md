# High-distance CPU and RAM cleanup

September 20, 2026; Apple M4 Max; Minecraft 26.2; Java 25; Metal.

## Changes

At 128 chunks, a 24-section-high world has 1,585,176 logical section slots.
Native storage eagerly constructs a wrapper and a value for every slot in both
ViewArea and SectionUpdateTracker. Beyond 32 chunks, these two audited owners now
use a paged, lazy store. Logical size, slot indices, wrapping and resident object
identity remain native. Recycling invokes the native setter, preserving task
cancellation, mesh release and dirty flags. Cleanup visits all resident entries.
Creation and camera movement are synchronized with visibility-worker access.

Factories whose constructors defer positioning are explicitly initialized before
publication. This matters for the runtime SectionDirtyState class: its constructor
does not assign sectionNode, so native eager storage relies on the first camera
reposition. The regression test uses both a deferred factory and the actual native
dirty-state class, including player edits and teleport recycling.

The visibility graph retains its native traversal, octree, nodes and publication
lifecycle; only large index tables use 256-entry pages. Rebuild scheduling uses
primitive long keys and packed section lookups. Terrain pipeline changes retain
compatible buffer/texture bindings; non-solid draws skip the pixel-LOD scan.
Geometry, shading resolution, draw order and refinement quotas are unchanged.

## Isolated allocation and movement measurements

ThreadMXBean allocated bytes, warmed constructors, 256 MB smoke-test heap.
Camera movement uses 10 warmups and 15 measured samples; values are medians.
The movement fixture holds 4,356 resident entries inside the native 128-distance
volume. It alternates the camera across a section boundary.

| Operation | Native eager | Lazy/paged |
| --- | ---: | ---: |
| Empty dirty-tracker construction | 69,747,864 bytes | 25,000 bytes |
| Camera reposition | 4.338 ms | 0.034 ms |
| Values inspected per camera move | 1,585,176 | 4,356 |
| Empty visibility index table | 6,340,720 bytes | 24,824 bytes |

These compare specific bookkeeping operations, not a fully loaded world's FPS,
process resident memory or all JVM allocations. Storage grows as entries are
accessed. Resident slot identities are retained until world/distance reset; travel
can eventually populate the whole ring. Even then per-entry wrappers are absent.
Sparse pages trade a little indexing overhead for avoided allocation. Full chunks,
server generation, lighting, initial tessellation and draw count still scale with
loaded terrain. This change does not replace far chunks with compact terrain data.

## Validation

`MTL_DEBUG_LAYER=1 ./gradlew build` passed in 20 seconds. The new
`sectionStorageSmoke` participates in `check`: native indexing/bounds, negative
coordinates, camera movement and teleports, identity-preserving recycling, actual
dirty-state initialization, player edits, cleanup, concurrent same-slot creation,
sparse/dense graph parity and allocation checks. Existing LOD geometry/queue tests
and Metal shading, depth, resource lifetime and terrain binding compatibility
checks also pass.

The first live attempts exposed the deferred dirty-state initialization issue.
Their timeout diagnostics prompted the fix and deterministic regression coverage;
the final 18.74-second route passes. It uses a NORMAL world, render 128, simulation
16, Threaded updates and Default/Metal. [Live report](live-report.json) records
11,955 render entries and 18,185 dirty states out of 1,585,176 logical slots.
320×180 distant targets, disable/reenable, odd resize, approach/refinement, retreat,
radius expansion and world close pass. No Metal validation errors occurred.
Existing development-account authentication and saved anisotropic-option warnings
are unrelated to the test.

Inspected [distant shading](quarter.png) and [restored near detail](approach.png).
The standard terrain and stepped fixture remain visible with the expected LOD
changes. [Build log](build.log) and [live log](live.log) retain the final evidence.

Reproduce:

```sh
./gradlew sectionStorageSmoke nativeTerrainLodSmoke
MTL_DEBUG_LAYER=1 ./gradlew build
MTL_DEBUG_LAYER=1 ./gradlew runClient -PmetalLifecycleTest \
  -PmetalGeometricLodTest=true -PmetalTerrainResolutionTest=true \
  -PmetalJvmArgs='-Xmx12G' --args='--graphicsBackend default'
```
