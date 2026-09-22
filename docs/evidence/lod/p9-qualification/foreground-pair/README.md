# Focused foreground pair, 2026-09-12

Both isolated processes pass: 18 phases, three repeats each of stationary/pan/traversal,
5,999 retained frames and 1,116 continuous presentation checks. M4 Max/64 GB,
macOS 27.0, 3840×2160, None pack, full scene resolution, NORMAL seed `metalcraft`,
16/16 render/simulation distance, Default/Metal, requested unlocked/VSync off.
Both receive all 1,057 tracked chunks and drain light/render queues. Minimum GPU
sample coverage is 99.696%; all sampled thermal states are nominal. Each process
takes about 115 seconds including startup and world preparation. Sources remain
unchanged throughout and match the parent `source.json`/`source.patch`.

Reproduce:

```sh
caffeinate -di python3 scripts/lod-release-matrix.py --phase-ticks 60 \
  --output build/reports/lod-p9-foreground \
  --case native-none-half-false-lod-false \
  --case native-none-half-false-lod-true
python3 docs/evidence/lod/compare-benchmarks.py \
  build/reports/lod-p9-foreground/native-none-half-false-lod-false/metrics.json \
  build/reports/lod-p9-foreground/native-none-half-false-lod-true/metrics.json
```

| Phase | Median interval off → on (ms) | Change | Median CPU change | Distant triangles saved |
| --- | --- | ---: | ---: | ---: |
| Stationary | 6.845 → 7.306 | +6.73% | +15.55% | 0.398% |
| Pan | 6.967 → 7.050 | +1.19% | +12.70% | 0.264% |
| Traversal | 8.176 → 7.013 | −14.22% | +11.01% | 0.297% |

Percentiles are medians of the three repeat percentiles, not pooled samples.
Frame p99 changes are +1.56/+0.65/+3.40%. GPU median span changes are
−0.47/−8.50/−4.96%, but those spans are not exclusive terrain cost.
Average-FPS repeat ranges overlap around 120 FPS in every phase. Traversal off
median intervals vary from 6.893 to 8.430 ms; the one improved median does not
establish an overall net benefit. P9.2 remains open. No 50% triangle or 20%
exclusive terrain-GPU objective is established.

Observed device allocation peaks are 1,275,953,152 bytes off and 1,303,740,416 bytes
on (approximately 1,216.84/1,243.34 MiB). These include allocation/view/drawable
observations, not driver-internal transients or per-LOD attribution. Loaded GPU
upload failures remain zero. This pair has a 16-chunk horizon; it does not qualify
dense distant-terrain driver allocation or close P9.3.

Both scene screenshots were inspected: near snow/rock geometry remains consistent;
distant fog/coverage and player skins differ between processes. These are visual
sanity checks, not pixel parity. The initial visible-section counts differ (871/945),
so input workload differences are a further limit on causal attribution.
Raw reports, images, invocation/source identities and logs are retained beside
`comparison.json` and `telemetry.json`. The earlier focus-rejected runs remain separate.
