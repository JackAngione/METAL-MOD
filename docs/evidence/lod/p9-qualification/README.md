# P9 optimization and qualification checkpoint

P9.2 and P9.3 are not completed by this checkpoint. Exact loaded meshes now share
CPU construction and GPU residency across selection tiers, and the new dense cache
probe quantifies repair correctness and coverage under a fixed result budget.

## Loaded implementation

Only flat, appearance-identical emitted unit faces are merged. The maximal merge
retains exact surface positions, original texture orientation, tint/light, and unit
section boundary strips. Consequently, smaller rectangle limits provide no lower
geometric error: all four selection tiers can use the same maximal merge. Selection
still enforces the existing full-detail radius, near-plane safeguards, transitions
and neighbor constraints. Custom/thin/overlapping geometry retains its old fallback.

The compiler builds one merge. Identity-equal tier meshes use one residency key;
selection tier and resource key are separate so transition history and tier counters
remain correct. Different mesh objects never alias just because their counts match.
The conservative retained CPU charge falls from 1,024 to 768 bytes per source quad
plus the existing 4,096-byte per-candidate overhead; the 64 MiB cap is unchanged.

`./gradlew lodSmoke lodMetalSmoke` passes in 7s. `MTL_DEBUG_LAYER=1 ./gradlew build`
passes in 20s on Apple M4 Max/macOS 27.0. This includes exact geometry/appearance,
Metal color/depth comparisons, stale ownership, retirement and shared-tier identity
checks. Build and smoke logs are retained here. The final API-validation build also passes
in 20s after the probe teardown check and setup/metadata refinement; see
[final-build.log](final-build.log). [source.json](source.json) and
[source.patch](source.patch) identify/reconstruct the final code.

## Dense cache and repair stress

Reproduce with `./gradlew lodDenseProbe`; output is
`build/reports/lod-dense-probe.json`. This is a **synthetic** production-store/cache
probe with a manually stepped worker, not a generated-world or GPU benchmark.
The fixture is a contiguous 32 × 32 section patch at section coordinates
x/z = 32…63, outside the ordinary 16-chunk loaded square and inside a 128-chunk
horizon. It uses a 512 MiB disk budget and the unchanged 64 MiB/128-node result caps.

| Quads per section | Input sections | Represented sections | Selected nodes | Selected bytes |
| --- | ---: | ---: | ---: | ---: |
| 1 | 1,024 | 1,024 | 1 | 114,688 |
| 640 | 1,024 | 936 | 60 | 67,092,480 |

The heavier case omits **88 sections (8.594%)**. This deliberately records the
existing budget limit; it is not a successful dense coverage gate. Exact parents
batch rather than simplify distant surfaces, so increasing terrain density can
exhaust the result budget even before the node count is reached.

Each case submits 24 rounds × 32 repairs (768 captures). The next producer round
arrives with the preceding round still queued. Coalescing leaves 400 writes, all
current final revisions persist, and byte-level final payload checks pass after
reopening the store. Both cases have zero drops, failures, corruption or evictions,
and finish with zero queued nodes/bytes. Every published result is checked for
stale versions, overlapping parent/child owners and result bounds.

Producer-plus-worker round medians are 191.40/368.71 ms for the light/heavy cases;
final drains are 157.51/364.84 ms. These include compression, synchronous fixture
submission and a stepped disk-worker batch, so they are neither render-thread cost
nor concurrent live repair latency. Raw round times and resource diagnostics are
in [dense-probe.json](dense-probe.json); source input hashes are in
[dense-inputs.json](dense-inputs.json).

## Driver allocation observations

The opt-in native probe samples `MTLDevice.currentAllocatedSize` after buffer,
texture/view and drawable creation/acquisition, plus explicit report reads. Atomic
high-water accounting includes observed transient allocations after their logical
release. A native test allocates/releases an 8 MiB buffer and checks the retained
peak. Benchmark environments start/reset the device-wide probe and stop it on exit.

Reports distinguish current allocation, observed peak and sample count. This is
actual driver-reported allocation accounting, not the sum of LOD payload charges.
It is **not** an absolute trace of driver-internal allocations between observations,
and not physical residency or a per-LOD allocation attribution.

## Outstanding acceptance

A qualified loaded off/on comparison must demonstrate a net frame-time benefit;
construction sharing and synthetic results cannot establish it. Dense explored
NORMAL-world coverage, live sustained repair latency, and driver observations during
that dense workload remain unqualified. Other Apple Silicon hardware is untested.
Both focused off-run attempts lost foreground focus during the first stationary
phase and were rejected (`AppKit state=14, focused=0`); no on-run was launched and
no A/B timings qualify. The attempts lasted 87/84 seconds including startup and
NORMAL-world preparation. Their invocations, source patches and failure logs remain
in [focus-rejected](focus-rejected) and [focus-rejected-retry](focus-rejected-retry).
At the user-authorized foreground retry, both processes pass all 18 phases in
about 230 seconds total. The result is mixed: stationary/pan median intervals
regress 6.73/1.19%, traversal improves 14.22%, CPU work rises in all phases, and
FPS repeat ranges overlap. P9.2 remains open. Observed driver peaks are
1,275,953,152/1,303,740,416 bytes off/on. This loaded-only pair does not qualify
the dense horizon workload for P9.3. [Qualified foreground evidence](foreground-pair/README.md).
