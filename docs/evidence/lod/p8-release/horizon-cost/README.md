# Complete preparation-cost probe

`caffeinate -di python3 scripts/lod-run-horizon.py --cold-cache --cost-probe --output build/reports/lod-horizon-cost` passes in **5m55s**, NORMAL seed `metalcraft`, 16/16 Default/Metal, M4 Max/64 GB, native 3840×2160, Standard/full scene. API validation is disabled for timing and passes separately in the final 19s build.

This focused probe retains three off/on repeats at 128 and 256 chunks (12 captures) and all repeated-edit/lifecycle checks. The timer now includes loaded and distant frame maintenance, selection, uniform writes and CPU upload work; worker execution and GPU rendering remain separate. p95 total preparation is **0.165 ms at 128 / 0.161 ms at 256**, using the median of repeat percentiles. The largest individual enabled-phase p95 is **0.166 ms**, below the 1 ms target.

Both focused frame comparisons remain within the +25% median / +35% p99 reporting budgets. The full Standard/None × full/half matrix is the separate `horizon-preview` artifact; these 12 captures do not replace its 48 cases. Source identity and the patch relative to the invocation's base commit are retained. The source differs from the earlier matrix only in preparation timing/metadata, the focused probe selector and settings-test coverage.
