# Qualified explored-horizon matrix, 2026-09-12 evening

Command: `caffeinate -di python3 scripts/lod-run-horizon.py --cold-cache --output build/reports/lod-horizon-preview`. The runner removes only the isolated gametest cache before launch. NORMAL seed `metalcraft`, Default/Metal, render/simulation 16/16, M4 Max/64 GB, native 3840×2160, default 2 GiB disk budget, API validation disabled for timing.

All 48 captures and the complete lifecycle pass in **13m13s**. Each Standard/None × full/half × off/on case has three repeats at 128 and 256 chunks. All sampled thermal states are nominal; minimum GPU coverage is 99.853%. Every accepted phase passes continuous presentation checks.

The controlled Finder activation in `focus-probe.json` interrupts the first phase. The harness rejects all 220 captured frames, reacquires focus, waits 20 ticks, and starts a fresh capture with its own 30-frame warm-up. The accepted retry passes. The discarded attempt is recorded in the raw row and excluded from CPU/GPU distributions.

| Horizon | Pack | Scene | Median change | p99 change | Represented sections |
| --- | --- | --- | ---: | ---: | ---: |
| 128 | metalcraft-standard | Full | +21.83% | +2.14% | 1002 |
| 128 | metalcraft-standard | Half | +18.35% | +1.04% | 1002 |
| 128 | none | Full | +6.67% | -3.96% | 1002 |
| 128 | none | Half | +1.98% | -7.72% | 1002 |
| 256 | metalcraft-standard | Full | +20.37% | -0.31% | 995 |
| 256 | metalcraft-standard | Half | +17.55% | +1.27% | 995 |
| 256 | none | Full | +2.16% | -3.56% | 995 |
| 256 | none | Half | +4.23% | -1.00% | 995 |

Every row stays within the provisional +25% median / +35% p99 budget. These are sparse explored patches, not dense circles; 256 moves farther from the same patch. Negative tail changes are timing variability, not a speedup claim. GPU spans increase with the added horizon. Raw samples, repeat ranges, source patch and exact invocation are retained.

From an empty test cache, the designated exploration phase after world creation takes 47.85s, including chunk receipt/meshing and its final 120-tick settle. It excludes initial world creation. Three 1,650-block edits persist in **800.3 / 646.0 / 399.8 ms**. These are measured edit-to-persist waits; worker batch time excludes queue wait and does not block the render thread.

Maximum recorded logical GPU payload high-water is 116.70 MiB, including resources awaiting GPU completion. Cold compressed queue high-water is 9.18 MiB. Recorded per-store write-payload high-water reaches 103.48 MiB; the largest later disk snapshot is 114.17 MiB. They are different measurements: per-store high-water records are sampled before some later cache instances close. Neither includes filesystem metadata/allocation rounding. The longest recorded completed store batch is 1,055.6 ms. Maximum sampled process-lifetime physical footprint is 5.29 GiB. GPU payload is not a driver allocation peak.

All uploads succeed. Same-world re-enable, repeated edit repair, resource reload, dimension isolation/return, persisted reopen, active/inactive global cache clear, and both world closes pass. Both closes and clears leave zero distant GPU bytes. The cold cache and all sampled disk usage stay within their fixed limits.

Inspected Standard/full 128 off/on, 256 on, and edited/reloaded images. The remembered mountain is present when enabled; unseen gaps and omitted distant fluids remain explicit. The repaired gold-block marker survives reload. Four representative images are retained here; `images.json` records all 26 original image hashes and local paths.

The preparation timer in this matrix covers loaded LOD only. A subsequent focused probe adds distant frame maintenance, selection, uniforms and uploads to that measurement; do not call this matrix's preparation percentiles the total LOD cost.

`source.patch` is relative to base commit 8f971658b930711975f02f5145806eeeac9617cd; measured source digest: `2f3f13d25918be924d183d0448ddac8d18df3e484e5b015fdc0e228b7e0183ef`. The later preparation timer and settings-test update are not in this source patch.
