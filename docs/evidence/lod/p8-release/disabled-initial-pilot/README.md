# Disabled-overhead pilot before idle refinement

The native 3840×2160 / None / full-scene pair uses NORMAL seed `metalcraft`, Default/Metal and 16/16, with three repeats per route phase. Both processes qualify, but current/off median intervals exceed pre-LOD `f48460d` by **12.61% stationary / 6.18% pan / 8.37% traversal**. p99 changes are +3.41% / −4.91% / +1.65%. This is failed performance evidence, not a passed disabled gate.

Current LOD still initializes an idle loaded renderer and performs per-draw terrain census/bookkeeping. A subsequent refinement disables that work during ordinary off play and makes the census an explicit benchmark option. This pair alone does not establish how much of the measured difference comes from those hooks: separate processes also vary in terrain coverage and scheduling. The next matched comparison tests the refinement. Source patch and both raw reports/invocations are retained; `summary.json` contains median-of-three repeat percentiles.
