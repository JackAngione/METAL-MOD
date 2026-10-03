# Distant terrain (LOD) — September 26, 2026

Rewrite of the terrain LOD feature. It replaces the compact horizon, the native-area shell
compiler, the disabled `lod/` prototype and Distant Pixel Resolution. Design and task list:
[LOD rewrite plan](../../LOD_REWRITE_PLAN.md).

## How it works

- **Render Distance** is the total view (now up to **1024 chunks** while distant terrain is on).
  **Native distance** (MetalCraft setting, default 12) is the radius of ordinary chunks. The
  integrated server loads, simulates and sends only that radius, and the client meshes only it.
- Beyond it, terrain comes from a quadtree of 32×32-cell nodes. A level-L cell is 2^L blocks and
  is used from `levelDistance(detail) × 2^L` blocks, so a cell stays near a fixed on-screen size.
  **Detail** (1–8) sets `levelDistance` (32–384 blocks; 5 = 128 ≈ 6 px per cell at 1080p/70°) and
  how far block-sized cells are **textured** (256 blocks at 5, 320 / 384 / 512 at 6 / 7 / 8; see
  [Textured near detail](#textured-near-detail--september-27-2026)).
- Cells are sampled on worker threads, never by the server:
  1. chunks the client has loaded (captured with their real blocks),
  2. chunks saved in region files (heightmaps and top blocks, through the server's I/O worker),
  3. otherwise the world generator's density function: at cell corners the vertical density profile
     is linear, so the surface is found from a handful of evaluations exactly as vanilla fills it.
     Biome palettes add surface blocks, snow lines, frozen water, steep-slope rock and tree canopies.
- Meshes are merged heightfield boxes in Minecraft's block vertex format, drawn through the
  ordinary terrain pipelines (so shader packs keep working). Colour lives in one 2048² atlas bound
  as `Sampler0` for distant draws, so tops merge by height alone. Textured nodes instead draw a quad
  per block face with block-atlas sprites, like native sections. Nodes that can meet native
  terrain are grouped per chunk; each frame the renderer omits chunks whose native surface sections
  are compiled, and draws the distant model wherever native terrain is not ready yet.

## Headless results (`./gradlew lodTerrainBenchmark`)

Apple M4 Max (16 cores), Java 25, vanilla Overworld generator, seed 7314159.

| Check | Result |
| --- | --- |
| Surface vs `getBaseHeight(OCEAN_FLOOR_WG)`, 2000 corner columns | 95.4% exact, 98.3% within 2 blocks |
| Surface vs vanilla, 600 interpolated columns | 93.7% exact, 98.3% within 2 blocks |
| One node (34×34 samples incl. border), one thread | 8.6 ms (level 0) to 18 ms (level 6), ≈15 µs per column |
| Level-4 nodes (32×32 chunks each), 8 threads | 396 nodes/s |
| Quads per cell after merging (varied terrain) | 0.33 (level 0) to 2.9 (level 6) |

The prototype produced about 70 columns per second by generating full chunks on the server.
Mismatches in the parity check are overhangs and cave openings, which a heightfield cannot show.

## In-game results (`MetalLodGameTest`)

Reused standard save `New World` (NORMAL, seed −4658973853064925697), Default/Metal, None pack,
1280×720, V-Sync off, unlocked frame rate, 16 simulation distance, native 12, detail 5, spectator
at y = 200. Each measurement is 100 ticks after 30 warm-up frames. Another MetalCraft dev client
was open on the same machine during these runs, so absolute FPS is indicative only; the phases
were measured back to back under the same conditions. Raw report: [lod-test.json](lod-test.json).

| Phase | Avg FPS | 1% low | CPU p50 | LOD select p50 | Draws | GPU memory |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Native 12 only (no LOD) | 1,850 | 677 | 0.34 ms | – | – | – |
| **LOD to 128 chunks** | **1,265** | 454 | 0.53 ms | 0.029 ms | 520 | 135 MiB |
| **LOD to 1024 chunks** | **1,021** | 376 | 0.74 ms | 0.045 ms | 918 | 385 MiB* |
| LOD to 128, Standard pack | 559 | 267 | 1.42 ms | 0.040 ms | – | – |

\* Includes nodes still resident from the 128-chunk view; nodes unused for ten seconds are
released, and least-recently-used nodes go first when the budget (1/16 of Metal's working set,
256 MiB–1.5 GiB) is reached.

| Timing | Seconds |
| --- | ---: |
| Whole 128-chunk distant view complete after joining | 2.7 |
| Complete again after flying 512 blocks | 2.1 |
| Growing the view from 128 to 1024 chunks | 2.3 |
| Native chunks at the new position (vanilla generation, for comparison) | 12.6 |

Checks that passed: client meshes 12 chunks and the server sends 12 while Render Distance is 128
and 1024; client chunk count stays bounded by the native radius; distant models cover the new
position before native chunks arrive; the Standard pack renders distant terrain and water;
disabling releases all distant GPU memory and restores ordinary loading. Captures were inspected
locally (none are committed).

## Limits

- Surface only: caves, overhangs, interiors and entities appear inside the native distance.
  Never-loaded, never-saved areas approximate trees and surface blocks from the biome.
- Single-player on Metal. Multiplayer keeps ordinary server-limited distances. Dimensions with a
  ceiling (the Nether) keep the native radius but draw no distant terrain.
- Distant water is a flat translucent surface; waterfalls and flowing water are not modelled.
- Resource reloads rebuild all distant models (block colours come from the atlas).

## Textured near detail — September 27, 2026

Before this change the highest detail looked as coarse as the default near the native boundary:
both already used block-sized cells there, and every cell was one flat averaged colour with no
shading, so forests and hills read as uniform slabs. Level-0 nodes within the detail's texture
distance are now meshed like native terrain seen from afar:

- one top per block with the block's top sprite, turned by a position hash as vanilla turns grass
  and sand; walls tiled one block per quad with the top block's side sprite, then the soil under it
  (dirt under grass, podzol and paths) and, four blocks down, rock (stone under soil, sandstone under
  sand); walls more than 16 blocks deep tile four blocks per quad;
- biome tints resolved per column, face shade, and vanilla's smooth-lighting occlusion from
  neighbouring columns, with each quad split along its darker diagonal;
- drawn with the block atlas and its mipmaps (no colour-atlas slot); fluids and hidden skirts stay
  merged. Real chunks record each column's top-face state, side state and biome
  (`LodSurface`, 8 bytes per column) so captured and saved terrain is textured as it really is;
  generated terrain uses the biome surface palette.
- Nodes wholly inside the native radius (stand-ins only) stay flat. If distant terrain exceeds its
  GPU budget, the texture distance drops by a quarter at a time (logged), since the budget cannot
  evict nodes in view.

Beyond the texture distance a block is at most about two pixels at 1080p, where its mipmapped
texture is its average colour, so flat cells remain there.

Fixed alongside: a native chunk whose surface section was outside the view (vanilla compiles only
sections in view) counted as not ready, so the whole distant chunk was drawn over native terrain on
screen and its tree columns (solid to the ground in the distant model) showed under real canopies.
Uncompiled sections outside the view no longer trigger the stand-in (without caching that result).

| Check (headless, `./gradlew lodTerrainBenchmark`) | Result |
| --- | --- |
| Textured level-0 node, generated terrain | 1.05–1.79 quads per cell (flat: 0.06–0.37) |
| Textured node in real forest/hill terrain (in game) | ≈2.6 quads per cell, ≈300 KB per node |
| Textured node build, one thread | 9.8 ms (flat 9.6 ms); meshing 0.41 ms of it |

In game (`MetalLodGameTest`, same save and settings as above, run back to back against the
previous commit with the same test): the window was paced at 120 FPS in both runs (it was not
frontmost), so frame rate does not compare cost, and GPU pass spans varied with GPU clock state
(the unchanged native-only pass measured 0.34–0.64 ms across runs). CPU time and settle times are
unchanged; the cost is memory:

| At 128 chunks | Before | After |
| --- | ---: | ---: |
| Detail 5: CPU p50 / GPU memory | 1.02 ms / 126 MiB | 0.88–0.97 ms / 142–171 MiB (36 textured nodes drawn) |
| Detail 8: CPU p50 / GPU memory* | 1.62 ms / 458 MiB | 1.62–1.71 ms / 713–773 MiB (249 textured nodes drawn) |
| Settle after joining / at detail 8 | 2.0 s / 6.4 s | 1.8–2.2 s / 6.4–6.6 s |

\* Includes nodes still resident from the detail-5 phase just before.

### Settings changes

Selection reads detail, native distance and Render Distance every frame, so a change starts
rebuilding at once, also behind the distant terrain screen while it pauses the game. A change now
also resets the texture budget reduction and, once the new view is complete, releases every node
only the old settings used (previously each lingered ten seconds, which could hold hundreds of MiB
and trip the budget reduction for the rest of the session). The screen's status line reads
"Building distant terrain: N areas left" until the view is complete, and the log records each
update. `MetalLodGameTest` changes detail in that screen and checks that rebuilding starts within
two ticks, completes and releases the finer view when detail is lowered again:

| Change (render 128, native 12) | Time to complete | Previous view released |
| --- | ---: | ---: |
| Detail 5 → 8 | 6.6 s | 194 MiB |
| Detail 8 → 5 | 1.5 s | 650 MiB |
| Render Distance 128 → 1024 | 2.3 s | 64 MiB |

Node meshes depend only on their level and form (flat or textured), not on detail, so a detail
change rebuilds the areas whose level or form changes and reuses the rest. When the whole distant
view is close (for example native 16 with Render Distance 28, a 256–448 block ring), the upper
detail steps differ only at its outer edge, because all of it is already block-sized textured cells.

Captured with `-PmetalLodTest=visual` (1920×1080, native 8, render 64, 24 blocks above ground;
local only): distant forests, hills, mountains, water edges and mushrooms show their block
textures and match native terrain at the boundary.

## Reproduce

```bash
./gradlew lodTerrainSmoke --offline
./gradlew lodTerrainBenchmark --offline
./gradlew runClient -PmetalLifecycleTest -PmetalLodTest=true '-PmetalJvmArgs=-Xmx8G' --args='--graphicsBackend default' --offline
./gradlew runClient -PmetalLifecycleTest -PmetalLodTest=visual -PmetalLodVisualDetails=5,8 '-PmetalJvmArgs=-Xmx8G' --args='--graphicsBackend default' --offline
```

The visual route only captures: the view across the native boundary at each listed detail, then
the same view with the native radius alone (`-PmetalLodVisualNative`, `-PmetalLodVisualRender` and
`-PmetalLodVisualHeight` adjust the native distance, the Render Distance and the height above ground).

`-PmetalLodTestWorld=<save name>` picks the save to reuse; a save locked by another running game
is skipped, and a NORMAL world is created only if none is available. Game-test runs restore
`run/options.txt` and the MetalCraft config files afterwards, because the Fabric test harness
forces its own options (render distance 5, clouds and music off).
