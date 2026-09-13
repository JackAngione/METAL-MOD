# Shared loaded selection graph — 2026-09-12

The loaded renderer now builds reciprocal adjacency once per visible section set
and shares it between upload-admission balancing and final availability resolution.
The graph stores offsets and neighbor indices in contiguous arrays. Each resolution
uses a fixed integer ring with at most one queued entry per node, preserving FIFO
propagation without boxed integers. Graphs are rebuilt each prepare call; no stale
cross-frame topology or tier decisions are retained.

Validation: Apple M4 Max, macOS 27.0, Temurin OpenJDK 25.0.4.

- `MTL_DEBUG_LAYER=1 ./gradlew build` passed in 19 seconds, including the LOD CPU
  and native Metal/shader suites. [Build log](build.txt).
- The CPU smoke suite compares 1,000 random directed graphs, sparse availability,
  invisible nodes and both smoothing settings against an independent fixed-point
  edge-scan solver. The same graph is reused across admission and resolution.
  A 1,024-node chain exercises late refinement and ring wraparound, and confirms
  that mutating source edges cannot change the graph. Empty input also passes.
- `python3 docs/evidence/lod/shared-selection/probe.py` compares the actual prior
  selector at commit `f6b5ae5` with the changed selector. All four fixture outputs
  match for admission and both smoothed/unsmoothed resolution. Timed pairs include
  graph construction and both solver calls. There are 500 paired warmups followed
  by three samples of 500 pairs, alternating order. It runs in an isolated JVM
  after the build completes. [Source](probe.py), [measurements](probe.txt).

Median of three sample means; CPU time is microseconds per admission/resolution pair:

| Fixture | Before µs | After µs | Time reduction | Allocated-byte reduction |
| --- | ---: | ---: | ---: | ---: |
| nodes=256 sparse=false | 11.228 | 7.392 | 34.2% | 69.2% |
| nodes=256 sparse=true | 13.318 | 6.731 | 49.5% | 68.4% |
| nodes=4096 sparse=false | 216.537 | 126.294 | 41.7% | 70.6% |
| nodes=4096 sparse=true | 182.391 | 113.439 | 37.8% | 68.2% |

The short probe includes JVM allocation/GC cost; JIT noise is visible in early
samples. Fixed 256/4,096-node grids with full or sparse availability are synthetic
selection workloads, not game frame-time captures. These results establish reduced
selection CPU/allocation cost with unchanged decisions in the fixtures. They do
not establish in-game FPS, triangle reductions, or GPU savings. No live world or
waived long validation route was run.
