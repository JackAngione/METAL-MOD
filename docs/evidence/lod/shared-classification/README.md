# Shared loaded-tier classification — 2026-09-12

The compiler worker classifies final emitted faces once, then constructs tiers 4,
1, 2 and 3 from the same grouping. Each tier copies its merge cells before removing
consumed faces. The original tier-4 profitability, unsupported-geometry and stale
capture exits still run before constructing the other tiers. No classification maps
escape into retained meshes, and admission/retention budgets are unchanged.

Validation on Apple M4 Max, macOS 27.0, Temurin OpenJDK 25.0.4:

- `./gradlew lodSmoke` passed in 4 seconds: tier independence, exact coverage,
  boundary strips, mixed AO/light preservation, duplicate rejection, and existing
  cache/selection/lifecycle fixtures.
- `MTL_DEBUG_LAYER=1 ./gradlew build` passed in 18 seconds, including CPU LOD,
  native Metal/shader, upload and lifetime smoke checks. [Build log](build.txt).
- `python3 docs/evidence/lod/shared-classification/probe.py` passed exact serialized
  output comparison for all four tiers against mesher commit
  `03b6be033ef6900dfa1317664c9439b666357e71`. The probe compiles both implementations
  in a temporary directory, warms each fixture for 100 paired iterations, then
  alternates order over three 100-build samples. Allocation uses the JVM thread
  allocation counter; time includes allocation/GC. [Probe source](probe.py),
  [isolated measurements](probe.txt). The retained run started after the build
  finished; an initial overlapping timing run was discarded.

Medians of the three sample means; time is microseconds per four-tier build:

| Fixture | Before µs | After µs | Time reduction | Allocated-byte reduction |
| --- | ---: | ---: | ---: | ---: |
| quads=256 shaded=false | 267.605 | 107.978 | 59.7% | 62.6% |
| quads=256 shaded=true | 229.421 | 164.510 | 28.3% | 61.1% |
| quads=4096 shaded=false | 4182.546 | 1320.912 | 68.4% | 62.2% |
| quads=4096 shaded=true | 3659.109 | 1183.644 | 67.7% | 61.2% |

These are synthetic CPU construction fixtures (one or sixteen planes with flat or
mixed vertex shading), not generated-world frame measurements. JIT and GC noise
remain visible in short samples. The probe establishes identical fixture output and
reduced worker construction cost, not a triangle reduction, GPU saving, or game FPS
improvement. No live world was launched and no waived long tests were reinstated.
