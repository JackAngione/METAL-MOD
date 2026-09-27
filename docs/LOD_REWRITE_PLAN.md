# Distant terrain (LOD) rewrite plan

Started 2026-09-26. Replaces the prototype LOD code (`lod/`, `horizon/`, `chunk/Native*`
shells and the Distant Pixel Resolution feature) with one system built for extreme render
distance at minimal cost.

## Behaviour

1. **Render Distance** (Minecraft's slider, up to 256 chunks) is the total distance.
2. **Native distance** (MetalCraft setting) is the radius of ordinary, full-quality chunks.
   The integrated server and client load/send/mesh only this radius.
3. Between the native radius and the total distance, terrain is drawn from compact LOD
   meshes whose cell size doubles with distance. A **detail** slider sets how quickly
   detail falls off (screen-space error target).
4. LOD terrain appears as fast as possible and never costs server ticks or disk writes.

## Why the prototype was slow

- Distant columns were produced by loading FULL vanilla chunks through server tickets
  (~70 columns/s, so a 256-chunk horizon took most of an hour and saved every chunk).
- Baking and meshing ran on the client/render threads; columns were heap objects.
- Fixed 8×8-chunk groups everywhere (thousands of draws at 256 chunks), per-frame
  allocation in selection, and native-area section re-meshing (shell compiler) on movement.

## Architecture

- **Sampling** (worker threads, never the server): cell heights come straight from the
  world generator's density function (surface found by scanning cell-corner densities and
  interpolating exactly as vanilla does), biome from the biome source, colour from
  biome surface/canopy palette × averaged block textures. Chunks the client has loaded
  are captured with their real blocks and override generated data.
- **Quadtree**: nodes are 32×32 cells; level L has 2^L-block cells. Level is chosen from
  distance so a cell stays under the detail slider's pixel target.
- **Meshing** (worker threads): greedy-merged heightfield boxes with skirts; colours in a
  per-node 32×32 texel block of one shared LOD colour atlas, so tops merge by height only.
  Nodes near the native boundary are laid out per chunk so the renderer can exclude
  chunks that native rendering already covers, with no re-meshing and no gaps.
- **Rendering**: vanilla terrain pipelines and vertex format (shader packs keep working);
  one draw per visible node (a few per boundary node) appended to the section draw list;
  Metal backend binds the LOD atlas as `Sampler0` for those draws. Water uses the
  translucent layer with Standard water metadata.

## Tasks

### Phase 0 — remove prototype code
- [x] Delete old `lod/` package, `horizon/` package and `chunk/Native*` shell/LOD classes
- [x] Remove their mixins, Metal backend hooks (LOD pipelines, pixel-resolution bands)
- [x] Remove old LOD tests, smokes, benchmarks, scripts and Gradle tasks
- [x] Replace settings: `lodEnabled`, `lodNativeDistance`, `lodDetail` (migrate old keys)

### Phase 1 — new LOD core
- [x] Settings, policy and lifecycle (`LodSystem`)
- [x] Block colour table from atlas sprites
- [x] Biome surface/canopy palette
- [x] Density-based terrain sampler (+ generic fallback)
- [x] Client chunk capture store and invalidation
- [x] Tile sampling (captured → generated)
- [x] Heightfield mesher (greedy, skirts, per-chunk ranges, water)
- [x] Quadtree selection with coverage fallback and build scheduling
- [x] GPU residency: vertex buffers, colour atlas, eviction, budget
- [x] Draw emission, translucent ordering, native-chunk exclusion
- [x] Metal backend: LOD atlas binding for LOD draws
- [x] Distance/fog/camera mixins (native loading radius, far plane, fog)
- [x] Settings screen and language strings

### Phase 2 — real terrain for saved chunks
- [x] Read saved FULL chunks from region files (heightmaps + top blocks) before generating

### Phase 3 — verification
- [x] Headless smoke: packing, mesher coverage/watertightness, sampler parity vs vanilla
- [x] Headless sampler throughput benchmark
- [x] Short in-game test at 128 render distance: coverage, handoff, FPS, memory

### Phase 3b — follow-ups found in testing
- [x] Distant models stand in for any native chunk whose surface is not compiled yet (join, teleport, reload)
- [x] Render Distance option extended to 1024 chunks with distant terrain; ordinary loading stays ≤ 256
- [x] `distant_terrain` debug overlay entry (enable in the F3 debug options screen)

### Phase 4 — documentation
- [x] README and evidence for the new system; mark prototype docs as superseded
