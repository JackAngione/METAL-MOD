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
  **Detail** (1–8) sets `levelDistance` (32–384 blocks; 5 = 128 ≈ 6 px per cell at 1080p/70°).
- Cells are sampled on worker threads, never by the server:
  1. chunks the client has loaded (captured with their real blocks),
  2. chunks saved in region files (heightmaps and top blocks, through the server's I/O worker),
  3. otherwise the world generator's density function: at cell corners the vertical density profile
     is linear, so the surface is found from a handful of evaluations exactly as vanilla fills it.
     Biome palettes add surface blocks, snow lines, frozen water, steep-slope rock and tree canopies.
- Meshes are merged heightfield boxes in Minecraft's block vertex format, drawn through the
  ordinary terrain pipelines (so shader packs keep working). Colour lives in one 2048² atlas bound
  as `Sampler0` for distant draws, so tops merge by height alone. Nodes that can meet native
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

## Reproduce

```bash
./gradlew lodTerrainSmoke --offline
./gradlew lodTerrainBenchmark --offline
./gradlew runClient -PmetalLifecycleTest -PmetalLodTest=true '-PmetalJvmArgs=-Xmx8G' --args='--graphicsBackend default' --offline
```

`-PmetalLodTestWorld=<save name>` picks the save to reuse; a save locked by another running game
is skipped, and a NORMAL world is created only if none is available. Game-test runs restore
`run/options.txt` and the MetalCraft config files afterwards, because the Fabric test harness
forces its own options (render distance 5, clouds and music off).
